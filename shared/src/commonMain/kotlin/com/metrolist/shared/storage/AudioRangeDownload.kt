package com.metrolist.shared.storage

import com.metrolist.shared.playback.PlaybackHttpStatusCarrier
import com.metrolist.shared.youtube.playback.PlaybackResource
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val PARALLEL_AUDIO_RANGES = 4
internal const val AUDIO_RANGE_BYTES = 1_048_576L
private val AUDIO_CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

internal suspend fun downloadAudioWithRanges(
    client: HttpClient,
    resource: PlaybackResource,
    maxBytes: Long,
    write: (offset: Long, bytes: ByteArray, length: Int) -> Unit,
): Pair<String?, Long> {
    require(resource.isDirectFile && isTrustedAudioDownloadUrl(resource.url)) { "Audio resource is not a trusted media file" }
    val chunkSize = resource.rangeChunkSizeBytes.coerceIn(1L, AUDIO_RANGE_BYTES)
    val writerMutex = Mutex()

    suspend fun fetch(
        start: Long,
        totalBytes: Long?,
    ): AudioRangeResult =
        fetchAudioRange(client, resource, start, totalBytes, maxBytes) { offset, bytes, length ->
            writerMutex.withLock { write(offset, bytes, length) }
        }

    val expectedBytes = resource.contentLengthBytes?.takeIf { it > 0L }
    check(
        expectedBytes == null || expectedBytes in MINIMUM_AUDIO_BYTES..maxBytes,
    ) { "Audio file exceeds available storage or is too small" }
    val first = fetch(0L, expectedBytes)
    var written = first.received
    if (first.partial) {
        coroutineScope {
            while (written < first.total) {
                val starts =
                    buildList {
                        var next = written
                        repeat(PARALLEL_AUDIO_RANGES) {
                            if (next < first.total) {
                                add(next)
                                next += minOf(chunkSize, first.total - next)
                            }
                        }
                    }
                written += starts.map { start -> async { fetch(start, first.total).received } }.awaitAll().sum()
            }
        }
    }
    check(isCompleteAudioDownload(written, first.total)) { "Downloaded audio is incomplete or too small" }
    return first.mimeType to written
}

internal suspend fun fetchAudioRange(
    client: HttpClient,
    resource: PlaybackResource,
    start: Long,
    totalBytes: Long?,
    maxBytes: Long,
    onResponse: suspend (String?, Long?, Boolean) -> Unit = { _, _, _ -> },
    write: suspend (Long, ByteArray, Int) -> Unit,
): AudioRangeResult {
    require(resource.isDirectFile && isTrustedAudioDownloadUrl(resource.url)) { "Audio resource is not a trusted media file" }
    val chunkSize = resource.rangeChunkSizeBytes.coerceIn(1L, AUDIO_RANGE_BYTES)
    val end = start + minOf(chunkSize, (totalBytes ?: Long.MAX_VALUE) - start) - 1L
    var url = resource.url
    var redirects = 0
    while (true) {
        var redirect: String? = null
        val result =
            client
                .prepareGet(url) {
                    if (redirects == 0) resource.headers.forEach { (name, value) -> headers.append(name, value) }
                    headers[HttpHeaders.Range] = "bytes=$start-$end"
                    headers[HttpHeaders.AcceptEncoding] = "identity"
                }.execute { response ->
                    val channel = response.bodyAsChannel()
                    try {
                        if (response.status.value in 300..399) {
                            val location = response.headers[HttpHeaders.Location] ?: error("Audio redirect location is missing")
                            redirect = URLBuilder(url).takeFrom(location).buildString()
                            return@execute null
                        }
                        val partial = response.status == HttpStatusCode.PartialContent
                        if (response.status.value >= 400) throw AudioRangeHttpStatusException(response.status.value)
                        check(partial || (start == 0L && response.status == HttpStatusCode.OK)) {
                            "Audio request failed with HTTP ${response.status.value}"
                        }
                        val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                        val responseBytes: Long?
                        val size: Long?
                        if (partial) {
                            val range = AUDIO_CONTENT_RANGE.matchEntire(response.headers[HttpHeaders.ContentRange].orEmpty())
                            checkNotNull(range) { "Missing or invalid audio Content-Range" }
                            val rangeStart = range.groupValues[1].toLongOrNull()
                            val rangeEnd = range.groupValues[2].toLongOrNull()
                            size = range.groupValues[3].toLongOrNull()
                            check(size != null && size > start && (totalBytes == null || size == totalBytes)) {
                                "Audio range total does not match the resource"
                            }
                            check(rangeStart == start && rangeEnd == minOf(end, size - 1L)) { "Audio range does not match the request" }
                            responseBytes = rangeEnd - start + 1L
                            check(contentLength == null || contentLength == responseBytes) { "Audio range length does not match" }
                        } else {
                            check(totalBytes == null || contentLength == null || totalBytes == contentLength) {
                                "Audio length does not match the resource"
                            }
                            size = totalBytes ?: contentLength
                            responseBytes = size
                        }
                        check(
                            size == null || size in MINIMUM_AUDIO_BYTES..maxBytes,
                        ) { "Audio file exceeds available storage or is too small" }
                        val mimeType =
                            response.headers[HttpHeaders.ContentType]
                                ?.substringBefore(';')
                                ?.trim()
                                ?.lowercase()
                                ?.takeIf(String::isNotBlank)
                        onResponse(mimeType, size, partial)
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = channel.readAvailable(buffer, 0, buffer.size)
                            if (count < 0) break
                            if (count == 0) continue
                            check(canWriteAudioChunk(received, count.toLong(), responseBytes ?: maxBytes, 0L)) {
                                "Audio response exceeds expected size"
                            }
                            write(start + received, buffer, count)
                            received += count
                        }
                        check(responseBytes == null || received == responseBytes) { "Downloaded audio is incomplete" }
                        AudioRangeResult(mimeType, size ?: received, received, partial)
                    } finally {
                        channel.cancel(null)
                    }
                }
        val target = redirect ?: return requireNotNull(result)
        check(canFollowAudioDownloadRedirect(redirects, target)) { "Audio redirect is untrusted or exceeded the redirect limit" }
        redirects++
        url = target
    }
}

internal data class AudioRangeResult(
    val mimeType: String?,
    val total: Long,
    val received: Long,
    val partial: Boolean,
)

internal class AudioRangeHttpStatusException(
    override val playbackHttpStatusCode: Int,
) : IllegalStateException("Audio request failed with HTTP $playbackHttpStatusCode"),
    PlaybackHttpStatusCarrier
