package com.metrolist.android.playback

import com.metrolist.android.models.PersistPlayerState
import com.metrolist.android.models.PlaybackPersistenceWriter
import com.metrolist.android.models.decodePersistPlayerState
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCheckpointRetryTest {
    @Test
    fun retriesAPausedPositionWithoutAnotherQueueUpdate() =
        runTest {
            val store = CheckpointStore(failPositionWrites = 1)
            val requests = Channel<PlaybackPersistenceWriter.Request>(Channel.CONFLATED)
            backgroundScope.launch { writePendingPlaybackCheckpoints(requests, store.writer::write, store.failures::add) }
            requests.send(store.writer.request(pausedAt(17_000L)))
            runCurrent()
            assertEquals(1, store.positionWrites)
            assertEquals(0L, store.storedPosition())

            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(17_000L, store.storedPosition())
            assertEquals(1, store.failures.size)
            assertEquals(0, store.queueWrites)
            assertEquals(2, store.positionWrites)
        }

    @Test
    fun newerRequestDoesNotWaitForTheFailedCheckpointsRetry() =
        runTest {
            val store = CheckpointStore(failPositionWrites = 1)
            val requests = Channel<PlaybackPersistenceWriter.Request>(Channel.CONFLATED)
            backgroundScope.launch { writePendingPlaybackCheckpoints(requests, store.writer::write, store.failures::add) }
            requests.send(store.writer.request(pausedAt(17_000L)))
            runCurrent()
            assertEquals(1, store.failures.size)

            requests.send(store.writer.request(pausedAt(23_000L)))
            runCurrent()
            assertEquals(23_000L, store.storedPosition())
            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(23_000L, store.storedPosition())
            assertEquals(2, store.positionWrites)
        }

    @Test
    fun delayedRetryCannotOverwriteFinalCheckpoint() =
        runTest {
            val store = CheckpointStore(failPositionWrites = 1)
            val requests = Channel<PlaybackPersistenceWriter.Request>(Channel.CONFLATED)
            backgroundScope.launch { writePendingPlaybackCheckpoints(requests, store.writer::write, store.failures::add) }
            requests.send(store.writer.request(pausedAt(17_000L)))
            runCurrent()
            assertEquals(1, store.failures.size)

            store.writer.write(store.writer.request(pausedAt(23_000L)))
            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(23_000L, store.storedPosition())
            assertEquals(2, store.positionWrites)
        }

    @Test
    fun stoppingTheWriterCancelsItsPendingRetry() =
        runTest {
            val store = CheckpointStore(failPositionWrites = 1)
            val requests = Channel<PlaybackPersistenceWriter.Request>(Channel.CONFLATED)
            val worker = backgroundScope.launch { writePendingPlaybackCheckpoints(requests, store.writer::write, store.failures::add) }
            requests.send(store.writer.request(pausedAt(17_000L)))
            runCurrent()
            assertEquals(1, store.failures.size)

            worker.cancelAndJoin()
            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(1, store.positionWrites)
            assertEquals(0L, store.storedPosition())
        }

    @Test
    fun retriesAtMostOnceWhenStorageStillFails() =
        runTest {
            val store = CheckpointStore(failPositionWrites = 2)
            val requests = Channel<PlaybackPersistenceWriter.Request>(Channel.CONFLATED)
            backgroundScope.launch { writePendingPlaybackCheckpoints(requests, store.writer::write, store.failures::add) }
            requests.send(store.writer.request(pausedAt(17_000L)))
            runCurrent()
            advanceTimeBy(10_000L)
            runCurrent()

            assertEquals(2, store.failures.size)
            assertEquals(2, store.positionWrites)
            requests.send(store.writer.request(pausedAt(23_000L)))
            runCurrent()
            assertEquals(23_000L, store.storedPosition())
        }

    private class CheckpointStore(
        failPositionWrites: Int,
    ) {
        private var queue = ""
        private var position = ""
        private var remainingPositionFailures = 0
        var queueWrites = 0
            private set
        var positionWrites = 0
            private set
        val failures = mutableListOf<Exception>()
        val writer =
            PlaybackPersistenceWriter(
                writePosition = {
                    positionWrites++
                    if (remainingPositionFailures > 0) {
                        remainingPositionFailures--
                        throw IOException("temporary file write failure")
                    }
                    position = it
                },
                writeStoredValue = {
                    queueWrites++
                    queue = it
                },
            )

        init {
            writer.write(writer.request(pausedAt(0L)))
            remainingPositionFailures = failPositionWrites
            positionWrites = 0
            queueWrites = 0
        }

        fun storedPosition(): Long = decodePersistPlayerState(queue, position).currentPosition
    }

    private companion object {
        fun pausedAt(position: Long) =
            PersistPlayerState(false, 0, false, 1f, position, 0, 3, queueSongs = listOf(SongItem("song", "Song", null, emptyList(), null)))
    }
}
