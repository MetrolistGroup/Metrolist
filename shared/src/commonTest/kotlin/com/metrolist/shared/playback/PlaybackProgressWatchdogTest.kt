package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackProgressWatchdogTest {
    @Test
    fun rearmRestartsTheTimeoutAfterAnOffloadFallback() {
        val watchdog = PlaybackProgressWatchdog(timeoutMs = 12_000L)

        assertFalse(watchdog.observe(playbackInstanceId = 1L, positionMs = 1_000L, durationMs = 60_000L, expectProgress = true, nowMs = 0L))
        assertTrue(
            watchdog.observe(playbackInstanceId = 1L, positionMs = 1_000L, durationMs = 60_000L, expectProgress = true, nowMs = 12_000L),
        )
        assertFalse(
            watchdog.observe(playbackInstanceId = 1L, positionMs = 1_000L, durationMs = 60_000L, expectProgress = true, nowMs = 12_000L),
        )

        watchdog.rearm(nowMs = 12_000L)

        assertFalse(
            watchdog.observe(playbackInstanceId = 1L, positionMs = 1_000L, durationMs = 60_000L, expectProgress = true, nowMs = 23_999L),
        )
        assertTrue(
            watchdog.observe(playbackInstanceId = 1L, positionMs = 1_000L, durationMs = 60_000L, expectProgress = true, nowMs = 24_000L),
        )
    }
}
