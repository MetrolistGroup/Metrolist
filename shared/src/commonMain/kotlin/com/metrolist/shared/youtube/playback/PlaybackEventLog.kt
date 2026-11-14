package com.metrolist.shared.youtube.playback

import kotlin.time.Clock
import kotlin.time.Instant

object PlaybackEventLog {
    const val MAX_EVENTS = 500

    val events = PlaybackEventBuffer(MAX_EVENTS)

    fun debug(
        kind: PlaybackEventKind,
        message: String,
        videoId: String? = null,
        clientName: String? = null,
        profileId: String? = null,
        details: Map<String, String> = emptyMap(),
        error: Throwable? = null,
    ) = record(
        PlaybackEventLevel.DEBUG,
        kind,
        message,
        videoId,
        clientName,
        profileId,
        details,
        error,
    )

    fun info(
        kind: PlaybackEventKind,
        message: String,
        videoId: String? = null,
        clientName: String? = null,
        profileId: String? = null,
        details: Map<String, String> = emptyMap(),
    ) = record(PlaybackEventLevel.INFO, kind, message, videoId, clientName, profileId, details)

    fun warn(
        kind: PlaybackEventKind,
        message: String,
        videoId: String? = null,
        clientName: String? = null,
        profileId: String? = null,
        details: Map<String, String> = emptyMap(),
        error: Throwable? = null,
    ) = record(
        PlaybackEventLevel.WARN,
        kind,
        message,
        videoId,
        clientName,
        profileId,
        details,
        error,
    )

    fun error(
        kind: PlaybackEventKind,
        message: String,
        videoId: String? = null,
        clientName: String? = null,
        profileId: String? = null,
        details: Map<String, String> = emptyMap(),
        error: Throwable? = null,
    ) = record(
        PlaybackEventLevel.ERROR,
        kind,
        message,
        videoId,
        clientName,
        profileId,
        details,
        error,
    )

    fun record(
        level: PlaybackEventLevel,
        kind: PlaybackEventKind,
        message: String,
        videoId: String? = null,
        clientName: String? = null,
        profileId: String? = null,
        details: Map<String, String> = emptyMap(),
        error: Throwable? = null,
    ) {
        val timestampMs = Clock.System.now().toEpochMilliseconds()
        events.append { id ->
            PlaybackEvent(
                id = id,
                timestampMs = timestampMs,
                level = level,
                kind = kind,
                message = mergeMessage(message, error),
                videoId = videoId,
                clientName = clientName,
                profileId = profileId,
                details = if (error == null) details else details + throwableDiagnostics(error),
            )
        }
    }

    fun error(
        kind: PlaybackEventKind,
        tag: String,
        message: String,
        error: Throwable,
    ) {
        val timestampMs = Clock.System.now().toEpochMilliseconds()
        events.append { id ->
            PlaybackEvent(
                id = id,
                timestampMs = timestampMs,
                level = PlaybackEventLevel.ERROR,
                kind = kind,
                message = "[$tag] $message (${error::class.simpleName ?: "failure"})",
                details = throwableDiagnostics(error),
            )
        }
    }

    fun captureYtLogger(
        level: PlaybackEventLevel,
        tag: String,
        message: String,
        error: Throwable? = null,
        videoId: String? = null,
        details: Map<String, String> = emptyMap(),
    ) {
        record(
            level = level,
            kind = kindForYtTag(tag),
            message = "[$tag] $message",
            videoId = videoId,
            clientName = details["client"],
            details = details,
            error = error,
        )
    }

    fun clear() {
        events.clear()
    }

    fun exportText(
        maxEvents: Int = MAX_EVENTS,
        maxChars: Int = Int.MAX_VALUE,
    ): String {
        val allEvents = events.value
        val snapshot = allEvents.takeLast(maxEvents.coerceAtLeast(0))
        val header =
            buildString {
                appendLine("Metrolist playback event log")
                appendLine("Exported: ${Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())}")
                appendLine("Events: ${snapshot.size} of ${allEvents.size}")
                appendLine("Environment:")
                formatPlaybackLogDetails(playbackEnvironmentSnapshot()).lineSequence().forEach { line ->
                    appendLine(line)
                }
                appendLine("---")
            }
        if (header.length >= maxChars) return header.take(maxChars.coerceAtLeast(0))

        val availableChars = maxChars - header.length
        val lines = ArrayDeque<String>()
        var bodyLength = 0
        for (event in snapshot.asReversed()) {
            val line = "${event.toLogLine()}\n"
            if (line.length > availableChars - bodyLength) break
            lines.addFirst(line)
            bodyLength += line.length
        }
        if (lines.size == snapshot.size) return header + lines.joinToString("")

        val marker = "--- older events omitted ---\n"
        while (lines.isNotEmpty() && bodyLength + marker.length > availableChars) {
            bodyLength -= lines.removeFirst().length
        }
        return (header + marker + lines.joinToString("")).take(maxChars.coerceAtLeast(0))
    }

    private fun mergeMessage(
        message: String,
        error: Throwable?,
    ): String {
        if (error == null) return message
        val described = PlaybackErrorMessages.describe(error)
        return if (message.isBlank()) {
            described
        } else if (described.isBlank() || message.contains(described)) {
            message
        } else {
            "$message $described"
        }
    }

    private fun kindForYtTag(tag: String): PlaybackEventKind =
        when {
            tag.contains("Sabr", ignoreCase = true) -> {
                PlaybackEventKind.SABR
            }

            tag.contains("Token", ignoreCase = true) || tag.contains("BotGuard", ignoreCase = true) -> {
                PlaybackEventKind.TOKEN
            }

            tag.contains("Cipher", ignoreCase = true) ||
                tag.contains("Ejs", ignoreCase = true) ||
                tag.contains("PlayerConfig", ignoreCase = true) -> {
                PlaybackEventKind.CIPHER
            }

            tag.contains("StreamResolver", ignoreCase = true) ||
                tag.contains("InnerTubeExtractor", ignoreCase = true) ||
                tag.contains("YtConfig", ignoreCase = true) -> {
                PlaybackEventKind.RESOLVE
            }

            tag.contains("PlayerClient", ignoreCase = true) || tag == "InnerTube" -> {
                PlaybackEventKind.CLIENT
            }

            tag.contains("MusicVideo", ignoreCase = true) || tag.contains("Player", ignoreCase = true) -> {
                PlaybackEventKind.PLAYER
            }

            else -> {
                PlaybackEventKind.TRACE
            }
        }
}
