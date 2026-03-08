package com.metrolist.shared.playback

import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class QueueEnqueueTest {
    @Test
    fun stoppedAndEmptyPlayersReplaceTheQueueAndPrepareFirstSongPaused() {
        for (retained in listOf(emptyList(), listOf(song("first")), listOf(song("old"), song("first")), List(58) { song("old-$it") })) {
            for (playNext in listOf(false, true)) {
                val queue = MusicQueue().apply { setQueue(retained) }
                var currentSong: SongItem? = null
                val requests = mutableListOf<Pair<SongItem, Boolean>>()

                fun enqueue(songs: List<SongItem>) {
                    enqueueOrStartQueue(
                        songs,
                        currentSong,
                        preventDuplicates = true,
                        startQueue = { additions, startPlaying ->
                            queue.setQueue(additions)
                            currentSong = queue.currentSong()
                            requests += assertNotNull(currentSong) to startPlaying
                        },
                        enqueue = { additions ->
                            if (playNext) {
                                queue.playNext(additions, preventDuplicates = true)
                            } else {
                                queue.addToQueue(additions, preventDuplicates = true)
                            }
                        },
                    )
                }
                val additions = listOf(song("first"), song("second"), song("third"))
                enqueue(additions)
                assertEquals(additions, queue.items.value)
                assertEquals(0, queue.currentIndex.value)
                assertEquals(additions.first(), currentSong)
                assertEquals(additions.first(), requests.single().first)
                assertFalse(requests.single().second)

                enqueue(listOf(song("later")))
                assertEquals(1, requests.size)
                assertEquals(additions.first(), currentSong)
                assertEquals(
                    if (playNext) listOf("first", "later", "second", "third") else listOf("first", "second", "third", "later"),
                    queue.items.value.map(SongItem::id),
                )
            }
        }
    }

    @Test
    fun visibleCurrentItemPreservesQueueSelectionAndDoesNotRequestPlayback() {
        for (playNext in listOf(false, true)) {
            val queue = MusicQueue().apply { setQueue(listOf(song("past"), song("current"), song("upcoming")), 1) }
            val current = queue.currentSong()
            enqueueOrStartQueue(
                listOf(song("added")),
                current,
                preventDuplicates = false,
                startQueue = { _, _ -> error("A paused, buffering, or playing current item must be retained") },
                enqueue = { additions ->
                    if (playNext) queue.playNext(additions) else queue.addToQueue(additions)
                },
            )
            assertEquals(current, queue.currentSong())
            assertEquals(1, queue.currentIndex.value)
            assertEquals(
                if (playNext) listOf("past", "current", "added", "upcoming") else listOf("past", "current", "upcoming", "added"),
                queue.items.value.map(SongItem::id),
            )
        }
    }

    @Test
    fun emptyAdditionDoesNotReplaceOrPrepareAStoppedQueue() {
        enqueueOrStartQueue(
            emptyList(),
            null,
            preventDuplicates = true,
            startQueue = { _, _ -> error("Empty input must not start a queue") },
            enqueue = { error("Empty input must not mutate a queue") },
        )
    }

    @Test
    fun startingEmptyOrDismissedQueueRespectsDuplicatePreferenceForBothActions() {
        val first = song("first")
        val second = song("second")
        val additions = listOf(first, first.copy(title = "repeated"), second, first, second)
        for (retained in listOf(emptyList(), listOf(song("old"), first))) {
            for (playNext in listOf(false, true)) {
                for (preventDuplicates in listOf(false, true)) {
                    val queue = MusicQueue().apply { setQueue(retained) }
                    val requests = mutableListOf<Pair<SongItem, Boolean>>()
                    enqueueOrStartQueue(
                        additions,
                        currentSong = null,
                        preventDuplicates = preventDuplicates,
                        startQueue = { songs, startPlaying ->
                            queue.setQueue(songs)
                            requests += assertNotNull(queue.currentSong()) to startPlaying
                        },
                        enqueue = { songs ->
                            if (playNext) {
                                queue.playNext(songs, preventDuplicates)
                            } else {
                                queue.addToQueue(songs, preventDuplicates)
                            }
                        },
                    )
                    assertEquals(if (preventDuplicates) listOf(first, second) else additions, queue.items.value)
                    assertEquals(0, queue.currentIndex.value)
                    assertEquals(first to false, requests.single())
                }
            }
        }
    }

    private fun song(id: String) = SongItem(id = id, title = id, thumbnail = null, artists = emptyList(), album = null)
}
