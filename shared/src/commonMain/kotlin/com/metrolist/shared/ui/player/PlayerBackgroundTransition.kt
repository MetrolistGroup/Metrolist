package com.metrolist.shared.ui.player

internal sealed interface PlayerBackgroundTransition {
    data class Animate(
        val artworkUrl: String,
    ) : PlayerBackgroundTransition

    data class Snap(
        val artworkUrl: String,
    ) : PlayerBackgroundTransition

    data class Seek(
        val artworkUrl: String,
        val fraction: Float,
    ) : PlayerBackgroundTransition
}

internal fun playerBackgroundTransition(
    thumbnailUrl: String,
    targetUrl: String,
    crossfade: PlayerArtworkCrossfade,
): PlayerBackgroundTransition {
    val nextUrl = crossfade.nextArtworkUrl
    return when {
        nextUrl != null && nextUrl != thumbnailUrl -> PlayerBackgroundTransition.Seek(nextUrl, crossfade.progress.coerceIn(0f, 1f))
        thumbnailUrl == targetUrl -> PlayerBackgroundTransition.Snap(thumbnailUrl)
        else -> PlayerBackgroundTransition.Animate(thumbnailUrl)
    }
}

internal fun retainPlayerBackgroundArtwork(
    url: String,
    prepared: PlayerTrackArtwork?,
    retained: PlayerTrackArtwork?,
): PlayerTrackArtwork? = prepared?.takeIf { it.url == url } ?: retained?.takeIf { it.url == url }
