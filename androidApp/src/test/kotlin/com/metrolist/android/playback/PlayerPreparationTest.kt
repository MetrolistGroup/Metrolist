package com.metrolist.android.playback

import android.os.Bundle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.playback.PlaybackFailureClassifier
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.SocketException
import java.util.concurrent.Executors

class PlayerPreparationTest {
    @Test
    fun networkChangesOnlyRetryFailedPlaybackThatStillWantsToPlay() {
        val remote = PlaybackResource("video", "https://media.example.test/audio", emptyMap(), null, null)
        val local = remote.copy(url = "file:///audio", clientName = "local", profileId = "cached-audio")
        assertEquals(PlaybackFailureCategory.NETWORK, PlaybackFailureClassifier.fromThrowable(IOException(SocketException("reset"))))
        assertFalse(shouldRecoverPlaybackOnNetworkChange(remote, false, true))
        assertFalse(shouldRecoverPlaybackOnNetworkChange(remote, false, false))
        assertFalse(shouldRecoverPlaybackOnNetworkChange(remote, true, false))
        assertFalse(shouldRecoverPlaybackOnNetworkChange(null, true, true))
        assertFalse(shouldRecoverPlaybackOnNetworkChange(local, true, true))
        assertTrue(shouldRecoverPlaybackOnNetworkChange(remote, true, true))
        for (category in PlaybackFailureCategory.entries) {
            assertFalse(
                shouldAdvanceAfterPlaybackFailure(false, category, true, autoSkipNextOnError = true),
            )
        }
        assertFalse(shouldAdvanceAfterPlaybackFailure(true, PlaybackFailureCategory.NETWORK, true, autoSkipNextOnError = true))
        assertFalse(shouldAdvanceAfterPlaybackFailure(true, PlaybackFailureCategory.TIMEOUT, true, autoSkipNextOnError = true))
        assertFalse(shouldAdvanceAfterPlaybackFailure(true, PlaybackFailureCategory.UNKNOWN, false, autoSkipNextOnError = true))
        assertFalse(
            shouldAdvanceAfterPlaybackFailure(
                true,
                PlaybackFailureCategory.UNKNOWN,
                true,
                PlaybackFailureCategory.NETWORK,
                autoSkipNextOnError = true,
            ),
        )
        assertTrue(shouldAdvanceAfterPlaybackFailure(true, PlaybackFailureCategory.UNAVAILABLE, true, autoSkipNextOnError = true))
        assertFalse(
            shouldAdvanceAfterPlaybackFailure(
                true,
                PlaybackFailureCategory.UNAVAILABLE,
                true,
                repeatMode = Player.REPEAT_MODE_ONE,
                autoSkipNextOnError = true,
            ),
        )
        assertTrue(
            shouldAdvanceAfterPlaybackFailure(
                true,
                PlaybackFailureCategory.UNAVAILABLE,
                true,
                repeatMode = Player.REPEAT_MODE_ALL,
                autoSkipNextOnError = true,
            ),
        )
        assertEquals(WarmReplacementAction.RETARGET, warmReplacementAction(123_456, 123_000, false, true, false, true, 1))
        assertEquals(WarmReplacementAction.COMMIT, warmReplacementAction(123_456, 123_456, false, true, false, true, 2))
    }

    @Test
    fun terminalFailuresKeepTheCurrentItemWhenAutoSkipIsDisabled() {
        for (category in PlaybackFailureCategory.entries) {
            assertFalse(shouldAdvanceAfterPlaybackFailure(true, category, true, autoSkipNextOnError = false))
        }
    }

    @Test
    fun speculativeQueueReplacementErrorsDoNotEnterActivePlaybackRecovery() {
        assertTrue(shouldReportActivePlaybackError(isSpeculativeQueueReplacement = false))
        assertFalse(shouldReportActivePlaybackError(isSpeculativeQueueReplacement = true))
    }

    @Test
    fun initialQueueReplacementRequiresCurrentRequestPlayerAndInstanceAfterReadiness() {
        assertTrue(isCurrentPreparedQueueReplacement(true, true, 4L, 4L))
        assertFalse(isCurrentPreparedQueueReplacement(false, true, 4L, 4L))
        assertFalse(isCurrentPreparedQueueReplacement(true, false, 4L, 4L))
        assertFalse(isCurrentPreparedQueueReplacement(true, true, 4L, 5L))
    }

    @Test
    fun playingReplacementCatchesUpWithoutRepeatedSeeksBeforeHandoff() {
        assertEquals(WarmReplacementAction.RETARGET, warmReplacementAction(69_000L, 68_000L, true, true, true, true, 0))
        assertEquals(WarmReplacementAction.CATCH_UP, warmReplacementAction(70_000L, 69_000L, true, true, true, true, 1))
        assertEquals(WarmReplacementAction.COMMIT, warmReplacementAction(71_000L, 71_000L, true, true, true, true, 1))
        assertEquals(WarmReplacementAction.WAIT, warmReplacementAction(71_000L, 71_000L, true, true, true, false, 1))
    }

