package com.metrolist.music.utils

import com.metrolist.music.db.entities.PlaylistEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPlaylistsTest {
    private fun playlist(
        name: String,
        browseId: String? = null,
        isAutoSync: Boolean = false,
    ) = PlaylistEntity(name = name, browseId = browseId, isAutoSync = isAutoSync)

    @Test
    fun `a playlist asked to sync that has no browseId is pending`() {
        assertTrue(playlist("offline", isAutoSync = true).isPendingRemoteCreation())
    }

    @Test
    fun `a playlist already created on YouTube is not pending`() {
        assertFalse(playlist("synced", browseId = "PL123", isAutoSync = true).isPendingRemoteCreation())
    }

    @Test
    fun `a local-only playlist is never pending`() {
        assertFalse(playlist("local").isPendingRemoteCreation())
    }

    @Test
    fun `a playlist that arrived from YouTube is not pending`() {
        assertFalse(playlist("downloaded", browseId = "PL456").isPendingRemoteCreation())
    }

    @Test
    fun `only the pending ones are collected, in order`() {
        val playlists = listOf(
            playlist("local"),
            playlist("first pending", isAutoSync = true),
            playlist("synced", browseId = "PL123", isAutoSync = true),
            playlist("second pending", isAutoSync = true),
        )

        assertEquals(
            listOf("first pending", "second pending"),
            playlists.pendingRemoteCreations().map { it.name },
        )
    }
}
