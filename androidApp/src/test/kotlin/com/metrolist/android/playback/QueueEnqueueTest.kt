package com.metrolist.android.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.QUEUE_SOURCE_ADDED_MANUALLY
import com.metrolist.shared.playback.QUEUE_SOURCE_RECOMMENDED
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueEnqueueTest {
    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)

    @Test
    fun batchesKeepOrderBeforeRecommendationsAndRespectCurrentDuplicate() {
        val queue = MusicQueue()
        queue.setQueue(listOf(song("current"), song("suggested").copy(queueSourceTitle = QUEUE_SOURCE_RECOMMENDED)))
        enqueueSongs(queue, listOf(song("a"), song("b")), false, false)
        assertEquals(listOf("current", "a", "b", "suggested"), queue.items.value.map { it.id })
        enqueueSongs(queue, listOf(song("current"), song("b").copy(queueSourceTitle = QUEUE_SOURCE_RECOMMENDED), song("c")), true, true)
        assertEquals(listOf("current", "b", "c", "a", "suggested"), queue.items.value.map { it.id })
        assertEquals(QUEUE_SOURCE_ADDED_MANUALLY, queue.items.value[1].queueSourceTitle)
    }

    @Test
    fun appendKeepsLastDuplicateWhilePlayNextKeepsFirstDuplicate() {
        val first = song("a").copy(title = "first")
        val last = first.copy(title = "last")
        for (playNext in listOf(false, true)) {
            val queue = MusicQueue().apply { setQueue(listOf(song("current"))) }
            enqueueSongs(queue, listOf(first, song("b"), last), playNext, true)
            assertEquals(if (playNext) listOf("current", "a", "b") else listOf("current", "b", "a"), queue.items.value.map { it.id })
            assertEquals(
                if (playNext) "first" else "last",
                queue.items.value
                    .first { it.id == "a" }
                    .title,
            )
        }
    }
}
