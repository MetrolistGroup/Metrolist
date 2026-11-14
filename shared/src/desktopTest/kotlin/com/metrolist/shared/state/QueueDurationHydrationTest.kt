package com.metrolist.shared.state

import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.playback.StoredQueueSnapshotSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueDurationHydrationTest {
    @Test
    fun incompleteQueueItemsObserveStoredDurationsWithoutLosingMetadataOrOccurrences() =
        runBlocking {
            val path = Files.createTempFile("metrolist-queue-duration", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val job = Job(coroutineContext[Job])
            try {
                val dao = database.dao()
                dao.insert(SongEntity("cached", "Stored title", duration = 183))
                dao.insert(SongEntity("later", "Later", duration = -1))
                dao.insert(SongEntity("edited", "Original", duration = 91, customTitle = "Custom"))
                val cached = SongItem("cached", "Cache title", null, emptyList(), null)
                val later = cached.copy(id = "later", duration = 0)
                val queue = MusicQueue().apply { setQueue(listOf(cached, later, cached), 1) }
                val entries = queue.state.value.entries

                SongMetadataState.bind(database, CoroutineScope(coroutineContext + job))
                withTimeout(5_000) {
                    SongMetadataState.revision.first { SongMetadataState.apply(cached).duration == 183L }
                }
                assertEquals(listOf(183L, null, 183L), entries.map { SongMetadataState.apply(it.song).duration })
                assertEquals("Cache title", SongMetadataState.apply(cached).title)
                assertEquals(200L, SongMetadataState.apply(cached.copy(duration = 200)).duration)
                assertEquals(183L, SongMetadataState.apply(cached.copy(duration = -1)).duration)
                assertNull(SongMetadataState.apply(cached.copy(id = "unknown", duration = -1)).duration)
                assertEquals("Custom", SongMetadataState.apply(cached.copy(id = "edited")).title)
                assertEquals(91L, SongMetadataState.apply(cached.copy(id = "edited")).duration)

                dao.update(SongEntity("later", "Later", duration = 245))
                withTimeout(5_000) {
                    SongMetadataState.revision.first { SongMetadataState.apply(later).duration == 245L }
                }
                assertEquals(listOf(183L, 245L, 183L), entries.map { SongMetadataState.apply(it.song).duration })
                assertEquals(entries, queue.state.value.entries)
                assertEquals(1, queue.currentIndex.value)

                val persisted = Json.encodeToString(StoredQueueSnapshotSerializer, queue.snapshot())
                val transferred = Json.encodeToString(MusicQueueSnapshot.serializer(), queue.snapshot())
                listOf(
                    Json.decodeFromString(StoredQueueSnapshotSerializer, persisted),
                    Json.decodeFromString(MusicQueueSnapshot.serializer(), transferred),
                ).forEach { snapshot ->
                    val restored = MusicQueue().apply { restore(snapshot) }
                    assertEquals(listOf(183L, 245L, 183L), restored.items.value.map { SongMetadataState.apply(it).duration })
                    assertEquals(1, restored.currentIndex.value)
                }
                dao.update(SongEntity("later", "Later", duration = 0))
                withTimeout(5_000) {
                    SongMetadataState.revision.first { SongMetadataState.apply(later).duration == null }
                }
                dao.delete(SongEntity("cached", "Stored title"))
                withTimeout(5_000) {
                    SongMetadataState.revision.first { SongMetadataState.apply(cached).duration == null }
                }
                job.cancelAndJoin()
                assertNull(SongMetadataState.apply(cached).duration)
                assertEquals("Embedded", SongMetadataState.apply(cached.copy(customTitle = "Embedded")).title)
            } finally {
                job.cancelAndJoin()
                database.close()
                Files.deleteIfExists(path)
            }
        }
}
