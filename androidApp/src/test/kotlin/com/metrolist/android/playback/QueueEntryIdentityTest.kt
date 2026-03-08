package com.metrolist.android.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueEntryIdentityTest {
    @Test
    fun bulkSelectionTraversesKeysOnceAndPreservesMissingKeyValidation() {
        val song = SongItem("duplicate", "Track", null, emptyList(), null)
        val queue = MusicQueue().apply { setQueue(List(10_000) { song }) }
        val keys =
            queue.state.value.entries
                .mapTo(mutableSetOf()) { it.key }
        var membershipChecks = 0
        val selection =
            object : Set<Long> by keys {
                override fun contains(element: Long): Boolean {
                    membershipChecks++
                    return keys.contains(element)
                }
            }
        assertEquals((0 until 10_000).toSet(), queueEntryIndices(queue.state.value, selection))
        assertEquals(10_000, membershipChecks)
        assertNull(queueEntryIndices(queue.state.value, keys + Long.MAX_VALUE))
        assertEquals(emptySet<Int>(), queueEntryIndices(queue.state.value, emptySet()))
    }

    @Test
    fun removedOccurrenceCannotRetargetShiftedDuplicate() {
        val duplicate = SongItem("duplicate", "Track", null, emptyList(), null)
        val queue = MusicQueue().apply { setQueue(listOf(duplicate, duplicate)) }
        val removedKey =
            queue.state.value.entries
                .first()
                .key

        assertEquals(0, queueEntryIndex(queue.state.value, removedKey))
        queue.removeAt(setOf(0))

        assertNull(queueEntryIndex(queue.state.value, removedKey))
        assertEquals(listOf("duplicate"), queue.items.value.map { it.id })
    }

    @Test
    fun duplicateOccurrencesResolveTogetherOrRejectTheStaleSelection() {
        val duplicate = SongItem("duplicate", "Track", null, emptyList(), null)
        val queue = MusicQueue().apply { setQueue(listOf(duplicate, duplicate, duplicate.copy(id = "other"))) }
        val keys =
            queue.state.value.entries
                .map { it.key }

        assertEquals(setOf(0, 1), queueEntryIndices(queue.state.value, setOf(keys[0], keys[1])))

        queue.removeAt(setOf(0))

        assertNull(queueEntryIndices(queue.state.value, setOf(keys[0], keys[1])))
    }
}
