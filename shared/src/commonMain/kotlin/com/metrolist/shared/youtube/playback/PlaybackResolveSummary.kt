package com.metrolist.shared.youtube.playback

import kotlin.time.Clock

/** Searchable tags and a readable timeline of the player-client attempts behind a playback failure. */
data class PlaybackResolveSummary(
    val tags: Map<String, String>,
    val extras: Map<String, String>,
)

fun playbackResolveSummary(
    events: List<PlaybackEvent>,
    videoId: String?,
    nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    windowMs: Long = 90_000L,
): PlaybackResolveSummary? {
    val relevant =
        events.filter { event ->
            event.timestampMs >= nowMs - windowMs &&
                event.kind in setOf(PlaybackEventKind.CLIENT, PlaybackEventKind.TOKEN, PlaybackEventKind.RESOLVE) &&
                (videoId == null || event.videoId == null || event.videoId == videoId)
        }
    if (relevant.isEmpty()) return null
    val clients = relevant.mapNotNull { it.details["client"] ?: it.clientName }.distinct()
    val failures =
        relevant.mapNotNull { event ->
            val client = event.details["client"] ?: event.clientName ?: return@mapNotNull null
            when {
                event.message.endsWith("player request failed") -> "$client:${event.details["exceptionType"] ?: "request"}"
                event.details["tokenUnavailable"] == "true" -> "$client:token_unavailable"
                event.details["requestFailure"] == "true" -> "$client:request_failure"
                else -> null
            }
        }
    val tokenEvents = relevant.filter { it.message.endsWith("token fetch completed") }
    val slowestTokenMs = tokenEvents.mapNotNull { it.details["elapsedMs"]?.toLongOrNull() }.maxOrNull()
    val first = relevant.first().timestampMs
    val timeline =
        relevant.takeLast(MAX_TIMELINE_LINES).joinToString("\n") { event ->
            val offset = (event.timestampMs - first) / 100 / 10.0
            val details = event.details.entries.joinToString(" ") { (key, value) -> "$key=$value" }
            "+${offset}s ${event.kind} ${event.message} $details".trim()
        }
    val tags =
        buildMap {
            if (clients.isNotEmpty()) put("playback.clients_tried", clients.joinToString(">").take(MAX_TAG_LENGTH))
            failures.lastOrNull()?.let { put("playback.last_client_failure", it.take(MAX_TAG_LENGTH)) }
            put("playback.token_failures", tokenEvents.count { it.details["tokenPresent"] == "false" }.toString())
            slowestTokenMs?.let { put("playback.slowest_token", durationBucket(it)) }
            put("playback.resolve_span", durationBucket(relevant.last().timestampMs - first))
        }
    val extras =
        buildMap {
            if (failures.isNotEmpty()) put("playback.client_failures", failures.joinToString(" -> "))
            slowestTokenMs?.let { put("playback.slowest_token_ms", it.toString()) }
            put("playback.resolve_timeline", timeline)
        }
    return PlaybackResolveSummary(tags, extras)
}

private fun durationBucket(ms: Long): String =
    when {
        ms < 1_000L -> "<1s"
        ms < 5_000L -> "1-5s"
        ms < 15_000L -> "5-15s"
        ms < 30_000L -> "15-30s"
        else -> ">30s"
    }

private const val MAX_TIMELINE_LINES = 60
private const val MAX_TAG_LENGTH = 200
