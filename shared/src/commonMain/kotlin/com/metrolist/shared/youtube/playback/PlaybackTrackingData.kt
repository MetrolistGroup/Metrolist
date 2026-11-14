package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.replaceClientPlaybackNonce

internal fun PlaybackResource.withFreshClientPlaybackNonce(): PlaybackResource = withClientPlaybackNonce(generateClientPlaybackNonce())

fun PlaybackResource.withClientPlaybackNonce(clientPlaybackNonce: String): PlaybackResource {
    val tracking = playbackTracking ?: return this
    return copy(
        url = replaceClientPlaybackNonce(url, clientPlaybackNonce),
        videoUrl = videoUrl?.let { replaceClientPlaybackNonce(it, clientPlaybackNonce) },
        playbackTracking = tracking.copy(clientPlaybackNonce = clientPlaybackNonce),
        sabrBootstrap =
            sabrBootstrap?.copy(
                serverAbrStreamingUrl = replaceClientPlaybackNonce(sabrBootstrap.serverAbrStreamingUrl, clientPlaybackNonce),
            ),
    )
}
