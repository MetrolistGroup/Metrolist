package com.metrolist.shared.playback

internal class WatchTimeSchedule(
    scheduledSeconds: List<Int>?,
    defaultIntervalSeconds: Int?,
) {
    private val scheduledOffsetsMs =
        scheduledSeconds
            .orEmpty()
            .asSequence()
            .filter { it > 0 }
            .distinct()
            .sorted()
            .map { it.toLong() * 1000L }
            .toList()
    private val defaultIntervalMs = defaultIntervalSeconds?.takeIf { it > 0 }?.toLong()?.times(1000L)

    fun flushOffsetMs(index: Int): Long? {
        require(index >= 0) { "Flush index cannot be negative" }
        if (index < scheduledOffsetsMs.size) return scheduledOffsetsMs[index]
        val interval = defaultIntervalMs ?: return null
        val base = scheduledOffsetsMs.lastOrNull() ?: 0L
        return base + (index - scheduledOffsetsMs.size + 1L) * interval
    }
}
