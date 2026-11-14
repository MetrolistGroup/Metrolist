package com.metrolist.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistDurationTest {
    private val song = SongItem("song", "Song", null, emptyList(), null, duration = 120)

    @Test
    fun completeTotalsIncludeEveryOccurrenceAndHiddenTrack() {
        val hidden = song.copy(id = "hidden", duration = 45)
        assertEquals(285L, playlistTotalDuration(listOf(song, hidden, song), hasMoreSongs = false, loadedSongCount = 3))
    }

    @Test
    fun partialFilteredAndUnknownDurationsCannotBecomeTotals() {
        assertNull(playlistTotalDuration(listOf(song), hasMoreSongs = true))
        assertNull(playlistTotalDuration(listOf(song), hasMoreSongs = false, loadedSongCount = 2))
        assertNull(playlistTotalDuration(listOf(song, song.copy(duration = null)), hasMoreSongs = false))
        assertNull(playlistTotalDuration(listOf(song.copy(duration = 0)), hasMoreSongs = false))
        assertNull(playlistTotalDuration(listOf(song.copy(duration = -1)), hasMoreSongs = false))
        assertNull(playlistTotalDuration(emptyList(), hasMoreSongs = false))
    }
}
