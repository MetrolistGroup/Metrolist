package com.metrolist.shared.ui.screens

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.settings.HiddenLibraryItem
import com.metrolist.shared.ui.components.CollectionPreviews
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class LibraryPlaybackTest {
    @Test
    fun albumTapProvidesTheCardPreviewBeforeNavigation() {
        val album = AlbumItem("library-preview", "Album", "https://example.com/card.jpg", emptyList())
        dispatchLibraryItemClick(
            listOf(album),
            0,
            onPlaySongs = { _, _ -> fail("An album tap must navigate") },
            onOpenItem = {
                assertEquals(album, CollectionPreviews.album(album.id))
                assertEquals(album.thumbnail, CollectionPreviews.albumPage(album.id)?.album?.thumbnail)
            },
        )
    }

    @Test
    fun tracksPlaybackKeepsDisplayedOrderAndSelectedPositionAfterFiltering() {
        val alpha = SongItem("a", "Alpha", null, emptyList(), null)
        val beta = alpha.copy(id = "b", title = "Beta")
        val zulu = alpha.copy(id = "z", title = "Zulu")
        val hidden = alpha.copy(id = "hidden", title = "Hidden")
        for (direction in SortDirection.entries) {
            val displayed =
                prepareLibraryItems(
                    listOf(zulu, hidden, beta, alpha),
                    LibraryFilter.TRACKS,
                    setOf(HiddenLibraryItem.from(hidden).key),
                    LibrarySortType.NAME,
                    "",
                    direction,
                    "",
                    emptyList(),
                )
            val expected = if (direction == SortDirection.ASCENDING) listOf(alpha, beta, zulu) else listOf(zulu, beta, alpha)
            expected.forEachIndexed { expectedIndex, selected ->
                var played = false
                dispatchLibraryItemClick(
                    displayed,
                    displayed.indexOf(selected),
                    onPlaySongs = { songs, index ->
                        played = true
                        assertEquals(expected, songs)
                        assertEquals(expectedIndex, index)
                        assertEquals(selected, songs[index])
                    },
                    onOpenItem = { fail("A track must start the displayed queue") },
                )
                assertEquals(true, played)
            }
        }
    }

    @Test
    fun mixedSearchQueuesOnlyMatchingSongsAndStillOpensCollections() {
        val playlist = PlaylistItem("same", "Match Beta", null)
        val first = SongItem("a", "Match Alpha", null, emptyList(), null)
        val last = first.copy(id = "same", title = "Match Zulu")
        val displayed =
            prepareLibraryItems(
                listOf(playlist, playlist.copy(id = "other", title = "Other")),
                null,
                emptySet(),
                LibrarySortType.NAME,
                "",
                SortDirection.ASCENDING,
                "Match",
                listOf(last, first, last),
            )
        var played = false
        dispatchLibraryItemClick(
            displayed,
            displayed.indexOf(last),
            onPlaySongs = { songs, index ->
                played = true
                assertEquals(listOf(first, last), songs)
                assertEquals(1, index)
            },
            onOpenItem = { fail("A song search result must start the displayed queue") },
        )
        assertEquals(true, played)
        var opened: PlaylistItem? = null
        dispatchLibraryItemClick(
            displayed,
            displayed.indexOf(playlist),
            onPlaySongs = { _, _ -> fail("A collection must keep its navigation action") },
            onOpenItem = { opened = it as PlaylistItem },
        )
        assertEquals(playlist, opened)
    }
}
