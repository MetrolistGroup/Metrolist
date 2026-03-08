package com.metrolist.shared.playback

internal fun iosPlaybackSampleIntervalMs(uiObserving: Boolean): Long = if (uiObserving) 100L else 1_000L

internal fun nextIosPlaybackBoundaryMs(
    positionMs: Long,
    durationMs: Long,
    fadeDurationMs: Long,
    silenceIntervals: List<SilenceInterval>,
    lastSilenceSkipEndMs: Long,
    preparationDurationMs: Long = durationMs,
): Long? {
    val transitionPoints =
        buildList {
            add(3_001L)
            if (durationMs > 0L) {
                add(durationMs)
                if (fadeDurationMs > 0L) add((durationMs - fadeDurationMs).coerceAtLeast(1L))
            }
            if (preparationDurationMs > 0L) add(minOf(15_000L, (preparationDurationMs - 30_000L).coerceAtLeast(1L)))
            silenceIntervals.forEach { if (it.endMs > lastSilenceSkipEndMs) add(it.startMs) }
        }
    return transitionPoints.filter { it > positionMs }.minOrNull()
}
