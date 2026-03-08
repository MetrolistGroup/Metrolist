package com.metrolist.shared.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.moveSelection
import kotlin.test.Test
import kotlin.test.assertEquals

class QueueReorderTest {
    @Test
    fun batchReorderPreservesDuplicateKeysCurrentOccurrenceShuffleAndUndo() {
        for (shuffled in listOf(false, true)) {
            val queue = queue()
            if (shuffled) queue.toggleShuffle()
            val before = queue.state.value
            val original = queue.snapshot().originalItems
            val order =
                before.entries.indices
                    .toList()
                    .moveSelection(0, 3, setOf(0, 2))
            queue.reorder(order)
            assertEquals(
                order.map { before.entries[it].key },
                queue.state.value.entries
                    .map { it.key },
            )
            assertEquals(
                before.entries[before.currentIndex].key,
                queue.state.value.entries[queue.currentIndex.value]
                    .key,
            )
            if (shuffled) assertEquals(original, queue.snapshot().originalItems)
            val committed = queue.state.value
            queue.reorder(listOf(0, 0, 1, 2))
            queue.reorder(listOf(0, 1, 2))
            assertEquals(committed, queue.state.value)
            val removed = requireNotNull(queue.removeAt(setOf(0)))
            queue.reorder(listOf(2, 0, 1))
            queue.restore(removed)
            assertEquals(4, queue.state.value.entries.size)
        }
    }

    private fun queue() =
        MusicQueue().apply {
            val duplicate = SongItem("duplicate", "Song", null, emptyList(), null)
            setQueue(List(4) { duplicate }, startIndex = 1)
        }
}
