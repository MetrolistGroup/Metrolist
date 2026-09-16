package com.metrolist.music.listentogether

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSyncTest {
    @Test
    fun heartbeatDriftDoesNotInterruptActivePlayback() {
        listOf(0L, 50L, 750L, 1_999L, 2_000L).forEach { positionDifferenceMs ->
            assertFalse(shouldSeekDuringActivePlayback(positionDifferenceMs, playbackReady = true))
        }
        assertFalse(shouldSeekDuringActivePlayback(10_000L, playbackReady = false))
        assertTrue(shouldSeekDuringActivePlayback(2_001L, playbackReady = true))
    }
}
