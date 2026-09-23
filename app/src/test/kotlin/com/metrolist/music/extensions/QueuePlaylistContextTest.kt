/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.extensions

import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.PersistQueue
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.YouTubePlaylistQueue
import com.metrolist.music.playback.queues.resolvePlaylistRemovalTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * A queue restored from disk must keep its playlist context, otherwise "remove from playlist"
 * silently disappears (or worse, targets the wrong playlist) after the app is restarted.
 */
@RunWith(RobolectricTestRunner::class)
class QueuePlaylistContextTest {

    private fun metadata(id: String, setVideoId: String?) = MediaMetadata(
        id = id,
        title = "title-$id",
        artists = listOf(MediaMetadata.Artist(id = "artist", name = "Artist")),
        duration = 100,
        setVideoId = setVideoId,
    )

    /** The same song added twice to one playlist: two rows, two distinct setVideoIds. */
    private val songs = listOf(
        metadata("song-1", "setVideoId-A"),
        metadata("song-1", "setVideoId-B"),
    )

    private fun listQueue() = ListQueue(
        title = "My playlist",
        items = listOf(
            metadata("song-1", "setVideoId-A").toMediaItem(),
            metadata("song-1", "setVideoId-B").toMediaItem(),
        ),
        playlistBrowseId = "VLPL_browse",
        playlistId = "local-1",
        playlistIsEditable = true,
    )

    @Test
    fun `list queue keeps playlist context through persist and restore`() {
        val persisted = listQueue().toPersistQueue(title = "My playlist", items = songs, mediaItemIndex = 1, position = 0L)

        assertEquals("VLPL_browse", persisted.playlistBrowseId)
        assertEquals("local-1", persisted.playlistId)
        assertEquals(true, persisted.playlistIsEditable)

        val restored = persisted.toQueue() as ListQueue

        assertEquals("VLPL_browse", restored.playlistBrowseId)
        assertEquals("local-1", restored.playlistId)
        assertEquals(true, restored.playlistIsEditable)
    }

    @Test
    fun `restored queue still resolves per occurrence removal targets`() {
        val restored = listQueue().toPersistQueue(title = "My playlist", items = songs, mediaItemIndex = 1, position = 0L).toQueue()

        val restoredItems = (restored as ListQueue).items
        val occurrenceIds = restoredItems.map { it.metadata?.setVideoId }
        assertEquals(listOf("setVideoId-A", "setVideoId-B"), occurrenceIds)

        val target = resolvePlaylistRemovalTarget(restored, occurrenceIds[1])
        assertEquals("VLPL_browse", target?.playlistId)
        assertEquals("setVideoId-B", target?.setVideoId)
        assertEquals("local-1", target?.localPlaylistId)
    }

    @Test
    fun `restored non editable queue offers no removal target`() {
        val restored = ListQueue(
            title = "Someone else's playlist",
            items = listOf(metadata("song-1", "setVideoId-A").toMediaItem()),
            playlistBrowseId = "VLPL_browse",
            playlistId = null,
            playlistIsEditable = false,
        ).toPersistQueue(title = "title", items = listOf(metadata("song-1", "setVideoId-A")), mediaItemIndex = 0, position = 0L).toQueue()

        assertNull(resolvePlaylistRemovalTarget(restored, "setVideoId-A"))
    }

    @Test
    fun `youtube playlist queue persists its editability and browse id`() {
        val persisted = YouTubePlaylistQueue(playlistId = "PL123", isEditable = true)
            .toPersistQueue(title = "title", items = listOf(metadata("song-1", "setVideoId-A")), mediaItemIndex = 0, position = 0L)

        assertEquals("PL123", persisted.playlistBrowseId)
        assertEquals(true, persisted.playlistIsEditable)

        val restored = persisted.toQueue() as ListQueue
        assertEquals("PL123", restored.playlistBrowseId)
        assertEquals(true, restored.playlistIsEditable)
        assertNull(restored.playlistId)

        val target = resolvePlaylistRemovalTarget(restored, "setVideoId-A")
        assertEquals("PL123", target?.playlistId)
        assertNull(target?.localPlaylistId)
    }

    @Test
    fun `queues without playlist context restore without one`() {
        val persisted = ListQueue(
            title = "Ad hoc queue",
            items = listOf(metadata("song-1", null).toMediaItem()),
        ).toPersistQueue(title = "title", items = listOf(metadata("song-1", "setVideoId-A")), mediaItemIndex = 0, position = 0L)

        assertNull(persisted.playlistBrowseId)
        assertNull(persisted.playlistId)
        assertEquals(false, persisted.playlistIsEditable)
        assertNull(resolvePlaylistRemovalTarget(persisted.toQueue(), "setVideoId-A"))
    }

    @Test
    fun `persist queue survives java serialization with its playlist context`() {
        val original = listQueue().toPersistQueue(title = "My playlist", items = songs, mediaItemIndex = 1, position = 42L)

        val bytes = ByteArrayOutputStream().also { out ->
            ObjectOutputStream(out).use { it.writeObject(original) }
        }.toByteArray()
        val roundTripped = ObjectInputStream(ByteArrayInputStream(bytes)).use {
            it.readObject() as PersistQueue
        }

        assertEquals("VLPL_browse", roundTripped.playlistBrowseId)
        assertEquals("local-1", roundTripped.playlistId)
        assertEquals(true, roundTripped.playlistIsEditable)
        assertEquals(listOf("setVideoId-A", "setVideoId-B"), roundTripped.items.map { it.setVideoId })
        assertNotNull(roundTripped.toQueue())
    }
}
