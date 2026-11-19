package com.metrolist.shared.ui.screens

import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SortDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryPlaylistCountSortTest {
    @Test
    fun sortsDisplayedRemoteCountsAlongsideLocalCountsAndKeepsUnknownLast() {
        val local = playlist("local", "20")
        val smallRemote = playlist("small-remote", "3 titres")
        val largeRemote = playlist("large-remote", "1,234 songs")
        val unknown = playlist("unknown", null)
        val abbreviated = playlist("abbreviated", "1.2K songs")
        val playlists = listOf(unknown, largeRemote, local, smallRemote, abbreviated)

        assertEquals(
            listOf(smallRemote, local, largeRemote, unknown, abbreviated),
            sortLibraryItems(playlists, LibrarySortType.SONG_COUNT, "", SortDirection.ASCENDING),
        )
        assertEquals(
            listOf(largeRemote, local, smallRemote, unknown, abbreviated),
            sortLibraryItems(playlists, LibrarySortType.SONG_COUNT, "", SortDirection.DESCENDING),
        )
    }

    private fun playlist(
        id: String,
        count: String?,
    ) = PlaylistItem(id = id, title = id, thumbnail = null, songCount = count)
}
