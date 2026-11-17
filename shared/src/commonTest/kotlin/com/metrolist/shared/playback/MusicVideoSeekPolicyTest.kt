package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MusicVideoSeekPolicyTest {
    @Test
    fun backgroundingCancelsSeekAndRejectsItsLateCompletion() {
        val lifecycle = MusicVideoSeekLifecycle()
        lifecycle.setForeground(true)
        val backgroundedSeek = assertNotNull(lifecycle.beginSeek())

        lifecycle.setForeground(false)

        assertFalse(lifecycle.completeSeek(backgroundedSeek, finished = true))
        assertFalse(lifecycle.positionSynchronized)

        lifecycle.setForeground(true)
        val resumedSeek = assertNotNull(lifecycle.beginSeek())
        assertFalse(lifecycle.completeSeek(backgroundedSeek, finished = true))
        assertFalse(lifecycle.positionSynchronized)
        assertTrue(lifecycle.completeSeek(resumedSeek, finished = true))
        assertTrue(lifecycle.positionSynchronized)
    }

    @Test
    fun explicitSeekClearsInitialSynchronizationUntilItsCompletion() {
        val lifecycle = MusicVideoSeekLifecycle()
        lifecycle.setForeground(true)
        lifecycle.markSynchronized()
        assertTrue(lifecycle.positionSynchronized)

        lifecycle.markUnsynchronized()
        val userSeek = assertNotNull(lifecycle.beginSeek())
        assertFalse(lifecycle.positionSynchronized)
        assertTrue(lifecycle.completeSeek(userSeek, finished = true))
        assertTrue(lifecycle.positionSynchronized)
    }

    @Test
    fun hiddenSurfaceCanInvalidatePreviouslySynchronizedPosition() {
        val lifecycle = MusicVideoSeekLifecycle()
        lifecycle.setForeground(true)
        lifecycle.markSynchronized()

        lifecycle.markUnsynchronized()

        assertFalse(lifecycle.positionSynchronized)
    }

    @Test
    fun cancellationAndFailedCompletionNeverRevealTheVideo() {
        val lifecycle = MusicVideoSeekLifecycle()
        lifecycle.setForeground(true)
        val cancelledSeek = assertNotNull(lifecycle.beginSeek())
        lifecycle.cancelSeek()
        assertFalse(lifecycle.completeSeek(cancelledSeek, finished = true))

        val failedSeek = assertNotNull(lifecycle.beginSeek())
        assertFalse(lifecycle.completeSeek(failedSeek, finished = false))
        assertFalse(lifecycle.positionSynchronized)
    }

    @Test
    fun targetChangesAccountForExpectedPlaybackAdvance() {
        assertFalse(isMaterialMusicVideoTargetChange(targetDeltaMs = 1_200L, expectedPlaybackAdvanceMs = 1_000L, toleranceMs = 1_500L))
        assertTrue(isMaterialMusicVideoTargetChange(targetDeltaMs = 8_000L, expectedPlaybackAdvanceMs = 1_000L, toleranceMs = 1_500L))
        assertTrue(isMaterialMusicVideoTargetChange(targetDeltaMs = -4_000L, expectedPlaybackAdvanceMs = 1_000L, toleranceMs = 1_500L))
    }

    @Test
    fun repeatedSlowDriftCorrectionsStopUntilTheNextUserSeek() {
        val policy = MusicVideoDriftRetryPolicy(slowSeekThresholdMs = 1_500L, maximumSlowSeeks = 2)

        assertTrue(policy.shouldRetry(isUserSeek = false))
        policy.recordSeek(2_000L)
        assertTrue(policy.shouldRetry(isUserSeek = false))
        policy.recordSeek(2_000L)
        assertFalse(policy.shouldRetry(isUserSeek = false))
        assertTrue(policy.exhausted)
        assertTrue(policy.shouldRetry(isUserSeek = true))
        policy.reset()
        assertTrue(policy.shouldRetry(isUserSeek = false))
    }
}
