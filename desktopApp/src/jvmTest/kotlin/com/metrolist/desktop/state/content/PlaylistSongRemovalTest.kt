package com.metrolist.desktop.state.content

import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistSongRemovalTest {
    @Test
    fun bulkRemovalConsumesEachRequestOnceAndPreservesOtherOccurrencesAndNonSongs() {
        val first = SongItem("same", "First", null, emptyList(), null, playlistSetVideoId = "first")
        val second = first.copy(title = "Second", playlistSetVideoId = "second")
        val local = first.copy(title = "Local", playlistLocalMappingId = 7)
        val otherLocal = local.copy(title = "Other local", playlistLocalMappingId = 8)
        val playlist = PlaylistItem("playlist", "Playlist", null)
        val sections: Map<String, List<YTItem>> =
            mapOf(
                "first" to listOf(first, second, local, playlist),
                "second" to listOf(first, second, otherLocal),
            )

        assertEquals(
            mapOf("first" to listOf(first, playlist), "second" to listOf(first, second, otherLocal)),
            sections.removePlaylistSongs(listOf(second, local)),
        )
        assertEquals(
            mapOf("first" to listOf(second, local, playlist), "second" to listOf(second, otherLocal)),
            sections.removePlaylistSongs(listOf(first.copy(playlistSetVideoId = null), first)),
        )
    }
}
