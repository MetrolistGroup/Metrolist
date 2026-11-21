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
    val hour = sleepTimerFieldValue(hours) ?: return null
    val minute = sleepTimerFieldValue(minutes) ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return ((hour * 60L + minute) * 60_000L).takeIf { it > 0L }
}

internal fun sleepTimerFieldValue(input: String): Int? {
    if (input.length > 2) return null
    var number = 0
    for (character in input) {
        val digit = character.digitToIntOrNull() ?: return null
        number = number * 10 + digit
    }
    return number
}
