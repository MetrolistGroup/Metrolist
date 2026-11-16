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
}
