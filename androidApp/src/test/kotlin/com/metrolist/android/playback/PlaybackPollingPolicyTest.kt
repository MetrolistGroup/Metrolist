package com.metrolist.android.playback

import androidx.media3.common.Player
import com.metrolist.shared.playback.PlaybackProgressWatchdog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPollingPolicyTest {
    @Test
    fun pausedSuppressedIdleAndEndedPlayersDoNotSampleProgress() {
        for (state in listOf(Player.STATE_IDLE, Player.STATE_BUFFERING, Player.STATE_READY, Player.STATE_ENDED)) {
            assertFalse(shouldSamplePlaybackProgress(false, false, state))
        }
        assertFalse(shouldSamplePlaybackProgress(false, true, Player.STATE_READY))
        assertFalse(shouldSamplePlaybackProgress(false, true, Player.STATE_IDLE))
        assertFalse(shouldSamplePlaybackProgress(false, true, Player.STATE_ENDED))
        assertTrue(shouldSamplePlaybackProgress(false, true, Player.STATE_BUFFERING))
        assertTrue(shouldSamplePlaybackProgress(true, true, Player.STATE_READY))
    }

    @Test
    fun liveOutputStallUsesRendererPositionInsteadOfElapsedSessionPosition() {
        assertEquals(
            71_000L,
            playbackProgressPositionMs(isLive = true, renderedPositionMs = 71_000L, reportedPositionMs = 72_000L),
        )
        assertEquals(
            72_000L,
            playbackProgressPositionMs(isLive = false, renderedPositionMs = 71_000L, reportedPositionMs = 72_000L),
        )
    }

    @Test
    fun briefBufferedRebufferDoesNotTriggerRecovery() {
        val watchdog = PlaybackProgressWatchdog()

        fun sample(
            position: Long,
            state: Int,
            time: Long,
        ) = watchdog.observe(
            9L,
            position,
            219_000L,
            shouldMonitorPlaybackProgress(true, Player.PLAYBACK_SUPPRESSION_REASON_NONE, state, false, true),
            time,
        )
        assertFalse(sample(66_000L, Player.STATE_READY, 0L))
        assertFalse(sample(66_927L, Player.STATE_BUFFERING, 1_000L))
        assertFalse(sample(66_927L, Player.STATE_READY, 1_165L))
        assertFalse(sample(67_869L, Player.STATE_READY, 2_761L))
    }

    @Test
    fun frozenReadyOutputTriggersRecoveryWithBackgroundPollingForPcmAndOffload() {
        for (offload in listOf(false, true)) {
            val watchdog = PlaybackProgressWatchdog()
            val expectProgress =
                shouldMonitorPlaybackProgress(true, Player.PLAYBACK_SUPPRESSION_REASON_NONE, Player.STATE_READY, false, offload)
            for (elapsed in 0L until 12_000L step interval(position = 70_000, duration = 219_000)) {
                assertFalse(watchdog.observe(1L, 70_000L, 219_000L, expectProgress, elapsed))
            }
            assertTrue(watchdog.observe(1L, 70_000L, 219_000L, expectProgress, 12_000L))
            assertFalse(watchdog.observe(1L, 70_000L, 219_000L, expectProgress, 13_000L))
        }
    }

    @Test
    fun offloadSleepTimeoutIgnoresGeneralWatchdogEndTolerance() {
        val watchdog = PlaybackProgressWatchdog()
        val tracker = OffloadSleepStallTracker()
        watchdog.observe(1L, 59_000L, 60_000L, true, 1_000L)
        tracker.onSleepingForOffload(nowMs = 1_000L, outputPosition = 10L)

        assertFalse(watchdog.observe(1L, 59_000L, 60_000L, true, 2_999L))
        assertEquals(0L, watchdog.stalledForMs(2_999L))
        assertFalse(tracker.shouldDisableOffload(outputPosition = 10L, expectProgress = true, nowMs = 2_999L))
        assertFalse(watchdog.observe(1L, 59_000L, 60_000L, true, 3_000L))
        assertEquals(0L, watchdog.stalledForMs(3_000L))
        assertTrue(tracker.shouldDisableOffload(outputPosition = 10L, expectProgress = true, nowMs = 3_000L))
    }

    @Test
    fun reportedOutputStallDisablesOffloadOnlyAfterTimeout() {
        val tracker = OffloadSleepStallTracker()
        tracker.onSleepingForOffload(nowMs = 1_000L, outputPosition = 9L)

        assertFalse(tracker.shouldDisableOffload(outputPosition = 10L, expectProgress = true, nowMs = 1_500L))
        assertFalse(tracker.shouldDisableOffload(outputPosition = 10L, expectProgress = true, nowMs = 3_499L))
        assertTrue(tracker.shouldDisableOffload(outputPosition = 10L, expectProgress = true, nowMs = 3_500L))
    }

    @Test
    fun missingOutputPositionLeavesStallDetectionToWatchdog() {
        for (initialOutputPosition in listOf(null, 10L)) {
            val tracker = OffloadSleepStallTracker()
            val watchdog = PlaybackProgressWatchdog()
            tracker.onSleepingForOffload(nowMs = 1_000L, outputPosition = initialOutputPosition)
            assertFalse(watchdog.observe(1L, 70_000L, 219_000L, true, 1_000L))

            assertFalse(tracker.shouldDisableOffload(outputPosition = null, expectProgress = true, nowMs = 1_500L))
            assertFalse(tracker.shouldDisableOffload(outputPosition = null, expectProgress = true, nowMs = 3_000L))
            assertFalse(tracker.shouldDisableOffload(outputPosition = null, expectProgress = true, nowMs = 13_000L))
            assertTrue(watchdog.observe(1L, 70_000L, 219_000L, true, 13_000L))
        }
    }

    @Test
    fun pausedOrSuppressedPlaybackDoesNotDisableOffloadUntilProgressIsExpectedAgain() {
        val tracker = OffloadSleepStallTracker()
        tracker.onSleepingForOffload(nowMs = 1_000L, outputPosition = 10L)

        assertFalse(tracker.shouldDisableOffload(outputPosition = 10L, expectProgress = false, nowMs = 8_000L))
        assertFalse(tracker.shouldDisableOffload(outputPosition = 10L, expectProgress = true, nowMs = 9_999L))
        assertTrue(tracker.shouldDisableOffload(outputPosition = 10L, expectProgress = true, nowMs = 11_999L))
    }

    @Test
    fun resumingAfterLongPausedSamplingGapRestartsOffloadStallDeadline() {
        val tracker = OffloadSleepStallTracker()
        tracker.onSleepingForOffload(nowMs = 1_000L, outputPosition = 10L)

        assertFalse(tracker.shouldDisableOffload(outputPosition = 10L, expectProgress = false, nowMs = 8_000L))
        assertFalse(tracker.shouldDisableOffload(outputPosition = 10L, expectProgress = true, nowMs = 60_000L))
        assertFalse(tracker.shouldDisableOffload(outputPosition = 10L, expectProgress = true, nowMs = 61_999L))
        assertTrue(tracker.shouldDisableOffload(outputPosition = 10L, expectProgress = true, nowMs = 62_000L))
    }

    @Test
    fun wakingFromOffloadSleepClearsStallTrackingBeforeLaterBuffering() {
        val tracker = OffloadSleepStallTracker()
        tracker.onSleepingForOffload(nowMs = 1_000L, outputPosition = 10L)

        tracker.reset()

        assertFalse(tracker.shouldDisableOffload(outputPosition = 10L, expectProgress = true, nowMs = 9_000L))
    }

    @Test
    fun pcmNetworkBufferingDoesNotConsumeTheOutputStallDeadline() {
        val watchdog = PlaybackProgressWatchdog()

        fun sample(
            state: Int,
            time: Long,
        ) = watchdog.observe(
            playbackInstanceId = 1L,
            positionMs = 70_000L,
            durationMs = 219_000L,
            expectProgress = shouldMonitorPlaybackProgress(true, Player.PLAYBACK_SUPPRESSION_REASON_NONE, state, false, false),
            nowMs = time,
        )
        assertFalse(sample(Player.STATE_READY, 0L))
        assertFalse(sample(Player.STATE_BUFFERING, 11_000L))
        assertFalse(sample(Player.STATE_BUFFERING, 60_000L))
        assertFalse(sample(Player.STATE_READY, 61_000L))
        assertFalse(sample(Player.STATE_READY, 72_000L))
        assertTrue(sample(Player.STATE_READY, 73_000L))
        assertTrue(shouldMonitorPlaybackProgress(true, Player.PLAYBACK_SUPPRESSION_REASON_NONE, Player.STATE_BUFFERING, false, true))
    }

    @Test
    fun pausedSuppressedIdleEndedAndRecoveringPlayersDoNotTriggerOutputRecovery() {
        for (offload in listOf(false, true)) {
            for (state in listOf(Player.STATE_IDLE, Player.STATE_BUFFERING, Player.STATE_READY, Player.STATE_ENDED)) {
                assertFalse(shouldMonitorPlaybackProgress(false, Player.PLAYBACK_SUPPRESSION_REASON_NONE, state, false, offload))
                assertFalse(
                    shouldMonitorPlaybackProgress(
                        true,
                        Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
                        state,
                        false,
                        offload,
                    ),
                )
                assertFalse(shouldMonitorPlaybackProgress(true, Player.PLAYBACK_SUPPRESSION_REASON_NONE, state, true, offload))
            }
            assertFalse(shouldMonitorPlaybackProgress(true, Player.PLAYBACK_SUPPRESSION_REASON_NONE, Player.STATE_IDLE, false, offload))
            assertFalse(shouldMonitorPlaybackProgress(true, Player.PLAYBACK_SUPPRESSION_REASON_NONE, Player.STATE_ENDED, false, offload))
        }
    }

    @Test
    fun foregroundMiniPlayerAndExpandedPlayerPollFinelyWhileBackgroundStaysCoarse() {
        assertEquals(1_000L, interval(position = 20_000))
        assertEquals(1_000L, interval(position = 59_900, enabled = false))
        assertEquals(1_000L, interval(position = 20_000, duration = -1))
        for (position in listOf(20_000L, 59_900L)) {
            assertEquals(100L, interval(position = position, fineProgressActive = true, enabled = false))
        }
        assertEquals(100L, interval(position = 58_500, speed = 2f))
        assertEquals(1_000L, interval(position = 56_000, speed = 2f))
    }

    @Test
    fun backgroundSamplesEnterShortCrossfadeWindowAtDifferentSpeedsAndTickOffsets() {
        for (speed in listOf(0.5f, 1f, 2f, 3f)) {
            for (offset in 0L until 1_000L step 100L) {
                var position = 54_000L + offset
                var enteredWindow = false
                while (position < 60_000L) {
                    if (position >= 59_000L) enteredWindow = true
                    position += (interval(position, speed) * speed).toLong()
                }
                assertTrue("Missed transition at speed=$speed offset=$offset", enteredWindow)
            }
        }
    }

    @Test
    fun longCrossfadesWakeBeforeTheirOwnBoundary() {
        assertEquals(100L, playbackPollIntervalMs(false, 49_000, 60_000, 2f, true, 10_000))
        assertEquals(1_000L, playbackPollIntervalMs(false, 45_000, 60_000, 2f, true, 10_000))
    }

    private fun interval(
        position: Long,
        speed: Float = 1f,
        duration: Long = 60_000L,
        fineProgressActive: Boolean = false,
        enabled: Boolean = true,
    ) = playbackPollIntervalMs(fineProgressActive, position, duration, speed, enabled, 1_000L)
}
