package com.metrolist.android.playback

import com.metrolist.android.models.PersistPlayerState
import com.metrolist.android.models.PlaybackPersistenceWriter
import com.metrolist.android.models.playbackPersistenceJson
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.playback.QUEUE_SOURCE_ADDED_MANUALLY
import com.metrolist.shared.playback.QUEUE_SOURCE_AUTO_MIX
import com.metrolist.shared.playback.QUEUE_SOURCE_RECOMMENDED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShufflePersistencePolicyTest {
    @Test
    fun unavailableRowsDoNotShiftTheSelectedNeighbourWhenContentFiltersAlsoApply() {
        val unavailable = song("unavailable", QUEUE_SOURCE_ADDED_MANUALLY).copy(isUnavailable = true)
        val blocked = song("blocked", QUEUE_SOURCE_ADDED_MANUALLY)
        val duplicate = song("duplicate", QUEUE_SOURCE_ADDED_MANUALLY)
        val songs = listOf(unavailable, blocked, duplicate, unavailable, duplicate)
        val (queue, index) = filteredQueueSelection(songs, 4) { it.id == blocked.id }!!
        assertEquals(listOf(duplicate, duplicate), queue)
        assertEquals(1, index)
        assertNull(filteredQueueSelection(listOf(unavailable), 0) { false })
    }

    @Test
    fun explicitShuffleShufflesCanonicalQueue() {
        assertTrue(newQueueShuffleEnabled(requestedShuffle = true, currentShuffle = false, persistentShuffle = false))
    }

    @Test
    fun persistentShuffleShufflesReplacementQueue() {
        assertTrue(newQueueShuffleEnabled(requestedShuffle = null, currentShuffle = true, persistentShuffle = true))
        assertFalse(newQueueShuffleEnabled(requestedShuffle = null, currentShuffle = true, persistentShuffle = false))
        assertFalse(newQueueShuffleEnabled(requestedShuffle = false, currentShuffle = true, persistentShuffle = true))
    }

    @Test
    fun browseReplacementResolvesShuffleBeforeResetAndKeepsSelectedOccurrence() {
        val duplicate = song("duplicate", QUEUE_SOURCE_ADDED_MANUALLY)
        val songs = listOf(duplicate, song("other", QUEUE_SOURCE_ADDED_MANUALLY), duplicate.copy())
        for (currentShuffle in listOf(false, true)) {
            for (persistentShuffle in listOf(false, true)) {
                for (requestedShuffle in listOf(null, false, true)) {
                    val queue = MusicQueue().apply { setQueue(listOf(song("old", QUEUE_SOURCE_ADDED_MANUALLY))) }
                    if (currentShuffle) queue.toggleShuffle()
                    val shuffleEnabled =
                        newQueueShuffleEnabled(
                            requestedShuffle = requestedShuffle,
                            currentShuffle = queue.isShuffled,
                            persistentShuffle = persistentShuffle,
                        )

                    queue.setQueue(songs, 2)
                    if (shuffleEnabled) queue.toggleShuffle(preserveHistory = requestedShuffle != true)

                    val expectedShuffle =
                        when (requestedShuffle) {
                            true -> true
                            false -> false
                            null -> currentShuffle && persistentShuffle
                        }
                    assertEquals(expectedShuffle, queue.isShuffled)
                    val snapshot = queue.snapshot()
                    assertEquals(songs, snapshot.originalItems)
                    assertEquals(2, snapshot.originalIndices[snapshot.currentIndex])
                    if (queue.isShuffled) queue.toggleShuffle()
                    assertEquals(if (requestedShuffle == true) listOf(songs[2], songs[0], songs[1]) else songs, queue.items.value)
                    assertEquals(if (requestedShuffle == true) 0 else 2, queue.currentIndex.value)
                }
            }
        }
    }

    @Test
    fun startupShuffleRequiresRememberSetting() {
        assertTrue(restoredShuffleMode(persistedShuffle = true, rememberShuffleAndRepeat = true))
        assertFalse(restoredShuffleMode(persistedShuffle = true, rememberShuffleAndRepeat = false))
    }

    @Test
    fun replacementFilteringRemapsSelectedDuplicateOccurrenceAndKeepsTransferMarkers() {
        val blocked = song("blocked", QUEUE_SOURCE_RECOMMENDED)
        val duplicate = song("duplicate", QUEUE_SOURCE_ADDED_MANUALLY)
        val tail = song("tail", QUEUE_SOURCE_AUTO_MIX)
        val (songs, startIndex) =
            filteredQueueSelection(listOf(blocked, duplicate, duplicate, tail), 1) { it === blocked }!!

        assertEquals(listOf(duplicate, duplicate, tail), songs)
        assertEquals(0, startIndex)
        val transferred = MusicQueue().apply { setQueue(songs, startIndex) }
        transferred.toggleShuffle()
        val snapshot = transferred.snapshot()
        assertEquals(0, snapshot.originalIndices[snapshot.currentIndex])
        assertEquals(
            listOf(QUEUE_SOURCE_ADDED_MANUALLY, QUEUE_SOURCE_ADDED_MANUALLY, QUEUE_SOURCE_AUTO_MIX),
            snapshot.originalItems.map(SongItem::queueSourceTitle),
        )
    }

    @Test
    fun replacementFilteringRejectsAnAllBlockedQueue() {
        val songs = listOf(song("blocked", QUEUE_SOURCE_RECOMMENDED))

        assertNull(filteredQueueSelection(songs, 0) { true })
    }

    @Test
    fun backendPersistenceRoundTripsCanonicalQueueAndUnshufflesWhenModesAreNotRemembered() {
        val seed = song("seed", QUEUE_SOURCE_ADDED_MANUALLY)
        val duplicate = song("duplicate", QUEUE_SOURCE_ADDED_MANUALLY)
        val generated = song("generated", QUEUE_SOURCE_AUTO_MIX)
        val blocked = song("blocked", QUEUE_SOURCE_RECOMMENDED)
        val original = listOf(seed, duplicate, generated, duplicate, blocked)
        val displayed = listOf(blocked, duplicate, seed, duplicate, generated)
        val queue =
            MusicQueueSnapshot(
                displayedItems = displayed,
                originalItems = original,
                currentIndex = 1,
                isShuffled = true,
                originalIndices = listOf(4, 3, 0, 1, 2),
            )
        val saved =
            PersistPlayerState(
                playWhenReady = true,
                repeatMode = 2,
                shuffleModeEnabled = true,
                volume = 1f,
                currentPosition = 45_000L,
                currentMediaItemIndex = queue.currentIndex,
                playbackState = 3,
                queueSongs = queue.displayedItems,
                queueSnapshot = queue,
                radioQueueSeedSongId = seed.id,
                radioQueue = true,
            )
        var stored = ""
        PlaybackPersistenceWriter(writePosition = {}) { stored = it }.let { writer -> writer.write(writer.request(saved)) }

        val persisted = playbackPersistenceJson.decodeFromString(PersistPlayerState.serializer(), stored)
        assertTrue(persisted.playWhenReady)
        assertEquals(45_000L, persisted.currentPosition)
        assertEquals(seed.id, persisted.radioQueueSeedSongId)
        assertEquals(queue, persisted.queueSnapshot)

        val (rememberedState, remembered) =
            persisted.restoredPlaybackState(rememberShuffleAndRepeat = true) { it.id == blocked.id }
        assertEquals(45_000L, rememberedState.currentPosition)
        assertTrue(remembered.isShuffled)
        assertEquals(listOf("duplicate", "seed", "duplicate", "generated"), remembered.displayedItems.map(SongItem::id))
        assertEquals(listOf(3, 0, 1, 2), remembered.originalIndices)
        assertEquals(0, remembered.currentIndex)

        val (restoredState, restored) =
            persisted.restoredPlaybackState(rememberShuffleAndRepeat = false) { it.id == blocked.id }
        assertEquals(45_000L, restoredState.currentPosition)
        assertFalse(restored.isShuffled)
        assertEquals(listOf(duplicate, seed, duplicate, generated), restored.displayedItems)
        assertEquals(
            listOf(QUEUE_SOURCE_ADDED_MANUALLY, QUEUE_SOURCE_ADDED_MANUALLY, QUEUE_SOURCE_ADDED_MANUALLY, QUEUE_SOURCE_AUTO_MIX),
            restored.displayedItems.map(SongItem::queueSourceTitle),
        )
        assertEquals(listOf(0, 1, 2, 3), restored.originalIndices)
        assertEquals(0, restored.currentIndex)

        val (_, legacy) =
            persisted.copy(queueSnapshot = null).restoredPlaybackState(rememberShuffleAndRepeat = false) { false }
        assertEquals(displayed, legacy.displayedItems)
        assertEquals(1, legacy.currentIndex)
        assertFalse(legacy.isShuffled)
    }

    @Test
    fun backendPersistenceResetsPositionWhenCurrentOccurrenceIsBlocked() {
        val keptDuplicate = song("duplicate", QUEUE_SOURCE_ADDED_MANUALLY).copy(title = "kept occurrence")
        val blockedDuplicate = song("duplicate", QUEUE_SOURCE_RECOMMENDED).copy(title = "blocked occurrence")
        val tail = song("tail", QUEUE_SOURCE_AUTO_MIX)
        val queue =
            MusicQueueSnapshot(
                displayedItems = listOf(tail, blockedDuplicate, keptDuplicate),
                originalItems = listOf(keptDuplicate, blockedDuplicate, tail),
                currentIndex = 1,
                isShuffled = true,
                originalIndices = listOf(2, 1, 0),
            )
        val saved =
            PersistPlayerState(
                playWhenReady = false,
                repeatMode = 0,
                shuffleModeEnabled = true,
                volume = 1f,
                currentPosition = 45_000L,
                currentMediaItemIndex = queue.currentIndex,
                playbackState = 1,
                queueSongs = queue.displayedItems,
                queueSnapshot = queue,
            )
        var stored = ""
        PlaybackPersistenceWriter(writePosition = {}) { stored = it }.let { writer -> writer.write(writer.request(saved)) }

        val persisted = playbackPersistenceJson.decodeFromString(PersistPlayerState.serializer(), stored)
        val (restoredState, restoredQueue) =
            persisted.restoredPlaybackState(rememberShuffleAndRepeat = true) { it.title == blockedDuplicate.title }

        assertEquals(0L, restoredState.currentPosition)
        assertEquals(listOf(tail, keptDuplicate), restoredQueue.displayedItems)
        assertEquals(1, restoredQueue.currentIndex)
        assertEquals(blockedDuplicate.id, restoredQueue.displayedItems[restoredQueue.currentIndex].id)
        assertEquals("kept occurrence", restoredQueue.displayedItems[restoredQueue.currentIndex].title)
    }

    private fun song(
        id: String,
        queueSourceTitle: String,
    ) = SongItem(id, id, null, emptyList(), null, queueSourceTitle = queueSourceTitle)
}
