package com.metrolist.shared.model

import com.metrolist.shared.settings.LibraryContentSort
import com.metrolist.shared.settings.LibrarySorting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistSongCountTest {
    @Test
    fun playlistChoiceKeepsLocalizedExactCountsAndUnknownValues() {
        assertEquals(1234, playlist("grouped", "١٬٢٣٤ أغنية").exactSongCount)
        assertEquals(42, playlist("remote", "42 songs").exactSongCount)
        assertEquals(0, playlist("empty", "0").exactSongCount)
        assertNull(playlist("abbreviated", "1.2K songs").exactSongCount)
        assertNull(playlist("missing", null).exactSongCount)
    }

    @Test
    fun pickerSortsLocalAndRemoteCountsNumericallyAndLeavesUnknownAtTheEnd() {
        val remote = playlist("remote", "1,234 songs")
        val local = playlist("local", "42")
        val unknown = playlist("unknown", null)
        val empty = playlist("empty", "0 songs")
        val playlists = listOf(unknown, remote, local, empty)

        fun sorted(direction: SortDirection): List<PlaylistItem> =
            playlists.sortedForPlaylistPicker(
                sort = PlaylistPickerSort.SONG_COUNT,
                direction = direction,
                name = PlaylistItem::title,
                addedAt = PlaylistItem::libraryAddedAt,
                lastUpdated = PlaylistItem::libraryUpdatedAt,
                songCount = PlaylistItem::exactSongCount,
                id = PlaylistItem::id,
                librarySorting = LibrarySorting(LibraryContentSort.NAME, SortDirection.ASCENDING),
                manualOrder = "",
                playtime = PlaylistItem::playtime,
            )

        assertEquals(listOf(empty, local, remote, unknown), sorted(SortDirection.ASCENDING))
        assertEquals(listOf(remote, local, empty, unknown), sorted(SortDirection.DESCENDING))
    }

    private fun playlist(
        id: String,
        count: String?,
    ) = PlaylistItem(id, id, null, songCount = count)
}
