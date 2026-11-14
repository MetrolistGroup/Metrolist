package com.metrolist.shared.discord

data class DiscordPlaybackTimestamps(
    val start: Long,
    val end: Long?,
)

fun discordPlaybackTimestamps(
    now: Long,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    playing: Boolean,
): DiscordPlaybackTimestamps {
    if (!playing || !speed.isFinite() || speed <= 0f) return DiscordPlaybackTimestamps(0, null)
    val start = now - (positionMs.coerceAtLeast(0) / speed.toDouble()).toLong()
    val end = durationMs.takeIf { it > 0 }?.let { now + ((it - positionMs).coerceAtLeast(0) / speed.toDouble()).toLong() }
    return DiscordPlaybackTimestamps(start, end)
}
