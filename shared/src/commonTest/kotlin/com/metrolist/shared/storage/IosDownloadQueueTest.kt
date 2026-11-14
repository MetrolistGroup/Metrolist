package com.metrolist.shared.storage

import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosDownloadQueueTest {
    @Test
    fun unavailableEpisodesCannotBeEnqueuedAndStoredRequestsStayIntact() {
        val queue = IosDownloadQueue("")
        val unavailable = song("unavailable").copy(isUnavailable = true, musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE")
        val available = song("available")
        assertEquals(1, queue.enqueue(listOf(unavailable, available), emptySet(), emptyMap(), false, 1L))
        assertEquals(listOf(available), queue.values().map { it.song })

        val stored = IosDownloadQueueRequest(unavailable, AudioDownloadState.PAUSED, 2L)
        queue.replace(listOf(stored))
        val restored = IosDownloadQueue(queue.encode())
        assertEquals(listOf(stored), restored.values())
        assertEquals(0, restored.enqueue(listOf(unavailable), emptySet(), restored.snapshot(), false, 3L))
        assertEquals(listOf(stored), restored.values())
    }

    @Test
    fun bulkMergePreservesChangesMadeDuringAvailabilityCheck() {
        val queue = IosDownloadQueue("")
        val songs = (0 until 5000).map { song(it.toString()) }
        assertEquals(5000, queue.enqueue(songs, emptySet(), emptyMap(), false, 1L))
        queue.replace(queue.values().map { it.copy(state = AudioDownloadState.FAILED) })
        val observed = queue.snapshot()
        queue.update("0") { it.copy(state = AudioDownloadState.CANCELLED) }
        queue.update("1") { it.copy(state = AudioDownloadState.PAUSED) }
        queue.update("2") { it.copy(state = AudioDownloadState.RUNNING) }
        assertEquals(4996, queue.enqueue(songs, setOf("3"), observed, false, 2L))
        assertEquals(AudioDownloadState.CANCELLED, queue.snapshot()["0"]?.state)
        assertEquals(AudioDownloadState.PAUSED, queue.snapshot()["1"]?.state)
        assertEquals(AudioDownloadState.RUNNING, queue.snapshot()["2"]?.state)
        assertEquals(AudioDownloadState.FAILED, queue.snapshot()["3"]?.state)
    }

    @Test
    fun persistencePrunesCompletedAndRecoversPendingWorkAndSuppressionMarkers() {
        val queue = IosDownloadQueue("")
        queue.replace(AudioDownloadState.entries.map { IosDownloadQueueRequest(song(it.name), it, 1L) })
        val restored = IosDownloadQueue(queue.encode())
        assertFalse("COMPLETED" in restored.snapshot())
        assertEquals(AudioDownloadState.QUEUED, restored.snapshot()["RUNNING"]?.state)
        assertEquals(AudioDownloadState.PAUSED, restored.snapshot()["PAUSED"]?.state)
        assertEquals(AudioDownloadState.CANCELLED, restored.snapshot()["CANCELLED"]?.state)
        val observed = restored.snapshot()
        assertEquals(1, restored.enqueue(restored.values().map { it.song }, emptySet(), observed, true, 2L))
        assertEquals(AudioDownloadState.PAUSED, restored.snapshot()["PAUSED"]?.state)
        assertEquals(AudioDownloadState.CANCELLED, restored.snapshot()["CANCELLED"]?.state)
        restored.update("RUNNING") { it.copy(state = AudioDownloadState.COMPLETED) }
        assertFalse("RUNNING" in IosDownloadQueue(restored.encode()).snapshot())
    }

    @Test
    fun completedHistoryIsRemovedOnLoadAndExplicitRetryCanReplaceCancelledRequest() {
        val queue =
            IosDownloadQueue(
                """[{"song":{"id":"done","title":"done","artists":[],"thumbnail":null,"album":null},"state":"COMPLETED","enqueuedAt":1}]""",
            )
        assertTrue(queue.values().isEmpty())
        queue.replace(listOf(IosDownloadQueueRequest(song("retry"), AudioDownloadState.CANCELLED, 1L)))
        assertEquals(1, queue.enqueue(listOf(song("retry")), emptySet(), queue.snapshot(), false, 2L))
        assertEquals(AudioDownloadState.QUEUED, IosDownloadQueue(queue.encode()).snapshot()["retry"]?.state)
    }

    @Test
    fun removingCompletedDownloadRetainsTrackedPlaylistSuppression() {
        val queue = IosDownloadQueue("")
        val song = song("removed")
        queue.enqueue(listOf(song), emptySet(), emptyMap(), false, 1L)
        queue.update(song.id) { it.copy(state = AudioDownloadState.COMPLETED) }
        val observedBeforeRemoval = queue.snapshot()
        queue.cancelRemovedDownload(song, 2L)
        assertEquals(0, queue.enqueue(listOf(song), emptySet(), observedBeforeRemoval, false, 3L))
        val restored = IosDownloadQueue(queue.encode())
        assertEquals(0, restored.enqueue(listOf(song), emptySet(), restored.snapshot(), true, 4L))
        assertEquals(1, restored.enqueue(listOf(song), emptySet(), restored.snapshot(), false, 5L))
    }

    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)
}
