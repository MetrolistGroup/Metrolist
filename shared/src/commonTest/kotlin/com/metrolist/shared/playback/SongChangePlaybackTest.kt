package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongChangePlaybackTest {
    @Test
    fun songChangesOnlyKeepAnExistingPauseWhenEnabled() {
        for (hasCurrentSong in listOf(false, true)) {
            for (playbackRequested in listOf(false, true)) {
                assertTrue(shouldPlayAfterSongChange(false, hasCurrentSong, playbackRequested))
            }
        }
        assertFalse(shouldPlayAfterSongChange(true, true, false))
        assertTrue(shouldPlayAfterSongChange(true, true, true))
        assertTrue(shouldPlayAfterSongChange(true, false, false))
    }
}
