package com.metrolist.shared.state

import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals

class SongLikeOrderTest {
    @Test
    fun remoteRefreshKeepsManualOrderAndPrependsNewLikes() {
        val local = listOf(song("second", "Old metadata"), song("first"), song("removed"))
        val remote = listOf(song("new"), song("first"), song("second", "Fresh metadata"))

        val reconciled = reconcileLikedSongs(local, remote)

        assertEquals(listOf("new", "second", "first"), reconciled.map { it.id })
        assertEquals("Fresh metadata", reconciled[1].title)
    }

    @Test
    fun refreshUsesRemoteOrderWithoutUndoingConcurrentLocalChanges() {
        val snapshot = listOf(song("first"), song("second"))
        val current = listOf(song("third"), song("second"))
        val remote = listOf(song("remote-new"), song("first"), song("second"))

        val reconciled = reconcileLikedSongsAfterRefresh(snapshot, current, remote)

        assertEquals(listOf("third", "remote-new", "second"), reconciled.map(SongItem::id))
    }

    @Test
    fun refreshKeepsExplicitlyCustomizedOrder() {
        val local = listOf(song("second"), song("first"))
        val remote = listOf(song("new"), song("first"), song("second"))

        val reconciled = reconcileLikedSongsAfterRefresh(local, local, remote, preserveLocalOrder = true)

        assertEquals(listOf("new", "second", "first"), reconciled.map(SongItem::id))
    }

    @Test
    fun manualMoveChangesOnlyClientOrder() {
        val songs = listOf(song("first"), song("second"), song("third"))

        assertEquals(listOf("third", "first", "second"), moveLikedSong(songs, "third", "first").map { it.id })
        assertEquals(songs, moveLikedSong(songs, "missing", "first"))
    }

    private fun song(
        id: String,
        title: String = id,
    ) = SongItem(id, title, null, emptyList(), null)
}
