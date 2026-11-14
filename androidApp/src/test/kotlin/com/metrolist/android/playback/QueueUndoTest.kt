package com.metrolist.android.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueUndoTest {
    @Test
    fun exactUndoRejectsDifferentSessionRevisionOrStandaloneQueue() {
        val song = SongItem("duplicate", "Track", null, emptyList(), null)
        val queue =
            MusicQueue().apply {
                setQueue(listOf(song, song), startIndex = 1)
                toggleShuffle()
            }
        val before = queue.snapshot()
        val removal = checkNotNull(queue.removeAt(setOf(1)))
        val after = queue.snapshot()
        val undo = QueueUndo(listOf(removal), before, after, "session", 4L)
        assertTrue(undo.isCurrent(after, "session", 4L))
        assertFalse(undo.isCurrent(after, "session", 5L))
        assertFalse(undo.isCurrent(after.copy(currentIndex = -1), "session", 4L))
        assertFalse(undo.isCurrent(after, "other", 4L))
        assertFalse(undo.isCurrent(after, null, null))
        val standalone = undo.copy(sessionId = null, queueRevision = null)
        assertTrue(standalone.isCurrent(after, null, null))
        assertFalse(standalone.isCurrent(before, null, null))
    }

    @Test
    fun remoteUndoMapsLaterRemovalsToOriginalIndices() {
        val songs = listOf("A", "B", "C", "D").map { SongItem(it, it, null, emptyList(), null) }
        val queue = MusicQueue().apply { setQueue(songs, startIndex = 2) }
        val before = queue.snapshot()
        val first = checkNotNull(queue.removeAt(setOf(0)))
        val afterFirst = queue.snapshot()
        val second = checkNotNull(queue.removeAt(setOf(2)))
        val undo =
            QueueUndo(listOf(first), before, afterFirst, "session", 5L)
                .append(second, afterFirst, queue.snapshot(), "session", 5L, 6L)

        assertEquals(setOf(0, 3), undo.originalRemovalIndices())
        assertFalse(before.currentIndex in undo.originalRemovalIndices())
    }

    @Test
    fun consecutiveRemovalsUndoInReverseAndStaleQueueStartsNewBatch() {
        val songs = (1..4).map { SongItem("$it", "Track $it", null, emptyList(), null) }
        val queue = MusicQueue().apply { setQueue(songs) }
        val before = queue.snapshot()
        val first = checkNotNull(queue.removeAt(setOf(1)))
        val afterFirst = queue.snapshot()
        val second = checkNotNull(queue.removeAt(setOf(1)))
        val afterSecond = queue.snapshot()
        val undo = QueueUndo(listOf(first), before, afterFirst, null, null).append(second, afterFirst, afterSecond, null, null)

        assertEquals(listOf(first, second), undo.removals)
        assertEquals(before, undo.before)
        undo.removals.asReversed().forEach(queue::restore)
        assertEquals(before, queue.snapshot())
        assertEquals(listOf(second), undo.append(second, before, afterSecond, null, null).removals)

        val remote = QueueUndo(listOf(first), before, afterFirst, "session", 5L)
        val extended = remote.append(second, afterFirst, afterSecond, "session", 5L, 6L)
        assertEquals(listOf(first, second), extended.removals)
        assertEquals(before, extended.before)
        assertTrue(extended.isCurrent(afterSecond, "session", 6L))
        assertEquals(listOf(second), remote.append(second, afterFirst, afterSecond, "session", 6L, 7L).removals)
        assertEquals(listOf(second), remote.append(second, afterFirst, afterSecond, "other", 5L, 6L).removals)
    }
}
