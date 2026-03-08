package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.StreamDiagnostics
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.shared.BuildConfig
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.PlaybackFailure
import com.metrolist.shared.playback.findPlaybackHttpStatusCode
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.youtube.strategy.ClientHealthTracker

internal fun presenceLabel(value: String?): String =
    if (value.isNullOrBlank()) {
        "missing"
    } else {
        "present:${value.length}chars"
    }

internal fun presenceLabel(bytes: ByteArray?): String =
    if (bytes == null) {
        "missing"
    } else {
        "present:${bytes.size}bytes"
    }

fun playbackEnvironmentSnapshot(): Map<String, String> =
    buildMap {
        putAll(playbackLogPlatformDetails())
        runCatching { put("appVersion", BuildConfig.APP_VERSION) }
            .onFailure { error -> put("appVersion", "unavailable: ${error::class.simpleName}") }
        runCatching { putAll(Settings.shared.toPlaybackLogDetails()) }
            .onFailure { error ->
                put("settingsSnapshot", "unavailable: ${error::class.simpleName ?: "unknown"}")
            }
        runCatching { putAll(GlobalYouTube.innerTube.toPlaybackSessionLogDetails()) }
            .onFailure { error ->
                put("sessionSnapshot", "unavailable: ${error::class.simpleName ?: "unknown"}")
            }
    }

fun Settings.toPlaybackLogDetails(): Map<String, String> =
    buildMap {
        put("audioQuality", audioQuality)
        put("playbackSpeed", playbackSpeed.toString())
        put("musicVideoQuality", musicVideoQuality.name)
        put("playVideosInMiniPlayer", playVideosInMiniPlayer.toString())
        put("videoViewerVisible", videoViewerVisible.toString())
        put("musicVideoPlaybackEnabled", musicVideoPlaybackEnabled.toString())
        put("youtubeCanvasMode", youtubeCanvasMode.name)
        put("canvasEnabledSources", canvasEnabledSources)
        put("devtoolsPlaybackClient", devtoolsPlaybackClient)
        put("autoSkipNextOnError", autoSkipNextOnError.toString())
        put("contentLanguage", contentLanguage)
        put("contentCountry", contentCountry)
        put("appLanguage", appLanguage)
        put("explicitOnly", explicitOnly.toString())
        put("seekDurationSeconds", seekDurationSeconds.toString())
        put("songCacheEnabled", songCacheEnabled.toString())
        put("maxSongCacheSize", maxSongCacheSize.toString())
        put("audioNormalization", audioNormalization.toString())
        put("skipSilence", skipSilence.toString())
        put("crossfadeEnabled", crossfadeEnabled.toString())
        put("crossfadeDuration", crossfadeDuration.toString())
        put("githubNTransformEnabled", githubNTransformEnabled.toString())
        put("playerConfigRemoteSourceUrl", presenceLabel(playerConfigRemoteSourceUrl))
        put("playerConfigRemoteFetchedAt", playerConfigRemoteFetchedAt.toString())
        put("volume", volume.toString())
        put("accountName", presenceLabel(accountName.takeIf { it.isNotBlank() }))
    }

fun InnerTube.toPlaybackSessionLogDetails(): Map<String, String> {
    val session = sessionSnapshot()
    return buildMap {
        put("signedIn", hasSapCookieAuth(session).toString())
        put("hl", session.locale.hl)
        put("gl", session.locale.gl)
        put("visitorData", presenceLabel(session.visitorData))
        put("dataSyncId", presenceLabel(session.dataSyncId))
        put("authUser", presenceLabel(session.authUser))
        put("useLoginForBrowse", session.useLoginForBrowse.toString())
        put("regionOverrideActive", session.regionOverrideActive.toString())
        put("cookie", presenceLabel(session.cookie))
        put("sapisid", presenceLabel(session.sapisid))
    }
}

fun ContentHints.toPlaybackLogDetails(): Map<String, String> =
    buildMap {
        put("explicit", isExplicit?.toString() ?: "unknown")
        put("kids", isKidsContent?.toString() ?: "unknown")
        put("ageRestricted", isAgeRestricted?.toString() ?: "unknown")
        put("live", isLive?.toString() ?: "unknown")
        put("uploaded", isUploaded?.toString() ?: "unknown")
        put("local", isLocal?.toString() ?: "unknown")
        put("wantVideo", wantVideo.toString())
        put("sabrFirst", sabrFirst.toString())
        put("maxVideoHeight", maxVideoHeight?.toString() ?: "auto")
        playbackClientOverrideId?.takeIf { it.isNotBlank() }?.let { put("clientOverride", it) }
        put("endpointParams", presenceLabel(endpointParams))
    }

