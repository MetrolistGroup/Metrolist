/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import com.metrolist.music.db.entities.PlaylistSongMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Removing a song from a playlist is destructive and irreversible from the UI, so the target has to
 * be identified exactly. These tests pin the rules: an occurrence is identified by setVideoId, and
 * no target is produced when the playlist is not editable or the occurrence is unknown.
 */
class PlaylistRemovalTargetTest {

    private fun listQueue(
        browseId: String? = "VLPL_browse",
        localId: String? = "local-1",
        editable: Boolean = true,
    ) = ListQueue(
        title = "playlist",
        items = emptyList(),
        playlistBrowseId = browseId,
        playlistId = localId,
        playlistIsEditable = editable,
    )

    @Test
    fun `list queue resolves target from browse id and set video id`() {
        val target = resolvePlaylistRemovalTarget(listQueue(), "setVideoId-A")

        assertEquals("VLPL_browse", target?.playlistId)
        assertEquals("setVideoId-A", target?.setVideoId)
        assertEquals("local-1", target?.localPlaylistId)
        assertEquals("listQueue", target?.source)
    }

    @Test
    fun `youtube playlist queue resolves target without a local playlist id`() {
        val target = resolvePlaylistRemovalTarget(
            YouTubePlaylistQueue(playlistId = "PL123", isEditable = true),
            "setVideoId-A",
        )

        assertEquals("PL123", target?.playlistId)
        assertEquals("setVideoId-A", target?.setVideoId)
        assertNull(target?.localPlaylistId)
        assertEquals("queue", target?.source)
    }

    // --- editability ---

    @Test
    fun `non editable youtube playlist yields no target`() {
        assertNull(
            resolvePlaylistRemovalTarget(
                YouTubePlaylistQueue(playlistId = "PL123", isEditable = false),
                "setVideoId-A",
            ),
        )
    }

    @Test
    fun `non editable list queue yields no target`() {
        assertNull(resolvePlaylistRemovalTarget(listQueue(editable = false), "setVideoId-A"))
    }

    @Test
    fun `list queue without browse id yields no target`() {
        assertNull(resolvePlaylistRemovalTarget(listQueue(browseId = null), "setVideoId-A"))
        assertNull(resolvePlaylistRemovalTarget(listQueue(browseId = "   "), "setVideoId-A"))
    }

    // --- setVideoId identity ---

    @Test
    fun `missing or blank set video id yields no target`() {
        assertNull(resolvePlaylistRemovalTarget(listQueue(), null))
        assertNull(resolvePlaylistRemovalTarget(listQueue(), ""))
        assertNull(resolvePlaylistRemovalTarget(listQueue(), "   "))
        assertNull(
            resolvePlaylistRemovalTarget(
                YouTubePlaylistQueue(playlistId = "PL123", isEditable = true),
                null,
            ),
        )
    }

    @Test
    fun `queue types without playlist context yield no target`() {
        assertNull(resolvePlaylistRemovalTarget(EmptyQueue, "setVideoId-A"))
        assertNull(resolvePlaylistRemovalTarget(null, "setVideoId-A"))
    }

    // --- duplicate occurrences ---

    @Test
    fun `duplicate occurrences of one song resolve to different targets`() {
        val queue = listQueue()

        val first = resolvePlaylistRemovalTarget(queue, "setVideoId-A")
        val second = resolvePlaylistRemovalTarget(queue, "setVideoId-B")

        assertEquals("setVideoId-A", first?.setVideoId)
        assertEquals("setVideoId-B", second?.setVideoId)
    }

    @Test
    fun `local row lookup picks the occurrence matching the set video id`() {
        val maps = listOf(
            PlaylistSongMap(id = 1, playlistId = "local-1", songId = "song", position = 0, setVideoId = "setVideoId-A"),
            PlaylistSongMap(id = 2, playlistId = "local-1", songId = "song", position = 4, setVideoId = "setVideoId-B"),
        )

        assertEquals(2, selectLocalPlaylistRowToRemove(maps, "local-1", "setVideoId-B")?.id)
        assertEquals(4, selectLocalPlaylistRowToRemove(maps, "local-1", "setVideoId-B")?.position)
        assertEquals(1, selectLocalPlaylistRowToRemove(maps, "local-1", "setVideoId-A")?.id)
    }

    @Test
    fun `local row lookup ignores the same song in other playlists`() {
        val maps = listOf(
            PlaylistSongMap(id = 1, playlistId = "local-2", songId = "song", position = 0, setVideoId = "setVideoId-A"),
            PlaylistSongMap(id = 2, playlistId = "local-1", songId = "song", position = 1, setVideoId = "setVideoId-A"),
        )

        assertEquals(2, selectLocalPlaylistRowToRemove(maps, "local-1", "setVideoId-A")?.id)
    }

    @Test
    fun `local row lookup returns null when no occurrence matches`() {
        val maps = listOf(
            PlaylistSongMap(id = 1, playlistId = "local-1", songId = "song", position = 0, setVideoId = "setVideoId-A"),
            PlaylistSongMap(id = 2, playlistId = "local-1", songId = "song", position = 1, setVideoId = null),
        )

        assertNull(selectLocalPlaylistRowToRemove(maps, "local-1", "setVideoId-Z"))
        assertNull(selectLocalPlaylistRowToRemove(emptyList(), "local-1", "setVideoId-A"))
    }
}
