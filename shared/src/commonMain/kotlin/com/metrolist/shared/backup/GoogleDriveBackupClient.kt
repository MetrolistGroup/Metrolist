package com.metrolist.shared.backup

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
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GoogleDriveBackupClient(
    brokerUrl: String,
    private val httpClient: HttpClient = createHttpClient().config { followRedirects = false },
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
        val accessToken = broker.accessToken(sessionToken).accessToken
        val folderId = getOrCreateBackupFolder(accessToken)
        val metadata =
            DriveCreateMetadata(
                name = filename,
                mimeType = "application/zip",
                parents = listOf(folderId),
                appProperties = mapOf(APP_PROPERTY_KEY to "true"),
            )
        val uploadUrl =
            httpClient
                .post(DRIVE_UPLOAD_URL) {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    header(UPLOAD_CONTENT_TYPE_HEADER, ContentType.Application.Zip.toString())
                    header(UPLOAD_CONTENT_LENGTH_HEADER, archiveSize.toString())
                    parameter("uploadType", "resumable")
                    parameter("fields", "id,name,modifiedTime")
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(metadata))
                }.requireDriveApiSuccess()
                .headers[HttpHeaders.Location]
                ?.also(::requireSafeUploadUrl)
                ?: error("Drive did not provide a resumable upload URL")
        val uploaded = uploadArchive(accessToken, uploadUrl, archiveSize, archiveSource)
        val cleanupError = runCatching { pruneOldBackups(accessToken, folderId) }.exceptionOrNull()
        if (cleanupError != null) {
            if (cleanupError is CancellationException) throw cleanupError
            Logger.w("GoogleDriveBackup", "Could not prune expired Drive backups", cleanupError)
            throw DriveCleanupException(listOf(cleanupError))
        }
        return uploaded
    }

    suspend fun cleanupBackups(sessionToken: String) {
        val accessToken = broker.accessToken(sessionToken).accessToken
        val folderId = findBackupFolder(accessToken) ?: return
        pruneOldBackups(accessToken, folderId)
    }

    private suspend fun uploadArchive(
        accessToken: String,
        uploadUrl: String,
        archiveSize: Long,
        archiveSource: () -> RawSource,
    ): DriveBackupFile {
        var offset = 0L
        var retries = 0
        while (offset < archiveSize) {
            val response =
                try {
                    httpClient.put(uploadUrl) {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header(HttpHeaders.ContentRange, "bytes $offset-${archiveSize - 1}/$archiveSize")
                        contentType(ContentType.Application.Zip)
                        timeout { requestTimeoutMillis = UPLOAD_TIMEOUT_MILLIS }
                        setBody(ArchiveChunkContent(archiveSize - offset, archiveSource, offset))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (retries++ >= MAX_UPLOAD_RETRIES) throw error
                    val progress = queryUploadOffset(accessToken, uploadUrl, archiveSize)
                    progress.completed?.let { return it }
                    if (progress.offset > offset) retries = 0
                    offset = progress.offset
                    continue
                }
            if (response.status in setOf(HttpStatusCode.OK, HttpStatusCode.Created)) return response.body()
            if (response.status != HttpStatusCode.PermanentRedirect) throw DriveBrokerException(response.status.value)
            val acknowledged = acknowledgedOffset(response.headers[HttpHeaders.Range], archiveSize)
            if (acknowledged <= offset && retries++ >= MAX_UPLOAD_RETRIES) throw DriveBrokerException(response.status.value)
            if (acknowledged > offset) retries = 0
            offset = acknowledged
        }
        error("Drive upload ended without a completion response")
    }

    private suspend fun queryUploadOffset(
        accessToken: String,
        uploadUrl: String,
        archiveSize: Long,
    ): UploadProgress {
        val response =
            httpClient.put(uploadUrl) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header(HttpHeaders.ContentRange, "bytes */$archiveSize")
                setBody(ByteArray(0))
            }
        if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created) {
            return UploadProgress(archiveSize, response.body())
        }
        if (response.status != HttpStatusCode.PermanentRedirect) throw DriveBrokerException(response.status.value)
        return UploadProgress(acknowledgedOffset(response.headers[HttpHeaders.Range], archiveSize), null)
    }

    private fun acknowledgedOffset(
        range: String?,
        archiveSize: Long,
    ): Long {
        val lastByte = range?.substringAfterLast('-', "")?.toLongOrNull() ?: return 0
        return (lastByte + 1).coerceAtMost(archiveSize)
    }

    private suspend fun findBackupFolder(accessToken: String): String? =
        httpClient
            .get("https://www.googleapis.com/drive/v3/files") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                parameter(
                    "q",
                    "name = '$BACKUP_FOLDER_NAME' and mimeType = '$FOLDER_MIME_TYPE' and trashed = false",
                )
                parameter("fields", "files(id)")
                parameter("pageSize", "10")
            }.requireDriveApiSuccess()
            .body<DriveFileList>()
            .files
            .firstOrNull()
            ?.id

    private suspend fun getOrCreateBackupFolder(accessToken: String): String {
        val existing = findBackupFolder(accessToken)
        if (existing != null) return existing
        return httpClient
            .post("https://www.googleapis.com/drive/v3/files") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                parameter("fields", "id")
                setBody(DriveCreateMetadata(BACKUP_FOLDER_NAME, FOLDER_MIME_TYPE))
            }.requireDriveApiSuccess()
            .body<DriveBackupFile>()
            .id
    }

    private suspend fun pruneOldBackups(
        accessToken: String,
        folderId: String,
    ) {
        val files = mutableListOf<DriveBackupFile>()
        var pageToken: String? = null
        do {
            val page =
                httpClient
                    .get("https://www.googleapis.com/drive/v3/files") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        parameter(
                            "q",
                            "'$folderId' in parents and appProperties has { key='$APP_PROPERTY_KEY' and value='true' } and trashed = false",
                        )
                        parameter("orderBy", "modifiedTime desc")
                        parameter("fields", "nextPageToken,files(id,name,modifiedTime)")
                        parameter("pageSize", "100")
                        pageToken?.let { parameter("pageToken", it) }
                    }.requireDriveApiSuccess()
                    .body<DriveFileList>()
            files += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        val failures = mutableListOf<Throwable>()
        files.drop(MAX_RETAINED_BACKUPS).forEach { file ->
            try {
                httpClient
                    .delete("https://www.googleapis.com/drive/v3/files/${file.id}") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                    }.requireDriveApiSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
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
        const val BACKUP_FOLDER_NAME = "Metrolist Backups"
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        const val APP_PROPERTY_KEY = "metrolistBackup"
        const val MAX_RETAINED_BACKUPS = 10
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
