package com.metrolist.shared.playlistimport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UniversalPlaylistTrackLimitTest {
    private val limit = PlaylistImportManager.MAX_PLAYLIST_TRACKS
    private val videoId = "abcdefghijk"

    @Test
    fun jsonRejectsOverflowButAcceptsExactlyTheLimitWithSkippedRows() {
        val tracks = """{"videoId":"$videoId"},""".repeat(limit)
        val exact = """{"tracks":[null,$tracks{}]}"""
        val oversized = """{"tracks":[null,$tracks{"title":"Overflow"}]}"""

        assertEquals(limit, UniversalPlaylistParser.parse(exact).size)
        val error =
            assertFailsWith<ImportException> {
                UniversalPlaylistParser.parse(oversized)
                Unit
            }
        assertEquals(ImportErrorKey.TooManyTracks, error.errorKey)
    }

    @Test
    fun m3uFileRejectsOverflowButAcceptsExactlyTheLimitWithComments() {
        val exact = "#EXTM3U\n" + "$videoId\n".repeat(limit) + "# End of playlist\n"
        val oversized = exact + "$videoId\n"

        assertEquals(limit, UniversalPlaylistParser.prepareFile(exact, "playlist.m3u8", "Playlist", "File").tracks.size)
        val error =
            assertFailsWith<ImportException> {
                UniversalPlaylistParser.prepareFile(oversized, "playlist.m3u8", "Playlist", "File")
                Unit
            }
        assertEquals(ImportErrorKey.TooManyTracks, error.errorKey)
    }

    @Test
    fun plainTextCountsRecognizedTracksNotNumberingOnlyLines() {
        val exact = "1.\n" + "Artist - Song\n".repeat(limit) + "# End of list\n"

        assertEquals(limit, UniversalPlaylistParser.parse(exact).size)
    }

    @Test
    fun plainTextRejectsOverflow() {
        val oversized = "Artist - Song\n".repeat(limit + 1)

        val error =
            assertFailsWith<ImportException> {
                UniversalPlaylistParser.parse(oversized)
                Unit
            }
        assertEquals(ImportErrorKey.TooManyTracks, error.errorKey)
    }
}
