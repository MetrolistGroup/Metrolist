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

    @Test
    fun `database pages are capped before loading relations`() {
        assertEquals(
            AndroidAutoPageRequest(offset = 0, limit = MAX_ANDROID_AUTO_PAGE_SIZE),
            androidAutoPageRequest(page = 0, pageSize = Int.MAX_VALUE),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = MAX_ANDROID_AUTO_PAGE_SIZE, limit = MAX_ANDROID_AUTO_PAGE_SIZE),
            androidAutoPageRequest(page = 1, pageSize = 1_000),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = 1_000, limit = MAX_ANDROID_AUTO_PAGE_SIZE),
            androidAutoPageRequest(page = 2, pageSize = 500),
        )
    }

    @Test
    fun `playlist pages account for the leading shuffle action`() {
        assertEquals(
            AndroidAutoPageRequest(offset = 0, limit = 99),
            androidAutoPageRequest(page = 0, pageSize = 100).afterLeadingItems(1),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = 99, limit = 100),
            androidAutoPageRequest(page = 1, pageSize = 100).afterLeadingItems(1),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = 499, limit = MAX_ANDROID_AUTO_PAGE_SIZE),
            androidAutoPageRequest(page = 1, pageSize = 1_000).afterLeadingItems(1),
        )
    }

    @Test
    fun `playlist containers account for both built in playlists`() {
        assertEquals(
            AndroidAutoPageRequest(offset = 0, limit = 0),
            androidAutoPageRequest(page = 0, pageSize = 1).afterLeadingItems(2),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = 0, limit = 0),
            androidAutoPageRequest(page = 1, pageSize = 1).afterLeadingItems(2),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = 0, limit = 1),
            androidAutoPageRequest(page = 2, pageSize = 1).afterLeadingItems(2),
        )
    }
}
