package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.shared.playback.PlaybackErrorContext
import com.metrolist.shared.playback.PlaybackFailureCategory
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException

fun StreamResolveException.playbackFailureCategory(): PlaybackFailureCategory =
    when (reason) {
        StreamResolveException.Reason.NETWORK -> PlaybackFailureCategory.NETWORK

        StreamResolveException.Reason.UNAVAILABLE,
        StreamResolveException.Reason.EXPLICIT_UNSUPPORTED,
        StreamResolveException.Reason.AGE_RESTRICTED,
        StreamResolveException.Reason.NO_MUSIC_VIDEO,
        -> PlaybackFailureCategory.UNAVAILABLE

        StreamResolveException.Reason.NO_PLAYABLE_STREAM -> PlaybackFailureCategory.PLAYABILITY

        StreamResolveException.Reason.UNKNOWN -> PlaybackFailureCategory.UNKNOWN
    }

fun Throwable.shouldReportPlaybackFailure(context: PlaybackErrorContext): Boolean {
    if (context.recovered == true) {
        PlaybackEventLog.debug(
            kind = PlaybackEventKind.RECOVERY,
            message = "Playback failure recovered.",
            videoId = context.videoId,
            clientName = context.clientName,
            profileId = context.profileId,
            details =
                buildMap {
                    put("stage", context.stage)
                    context.retryCount?.let { put("retryCount", it.toString()) }
                },
            error = this,
        )
        return false
    }
    var current: Throwable? = this
    while (current != null) {
        if (current is CancellationException) return false
        // Type checks survive Android release obfuscation.
        if (current is HttpRequestTimeoutException || current is ConnectTimeoutException || current is SocketTimeoutException) return false
        // Platform connectivity exceptions are not available in common code.
        if (current::class.simpleName in TRANSIENT_CONNECTIVITY_FAILURES) return false
        if (current is StreamResolveException &&
            current.reason in
            setOf(
                StreamResolveException.Reason.UNAVAILABLE,
                StreamResolveException.Reason.EXPLICIT_UNSUPPORTED,
                StreamResolveException.Reason.AGE_RESTRICTED,
                StreamResolveException.Reason.NO_MUSIC_VIDEO,
            )
        ) {
            return false
        }
        current = current.cause?.takeUnless { it === current }
    }
    return true
}

private val TRANSIENT_CONNECTIVITY_FAILURES =
    setOf(
        "ConnectException",
        "NoRouteToHostException",
        "UnknownHostException",
        "UnresolvedAddressException",
    )
