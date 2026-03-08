package com.metrolist.shared.playback

import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.shared.youtube.playback.playbackFailureCategory

enum class PlaybackFailureCategory {
    CDN_FORBIDDEN,
    TOKEN,
    URL_EXPIRED,
    DECODER,
    SABR_PROTOCOL,
    SABR_ATTESTATION,
    TIMEOUT,
    RATE_LIMIT,
    NETWORK,
    RENDERER,
    PLAYABILITY,
    UNAVAILABLE,
    UNKNOWN,
}

class PlaybackNetworkChangedException : Exception()

class PlaybackProgressTimeoutException : Exception()

interface PlaybackHttpStatusCarrier {
    val playbackHttpStatusCode: Int?
}

fun Throwable.findPlaybackHttpStatusCode(): Int? =
    generateSequence(this) { current -> current.cause?.takeUnless { it === current } }
        .mapNotNull { current ->
            when (current) {
                is PlaybackHttpStatusCarrier -> current.playbackHttpStatusCode
                is SabrProtocolException -> current.httpStatusCode
                else -> null
            }
        }.firstOrNull()

fun playbackFailureCategoryFromSabr(kind: SabrFailureKind?): PlaybackFailureCategory =
    when (kind) {
        SabrFailureKind.ATTESTATION_REQUIRED -> PlaybackFailureCategory.SABR_ATTESTATION
        SabrFailureKind.PROTOCOL, SabrFailureKind.RELOAD_PLAYER -> PlaybackFailureCategory.SABR_PROTOCOL
        null -> PlaybackFailureCategory.UNKNOWN
    }

object PlaybackFailureClassifier {
    fun fromHttpStatus(status: Int): PlaybackFailureCategory =
        when (status) {
            401, 407 -> PlaybackFailureCategory.TOKEN
            403 -> PlaybackFailureCategory.CDN_FORBIDDEN
            404, 410 -> PlaybackFailureCategory.URL_EXPIRED
            408 -> PlaybackFailureCategory.TIMEOUT
            425 -> PlaybackFailureCategory.NETWORK
            429 -> PlaybackFailureCategory.RATE_LIMIT
            else -> if (status in 500..599) PlaybackFailureCategory.NETWORK else PlaybackFailureCategory.UNKNOWN
        }

    fun fromThrowable(error: Throwable?): PlaybackFailureCategory {
        if (error == null) return PlaybackFailureCategory.UNKNOWN
        generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.forEach { current ->
            val name = current::class.simpleName.orEmpty()
            when {
                current is StreamResolveException -> {
                    return current.playbackFailureCategory()
                }

                current is PlaybackHttpStatusCarrier && current.playbackHttpStatusCode != null -> {
                    return fromHttpStatus(current.playbackHttpStatusCode!!)
                }

                current is SabrProtocolException -> {
                    current.httpStatusCode?.let { return fromHttpStatus(it) }
                    return playbackFailureCategoryFromSabr(current.kind)
                }

                name.contains("Sabr", ignoreCase = true) -> {
                    return playbackFailureCategoryFromSabr(
                        (current as? SabrProtocolException)?.kind,
                    )
                }

                name.contains("Timeout", ignoreCase = true) -> {
                    return PlaybackFailureCategory.TIMEOUT
                }

                name.contains("Decoder", ignoreCase = true) -> {
                    return PlaybackFailureCategory.DECODER
                }

                name.contains("Renderer", ignoreCase = true) -> {
                    return PlaybackFailureCategory.RENDERER
                }

                name.contains("Network", ignoreCase = true) || name in NETWORK_ERRORS -> {
                    return PlaybackFailureCategory.NETWORK
                }
            }
        }
        return PlaybackFailureCategory.UNKNOWN
    }

    private val NETWORK_ERRORS =
        setOf("ConnectException", "SocketException", "UnknownHostException", "NoRouteToHostException", "UnresolvedAddressException")
}