fun SongItem.toPlaybackLogDetails(): Map<String, String> =
    buildMap {
        put("songDurationSeconds", duration?.toString() ?: "unknown")
        put("songExplicit", isExplicit.toString())
        put(
            "songSource",
            when {
                isLocal -> {
                    "local"
                }

                isUploaded -> {
                    "upload"
                }

                else -> {
                    "youtube"
                }
            },
        )
        musicVideoType?.takeIf { it.isNotBlank() }?.let { put("musicVideoType", it) }
    }

fun PlaybackFailure.toPlaybackLogDetails(): Map<String, String> =
    buildMap {
        put("failureStage", stage)
        put("failureCategory", category.name)
        sourceName?.takeIf { it.isNotBlank() }?.let { put("failureSource", it) }
        code?.takeIf { it.isNotBlank() }?.let { put("failureCode", it) }
        httpStatusCode?.let { put("httpStatus", it.toString()) }
        playbackInstanceId?.let { put("playbackInstanceId", it.toString()) }
        put("playbackPositionMs", playbackPositionMs.toString())
        put("resumePlayback", resumePlayback.toString())
        put("mediaSourceFailure", isMediaSourceFailure.toString())
        put("excludePlaybackProfile", shouldExcludePlaybackProfile.toString())
        sabrFailureKind?.let { put("sabrFailureKind", it.name) }
    }

fun StreamDiagnostics.toPlaybackLogDetails(): Map<String, String> =
    buildMap {
        put("authenticatedWatchPage", usedAuthenticatedWatchPage.toString())
        if (attempts.isEmpty()) {
            put("streamAttempts", "none")
            put("userAgentsTried", "none")
            return@buildMap
        }
        put(
            "streamAttempts",
            attempts.joinToString(" -> ") { attempt ->
                "${attempt.clientName}[${attempt.profileId ?: "unknown"}]=${attempt.outcome}"
            },
        )
        put(
            "userAgentsTried",
            attempts
                .distinctBy { it.clientName to it.userAgent }
                .joinToString(" | ") { "${it.clientName}: ${it.userAgent}" },
        )
        attempts.forEachIndexed { index, attempt ->
            val prefix = "attempt.${index + 1}"
            put("$prefix.client", attempt.clientName)
            put("$prefix.profile", attempt.profileId ?: "unknown")
            put("$prefix.outcome", PlaybackErrorMessages.attemptOutcome(attempt.outcome))
            put("$prefix.userAgent", attempt.userAgent)
        }
    }

fun SabrBootstrap.toPlaybackLogDetails(): Map<String, String> =
    buildMap {
        put("sabrItag", audioFormat.itag.toString())
        put("sabrLastModified", audioFormat.lastModified.toString())
        audioFormat.xtags?.let { put("sabrXtags", it) }
        put("sabrDurationMs", durationMs.toString())
        contentLengthBytes?.let { put("sabrContentLength", it.toString()) }
        put("sabrMime", mimeType)
        put("sabrClientId", clientName.toString())
        put("sabrClientVersion", clientVersion)
        put("sabrDrc", isDrc.toString())
        put("sabrPoToken", presenceLabel(poToken))
        put("sabrUstreamerConfig", "${videoPlaybackUstreamerConfig.size}bytes")
        put("sabrDiscardVideoItag", discardVideoFormat.itag.toString())
        put("sabrDiscardVideoHeight", discardVideoHeight.toString())
        audioTrackId?.takeIf { it.isNotBlank() }?.let { put("sabrAudioTrackId", it) }
        requestUserAgent?.takeIf { it.isNotBlank() }?.let { put("sabrUserAgent", it) }
        requestOrigin?.takeIf { it.isNotBlank() }?.let { put("sabrOrigin", it) }
        put("sabrUrl", PlaybackUrlRedaction.redact(serverAbrStreamingUrl))
    }

fun TokenProviderCapabilities.toPlaybackLogDetails(): Map<String, String> =
    buildMap {
        put(
            "poTokenProviders",
            if (providers.isEmpty()) {
                "none"
            } else {
                providers.joinToString(",") { it.name }
            },
        )
        put("poTokenUsesWebView", usesWebView.toString())
    }

