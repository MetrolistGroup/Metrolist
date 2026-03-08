package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class SilenceMapTest {
    @Test
    fun onlyDecodedLowEnergyRunsLongerThanThresholdBecomeSilence() {
        val intervals =
            detectSilenceIntervals(
                rmsWindows = listOf(0f, 0.001f, 0.001f, 0.001f, 0.001f, 0.02f, 0.001f, 0.001f),
                windowDurationMs = 100L,
                minimumSilenceMs = 400L,
            )

        assertEquals(listOf(SilenceInterval(0L, 500L)), intervals)
    }

    @Test
    fun trailingSilenceIsRetainedAndNonFiniteSamplesBreakRuns() {
        assertEquals(
            listOf(SilenceInterval(400L, 800L)),
            detectSilenceIntervals(
                rmsWindows = listOf(0.1f, 0f, 0f, Float.NaN, 0f, 0f, 0f, 0f),
                windowDurationMs = 100L,
                minimumSilenceMs = 300L,
            ),
        )
    }
}
