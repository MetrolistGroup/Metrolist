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
    fun targetChangesIgnoreNormalPlaybackAdvanceButDetectExplicitSeeks() {
        assertFalse(isMaterialMusicVideoTargetChange(targetDeltaMs = 1_200L, expectedPlaybackAdvanceMs = 1_000L, toleranceMs = 1_500L))
        assertTrue(isMaterialMusicVideoTargetChange(targetDeltaMs = 8_000L, expectedPlaybackAdvanceMs = 1_000L, toleranceMs = 1_500L))
        assertTrue(isMaterialMusicVideoTargetChange(targetDeltaMs = -4_000L, expectedPlaybackAdvanceMs = 1_000L, toleranceMs = 1_500L))
    }
}
