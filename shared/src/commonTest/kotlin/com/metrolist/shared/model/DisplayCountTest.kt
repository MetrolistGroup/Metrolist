package com.metrolist.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayCountTest {
    @Test
    fun emptyPlaylistsHaveKnownCountsWhileMissingCountsRemainUnknown() {
        assertEquals(0, parseDisplayCount("0"))
        assertEquals(0, parseDisplayCount("0 songs"))
        assertEquals(1, parseDisplayCount("1"))
        assertEquals(1, parseDisplayCount("1 song"))
        assertNull(parseDisplayCount(null))
        assertNull(parseDisplayCount(""))
        assertNull(parseDisplayCount(" "))
    }

    @Test
    fun exactCountsAcceptGroupingAndLocalizedDigits() {
        listOf("1,234 songs", "1.234 Titel", "1 234 titres", "1\u00a0234 tracks", "1\u202f234 tracks", "١٬٢٣٤ أغنية").forEach {
            assertEquals(1234, parseDisplayCount(it), it)
        }
        assertEquals(123456, parseDisplayCount("1,23,456 songs"))
        assertEquals(42, parseDisplayCount("42 songs"))
        listOf("1.2K songs", "1 K songs", "1 тыс. песен", "1,2 M songs", "Unknown", "999999999999 songs").forEach {
            assertNull(parseDisplayCount(it), it)
        }
    }

    @Test
    fun systemPlaylistsUseIdsAndNeverUserTitles() {
        listOf("Liked Music", "New Episodes", "Episodes for Later").forEach {
            assertFalse(PlaylistItem(id = "PLuser", title = it, thumbnail = null).isSystemPlaylist())
        }
        listOf("LM", "VLLM", "LM:account", "RDPN", "VLRDPN", "SE", "VLSE").forEach {
            assertTrue(PlaylistItem(id = it, title = "Muzyka", thumbnail = null).isSystemPlaylist())
        }
        assertFalse(PlaylistItem(id = "SEuser", title = "Music", thumbnail = null).isSystemPlaylist())
    }
}
