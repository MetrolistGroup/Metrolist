package com.metrolist.music.playback

import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.Queue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class QueueStatusFilterTest {
    private fun item(id: String, explicit: Boolean = false) =
        MediaMetadata(id = id, title = id, artists = emptyList(), duration = 0, explicit = explicit).toMediaItem()

    private val items = listOf(item("a", explicit = true), item("b"), item("c", explicit = true), item("d"))

    @Test
    fun `filtering keeps the selected song as the start item`() {
        val status = Queue.Status(null, items, mediaItemIndex = 3, position = 500L).filterExplicit()

        assertEquals(listOf("b", "d"), status.items.map { it.mediaId })
        assertEquals("d", status.items[status.mediaItemIndex].mediaId)
        assertEquals(500L, status.position)
    }

    @Test
    fun `filtered start song falls through to the next kept song`() {
        val status = Queue.Status(null, items, mediaItemIndex = 2, position = 500L).filterExplicit()

        assertEquals("d", status.items[status.mediaItemIndex].mediaId)
        assertEquals(0L, status.position)
    }
}
