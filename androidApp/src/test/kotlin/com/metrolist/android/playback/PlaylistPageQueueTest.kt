package com.metrolist.android.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistPageQueueTest {
    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)

    @Test
    fun pagesPreserveDuplicateOccurrencesSelectedTrackAndCanonicalOrderThroughShuffle() {
        val first = listOf(song("a"), song("repeat"), song("b"))
        val second = listOf(song("repeat"), song("c"), song("d"))
        for (shuffle in listOf(false, true)) {
            val queue = MusicQueue()
            queue.setQueue(first, 1)
            val selected =
                queue.state.value.entries[1]
                    .key
            if (shuffle) queue.toggleShuffle()
            queue.appendPage(second)
            queue.appendPage(listOf(song("e")))
            assertEquals(
                selected,
                queue.state.value.entries[queue.currentIndex.value]
                    .key,
            )
            assertEquals(
                7,
                queue.state.value.entries
                    .map { it.key }
                    .toSet()
                    .size,
            )
            assertEquals(first + second + song("e"), queue.snapshot().originalItems)
            assertEquals(2, queue.items.value.count { it.id == "repeat" })
            if (shuffle) queue.toggleShuffle()
            assertEquals(first + second + song("e"), queue.items.value)
            assertEquals(1, queue.currentIndex.value)
            assertTrue(
                queue.items.value
                    .last()
                    .id == "e",
            )
        }
    }
}
