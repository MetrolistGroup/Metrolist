package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.shared.playback.PlaybackHttpStatusCarrier
import kotlinx.coroutines.CancellationException

object PlaybackErrorMessages {
    fun describe(error: Throwable?): String {
        if (error == null) return "Unknown playback error (no exception was attached)."
        val parts = ArrayList<String>(4)
        generateSequence(error) { current -> current.cause?.takeUnless { it === current } }
            .forEach { current ->
                describeOne(current)?.let(parts::add)
            }
        return parts.distinct().joinToString(" ").ifBlank {
            error::class.simpleName?.let { "$it occurred during playback." } ?: "Unknown playback error."
        }
    }

    fun httpStatus(code: Int): String =
        when (code) {
            403 -> {
                "HTTP 403 Forbidden: YouTube's CDN refused the media request. " +
                    "The URL likely expired, the poToken is missing/invalid, or this client is blocked for the video."
            }

            404 -> {
                "HTTP 404 Not Found: the media URL no longer exists."
            }

            410 -> {
                "HTTP 410 Gone: the media URL expired and must be resolved again."
            }

            416 -> {
                "HTTP 416 Range Not Satisfiable: the player requested a byte range the CDN does not have. " +
                    "This often means the range strategy does not match the stream."
            }

            429 -> {
                "HTTP 429 Too Many Requests: YouTube is rate-limiting media downloads."
            }

            in 500..599 -> {
                "HTTP $code: YouTube's media server failed while serving audio."
            }

            else -> {
                "HTTP $code: unexpected media-server status."
            }
        }

    fun playabilityStatus(status: String?): String {
        val normalized = status.orEmpty().uppercase()
        val meaning =
            when (normalized) {
                "OK" -> {
                    "YouTube marked the player response as playable."
                }

                "UNPLAYABLE" -> {
                    "YouTube marked this video unplayable for the attempted client."
                }

                "LOGIN_REQUIRED" -> {
                    "This client needs a signed-in YouTube session."
                }

                "AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "CONTENT_CHECK_REQUIRED" -> {
                    "YouTube requires age verification before this client can play the video."
                }

                "LIVE_STREAM_OFFLINE" -> {
                    "The live stream is currently offline."
                }

                "ERROR" -> {
                    "YouTube returned a generic player error for this client."
                }

                "VIDEO_UNAVAILABLE" -> {
                    "YouTube says this video is unavailable."
                }

                else -> {
                    if (normalized.isBlank()) {
                        "YouTube did not return a playability status."
                    } else {
                        "YouTube playability status $normalized."
                    }
                }
            }
        return meaning
    }

    fun sabrFailureKind(kind: SabrFailureKind?): String =
        when (kind) {
            SabrFailureKind.ATTESTATION_REQUIRED -> {
                "SABR attestation required: YouTube refused to send audio until a valid Proof-of-Origin token is attached."
            }

            SabrFailureKind.RELOAD_PLAYER -> {
                "SABR asked the player response to be reloaded before streaming can continue."
            }

            SabrFailureKind.PROTOCOL -> {
                "SABR protocol failure: the UMP stream did not make audio progress or returned an unrecoverable protocol error."
            }

            null -> {
                "SABR failure with no classified kind."
            }
        }

    fun attemptOutcome(outcome: String): String {
        val playabilityPrefix = "playability:"
        val requestPrefix = "request:"
        val selectionPrefix = "selection:"
        return when {
            outcome == "playable_response" -> {
                "returned a playable player response"
            }

            outcome == "no_playable_response" -> {
                "returned no playable player response"
            }

            outcome.startsWith(playabilityPrefix) -> {
                playabilityStatus(outcome.removePrefix(playabilityPrefix))
            }

            outcome.startsWith(requestPrefix) -> {
                "InnerTube player request failed (${outcome.removePrefix(requestPrefix)})."
            }

            outcome.startsWith(selectionPrefix) -> {
                "skipped during client selection: ${outcome.removePrefix(selectionPrefix).replace("+", ", ")}"
            }

            else -> {
                outcome
            }
        }
    }

    fun streamResolveReason(reason: StreamResolveException.Reason): String =
        when (reason) {
            StreamResolveException.Reason.NO_PLAYABLE_STREAM -> {
                "No playable audio stream was found after trying the available InnerTube clients."
            }

            StreamResolveException.Reason.EXPLICIT_UNSUPPORTED -> {
                "This explicit track is not playable with the current YouTube session or client set."
            }

            StreamResolveException.Reason.UNAVAILABLE -> {
                "YouTube reports this track as unavailable."
            }

            StreamResolveException.Reason.AGE_RESTRICTED -> {
                "YouTube reports this track as age-restricted."
            }

            StreamResolveException.Reason.NO_MUSIC_VIDEO -> {
                "No music video counterpart was found for this track."
            }

            StreamResolveException.Reason.NETWORK -> {
                "Could not reach YouTube to fetch stream data."
            }

            StreamResolveException.Reason.UNKNOWN -> {
                "Stream resolution failed for an unknown reason."
            }
        }

    private fun describeOne(error: Throwable): String? {
        if (error is CancellationException) return null
        return when (error) {
            is SabrProtocolException -> {
                sabrFailureKind(error.kind)
            }

            is StreamResolveException -> {
                buildString {
                    append(streamResolveReason(error.reason))
                    error.diagnostics?.attempts?.takeIf { it.isNotEmpty() }?.let { attempts ->
                        append(" Client attempts: ")
                        append(
                            attempts.joinToString("; ") { attempt ->
                                "${attempt.clientName}/${attempt.profileId ?: "no-profile"}: ${attemptOutcome(attempt.outcome)}"
                            },
                        )
                    }
                }
            }

            else -> {
                if (error is PlaybackHttpStatusCarrier) {
                    error.playbackHttpStatusCode?.let { return httpStatus(it) }
                }
                val name = error::class.simpleName.orEmpty()
                when (name) {
                    "UnknownHostException", "UnresolvedAddressException" -> {
                        "DNS lookup failed: the device could not resolve YouTube's host name."
                    }

                    "ConnectException", "NoRouteToHostException" -> {
                        "Network connect failed: the device could not open a connection to YouTube."
                    }

                    "SocketTimeoutException", "ConnectTimeoutException", "HttpRequestTimeoutException" -> {
                        "Network timeout while talking to YouTube or the media CDN."
                    }

                    "SSLHandshakeException", "SSLException" -> {
                        "TLS handshake failed while connecting to YouTube or the media CDN."
                    }

                    else -> {
                        name.takeIf { it.isNotBlank() }?.let { "$it occurred during playback." }
                    }
                }
            }
        }
    }
}

object PlaybackUrlRedaction {
    fun host(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return null
        val start = schemeEnd + 3
        val end =
            url.indexOfAny(charArrayOf('/', '?', '#'), start).let { index ->
                if (index < 0) url.length else index
            }
        return url.substring(start, end).takeIf { it.isNotBlank() }
    }

    fun redact(url: String): String = "<redacted-url:${url.length}chars>"
}
