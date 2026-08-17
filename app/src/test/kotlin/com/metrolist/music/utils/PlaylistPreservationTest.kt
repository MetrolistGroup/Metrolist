package com.metrolist.music.utils

import org.junit.Assert.assertEquals
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
}
