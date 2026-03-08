package com.metrolist.shared.playback

import com.metrolist.shared.playback.ffmpeg.DirectPlaybackCache
import com.metrolist.shared.playback.ffmpeg.HttpRangeInputStream
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

const val PLAYBACK_PRE_CACHE_DELAY_MS = 5_000L
const val PLAYBACK_PRE_CACHE_BYTES = 1_048_576L

object PlaybackPreCache {
    suspend fun prefetch(
        resource: PlaybackResource,
        directory: File,
        budgetBytes: Long,
    ) {
        if (!resource.isDirectFile || !resource.url.startsWith("https://")) return
        val mediaSize = resource.contentLengthBytes?.takeIf { it > 0L } ?: return
        val capture = DirectPlaybackCache.begin(resource, directory, budgetBytes, prefetch = true) ?: return
        val stream =
            try {
                HttpRangeInputStream(
                    url = resource.url,
                    requestHeaders = resource.headers,
                    mediaSize = mediaSize,
                    chunkSize = PLAYBACK_PRE_CACHE_BYTES.toInt(),
                    initialChunkSize = PLAYBACK_PRE_CACHE_BYTES.toInt(),
                    cacheCapture = capture,
                    cacheCapturePrefetch = true,
                )
            } catch (error: Exception) {
                capture.discard()
                throw error
            }
        try {
            val buffer = ByteArray(64 * 1024)
            var remaining = minOf(PLAYBACK_PRE_CACHE_BYTES, mediaSize)
            while (remaining > 0L) {
                currentCoroutineContext().ensureActive()
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                remaining -= read
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        } finally {
            stream.close()
        }
    }
}
