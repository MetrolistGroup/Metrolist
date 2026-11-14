package com.metrolist.android.playback

import com.metrolist.innertubex.extraction.StreamDiagnostics
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.PlaybackErrorContext
import com.metrolist.shared.utils.redactDiagnosticText
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.playbackResolveSummary
import com.metrolist.shared.youtube.playback.shouldReportPlaybackFailure
import io.sentry.Attachment
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel

internal object PlaybackErrorReporter {
    private const val PLAYBACK_LOG_EVENTS = 200
    private const val PLAYBACK_LOG_CHARS = 64 * 1024
    private var lastPlaybackBreadcrumbKey: String? = null

    private val resourceExtraKeys =
        listOf(
            "playback.selected_client",
            "playback.selected_profile",
            "playback.selected_user_agent",
            "playback.itag",
            "playback.mime_type",
            "playback.codecs",
            "playback.bitrate",
            "playback.sample_rate",
            "playback.stream_attempts",
            "playback.user_agents_tried",
            "playback.authenticated_watch_page",
        )
    private val playbackExtraKeys =
        listOf(
            "playback.song_duration_seconds",
            "playback.song_explicit",
            "playback.song_source",
        ) + resourceExtraKeys

    fun setActivePlayback(
        song: SongItem,
        resource: PlaybackResource,
    ) {
        Sentry.setTag("playback.video_id", song.id)
        Sentry.setTag("playback.client", resource.clientName)
        Sentry.setTag("playback.profile", resource.profileId)
        setSongExtras(song, Sentry::setExtra)
        setResourceExtras(resource, Sentry::setExtra)

        val breadcrumbKey = "${song.id}:${resource.clientName}:${resource.profileId}:${resource.itag}"
        if (lastPlaybackBreadcrumbKey != breadcrumbKey) {
            lastPlaybackBreadcrumbKey = breadcrumbKey
            Sentry.addBreadcrumb(
                Breadcrumb().apply {
                    category = "playback"
                    message = "Playback started"
                    level = SentryLevel.INFO
                    setData("video_id", song.id)
                    setData("client", resource.clientName)
                    setData("profile", resource.profileId)
                },
            )
        }
    }

    fun clearActivePlayback() {
        lastPlaybackBreadcrumbKey = null
        listOf("playback.video_id", "playback.client", "playback.profile").forEach(Sentry::removeTag)
        playbackExtraKeys.forEach(Sentry::removeExtra)
    }

    fun report(
        error: Throwable,
        context: PlaybackErrorContext,
    ) {
        if (!error.shouldReportPlaybackFailure(context)) return

        Sentry.captureException(error) { scope ->
            scope.level = SentryLevel.ERROR
            scope.setTag("error.domain", "playback")
            scope.setTag("playback.stage", context.stage)
            context.videoId?.let {
                scope.setTag("playback.video_id", it)
                scope.setExtra("playback.video_id", it)
            }
            context.clientName?.let { scope.setTag("playback.client", it) }
            context.profileId?.let { scope.setTag("playback.profile", it) }
            context.sourceName?.let { scope.setTag("playback.source", it) }
            context.code?.let { scope.setTag("playback.error_code", it) }
            context.recovered?.let { scope.setTag("playback.recovered", it.toString()) }
            context.retryCount?.let { scope.setTag("playback.retry_count", it.toString()) }
            context.song?.let { song -> setSongExtras(song, scope::setExtra) }
            if (context.song != null && context.resource == null) {
                resourceExtraKeys.forEach(scope::removeExtra)
                if (context.clientName == null) scope.removeTag("playback.client")
                if (context.profileId == null) scope.removeTag("playback.profile")
            }
            context.resource?.let { resource -> setResourceExtras(resource, scope::setExtra) }
            val diagnostics = error.streamDiagnostics() ?: context.resource?.streamDiagnostics
            diagnostics?.let { setDiagnosticsExtras(it, scope::setExtra) }
            playbackResolveSummary(PlaybackEventLog.events.value, context.videoId)?.let { summary ->
                summary.tags.forEach(scope::setTag)
                summary.extras.forEach { (key, value) -> scope.setExtra(key, redactDiagnosticText(value)) }
            }
            scope.addAttachment(
                Attachment(
                    redactDiagnosticText(PlaybackEventLog.exportText(PLAYBACK_LOG_EVENTS, PLAYBACK_LOG_CHARS)).encodeToByteArray(),
                    "playback-log.txt",
                    "text/plain",
                ),
            )
        }
    }

    private fun Throwable.streamDiagnostics(): StreamDiagnostics? {
        val pending = java.util.ArrayDeque<Throwable>().apply { add(this@streamDiagnostics) }
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            if (current is StreamResolveException && current.diagnostics != null) return current.diagnostics
            current.cause?.let(pending::addLast)
            current.suppressed.forEach(pending::addLast)
        }
        return null
    }

    private fun setSongExtras(
        song: SongItem,
        setExtra: (String, String) -> Unit,
    ) {
        setExtra("playback.song_duration_seconds", song.duration?.toString() ?: "unknown")
        setExtra("playback.song_explicit", song.isExplicit.toString())
        setExtra(
            "playback.song_source",
            if (song.isLocal) {
                "local"
            } else if (song.isUploaded) {
                "upload"
            } else {
                "youtube"
            },
        )
    }

    private fun setResourceExtras(
        resource: PlaybackResource,
        setExtra: (String, String) -> Unit,
    ) {
        setExtra("playback.selected_client", resource.clientName)
        setExtra("playback.selected_profile", resource.profileId)
        setExtra("playback.selected_user_agent", resource.headers["User-Agent"] ?: "none")
        setExtra("playback.itag", resource.itag?.toString() ?: "unknown")
        setExtra("playback.mime_type", resource.mimeType ?: "unknown")
        setExtra("playback.codecs", resource.codecs ?: "unknown")
        setExtra("playback.bitrate", resource.bitrate?.toString() ?: "unknown")
        setExtra("playback.sample_rate", resource.sampleRate?.toString() ?: "unknown")
        resource.streamDiagnostics?.let { setDiagnosticsExtras(it, setExtra) }
            ?: run {
                setExtra("playback.stream_attempts", "none")
                setExtra("playback.user_agents_tried", "none")
                setExtra("playback.authenticated_watch_page", "false")
            }
    }

    private fun setDiagnosticsExtras(
        diagnostics: StreamDiagnostics,
        setExtra: (String, String) -> Unit,
    ) {
        setExtra(
            "playback.stream_attempts",
            diagnostics.attempts.joinToString(" -> ") { attempt ->
                "${attempt.clientName}[${attempt.profileId ?: "unknown"}]=${attempt.outcome}"
            },
        )
        setExtra(
            "playback.user_agents_tried",
            diagnostics.attempts
                .distinctBy { it.clientName to it.userAgent }
                .joinToString("\n") { "${it.clientName}: ${it.userAgent}" },
        )
        setExtra("playback.authenticated_watch_page", diagnostics.usedAuthenticatedWatchPage.toString())
    }
}
