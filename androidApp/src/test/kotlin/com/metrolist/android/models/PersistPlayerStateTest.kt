package com.metrolist.android.models

import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class PersistPlayerStateTest {
    @Test
    fun joinedIoCheckpointFinishesStorageBeforeTeardownAndRejectsOlderQueuedWrites() =
        runBlocking {
            val writeStarted = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            var stored = ""
            val writer =
                PlaybackPersistenceWriter(writePosition = {}) {
                    writeStarted.countDown()
                    check(releaseWrite.await(5, TimeUnit.SECONDS))
                    stored = it
                }
            val older = writer.request(persistedState("old"))
            val finalState = persistedState("final").copy(currentPosition = 12_345)
            val request = writer.request(finalState)
            val checkpoint = launch(Dispatchers.IO) { writer.write(request) }
            try {
                assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
                assertFalse(checkpoint.isCompleted)
                assertEquals("", stored)
            } finally {
                releaseWrite.countDown()
            }
            checkpoint.join()
            writer.write(older)
            assertEquals(
                finalState,
                playbackPersistenceJson.decodeFromString(PersistPlayerState.serializer(), stored).copy(revision = null),
            )
        }

    @Test
    fun requestDoesNotWaitForSlowStorageWrite() {
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        var stored = ""
        val writer =
            PlaybackPersistenceWriter(writePosition = {}) {
                writeStarted.countDown()
                releaseWrite.await(5, TimeUnit.SECONDS)
                stored = it
            }
        val writing = thread { writer.write(writer.request(persistedState("old"))) }
        val nextRequest = AtomicReference<PlaybackPersistenceWriter.Request>()
        val requestFinished = CountDownLatch(1)
        try {
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
            val requesting =
                thread {
                    nextRequest.set(writer.request(persistedState("new")))
                    requestFinished.countDown()
                }
            assertTrue(requestFinished.await(3, TimeUnit.SECONDS))
            requesting.join()
        } finally {
            releaseWrite.countDown()
            writing.join()
        }
        writer.write(nextRequest.get())
        assertEquals(
            persistedState("new"),
            playbackPersistenceJson.decodeFromString(PersistPlayerState.serializer(), stored).copy(revision = null),
        )
    }

    @Test
    fun persistenceOmitsSongDefaultsAndRoundTrips() {
        val song =
            SongItem(
                id = "song-id",
                title = "Song",
                thumbnail = null,
                artists = listOf(ArtistTiny(id = null, name = "Artist")),
                album = null,
            )
        val state =
            PersistPlayerState(
                playWhenReady = false,
                repeatMode = 1,
                shuffleModeEnabled = false,
                volume = 1f,
                currentPosition = 12_345L,
                currentMediaItemIndex = 0,
                playbackState = 1,
                queueSongs = listOf(song),
            )

        val encoded = playbackPersistenceJson.encodeToString(PersistPlayerState.serializer(), state)

        assertFalse(encoded.contains("\"endpointParams\""))
        assertFalse(encoded.contains("\"isExplicit\""))
        assertFalse(encoded.contains("\"radioQueueChips\""))
        assertEquals(state, playbackPersistenceJson.decodeFromString(PersistPlayerState.serializer(), encoded))
    }

    @Test
    fun clearThenShutdownLeavesNoStateForRelaunchDespiteQueuedWrites() {
        val oldState = persistedState("old")
        var stored = playbackPersistenceJson.encodeToString(PersistPlayerState.serializer(), oldState)
        val writer = PlaybackPersistenceWriter(writePosition = {}) { stored = it }
        val pendingOldState = writer.request(oldState)
        val pendingClear = writer.request(null)

        writer.write(writer.request(null))
        writer.write(pendingOldState)
        writer.write(pendingClear)

        assertEquals("", stored)
    }

    @Test
    fun latestLifecycleRequestWinsPendingStateAndAllowsLaterWrites() {
        var stored = ""
        val writer = PlaybackPersistenceWriter(writePosition = {}) { stored = it }
        val pendingOldState = writer.request(persistedState("old"))
        val currentState = persistedState("current")

        writer.write(writer.request(currentState))
        writer.write(pendingOldState)

        assertEquals(
            currentState,
            playbackPersistenceJson.decodeFromString(PersistPlayerState.serializer(), stored).copy(revision = null),
        )

        val laterState = persistedState("later")
        writer.write(writer.request(laterState))
        assertEquals(
            laterState,
            playbackPersistenceJson.decodeFromString(PersistPlayerState.serializer(), stored).copy(revision = null),
        )
    }

    @Test
    fun persistencePreservesQueueRestorationMetadata() {
        val song1 = SongItem(id = "s1", title = "Song 1", thumbnail = null, artists = emptyList(), album = null)
        val song2 = SongItem(id = "s2", title = "Song 2", thumbnail = null, artists = emptyList(), album = null)
        val state =
            PersistPlayerState(
                playWhenReady = false,
                repeatMode = 2,
                shuffleModeEnabled = true,
                volume = 1f,
                currentPosition = 45_000L,
                currentMediaItemIndex = 1,
                playbackState = 1,
                queueSongs = listOf(song1, song2),
                queueTitle = "My Queue",
                radioQueue = true,
                radioQueueSeedSongId = "s1",
                radioQueueFinite = true,
                queueSessionId = 999L,
            )

        val encoded = playbackPersistenceJson.encodeToString(PersistPlayerState.serializer(), state)
        val restored = playbackPersistenceJson.decodeFromString(PersistPlayerState.serializer(), encoded)

        assertEquals(state, restored)
        assertEquals(1, restored.currentMediaItemIndex)
        assertEquals(45_000L, restored.currentPosition)
        assertEquals(listOf("s1", "s2"), restored.queueSongs.map { it.id })
    }

    @Test
    fun indexCheckpointsRetainQueueAcrossInterruptedWritesAndReadLegacy() {
        var stored = ""
        var position = ""
        var writes = 0
        val writer =
            PlaybackPersistenceWriter(writePosition = { position = it }) {
                stored = it
                writes++
            }
        val old = persistedState("first").copy(queueSongs = persistedState("first").queueSongs + persistedState("second").queueSongs)
        val legacy = playbackPersistenceJson.encodeToString(PersistPlayerState.serializer(), old)
        assertEquals(old, decodePersistPlayerState(legacy, ""))
        writer.write(writer.request(old))
        writer.write(writer.request(old.copy(currentMediaItemIndex = 1, currentPosition = 5000, repeatMode = 2)))
        assertEquals(1, writes)
        assertEquals(1, decodePersistPlayerState(stored, position).currentMediaItemIndex)
        assertEquals(5000L, decodePersistPlayerState(stored, position).currentPosition)
        val stale = position
        val interrupted = PlaybackPersistenceWriter(writePosition = { error("killed before checkpoint") }) { stored = it }
        runCatching { interrupted.write(interrupted.request(persistedState("new"))) }
        val restored = decodePersistPlayerState(stored, stale)
        assertEquals("new", restored.queueSongs.single().id)
        assertEquals(0L, restored.currentPosition)
    }

    private fun persistedState(songId: String): PersistPlayerState =
        PersistPlayerState(
            playWhenReady = false,
            repeatMode = 0,
            shuffleModeEnabled = false,
            volume = 1f,
            currentPosition = 0L,
            currentMediaItemIndex = 0,
            playbackState = 1,
            queueSongs = listOf(SongItem(id = songId, title = songId, thumbnail = null, artists = emptyList(), album = null)),
        )
}
