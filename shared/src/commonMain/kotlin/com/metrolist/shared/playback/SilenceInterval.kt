package com.metrolist.shared.playback

import kotlin.math.pow

data class SilenceInterval(
    val startMs: Long,
    val endMs: Long,
)

fun detectSilenceIntervals(
    rmsWindows: List<Float>,
    windowDurationMs: Long = 100L,
    minimumSilenceMs: Long = 500L,
    thresholdDb: Float = -50f,
): List<SilenceInterval> {
    require(windowDurationMs > 0L)
    require(minimumSilenceMs > 0L)
    val threshold = 10.0.pow(thresholdDb.toDouble() / 20.0).toFloat()
    val minimumWindows = (minimumSilenceMs + windowDurationMs - 1L) / windowDurationMs
    val intervals = mutableListOf<SilenceInterval>()
    var start = -1
    rmsWindows.forEachIndexed { index, rms ->
        val silent = rms.isFinite() && rms >= 0f && rms <= threshold
        if (silent && start < 0) start = index
        if (!silent && start >= 0) {
            if (index - start >= minimumWindows) intervals += SilenceInterval(start * windowDurationMs, index * windowDurationMs)
            start = -1
        }
    }
    if (start >= 0 && rmsWindows.size - start >= minimumWindows) {
        intervals += SilenceInterval(start * windowDurationMs, rmsWindows.size * windowDurationMs)
    }
    return intervals
}
