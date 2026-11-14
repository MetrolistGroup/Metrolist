package com.metrolist.android.playback

import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueuePersistenceTest {
    @Test
    fun coalescesSnapshotsAndFlushRestoresLatestStateWithoutAnOlderWrite() =
        runTest {
            val writes = mutableListOf<String>()
            val writer = DownloadQueuePersistence(backgroundScope) { writes += Json.encodeToString(it) }
            val request = DownloadRequest(SongItem("a", "A", null, emptyList(), null), enqueuedAt = 1L)
            writer.submit(listOf(request))
            runCurrent()
            writer.submit(listOf(request.copy(state = DownloadQueueState.RUNNING)))
            advanceTimeBy(250)
            runCurrent()
            assertEquals(1, writes.size)
            assertEquals(DownloadQueueState.RUNNING, Json.decodeFromString<List<DownloadRequest>>(writes.last()).single().state)
            writer.submit(listOf(request.copy(state = DownloadQueueState.PAUSED)))
            writer.flush()
            writer.submit(listOf(request.copy(state = DownloadQueueState.CANCELLED)))
            writer.flush()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(3, writes.size)
            assertEquals(DownloadQueueState.CANCELLED, Json.decodeFromString<List<DownloadRequest>>(writes.last()).single().state)
            writer.submit(emptyList())
            writer.flush()
            assertTrue(Json.decodeFromString<List<DownloadRequest>>(writes.last()).isEmpty())
        }
}
