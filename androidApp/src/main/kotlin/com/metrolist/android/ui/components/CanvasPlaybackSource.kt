package com.metrolist.android.ui.components

import com.metrolist.android.ui.player.CanvasCache
import com.metrolist.shared.api.artwork.YouTubeCanvasClip
import com.metrolist.shared.youtube.playback.PlaybackResource

internal data class CanvasPlaybackSource(
    val url: String,
    val cacheKey: String?,
    val clips: List<YouTubeCanvasClip>,
)

internal fun isPreparedCanvasUrl(url: String) = url.startsWith("file:")

internal fun canvasPlaybackSources(
    url: String,
    resource: PlaybackResource?,
    cacheKey: String?,
    clips: List<YouTubeCanvasClip>,
    cachedAvailable: Boolean,
    preparingClips: Boolean = false,
): Pair<CanvasPlaybackSource, CanvasPlaybackSource?> {
    if (!CanvasCache.isCachedUrl(url) && !isPreparedCanvasUrl(url)) {
        return CanvasPlaybackSource(url, cacheKey.takeUnless { preparingClips }, clips) to null
    }
    val originalUrl =
        resource
            ?.let {
                if (it.hasSabrVideo) "metrolist://sabr-video/${it.videoId}" else it.videoUrl
            }?.takeUnless { CanvasCache.isCachedUrl(it) || it.isBlank() }
    // Prepared clips use a separate timeline and cache key from full-video streams.
    val recovery = originalUrl?.let { CanvasPlaybackSource(it, null, clips) }
    if (CanvasCache.isCachedUrl(url) && !cachedAvailable && recovery != null) return recovery to null
    return CanvasPlaybackSource(url, cacheKey.takeUnless { isPreparedCanvasUrl(url) }, emptyList()) to recovery
}
