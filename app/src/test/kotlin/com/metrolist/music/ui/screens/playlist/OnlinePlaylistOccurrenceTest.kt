/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.playlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlinePlaylistOccurrenceTest {
    @Test
    fun `duplicate videos have distinct selection keys`() {
        assertNotEquals(
            playlistOccurrenceKey("song-1", "setVideoId-A", 0),
            playlistOccurrenceKey("song-1", "setVideoId-B", 1),
        )
    }

    @Test
    fun `active state compares occurrence ids`() {
        assertTrue(isSamePlaylistOccurrence("song-1", "setVideoId-A", "song-1", "setVideoId-A"))
        assertFalse(isSamePlaylistOccurrence("song-1", "setVideoId-A", "song-1", "setVideoId-B"))
    }

    @Test
    fun `missing occurrence ids fall back to source positions`() {
        assertNotEquals(
            playlistOccurrenceKey("song-1", null, 0),
            playlistOccurrenceKey("song-1", null, 1),
        )
    }
}
