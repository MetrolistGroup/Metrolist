package com.metrolist.android.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PendingQueueRestorePositionTest {
    @Test
    fun playingAnotherOccurrenceDoesNotReuseTheRestoredPosition() {
        for (requested in listOf(0L, 7_000L)) {
            val queue = restoredQueue()
            val first = queue.currentEntry()
            val position = PendingQueueRestorePosition().apply { restore(first.key, 42_000L) }

            queue.skipTo(2)
            assertEquals(first.song, queue.currentEntry().song)
            assertNotEquals(first.key, queue.currentEntry().key)
            assertEquals(requested, position.take(queue.currentEntry().key, requested))

            queue.skipTo(0)
            assertEquals(0L, position.take(queue.currentEntry().key, 0L))
        }
    }

    @Test
    fun movingAndSeekingTheOriginalOccurrenceKeepsItsPendingOffset() {
        val queue = restoredQueue()
        val original = queue.currentEntry()
        val position = PendingQueueRestorePosition().apply { restore(original.key, 42_000L) }
        queue.move(0, 1)
        assertEquals(original, queue.currentEntry())

        position.update(queue.currentEntry().key, 9_000L)
        assertEquals(9_000L, position.take(queue.currentEntry().key, 0L))
        assertEquals(0L, position.take(queue.currentEntry().key, 0L))
    }

    @Test
    fun aZeroSavedPositionDoesNotOverrideAnExplicitRequest() {
        val position = PendingQueueRestorePosition().apply { restore(10L, 0L) }
        assertEquals(7_000L, position.take(10L, 7_000L))
        position.restore(10L, 42_000L)
        position.update(10L, 0L)
        assertEquals(0L, position.take(10L, 7_000L))
    }

    private fun restoredQueue(): MusicQueue {
        val duplicate = SongItem("duplicate", "Duplicate", null, emptyList(), null)
        val other = duplicate.copy(id = "other")
        val prior = MusicQueue().apply { setQueue(listOf(duplicate, other, duplicate)) }
        return MusicQueue().apply { restore(prior.snapshot()) }
    }

    private fun MusicQueue.currentEntry(): MusicQueueEntry = state.value.let { it.entries[it.currentIndex] }
}
