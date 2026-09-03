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

    @Test
    fun `a playlist that appeared while the answer was lost is claimed`() {
        assertEquals(
            "PL_new",
            playlistCreatedByLostRequest(
                name = "Rock",
                idsBefore = setOf("PL_old"),
                remoteAfter = listOf("PL_old" to "Jazz", "PL_new" to "Rock"),
            ),
        )
    }

    @Test
    fun `a request that never reached YouTube claims nothing`() {
        assertEquals(
            null,
            playlistCreatedByLostRequest(
                name = "Rock",
                idsBefore = setOf("PL_old"),
                remoteAfter = listOf("PL_old" to "Jazz"),
            ),
        )
    }

    @Test
    fun `a playlist that already carried the name is not claimed`() {
        // The user is entitled to a second playlist called Rock, so the one that was already
        // there is not evidence that this request landed.
        assertEquals(
            null,
            playlistCreatedByLostRequest(
                name = "Rock",
                idsBefore = setOf("PL_rock"),
                remoteAfter = listOf("PL_rock" to "Rock"),
            ),
        )
    }

    @Test
    fun `a new playlist under a different name is not claimed`() {
        assertEquals(
            null,
            playlistCreatedByLostRequest(
                name = "Rock",
                idsBefore = emptySet(),
                remoteAfter = listOf("PL_new" to "Jazz"),
            ),
        )
    }

    @Test
    fun `two new playlists under the name claim neither`() {
        // Claiming the wrong one would tie this device's playlist to the other.
        assertEquals(
            null,
            playlistCreatedByLostRequest(
                name = "Rock",
                idsBefore = emptySet(),
                remoteAfter = listOf("PL_a" to "Rock", "PL_b" to "Rock"),
            ),
        )
    }

    @Test
    fun `the same new playlist listed twice is still claimed`() {
        assertEquals(
            "PL_new",
            playlistCreatedByLostRequest(
                name = "Rock",
                idsBefore = emptySet(),
                remoteAfter = listOf("PL_new" to "Rock", "PL_new" to "Rock"),
            ),
        )
    }

    @Test
    fun `a name is matched exactly`() {
        assertEquals(
            null,
            playlistCreatedByLostRequest(
                name = "Rock",
                idsBefore = emptySet(),
                remoteAfter = listOf("PL_new" to "rock "),
            ),
        )
    }
}
