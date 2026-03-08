package com.metrolist.shared.playback

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.shared.youtube.playback.PlaybackResource

internal const val IOS_OPUS_MAX_INPUT_BYTES = 32L * 1024 * 1024
internal const val IOS_SABR_PREPARATION_TIMEOUT_MS = 120_000L

internal fun PlaybackResource.isWebmOpus(): Boolean =
    (isDirectFile || isSabr) && mimeType?.substringBefore(';')?.equals("audio/webm", ignoreCase = true) == true &&
        codecs?.trim()?.equals("opus", ignoreCase = true) == true

internal fun iosPlaybackAudioQuality(setting: String): AudioQuality =
    runCatching { AudioQuality.valueOf(setting) }.getOrDefault(AudioQuality.AUTO)

internal fun ContentHints.withIosAudioCapabilities(allowHls: Boolean = false) =
    copy(sabrFirst = false).withStreamCapabilities(
        allowHls = allowHls,
        allowSabr = true,
        allowBoundedRange = true,
    )

internal suspend fun resolveIosAudioResource(
    quality: AudioQuality,
    refresh: Boolean = false,
    forDownload: Boolean = false,
    forceAac: Boolean = false,
    resolve: suspend (AudioQuality, Boolean, Boolean) -> PlaybackResource,
): PlaybackResource {
    val requestedQuality = if (forceAac || (forDownload && quality != AudioQuality.LOW)) AudioQuality.MP4 else quality

    suspend fun resolveProgressiveOrHls(allowHls: Boolean): PlaybackResource {
        val resource =
            try {
                resolve(requestedQuality, refresh, allowHls)
            } catch (error: StreamResolveException) {
                if (requestedQuality == AudioQuality.MP4 || !error.hasNoPlayableStream()) throw error
                return resolve(AudioQuality.MP4, refresh, allowHls)
            }
        if ((!forDownload || resource.isDirectFile) && (
                resource.mimeType?.startsWith("audio/mp4", ignoreCase = true) == true ||
                    (
                        !forDownload && (
                            (!resource.isDirectFile && !resource.isSabr) ||
                                (!forceAac && resource.isWebmOpus() && (resource.contentLengthBytes ?: 0L) <= IOS_OPUS_MAX_INPUT_BYTES)
                        )
                    )
            )
        ) {
            return resource
        }
        if (forceAac) {
            throw StreamResolveException(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "No compatible iOS audio stream")
        }
        return resolve(AudioQuality.MP4, refresh, allowHls)
    }

    return try {
        resolveProgressiveOrHls(allowHls = false)
    } catch (error: StreamResolveException) {
        if (forDownload || !error.hasNoPlayableStream()) throw error
        resolveProgressiveOrHls(allowHls = true)
    }
}

internal val PlaybackResource.requiresIosAudioCapture: Boolean
    get() = isSabr || isWebmOpus() || requireBoundedRange

private fun StreamResolveException.hasNoPlayableStream(): Boolean =
    reason == StreamResolveException.Reason.NO_PLAYABLE_STREAM || reason == StreamResolveException.Reason.EXPLICIT_UNSUPPORTED
