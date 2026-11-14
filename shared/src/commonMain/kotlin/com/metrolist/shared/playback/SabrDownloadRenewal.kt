package com.metrolist.shared.playback

import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.io.IOException

internal fun renewingSabrDownload(
    resource: PlaybackResource,
    openStream: (PlaybackResource, Long) -> Flow<SabrChunk>,
    renew: suspend (PlaybackResource) -> PlaybackResource,
): Flow<SabrChunk> =
    flow {
        var current = resource
        var written = 0L
        var positionMs = 0L
        var renewed = false
        while (true) {
            val sessionStartMs = positionMs
            var needsRenewal = false
            openStream(current, sessionStartMs)
                .transformWhile { chunk ->
                    if (chunk.startRange > written || (!renewed && chunk.startRange != written)) {
                        throw IncompleteAudioDownloadException("SABR download has a gap or overlapping bytes")
                    }
                    if (chunk.endRangeExclusive > written) {
                        // Renewed sessions repeat initialization and may repeat the boundary segment.
                        val overlap = (written - chunk.startRange).toInt()
                        val remaining =
                            if (overlap == 0) {
                                chunk
                            } else {
                                chunk.copy(
                                    data = chunk.data.copyOfRange(overlap, chunk.data.size),
                                    startRange = written,
                                )
                            }
                        emit(remaining)
                        written = chunk.endRangeExclusive
                        val start = chunk.startMs
                        val duration = chunk.durationMs
                        if (start != null && duration != null && start >= 0L && duration >= 0L && start <= Long.MAX_VALUE - duration) {
                            positionMs = maxOf(positionMs, start + duration)
                        }
                    }
                    val bootstrap = checkNotNull(current.sabrBootstrap)
                    needsRenewal = TokenlessStreamRenewal.needsRenewal(current) &&
                        positionMs - sessionStartMs >= TokenlessStreamRenewal.RENEW_AFTER_MEDIA_MS &&
                        positionMs < bootstrap.durationMs &&
                        written != (bootstrap.contentLengthBytes ?: current.contentLengthBytes)
                    !needsRenewal
                }.collect { emit(it) }
            if (!needsRenewal) break
            val fresh = renew(current)
            if (!current.hasSameSabrAudio(fresh)) {
                throw IncompleteAudioDownloadException("Renewed SABR download changed audio representation")
            }
            current = fresh
            renewed = true
        }
    }

class IncompleteAudioDownloadException(
    message: String,
) : IOException(message)
