package com.metrolist.shared.playlistimport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CsvPlaylistTrackLimitTest {
    private val limit = PlaylistImportManager.MAX_PLAYLIST_TRACKS
    private val mapping = CsvColumnMapping(hasHeader = true, title = 0, artist = -1)
    private val repeatedTrack = listOf("Repeat")

    @Test
    fun failsInsteadOfReturningPartialCsvWhenOneTrackExceedsLimit() {
        val csv = CsvPlaylist(listOf(listOf("Title")) + List(limit + 1) { repeatedTrack })

        val result = runCatching { csv.tracks(mapping) }
        assertTrue(result.isFailure, "Import returned ${result.getOrNull()?.size} of ${limit + 1} tracks")
        val error = assertIs<ImportException>(result.exceptionOrNull())
        assertEquals(ImportErrorKey.TooManyTracks, error.errorKey)
    }

    @Test
    fun acceptsExactlyTheLimitWithBlankRowsAndPreservesRepeatedTracks() {
        val csv =
            CsvPlaylist(
                listOf(listOf("Title"), listOf("First"), listOf(" ")) +
                    List(limit - 2) { repeatedTrack } + listOf(listOf("Last")),
            )

        val tracks = csv.tracks(mapping)
        assertEquals(limit, tracks.size)
        assertEquals("First", tracks.first().title)
        assertEquals("Last", tracks.last().title)
        assertEquals(limit - 2, tracks.count { it.title == "Repeat" })
    }
}
