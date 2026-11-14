package com.metrolist.shared.deviceconnect

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceConnectPlaybackReducerTest {
    @Test
    fun radioReplacementRetainsTheAudibleTracksClockAndPlaybackState() {
        val seed = SongItem("seed", "Seed", null, emptyList(), null, duration = 180)
        val queue = MusicQueue().apply { setQueue(listOf(seed.copy(id = "previous"), seed, seed.copy(id = "next")), 1) }
        val command =
            DcPlaybackCommand(
                DcPlaybackAction.REPLACE_QUEUE,
                songs = listOf(seed),
                index = 0,
                finite = false,
                positionMs = 30_000L,
                preserveCurrentTrack = true,
            )
        assertEquals(command, DcJson.decodeFromString<DcPlaybackCommand>(DcJson.encodeToString(command)))
        for (playing in listOf(false, true)) {
            val state = DcLocalPlaybackState(queue = queue.snapshot(), ready = true, playing = playing, positionMs = 42_000L)
            val change = reduceDcPlayback(state, command)!!
            assertFalse(change.trackChanged)
            assertEquals(42_000L, change.playback.positionMs)
            assertEquals(playing, change.playback.playing)
            assertTrue(change.playback.ready)
            assertFalse(change.playback.finite)
            assertEquals(listOf(seed), change.playback.queue.displayedItems)
            assertEquals(0, change.playback.queue.currentIndex)

            val different = reduceDcPlayback(state, command.copy(songs = listOf(seed.copy(id = "different"))))!!
            assertTrue(different.trackChanged)
            assertEquals(0L, different.playback.positionMs)
            assertFalse(different.playback.ready)

            val ordinary = reduceDcPlayback(state, command.copy(preserveCurrentTrack = false, positionMs = 0L))!!
            assertTrue(ordinary.trackChanged)
            assertEquals(0L, ordinary.playback.positionMs)

            val unloaded = reduceDcPlayback(state.copy(ready = false), command)!!
            assertTrue(unloaded.trackChanged)
            assertEquals(0L, unloaded.playback.positionMs)
        }
    }

    @Test
    fun pitchCommandsValidateBoundsAndPreserveSpeedAndQueue() {
        val state = DcLocalPlaybackState(positionMs = 1234L, speed = 1.25f, pitch = 0.8f)
        val command = DcPlaybackCommand(DcPlaybackAction.PITCH)
        assertNull(reduceDcPlayback(state, command))
        for (pitch in listOf(0.49f, 2.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull(reduceDcPlayback(state, command.copy(pitch = pitch)))
            assertFalse(validDcPlayback(state.copy(pitch = pitch)))
        }
        for (pitch in listOf(0.5f, 1f, 2f)) {
            val change = reduceDcPlayback(state, command.copy(pitch = pitch))!!
            assertEquals(state.copy(pitch = pitch), change.playback)
            assertFalse(change.trackChanged)
            assertTrue(validDcPlayback(change.playback))
        }
        assertEquals(0.8f, reduceDcPlayback(state, DcPlaybackCommand(DcPlaybackAction.SPEED, speed = 1.5f))!!.playback.pitch)
    }

    @Test
    fun volumeCommandsValidateBoundsAndLeavePlaybackUntouched() {
        val state = DcLocalPlaybackState(positionMs = 1234L, speed = 1.25f, volume = 0.4f)
        val command = DcPlaybackCommand(DcPlaybackAction.VOLUME)
        assertNull(reduceDcPlayback(state, command))
        for (volume in listOf(-0.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull(reduceDcPlayback(state, command.copy(volume = volume)))
            assertFalse(validDcPlayback(state.copy(volume = volume)))
        }
        for (volume in listOf(0f, 0.6f, 1f)) {
            val change = reduceDcPlayback(state, command.copy(volume = volume))!!
            assertEquals(state.copy(volume = volume), change.playback)
            assertFalse(change.trackChanged)
            assertTrue(validDcPlayback(change.playback))
            assertEquals(
                command.copy(volume = volume),
                DcJson.decodeFromString<DcPlaybackCommand>(DcJson.encodeToString(command.copy(volume = volume))),
            )
        }
    }

    @Test
    fun shuffledReplacementPreservesCanonicalOrderAndSelectedOccurrence() {
        val duplicate = SongItem("same", "Same", null, emptyList(), null)
        val songs = listOf(duplicate, duplicate, SongItem("last", "Last", null, emptyList(), null))
        val shuffled =
            reduceDcPlayback(
                DcLocalPlaybackState(),
                DcPlaybackCommand(
                    action = DcPlaybackAction.REPLACE_QUEUE,
                    songs = songs,
                    index = 1,
                    enabled = true,
                ),
            )!!

        assertTrue(shuffled.playback.queue.isShuffled)
        assertEquals(songs, shuffled.playback.queue.originalItems)
        assertEquals(1, shuffled.playback.queue.originalIndices[shuffled.playback.queue.currentIndex])

        val unshuffled =
            reduceDcPlayback(shuffled.playback, DcPlaybackCommand(DcPlaybackAction.SHUFFLE, enabled = false))!!
        assertEquals(songs, unshuffled.playback.queue.displayedItems)
        assertEquals(1, unshuffled.playback.queue.currentIndex)
    }

    @Test
    fun historyFreeShuffledReplacementQueuesEveryOtherSong() {
        val songs = (0 until 5).map { SongItem("s$it", "Song $it", null, emptyList(), null) }
        val command =
            DcPlaybackCommand(
                action = DcPlaybackAction.REPLACE_QUEUE,
                songs = songs,
                index = 4,
                enabled = true,
                preserveHistory = false,
            )
        val queue = reduceDcPlayback(DcLocalPlaybackState(), command)!!.playback.queue

        assertEquals(0, queue.currentIndex)
        assertEquals(songs[4], queue.displayedItems.first())
        assertEquals(songs.toSet(), queue.displayedItems.toSet())
        assertEquals(command, DcJson.decodeFromString<DcPlaybackCommand>(DcJson.encodeToString(command)))
    }

    @Test
    fun replacementPreservesShuffledDuplicateOccurrenceAndPlaybackIntent() {
        val duplicate = SongItem("song", "Song", null, emptyList(), null, duration = 300)
        val replacement = duplicate.copy(id = "video", duration = 240)
        val initialQueue =
            MusicQueueSnapshot(
                displayedItems = listOf(duplicate, duplicate, duplicate),
                originalItems = listOf(duplicate, duplicate, duplicate),
                currentIndex = 1,
                isShuffled = true,
                originalIndices = listOf(2, 0, 1),
            )
        for (playing in listOf(false, true)) {
            val initial =
                DcLocalPlaybackState(
                    queue = initialQueue,
                    title = "Album",
                    repeatMode = DcRepeatMode.ONE,
                    playing = playing,
                    positionMs = 15_000L,
                    durationMs = 300_000L,
                    speed = 1.25f,
                    ready = true,
                    finite = false,
                )
            val command = DcPlaybackCommand(DcPlaybackAction.REPLACE_CURRENT, songs = listOf(replacement), positionMs = 12_000L)
            val change = reduceDcPlayback(initial, command)!!
            val expectedQueue =
                initialQueue.copy(
                    displayedItems = listOf(duplicate, replacement, duplicate),
                    originalItems = listOf(replacement, duplicate, duplicate),
                )
            assertTrue(command.isQueueIndexed())
            assertTrue(change.trackChanged)
            assertEquals(
                initial.copy(queue = expectedQueue, positionMs = 12_000L, durationMs = 240_000L, ready = false),
                change.playback,
            )
            assertTrue(validDcPlayback(change.playback))
            val unshuffled = reduceDcPlayback(change.playback, DcPlaybackCommand(DcPlaybackAction.SHUFFLE, enabled = false))!!
            assertEquals(listOf(duplicate, replacement, duplicate), unshuffled.playback.queue.displayedItems)
            assertEquals(1, unshuffled.playback.queue.currentIndex)
        }
    }

    @Test
    fun selectingASuggestionMovesAndStartsTheExactOccurrenceAtomically() {
        val duplicate = SongItem("same", "Same", null, emptyList(), null)
        val other = duplicate.copy(id = "other")
        val queue = MusicQueue().apply { setQueue(listOf(duplicate, other, duplicate), 0) }
        val state = DcLocalPlaybackState(queue = queue.snapshot())
        val selection = DcPlaybackCommand(DcPlaybackAction.SELECT, index = 2, toIndex = 1)
        val change = reduceDcPlayback(state, selection)!!
        assertTrue(change.trackChanged)
        assertTrue(change.playback.playing)
        assertEquals(1, change.playback.queue.currentIndex)
        assertEquals(
            listOf("same", "same", "other"),
            change.playback.queue.displayedItems
                .map { it.id },
        )
        assertNull(reduceDcPlayback(state, selection.copy(toIndex = 3)))
        assertNull(reduceDcPlayback(state, selection.copy(toIndex = -2)))
    }

    @Test
    fun batchOrderKeepsCurrentPlaybackAndDuplicateShuffleIdentity() {
        val duplicate = SongItem("same", "Same", null, emptyList(), null)
        val queue =
            MusicQueue().apply {
                setQueue(List(4) { duplicate }, 2)
                toggleShuffle()
            }
        val state = DcLocalPlaybackState(queue = queue.snapshot(), playing = true, positionMs = 1234, ready = true)
        val command = DcPlaybackCommand(DcPlaybackAction.REORDER, order = listOf(3, 0, 2, 1))
        assertEquals(command, DcJson.decodeFromString<DcPlaybackCommand>(DcJson.encodeToString(command)))
        queue.reorder(command.order)
        val change = requireNotNull(reduceDcPlayback(state, command))
        assertTrue(command.isQueueIndexed())
        assertFalse(change.trackChanged)
        assertEquals(state.copy(queue = queue.snapshot()), change.playback)
        assertNull(reduceDcPlayback(state, command.copy(order = listOf(0, 0, 2, 3))))
        assertNull(reduceDcPlayback(state, command.copy(order = listOf(0, 1, 2))))
        val advanced = state.copy(queue = state.queue.copy(currentIndex = 3), positionMs = 5678)
        val afterAdvance = requireNotNull(reduceDcPlayback(advanced, command))
        assertEquals(0, afterAdvance.playback.queue.currentIndex)
        assertEquals(5678L, afterAdvance.playback.positionMs)
        assertFalse(afterAdvance.trackChanged)
    }

    @Test
    fun movingAnOccurrenceCanUpdateItsMetadata() {
        val song = SongItem("song", "Song", null, emptyList(), null)
        val other = song.copy(id = "other")
        val state = DcLocalPlaybackState(queue = MusicQueueSnapshot(listOf(song, other), listOf(song, other), 0))
        val updated = song.copy(queueSourceTitle = "manual")

        val moved = reduceDcPlayback(state, DcPlaybackCommand(DcPlaybackAction.MOVE, listOf(updated), index = 0, toIndex = 1))!!

        assertEquals(
            listOf("other", "song"),
            moved.playback.queue.displayedItems
                .map { it.id },
        )
        assertEquals(
            "manual",
            moved.playback.queue.displayedItems[1]
                .queueSourceTitle,
        )
        assertNull(
            reduceDcPlayback(
                state,
                DcPlaybackCommand(DcPlaybackAction.MOVE, listOf(other), index = 0, toIndex = 1),
            ),
        )
    }

    @Test
    fun queueMutationsRejectUnrepresentableSongDurations() {
        val song = SongItem("song", "Song", null, emptyList(), null)
        val state = DcLocalPlaybackState(queue = MusicQueueSnapshot(listOf(song), listOf(song), 0))
        val invalid = song.copy(duration = Long.MAX_VALUE)
        val mixed = listOf(song, invalid)

        assertFalse(validDcPlayback(state.copy(queue = MusicQueueSnapshot(mixed, mixed, 0))))
        assertNull(reduceDcPlayback(state, DcPlaybackCommand(DcPlaybackAction.REPLACE_QUEUE, mixed, index = 0)))
        assertNull(reduceDcPlayback(state, DcPlaybackCommand(DcPlaybackAction.ADD, mixed)))
        assertNull(reduceDcPlayback(state, DcPlaybackCommand(DcPlaybackAction.PLAY_NEXT, mixed)))
        assertNull(reduceDcPlayback(state, DcPlaybackCommand(DcPlaybackAction.MOVE, listOf(invalid), index = 0, toIndex = 0)))
    }

    @Test
    fun replacementRejectsMalformedInputAndClampsKnownDuration() {
        val song = SongItem("song", "Song", null, emptyList(), null, duration = 2)
        val state = DcLocalPlaybackState(queue = MusicQueueSnapshot(listOf(song), listOf(song), 0))
        val valid = DcPlaybackCommand(DcPlaybackAction.REPLACE_CURRENT, songs = listOf(song), positionMs = 5_000L)
        val invalid =
            listOf(
                valid.copy(songs = emptyList()),
                valid.copy(songs = listOf(song, song)),
                valid.copy(songs = listOf(song.copy(id = ""))),
                valid.copy(songs = listOf(song.copy(duration = -1))),
                valid.copy(songs = listOf(song.copy(duration = Long.MAX_VALUE))),
                valid.copy(positionMs = -1L),
            )
        invalid.forEach { assertNull(reduceDcPlayback(state, it)) }
        assertNull(reduceDcPlayback(DcLocalPlaybackState(), valid))
        assertEquals(2_000L, reduceDcPlayback(state, valid)!!.playback.positionMs)
        assertEquals(5_000L, reduceDcPlayback(state, valid.copy(songs = listOf(song.copy(duration = null))))!!.playback.positionMs)
    }

    @Test
    fun undoRestartsRemovedCurrentDuplicateButPreservesUnaffectedOccurrence() {
        val duplicate = SongItem(id = "duplicate", title = "Duplicate", thumbnail = null, artists = emptyList(), album = null)
        val queue = MusicQueue().apply { setQueue(listOf(duplicate, duplicate, duplicate), 1) }
        val before = DcLocalPlaybackState(queue = queue.snapshot(), positionMs = 10_000L)
        val removedCurrent = reduceDcPlayback(before, DcPlaybackCommand(DcPlaybackAction.REMOVE, index = 1))!!
        val restoredCurrent =
            reduceDcPlayback(
                removedCurrent.playback,
                DcPlaybackCommand(DcPlaybackAction.RESTORE_QUEUE, queue = before.queue, indices = setOf(1)),
            )!!
        assertTrue(restoredCurrent.trackChanged)
        assertEquals(before.queue, restoredCurrent.playback.queue)
        assertEquals(0L, restoredCurrent.playback.positionMs)

        val removedPrevious = reduceDcPlayback(before, DcPlaybackCommand(DcPlaybackAction.REMOVE, index = 0))!!
        val restoredPrevious =
            reduceDcPlayback(
                removedPrevious.playback,
                DcPlaybackCommand(DcPlaybackAction.RESTORE_QUEUE, queue = before.queue, indices = setOf(0)),
            )!!
        assertFalse(restoredPrevious.trackChanged)
        assertEquals(before.queue, restoredPrevious.playback.queue)
        assertEquals(before.positionMs, restoredPrevious.playback.positionMs)
    }
}
