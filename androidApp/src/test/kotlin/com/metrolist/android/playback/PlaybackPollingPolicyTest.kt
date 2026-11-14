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
