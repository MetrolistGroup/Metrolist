package com.metrolist.shared.playback

import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QueueCheckpointPersistenceTest {
    private val song = SongItem("one", "A distinct title", null, emptyList(), null)

    @Test
    fun duplicateOccurrencesAndShuffleSurviveCompactAndLegacyFormats() {
        val queue =
            MusicQueue().apply {
                setQueue(listOf(song, song, song.copy(id = "two")), 1)
                toggleShuffle()
            }
        val snapshot = queue.snapshot()
        val encoded = Json.encodeToString(StoredQueueSnapshotSerializer, snapshot)
        assertFalse(encoded.contains("displayedItems"))
        assertEquals(2, Regex("A distinct title").findAll(encoded).count())
        val decoded = Json.decodeFromString(StoredQueueSnapshotSerializer, encoded)
        assertEquals(snapshot, decoded)
        val legacy = Json.encodeToString(MusicQueueSnapshot.serializer(), snapshot)
        assertEquals(snapshot, Json.decodeFromString(StoredQueueSnapshotSerializer, legacy))
        val restored =
            MusicQueue().apply {
                restore(decoded)
                toggleShuffle()
            }
        assertEquals(1, restored.currentIndex.value)
    }

    @Test
    fun skipsReuseAllQueueSizedCollectionsAndPublishedEntries() {
        val queue = MusicQueue().apply { setQueue(List(10_000) { song }) }
        val snapshot = queue.snapshot()
        val entries = queue.state.value.entries
        repeat(100) { queue.skipTo(it) }
        assertSame(snapshot.originalItems, queue.snapshot().originalItems)
        assertSame(snapshot.displayedItems, queue.snapshot().displayedItems)
        assertSame(snapshot.originalIndices, queue.snapshot().originalIndices)
        assertSame(entries, queue.state.value.entries)
        queue.updateItems(mapOf(99 to song.copy(title = "updated")))
        assertSame(entries[0], queue.state.value.entries[0])
        assertEquals("updated", queue.snapshot().displayedItems[99].title)
    }

    @Test
    fun checkpointsDoNotRewriteMetadataAndInterruptedWritesKeepACompleteQueue() {
        var stored = ""
        var position = ""
        var count = 0
        val writer =
            QueueCheckpointPersistence({
                stored = it
                count++
            }, { position = it })
        val queue = MusicQueue().apply { setQueue(listOf(song, song), 0) }
        val initial = QueuePlaybackState(queue.snapshot(), positionMs = 123)
        writer.persist(initial)
        queue.skipTo(1)
        writer.persist(initial.copy(queue = queue.snapshot(), positionMs = 5000, repeatMode = RepeatMode.ALL))
        assertEquals(1, count)
        val checkpoint = Json.decodeFromString(QueuePositionCheckpoint.serializer(), position)
        assertEquals(1, checkpoint.currentIndex)
        assertEquals(5000L, checkpoint.positionMs)
        val interrupted = QueueCheckpointPersistence({ stored = it }, { error("killed") })
        runCatching { interrupted.persist(initial.copy(queueTitle = "new queue")) }
        val restored = Json.decodeFromString(QueuePlaybackState.serializer(), stored)
        assertEquals(2, restored.queue.displayedItems.size)
        assertTrue(restored.revision != checkpoint.revision)
        assertEquals(123L, restored.positionMs)
    }

    @Test
    fun restoringLocalQueueAfterPendingRoomQueueRejectsEarlierPositionCheckpoints() {
        var stored = ""
        var position = ""
        val writer = QueueCheckpointPersistence({ stored = it }, { position = it })
        val queue = MusicQueue().apply { setQueue(listOf(song), 0) }
        val local = QueuePlaybackState(queue.snapshot(), positionMs = 12_000, queueTitle = "Local")
        writer.persist(local)
        val original = decodeQueuePlaybackState(stored, position)
        val originalPosition = position
        queue.setQueue(listOf(song.copy(id = "room")), 0)
        writer.persist(local.copy(queue = queue.snapshot(), positionMs = 45_000))
        val roomPosition = position

        writer.persist(local.copy(positionMs = 21_000))
        val restored = decodeQueuePlaybackState(stored, position)
        assertEquals(local.copy(positionMs = 21_000, revision = restored.revision), restored)
        assertTrue(original.revision != restored.revision)
        assertEquals(restored, decodeQueuePlaybackState(stored, originalPosition))
        assertEquals(restored, decodeQueuePlaybackState(stored, roomPosition))
    }

    @Test
    fun oldSnapshotsAndMismatchedOrMalformedCheckpointsUseTheirOwnFallback() {
        val snapshot = MusicQueue().apply { setQueue(listOf(song), 0) }.snapshot()
        val legacy = """{"queue":${Json.encodeToString(MusicQueueSnapshot.serializer(), snapshot)},"positionMs":4321}"""
        val old = decodeQueuePlaybackState(legacy, "not json")
        assertEquals(4321L, old.positionMs)
        assertEquals(snapshot, old.queue)
        val full = Json.encodeToString(QueuePlaybackState.serializer(), old.copy(revision = "current"))
        val stale = Json.encodeToString(QueuePositionCheckpoint.serializer(), QueuePositionCheckpoint("previous", 0, 0, RepeatMode.ONE))
        assertEquals(4321L, decodeQueuePlaybackState(full, stale).positionMs)
        val invalidIndex =
            Json.encodeToString(
                QueuePositionCheckpoint.serializer(),
                QueuePositionCheckpoint("current", 0, 5, RepeatMode.ONE),
            )
        assertEquals(4321L, decodeQueuePlaybackState(full, invalidIndex).positionMs)
        val stored = full
        val failedWriter = QueueCheckpointPersistence({ error("before snapshot commit") }, { error("checkpoint must not be reached") })
        runCatching { failedWriter.persist(old.copy(queueTitle = "new")) }
        assertEquals(snapshot, decodeQueuePlaybackState(stored, stale).queue)
    }

    @Test
    fun clearsStaleStateOnceAndRetriesAnInterruptedClear() {
        val writes = mutableListOf<String>()
        var fail = true
        val writer =
            QueueCheckpointPersistence(
                { writes += "queue:$it" },
                {
                    if (fail) {
                        fail = false
                        error("Storage unavailable")
                    }
                    writes += "position:$it"
                },
            )
        runCatching { writer.persist(null) }
        writer.persist(null)
        repeat(10) { writer.persist(null) }
        assertEquals(listOf("queue:", "queue:", "position:"), writes)
        val queue = MusicQueue().apply { setQueue(listOf(song)) }.snapshot()
        writer.persist(QueuePlaybackState(queue, 1234L))
        writes.clear()
        writer.persist(null)
        writer.persist(null)
        assertEquals(listOf("queue:", "position:"), writes)
    }
}
