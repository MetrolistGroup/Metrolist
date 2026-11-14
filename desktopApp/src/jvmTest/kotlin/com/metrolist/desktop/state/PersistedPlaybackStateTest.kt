package com.metrolist.desktop.state

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PersistedPlaybackStateTest {
    @Test
    fun restoreWithoutRememberingShuffleKeepsCurrentOccurrenceAndOrdersUpcomingSongs() {
        val first = song("same", "first")
        val second = song("same", "second")
        val snapshot =
            MusicQueueSnapshot(
                displayedItems = listOf(second, song("other", "other"), first),
                originalItems = listOf(first, second, song("other", "other")),
                currentIndex = 0,
                isShuffled = true,
            )

        val restored = snapshot.forRestore(rememberShuffle = false)

        assertFalse(restored.isShuffled)
        assertEquals(listOf("second", "first", "other"), restored.displayedItems.map { it.title })
        assertEquals(0, restored.currentIndex)
        assertEquals("second", restored.displayedItems[restored.currentIndex].title)
    }

    @Test
    fun restoreRejectsMismatchedCanonicalItemsBeforeDisablingShuffle() {
        val snapshot =
            MusicQueueSnapshot(
                displayedItems = listOf(song("a", "a"), song("b", "b")),
                originalItems = listOf(song("a", "a"), song("foreign", "foreign")),
                currentIndex = 1,
                isShuffled = true,
            )

        val restored = snapshot.forRestore(rememberShuffle = false)

        assertEquals(listOf("a", "b"), restored.displayedItems.map { it.id })
        assertEquals("b", restored.displayedItems[restored.currentIndex].id)
    }

    private fun song(
        id: String,
        title: String,
    ) = SongItem(id, title, null, emptyList(), null)
}
