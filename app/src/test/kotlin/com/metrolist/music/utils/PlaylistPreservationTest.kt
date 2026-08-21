package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistPreservationTest {
    @Test
    fun `a playlist the remote lists in full preserves nothing`() {
        assertEquals(
            emptyList<String>(),
            songIdsAbsentFromRemote(listOf("a", "b"), listOf("a", "b")),
        )
    }

    @Test
    fun `a song the remote does not list is preserved`() {
        assertEquals(
            listOf("c"),
            songIdsAbsentFromRemote(listOf("a", "b", "c"), listOf("a", "b")),
        )
    }

    @Test
    fun `a song held twice locally and listed once remotely keeps its second copy`() {
        assertEquals(
            listOf("a"),
            songIdsAbsentFromRemote(listOf("a", "a"), listOf("a")),
        )
    }

    @Test
    fun `a song held twice on both sides preserves nothing`() {
        assertEquals(
            emptyList<String>(),
            songIdsAbsentFromRemote(listOf("a", "a"), listOf("a", "a")),
        )
    }

    @Test
    fun `a song the remote lists more often than the playlist holds preserves nothing`() {
        assertEquals(
            emptyList<String>(),
            songIdsAbsentFromRemote(listOf("a"), listOf("a", "a")),
        )
    }

    @Test
    fun `an empty playlist preserves nothing`() {
        assertEquals(
            emptyList<String>(),
            songIdsAbsentFromRemote(emptyList(), listOf("a")),
        )
    }

    @Test
    fun `everything is preserved when the remote lists nothing`() {
        assertEquals(
            listOf("a", "b"),
            songIdsAbsentFromRemote(listOf("a", "b"), emptyList()),
        )
    }

    @Test
    fun `preserved songs keep the order the playlist holds them in`() {
        assertEquals(
            listOf("c", "d"),
            songIdsAbsentFromRemote(listOf("c", "a", "b", "d"), listOf("b", "a")),
        )
    }

    @Test
    fun `matching ignores the order the remote lists songs in`() {
        assertEquals(
            listOf("c"),
            songIdsAbsentFromRemote(listOf("a", "b", "c"), listOf("b", "a")),
        )
    }

    @Test
    fun `a read that arrives in full is usable`() {
        assertTrue(remoteReadAccountsForPlaylist(readSongCount = 12, claimedSongCount = 12))
    }

    @Test
    fun `a read that falls short of the claim is not usable`() {
        assertFalse(remoteReadAccountsForPlaylist(readSongCount = 3, claimedSongCount = 300))
    }

    @Test
    fun `a read of nothing from a playlist that claims songs is not usable`() {
        assertFalse(remoteReadAccountsForPlaylist(readSongCount = 0, claimedSongCount = 12))
    }

    @Test
    fun `a playlist that genuinely holds nothing is usable`() {
        assertTrue(remoteReadAccountsForPlaylist(readSongCount = 0, claimedSongCount = 0))
    }

    @Test
    fun `a read of nothing from a page that claims nothing countable is not usable`() {
        assertFalse(remoteReadAccountsForPlaylist(readSongCount = 0, claimedSongCount = null))
    }

    @Test
    fun `songs that arrive without a claim to check them against are usable`() {
        assertTrue(remoteReadAccountsForPlaylist(readSongCount = 12, claimedSongCount = null))
    }

    @Test
    fun `a read longer than the claim is usable`() {
        // The count under the title can lag behind an edit that already landed.
        assertTrue(remoteReadAccountsForPlaylist(readSongCount = 13, claimedSongCount = 12))
    }

    @Test
    fun `the claimed count is read out of the localised text`() {
        assertEquals(12, remoteSongCountOf("12 songs"))
        assertEquals(12, remoteSongCountOf("12 canciones"))
        assertEquals(0, remoteSongCountOf("0 songs"))
    }

    @Test
    fun `a page that says nothing countable claims nothing`() {
        assertEquals(null, remoteSongCountOf(null))
        assertEquals(null, remoteSongCountOf("No songs"))
    }
}
