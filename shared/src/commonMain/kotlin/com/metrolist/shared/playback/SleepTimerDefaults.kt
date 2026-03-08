package com.metrolist.shared.playback

data class SleepTimerDefaults(
    val durationMinutes: Int = 15,
    val finishCurrentSong: Boolean = false,
    val fadeOut: Boolean = false,
)

fun sleepTimerDurationMs(
    hours: String,
    minutes: String,
): Long? {
    if (hours.length > 2 || minutes.length > 2 || (hours + minutes).any { it !in '0'..'9' }) return null
    val hour = if (hours.isEmpty()) 0 else hours.toIntOrNull() ?: return null
    val minute = if (minutes.isEmpty()) 0 else minutes.toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return ((hour * 60L + minute) * 60_000L).takeIf { it > 0L }
}
