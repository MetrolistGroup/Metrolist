package com.metrolist.music.playback.queues

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.extensions.clearPlaylistSource
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.withoutPlaylistSource
import com.metrolist.music.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaylistRemovalMenuStateTest {
    private fun song(occurrence: String, browseId: String = "VLplaylist-A") = MediaMetadata(
        id = "song", title = "Song", artists = emptyList(), duration = 123,
        setVideoId = occurrence, playlistBrowseId = browseId,
    )

    @Test
    fun `clearing an occurrence updates the live menu and all its queued copies only`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val player = ExoPlayer.Builder(context).build()
        try {
            val original = song("first")
            val items = listOf(original, song("second"), original, song("other", "VLplaylist-B")).map { it.toMediaItem() }
            var observed: MediaMetadata? = null
            player.addListener(object : Player.Listener {
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    observed = player.currentMediaItem?.metadata
                }
            })
            player.setMediaItems(items)
            player.clearPlaylistSource { it.setVideoId == "first" && it.playlistBrowseId == "VLplaylist-A" }
            val liveItems = (0 until player.mediaItemCount).map(player::getMediaItemAt)
            assertNull(observed?.playlistBrowseId)
            assertNull(liveItems[0].metadata?.playlistBrowseId)
            assertNull(liveItems[2].metadata?.playlistBrowseId)
            assertEquals("VLplaylist-A", liveItems[1].metadata?.playlistBrowseId)
            assertEquals("VLplaylist-B", liveItems[3].metadata?.playlistBrowseId)
            val menu = resolvePlaylistMenuMetadata(original, liveItems)
            assertNull(menu.playlistBrowseId)
            val queue = ListQueue(items = liveItems, playlistBrowseId = "VLplaylist-A", playlistIsEditable = true)
            assertNull(resolvePlaylistRemovalTarget(queue, menu.setVideoId, menu.playlistBrowseId))
            assertEquals("first", menu.setVideoId)
        } finally {
            player.release()
        }
    }

    @Test
    fun `local occurrence keeps its identity after source removal so an old menu snapshot resolves correctly`() {
        val original = song("first").copy(playlistBrowseId = null, localPlaylistId = "local", playlistSongMapId = 42)
        val cleared = original.toMediaItem().withoutPlaylistSource()
        val menu = resolvePlaylistMenuMetadata(original, listOf(cleared))
        assertNull(menu.localPlaylistId)
        assertEquals(42, menu.playlistSongMapId)
    }

    @Test
    fun `unknown source never inherits another queued copy's playlist`() {
        val original = song("first").copy(playlistBrowseId = null)
        val menu = resolvePlaylistMenuMetadata(original, listOf(song("first").toMediaItem()))
        assertNull(menu.playlistBrowseId)
    }

    @Test
    fun `a queue item that disappeared cannot keep an actionable stale source`() {
        val menu = resolvePlaylistMenuMetadata(song("deleted"), listOf(song("second").toMediaItem()))
        assertNull(menu.playlistBrowseId)
    }
}
