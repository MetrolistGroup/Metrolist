package com.metrolist.music.utils

import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.PlaylistPage
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

    private fun emptyPage(songCountText: String?) =
        PlaylistPage(
            playlist =
                PlaylistItem(
                    id = "playlist",
                    title = "Playlist",
                    author = null,
                    songCountText = songCountText,
                    thumbnail = null,
                    playEndpoint = null,
                    shuffleEndpoint = null,
                    radioEndpoint = null,
                ),
            songs = emptyList(),
            songsContinuation = null,
            continuation = null,
        )

    @Test
    fun `empty fetch with advertised songs is not genuine`() {
        assertEquals(false, isGenuineEmptyPlaylist(emptyPage("37 songs")))
    }

    @Test
    fun `empty fetch with zero advertised songs is genuine`() {
        assertEquals(true, isGenuineEmptyPlaylist(emptyPage("0 songs")))
    }

    @Test
    fun `empty fetch without advertised count is not genuine`() {
        assertEquals(false, isGenuineEmptyPlaylist(emptyPage(null)))
    }

    @Test
    fun `non-empty fetch is never genuine empty`() {
        val page =
            emptyPage("37 songs").copy(
                songs = listOf(SongItem(id = "a", title = "A", artists = emptyList(), thumbnail = "")),
            )
        assertEquals(false, isGenuineEmptyPlaylist(page))
    }
}
