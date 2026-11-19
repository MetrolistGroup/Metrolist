package com.metrolist.desktop.state.playback

import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.CastError
import com.metrolist.shared.cast.CastMedia
import com.metrolist.shared.cast.CastProtocol
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.cast.JvmCastManager
import com.metrolist.shared.equalizer.EqualizerProfile
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicPlayer
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.PlaybackFailure
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.playback.PlaybackFailureClassifier
import com.metrolist.shared.playback.PlaybackRecoveryAction
import com.metrolist.shared.playback.PlaybackRecoveryBudget
import com.metrolist.shared.playback.PlaybackRecoveryPolicy
import com.metrolist.shared.playback.ffmpeg.AudioRenderer
import com.metrolist.shared.playback.shouldSkipAfterPlaybackFailure
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.PropertiesSettings
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopCastPlaybackTest {
    @BeforeTest
    fun isolateSettings() {
        Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
    }

    @AfterTest
    fun restoreSettings() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun checkpointMapsOnlyTheSameLocalVideoTimeline() {
        val remote = CastState(positionMs = 60_000L, mediaVideoId = "counterpart")
        assertEquals(55_000L, desktopCastCheckpointPosition(remote, "counterpart") { it - 5_000L })
        assertEquals(60_000L, desktopCastCheckpointPosition(remote, "original") { error("different timeline") })
        assertEquals(60_000L, desktopCastCheckpointPosition(remote.copy(mediaVideoId = null), null) { error("unknown timeline") })
    }

    @Test
    fun songChangesReuseTheReceiverAndPreservePlayingAndPausedIntent() =
        withPlayback {
            select(1).join()
            assertLoaded(second)
            select(0, playing = false).join()
            assertLoaded(first)
            assertFalse(manager.state.value.isPlaying)
            assertEquals(3, receiver.actions.count { it == "SetAVTransportURI" })
            assertEquals(1, receiver.descriptorRequests)
        }

    @Test
    fun repeatOneLoadsTheCurrentOccurrenceWithEnrichedPlaybackMetadata() =
        withPlayback {
            val enriched =
                first.copy(
                    canonicalTitle = "Canonical title",
                    album = AlbumTiny("album", "Album"),
                    thumbnail = "https://example.com/cover.jpg",
                )
            val decision = decidePlaybackQueueTransition(RepeatMode.ONE, true, enriched, queue.items.value, queue.currentIndex.value)
            assertEquals(PlaybackQueueAction.REPLAY_CURRENT, decision.action)
            val generation = manager.state.value.mediaGeneration
            assertEquals(
                DesktopCastTrackResult.LOADED,
                playback.replaceTrack(requireNotNull(decision.song), 0L, true) { true },
            )
            assertLoaded(first)
            assertTrue(manager.state.value.isPlaying)
            assertEquals(generation + 1L, manager.state.value.mediaGeneration)
            assertEquals(1, receiver.descriptorRequests)
        }

    @Test
    fun metadataChangesDuringResolutionKeepTheOccurrenceButSongChangesDoNot() {
        for (changeSong in listOf(false, true)) {
            withPlayback {
                queue.skipTo(1)
                val entry = queue.state.value.entries[1]
                val started = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                fetch = { song, _, _ ->
                    started.complete(Unit)
                    finish.await()
                    resource(song)
                }
                val selection = scope.async { playback.replaceTrack(second, 0L, true) { true } }
                started.await()
                queue.replaceCurrent(second.copy(id = if (changeSong) third.id else second.id, canonicalTitle = "Updated title"))
                finish.complete(Unit)
                assertEquals(
                    if (changeSong) DesktopCastTrackResult.SUPERSEDED else DesktopCastTrackResult.LOADED,
                    selection.await(),
                )
                assertEquals(
                    entry.key,
                    queue.state.value.entries[1]
                        .key,
                )
                assertEquals(if (changeSong) first.id else second.id, manager.state.value.mediaVideoId)
            }
        }
    }

    @Test
    fun castAndLocalResolutionFailuresPreserveNetworkAndTimeoutSkipPolicy() {
        val failures =
            listOf(
                PlaybackFailureCategory.TIMEOUT to { SocketTimeoutException("Resolution timed out") },
                PlaybackFailureCategory.NETWORK to { UnknownHostException("Stream host unavailable") },
            )
        for ((expectedCategory, createFailure) in failures) {
            for (nextOccurrence in listOf(false, true)) {
                withPlayback {
                    val target = if (nextOccurrence) second else first
                    val castFailure = createFailure()
                    val localFailure = createFailure()
                    val attempts = CopyOnWriteArrayList<CastProtocol?>()
                    fetch = { song, protocol, onFailure ->
                        assertEquals(target.id, song.id)
                        attempts += protocol
                        onFailure(if (protocol != null) castFailure else localFailure)
                        null
                    }
                    var failure: Throwable? = null
                    val resource =
                        if (nextOccurrence) {
                            resolveNextQueueOccurrence(queue, { true }) { song ->
                                playback.resolveTrack(song, onFailure = { failure = it }) { true }
                            }?.second
                        } else {
                            playback.resolveTrack(target, onFailure = { failure = it }) { true }
                        }
                    assertNull(resource)
                    assertSame(localFailure, failure)
                    assertEquals(listOf(CastProtocol.SONOS, null), attempts)
                    val category = PlaybackFailureClassifier.fromThrowable(failure)
                    assertEquals(expectedCategory, category)
                    if (shouldSkipAfterPlaybackFailure(true, true, true, false, category)) queue.getNext()
                    assertEquals(if (nextOccurrence) 1 else 0, queue.currentIndex.value)
                    assertFalse(manager.state.value.isConnected)
                    assertFalse(receiver.playing)
                }
            }
        }
    }

    @Test
    fun playbackIntentChangesDuringManualAndAutomaticResolutionReachTheReplacement() {
        for (automatic in listOf(false, true)) {
            for (initialPlaying in listOf(false, true)) {
                for (appControl in listOf(false, true)) {
                    withPlayback {
                        if (!initialPlaying) {
                            manager.pause()
                            manager.state.first { !it.isPlaying }
                        }
                        val started = CompletableDeferred<Unit>()
                        val finish = CompletableDeferred<Unit>()
                        fetch = { song, _, _ ->
                            started.complete(Unit)
                            finish.await()
                            resource(song)
                        }
                        val selection = if (automatic) advance() else select(1, playing = initialPlaying)
                        started.await()
                        if (appControl) {
                            playback.setPlaying(!initialPlaying).join()
                        } else {
                            if (initialPlaying) manager.pause() else manager.play()
                            manager.state.first { it.isPlaying != initialPlaying }
                        }
                        finish.complete(Unit)
                        selection.join()
                        assertLoaded(second)
                        assertEquals(!initialPlaying, manager.state.value.isPlaying)
                        assertEquals(!initialPlaying, receiver.playing)
                    }
                }
            }
        }
    }

    @Test
    fun initialReceiverStateCannotOverwriteACompletedSeek() =
        runBlocking {
            val pending = ConcurrentLinkedQueue<Runnable>()
            val paused = AtomicBoolean(true)
            val dispatcher =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        if (paused.get()) pending.add(block) else block.run()
                    }
                }

            fun drain() {
                paused.set(false)
                while (pending.isNotEmpty()) pending.remove().run()
            }
            val fixture = Fixture(CoroutineScope(coroutineContext + SupervisorJob()), false, null, dispatcher)
            try {
                fixture.connect()
                fixture.receiver.position = "00:00:42"
                fixture.manager.seekTo(42_000L)
                drain()
                assertEquals(42_000L, fixture.manager.state.value.positionMs)
            } finally {
                drain()
                withContext(NonCancellable) { fixture.close() }
            }
        }

    @Test
    fun disconnectDuringReceiverSwitchResumesTheCurrentSongLocally() =
        withPlayback(localPlayback = true) {
            receiver.position = "00:00:42"
            manager.seekTo(42_000L)
            val started = CompletableDeferred<Unit>()
            fetch = { song, protocol, _ ->
                if (protocol != null) {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    null
                } else {
                    localResource(song)
                }
            }
            Receiver().use { other ->
                val handoff =
                    requireNotNull(
                        playback.castTo(other.device.copy(id = "other")) {
                            DesktopCastHandoffState(first, null, 42_000L, 60_000L, true, 1f, player.currentPlaybackInstanceId)
                        },
                    )
                transitions.job = handoff
                started.await()
                playback.disconnect { first }
                handoff.join()
                assertFalse(manager.state.value.isConnected)
                assertEquals(first.id, player.currentSong.value?.id)
                assertTrue(player.playbackReady)
                assertEquals(42_000L, player.currentPosition.value)
                assertTrue(player.wantsPlayback)
                assertTrue(other.actions.isEmpty())
            }
        }

    @Test
    fun radioDisconnectFallbackPreparesAtThePreservedPositionAndLatestIntent() {
        for (playing in listOf(false, true)) {
            withPlayback(localPlayback = true) {
                val started = CompletableDeferred<Unit>()
                fetch = { song, protocol, _ ->
                    if (protocol != null) {
                        started.complete(Unit)
                        CompletableDeferred<Unit>().await()
                        null
                    } else {
                        localResource(song)
                    }
                }
                val radio = startRadio(localPrepared = true)
                started.await()
                if (!playing) playback.setPlaying(false).join()
                playback.disconnect { first }
                radio.join()
                assertFalse(manager.state.value.isConnected)
                assertEquals(listOf(first), localSelections)
                assertEquals(first.id, player.currentSong.value?.id)
                assertTrue(player.playbackReady)
                assertEquals(42_000L, player.currentPlaybackPositionMs())
                assertEquals(playing, player.wantsPlayback)
            }
        }
    }

    @Test
    fun manualSelectionCancelsAnAutomaticAdvanceEvenWhenItsResolverIgnoresCancellation() =
        withPlayback {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            fetch = { song, _, _ ->
                if (song == second) {
                    started.complete(Unit)
                    withContext(NonCancellable) { finish.await() }
                }
                resource(song)
            }
            val automatic = advance()
            started.await()
            val manual = select(2)
            manual.join()
            finish.complete(Unit)
            automatic.join()
            assertTrue(automatic.isCancelled)
            assertLoaded(third)
            assertEquals(listOf("First", "Third"), receiver.loadedTitles)
            assertEquals(listOf(third), published)
        }

    @Test
    fun cancelledFailedResolutionCannotDisconnectTheLaterManualSelection() =
        withPlayback {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            fetch = { song, _, _ ->
                if (song == second) {
                    started.complete(Unit)
                    withContext(NonCancellable) { finish.await() }
                    null
                } else {
                    resource(song)
                }
            }
            val automatic = advance()
            started.await()
            select(2).join()
            finish.complete(Unit)
            automatic.join()
            assertLoaded(third)
            assertNull(manager.state.value.error)
            assertEquals(1, receiver.descriptorRequests)
        }

    @Test
    fun eofDuringManualResolutionCannotAdvancePastTheSelectedSong() =
        withPlayback {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            fetch = { song, _, _ ->
                started.complete(Unit)
                finish.await()
                resource(song)
            }
            val manual = select(1)
            started.await()
            assertFalse(
                transitions.advance(
                    manager.state.value.copy(hasEnded = true),
                    queue.state.value,
                ) { error("EOF replaced a pending selection") },
            )
            finish.complete(Unit)
            manual.join()
            assertLoaded(second)
            assertEquals(listOf(second), published)
            yield()
            assertFalse(transitions.pending)
        }

    @Test
    fun eofIsRecheckedWhenAPendingTransitionFinishesWithoutAnotherReceiverReport() =
        withPlayback {
            val ended = MutableStateFlow(false)
            val advanced = CompletableDeferred<Unit>()
            val generation = transitions.begin()
            val observer =
                scope.launch {
                    combine(ended, transitions.pendingState) { eof, _ -> eof }.collect { eof ->
                        if (eof) {
                            transitions.advance(
                                manager.state.value.copy(hasEnded = true),
                                queue.state.value,
                            ) { advanced.complete(Unit) }
                        }
                    }
                }
            ended.value = true
            yield()
            assertFalse(advanced.isCompleted)
            transitions.finish(generation)
            advanced.await()
            observer.cancel()
        }

    @Test
    fun sameTrackRadioQueueReplacementCancelsTheDiscardedQueuesAutomaticAdvance() =
        withPlayback {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            fetch = { song, _, _ ->
                if (song == second) {
                    started.complete(Unit)
                    withContext(NonCancellable) { finish.await() }
                }
                resource(song)
            }
            val automatic = advance()
            started.await()
            val oldEntry = queue.state.value.entries[0]
            val radio = startRadio(localPrepared = true)
            radio.join()
            finish.complete(Unit)
            automatic.join()
            assertLoaded(first)
            assertEquals(listOf(first), queue.items.value)
            assertTrue(
                manager.state.value.queueEntry
                    ?.key != oldEntry.key,
            )
            assertEquals(42_000L, manager.state.value.positionMs)
            assertEquals(listOf("First", "First"), receiver.loadedTitles)
        }

    @Test
    fun radioRejectsALateEndReportFromTheDiscardedQueue() =
        withPlayback {
            val oldEnd = manager.state.value.copy(hasEnded = true)
            startRadio(localPrepared = true).join()
            yield()
            assertFalse(transitions.advance(oldEnd, queue.state.value) { error("Discarded queue advanced Radio") })
            assertLoaded(first)
        }

    @Test
    fun sameTrackRadioPreservesPausedReceiverIntent() =
        withPlayback {
            manager.pause()
            manager.state.first { !it.isPlaying }
            startRadio(localPrepared = true).join()
            assertLoaded(first)
            assertFalse(manager.state.value.isPlaying)
            assertEquals(42_000L, manager.state.value.positionMs)
        }

    @Test
    fun preparedLocalRadioKeepsPlaybackAndStillReplacesTheQueueThroughTheOwner() =
        withPlayback {
            manager.disconnect()
            val generation = transitions.generation
            startRadio(localPrepared = true).join()
            assertEquals(generation + 1L, transitions.generation)
            assertEquals(listOf(first), queue.items.value)
            assertTrue(published.isEmpty())
            assertTrue(localSelections.isEmpty())
        }

    @Test
    fun failedNextResolutionStopsTheOldReceiverBeforeTheQueueAndDisplayedSongAdvance() =
        withPlayback {
            fetch = { song, protocol, _ ->
                assertEquals(second, song)
                assertEquals(first, queue.currentItem())
                if (protocol != null) {
                    assertEquals(CastProtocol.SONOS, protocol)
                    assertTrue(receiver.playing)
                } else {
                    assertFalse(receiver.playing)
                }
                null
            }
            advance().join()
            assertEquals(second, queue.currentItem())
            assertEquals(listOf(second), localSelections)
            assertFalse(receiver.playing)
            assertFalse(manager.state.value.isConnected)
            assertEquals(CastError.UNSUPPORTED_STREAM, manager.state.value.error)
            assertFalse(playback.shouldResumeAfterDisconnect(manager.state.value))
            assertEquals(1, receiver.actions.count { it == "Stop" })
            assertTrue(published.isEmpty())
        }

    @Test
    fun disconnectDuringManualResolutionCancelsTheCastLoadAndContinuesTheSelectionLocally() =
        withPlayback(localPlayback = true) {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            fetch = { song, protocol, _ ->
                assertEquals(second, song)
                if (protocol != null) {
                    started.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                localResource(song)
            }
            val manual = select(1)
            started.await()
            playback.disconnect { first }
            cancelled.await()
            manual.join()
            assertFalse(manager.state.value.isConnected)
            assertEquals(second, queue.currentItem())
            assertEquals(listOf(second), localSelections)
            assertTrue(published.isEmpty())
            assertTrue(player.playbackReady)
            assertEquals(second.id, player.currentSong.value?.id)
            assertEquals(listOf("First"), receiver.loadedTitles)
        }

    @Test
    fun disconnectDuringReceiverLoadContinuesTheSelectedSongLocally() =
        withPlayback(localPlayback = true) {
            fetch = { song, protocol, _ -> if (protocol == null) localResource(song) else resource(song) }
            val loading = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            receiver.onPlay = {
                loading.complete(Unit)
                runBlocking { finish.await() }
            }
            try {
                val manual = select(1)
                loading.await()
                playback.disconnect { first }
                finish.complete(Unit)
                manual.join()
                assertFalse(manager.state.value.isConnected)
                assertFalse(receiver.playing)
                assertEquals(second, queue.currentItem())
                assertEquals(listOf(second), localSelections)
                assertTrue(published.isEmpty())
                assertTrue(player.playbackReady)
                assertEquals(second.id, player.currentSong.value?.id)
            } finally {
                finish.complete(Unit)
                receiver.onPlay = {}
            }
        }

    @Test
    fun disconnectDuringNextResolutionFetchesTheSelectedOccurrenceForLocalPlayback() =
        withPlayback(localPlayback = true) {
            val started = CompletableDeferred<Unit>()
            val localFetches = CopyOnWriteArrayList<SongItem>()
            fetch = { song, protocol, _ ->
                if (protocol != null) {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                } else {
                    localFetches += song
                }
                localResource(song)
            }
            val automatic = advance()
            started.await()
            playback.setPlaying(false).join()
            playback.disconnect { first }
            automatic.join()
            assertEquals(listOf(second), localFetches)
            assertEquals(listOf(second), localSelections)
            assertEquals(second, queue.currentItem())
            assertFalse(receiver.playing)
            assertTrue(player.playbackReady)
            assertEquals(second.id, player.currentSong.value?.id)
            assertEquals(0L, player.currentPosition.value)
            assertFalse(player.wantsPlayback)
        }

    @Test
    fun skipDisconnectFallbackAppliesASeekMadeDuringLocalPreparation() =
        withPlayback(localPlayback = true) {
            val started = CompletableDeferred<Unit>()
            val preparing = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            fetch = { song, protocol, _ ->
                if (protocol != null) {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
                localResource(song)
            }
            onPrepare = {
                preparing.complete(Unit)
                runBlocking { finish.await() }
            }
            try {
                val selection = advance()
                started.await()
                playback.setPlaying(false).join()
                playback.disconnect { first }
                preparing.await()
                resumePositionMs = 9_000L
                finish.complete(Unit)
                selection.join()
                assertEquals(second.id, player.currentSong.value?.id)
                assertTrue(player.playbackReady)
                player.currentPosition.first { it == 9_000L }
                assertFalse(player.wantsPlayback)
            } finally {
                finish.complete(Unit)
            }
        }

    @Test
    fun radioPreparationFailureStopsThePreviousLocalSongAndReportsTheCause() =
        withPlayback(localPlayback = true) {
            assertTrue(player.prepareAndPlay(first, localResource(first), resumePlayback = true))
            assertTrue(player.wantsPlayback)
            val oldInstance = player.currentPlaybackInstanceId
            val started = CompletableDeferred<Unit>()
            fetch = { song, protocol, _ ->
                if (protocol != null) {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    null
                } else {
                    resource(song).copy(url = "/missing/radio-stream.wav", clientName = "local")
                }
            }
            val radio = startRadio(localPrepared = true, song = third)
            started.await()
            playback.disconnect { first }
            radio.join()
            assertEquals(third, queue.currentItem())
            assertEquals(third.id, player.currentSong.value?.id)
            assertTrue(player.currentPlaybackInstanceId != oldInstance)
            assertFalse(player.isPlaying.value)
            assertFalse(player.playbackReady)
            val failure = preparationFailures.receive()
            assertEquals("audio_prepare", failure.stage)
            assertEquals(third.id, failure.videoId)
            assertEquals(player.currentPlaybackInstanceId, failure.playbackInstanceId)
            assertNotNull(failure.cause)
            assertTrue(PlaybackRecoveryPolicy.canRecover(failure))
            assertTrue(localSelections.isEmpty())
        }

    @Test
    fun ordinaryQueuePrepareFailureRetainsTheResourceAndRecoversWithAnotherClient() {
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .body(byteArrayOf().toResponseBody())
                        .build()
                }.build()
        try {
            withPlayback(localPlayback = true, mediaHttpClient = client) {
                manager.disconnect()
                queue.skipTo(1)
                val rejected = resource(second).copy(clientName = "WEB_REMIX", profileId = "WEB_REMIX__po")
                assertFalse(prepareLocal(second, rejected, playing = true))
                val failure = preparationFailures.receive()
                assertEquals("audio_prepare", failure.stage)
                assertEquals(second.id, failure.videoId)
                assertEquals(player.currentPlaybackInstanceId, failure.playbackInstanceId)
                assertEquals(PlaybackFailureCategory.CDN_FORBIDDEN, failure.category)
                assertSame(rejected, player.currentPlaybackResource.value)
                assertNull(player.loadFailure.value)
                assertTrue(player.wantsPlayback)
                assertTrue(PlaybackRecoveryPolicy.canRecover(failure))
                val excluded = PlaybackRecoveryPolicy.recoveryExclusions(failure.category, rejected, failure.playbackPositionMs)
                assertTrue(rejected.profileId in excluded)
                val budget = PlaybackRecoveryBudget.starting(0L)
                val decision = PlaybackRecoveryPolicy.decide(failure.category, budget, 1L, rejected, failure.playbackPositionMs)
                assertEquals(PlaybackRecoveryAction.REFRESH_URL, decision.action)
                assertEquals(budget.attemptsRemaining - 1, decision.budget.attemptsRemaining)
                val replacement = localResource(second).copy(clientName = "ANDROID", profileId = "ANDROID")
                assertTrue(replacement.profileId !in excluded)
                assertTrue(
                    withContext(Dispatchers.IO) {
                        player.playRecovered(
                            second,
                            replacement,
                            failure.playbackPositionMs,
                            resumePlayback = player.wantsPlayback,
                            recoveryPlaybackInstanceId = failure.playbackInstanceId,
                        )
                    },
                )
                assertSame(replacement, player.currentPlaybackResource.value)
                assertEquals(second.id, player.currentSong.value?.id)
                assertTrue(player.playbackReady)
                assertTrue(player.wantsPlayback)
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test
    fun replacingAnOccurrenceWithTheSameSongRejectsItsPendingLoad() =
        withPlayback {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            fetch = { song, _, _ ->
                started.complete(Unit)
                finish.await()
                resource(song)
            }
            val manual = select(1)
            started.await()
            queue.setQueue(listOf(first, second, third), 1)
            finish.complete(Unit)
            manual.join()
            assertTrue(published.isEmpty())
            assertTrue(localSelections.isEmpty())
            assertEquals(listOf("First"), receiver.loadedTitles)
        }

    private fun withPlayback(
        localPlayback: Boolean = false,
        mediaHttpClient: OkHttpClient? = null,
        block: suspend Fixture.() -> Unit,
    ): Unit =
        runBlocking {
            withTimeout(15_000L) {
                val fixture = Fixture(CoroutineScope(coroutineContext + SupervisorJob()), localPlayback, mediaHttpClient)
                try {
                    fixture.connect()
                    fixture.block()
                    fixture.failures.firstOrNull()?.let { throw it }
                } finally {
                    withContext(NonCancellable) { fixture.close() }
                }
            }
        }

    private class Fixture(
        parentScope: CoroutineScope,
        localPlayback: Boolean,
        mediaHttpClient: OkHttpClient?,
        managerDispatcher: CoroutineDispatcher? = null,
    ) {
        val failures = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(parentScope.coroutineContext + CoroutineExceptionHandler { _, error -> failures += error })
        val receiver = Receiver()
        val first = SongItem("first", "First", null, emptyList(), null)
        val second = first.copy(id = "second", title = "Second")
        val third = first.copy(id = "third", title = "Third")
        val queue = MusicQueue().apply { setQueue(listOf(first, second, third)) }
        val player =
            when {
                mediaHttpClient != null -> {
                    MusicPlayer(mediaHttpClient = mediaHttpClient, rendererFactory = { rate, channels ->
                        onPrepare()
                        testRenderer(rate, channels)
                    })
                }

                localPlayback -> {
                    MusicPlayer(rendererFactory = { rate, channels ->
                        onPrepare()
                        testRenderer(rate, channels)
                    })
                }

                else -> {
                    MusicPlayer()
                }
            }
        private var localFile: Path? = null

        fun localResource(song: SongItem): PlaybackResource {
            val file =
                localFile ?: Files.createTempFile("cast-finalround-", ".wav").also {
                    Files.write(it, silentWav())
                    localFile = it
                }
            return resource(
                song,
            ).copy(url = file.toString(), clientName = "local", mimeType = "audio/wav", contentLengthBytes = Files.size(file))
        }

        val managerScope = managerDispatcher?.let { CoroutineScope(scope.coroutineContext + it) } ?: scope
        val manager =
            JvmCastManager(managerScope, { error("No discovery") }, { listOf(InetAddress.getLoopbackAddress()) }, "test", streamPort = 0)
        val transitions = DesktopPlaybackTransitions(scope)
        val published = mutableListOf<SongItem>()
        val localSelections = mutableListOf<SongItem>()
        var fetch: suspend (SongItem, CastProtocol?, (Throwable) -> Unit) -> PlaybackResource? = { song, _, _ -> resource(song) }
        val playback =
            DesktopCastPlayback(
                scope,
                player,
                queue,
                {},
                manager,
                transitions,
            ) { song, protocol, onFailure -> fetch(song, protocol, onFailure) }

        suspend fun connect() {
            assertTrue(
                manager.cast(
                    receiver.device,
                    CastMedia(first, null, resource(first), 180_000L, queue.state.value.entries[0]),
                    0L,
                    true,
                    1f,
                ),
            )
        }

        var resumePositionMs = 0L
        var onPrepare: () -> Unit = {}
        val preparationFailures = Channel<PlaybackFailure>(Channel.UNLIMITED)
        private val failureObserver =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                player.streamFailure.collect(preparationFailures::send)
            }

        suspend fun prepareLocal(
            song: SongItem,
            resource: PlaybackResource,
            playing: Boolean,
        ): Boolean {
            withContext(Dispatchers.IO) {
                player.play(song, resource, startPaused = !transitions.playbackIntent(playing) || resumePositionMs > 0L)
            }
            val played = player.playbackReady
            completeDesktopPlaybackResume(
                positionMs = resumePositionMs,
                prepared = player.playbackReady,
                currentTransition = true,
                desiredPlaying = { transitions.playbackIntent(playing) },
                seek = player::seekTo,
                setPlaying = player::setPlaying,
            )
            if (played) localSelections += song
            return played
        }

        fun startRadio(
            localPrepared: Boolean,
            song: SongItem = first,
        ): Job {
            val radio =
                DesktopRadioPlayback(
                    scope,
                    beginTransition = { preserveCurrent ->
                        assertEquals(localPrepared && !manager.state.value.isConnected, preserveCurrent)
                        transitions.begin()
                    },
                    replaceQueue = { song, _ -> queue.setQueue(listOf(song)) },
                    play = { song, generation, playing, position ->
                        resumePositionMs = position
                        when (playback.replaceTrack(song, position, playing) { transitions.generation == generation }) {
                            DesktopCastTrackResult.LOADED -> {
                                published += song
                            }

                            DesktopCastTrackResult.SUPERSEDED -> {
                                Unit
                            }

                            DesktopCastTrackResult.LOCAL -> {
                                val resource = fetch(song, null) {}
                                if (resource != null) prepareLocal(song, resource, playing)
                            }
                        }
                    },
                    recommendations = { _, _ -> emptyList() },
                    appendRecommendations = { _, _ -> },
                )
            return radio.start(song, localPrepared, manager.state.value.copy(positionMs = 42_000L)).also { transitions.job = it }
        }

        fun select(
            index: Int,
            playing: Boolean = true,
            positionMs: Long = 0L,
        ): Job {
            val generation = transitions.begin()
            resumePositionMs = positionMs
            return scope
                .launch {
                    val song = queue.items.value[index]
                    queue.skipTo(index)
                    val result = playback.replaceTrack(song, positionMs, playing) { transitions.generation == generation }
                    when (result) {
                        DesktopCastTrackResult.LOADED -> {
                            published += song
                        }

                        DesktopCastTrackResult.LOCAL -> {
                            val resource = fetch(song, null) {}
                            if (resource != null) prepareLocal(song, resource, playing)
                        }

                        DesktopCastTrackResult.SUPERSEDED -> {
                            Unit
                        }
                    }
                }.also { transitions.job = it }
        }

        fun advance(): Job {
            var job: Job? = null
            resumePositionMs = 0L
            assertTrue(
                transitions.advance(manager.state.value.copy(hasEnded = true), queue.state.value) {
                    val generation = transitions.begin()
                    job =
                        scope
                            .launch {
                                val next =
                                    resolveNextQueueOccurrence(queue, { transitions.generation == generation }) { song ->
                                        playback.resolveTrack(song) { transitions.generation == generation }
                                    } ?: return@launch
                                val resource = next.second
                                if (resource == null) {
                                    assertFalse(receiver.playing)
                                    localSelections += next.first
                                } else {
                                    when (
                                        playback.replaceTrack(
                                            next.first,
                                            0L,
                                            true,
                                            resource,
                                        ) { transitions.generation == generation }
                                    ) {
                                        DesktopCastTrackResult.LOADED -> published += next.first
                                        DesktopCastTrackResult.LOCAL -> prepareLocal(next.first, resource, true)
                                        DesktopCastTrackResult.SUPERSEDED -> Unit
                                    }
                                }
                            }.also { transitions.job = it }
                },
            )
            return requireNotNull(job)
        }

        fun assertLoaded(song: SongItem) {
            val state = manager.state.value
            assertTrue(state.isConnected)
            assertEquals(receiver.device, state.device)
            assertEquals(song.id, state.mediaVideoId)
            assertEquals(song, queue.currentItem())
            assertEquals(queue.state.value.entries[queue.currentIndex.value], state.queueEntry)
            assertEquals(song.title, receiver.loadedTitles.last())
        }

        suspend fun close() {
            failureObserver.cancel()
            preparationFailures.close()
            scope.cancel()
            manager.release()
            player.release()
            receiver.close()
            localFile?.let(Files::deleteIfExists)
        }
    }

    companion object {
        private fun silentWav(): ByteArray =
            ByteBuffer
                .allocate(44 + 960_000)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray())
                    putInt(capacity() - 8)
                    put("WAVEfmt ".toByteArray())
                    putInt(16)
                    putShort(1)
                    putShort(1)
                    putInt(8_000)
                    putInt(16_000)
                    putShort(2)
                    putShort(16)
                    put("data".toByteArray())
                    putInt(960_000)
                }.array()

        private fun outputLine(): SourceDataLine {
            var format = AudioFormat(8_000f, 16, 1, true, false)
            var open = false
            return Proxy.newProxyInstance(SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)) { _, method, args ->
                when (method.name) {
                    "open" -> {
                        format = args!![0] as AudioFormat
                        open = true
                        null
                    }

                    "close" -> {
                        open = false
                        null
                    }

                    "isOpen" -> {
                        open
                    }

                    "isRunning", "isActive", "isControlSupported" -> {
                        false
                    }

                    "available", "getFramePosition" -> {
                        0
                    }

                    "getBufferSize" -> {
                        4_096
                    }

                    "getLongFramePosition", "getMicrosecondPosition" -> {
                        0L
                    }

                    "getFormat" -> {
                        format
                    }

                    "write" -> {
                        Thread.sleep(5)
                        args!![2] as Int
                    }

                    else -> {
                        null
                    }
                }
            } as SourceDataLine
        }

        private fun testRenderer(
            rate: Int,
            channels: Int,
        ): AudioRenderer {
            val silenceSettings: () -> Pair<Boolean, Boolean> = { false to false }
            val equalizer: () -> EqualizerProfile? = { null }
            return AudioRenderer::class.java
                .getConstructor(
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    SourceDataLine::class.java,
                    Function0::class.java,
                    Function0::class.java,
                ).newInstance(rate, channels, outputLine(), silenceSettings, equalizer)
        }

        private fun resource(song: SongItem) =
            PlaybackResource(
                song.id,
                "https://r1.googlevideo.com/videoplayback",
                emptyMap(),
                null,
                null,
                mimeType = "audio/mp4",
                contentLengthBytes = 1024L,
            )
    }

    private class Receiver : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val actions = CopyOnWriteArrayList<String>()
        val loadedTitles = CopyOnWriteArrayList<String>()

        @Volatile var onPlay: () -> Unit = {}

        @Volatile var position = "00:00:00"

        @Volatile var playing = false

        @Volatile var descriptorRequests = 0
        val device =
            CastDevice(
                "test",
                "Speaker",
                CastProtocol.SONOS,
                "127.0.0.1",
                server.address.port,
                "http://127.0.0.1:${server.address.port}/descriptor",
            )

        init {
            server.createContext("/") { exchange ->
                val action =
                    exchange.requestHeaders
                        .getFirst("SOAPAction")
                        ?.substringAfter('#')
                        ?.trim('"')
                if (action != null) actions += action
                val request = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                if (action == "SetAVTransportURI") {
                    Regex("&lt;dc:title&gt;(.*?)&lt;/dc:title&gt;")
                        .find(request)
                        ?.groupValues
                        ?.get(1)
                        ?.let(loadedTitles::add)
                }
                val response =
                    if (action == null) {
                        descriptorRequests++
                        """<root><device><friendlyName>Speaker</friendlyName><UDN>test</UDN><manufacturer>Sonos</manufacturer><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/control</controlURL></service></serviceList></device></root>"""
                    } else {
                        when (action) {
                            "Play" -> {
                                playing = true
                                onPlay()
                            }

                            "Stop", "Pause" -> {
                                playing = false
                            }
                        }
                        when (action) {
                            "GetTransportInfo" -> {
                                val state = if (playing) "PLAYING" else "STOPPED"
                                "<response><CurrentTransportState>$state</CurrentTransportState></response>"
                            }

                            "GetPositionInfo" -> {
                                "<response><RelTime>$position</RelTime><TrackDuration>00:03:00</TrackDuration></response>"
                            }

                            else -> {
                                "<response/>"
                            }
                        }
                    }
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }
}
