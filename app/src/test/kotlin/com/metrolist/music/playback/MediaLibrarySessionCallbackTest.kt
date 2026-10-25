/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaLibrarySessionCallbackTest {
    @Test
    fun `search media ids are parsed into query and song id`() {
        assertEquals("daft punk" to "abc", parseSearchMediaId("${MusicService.SEARCH}/daft punk/abc"))
        assertEquals("ac/dc" to "xyz", parseSearchMediaId("${MusicService.SEARCH}/ac/dc/xyz"))
        assertNull(parseSearchMediaId("song/abc"))
        assertNull(parseSearchMediaId("${MusicService.SEARCH}/noSongId"))
    }

    @Test
    fun `results are limited to the requested page`() {
        val children = listOf("one", "two", "three", "four", "five")

        assertEquals(listOf("one", "two"), children.paginate(page = 0, pageSize = 2))
        assertEquals(listOf("three", "four"), children.paginate(page = 1, pageSize = 2))
        assertEquals(listOf("five"), children.paginate(page = 2, pageSize = 2))
        assertEquals(emptyList<String>(), children.paginate(page = 3, pageSize = 2))
        assertEquals(children, children.paginate(page = 0, pageSize = Int.MAX_VALUE))
    }
}