fun ClientHealthTracker.toPlaybackLogDetails(): Map<String, String> {
    val summary = diagnosticSummary()
    return if (summary.isBlank()) emptyMap() else mapOf("clientHealth" to summary)
}

fun throwableDiagnostics(error: Throwable?): Map<String, String> {
    if (error == null) return emptyMap()
    return buildMap {
        val chain = ArrayList<Throwable>(4)
        generateSequence(error) { current -> current.cause?.takeUnless { it === current } }.forEach(chain::add)
        put(
            "exceptionChain",
            chain.joinToString(" -> ") { current ->
                current::class.simpleName ?: current::class.qualifiedName ?: "Throwable"
            },
        )
        put(
            "exceptionTypes",
            chain.joinToString(",") { current ->
                current::class.simpleName ?: current::class.qualifiedName ?: "Throwable"
            },
        )
        if (error.suppressedExceptions.isNotEmpty()) {
            put(
                "suppressed",
                error.suppressedExceptions.joinToString(" | ") { current ->
                    current::class.simpleName ?: current::class.qualifiedName ?: "Throwable"
                },
            )
        }
        chain.forEach { current ->
            when (current) {
                is StreamResolveException -> {
                    put("resolveReason", current.reason.name)
                    current.diagnostics?.let { putAll(it.toPlaybackLogDetails()) }
                }

                is SabrProtocolException -> {
                    put("sabrKind", current.kind.name)
                }
            }
        }
        error.findPlaybackHttpStatusCode()?.let { put("httpStatus", it.toString()) }
    }
}

fun formatPlaybackLogDetails(details: Map<String, String>): String {
    if (details.isEmpty()) return ""
    return details.entries.joinToString("\n") { (key, value) ->
        if (value.contains('\n')) {
            buildString {
                append("  ")
                append(key)
                append('=')
                value.lineSequence().forEachIndexed { index, line ->
                    if (index == 0) {
                        append(line)
                    } else {
                        append('\n')
                        append("    ")
                        append(line)
                    }
                }
            }
        } else {
            "  $key=$value"
        }
    }
}

fun PlaybackResource.toPlaybackLogDetails(): Map<String, String> =
    buildMap {
        put("transport", if (isSabr) "SABR" else "Direct")
        put("client", clientName)
        put("profile", profileId)
        itag?.let { put("itag", it.toString()) }
        mimeType?.let { put("mime", it) }
        codecs?.let { put("codecs", it) }
        bitrate?.let { put("bitrate", it.toString()) }
        sampleRate?.let { put("sampleRate", it.toString()) }
        loudnessDb?.let { put("loudnessDb", it.toString()) }
        contentLengthBytes?.let { put("contentLength", it.toString()) }
        put("requireBoundedRange", requireBoundedRange.toString())
        put("useRangeChunks", useRangeChunks.toString())
        put("rangeChunkSizeBytes", rangeChunkSizeBytes.toString())
        put("url", PlaybackUrlRedaction.redact(url))
        PlaybackUrlRedaction.host(url)?.let { put("urlHost", it) }
        val headerNames = headers.keys.sorted()
        if (headerNames.isNotEmpty()) put("headerKeys", headerNames.joinToString(","))
        headers["User-Agent"]?.let { put("userAgent", it) }
        headers["Range"]?.let { put("range", it) }
        headers["Origin"]?.let { origin ->
            put("originPresent", "true")
            PlaybackUrlRedaction.host(origin)?.let { put("originHost", it) }
        }
        headers["Referer"]?.let { referer ->
            put("refererPresent", "true")
            PlaybackUrlRedaction.host(referer)?.let { put("refererHost", it) }
        }
        sabrBootstrap?.let { putAll(it.toPlaybackLogDetails()) }
        videoItag?.let { put("videoItag", it.toString()) }
        videoWidth?.let { put("videoWidth", it.toString()) }
        videoHeight?.let { put("videoHeight", it.toString()) }
        videoMimeType?.let { put("videoMime", it) }
        videoCodecs?.let { put("videoCodecs", it) }
        videoBitrate?.let { put("videoBitrate", it.toString()) }
        videoContentLengthBytes?.let { put("videoContentLength", it.toString()) }
        videoUrl?.let { put("videoUrl", PlaybackUrlRedaction.redact(it)) }
        if (availableVideoHeights.isNotEmpty()) {
            put("availableVideoHeights", availableVideoHeights.joinToString(","))
        }
        expiresAt?.let { put("expiresAt", it.toString()) }
        streamDiagnostics?.let { putAll(it.toPlaybackLogDetails()) }
    }
