package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackProgressWatchdogTest {
    @Test
    fun detectsStartupAndPostSeekStallsWithoutRenderedAudioOrKnownDuration() {
        val watchdog = PlaybackProgressWatchdog()
        assertFalse(watchdog.observe(1L, 0L, 0L, expectProgress = true, nowMs = 0L))
        assertTrue(watchdog.observe(1L, 0L, 0L, expectProgress = true, nowMs = 12_000L))
        assertFalse(watchdog.observe(1L, 45_000L, 180_000L, expectProgress = true, nowMs = 13_000L))
        assertTrue(watchdog.observe(1L, 45_000L, 180_000L, expectProgress = true, nowMs = 25_000L))
    }

    @Test
    fun pauseSuppressionAndRecoveryDoNotAccumulateStallTime() {
        val watchdog = PlaybackProgressWatchdog()
        assertFalse(watchdog.observe(1L, 0L, 180_000L, expectProgress = true, nowMs = 0L))
        assertFalse(watchdog.observe(1L, 0L, 180_000L, expectProgress = false, nowMs = 11_000L))
        assertFalse(watchdog.observe(1L, 0L, 180_000L, expectProgress = false, nowMs = 30_000L))
        assertFalse(watchdog.observe(1L, 0L, 180_000L, expectProgress = true, nowMs = 60_001L))
        assertFalse(watchdog.observe(2L, 0L, 180_000L, expectProgress = true, nowMs = 72_000L))
        assertFalse(watchdog.observe(0L, 0L, 0L, expectProgress = true, nowMs = 90_000L))
        assertFalse(watchdog.observe(0L, 0L, 0L, expectProgress = true, nowMs = 120_000L))
    }

    @Test
    fun reportsPlaybackThatStopsAdvancing() {
        val watchdog = PlaybackProgressWatchdog(timeoutMs = 12_000L)

        assertFalse(watchdog.observe(1L, 10_000L, 180_000L, expectProgress = true, nowMs = 1_000L))
        assertFalse(watchdog.observe(1L, 10_000L, 180_000L, expectProgress = true, nowMs = 12_999L))
        assertTrue(watchdog.observe(1L, 10_000L, 180_000L, expectProgress = true, nowMs = 13_000L))
        assertFalse(watchdog.observe(1L, 10_000L, 180_000L, expectProgress = true, nowMs = 30_000L))
    }

    @Test
    fun resetsForProgressPauseTrackChangeAndNaturalEnd() {
        val watchdog = PlaybackProgressWatchdog(timeoutMs = 12_000L)

        assertFalse(watchdog.observe(1L, 10_000L, 180_000L, expectProgress = true, nowMs = 1_000L))
        assertFalse(watchdog.observe(1L, 11_000L, 180_000L, expectProgress = true, nowMs = 13_000L))
        assertFalse(watchdog.observe(1L, 11_000L, 180_000L, expectProgress = false, nowMs = 30_000L))
        assertFalse(watchdog.observe(2L, 11_000L, 180_000L, expectProgress = true, nowMs = 50_000L))
        assertFalse(watchdog.observe(2L, 179_000L, 180_000L, expectProgress = true, nowMs = 70_000L))
        assertFalse(watchdog.observe(2L, 179_000L, 180_000L, expectProgress = true, nowMs = 100_000L))
    }
}
