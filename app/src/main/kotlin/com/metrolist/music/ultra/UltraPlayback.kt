package com.metrolist.music.ultra

import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.music.utils.InnerTubeXPlayer
import ultra.hooks.ResolvedStream
import ultra.hooks.StreamRequest
import ultra.hooks.StreamResolution
import ultra.hooks.UltraHooks

/**
 * Glue for seam S7 (InnerTubeXPlayer.playerResponseForPlayback).
 *
 * Returns null when no ultra resolver claims the track, which tells the seam to fall through to
 * upstream's normal YouTube extraction, unchanged. Otherwise returns the result the seam should
 * hand straight back to the player.
 */
internal suspend fun ultraPrePlayback(
    videoId: String,
    playlistId: String?,
): Result<InnerTubeXPlayer.PlaybackData>? =
    when (val resolution = UltraHooks.resolveStream(StreamRequest(videoId, playlistId))) {
        StreamResolution.Pass -> null
        is StreamResolution.Resolved -> Result.success(resolution.stream.toPlaybackData())
        is StreamResolution.Failed -> Result.failure(resolution.cause)
    }

/**
 * Translates an ultra [ResolvedStream] into upstream's [InnerTubeXPlayer.PlaybackData], mirroring
 * how upstream maps its own extractor output (itag 0 = "not a YouTube format"). Range chunking is
 * off, so [InnerTubeXPlayer.PlaybackData.rangeChunkSizeBytes] is unused.
 */
internal fun ResolvedStream.toPlaybackData(): InnerTubeXPlayer.PlaybackData =
    InnerTubeXPlayer.PlaybackData(
        audioConfig = loudnessDb?.let { PlayerResponse.PlayerConfig.AudioConfig(it, null) },
        videoDetails = null,
        playbackTracking = null,
        format =
            PlayerResponse.StreamingData.Format(
                itag = 0,
                url = url,
                mimeType = mimeType,
                bitrate = bitrate ?: 0,
                width = null,
                height = null,
                contentLength = contentLengthBytes,
                quality = "",
                fps = null,
                qualityLabel = null,
                averageBitrate = bitrate,
                audioQuality = null,
                approxDurationMs = null,
                audioSampleRate = sampleRate,
                audioChannels = null,
                loudnessDb = loudnessDb,
                lastModified = null,
                signatureCipher = null,
                cipher = null,
                audioTrack = null,
            ),
        streamUrl = url,
        streamExpiresInSeconds = ttlSeconds.coerceAtLeast(1),
        streamClient = sourceName,
        streamHeaders = headers,
        requireBoundedRange = false,
        rangeChunkSizeBytes = 0L,
        useRangeChunks = false,
    )
