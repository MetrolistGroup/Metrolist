/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibrarySessionCallbackTest {
    @Test
    fun `browse tree parents accept subscriptions`() {
        assertTrue(isBrowsableMediaId(MusicService.ROOT))
        assertTrue(isBrowsableMediaId(MusicService.ARTIST))
        assertTrue(isBrowsableMediaId("${MusicService.ARTIST}/artist-id"))
        assertTrue(isBrowsableMediaId("${MusicService.PLAYLIST}/playlist-id"))
        assertFalse(isBrowsableMediaId("unknown"))
        assertFalse(isBrowsableMediaId("${MusicService.SONG}/song-id"))
    }

    @Test
    fun `children are limited to the requested page`() {
        val children = listOf("one", "two", "three", "four", "five")

        assertEquals(listOf("one", "two"), children.paginate(page = 0, pageSize = 2))
        assertEquals(listOf("three", "four"), children.paginate(page = 1, pageSize = 2))
        assertEquals(listOf("five"), children.paginate(page = 2, pageSize = 2))
        assertEquals(emptyList<String>(), children.paginate(page = 3, pageSize = 2))
    }
}
