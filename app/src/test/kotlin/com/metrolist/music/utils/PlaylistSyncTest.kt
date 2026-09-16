package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistSyncTest {
    @Test
    fun `local-only songs are preserved`() {
        assertEquals(
            listOf(2),
            localSongIndexesAbsentFromRemote(listOf("a", "b", "c"), listOf("a", "b")),
        )
    }

    @Test
    fun `duplicate occurrences are compared separately`() {
        assertEquals(
            listOf(1),
            localSongIndexesAbsentFromRemote(listOf("a", "a"), listOf("a")),
        )
    }

    @Test
    fun `remote ordering does not create local-only songs`() {
        assertEquals(
            emptyList<Int>(),
            localSongIndexesAbsentFromRemote(listOf("a", "b"), listOf("b", "a")),
        )
    }
}
