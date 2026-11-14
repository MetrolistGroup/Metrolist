package com.metrolist.shared.model

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistPlaybackTest {
    @Test
    fun searchedSongStartsInTheWholeSortedPlaylist() =
        runTest {
            val songs = listOf(song("z", "Zulu"), song("a", "Alpha"), song("m", "Match"))
            for (direction in SortDirection.entries) {
                val options = PlaylistPresentationOptions(sort = PlaylistSongSort.TITLE, direction = direction, query = "Match")
                val displayed = preparePlaylistPresentation(songs, options).songs
                val queue = preparePlaylistPresentation(songs, options.copy(query = "")).songs
                assertEquals(listOf(songs[2]), displayed)
                assertEquals(
                    if (direction ==
                        SortDirection.ASCENDING
                    ) {
                        listOf(songs[1], songs[2], songs[0])
                    } else {
                        listOf(songs[0], songs[2], songs[1])
                    },
                    queue,
                )
                assertEquals(1, queue.indexOfPlaylistOccurrence(displayed.single()))
            }
        }

    @Test
    fun tappedDuplicateKeepsItsOccurrenceAfterReloadAndSorting() =
        runTest {
            val first = song("repeat", "Match").copy(playlistSetVideoId = "first")
            val second = first.copy(playlistSetVideoId = "second")
            val songs = listOf(first, song("other", "Alpha"), second)
            val queue =
                preparePlaylistPresentation(
                    songs.map { it.copy() },
                    PlaylistPresentationOptions(sort = PlaylistSongSort.TITLE),
                ).songs
            assertEquals(2, queue.indexOfPlaylistOccurrence(second))
            val local = second.copy(playlistSetVideoId = null, playlistLocalMappingId = 2)
            assertEquals(1, listOf(local.copy(playlistLocalMappingId = 1), local.copy()).indexOfPlaylistOccurrence(local))
        }

    @Test
    fun equalSongsUseTheTappedReferenceAndMissingSongsDoNotStartAnotherTrack() {
        val first = song("repeat", "Match")
        val second = first.copy()
        assertEquals(2, listOf(first, song("other", "Alpha"), second).indexOfPlaylistOccurrence(second))
        assertEquals(-1, listOf(first).indexOfPlaylistOccurrence(song("missing", "Missing")))
    }

    private fun song(
        id: String,
        title: String,
    ) = SongItem(id, title, null, emptyList(), null)
}
