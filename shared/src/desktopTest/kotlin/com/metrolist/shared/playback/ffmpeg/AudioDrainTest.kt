package com.metrolist.shared.playback.ffmpeg

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioDrainTest {
    @Test
    fun mixerThatNeverEmptiesCompletesAtTheDrainBound() {
        var nowNs = 0L
        val drained =
            awaitAudioDrain(
                timeoutMs = 400L,
                shouldContinue = { true },
                nanoTime = { nowNs },
                sleep = { nowNs += TimeUnit.MILLISECONDS.toNanos(it) },
                hasPendingAudio = { true },
            )
        assertTrue(drained)
        assertEquals(TimeUnit.MILLISECONDS.toNanos(400L), nowNs)
    }

    @Test
    fun pauseDoesNotConsumeTheRemainingDrainTime() {
        var nowNs = 0L
        var playingNs = 0L
        val drained =
            awaitAudioDrain(
                timeoutMs = 400L,
                shouldContinue = { true },
                awaitResume = { nowNs += TimeUnit.SECONDS.toNanos(10L) },
                nanoTime = { nowNs },
                sleep = {
                    val elapsedNs = TimeUnit.MILLISECONDS.toNanos(it)
                    playingNs += elapsedNs
                    nowNs += elapsedNs
                },
                hasPendingAudio = { true },
            )
        assertTrue(drained)
        assertEquals(TimeUnit.MILLISECONDS.toNanos(400L), playingNs)
    }

    @Test
    fun cancellationAtTheDeadlineDoesNotCompleteTheTrack() {
        var active = true
        var nowNs = 0L
        val drained =
            awaitAudioDrain(
                timeoutMs = 5L,
                shouldContinue = { active },
                nanoTime = { nowNs },
                sleep = {
                    nowNs += TimeUnit.MILLISECONDS.toNanos(it)
                    active = false
                },
                hasPendingAudio = { true },
            )
        assertFalse(drained)
    }

    @Test
    fun cancellationWhilePausedDoesNotPollTheRetiredMixer() {
        var active = true
        val drained =
            awaitAudioDrain(
                timeoutMs = 400L,
                shouldContinue = { active },
                awaitResume = { active = false },
                hasPendingAudio = { error("Retired mixer must not be polled") },
            )
        assertFalse(drained)
    }

    @Test
    fun interruptStillStopsTheDrainWait() {
        assertFailsWith<InterruptedException> {
            awaitAudioDrain(
                timeoutMs = 400L,
                shouldContinue = { true },
                sleep = { throw InterruptedException() },
                hasPendingAudio = { true },
            )
        }
    }
}
