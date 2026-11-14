package com.metrolist.android.playback

import com.metrolist.shared.listentogether.ListenTogetherConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackServiceLifetimeTest {
    @Test
    fun leavingOrFailingAnIdleRoomReleasesDemandButPendingRequestsAndActiveRoomsKeepIt() {
        for (connection in ListenTogetherConnectionState.entries) {
            assertEquals(
                connection == ListenTogetherConnectionState.CONNECTING || connection == ListenTogetherConnectionState.RECONNECTING,
                roomNeedsPlaybackService(false, false, connection),
            )
            assertTrue(roomNeedsPlaybackService(true, false, connection))
            assertTrue(roomNeedsPlaybackService(false, true, connection))
        }
    }

    @Test
    fun removedTaskStopsPausedPlaybackAndDoesNotPoll() =
        runTest {
            val removed = MutableStateFlow(false)
            var stops = 0
            backgroundScope.launch {
                observePlaybackServiceLifetime(removed, MutableStateFlow(false), MutableStateFlow(false)) { stops++ }
            }
            runCurrent()
            assertEquals(0, stops)
            removed.value = true
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, stops)
            advanceTimeBy(3_600_000)
            runCurrent()
            assertEquals(1, stops)
        }

    @Test
    fun playbackPreparationRemoteSessionsAndFocusRecoveryKeepTheServiceUntilTheirDemandEnds() =
        runTest {
            val required = MutableStateFlow(true)
            var stops = 0
            backgroundScope.launch {
                observePlaybackServiceLifetime(MutableStateFlow(true), required, MutableStateFlow(false)) { stops++ }
            }
            advanceTimeBy(3_600_000)
            runCurrent()
            assertEquals(0, stops)
            required.value = false
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, stops)
        }

    @Test
    fun returningToTheTaskOrResumingCancelsShutdownWaitingForTheCheckpoint() =
        runTest {
            val removed = MutableStateFlow(true)
            val required = MutableStateFlow(false)
            val checkpoint = CompletableDeferred<Unit>()
            var stops = 0
            backgroundScope.launch {
                observePlaybackServiceLifetime(removed, required, MutableStateFlow(false)) {
                    checkpoint.await()
                    stops++
                }
            }
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            removed.value = false
            runCurrent()
            checkpoint.complete(Unit)
            runCurrent()
            assertEquals(0, stops)
            required.value = true
            removed.value = true
            runCurrent()
            assertEquals(0, stops)
            required.value = false
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, stops)
        }

    @Test
    fun stopOnTaskClearStillStopsActivePlayback() =
        runTest {
            var stops = 0
            backgroundScope.launch {
                observePlaybackServiceLifetime(MutableStateFlow(true), MutableStateFlow(true), MutableStateFlow(true)) { stops++ }
            }
            runCurrent()
            assertEquals(1, stops)
        }
}
