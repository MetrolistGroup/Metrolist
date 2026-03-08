package com.metrolist.shared.utils

fun getCurrentTimeMillis(): Long = MetrolistClock.nowMs()

fun formatDuration(
    durationMs: Long,
    hideLeadingZeroUnits: Boolean = false,
): String {
    if (durationMs <= 0) return if (hideLeadingZeroUnits) "00" else "0:00"
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours.toString().padStart(
            if (hideLeadingZeroUnits) 2 else 1,
            '0',
        )}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"

        minutes > 0 -> "${minutes.toString().padStart(if (hideLeadingZeroUnits) 2 else 1, '0')}:${seconds.toString().padStart(2, '0')}"

        hideLeadingZeroUnits -> seconds.toString().padStart(2, '0')

        else -> "0:${seconds.toString().padStart(2, '0')}"
    }
}

fun formatQueueDuration(durationMs: Long?): String = durationMs?.takeIf { it > 0L }?.let(::formatDuration) ?: "--:--"
