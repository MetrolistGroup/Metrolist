package com.metrolist.android.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.QueuePlaybackRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class QueuePlaybackRequestFlowTest {
    @Test
    fun enqueueAutoplaysFirstSelectionAndDoesNotInterruptExistingSelection() {
        val queue = MusicQueue()
        val emitted = mutableListOf<QueuePlaybackRequest>()
        val song = request(1).song
        queue.addToQueue(listOf(song))
        prepareEnqueuedCurrent(queue) { selected, resume -> emitted += QueuePlaybackRequest(1, selected, 0L, resume) }
        assertEquals(song, emitted.single().song)
        assertTrue(emitted.single().resumePlayback)
        queue.addToQueue(listOf(request(2).song))
        prepareEnqueuedCurrent(queue) { selected, resume -> emitted += QueuePlaybackRequest(2, selected, 0L, resume) }
        assertEquals(1, emitted.size)
    }

    @Test
    fun addingToEmptyOrUnselectedQueueSelectsFirstTrackOnlyOnce() {
        for (playNext in listOf(false, true)) {
            val queue = MusicQueue()
            assertFalse(queue.selectFirstIfUnselected())
            val first = request(1).song
            if (playNext) queue.playNext(first) else queue.addToQueue(first)
            assertTrue(queue.selectFirstIfUnselected())
            assertSame(first, queue.currentSong())
            assertTrue(shouldRequestQueueCurrent(null, first, null))
            if (playNext) queue.playNext(request(2).song) else queue.addToQueue(request(2).song)
            assertFalse(queue.selectFirstIfUnselected())
            assertSame(first, queue.currentSong())
            queue.setQueue(listOf(first, request(2).song), startIndex = -1)
            queue.addToQueue(request(3).song)
            assertTrue(queue.selectFirstIfUnselected())
            assertSame(first, queue.currentSong())
        }
    }

    @Test
    fun playbackTransitionWaitsForQueueAndExactStreamRecoveryCompletion() {
        val transitions = PlaybackTransitionState()

        transitions.beginRecovery(1)
        assertTrue(transitions.recovering)
        transitions.beginQueue()
        transitions.finishRecovery(1)
        assertFalse(transitions.recovering)
        assertTrue(transitions.pending.value)

        transitions.beginRecovery(2)
        transitions.finishQueue()
        transitions.finishRecovery(1)
        assertTrue(transitions.pending.value)

        transitions.finishRecovery(2)
        assertFalse(transitions.recovering)
        assertFalse(transitions.pending.value)
    }

    @Test
    fun cancelledSyncLoadRetriesTheSelectedOccurrenceEvenWithOldAudio() {
        val song = request(1).song
        val source = MusicQueue().apply { setQueue(listOf(song, song), startIndex = 1) }
        val queue = MusicQueue().apply { restore(source.snapshot()) }
        val selected = queue.currentSong()
        val before = queue.snapshot()

        assertSame(selected, queuePlaybackSongToRetry(selected, null, hasLoadedTrack = false, trackChangePending = true))
        assertSame(selected, queuePlaybackSongToRetry(selected, request(2).song, hasLoadedTrack = true, trackChangePending = true))
        assertSame(
            selected,
            queuePlaybackSongToRetry(selected, queue.items.value.first(), hasLoadedTrack = true, trackChangePending = true),
        )
        assertEquals(before, queue.snapshot())
        assertEquals(1, queue.currentIndex.value)
    }

    @Test
    fun loadedQueueEditsResumeButMissingOrDifferentAudioRetries() {
        val song = request(1).song
        val queue = MusicQueue().apply { setQueue(listOf(song, request(2).song)) }
        queue.move(0, 1)
        val selected = queue.currentSong()

        assertNull(queuePlaybackSongToRetry(selected, song, hasLoadedTrack = true, trackChangePending = false))
        assertSame(selected, queuePlaybackSongToRetry(selected, song, hasLoadedTrack = false, trackChangePending = false))
        assertSame(selected, queuePlaybackSongToRetry(selected, request(2).song, hasLoadedTrack = true, trackChangePending = false))
        assertSame(song, queuePlaybackSongToRetry(null, song, hasLoadedTrack = false, trackChangePending = false))
        assertNull(queuePlaybackSongToRetry(null, null, hasLoadedTrack = false, trackChangePending = true))
    }

    @Test
    fun rapidRequestsKeepTheLatestWhileCollectorIsBusy() =
        runBlocking {
            val requests = queuePlaybackRequestFlow()
            val firstReceived = CompletableDeferred<Unit>()
            val releaseCollector = CompletableDeferred<Unit>()
            val received = mutableListOf<QueuePlaybackRequest>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    requests
                        .onEach {
                            if (received.isEmpty()) {
                                firstReceived.complete(Unit)
                                releaseCollector.await()
                            }
                        }.take(2)
                        .toList(received)
                }

            assertTrue(requests.tryEmit(request(1)))
            firstReceived.await()
            assertTrue(requests.tryEmit(request(2)))
            assertTrue(requests.tryEmit(request(3)))
            assertTrue(requests.tryEmit(request(4)))
            assertEquals(4L, requests.replayCache.single().requestId)
            releaseCollector.complete(Unit)
            withTimeout(1_000.milliseconds) { collector.join() }

            assertEquals(listOf(1L, 3L), received.map(QueuePlaybackRequest::requestId))
        }

    @Test
    fun queueItemSwitchCarriesRequestedPosition() {
        assertEquals(42_000L, request(1, positionMs = 42_000L).positionMs)
    }

    @Test
    fun connectedUpdateDuringExplicitDisconnectDoesNotConsumeItsGuard() {
        val suppression = CastDisconnectSuppression()

        suppression.beginExplicitDisconnect()
        assertFalse(suppression.onConnectionState(isConnected = true))
        suppression.finishExplicitDisconnect(disconnected = true)

        assertTrue(suppression.onConnectionState(isConnected = false))
    }

    @Test
    fun switchingCastReceiversSuppressesTheIntermediateDisconnect() {
        val suppression = CastDisconnectSuppression()

        suppression.beginReplacement()

        assertTrue(suppression.onConnectionState(isConnected = false))
        assertFalse(suppression.onConnectionState(isConnected = true))
    }

    @Test
    fun aNewCastConnectionClearsAnUnobservedOldDisconnectGuard() {
        val suppression = CastDisconnectSuppression()

        suppression.beginExplicitDisconnect()
        suppression.finishExplicitDisconnect(disconnected = true)

        assertFalse(suppression.onConnectionState(isConnected = true))
        assertFalse(suppression.onConnectionState(isConnected = false))
    }

    @Test
    fun failedCastReplacementDisconnectsAndRecoversLocally() =
        runBlocking {
            val suppression = CastDisconnectSuppression()
            var connected = true
            var disconnectCalls = 0
            val failures = mutableListOf<Exception>()

            val result =
                routeCastQueueReplacement(
                    suppression = suppression,
                    isCurrentRequest = { true },
                    isConnected = { connected },
                    replace = { throw IllegalStateException("receiver failed") },
                    disconnect = {
                        disconnectCalls++
                        connected = false
                    },
                    onFailure = failures::add,
                )

            assertEquals(CastQueueReplacementResult.LOCAL, result)
            assertEquals(1, disconnectCalls)
            assertEquals(1, failures.size)
            assertTrue(suppression.onConnectionState(isConnected = false))
            assertFalse(suppression.onConnectionState(isConnected = false))
        }

    @Test
    fun replacementFailureThatAlreadyDisconnectedStillRecoversLocally() =
        runBlocking {
            val suppression = CastDisconnectSuppression()
            var connected = true
            var disconnectCalls = 0
            var observerSuppressed = false

            val result =
                routeCastQueueReplacement(
                    suppression = suppression,
                    isCurrentRequest = { true },
                    isConnected = { connected },
                    replace = {
                        connected = false
                        observerSuppressed = suppression.onConnectionState(isConnected = false)
                        false
                    },
                    disconnect = { disconnectCalls++ },
                )

            assertEquals(CastQueueReplacementResult.LOCAL, result)
            assertEquals(0, disconnectCalls)
            assertTrue(observerSuppressed)
            assertFalse(suppression.onConnectionState(isConnected = false))
        }

    @Test
    fun staleCastReplacementDoesNotRecoverAnObsoleteQueueRequest() =
        runBlocking {
            var current = true

            val result =
                routeCastQueueReplacement(
                    suppression = CastDisconnectSuppression(),
                    isCurrentRequest = { current },
                    isConnected = { true },
                    replace = {
                        current = false
                        true
                    },
                    disconnect = {},
                )

            assertEquals(CastQueueReplacementResult.STALE, result)
        }

    @Test
    fun cancelledCastReplacementClearsItsDisconnectGuard() =
        runBlocking {
            val suppression = CastDisconnectSuppression()

            try {
                routeCastQueueReplacement(
                    suppression = suppression,
                    isCurrentRequest = { true },
                    isConnected = { true },
                    replace = { throw CancellationException("superseded request") },
                    disconnect = {},
                )
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                assertFalse(suppression.onConnectionState(isConnected = false))
            }
        }

    @Test
    fun stoppedPlayerRequestsRestoredCurrentSongDespiteCachedRequest() {
        val restored = request(1).song

        assertTrue(shouldRequestQueueCurrent(currentSong = null, queueSong = restored, pendingSong = restored))
        assertFalse(shouldRequestQueueCurrent(currentSong = restored, queueSong = restored, pendingSong = restored))
    }

    private fun request(
        id: Long,
        positionMs: Long = 0L,
        resumePlayback: Boolean = true,
    ) = QueuePlaybackRequest(
        requestId = id,
        song = SongItem("song-$id", "Song $id", null, emptyList(), null),
        positionMs = positionMs,
        resumePlayback = resumePlayback,
    )
}
