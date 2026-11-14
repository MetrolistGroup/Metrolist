package com.metrolist.desktop.state.playback

import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.playback.shouldSkipAfterPlaybackFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueOccurrenceResolutionTest {
    @Test
    fun failedNextOccurrenceStaysSelectedUnlessAutoSkipIsEnabled() =
        runTest {
            for (autoSkip in listOf(false, true)) {
                val queue = MusicQueue()
                val songs = listOf("current", "failed", "next").map { SongItem(it, it, null, emptyList(), null) }
                queue.setQueue(songs)
                var errorReported = false
                val failed =
                    resolveNextQueueOccurrence<String>(queue, { true }) {
                        errorReported = true
                        null
                    }
                assertEquals(songs[1], failed?.first)
                assertNull(failed?.second)
                assertEquals(true, errorReported)
                assertEquals(1, queue.currentIndex.value)
                if (shouldSkipAfterPlaybackFailure(autoSkip, true, true, false, PlaybackFailureCategory.UNAVAILABLE)) {
                    resolveNextQueueOccurrence(queue, { true }) { it.id }
                }
                assertEquals(if (autoSkip) 2 else 1, queue.currentIndex.value)
            }
        }

    @Test
    fun failedResolutionCannotSelectARemovedOccurrenceOrASupersededRequest() =
        runTest {
            val queue = MusicQueue()
            val songs = listOf("current", "removed", "remaining").map { SongItem(it, it, null, emptyList(), null) }
            queue.setQueue(songs)
            var current = true
            val result =
                resolveNextQueueOccurrence<String>(queue, { current }) {
                    queue.removeAt(setOf(1))
                    current = false
                    null
                }
            assertNull(result)
            assertEquals(0, queue.currentIndex.value)
        }

    @Test
    fun removingTheOnlyNextEntryLeavesQueueEndPolicyAvailable() =
        runTest {
            val queue = MusicQueue()
            val song = SongItem("current", "Current", null, emptyList(), null)
            queue.setQueue(listOf(song, song.copy(id = "next")))
            val next =
                resolveNextQueueOccurrence(queue, { true }) {
                    queue.removeAt(setOf(1))
                    it.id
                }
            assertNull(next)
            assertEquals(song, queue.currentItem())
            assertEquals(
                PlaybackQueueAction.AUTOPLAY,
                decidePlaybackQueueTransition(RepeatMode.OFF, false, song, queue.items.value, queue.currentIndex.value).action,
            )
        }

    @Test
    fun removedNextOccurrenceIsRecomputedAfterResourceResolution() =
        runTest {
            val queue = MusicQueue()
            val songs = listOf("A", "B", "C").map { SongItem(it, it, null, emptyList(), null) }
            queue.setQueue(songs)
            val resolving = CompletableDeferred<Unit>()
            val resolved = CompletableDeferred<Unit>()
            val next =
                async {
                    resolveNextQueueOccurrence(queue, { true }) {
                        if (it.id == "B") {
                            resolving.complete(Unit)
                            resolved.await()
                        }
                        it.id
                    }
                }
            resolving.await()
            queue.removeAt(setOf(1))
            resolved.complete(Unit)
            assertEquals("C", next.await()?.second)
            assertEquals(songs[2], queue.currentItem())
        }

    @Test
    fun duplicateInsertionChangesTheOccurrenceButUnrelatedEditsDoNotRestartResolution() =
        runTest {
            val queue = MusicQueue()
            val song = SongItem("same", "Same", null, emptyList(), null)
            queue.setQueue(listOf(song, song))
            var lookups = 0
            val next =
                resolveNextQueueOccurrence(queue, { true }) {
                    lookups++
                    if (lookups == 1) queue.playNext(listOf(song)) else queue.addToQueue(listOf(song))
                    lookups
                }
            assertEquals(2, lookups)
            assertEquals(2, next?.second)
            assertEquals(
                queue.state.value.entries[1]
                    .key,
                queue.state.value.entries[queue.currentIndex.value]
                    .key,
            )
        }
}
