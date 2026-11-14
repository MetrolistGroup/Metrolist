package com.metrolist.shared.playback

import com.metrolist.shared.deviceconnect.DcLocalPlaybackState
import com.metrolist.shared.deviceconnect.DcPlaybackAction
import com.metrolist.shared.deviceconnect.DcPlaybackCommand
import com.metrolist.shared.deviceconnect.reduceDcPlayback
import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyShuffleTest {
    private val duplicate = SongItem("same", "Same", null, emptyList(), null)
    private val testSongs =
        listOf(
            duplicate,
            duplicate.copy(queueSourceTitle = QUEUE_SOURCE_RECOMMENDED),
            duplicate,
            duplicate.copy(queueSourceTitle = QUEUE_SOURCE_AUTO_MIX),
            duplicate,
        )

    @Test
    fun bothStylesKeepTheSelectedOccurrenceAndVisitEveryUpcomingOccurrence() {
        for (legacy in listOf(false, true)) {
            for (preserveHistory in listOf(false, true)) {
                for (startIndex in -1..testSongs.lastIndex) {
                    val queue = MusicQueue().apply { setQueue(testSongs, startIndex) }
                    val original = queue.state.value.entries
                    val prefix =
                        if (preserveHistory && !legacy) original.take(startIndex + 1) else listOfNotNull(original.getOrNull(startIndex))

                    queue.toggleShuffle(preserveHistory, legacyShuffle = legacy)

                    assertTrue(queue.isShuffled)
                    assertEquals(prefix.lastIndex, queue.currentIndex.value)
                    assertEquals(
                        prefix,
                        queue.state.value.entries
                            .take(prefix.size),
                    )
                    assertEquals(
                        original.map { it.key }.toSet(),
                        queue.state.value.entries
                            .map { it.key }
                            .toSet(),
                    )
                    assertEquals(testSongs, queue.snapshot().originalItems)
                    assertEquals(
                        original.getOrNull(startIndex),
                        queue.state.value.entries
                            .getOrNull(queue.currentIndex.value),
                    )
                    val visited = mutableSetOf<Long>()
                    while (queue.next() != null) {
                        visited +=
                            queue.state.value.entries[queue.currentIndex.value]
                                .key
                    }
                    assertEquals(original.filterNot { it in prefix }.map { it.key }.toSet(), visited)
                }
            }
        }
    }

    @Test
    fun unshuffleRetainsPlayedOccurrencesAfterChangingStyleAndRestoringSnapshot() {
        val queue = MusicQueue().apply { setQueue(testSongs, testSongs.lastIndex) }
        val original = queue.state.value.entries
        queue.toggleShuffle(legacyShuffle = true)
        queue.next()
        val snapshot = queue.snapshot()
        val playedIndices = snapshot.originalIndices.take(snapshot.currentIndex + 1)
        val restored = MusicQueue().apply { restore(snapshot) }

        restored.toggleShuffle(legacyShuffle = false)

        val expected = playedIndices + original.indices.filterNot { it in playedIndices }
        assertFalse(restored.isShuffled)
        assertEquals(expected.map { original[it].song }, restored.items.value)
        assertEquals(snapshot.currentIndex, restored.currentIndex.value)
        assertEquals(snapshot.displayedItems[snapshot.currentIndex], restored.currentItem())
    }

    @Test
    fun persistentShuffleAndRemoteCommandsUseTheSelectedStyle() {
        for (legacy in listOf(false, true)) {
            val queue = MusicQueue().apply { setQueue(listOf(duplicate.copy(id = "old"))) }
            queue.toggleShuffle(legacyShuffle = legacy)
            queue.syncQueue(testSongs, testSongs.lastIndex, persistentShuffle = true, legacyShuffle = legacy)
            assertEquals(if (legacy) 0 else testSongs.lastIndex, queue.currentIndex.value)
            assertEquals(testSongs.lastIndex, queue.snapshot().originalIndices[queue.currentIndex.value])

            val remote =
                reduceDcPlayback(
                    DcLocalPlaybackState(queue = MusicQueue().apply { setQueue(testSongs, testSongs.lastIndex) }.snapshot()),
                    DcPlaybackCommand(DcPlaybackAction.SHUFFLE, enabled = true),
                    legacyShuffle = legacy,
                )!!.playback.queue
            assertTrue(remote.isShuffled)
            assertEquals(if (legacy) 0 else testSongs.lastIndex, remote.currentIndex)
            assertEquals(testSongs.lastIndex, remote.originalIndices[remote.currentIndex])
            assertEquals(testSongs, remote.originalItems)
        }
    }
}