    @Test
    fun recoveryHandoffNeverRewindsAndRetargetsRemainBounded() {
        fun action(
            source: Long,
            candidate: Long,
            playing: Boolean = true,
            retargets: Int = 1,
            userSeek: Boolean = false,
        ) = warmReplacementAction(source, candidate, playing, true, playing, true, retargets, userSeek, preservePosition = true)

        assertEquals(WarmReplacementAction.CATCH_UP, action(6_183L, 6_182L))
        assertEquals(WarmReplacementAction.COMMIT, action(6_183L, 6_183L))
        assertEquals(WarmReplacementAction.WAIT_FOR_SOURCE, action(6_183L, 7_000L, retargets = 0))
        assertEquals(WarmReplacementAction.FAILED, action(6_183L, 7_000L, playing = false))
        assertEquals(WarmReplacementAction.RETARGET, action(1_000L, 7_000L, userSeek = true))
        assertEquals(WarmReplacementAction.FAILED, action(1_000L, 7_000L, retargets = 3, userSeek = true))
    }

    @Test(timeout = 5_000)
    fun stalledReplacementCommitsReadinessWithoutRunningASecondAudioClock() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking {
                val player = withContext(dispatcher) { ThreadBoundPlayer() }
                val preparing = CompletableDeferred<Unit>()
                val load = async(dispatcher) { prepareStreamReplacement(player.api) { preparing.complete(Unit) } }
                preparing.await()
                withContext(dispatcher) {
                    assertFalse(player.api.playWhenReady)
                    player.listener!!.onPlaybackStateChanged(Player.STATE_READY)
                }
                assertSame(MediaPreparationResult.Ready, load.await())
                assertEquals(0, player.stops)
                assertEquals(1, player.removals)
            }
        }
    }

    @Test(timeout = 5_000)
    fun cancellationFromAnotherThreadCleansUpOnPlayerThread() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking {
                val player = withContext(dispatcher) { ThreadBoundPlayer() }
                val preparing = CompletableDeferred<Unit>()
                val load = async(dispatcher) { awaitPlayerReady(player.api) { preparing.complete(Unit) } }
                preparing.await()

                load.cancelAndJoin()

                assertEquals(1, player.stops)
                assertEquals(1, player.removals)
                assertNull(player.listener)
            }
        }
    }

    @Test(timeout = 5_000)
    fun asynchronousPlayerErrorCompletesReadinessAsFailed() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking {
                val player = withContext(dispatcher) { ThreadBoundPlayer() }
                val preparing = CompletableDeferred<Unit>()
                val load = async(dispatcher) { awaitPlayerReady(player.api) { preparing.complete(Unit) } }
                preparing.await()
                val error = TestPlaybackException()

                withContext(dispatcher) { player.listener!!.onPlayerError(error) }

                val result = load.await()
                assertTrue(result is MediaPreparationResult.Failed)
                assertSame(error, (result as MediaPreparationResult.Failed).error)
                assertEquals(0, player.stops)
                assertEquals(1, player.removals)
                assertNull(player.listener)
            }
        }
    }

    @Test(timeout = 5_000)
    fun readinessRemovesListenerWithoutStoppingPlayback() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking {
                val player = withContext(dispatcher) { ThreadBoundPlayer() }
                val preparing = CompletableDeferred<Unit>()
                val load = async(dispatcher) { awaitPlayerReady(player.api) { preparing.complete(Unit) } }
                preparing.await()

                withContext(dispatcher) { player.listener!!.onPlaybackStateChanged(Player.STATE_READY) }

                assertSame(MediaPreparationResult.Ready, load.await())
                assertEquals(0, player.stops)
                assertEquals(1, player.removals)
                assertNull(player.listener)
            }
        }
    }

    @Test(timeout = 5_000)
    fun pendingQueuePreparationKeepsIntentAcrossDiscoveryAndReadiness() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking {
                val scenarios =
                    listOf(
                        IntentScenario(true, emptyList(), emptyList(), true),
                        IntentScenario(true, listOf(false), emptyList(), false),
                        IntentScenario(true, emptyList(), listOf(false), false),
                        IntentScenario(true, listOf(false), listOf(true), true),
                        IntentScenario(false, emptyList(), emptyList(), false),
                        IntentScenario(false, emptyList(), listOf(true), true),
                        IntentScenario(true, emptyList(), emptyList(), false, castAutoplay = false),
                        IntentScenario(false, emptyList(), emptyList(), true, castAutoplay = true),
                        IntentScenario(false, listOf(false), emptyList(), false, castAutoplay = true),
                        IntentScenario(false, emptyList(), listOf(false), false, castAutoplay = true),
                        IntentScenario(true, emptyList(), listOf(true), true, castAutoplay = false),
                    )
                for (scenario in scenarios) {
                    val player = withContext(dispatcher) { ThreadBoundPlayer() }
                    val discovered = CompletableDeferred<Unit>()
                    val preparing = CompletableDeferred<Unit>()
                    withContext(dispatcher) {
                        player.api.playWhenReady = !scenario.requested
                        stopForPlaybackRequest(player.api, scenario.requested)
                        assertEquals(scenario.requested, player.api.playWhenReady)
                        scenario.castAutoplay?.let { remoteAutoplay ->
                            var connected = true
                            val route =
                                routeCastQueueReplacement(
                                    suppression = CastDisconnectSuppression(),
                                    isCurrentRequest = { true },
                                    isConnected = { connected },
                                    replace = { false },
                                    disconnect = { connected = false },
                                )
                            assertEquals(CastQueueReplacementResult.LOCAL, route)
                            setPendingPlaybackIntent(player.api, requestId = 2L, currentRequestId = 2L, remoteAutoplay)
                            assertEquals(remoteAutoplay, player.api.playWhenReady)
                            setPendingPlaybackIntent(player.api, requestId = 1L, currentRequestId = 2L, !remoteAutoplay)
                            assertEquals(remoteAutoplay, player.api.playWhenReady)
                        }
                    }
                    val load =
                        async(dispatcher) {
                            discovered.await()
                            val targetPlaying =
                                playbackIntentForPreparation(player.api, scenario.requested, preserveCurrentIntent = true)
                            awaitPlayerReady(player.api) { preparing.complete(Unit) }
                            player.api.playWhenReady = targetPlaying()
                            player.api.playWhenReady
                        }
                    withContext(dispatcher) {
                        scenario.duringDiscovery.forEach { player.api.playWhenReady = it }
                        discovered.complete(Unit)
                    }
                    preparing.await()
                    withContext(dispatcher) {
                        scenario.duringReadiness.forEach { player.api.playWhenReady = it }
                        player.listener!!.onPlaybackStateChanged(Player.STATE_READY)
                    }
                    assertEquals(scenario.toString(), scenario.expected, load.await())
                    assertEquals(1, player.stops)
                    assertNull(player.listener)
                }
            }
        }
    }

    @Test
    fun standaloneColdLoadsInitializeIntentButRecoveryPreservesLiveIntent() {
        val player = ThreadBoundPlayer()
        val coldIntent = playbackIntentForPreparation(player.api, resumePlayback = true, preserveCurrentIntent = false)
        assertTrue(coldIntent())
        player.api.playWhenReady = false
        assertFalse(coldIntent())
        val recoveryIntent = playbackIntentForPreparation(player.api, resumePlayback = true, preserveCurrentIntent = true)
        assertFalse(recoveryIntent())
        player.api.playWhenReady = true
        assertTrue(recoveryIntent())
        val restoredIntent = playbackIntentForPreparation(player.api, resumePlayback = false, preserveCurrentIntent = false)
        assertFalse(restoredIntent())
    }

    @Test
    fun synchronizedIntentProviderRemainsAuthoritativeForColdAndWarmLoads() {
        for (preserveCurrentIntent in listOf(false, true)) {
            val player = ThreadBoundPlayer()
            var desiredPlaying = true
            val targetPlaying =
                playbackIntentForPreparation(player.api, resumePlayback = false, preserveCurrentIntent) { desiredPlaying }
            assertTrue(targetPlaying())
            desiredPlaying = false
            player.api.playWhenReady = true
            assertFalse(targetPlaying())
        }
    }

    private data class IntentScenario(
        val requested: Boolean,
        val duringDiscovery: List<Boolean>,
        val duringReadiness: List<Boolean>,
        val expected: Boolean,
        val castAutoplay: Boolean? = null,
    )

    private class TestPlaybackException :
        PlaybackException("failure", null, PlaybackException.ERROR_CODE_UNSPECIFIED, Bundle.EMPTY, 0L)

    private class ThreadBoundPlayer {
        private val owner = Thread.currentThread()
        var listener: Player.Listener? = null
        var stops = 0
        var removals = 0
        private var playWhenReady = false
        val api =
            Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
                check(Thread.currentThread() === owner) { "Player accessed from the wrong thread" }
                when (method.name) {
                    "getPlayWhenReady" -> {
                        return@newProxyInstance playWhenReady
                    }

                    "setPlayWhenReady" -> {
                        playWhenReady = args!![0] as Boolean
                    }

                    "addListener" -> {
                        listener = args!![0] as Player.Listener
                    }

                    "removeListener" -> {
                        assertSame(listener, args!![0])
                        listener = null
                        removals++
                    }

                    "stop" -> {
                        stops++
                    }

                    else -> {
                        error("Unexpected player call: ${method.name}")
                    }
                }
                null
            } as Player
    }
}
