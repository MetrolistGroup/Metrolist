package com.metrolist.shared.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackNetworkRecoveryTest {
    @Test
    fun pausedFailedRecoveryWaitsForAConnectionChangeOrResumeWithoutPolling() =
        runTest {
            val changes = MutableStateFlow(0)
            var playing = false
            var attempts = 0
            var positionReads = 0
            val recovery =
                async {
                    PlaybackNetworkRecovery.recover(
                        isCurrent = { true },
                        connected = { true },
                        wantsPlayback = { playing },
                        positionMs = {
                            positionReads++
                            0L
                        },
                        onReconnecting = {},
                        stateChanges = changes.map { Unit },
                        timeSource = testScheduler.timeSource,
                    ) {
                        attempts++
                        false
                    }
                }
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, attempts)
            val pausedReads = positionReads
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(1, attempts)
            assertEquals(pausedReads, positionReads)
            changes.value++
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, attempts)
            playing = true
            changes.value++
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(attempts > 2)
            recovery.cancelAndJoin()
        }

    @Test
    fun failedCandidateDoesNotMakeTheOriginalStreamTimeoutTerminal() {
        for (original in listOf(PlaybackFailureCategory.NETWORK, PlaybackFailureCategory.TIMEOUT)) {
            val failure = PlaybackFailure("audio_stream", "stream failed", category = original)
            for (preparation in PlaybackFailureCategory.entries) {
                assertTrue(PlaybackNetworkRecovery.handles(failure, preparation))
                assertFalse(PlaybackNetworkRecovery.handles(failure.copy(isMediaSourceFailure = false), preparation))
            }
        }
        val decoder = PlaybackFailure("audio_stream", "decoder failed", category = PlaybackFailureCategory.DECODER)
        assertFalse(PlaybackNetworkRecovery.handles(decoder, PlaybackFailureCategory.UNKNOWN))
        assertTrue(PlaybackNetworkRecovery.handles(decoder, PlaybackFailureCategory.TIMEOUT))
    }

    @Test
    fun failedAndSlowCandidatesRetainAdvancingPlaybackButAStallStillExpires() =
        runTest {
            var position = 32_134L
            var attempts = 0
            var reconnecting = false
            val recovery =
                async {
                    PlaybackNetworkRecovery.recover(
                        { true },
                        { true },
                        { true },
                        { position },
                        { reconnecting = it },
                        emptyFlow(),
                        testScheduler.timeSource,
                    ) {
                        if (++attempts % 2 == 0) awaitCancellation()
                        false
                    }
                }
            runCurrent()
            repeat(600) {
                advanceTimeBy(250L)
                position += 250L
                runCurrent()
            }
            assertTrue(attempts > 3)
            assertFalse(recovery.isCompleted)
            assertFalse(reconnecting)
            advanceTimeBy(119_999L)
            runCurrent()
            assertFalse(recovery.isCompleted)
            assertTrue(reconnecting)
            advanceTimeBy(1L)
            assertFalse(recovery.await())
            assertFalse(reconnecting)
        }

    @Test
    fun offlinePlaybackWaitsAndResumesAtTheLatestPosition() =
        runTest {
            var connected = false
            var position = 6_274L
            var reconnecting = false
            var attempts = 0
            val recovery =
                async {
                    PlaybackNetworkRecovery.recover(
                        { true },
                        { connected },
                        { true },
                        { position },
                        { reconnecting = it },
                        emptyFlow(),
                        testScheduler.timeSource,
                    ) {
                        attempts++
                        assertEquals(16_274L, position)
                        true
                    }
                }
            runCurrent()
            repeat(40) {
                position += 250L
                advanceTimeBy(250L)
            }
            runCurrent()
            assertFalse(reconnecting)
            advanceTimeBy(90_000L)
            runCurrent()
            assertTrue(reconnecting)
            assertEquals(0, attempts)
            assertFalse(recovery.isCompleted)
            connected = true
            advanceTimeBy(500L)
            assertTrue(recovery.await())
            assertEquals(1, attempts)
            assertFalse(reconnecting)
        }

    @Test
    fun pausedPlaybackCanWaitIndefinitelyAndRemainsPausedAfterReconnect() =
        runTest {
            val connected = MutableStateFlow(false)
            var playing = true
            var reconnecting = false
            var positionReads = 0
            val recovery =
                async {
                    PlaybackNetworkRecovery.recover(
                        { true },
                        { connected.value },
                        { playing },
                        {
                            positionReads++
                            6_274L
                        },
                        { reconnecting = it },
                        connected.map { Unit },
                        testScheduler.timeSource,
                    ) {
                        assertFalse(playing)
                        true
                    }
                }
            advanceTimeBy(90_000L)
            playing = false
            advanceTimeBy(500L)
            runCurrent()
            val pausedReads = positionReads
            advanceTimeBy(600_000L)
            runCurrent()
            assertEquals(pausedReads, positionReads)
            assertFalse(reconnecting)
            assertFalse(recovery.isCompleted)
            connected.value = true
            advanceTimeBy(500L)
            assertTrue(recovery.await())
        }

    @Test
    fun unusableNetworkExhaustsOnlyAfterTwoMinutesOfStalledPlayback() =
        runTest {
            var reconnecting = false
            val recovery =
                async {
                    PlaybackNetworkRecovery.recover(
                        { true },
                        { false },
                        { true },
                        { 6_274L },
                        { reconnecting = it },
                        emptyFlow(),
                        testScheduler.timeSource,
                    ) { error("No route must not consume resolver attempts") }
                }
            advanceTimeBy(119_999L)
            assertFalse(recovery.isCompleted)
            assertTrue(reconnecting)
            advanceTimeBy(1L)
            assertFalse(recovery.await())
            assertFalse(reconnecting)
        }

    @Test
    fun routeAloneDoesNotProveConnectivityAndFailedRequestsBackOff() =
        runTest {
            val attempts = mutableListOf<Long>()
            val recovery =
                async {
                    PlaybackNetworkRecovery.recover(
                        { true },
                        { true },
                        { true },
                        { 0L },
                        {},
                        emptyFlow(),
                        testScheduler.timeSource,
                    ) {
                        attempts += testScheduler.currentTime
                        false
                    }
                }
            advanceTimeBy(119_999L)
            assertFalse(recovery.isCompleted)
            assertTrue(attempts.size in 5..20)
            assertTrue(attempts.zipWithNext().all { (a, b) -> b - a >= 1_000L })
            assertFalse(recovery.await())
        }

    @Test
    fun trackChangesAndCancellationStopPendingRetries() =
        runTest {
            for (cancel in listOf(false, true)) {
                var current = true
                var cancelledAttempt = false
                var reconnecting = false
                val recovery =
                    async {
                        PlaybackNetworkRecovery.recover(
                            { current },
                            { true },
                            { true },
                            { 0L },
                            { reconnecting = it },
                            emptyFlow(),
                            testScheduler.timeSource,
                        ) {
                            try {
                                awaitCancellation()
                            } finally {
                                cancelledAttempt = true
                            }
                        }
                    }
                advanceTimeBy(1_500L)
                assertTrue(reconnecting)
                if (cancel) {
                    recovery.cancelAndJoin()
                } else {
                    current = false
                    advanceTimeBy(250L)
                    assertFalse(recovery.await())
                }
                assertTrue(cancelledAttempt)
                assertFalse(reconnecting)
            }
        }

    @Test
    fun successfulHandoffCanReplaceThePlaybackInstance() =
        runTest {
            var current = true
            assertTrue(
                PlaybackNetworkRecovery.recover(
                    { current },
                    { true },
                    { true },
                    { 0L },
                    {},
                    emptyFlow(),
                    testScheduler.timeSource,
                ) {
                    current = false
                    true
                },
            )
        }

    @Test
    fun cancelledResolverFlightRetriesWithoutCancellingPlaybackRecovery() =
        runTest {
            var attempts = 0
            assertTrue(
                PlaybackNetworkRecovery.recover(
                    { true },
                    { true },
                    { true },
                    { 0L },
                    {},
                    emptyFlow(),
                    testScheduler.timeSource,
                ) {
                    if (++attempts == 1) throw CancellationException("Network scope changed")
                    true
                },
            )
            assertEquals(2, attempts)
        }

    @Test
    fun stalledRequestsHaveADeadlineWithoutEndingRecoveryImmediately() =
        runTest {
            var attempts = 0
            val recovery =
                async {
                    PlaybackNetworkRecovery.recover(
                        { true },
                        { true },
                        { true },
                        { 0L },
                        {},
                        emptyFlow(),
                        testScheduler.timeSource,
                    ) {
                        attempts++
                        awaitCancellation()
                    }
                }
            advanceTimeBy(95_000L)
            assertFalse(recovery.isCompleted)
            assertTrue(attempts >= 3)
            assertFalse(recovery.await())
        }
}
