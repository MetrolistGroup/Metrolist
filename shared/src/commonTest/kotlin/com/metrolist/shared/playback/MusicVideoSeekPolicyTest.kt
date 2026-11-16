package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicVideoSeekPolicyTest {
    @Test
    fun pendingSeeksRequireForegroundReadyItemAndNoSeekInFlight() {
        assertTrue(canApplyMusicVideoSeek(isForeground = true, seekInFlight = false, itemReady = true))
        assertFalse(canApplyMusicVideoSeek(isForeground = false, seekInFlight = false, itemReady = true))
        assertFalse(canApplyMusicVideoSeek(isForeground = true, seekInFlight = true, itemReady = true))
        assertFalse(canApplyMusicVideoSeek(isForeground = true, seekInFlight = false, itemReady = false))
    }

    @Test
    fun foregroundResumeRequestsResynchronization() {
        assertTrue(shouldResynchronizeMusicVideoOnForeground(wasForeground = false, isForeground = true))
        assertFalse(shouldResynchronizeMusicVideoOnForeground(wasForeground = true, isForeground = true))
        assertFalse(shouldResynchronizeMusicVideoOnForeground(wasForeground = false, isForeground = false))
        // Seek eligibility intentionally ignores playback intent so paused video can sync on resume.
        assertTrue(canApplyMusicVideoSeek(isForeground = true, seekInFlight = false, itemReady = true))
    }

    @Test
    fun seekCompletionIsIgnoredWhenBackgroundedCancelledOrSuperseded() {
        assertTrue(isCurrentMusicVideoSeek(completedSeekGeneration = 3L, currentSeekGeneration = 3L))
        assertFalse(isCurrentMusicVideoSeek(completedSeekGeneration = 2L, currentSeekGeneration = 3L))
        assertTrue(shouldAcceptMusicVideoSeekCompletion(isForeground = true, finished = true))
        assertFalse(shouldAcceptMusicVideoSeekCompletion(isForeground = false, finished = true))
        assertFalse(shouldAcceptMusicVideoSeekCompletion(isForeground = true, finished = false))
    }

    @Test
    fun successfulSeekRevealsVideoUnlessAnotherUserSeekIsPending() {
        assertTrue(shouldRevealMusicVideoAfterSeek(finished = true, userSeekPending = false))
        assertFalse(shouldRevealMusicVideoAfterSeek(finished = true, userSeekPending = true))
        assertFalse(shouldRevealMusicVideoAfterSeek(finished = false, userSeekPending = false))
    }

    @Test
    fun targetChangesIgnoreNormalPlaybackAdvanceButDetectExplicitSeeks() {
        assertFalse(isMaterialMusicVideoTargetChange(targetDeltaMs = 1_200L, expectedPlaybackAdvanceMs = 1_000L, toleranceMs = 1_500L))
        assertTrue(isMaterialMusicVideoTargetChange(targetDeltaMs = 8_000L, expectedPlaybackAdvanceMs = 1_000L, toleranceMs = 1_500L))
        assertTrue(isMaterialMusicVideoTargetChange(targetDeltaMs = -4_000L, expectedPlaybackAdvanceMs = 1_000L, toleranceMs = 1_500L))
    }
}
