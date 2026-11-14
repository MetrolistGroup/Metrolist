package com.metrolist.shared.playback

import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.SabrResponseDiagnostics
import com.metrolist.shared.youtube.playback.PlaybackResource
import io.ktor.client.HttpClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

@OptIn(ExperimentalSabrApi::class)
internal suspend fun fetchSabrAudio(
    client: HttpClient,
    resource: PlaybackResource,
    maxBytes: Long,
    onResponse: ((SabrResponseDiagnostics) -> Unit)? = null,
    write: suspend (Long, ByteArray, Int) -> Unit,
): Long {
    val bootstrap = requireNotNull(resource.sabrBootstrap)
    return withTimeoutOrNull(IOS_SABR_PREPARATION_TIMEOUT_MS) {
        collectSabrAudio(
            SabrAudioStream(
                client,
                bootstrap,
                maxRequestsWithoutProgress = 2,
                tokenSupplier = resource.sabrTokenSupplier,
                onResponse = onResponse,
            ).chunks(),
            bootstrap.contentLengthBytes ?: resource.contentLengthBytes,
            maxBytes,
            expectedDurationMs = bootstrap.durationMs,
            write = write,
        )
    } ?: throw IncompleteAudioDownloadException("SABR audio preparation timed out")
}

internal suspend fun collectSabrAudio(
    chunks: Flow<SabrChunk>,
    contentLength: Long?,
    maxBytes: Long,
    expectedDurationMs: Long? = null,
    awaitReady: suspend () -> Unit = {},
    onProgress: (Long, Long?) -> Unit = { _, _ -> },
    write: suspend (Long, ByteArray, Int) -> Unit,
): Long {
    if (contentLength != null && contentLength !in 1..maxBytes) {
        throw IncompleteAudioDownloadException("Invalid SABR audio size")
    }
    var written = 0L
    var endMs = 0L
    chunks.collect { chunk ->
        currentCoroutineContext().ensureActive()
        awaitReady()
        if (chunk.startRange != written || chunk.endRangeExclusive > minOf(contentLength ?: maxBytes, maxBytes)) {
            throw IncompleteAudioDownloadException("SABR audio has an invalid byte range")
        }
        write(written, chunk.data, chunk.data.size)
        written = chunk.endRangeExclusive
        val start = chunk.startMs
        val duration = chunk.durationMs
        if (start != null && duration != null && start >= 0 && duration >= 0 && start <= Long.MAX_VALUE - duration) {
            endMs = maxOf(endMs, start + duration)
        }
        onProgress(written, contentLength)
    }
    currentCoroutineContext().ensureActive()
    awaitReady()
    if (written < 1024L || (contentLength != null && written != contentLength) ||
        (
            expectedDurationMs != null && (
                expectedDurationMs <= 0 || abs(
                    endMs - expectedDurationMs,
                ) > maxOf(2000L, expectedDurationMs / 100)
            )
        )
    ) {
        throw IncompleteAudioDownloadException("SABR audio ended before the complete track was downloaded")
    }
    return written
}
