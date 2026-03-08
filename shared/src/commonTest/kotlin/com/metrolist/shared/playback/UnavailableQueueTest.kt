package com.metrolist.shared.playback

import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnavailableQueueTest {
    private val unavailable = song("unavailable").copy(isUnavailable = true, playlistSetVideoId = "removed-occurrence")

    @Test
    fun queueSyncRemapsTheSelectionWhenAppendingPastUnavailableRows() {
        val current = song("current")
        val last = song("last")
        val queue = MusicQueue().apply { setQueue(listOf(current)) }
        queue.syncQueue(listOf(current, unavailable, last), 2, persistentShuffle = true)
        assertEquals(listOf(current, last), queue.items.value)
        assertEquals(last, queue.currentSong())
        assertEquals(1, queue.currentIndex.value)
    }

    @Test
    fun listPlaybackKeepsTheSelectedOccurrenceAfterSkippingUnavailableRows() {
        val duplicate = song("duplicate")
        val songs = listOf(unavailable, duplicate, unavailable, duplicate, song("last"))
        val queue = MusicQueue()
        queue.setQueue(songs, 3)
        assertEquals(listOf(duplicate, duplicate, songs.last()), queue.items.value)
        assertEquals(1, queue.currentIndex.value)
        queue.toggleShuffle()
        assertEquals(duplicate, queue.currentSong())
        assertTrue(queue.items.value.none { it.isUnavailable })
        queue.setQueue(listOf(unavailable))
        assertTrue(queue.items.value.isEmpty())
        assertEquals(-1, queue.currentIndex.value)
    }

    @Test
    fun selectionsContinuationsAndRestoredQueuesExcludeUnavailableRows() {
        val queue = MusicQueue().apply { setQueue(listOf(song("current"))) }
        queue.addToQueue(listOf(unavailable, song("added")))
        queue.playNext(listOf(unavailable, song("next")))
        queue.appendPage(listOf(unavailable, song("continued")))
        assertEquals(listOf("current", "next", "added", "continued"), queue.items.value.map { it.id })
        queue.replaceUpcomingQueue(listOf(unavailable, song("replacement")))
        assertEquals(listOf("current", "replacement"), queue.items.value.map { it.id })
        val songs = listOf(unavailable, song("current"), unavailable, song("last"))
        queue.restore(MusicQueueSnapshot(songs, songs, 1))
        assertEquals(listOf("current", "last"), queue.items.value.map { it.id })
        assertEquals(0, queue.currentIndex.value)
    }

    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)
}
