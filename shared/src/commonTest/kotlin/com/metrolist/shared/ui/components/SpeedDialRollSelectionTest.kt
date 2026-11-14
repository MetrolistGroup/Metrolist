package com.metrolist.shared.ui.components

import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeedDialRollSelectionTest {
    @Test
    fun aResultOnTheLastPageKeepsItsIdentityWhenItemsMoveDuringTheRoll() {
        val items = List(26) { SongItem("song-$it", "", null, emptyList(), null) }
        val result = items.last()
        val selection = SpeedDialRollSelection(result)

        assertEquals(result, selection.resolve(items) { true })
        val refreshed = result.copy(title = "Renamed")
        assertEquals(refreshed, selection.resolve(listOf(refreshed) + items.dropLast(1)) { true })
    }

    @Test
    fun aCanonicalPlaylistAliasStillResolvesAfterARefresh() {
        val playlist = PlaylistItem("VLPLchosen", "", null)
        val refreshed = playlist.copy(id = "PLchosen")

        assertEquals(refreshed, SpeedDialRollSelection(playlist).resolve(listOf(refreshed)) { true })
    }

    @Test
    fun aRemovedOrDisabledResultCannotRunItsAction() {
        val item = SongItem("chosen", "", null, emptyList(), null)
        val selection = SpeedDialRollSelection(item)

        assertNull(selection.resolve(emptyList()) { true })
        assertNull(selection.resolve(listOf(item)) { false })
    }
}
