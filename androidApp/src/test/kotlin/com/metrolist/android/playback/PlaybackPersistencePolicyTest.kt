package com.metrolist.android.playback

import com.metrolist.android.models.PersistPlayerState
import com.metrolist.android.models.PlaybackPersistenceWriter
import com.metrolist.android.models.playbackPersistenceJson
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPersistencePolicyTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun destructionWaitIsBoundedWithoutCancellingTheFinalWriter() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var persisted = false
            val checkpoint =
                backgroundScope.launch {
                    release.await()
                    persisted = true
                }
            awaitFinalPlaybackCheckpoint(checkpoint)
            assertEquals(3_000L, testScheduler.currentTime)
            assertTrue(checkpoint.isActive)
            assertFalse(persisted)
            release.complete(Unit)
            checkpoint.join()
            assertTrue(persisted)
            awaitFinalPlaybackCheckpoint(checkpoint)
            awaitFinalPlaybackCheckpoint(null)
            assertEquals(3_000L, testScheduler.currentTime)
        }

    @Test
    fun restorationProtectsSavedQueueBeforeStartupAndUntilItFinishes() =
        runBlocking {
            val lifecycle = PlaybackPersistenceLifecycle()
            val state = pausedQueueState()
            val saved = playbackPersistenceJson.encodeToString(PersistPlayerState.serializer(), state)
            var stored = saved
            val writer = PlaybackPersistenceWriter(writePosition = {}) { stored = it }

            fun checkpoint() {
                if (lifecycle.canSave(persistentQueue = true)) writer.write(writer.request(null))
            }
            val started = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            checkpoint()
            assertEquals(saved, stored)
            val restoration =
                launch {
                    lifecycle.restore {
                        started.complete(Unit)
                        resume.await()
                    }
                }
            started.await()
            checkpoint()
            assertEquals(saved, stored)
            resume.complete(Unit)
            restoration.join()
            assertTrue(lifecycle.canSave(persistentQueue = true))
            val restored = state.copy(currentPosition = 15_000)
            writer.write(writer.request(restored))
            assertEquals(restored, playbackPersistenceJson.decodeFromString(PersistPlayerState.serializer(), stored).copy(revision = null))
        }

    @Test
    fun cancelledRestorationKeepsLifecycleCheckpointsFromClearingSavedQueue() =
        runBlocking {
            val lifecycle = PlaybackPersistenceLifecycle()
            val state = pausedQueueState()
            val saved = playbackPersistenceJson.encodeToString(PersistPlayerState.serializer(), state)
            var stored = saved
            val writer = PlaybackPersistenceWriter(writePosition = {}) { stored = it }

            fun checkpoint() {
                if (lifecycle.canSave(persistentQueue = true)) writer.write(writer.request(null))
            }
            val started = CompletableDeferred<Unit>()
            val restoration =
                launch {
                    lifecycle.restore {
                        started.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                }
            started.await()
            restoration.cancelAndJoin()
            checkpoint()
            assertEquals(saved, stored)
            assertFalse(lifecycle.canSave(persistentQueue = true))
            if (lifecycle.canSave(persistentQueue = false)) writer.write(writer.request(null))
            assertEquals("", stored)
        }

    @Test
    fun pausedSeeksArePersistedWithoutWaitingForPlaybackOrShutdown() {
        val state = pausedQueueState()
        assertFalse(shouldPersistPlaybackState(state, state))
        assertTrue(shouldPersistPlaybackState(state, state.copy(currentPosition = 10_001)))
        assertTrue(shouldPersistPlaybackState(state, state.copy(currentPosition = 9_999)))
    }

    @Test
    fun checkpointsSkipStationaryPlaybackButKeepPositionProgressAndQueueChanges() {
        val state = PersistPlayerState(true, 0, false, 1f, 10_000, 0, 3)
        assertTrue(shouldPersistPlaybackState(null, state))
        assertTrue(shouldPersistPlaybackState(state, null))
        assertFalse(shouldPersistPlaybackState(null, null))
        assertFalse(shouldPersistPlaybackState(state, state))
        assertFalse(shouldPersistPlaybackState(state, state.copy(currentPosition = 14_999)))
        assertTrue(shouldPersistPlaybackState(state, state.copy(currentPosition = 15_000)))
        assertTrue(shouldPersistPlaybackState(state, state.copy(currentPosition = 5_000)))
        assertTrue(shouldPersistPlaybackState(state, state.copy(playWhenReady = false)))
        assertTrue(shouldPersistPlaybackState(state, state.copy(currentMediaItemIndex = 1)))
        assertTrue(shouldPersistPlaybackState(state, state.copy(repeatMode = 1)))
        assertTrue(shouldPersistPlaybackState(state, state.copy(queueTitle = "queue")))
    }

    private fun pausedQueueState() =
        PersistPlayerState(
            playWhenReady = false,
            repeatMode = 0,
            shuffleModeEnabled = false,
            volume = 1f,
            currentPosition = 10_000,
            currentMediaItemIndex = 1,
            playbackState = 1,
            queueSongs =
                listOf(
                    SongItem(id = "first", title = "First", thumbnail = null, artists = emptyList(), album = null),
                    SongItem(id = "second", title = "Second", thumbnail = null, artists = emptyList(), album = null),
                ),
        )
}
