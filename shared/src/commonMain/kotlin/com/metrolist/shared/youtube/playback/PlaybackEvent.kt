package com.metrolist.shared.youtube.playback

import kotlin.time.Instant

enum class PlaybackEventLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

enum class PlaybackEventKind {
    RESOLVE,
    CLIENT,
    STREAM,
    SABR,
    PLAYER,
    QUEUE,
    RECOVERY,
    TOKEN,
    CIPHER,
    TRACE,
    NETWORK,
    IMPORT,
    SYNC,
    OTHER,
}

data class PlaybackEvent(
    val id: Long,
    val timestampMs: Long,
    val level: PlaybackEventLevel,
    val kind: PlaybackEventKind,
    val message: String,
    val videoId: String? = null,
    val clientName: String? = null,
    val profileId: String? = null,
    val details: Map<String, String> = emptyMap(),
) {
    fun toLogLine(): String =
        buildString {
            append(Instant.fromEpochMilliseconds(timestampMs).toString())
            append(' ')
            append(level.name)
            append(' ')
            append(kind.name)
            videoId?.takeIf { it.isNotBlank() }?.let { append(" videoId=").append(it) }
            clientName?.takeIf { it.isNotBlank() }?.let { append(" client=").append(it) }
            profileId?.takeIf { it.isNotBlank() }?.let { append(" profile=").append(it) }
            append('\n')
            append("  ")
            append(message)
            if (details.isNotEmpty()) {
                append('\n')
                append(formatPlaybackLogDetails(details))
            }
        }

    fun detailPreviewLines(limit: Int = 12): List<String> {
        if (details.isEmpty()) return emptyList()
        val lines = formatPlaybackLogDetails(details).lineSequence().toList()
        return if (lines.size <= limit) {
            lines
        } else {
            lines.take(limit) + listOf("  ... +${lines.size - limit} more")
        }
    }
}
