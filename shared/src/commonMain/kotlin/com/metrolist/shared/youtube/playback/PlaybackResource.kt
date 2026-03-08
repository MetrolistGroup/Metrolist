package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.extraction.PlaybackTrackingData
import com.metrolist.innertubex.extraction.StreamDiagnostics
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import kotlin.time.Instant

data class PlaybackResource(
    val videoId: String,
    val url: String,
    val headers: Map<String, String>,
    val loudnessDb: Double?,
    val expiresAt: Instant?,
    val perceptualLoudnessDb: Double? = null,
    val contentLengthBytes: Long? = null,
    val itag: Int? = null,
    val mimeType: String? = null,
    val codecs: String? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val durationSeconds: Long? = null,
    val clientName: String = "unknown",
    val profileId: String = "unknown",
    val requireBoundedRange: Boolean = true,
    val useRangeChunks: Boolean = false,
    val rangeChunkSizeBytes: Long = 1_048_576L,
    val playbackTracking: PlaybackTrackingData? = null,
    val streamDiagnostics: StreamDiagnostics? = null,
    val videoUrl: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoMimeType: String? = null,
    val videoCodecs: String? = null,
    val videoBitrate: Int? = null,
    val videoItag: Int? = null,
    val videoContentLengthBytes: Long? = null,
    val sabrBootstrap: SabrBootstrap? = null,
    val sabrVideoBootstrap: SabrBootstrap? = null,
    val availableVideoHeights: List<Int> = emptyList(),
    val counterpartSource: String? = null,
    val musicVideoId: String? = null,
    val musicVideoPositionMap: CounterpartPositionMap? = null,
    val allowPlaybackTrackingFallback: Boolean = false,
    val sabrTokenSupplier: (suspend () -> ByteArray?)? = null,
    val onSabrAttestationPending: (() -> Unit)? = null,
) {
    val networkUrl: String?
        get() =
            (sabrBootstrap?.serverAbrStreamingUrl ?: url).takeIf {
                it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
            }

    val isDirectFile: Boolean
        get() =
            !isSabr && !mimeType.orEmpty().contains("mpegurl", ignoreCase = true) &&
                !mimeType.orEmpty().contains("dash+xml", ignoreCase = true) &&
                !url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) &&
                !url.substringBefore('?').endsWith(".mpd", ignoreCase = true)

    val hasDirectVideo: Boolean
        get() = !videoUrl.isNullOrBlank() && !videoUrl.startsWith("sabr-video://")

    val isSabr: Boolean
        get() = sabrBootstrap != null

    val hasSabrVideo: Boolean
        get() = sabrVideoBootstrap?.selectedVideoFormat != null

    val hasVideo: Boolean
        get() = hasDirectVideo || hasSabrVideo

    val isLive: Boolean = profileId == "ANDROID_LIVE" || isLiveStreamUrl(url) || isLiveStreamUrl(videoUrl.orEmpty())

    fun hasSameSabrAudio(other: PlaybackResource): Boolean {
        val first = sabrBootstrap ?: return false
        val second = other.sabrBootstrap ?: return false
        return first.videoId == second.videoId && first.audioFormat.lastModified > 0L &&
            first.audioFormat == second.audioFormat && first.audioTrackId == second.audioTrackId &&
            first.isDrc == second.isDrc && first.mimeType == second.mimeType && first.durationMs == second.durationMs &&
            (first.contentLengthBytes ?: contentLengthBytes) == (second.contentLengthBytes ?: other.contentLengthBytes)
    }
}

fun isLiveStreamUrl(url: String): Boolean =
    url.contains("yt_live_broadcast", ignoreCase = true) ||
        url.contains("/live/1", ignoreCase = true) ||
        url.contains("live=1", ignoreCase = true) ||
        url.contains("/hls_playlist/", ignoreCase = true) ||
        url.contains("/hls_variant/", ignoreCase = true)
