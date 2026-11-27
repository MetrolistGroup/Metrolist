package com.metrolist.shared.backup

import com.metrolist.shared.api.parseRetryAfter
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.google_drive_backup_folder
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.utils.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock

class GoogleDriveBackupClient(
    brokerUrl: String,
    private val httpClient: HttpClient = createHttpClient(followRedirects = false),
) {
    private val broker = DriveTokenBrokerClient(brokerUrl, httpClient)

    fun close() = httpClient.close()

    suspend fun uploadBackup(
        sessionToken: String,
        filename: String,
        archiveSize: Long,
        archiveSource: () -> RawSource,
    ): DriveBackupFile {
        require(filename.isNotBlank() && filename.none { it == '/' || it == '\\' })
        require(archiveSize > 0)
        val tokens = DriveAccessTokenProvider(broker, sessionToken)
        val folderIds = getOrCreateBackupFolders(tokens)
        val metadata =
            DriveCreateMetadata(
                name = filename,
                mimeType = "application/zip",
                parents = listOf(folderIds.first()),
                appProperties = mapOf(APP_PROPERTY_KEY to "true"),
            )
        val uploadUrl =
            authenticatedRequest(tokens) { token ->
                httpClient
                    .post(DRIVE_UPLOAD_URL) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        header(UPLOAD_CONTENT_TYPE_HEADER, ContentType.Application.Zip.toString())
                        header(UPLOAD_CONTENT_LENGTH_HEADER, archiveSize.toString())
                        parameter("uploadType", "resumable")
                        parameter("fields", "id,name,modifiedTime")
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(metadata))
                    }
            }.requireDriveApiSuccess()
                .headers[HttpHeaders.Location]
                ?.also(::requireSafeUploadUrl)
                ?: error("Drive did not provide a resumable upload URL")
        val uploaded = uploadArchive(tokens, uploadUrl, archiveSize, archiveSource)
        val cleanupError = runCatching { pruneOldBackups(tokens, folderIds) }.exceptionOrNull()
        if (cleanupError != null) {
            if (cleanupError is CancellationException) throw cleanupError
            if (cleanupError is DriveReauthorizationRequiredException) throw cleanupError
            Logger.w("GoogleDriveBackup", "Could not prune expired Drive backups", cleanupError)
            throw DriveCleanupException(listOf(cleanupError))
        }
        return uploaded
    }

    suspend fun cleanupBackups(sessionToken: String) {
        val tokens = DriveAccessTokenProvider(broker, sessionToken)
        val folderIds = findBackupFolders(tokens)
        if (folderIds.isEmpty()) return
        pruneOldBackups(tokens, folderIds)
    }

    private suspend fun uploadArchive(
        tokens: DriveAccessTokenProvider,
        uploadUrl: String,
        archiveSize: Long,
        archiveSource: () -> RawSource,
    ): DriveBackupFile {
        var offset = 0L
        var retries = 0

        suspend fun recoverUpload(failure: Throwable): UploadProgress {
            if (retries++ >= MAX_UPLOAD_RETRIES) throw failure
            val progress = queryUploadOffset(tokens, uploadUrl, archiveSize)
            if (progress.offset > offset) retries = 0
            offset = progress.offset
            return progress
        }

        while (offset < archiveSize) {
            val response =
                try {
                    authenticatedRequest(tokens) { token ->
                        httpClient.put(uploadUrl) {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            header(HttpHeaders.ContentRange, "bytes $offset-${archiveSize - 1}/$archiveSize")
                            contentType(ContentType.Application.Zip)
                            timeout { requestTimeoutMillis = UPLOAD_TIMEOUT_MILLIS }
                            setBody(ArchiveChunkContent(archiveSize - offset, archiveSource, offset))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val progress = recoverUpload(error)
                    progress.completed?.let { return it }
                    continue
                }
            if (response.status in setOf(HttpStatusCode.OK, HttpStatusCode.Created)) return response.body()
            if (response.status == HttpStatusCode.PermanentRedirect) {
                val acknowledged = acknowledgedOffset(response.headers[HttpHeaders.Range], archiveSize)
                if (acknowledged <= offset && retries++ >= MAX_UPLOAD_RETRIES) throw DriveBrokerException(response.status.value)
                if (acknowledged > offset) retries = 0
                offset = acknowledged
                continue
            }
            if (isRetryableFailure(response)) {
                if (response.status == HttpStatusCode.TooManyRequests || response.status == HttpStatusCode.Forbidden) waitForRetry(response)
                val progress = recoverUpload(DriveBrokerException(response.status.value))
                progress.completed?.let { return it }
                continue
            }
            throw DriveBrokerException(response.status.value)
        }
        error("Drive upload ended without a completion response")
    }

    private suspend fun queryUploadOffset(
        tokens: DriveAccessTokenProvider,
        uploadUrl: String,
        archiveSize: Long,
    ): UploadProgress {
        var attempt = 0
        while (true) {
            val response =
                try {
                    authenticatedRequest(tokens) { token ->
                        httpClient.put(uploadUrl) {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            header(HttpHeaders.ContentRange, "bytes */$archiveSize")
                            setBody(ByteArray(0))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (attempt++ >= MAX_UPLOAD_RETRIES) throw error
                    delay(STATUS_PROBE_RETRY_DELAY_MILLIS * attempt)
                    continue
                }
            if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created) {
                return UploadProgress(archiveSize, response.body())
            }
            if (response.status == HttpStatusCode.PermanentRedirect) {
                return UploadProgress(acknowledgedOffset(response.headers[HttpHeaders.Range], archiveSize), null)
            }
            if (!isRetryableFailure(response) || attempt++ >= MAX_UPLOAD_RETRIES) {
                throw DriveBrokerException(response.status.value)
            }
            if (response.status == HttpStatusCode.TooManyRequests || response.status == HttpStatusCode.Forbidden) {
                waitForRetry(response)
            } else {
                delay(STATUS_PROBE_RETRY_DELAY_MILLIS * attempt)
            }
        }
    }

    private suspend fun authenticatedRequest(
        tokens: DriveAccessTokenProvider,
        request: suspend (String) -> HttpResponse,
    ): HttpResponse {
        val firstToken = tokens.current()
        val firstResponse = request(firstToken)
        if (firstResponse.status != HttpStatusCode.Unauthorized) return firstResponse
        val refreshed = tokens.refresh()
        // The broker maps confirmed credential loss to a reauthorization error; an unchanged
        // token means the 401 was not an expiry, so let the caller handle the original response.
        if (refreshed == firstToken) return firstResponse
        return request(refreshed)
    }

    private suspend fun waitForRetry(response: HttpResponse) {
        val retryAfterMillis = response.headers[HttpHeaders.RetryAfter]?.let(::parseRetryAfter) ?: return
        if (retryAfterMillis > 0) delay(retryAfterMillis)
    }

    private suspend fun isRetryableFailure(response: HttpResponse): Boolean {
        if (response.status == HttpStatusCode.TooManyRequests || response.status.value in 500..599) return true
        if (response.status != HttpStatusCode.Forbidden) return false
        val body = runCatching { response.bodyAsText() }.getOrNull() ?: return false
        return RATE_LIMIT_REASONS.any { reason -> body.contains(reason) }
    }

    private fun acknowledgedOffset(
        range: String?,
        archiveSize: Long,
    ): Long {
        val lastByte = range?.substringAfterLast('-', "")?.toLongOrNull() ?: return 0
        return (lastByte + 1).coerceAtMost(archiveSize)
    }

    private suspend fun findBackupFolders(tokens: DriveAccessTokenProvider): List<String> {
        val folderIds = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val page =
                authenticatedRequest(tokens) { token ->
                    httpClient
                        .get("https://www.googleapis.com/drive/v3/files") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            parameter(
                                "q",
                                "appProperties has { key='$FOLDER_PROPERTY_KEY' and value='true' } and mimeType = '$FOLDER_MIME_TYPE' and trashed = false",
                            )
                            parameter("fields", "nextPageToken,files(id)")
                            parameter("pageSize", "100")
                            pageToken?.let { parameter("pageToken", it) }
                        }
                }.requireDriveApiSuccess()
                    .body<DriveFileList>()
            folderIds += page.files.map { it.id }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return folderIds.distinct()
    }

    private suspend fun getOrCreateBackupFolders(tokens: DriveAccessTokenProvider): List<String> {
        val existing = findBackupFolders(tokens)
        if (existing.isNotEmpty()) return existing
        val folderName = getString(Res.string.google_drive_backup_folder)
        val created =
            authenticatedRequest(tokens) { token ->
                httpClient
                    .post("https://www.googleapis.com/drive/v3/files") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        parameter("fields", "id")
                        setBody(
                            DriveCreateMetadata(
                                folderName,
                                FOLDER_MIME_TYPE,
                                appProperties = mapOf(FOLDER_PROPERTY_KEY to "true"),
                            ),
                        )
                    }
            }.requireDriveApiSuccess()
                .body<DriveBackupFile>()
                .id
        // Concurrent first backups can create several folders. Drive allows one parent per file, but
        // retention cleanup searches every matching folder.
        return (existing + created + findBackupFolders(tokens)).distinct()
    }

    private suspend fun pruneOldBackups(
        tokens: DriveAccessTokenProvider,
        folderIds: List<String>,
    ) {
        val parentQuery = folderIds.joinToString(" or ") { "'$it' in parents" }
        val files = mutableListOf<DriveBackupFile>()
        var pageToken: String? = null
        do {
            val page =
                authenticatedRequest(tokens) { token ->
                    httpClient
                        .get("https://www.googleapis.com/drive/v3/files") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            parameter(
                                "q",
                                "($parentQuery) and appProperties has { key='$APP_PROPERTY_KEY' and value='true' } and trashed = false",
                            )
                            parameter("orderBy", "modifiedTime desc")
                            parameter("fields", "nextPageToken,files(id,name,modifiedTime)")
                            parameter("pageSize", "100")
                            pageToken?.let { parameter("pageToken", it) }
                        }
                }.requireDriveApiSuccess()
                    .body<DriveFileList>()
            files += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        val failures = mutableListOf<Throwable>()
        files
            .distinctBy { it.id }
            .drop(MAX_RETAINED_BACKUPS)
            .forEach { file ->
                try {
                    authenticatedRequest(tokens) { token ->
                        httpClient
                            .delete("https://www.googleapis.com/drive/v3/files/${file.id}") {
                                header(HttpHeaders.Authorization, "Bearer $token")
                            }
                    }.requireDriveApiSuccess()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (reauthorization: DriveReauthorizationRequiredException) {
                    throw reauthorization
                } catch (error: Exception) {
                    Logger.w("GoogleDriveBackup", "Could not delete expired backup ${file.id}", error)
                    failures += error
                }
            }
        if (failures.isNotEmpty()) throw DriveCleanupException(failures)
    }

    private companion object {
        const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        const val UPLOAD_CONTENT_TYPE_HEADER = "X-Upload-Content-Type"
        const val UPLOAD_CONTENT_LENGTH_HEADER = "X-Upload-Content-Length"
        const val UPLOAD_TIMEOUT_MILLIS = 15 * 60 * 1000L
        const val MAX_UPLOAD_RETRIES = 3
        const val STATUS_PROBE_RETRY_DELAY_MILLIS = 1_000L
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        const val FOLDER_PROPERTY_KEY = "metrolistBackupFolder"
        const val APP_PROPERTY_KEY = "metrolistBackup"
        const val MAX_RETAINED_BACKUPS = 10
        val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded")
    }
}

/** Keeps a resumable upload on the same session even when the broker token expires mid-transfer. */
private class DriveAccessTokenProvider(
    private val broker: DriveTokenBrokerClient,
    private val sessionToken: String,
) {
    private var accessToken: String? = null
    private var expiresAtMillis = 0L

    suspend fun current(): String = accessToken?.takeIf { expiresAtMillis > Clock.System.now().toEpochMilliseconds() } ?: refresh()

    suspend fun refresh(): String {
        val token = broker.accessToken(sessionToken)
        accessToken = token.accessToken
        expiresAtMillis =
            Clock.System.now().toEpochMilliseconds() +
            (token.expiresIn - TOKEN_REFRESH_MARGIN_SECONDS).coerceAtLeast(0) * 1000
        return token.accessToken
    }

    private companion object {
        const val TOKEN_REFRESH_MARGIN_SECONDS = 60L
    }
}

class DriveCleanupException(
    val failures: List<Throwable>,
) : Exception("Could not remove expired Drive backups")

private fun requireSafeUploadUrl(value: String) {
    val parsed = runCatching { Url(value) }.getOrNull()
    val uploadId = parsed?.parameters?.get("upload_id")
    require(
        parsed != null &&
            parsed.protocol.name == "https" &&
            parsed.host == "www.googleapis.com" &&
            parsed.encodedPath == "/upload/drive/v3/files" &&
            !uploadId.isNullOrBlank(),
    ) { "Drive returned an invalid resumable upload URL" }
}

private data class UploadProgress(
    val offset: Long,
    val completed: DriveBackupFile?,
)

private class ArchiveChunkContent(
    private val byteCount: Long,
    private val sourceFactory: () -> RawSource,
    private val startOffset: Long,
) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType = ContentType.Application.Zip
    override val contentLength: Long = byteCount

    override suspend fun writeTo(channel: ByteWriteChannel) {
        val source = sourceFactory()
        val bufferedSource = Buffer()
        try {
            var skipped = 0L
            while (skipped < startOffset) {
                val read = source.readAtMostTo(bufferedSource, minOf(UPLOAD_BUFFER_SIZE.toLong(), startOffset - skipped))
                check(read > 0) { "Backup archive ended before its recorded size" }
                bufferedSource.skip(read)
                skipped += read
            }
            val buffer = ByteArray(UPLOAD_BUFFER_SIZE)
            var remaining = byteCount
            while (remaining > 0) {
                if (bufferedSource.exhausted()) {
                    val count = source.readAtMostTo(bufferedSource, minOf(UPLOAD_BUFFER_SIZE.toLong(), remaining))
                    check(count > 0) { "Backup archive ended before its recorded size" }
                }
                val read = bufferedSource.readAtMostTo(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(read > 0) { "Backup archive ended before its recorded size" }
                channel.writeFully(buffer, 0, read)
                remaining -= read
            }
        } finally {
            source.close()
        }
    }

    private companion object {
        const val UPLOAD_BUFFER_SIZE = 64 * 1024
    }
}

private suspend fun HttpResponse.requireDriveApiSuccess(): HttpResponse =
    also {
        if (status.value !in 200..299) throw DriveBrokerException(status.value)
    }

@Serializable
data class DriveBackupFile(
    val id: String,
    val name: String? = null,
    val modifiedTime: String? = null,
)

@Serializable
private data class DriveFileList(
    val files: List<DriveBackupFile> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
private data class DriveCreateMetadata(
    val name: String,
    val mimeType: String,
    val parents: List<String> = emptyList(),
    val appProperties: Map<String, String> = emptyMap(),
)
