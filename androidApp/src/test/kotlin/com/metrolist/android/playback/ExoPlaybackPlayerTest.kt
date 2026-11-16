package com.metrolist.android.playback

import android.app.Application
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.deviceconnect.DcLocalPlaybackState
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SearchResultPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.playback.ffmpeg.SabrInputStream
import com.metrolist.shared.playback.playbackCrossfadeGains
import com.metrolist.shared.playback.playbackNormalizationGain
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class ExoPlaybackPlayerTest {
    private lateinit var player: ExoPlaybackPlayer

    @get:Rule val temporaryFolder = TemporaryFolder()

    @Before
    fun createPlayer() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
    }

    @After
    fun releasePlayer() {
        if (::player.isInitialized) player.releaseAndAwaitScope()
        Dispatchers.resetMain()
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun cancellingASilentPreloadDoesNotNotifySystemAudioEffects() {
        val context = RuntimeEnvironment.getApplication()
        val incoming =
            ExoPlaybackPlayer::class.java
                .getDeclaredMethod("createPlayer", Int::class.javaPrimitiveType, AndroidPlaybackPlayerRole::class.java)
                .apply { isAccessible = true }
                .invoke(player, 2_000, AndroidPlaybackPlayerRole.SECONDARY) as ExoPlayer
        incoming.setAudioSessionId(42)
        ReflectionHelpers.setField(player, "nextPlayer", incoming)
        val sessionId = incoming.audioSessionId
        assertTrue(sessionId > 0)
        player.cancelNext()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(
            shadowOf(context).broadcastIntents.any {
                it.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0) == sessionId
            },
        )
    }

    @Test
    fun terminalSabrFailureStartsRecoveryBeforeBufferedBytesDrain() =
        runBlocking {
            for (kind in SabrFailureKind.entries) {
                val error = SabrProtocolException("Producer failed", kind)
                val source =
                    SabrInputStream(1024L, { _, _ ->
                        flow {
                            emit(SabrChunk(ByteArray(512), 0L, true, null, null, null))
                            throw error
                        }
                    })
                val failures = mutableListOf<AndroidPlaybackRecoveryRequest>()
                val collector = CoroutineScope(Dispatchers.Main.immediate)
                collector.launch { player.streamFailure.collect { failures += it } }
                try {
                    withTimeout(5_000) { while (source.streamFailure == null) delay(10) }
                    ReflectionHelpers.getField<MutableStateFlow<Long>>(player, "playbackInstanceIdState").value = 42L
                    ReflectionHelpers.setField(player, "currentResource", PlaybackResource("song", "sabr://song", emptyMap(), null, null))
                    ReflectionHelpers.setField(player, "currentSongValue", song())
                    ReflectionHelpers.setField(player, "activeAudioSource", source)
                    val poll =
                        ExoPlaybackPlayer::class.java
                            .getDeclaredMethod("updateProgressState", Boolean::class.javaPrimitiveType)
                            .apply { isAccessible = true }
                    poll.invoke(player, false)
                    assertEquals(1, failures.size)
                    assertEquals(
                        kind,
                        failures
                            .single()
                            .failure.cause
                            ?.let { (it as SabrProtocolException).kind },
                    )
                    assertTrue(failures.single().failure.shouldExcludePlaybackProfile)
                    assertEquals(null, underlyingPlayer().playerError)
                    assertEquals(512, source.readNBytes(512).size)
                    player.finishPlaybackRecovery(failures.single().transitionId)
                    poll.invoke(player, false)
                    assertEquals(1, failures.size)
                } finally {
                    collector.cancel()
                    ReflectionHelpers.setField(player, "activeAudioSource", null)
                    source.close()
                }
            }
        }

    @Test
    fun crossfadeVolumeLogsRetainEarlierDiagnosticsAndTheLatestGains() =
        runTest {
            val incoming =
                ExoPlaybackPlayer::class.java
                    .getDeclaredMethod("createPlayer", Int::class.javaPrimitiveType, AndroidPlaybackPlayerRole::class.java)
                    .apply { isAccessible = true }
                    .invoke(player, 2_000, AndroidPlaybackPlayerRole.SECONDARY) as ExoPlayer
            ReflectionHelpers.setField(player, "nextPlayer", incoming)
            player.setCrossfadeVolumes(100f, 0f)
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            PlaybackEventLog.clear()
            PlaybackEventLog.info(PlaybackEventKind.PLAYER, "Seek before crossfade.")

            for (step in 1..625) {
                val progress = step / 625f
                player.setCrossfadeVolumes((1f - progress) * 100f, progress * 100f)
                advanceTimeBy(16L)
                runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
            }
            advanceTimeBy(501L)
            runCurrent()

            val events = PlaybackEventLog.events.value
            assertTrue(events.any { it.message == "Seek before crossfade." })
            val outputEvents = events.filter { it.message.startsWith("Android player output") }
            assertTrue("A ten-second ramp must not flood the event buffer", outputEvents.size <= 44)
            val latest = outputEvents.groupBy { it.details["playerId"] }.mapValues { it.value.last().details["playerVolume"] }
            assertEquals(2, latest.size)
            assertEquals("0.0", latest[Integer.toHexString(System.identityHashCode(underlyingPlayer()))])
            assertEquals("1.0", latest[Integer.toHexString(System.identityHashCode(incoming))])
        }

    @Test
    fun muteRestoreAndLifecycleLogsBypassVolumeSamplingAndReleaseCancelsPendingSamples() =
        runTest {
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            PlaybackEventLog.clear()
            val native = underlyingPlayer()
            native.volume = 0.8f
            native.volume = 0.7f
            assertTrue(PlaybackEventLog.events.value.none { it.message == "Android player output volume." })
            native.volume = 0f
            assertEquals(
                "0.0",
                PlaybackEventLog.events.value
                    .last()
                    .details["playerVolume"],
            )
            native.volume = 0.6f
            assertEquals(
                "0.6",
                PlaybackEventLog.events.value
                    .last()
                    .details["playerVolume"],
            )
            native.volume = 0.5f
            native.playWhenReady = true
            shadowOf(Looper.getMainLooper()).idle()
            val playing = PlaybackEventLog.events.value.last { it.message.startsWith("Android player output") }
            assertEquals("true", playing.details["playWhenReady"])
            assertEquals("0.5", playing.details["playerVolume"])
            native.pause()
            shadowOf(Looper.getMainLooper()).idle()
            val paused = PlaybackEventLog.events.value.last { it.message.startsWith("Android player output") }
            assertEquals("false", paused.details["playWhenReady"])
            native.volume = 0.4f
            ExoPlaybackPlayer::class.java
                .getDeclaredMethod("releasePlayer", ExoPlayer::class.java)
                .apply { isAccessible = true }
                .invoke(player, native)
            val count = PlaybackEventLog.events.value.size
            assertTrue(PlaybackEventLog.events.value.any { it.message == "Android player output released." })
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(count, PlaybackEventLog.events.value.size)
        }

    @Test
    fun releaseWaitsForMainScopeCancellationBeforeRestoringTheDispatcher() {
        player.releaseAndAwaitScope()
        Dispatchers.resetMain()
        player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
        val scope = ReflectionHelpers.getField<CoroutineScope>(player, "scope")
        var cleanupFinished = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                cleanupFinished = true
            }
        }

        player.releaseAndAwaitScope()

        assertTrue(cleanupFinished)
        assertTrue(scope.coroutineContext.job.isCompleted)
    }

    @Test
    fun controllerVolumeFeedbackPreservesLocalOutputVolume() {
        player.setVolume(0.3f)
        player.setAudioOutputEnabled(false)
        player.setControllerPlayback(DcLocalPlaybackState(volume = 0.8f))
        assertEquals(0.8f, player.volume.value, 0f)
        assertEquals(0f, underlyingPlayer().volume, 0f)
        player.setControllerPlayback(DcLocalPlaybackState(volume = 0.6f))
        assertEquals(0.6f, player.volume.value, 0f)
        player.setControllerPlayback(null)
        player.setAudioOutputEnabled(true)
        assertEquals(0.3f, player.volume.value, 0f)
        assertEquals(0.3f, underlyingPlayer().volume, 0f)
    }

    @Test
    fun connectedQueueTransitionsReplaceTheRetiredTrackWithoutWarmingAnotherPlayer() =
        runTest {
            val first = song().copy(id = "first")
            val second = song().copy(id = "second")
            val songs = listOf(first, second, second)
            player.setQueue(songs)
            player.setVolume(0.6f)
            player.setAudioOutputEnabled(true)
            val primary = underlyingPlayer()
            var previousInstance = player.currentPlaybackInstanceId
            for ((index, track) in songs.withIndex()) {
                player.restoreQueue(player.queueSnapshot().copy(currentIndex = index), currentTrackChanged = true)
                player.pause()
                val prepared =
                    player.prepareAndPlay(
                        song = track,
                        resource = PlaybackResource(track.id, "file:///queued-audio", emptyMap(), null, null),
                        positionMs = 0L,
                        resumePlayback = true,
                        playWhenReadyProvider = { true },
                    )

                assertTrue("Queue selection must not wait for a speculative replacement player", prepared)
                assertSame(primary, underlyingPlayer())
                assertEquals(0.6f, underlyingPlayer().volume, 0f)
                assertEquals(0.6f, player.volume.value, 0f)
                assertEquals(track.id, player.currentSong.value?.id)
                assertEquals(index, player.currentMediaItemIndex.value)
                assertEquals(0L, player.getRealtimePositionMs())
                assertTrue(player.wantsPlayback)
                assertTrue(player.currentPlaybackInstanceId > previousInstance)
                previousInstance = player.currentPlaybackInstanceId
            }
            // Progress sampling polls while playback is wanted, so stop it before runTest drains the scheduler.
            player.pause()
        }

    @Test
    fun outputRecoveryCreatesFreshSessionAndRetainsPausedQueuePositionAndGains() = verifyReplacement(freshAudioSession = true)

    @Test
    fun ordinaryWarmReplacementKeepsItsOwnSessionAndRetainsPausedQueuePositionAndGains() = verifyReplacement(freshAudioSession = false)

    private fun verifyReplacement(freshAudioSession: Boolean) {
        player.releaseAndAwaitScope()
        Dispatchers.resetMain()
        player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
        runBlocking {
            val songs = listOf(song(), song(), song().copy(id = "last"))
            player.setQueue(songs, startIndex = 1, preparePlayback = false)
            player.setVolume(0.6f)
            player.setSleepTimerGain(0.5f)
            val resource = downloadedResource()
            assertTrue(player.prepareAndPlay(songs[1], resource, positionMs = 500, resumePlayback = false))
            val previous = underlyingPlayer()
            val instance = player.currentPlaybackInstanceId
            val queue = player.queueSnapshot()
            val replacement =
                async(Dispatchers.Main.immediate) {
                    player.prepareAndPlay(
                        songs[1],
                        resource,
                        positionMs = player.getRealtimePositionMs(),
                        resumePlayback = false,
                        positionMsProvider = player::getRealtimePositionMs,
                        playWhenReadyProvider = { player.wantsPlayback },
                        recoveryPlaybackInstanceId = instance,
                        freshAudioSession = freshAudioSession,
                    )
                }
            // Media3 loads the local source on its playback thread, then posts readiness to the main looper.
            for (attempt in 0 until 500) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
                if (replacement.isCompleted) break
                Thread.sleep(1)
            }
            assertTrue("The downloaded source must warm without a network request", replacement.isCompleted)
            assertTrue(replacement.await())
            val current = underlyingPlayer()
            assertNotSame(previous, current)
            assertTrue(current.audioSessionId > 0)
            assertTrue(current.audioSessionId != previous.audioSessionId)
            assertEquals(current.audioSessionId, player.getAudioSessionId())
            assertEquals(queue, player.queueSnapshot())
            assertSame(resource, player.currentPlaybackResource.value)
            assertEquals(500L, player.getRealtimePositionMs())
            assertFalse(player.wantsPlayback)
            assertEquals(0.3f, current.volume, 0.001f)
            assertEquals(
                AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
                current.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode,
            )
        }
    }

    @Test
    fun committedPreloadUpdatesVideoModeBeforeSongCollectorsForNextEofAndCrossfade() {
        player.releaseAndAwaitScope()
        Dispatchers.resetMain()
        val audio = downloadedResource()
        for (advance in listOf("next", "eof", "crossfade", "hidden-video")) {
            player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
            Settings.shared.videoViewerVisible = advance != "hidden-video"
            val collectorScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
            try {
                runBlocking {
                    val first = song().copy(musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
                    val second = first.copy(id = "second")
                    player.setQueue(listOf(first, second), preparePlayback = false)
                    val resource = audio.copy(videoUrl = audio.url)
                    val nextResource = resource.copy(videoId = second.id)
                    assertTrue(player.prepareAndPlay(first, resource, resumePlayback = false))
                    val controller =
                        AndroidVideoModeController(
                            RuntimeEnvironment.getApplication(),
                            player,
                            collectorScope,
                            MusicVideoResolver(counterpartLookup = { null }, search = { _, _ -> SearchResultPage(emptyList()) }),
                            { it() },
                            { _, _ -> null },
                            { error(it) },
                            isDownloaded = { false },
                        )
                    controller.onQueuePlaybackStarted(first, Settings.shared.videoViewerVisible, resource)
                    val collector = collectorScope.launch { player.currentSong.collect(controller::onSongChanged) }
                    var commits = 0
                    player.onPreloadedPlaybackCommitted = { song, committedResource ->
                        assertEquals(first.id, player.currentSong.value?.id)
                        assertSame(nextResource, committedResource)
                        controller.onPreloadedPlaybackCommitted(song, committedResource)
                        commits++
                    }
                    val preparation =
                        async(Dispatchers.Main.immediate) {
                            player.prepareNextTrack(nextResource, startPaused = true, queueSongId = second.id)
                        }
                    for (attempt in 0 until 500) {
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
                        if (preparation.isCompleted) break
                        Thread.sleep(1)
                    }
                    assertTrue(preparation.isCompleted)
                    val token = checkNotNull(preparation.await())
                    assertFalse(player.swapToPreloadedQueueNext(token.copy(generation = token.generation + 1), nextResource, second))
                    assertEquals(0, commits)
                    player.resume()
                    when (advance) {
                        "next", "hidden-video" -> {
                            player.consumePreloadedNext = { player.swapToPreloadedQueueNext(token, nextResource, second) }
                            player.skipToNext()
                        }

                        "eof" -> {
                            player.startPreloadedNextAtEof = { player.swapToPreloadedQueueNext(token, nextResource, second) }
                            ExoPlaybackPlayer::class.java
                                .getDeclaredMethod(
                                    "completePlaybackAtEof",
                                ).apply { isAccessible = true }
                                .invoke(player)
                        }

                        else -> {
                            assertTrue(player.startNextPlayback())
                            player.setCrossfadeVolumes(50f, 50f)
                            assertTrue(player.swapToPreloadedQueueNext(token, nextResource, second))
                        }
                    }
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(1, commits)
                    assertEquals(second.id, player.currentSong.value?.id)
                    assertEquals(advance != "hidden-video", controller.isVideoModeActive.value)
                    assertEquals(advance != "hidden-video", controller.isVideoViewerVisible.value)
                    collector.cancel()
                }
            } finally {
                collectorScope.cancel()
                player.releaseAndAwaitScope()
            }
        }
    }

    @Test
    fun gaplessPreloadEnqueuesTheNextSourceAndManualNextKeepsThePlayerAndSession() =
        runBlocking {
            player.releaseAndAwaitScope()
            Dispatchers.resetMain()
            player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
            val first = song()
            val second = first.copy(id = "second")
            player.setQueue(listOf(first, second), preparePlayback = false)
            val resource = downloadedResource()
            assertTrue(player.prepareAndPlay(first, resource, resumePlayback = false))
            val native = underlyingPlayer().also(::awaitPlaybackReady)
            val sessionId = native.audioSessionId
            val instanceId = player.currentPlaybackInstanceId
            val nextResource = resource.copy(videoId = second.id)
            val token = checkNotNull(player.prepareNextTrack(nextResource, startPaused = true, queueSongId = second.id))

            assertEquals(2, native.mediaItemCount)
            assertEquals(0, native.currentMediaItemIndex)
            assertSame(nextResource, native.getMediaItemAt(1).localConfiguration?.tag)
            assertEquals(first, player.currentSong.value)
            assertEquals(instanceId, player.currentPlaybackInstanceId)
            assertTrue(player.isPreloadedPlaybackActive(token))
            assertEquals(null, ReflectionHelpers.getField<ExoPlayer?>(player, "nextPlayer"))
            assertEquals(
                AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
                native.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode,
            )

            player.consumePreloadedNext = { player.swapToPreloadedQueueNext(token, nextResource, second) }
            val stalls = ReflectionHelpers.getField<OffloadSleepStallTracker>(player, "offloadSleepStallTracker")
            player.resume()
            stalls.onSleepingForOffload(nowMs = 0L, outputPosition = 123L)
            player.skipToNext()

            assertSame(native, underlyingPlayer())
            assertEquals(sessionId, native.audioSessionId)
            assertEquals(1, native.mediaItemCount)
            assertEquals(second, player.currentSong.value)
            assertEquals(1, player.currentMediaItemIndex.value)
            assertEquals(token.playerInstanceId, player.currentPlaybackInstanceId)
            assertFalse(player.isPreloadedPlaybackActive(token))
            assertTrue(ReflectionHelpers.getField<Boolean>(stalls, "sleepingForOffload"))
        }

    @Test
    fun retryablePlaylistLoadErrorsKeepTheQueuedSourceAndDoNotRecoverTheCurrentTrack() =
        runBlocking {
            player.releaseAndAwaitScope()
            Dispatchers.resetMain()
            player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
            val first = song()
            val second = first.copy(id = "second")
            player.setQueue(listOf(first, second), preparePlayback = false)
            val resource = downloadedResource()
            assertTrue(player.prepareAndPlay(first, resource, resumePlayback = false))
            val native = underlyingPlayer().also(::awaitPlaybackReady)
            val instanceId = player.currentPlaybackInstanceId
            val failures = mutableListOf<Throwable>()
            val recoveries = mutableListOf<AndroidPlaybackRecoveryRequest>()
            val collector = CoroutineScope(Dispatchers.Main.immediate)
            collector.launch { player.streamFailure.collect { recoveries += it } }
            try {
                val nextResource = resource.copy(videoId = second.id)
                val token =
                    checkNotNull(
                        player.prepareNextTrack(nextResource, startPaused = true, queueSongId = second.id, onFailure = { failures += it }),
                    )
                val listener = ReflectionHelpers.getField<AnalyticsListener>(player, "networkLoadListener")
                for (error in listOf(SocketTimeoutException("Read timed out"), IOException("Retry pending"))) {
                    listener.onLoadError(
                        AnalyticsListener.EventTime(0L, native.currentTimeline, 1, null, 0L, native.currentTimeline, 0, null, 0L, 0L),
                        LoadEventInfo.Builder(0L, DataSpec(Uri.parse(resource.url)), 0L).build(),
                        MediaLoadData(C.DATA_TYPE_MEDIA),
                        error,
                        false,
                    )
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(2, native.mediaItemCount)
                    assertSame(nextResource, native.getMediaItemAt(1).localConfiguration?.tag)
                    assertTrue(player.isPreloadedPlaybackActive(token))
                    assertTrue(failures.isEmpty())
                    assertTrue(recoveries.isEmpty())
                    assertEquals(instanceId, player.currentPlaybackInstanceId)
                    assertSame(resource, player.currentPlaybackResource.value)
                }
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun failedPlaylistPreloadPreservesTheCurrentTrackAndRestoresItsOffloadEligibility() =
        runBlocking {
            player.releaseAndAwaitScope()
            Dispatchers.resetMain()
            player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
            Settings.shared.audioNormalization = true
            val first = song()
            val second = first.copy(id = "second")
            player.setQueue(listOf(first, second), preparePlayback = false)
            val resource = downloadedResource()
            assertTrue(player.prepareAndPlay(first, resource, resumePlayback = false))
            val native = underlyingPlayer().also(::awaitPlaybackReady)
            val instanceId = player.currentPlaybackInstanceId
            val error = IOException("Next source failed")
            val failures = mutableListOf<Throwable>()
            val token =
                checkNotNull(
                    player.prepareNextTrack(
                        resource.copy(videoId = second.id, perceptualLoudnessDb = -3.0),
                        startPaused = true,
                        queueSongId = second.id,
                        onFailure = { failures += it },
                    ),
                )
            assertEquals(
                AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
                native.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode,
            )
            val listener = ReflectionHelpers.getField<AnalyticsListener>(player, "networkLoadListener")
            listener.onLoadError(
                AnalyticsListener.EventTime(0L, native.currentTimeline, 1, null, 0L, native.currentTimeline, 0, null, 0L, 0L),
                LoadEventInfo(0L, DataSpec(Uri.parse(resource.url)), 0L),
                MediaLoadData(C.DATA_TYPE_MEDIA),
                error,
                true,
            )

            assertEquals(listOf(error), failures)
            assertEquals(1, native.mediaItemCount)
            assertFalse(player.isPreloadedPlaybackActive(token))
            assertSame(native, underlyingPlayer())
            assertSame(resource, player.currentPlaybackResource.value)
            assertEquals(instanceId, player.currentPlaybackInstanceId)
            assertEquals(first, player.currentSong.value)
            assertEquals(null, player.loadError.value)
            assertEquals(
                AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
                native.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode,
            )
        }

    @Test
    fun nativeGaplessTransitionCommitsTheNextOccurrenceWithoutWaitingForPlayerEnded() =
        runBlocking {
            player.releaseAndAwaitScope()
            Dispatchers.resetMain()
            player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
            Settings.shared.crossfadeEnabled = true
            val first = song().copy(album = AlbumTiny("album", "Album"))
            val second = first.copy(id = "second")
            val third = first.copy(id = "third")
            player.setQueue(listOf(first, second, third), preparePlayback = false)
            val resource = downloadedResource()
            assertTrue(player.prepareAndPlay(first, resource, resumePlayback = false))
            val native = underlyingPlayer().also(::awaitPlaybackReady)
            val sessionId = native.audioSessionId
            val instanceId = player.currentPlaybackInstanceId
            val nextResource = resource.copy(videoId = second.id)
            val token = checkNotNull(player.prepareNextTrack(nextResource, startPaused = true, queueSongId = second.id))
            var commits = 0
            player.onPreloadedPlaybackCommitted = { song, committed ->
                assertEquals(first, player.currentSong.value)
                assertEquals(second, song)
                assertSame(nextResource, committed)
                commits++
            }
            val completions = mutableListOf<Long>()
            val collector = CoroutineScope(Dispatchers.Main.immediate)
            collector.launch {
                player.onEOFPlaybackInstance.collect { completedInstance ->
                    completions += completedInstance
                    player.swapToPreloadedQueueNext(token, nextResource, second)
                }
            }
            native.seekTo(1, 0L)
            val listener = ReflectionHelpers.getField<Player.Listener>(player, "playbackListener")
            listener.onPositionDiscontinuity(
                Player.PositionInfo(null, 0, native.getMediaItemAt(0), null, 0, 20_000L, 20_000L, C.INDEX_UNSET, C.INDEX_UNSET),
                Player.PositionInfo(null, 1, native.getMediaItemAt(1), null, 1, 0L, 0L, C.INDEX_UNSET, C.INDEX_UNSET),
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
            )
            shadowOf(Looper.getMainLooper()).idle()
            collector.cancel()

            assertEquals(listOf(instanceId), completions)
            assertTrue(native.playbackState != Player.STATE_ENDED)
            assertSame(native, underlyingPlayer())
            assertEquals(sessionId, native.audioSessionId)
            assertEquals(second, player.currentSong.value)
            assertEquals(1, player.currentMediaItemIndex.value)
            assertEquals(1, native.mediaItemCount)
            assertEquals(1, commits)

            val thirdToken =
                checkNotNull(player.prepareNextTrack(resource.copy(videoId = third.id), startPaused = true, queueSongId = third.id))
            assertEquals(2, native.mediaItemCount)
            player.removeQueueItem(2)
            assertEquals(1, native.mediaItemCount)
            assertFalse(player.isPreloadedPlaybackActive(thirdToken))
            assertEquals(second, player.currentSong.value)
            assertSame(native, underlyingPlayer())
        }

    @Test
    fun cancellingAPreloadAfterNativeTransitionCommitsItsPlaybackIdentityAndSource() =
        runBlocking {
            player.releaseAndAwaitScope()
            Dispatchers.resetMain()
            player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
            Settings.shared.audioNormalization = true
            val first = song()
            val second = first.copy(id = "second")
            val selected =
                second.copy(
                    id = "selected-audio",
                    title = "Selected audio",
                    canonicalTitle = "Selected audio",
                    canonicalArtists = second.artists,
                )
            player.setQueue(listOf(first, second, first.copy(id = "third")), preparePlayback = false)
            val occurrenceKey =
                player.queueState.value.entries[1]
                    .key
            val resource = downloadedResource().copy(perceptualLoudnessDb = -17.0)
            assertTrue(player.prepareAndPlay(first, resource, resumePlayback = false))
            val native = underlyingPlayer().also(::awaitPlaybackReady)
            val previousInstance = player.currentPlaybackInstanceId
            val nextResource = resource.copy(videoId = selected.id, perceptualLoudnessDb = -3.0)
            val token =
                checkNotNull(player.prepareNextTrack(nextResource, startPaused = true, queueSongId = second.id, playbackSong = selected))
            val reporter = ReflectionHelpers.getField<Any>(player, "nextPlaybackReporter")
            var commits = 0
            player.onPreloadedPlaybackCommitted = { committedSong, committedResource ->
                assertEquals(selected, committedSong)
                assertSame(nextResource, committedResource)
                commits++
            }
            val source = SabrInputStream(0L, { _, _ -> flow { } }, startImmediately = false, tempDirectory = temporaryFolder.root)
            ReflectionHelpers.setField(player, "nextAudioSource", source)
            val completions = mutableListOf<Long>()
            val collector = CoroutineScope(Dispatchers.Main.immediate)
            collector.launch { player.onEOFPlaybackInstance.collect { completions += it } }
            try {
                val oldPosition =
                    Player.PositionInfo(
                        null,
                        0,
                        native.getMediaItemAt(0),
                        null,
                        0,
                        20_000L,
                        20_000L,
                        C.INDEX_UNSET,
                        C.INDEX_UNSET,
                    )
                val newPosition = Player.PositionInfo(null, 1, native.getMediaItemAt(1), null, 1, 0L, 0L, C.INDEX_UNSET, C.INDEX_UNSET)
                native.seekTo(1, 750L)
                awaitPlaybackReady(native)
                native.addListener(
                    object : Player.Listener {
                        override fun onTimelineChanged(
                            timeline: Timeline,
                            reason: Int,
                        ) {
                            if (native.mediaItemCount == 1 && ReflectionHelpers.getField<Int>(player, "nextPlaylistIndex") == 0) {
                                player.cancelNext()
                            }
                        }
                    },
                )
                ReflectionHelpers
                    .getField<Player.Listener>(player, "playbackListener")
                    .onPositionDiscontinuity(oldPosition, newPosition, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
                player.cancelNext()
                shadowOf(Looper.getMainLooper()).idle()

                assertEquals(1, native.mediaItemCount)
                assertEquals(0, native.currentMediaItemIndex)
                assertSame(nextResource, native.currentMediaItem?.localConfiguration?.tag)
                assertEquals(750L, native.currentPosition)
                assertEquals(Player.STATE_READY, native.playbackState)
                assertFalse(player.isPreloadedPlaybackActive(token))
                assertSame(nextResource, player.currentPlaybackResource.value)
                assertEquals(token.playerInstanceId, player.currentPlaybackInstanceId)
                assertTrue(previousInstance != player.currentPlaybackInstanceId)
                assertEquals(selected, player.currentSong.value)
                assertEquals(selected, ReflectionHelpers.getField<SongItem>(player, "currentSongValue"))
                assertEquals(1, player.currentMediaItemIndex.value)
                assertEquals(
                    occurrenceKey,
                    player.queueState.value.entries[player.currentMediaItemIndex.value]
                        .key,
                )
                assertEquals(selected, player.songQueue.value[1])
                assertSame(reporter, ReflectionHelpers.getField<Any>(player, "playbackReporter"))
                assertSame(nextResource, normalizationProcessors().getValue(native).resource)
                assertEquals(
                    playbackNormalizationGain(nextResource.loudnessDb, true, perceptualLoudnessDb = nextResource.perceptualLoudnessDb),
                    normalizationProcessors().getValue(native).normalizationGain,
                    0.00001f,
                )
                assertEquals(1, commits)
                assertTrue(completions.all { it == previousInstance })
                assertFalse(player.swapToPreloadedQueueNext(token, nextResource, selected))
                assertSame(source, ReflectionHelpers.getField<SabrInputStream>(player, "activeAudioSource"))
                assertFalse(ReflectionHelpers.getField<Boolean>(source, "closed"))
                player.cancelNext()
                assertEquals(1, commits)
                assertSame(nextResource, player.currentPlaybackResource.value)
                player.stop()
                assertTrue(ReflectionHelpers.getField<Boolean>(source, "closed"))
            } finally {
                collector.cancel()
                source.close()
            }
        }

    @Test
    fun cancellingAPlayingCounterpartPreservesQueueMetadataEditsAndClears() =
        runBlocking {
            val audio = downloadedResource()
            val canonicalArtist = ArtistTiny("audio-artist", "Canonical artist")
            for ((editBeforePreparation, clearEdits) in listOf(false to false, false to true, true to true)) {
                player.releaseAndAwaitScope()
                Dispatchers.resetMain()
                player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
                val first = song()
                val second = first.copy(id = "second", customTitle = "Old title", customArtist = "Old artist", queueSourceTitle = "Album")
                val selected =
                    second.copy(
                        id = "selected-audio",
                        title = "Old title",
                        artists = listOf(canonicalArtist.copy(name = "Old artist")),
                        canonicalTitle = "Canonical audio",
                        canonicalArtists = listOf(canonicalArtist),
                    )
                val edited =
                    second.copy(
                        customTitle = if (clearEdits) null else "Latest title",
                        customArtist = if (clearEdits) null else "Latest artist",
                        queueSourceTitle = "Added manually",
                    )
                player.setQueue(listOf(first, second), preparePlayback = false)
                val occurrenceKey =
                    player.queueState.value.entries[1]
                        .key
                assertTrue(player.prepareAndPlay(first, audio, resumePlayback = false))
                val native = underlyingPlayer().also(::awaitPlaybackReady)
                if (editBeforePreparation) player.updateQueueItems(mapOf(1 to edited))
                val resource = audio.copy(videoId = selected.id)
                val token =
                    checkNotNull(
                        player.prepareNextTrack(
                            resource,
                            startPaused = true,
                            queueSongId = second.id,
                            playbackSong = selected,
                            sourceSong = second,
                        ),
                    )
                if (!editBeforePreparation) player.updateQueueItems(mapOf(1 to edited))
                assertTrue(player.isPreloadedPlaybackActive(token))
                assertEquals(
                    occurrenceKey,
                    player.queueState.value.entries[1]
                        .key,
                )
                val oldPosition =
                    Player.PositionInfo(
                        null,
                        0,
                        native.getMediaItemAt(0),
                        null,
                        0,
                        20_000L,
                        20_000L,
                        C.INDEX_UNSET,
                        C.INDEX_UNSET,
                    )
                val newPosition = Player.PositionInfo(null, 1, native.getMediaItemAt(1), null, 1, 0L, 0L, C.INDEX_UNSET, C.INDEX_UNSET)
                native.seekTo(1, 750L)
                awaitPlaybackReady(native)
                player.startPreloadedNextAtEof = { player.cancelNext() }

                ReflectionHelpers
                    .getField<Player.Listener>(player, "playbackListener")
                    .onPositionDiscontinuity(oldPosition, newPosition, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
                shadowOf(Looper.getMainLooper()).idle()

                val promoted = checkNotNull(player.currentSong.value)
                assertEquals(selected.id, promoted.id)
                assertEquals(edited.customTitle, promoted.customTitle)
                assertEquals(edited.customArtist, promoted.customArtist)
                assertEquals(if (clearEdits) "Canonical audio" else "Latest title", promoted.title)
                assertEquals(if (clearEdits) "Canonical artist" else "Latest artist", promoted.artists.single().name)
                assertEquals("Canonical audio", promoted.canonicalTitle)
                assertEquals(listOf(canonicalArtist), promoted.canonicalArtists)
                assertEquals(edited.queueSourceTitle, promoted.queueSourceTitle)
                assertEquals(promoted, player.songQueue.value[1])
                assertEquals(1, player.currentMediaItemIndex.value)
                assertEquals(
                    occurrenceKey,
                    player.queueState.value.entries[1]
                        .key,
                )
                assertEquals(token.playerInstanceId, player.currentPlaybackInstanceId)
                assertSame(resource, player.currentPlaybackResource.value)
                assertFalse(player.isPreloadedPlaybackActive(token))
            }
        }

    @Test
    fun cancellingAPlayingPreloadRestoresThePreviousTrackWhenItsQueueOccurrenceWasRemoved() =
        runBlocking {
            player.releaseAndAwaitScope()
            Dispatchers.resetMain()
            player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
            val first = song()
            val second = first.copy(id = "second")
            player.setQueue(listOf(first, second, second), preparePlayback = false)
            val originalEntries = player.queueState.value.entries
            val resource = downloadedResource()
            assertTrue(player.prepareAndPlay(first, resource, resumePlayback = false))
            val native = underlyingPlayer().also(::awaitPlaybackReady)
            val instance = player.currentPlaybackInstanceId
            val nextResource = resource.copy(videoId = second.id)
            val token = checkNotNull(player.prepareNextTrack(nextResource, startPaused = true, queueSongId = second.id))
            val source = SabrInputStream(0L, { _, _ -> flow { } }, startImmediately = false, tempDirectory = temporaryFolder.root)
            ReflectionHelpers.setField(player, "nextAudioSource", source)
            try {
                val oldPosition =
                    Player.PositionInfo(
                        null,
                        0,
                        native.getMediaItemAt(0),
                        null,
                        0,
                        20_000L,
                        20_000L,
                        C.INDEX_UNSET,
                        C.INDEX_UNSET,
                    )
                val newPosition = Player.PositionInfo(null, 1, native.getMediaItemAt(1), null, 1, 0L, 0L, C.INDEX_UNSET, C.INDEX_UNSET)
                native.seekTo(1, 750L)
                awaitPlaybackReady(native)
                ReflectionHelpers
                    .getField<Player.Listener>(player, "playbackListener")
                    .onPositionDiscontinuity(oldPosition, newPosition, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)

                player.removeQueueItem(1)
                shadowOf(Looper.getMainLooper()).idle()

                assertEquals(1, native.mediaItemCount)
                assertSame(resource, native.currentMediaItem?.localConfiguration?.tag)
                assertSame(resource, player.currentPlaybackResource.value)
                assertEquals(first, player.currentSong.value)
                assertEquals(instance, player.currentPlaybackInstanceId)
                assertEquals(0, player.currentMediaItemIndex.value)
                assertEquals(
                    listOf(originalEntries[0].key, originalEntries[2].key),
                    player.queueState.value.entries
                        .map { it.key },
                )
                assertFalse(player.isPreloadedPlaybackActive(token))
                assertFalse(player.swapToPreloadedQueueNext(token, nextResource, second))
                assertTrue(ReflectionHelpers.getField<Boolean>(source, "closed"))
                assertTrue(
                    PlaybackEventLog.events.value.any {
                        it.message == "Android seek requested." && it.details["reason"] == "cancel_playing_preload" &&
                            it.details["toPositionMs"] == "20000"
                    },
                )
            } finally {
                source.close()
            }
        }

    @Test
    fun consecutiveGaplessTransitionsKeepOnlyTheCurrentAndPreloadedItems() =
        runBlocking {
            player.releaseAndAwaitScope()
            Dispatchers.resetMain()
            player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
            Settings.shared.audioNormalization = true
            val songs = List(6) { song().copy(id = "song-$it") }
            player.setQueue(songs, preparePlayback = false)
            val resource = downloadedResource().copy(videoId = songs.first().id, perceptualLoudnessDb = -3.0)
            assertTrue(player.prepareAndPlay(songs.first(), resource, resumePlayback = false))
            val native = underlyingPlayer().also(::awaitPlaybackReady)
            val sessionId = native.audioSessionId
            val listener = ReflectionHelpers.getField<Player.Listener>(player, "playbackListener")
            val nativeTransitions = mutableListOf<Int>()
            val nativeStates = mutableListOf<Int>()
            native.addListener(
                object : Player.Listener {
                    override fun onMediaItemTransition(
                        mediaItem: MediaItem?,
                        reason: Int,
                    ) {
                        nativeTransitions += reason
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        nativeStates += playbackState
                    }
                },
            )
            val completions = mutableListOf<Long>()
            val collector = CoroutineScope(Dispatchers.Main.immediate)
            collector.launch { player.onEOFPlaybackInstance.collect { completions += it } }
            try {
                for (index in 1..songs.lastIndex) {
                    val nextResource = resource.copy(videoId = songs[index].id, perceptualLoudnessDb = if (index % 2 == 0) -3.0 else -6.0)
                    val token = checkNotNull(player.prepareNextTrack(nextResource, startPaused = true, queueSongId = songs[index].id))
                    assertEquals(2, native.mediaItemCount)
                    assertEquals(1, ReflectionHelpers.getField<Int>(player, "nextPlaylistIndex"))
                    val completedInstance = player.currentPlaybackInstanceId
                    val oldPosition =
                        Player.PositionInfo(null, 0, native.getMediaItemAt(0), null, 0, 20_000L, 20_000L, C.INDEX_UNSET, C.INDEX_UNSET)
                    val newPosition =
                        Player.PositionInfo(null, 1, native.getMediaItemAt(1), null, 1, 0L, 0L, C.INDEX_UNSET, C.INDEX_UNSET)
                    native.seekTo(1, 750L)
                    awaitPlaybackReady(native)
                    nativeTransitions.clear()
                    nativeStates.clear()
                    player.startPreloadedNextAtEof = {
                        assertEquals(completedInstance, it)
                        assertEquals(1, native.mediaItemCount)
                        assertEquals(0, native.currentMediaItemIndex)
                        assertEquals(0, ReflectionHelpers.getField<Int>(player, "nextPlaylistIndex"))
                        assertTrue(player.startNextPlayback())
                    }

                    listener.onPositionDiscontinuity(oldPosition, newPosition, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(index, completions.size)
                    assertEquals(completedInstance, completions.last())
                    assertTrue(player.swapToPreloadedQueueNext(token, nextResource, songs[index]))
                    shadowOf(Looper.getMainLooper()).idle()

                    assertEquals(1, native.mediaItemCount)
                    assertEquals(0, native.currentMediaItemIndex)
                    assertEquals(750L, native.currentPosition)
                    assertEquals(index, player.currentMediaItemIndex.value)
                    assertEquals(token.playerInstanceId, player.currentPlaybackInstanceId)
                    assertSame(native, underlyingPlayer())
                    assertEquals(sessionId, native.audioSessionId)
                    assertSame(nextResource, native.currentMediaItem?.localConfiguration?.tag)
                    assertSame(nextResource, normalizationProcessors().getValue(native).resource)
                    assertEquals(
                        playbackNormalizationGain(
                            nextResource.loudnessDb,
                            true,
                            Settings.shared.loudnessLevel,
                            nextResource.perceptualLoudnessDb,
                        ),
                        normalizationProcessors().getValue(native).normalizationGain,
                        0.00001f,
                    )
                    assertEquals(1f, native.volume, 0f)
                    assertEquals(
                        AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
                        native.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode,
                    )
                    assertTrue(nativeTransitions.isEmpty())
                    assertFalse(nativeStates.contains(Player.STATE_ENDED))
                    assertEquals(index, completions.size)
                    assertFalse(player.isPreloadedPlaybackActive(token))
                }
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun serviceSeeksKeepTheUnstartedPlaylistPreloadNearTheEndOfTheTrack() =
        runBlocking {
            player.releaseAndAwaitScope()
            Dispatchers.resetMain()
            val context = RuntimeEnvironment.getApplication()
            player = ExoPlaybackPlayer(context)
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
            try {
                val coordinator =
                    AndroidQueueCoordinator(
                        context,
                        player,
                        scope,
                        MutableStateFlow(Player.REPEAT_MODE_OFF),
                        MutableStateFlow(false),
                        {},
                        {},
                    )
                val transitions =
                    AndroidQueueTransitions(
                        player,
                        scope,
                        AudioCacheManager(context, database),
                        MusicVideoResolver(counterpartLookup = { null }, search = { _, _ -> SearchResultPage(emptyList()) }),
                        coordinator,
                        { _, _ -> null },
                        { _, _, _, _ -> },
                        {},
                        episodeResumePosition = { _, _ -> 0L },
                    )
                coordinator.transitions = transitions
                val service = Robolectric.buildService(MusicService::class.java).get()
                ReflectionHelpers.setField(service, "player", player)
                ReflectionHelpers.setField(service, "transitions", transitions)
                val first = song()
                val second = first.copy(id = "second")
                player.setQueue(listOf(first, second), preparePlayback = false)
                val resource = downloadedResource()
                assertTrue(player.prepareAndPlay(first, resource, resumePlayback = false))
                val native = underlyingPlayer().also(::awaitPlaybackReady)
                val instance = player.currentPlaybackInstanceId
                val nextResource = resource.copy(videoId = second.id)
                val token = checkNotNull(player.prepareNextTrack(nextResource, startPaused = true, queueSongId = second.id))

                for (positionMs in listOf(5_000L, 19_000L)) {
                    service.seekTo(positionMs)
                    awaitPlaybackReady(native)

                    assertEquals(positionMs, native.currentPosition)
                    assertEquals(2, native.mediaItemCount)
                    assertEquals(0, native.currentMediaItemIndex)
                    assertSame(nextResource, native.getMediaItemAt(1).localConfiguration?.tag)
                    assertTrue(player.isPreloadedPlaybackActive(token))
                    assertEquals(instance, player.currentPlaybackInstanceId)
                    assertSame(resource, player.currentPlaybackResource.value)
                }
            } finally {
                scope.cancel()
                database.close()
            }
        }

    @Test
    fun queuedGaplessSourceSurvivesSeeksAndCancelsForPreviousOrQueueReplacement() =
        runBlocking {
            val resource = downloadedResource()
            val songs = List(3) { song().copy(id = "song-$it") }
            for (action in listOf("seek", "restart", "previous", "replace", "cancel")) {
                player.releaseAndAwaitScope()
                Dispatchers.resetMain()
                player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
                player.setQueue(songs, startIndex = 1, preparePlayback = false)
                val currentResource = resource.copy(videoId = songs[1].id)
                assertTrue(player.prepareAndPlay(songs[1], currentResource, resumePlayback = false))
                val native = underlyingPlayer().also(::awaitPlaybackReady)
                val token =
                    checkNotNull(
                        player.prepareNextTrack(resource.copy(videoId = songs[2].id), startPaused = true, queueSongId = songs[2].id),
                    )
                when (action) {
                    "seek" -> {
                        player.seekTo(6_000L)
                    }

                    "restart" -> {
                        player.seekTo(6_000L)
                        player.skipToPrevious()
                    }

                    "previous" -> {
                        player.skipToPrevious()
                    }

                    "replace" -> {
                        player.setQueue(listOf(songs[0]), preparePlayback = true)
                    }

                    else -> {
                        player.cancelNext()
                    }
                }
                shadowOf(Looper.getMainLooper()).idle()
                assertSame(native, underlyingPlayer())
                assertEquals(0, native.currentMediaItemIndex)
                assertSame(currentResource, native.currentMediaItem?.localConfiguration?.tag)
                if (action == "seek" || action == "restart") {
                    assertEquals(if (action == "seek") 6_000L else 0L, native.currentPosition)
                    assertEquals(2, native.mediaItemCount)
                    assertTrue(player.isPreloadedPlaybackActive(token))
                    player.cancelNext()
                } else {
                    assertFalse(player.isPreloadedPlaybackActive(token))
                    if (action != "cancel") {
                        assertEquals(
                            songs[0],
                            player.queuePlaybackRequests.replayCache
                                .single()
                                .song,
                        )
                    }
                }
                assertEquals(1, native.mediaItemCount)
                assertSame(currentResource, native.currentMediaItem?.localConfiguration?.tag)
            }
        }

    @Test
    fun preloadedTrackKeepsItsOwnEffectsSessionDuringOverlapAndAfterPromotion() {
        player.releaseAndAwaitScope()
        Dispatchers.resetMain()
        player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
        runBlocking {
            val first = song()
            val second = song().copy(id = "second")
            player.setQueue(listOf(first, second), preparePlayback = false)
            val audio = downloadedResource()
            val resource = audio.copy(videoUrl = audio.url)
            val nextResource = resource.copy(videoId = second.id)
            assertTrue(player.prepareAndPlay(first, resource, positionMs = 6_183L, resumePlayback = false))
            val previous = underlyingPlayer()
            val preparation =
                async(Dispatchers.Main.immediate) {
                    player.prepareNextTrack(
                        resource = nextResource,
                        expectedCurrentPlaybackInstanceId = player.currentPlaybackInstanceId,
                        expectedCurrentVideoId = first.id,
                        startPaused = true,
                        queueSongId = second.id,
                        onFailure = { throw it },
                    )
                }
            for (attempt in 0 until 500) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
                if (preparation.isCompleted) break
                Thread.sleep(1)
            }
            assertTrue("The local preload must become ready", preparation.isCompleted)
            val token = checkNotNull(preparation.await())
            val incoming =
                ExoPlaybackPlayer::class.java.getDeclaredField("nextPlayer").let {
                    it.isAccessible = true
                    it.get(player) as ExoPlayer
                }
            assertTrue(incoming.audioSessionId > 0)
            assertTrue(incoming.audioSessionId != previous.audioSessionId)
            assertEquals(6_183L, player.getRealtimePositionMs())
            assertEquals(
                AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
                previous.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode,
            )
            repeat(3) {
                player.applyAudioPreferences()
                player.setVolume(0.8f)
                assertEquals(
                    "A paused preload with its own session must not evict the primary offload output",
                    AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
                    incoming.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode,
                )
            }
            assertNotSame(normalizationProcessors()[previous], normalizationProcessors()[incoming])
            assertSame(resource, normalizationProcessors().getValue(previous).resource)
            assertSame(nextResource, normalizationProcessors().getValue(incoming).resource)
            assertFalse(
                "Preparing a silent track must not reconfigure system effects during the current song",
                shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.any {
                    it.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION &&
                        it.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0) == incoming.audioSessionId
                },
            )
            player.setVolume(0.8f)
            player.setSleepTimerGain(0.5f)
            assertTrue(player.startNextPlayback())
            player.setCrossfadeVolumes(75f, 25f)
            assertEquals(0.3f, previous.volume, 0.00001f)
            assertEquals(0.1f, incoming.volume, 0.00001f)
            val previousSession = previous.audioSessionId
            val incomingSession = incoming.audioSessionId
            val broadcasts = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents
            assertTrue(
                broadcasts.any {
                    it.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION &&
                        it.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0) == incomingSession
                },
            )
            assertFalse(
                broadcasts.any {
                    it.action == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION &&
                        it.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0) == incomingSession
                },
            )
            assertTrue(player.swapToPreloadedQueueNext(token, nextResource, second, transitionGain = 1f))
            assertSame(incoming, underlyingPlayer())
            assertEquals(incomingSession, player.getAudioSessionId())
            assertEquals(
                AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
                incoming.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode,
            )
            assertEquals(0.4f, incoming.volume, 0.00001f)
            assertSame(nextResource, normalizationProcessors().getValue(incoming).resource)
            assertTrue(
                shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.any {
                    it.action == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION &&
                        it.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0) == previousSession
                },
            )
            assertFalse(
                shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.any {
                    it.action == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION &&
                        it.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0) == incomingSession
                },
            )
            player.pause()
        }
    }

    @Test
    fun repeatedRecoveriesIgnoreStalePositionsButAllowUserSeeks() {
        player.releaseAndAwaitScope()
        Dispatchers.resetMain()
        player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
        runBlocking {
            val resource = downloadedResource()
            player.setQueue(listOf(song()), preparePlayback = false)
            assertTrue(player.prepareAndPlay(song(), resource, positionMs = 6_183L, resumePlayback = false))
            for (freshSession in listOf(false, true, false)) {
                val instance = player.currentPlaybackInstanceId
                val replacement =
                    async(Dispatchers.Main.immediate) {
                        player.prepareAndPlay(
                            song(),
                            resource,
                            positionMs = 0L,
                            resumePlayback = false,
                            positionMsProvider = { 0L },
                            recoveryPlaybackInstanceId = instance,
                            freshAudioSession = freshSession,
                        )
                    }
                for (attempt in 0 until 500) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
                    if (replacement.isCompleted) break
                    Thread.sleep(1)
                }
                assertTrue("The recovery must finish", replacement.isCompleted)
                assertTrue(replacement.await())
                assertEquals(6_183L, underlyingPlayer().currentPosition)
                assertEquals(6_183L, player.getRealtimePositionMs())
                underlyingPlayer().seekTo(0L)
                assertEquals(6_183L, player.getRealtimePositionMs())
            }
            player.seekTo(1_000L)
            assertEquals(1_000L, player.getRealtimePositionMs())
        }
    }

    @Test
    fun freshSessionRecoveryFollowsSeeksMadeWhileTheReplacementPrepares() {
        player.releaseAndAwaitScope()
        Dispatchers.resetMain()
        player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
        runBlocking {
            val resource = downloadedResource()
            player.setQueue(listOf(song()), preparePlayback = false)
            assertTrue(player.prepareAndPlay(song(), resource, positionMs = 6_183L, resumePlayback = false))
            for (target in listOf(12_000L, 2_000L)) {
                val previous = underlyingPlayer()
                val replacement =
                    async(Dispatchers.Main.immediate) {
                        player.prepareAndPlay(
                            song(),
                            resource,
                            positionMs = 6_183L,
                            resumePlayback = false,
                            positionMsProvider = { 6_183L },
                            recoveryPlaybackInstanceId = player.currentPlaybackInstanceId,
                            freshAudioSession = true,
                            seekSource = PlaybackSeekSource("recovery", mapOf("reason" to "Bluetooth audio output failure: user_seek")),
                        )
                    }
                assertFalse("The seek must happen during preparation", replacement.isCompleted)
                player.seekTo(target)
                for (attempt in 0 until 500) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
                    if (replacement.isCompleted) break
                    Thread.sleep(1)
                }
                assertTrue("The recovery must finish", replacement.isCompleted)
                assertTrue(replacement.await())
                assertNotSame(previous, underlyingPlayer())
                assertEquals(target, underlyingPlayer().currentPosition)
                assertEquals(target, player.getRealtimePositionMs())
                assertTrue(
                    PlaybackEventLog.events.value.any {
                        it.message == "Android seek requested." && it.details["seekSource"] == "recovery" &&
                            it.details["reason"] == "Bluetooth audio output failure: user_seek" &&
                            it.details["toPositionMs"] == target.toString()
                    },
                )
            }
        }
    }

    @Test
    fun failedFreshSessionPreparationKeepsOnlyTheOutgoingPlayer() {
        player.releaseAndAwaitScope()
        Dispatchers.resetMain()
        player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
        runBlocking {
            val resource = downloadedResource()
            player.setQueue(listOf(song()), preparePlayback = false)
            assertTrue(player.prepareAndPlay(song(), resource, positionMs = 6_183L, resumePlayback = false))
            player.resume()
            val outgoing = underlyingPlayer()
            val instance = player.currentPlaybackInstanceId
            val replacement =
                async(Dispatchers.Main.immediate) {
                    player.prepareAndPlay(
                        song(),
                        resource.copy(url = "file:///missing-replacement.wav"),
                        positionMs = 6_183L,
                        resumePlayback = true,
                        recoveryPlaybackInstanceId = instance,
                        freshAudioSession = true,
                    )
                }
            for (attempt in 0 until 500) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
                if (replacement.isCompleted) break
                Thread.sleep(1)
            }
            assertTrue(replacement.isCompleted)
            assertFalse(replacement.await())
            assertSame(outgoing, underlyingPlayer())
            assertSame(resource, player.currentPlaybackResource.value)
            assertEquals(instance, player.currentPlaybackInstanceId)
            assertTrue(outgoing.playWhenReady)
            assertTrue(outgoing.volume > 0f)
            player.pause()
        }
    }

    @Test
    fun retiredPlayerCallbacksAndLaterResumeCannotControlTheReplacement() {
        player.releaseAndAwaitScope()
        Dispatchers.resetMain()
        player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
        runBlocking {
            val resource = downloadedResource()
            player.setQueue(listOf(song()), preparePlayback = false)
            assertTrue(player.prepareAndPlay(song(), resource, positionMs = 6_183L, resumePlayback = false))
            val outgoing = underlyingPlayer()
            val retiredListener =
                ReflectionHelpers.getField<Player.Listener>(player, "playbackListener")
            val replacement =
                async(Dispatchers.Main.immediate) {
                    player.prepareAndPlay(
                        song(),
                        resource,
                        positionMs = 6_183L,
                        resumePlayback = false,
                        recoveryPlaybackInstanceId = player.currentPlaybackInstanceId,
                        freshAudioSession = true,
                    )
                }
            for (attempt in 0 until 500) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
                if (replacement.isCompleted) break
                Thread.sleep(1)
            }
            assertTrue(replacement.isCompleted)
            assertTrue(replacement.await())
            val incoming = underlyingPlayer()
            assertNotSame(outgoing, incoming)
            assertEquals(0f, outgoing.volume, 0f)
            assertFalse(outgoing.playWhenReady)
            PlaybackEventLog.clear()
            player.resume()
            assertTrue(incoming.playWhenReady)
            assertFalse(outgoing.playWhenReady)
            ReflectionHelpers.getField<Player.Listener>(player, "playbackListener").onIsPlayingChanged(true)

            retiredListener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
            retiredListener.onIsPlayingChanged(false)
            retiredListener.onPlaybackStateChanged(Player.STATE_ENDED)
            retiredListener.onEvents(outgoing, Player.Events(FlagSet.Builder().add(Player.EVENT_PLAY_WHEN_READY_CHANGED).build()))
            retiredListener.onPlayerError(PlaybackException("retired player", null, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
            val oldPosition = Player.PositionInfo(null, 0, MediaItem.EMPTY, null, 0, 49_000L, 49_000L, C.INDEX_UNSET, C.INDEX_UNSET)
            val newPosition = Player.PositionInfo(null, 0, MediaItem.EMPTY, null, 0, 34_000L, 34_000L, C.INDEX_UNSET, C.INDEX_UNSET)
            retiredListener.onPositionDiscontinuity(oldPosition, newPosition, Player.DISCONTINUITY_REASON_SEEK)

            assertTrue(player.wantsPlaybackState.value)
            assertTrue(player.isPlaying.value)
            assertEquals(6_183L, player.getRealtimePositionMs())
            assertTrue(PlaybackEventLog.events.value.none { it.message == "Android native position discontinuity." })
        }
    }

    @Test
    fun positionLogDistinguishesRequestedSeeksFromNativeDiscontinuities() =
        runTest {
            val track = song()
            player.setQueue(listOf(track), preparePlayback = false)
            assertTrue(
                player.prepareAndPlay(
                    track,
                    PlaybackResource(track.id, "file:///audio", emptyMap(), null, null),
                    positionMs = 49_000L,
                    resumePlayback = false,
                ),
            )
            val instance = player.currentPlaybackInstanceId.toString()
            PlaybackEventLog.clear()

            player.seekTo(
                34_000L,
                PlaybackSeekSource(
                    "lyrics_tap",
                    mapOf("lineIndex" to "7", "lineStartMs" to "34500", "offsetMs" to "500", "lyricsRetimed" to "false"),
                ),
            )
            underlyingPlayer().seekTo(66_000L)

            val events = PlaybackEventLog.events.value
            val request = events.single { it.message == "Android seek requested." }
            assertEquals(track.id, request.videoId)
            assertEquals(instance, request.details["playbackInstanceId"])
            assertEquals("49000", request.details["fromPositionMs"])
            assertEquals("34000", request.details["toPositionMs"])
            assertEquals("lyrics_tap", request.details["seekSource"])
            assertEquals("7", request.details["lineIndex"])
            assertEquals("34500", request.details["lineStartMs"])
            assertEquals("500", request.details["offsetMs"])
            assertEquals("false", request.details["lyricsRetimed"])
            val discontinuities = events.filter { it.message == "Android native position discontinuity." }
            assertEquals(listOf("34000", "66000"), discontinuities.map { it.details["toPositionMs"] })
            assertTrue(discontinuities.all { it.details["reason"] == Player.DISCONTINUITY_REASON_SEEK.toString() })
            assertTrue(discontinuities.all { it.details["playbackInstanceId"] == instance })
            assertTrue(request.id < discontinuities.first().id)
        }

    private fun awaitPlaybackReady(native: ExoPlayer) {
        for (attempt in 0 until 500) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (native.playbackState == Player.STATE_READY && native.audioSessionId > 0) return
            Thread.sleep(1)
        }
        assertEquals(Player.STATE_READY, native.playbackState)
        assertTrue(native.audioSessionId > 0)
    }

    private fun downloadedResource(): PlaybackResource {
        val audio = temporaryFolder.newFile("download.wav")
        val frames = 48_000 * 20
        val pcmBytes = frames * 2
        val wav = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        wav
            .put("RIFF".toByteArray())
            .putInt(36 + pcmBytes)
            .put("WAVEfmt ".toByteArray())
            .putInt(16)
        wav
            .putShort(1)
            .putShort(1)
            .putInt(48_000)
            .putInt(96_000)
            .putShort(2)
            .putShort(16)
        wav.put("data".toByteArray()).putInt(pcmBytes)
        audio.writeBytes(wav.array())
        return PlaybackResource("song", audio.toURI().toString(), emptyMap(), null, null)
    }

    @Test
    fun freshShuffleKeepsSelectedTrackAndMovesEveryOtherOccurrenceUpcoming() {
        Settings.shared.useLegacyShuffle = false
        val songs = listOf(song().copy(id = "first"), song(), song().copy(id = "last"), song())
        player.setQueue(songs, startIndex = songs.lastIndex, preparePlayback = false)
        val selected =
            player.queueState.value.entries
                .last()

        assertTrue(player.toggleShuffle(preserveHistory = false))

        assertEquals(0, player.currentMediaItemIndex.value)
        assertEquals(
            selected,
            player.queueState.value.entries
                .first(),
        )
        assertEquals(songs.last(), player.currentSong.value)
        assertEquals(songs, player.queueSnapshot().originalItems)
        assertEquals(songs.lastIndex, player.queueSnapshot().originalIndices.first())
        assertEquals(songs.size, player.songQueue.value.size)
        assertFalse(player.toggleShuffle())
        assertEquals(listOf(songs.last()) + songs.dropLast(1), player.songQueue.value)
    }

    @Test
    fun legacyUnshuffleRestoresOriginalOrderAndSelectedOccurrence() {
        Settings.shared.useLegacyShuffle = true
        val songs = listOf(song().copy(id = "first"), song(), song().copy(id = "last"), song())
        player.setQueue(songs, startIndex = songs.lastIndex, preparePlayback = false)
        val selected =
            player.queueState.value.entries
                .last()

        assertTrue(player.toggleShuffle())
        assertFalse(player.toggleShuffle())

        assertEquals(songs, player.songQueue.value)
        assertEquals(songs.lastIndex, player.currentMediaItemIndex.value)
        assertEquals(
            selected,
            player.queueState.value.entries
                .last(),
        )
    }

    @Test
    fun audioTrackFailureDisablesOffloadAcrossPreferenceChanges() {
        val exo = underlyingPlayer()
        ExoPlaybackPlayer::class.java
            .getDeclaredField("currentResource")
            .apply { isAccessible = true }
            .set(player, PlaybackResource("song", "file:///downloaded-song", emptyMap(), null, null))
        player.applyAudioPreferences()
        assertEquals(
            AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
            exo.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode,
        )
        val listener =
            ExoPlaybackPlayer::class.java.getDeclaredField("playbackListener").let {
                it.isAccessible = true
                it.get(player) as Player.Listener
            }

        listener.onPlayerError(PlaybackException("output failed", null, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))

        assertEquals(
            AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
            exo.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode,
        )
        player.setPlaybackSpeed(1.25f)
        player.setPlaybackSpeed(1f)
        player.applyAudioPreferences()
        assertEquals(
            AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
            exo.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode,
        )
    }

    @Test
    fun recoverableOffloadSinkErrorsFallBackOnceWithoutWaitingForPlayerError() {
        val exo = underlyingPlayer()
        ExoPlaybackPlayer::class.java
            .getDeclaredField("currentResource")
            .apply { isAccessible = true }
            .set(player, PlaybackResource("song", "file:///downloaded-song", emptyMap(), null, null))
        player.applyAudioPreferences()
        assertEquals(
            AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
            exo.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode,
        )
        val listener =
            ExoPlaybackPlayer::class.java.getDeclaredField("networkLoadListener").let {
                it.isAccessible = true
                it.get(player) as AnalyticsListener
            }
        val eventTime = AnalyticsListener.EventTime(0L, Timeline.EMPTY, 0, null, 0L, Timeline.EMPTY, 0, null, 0L, 0L)
        val error = AudioSink.WriteException(-6, Format.Builder().build(), true)
        val failures = mutableListOf<AndroidPlaybackRecoveryRequest>()
        val collector = CoroutineScope(Dispatchers.Main.immediate)
        collector.launch { player.streamFailure.collect { failures += it } }
        try {
            listener.onAudioSinkError(eventTime, error)
            assertEquals(1, failures.size)
            player.finishPlaybackRecovery(failures.single().transitionId)
            repeat(20) {
                listener.onAudioSinkError(eventTime, error)
                player.applyAudioPreferences()
                assertEquals(
                    AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
                    exo.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode,
                )
            }
            assertEquals(1, failures.size)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun skipsPreservePlaybackIntentDuringQueueResolution() {
        val songs = listOf(song().copy(id = "first"), song().copy(id = "second"), song().copy(id = "third"))
        for (playing in listOf(false, true)) {
            player.setQueue(songs, startIndex = 1, startPlaying = playing)
            player.skipToNext()
            assertEquals(
                "third",
                player.queuePlaybackRequests.replayCache
                    .single()
                    .song.id,
            )
            assertEquals(
                playing,
                player.queuePlaybackRequests.replayCache
                    .single()
                    .resumePlayback,
            )
            player.skipToPrevious()
            assertEquals(
                "second",
                player.queuePlaybackRequests.replayCache
                    .single()
                    .song.id,
            )
            assertEquals(
                playing,
                player.queuePlaybackRequests.replayCache
                    .single()
                    .resumePlayback,
            )
        }
        player.pause()
        player.skipToNext()
        assertFalse(
            player.queuePlaybackRequests.replayCache
                .single()
                .resumePlayback,
        )
        player.skipToQueueItem(0, resumePlayback = player.wantsPlayback)
        assertFalse(
            player.queuePlaybackRequests.replayCache
                .single()
                .resumePlayback,
        )
        player.skipToQueueItem(1)
        assertTrue(
            player.queuePlaybackRequests.replayCache
                .single()
                .resumePlayback,
        )
    }

    @Test
    fun resumingAnUnpreparedQueueKeepsItsSelectedPosition() {
        player.setQueue(listOf(song()), startPositionMs = 48_000L)
        assertEquals(48_000L, player.getRealtimePositionMs())

        player.resume()

        assertEquals(
            48_000L,
            player.queuePlaybackRequests.replayCache
                .single()
                .positionMs,
        )
    }

    @Test
    fun screenOffFocusRecoveryRequiresScreenOffNoCallAndLocalPlayback() {
        assertTrue(shouldRecoverPlaybackAfterScreenOffFocusLoss(false, AudioManager.MODE_NORMAL, false, false))
        assertFalse(shouldRecoverPlaybackAfterScreenOffFocusLoss(true, AudioManager.MODE_NORMAL, false, false))
        assertFalse(shouldRecoverPlaybackAfterScreenOffFocusLoss(false, AudioManager.MODE_IN_CALL, false, false))
        assertFalse(shouldRecoverPlaybackAfterScreenOffFocusLoss(false, AudioManager.MODE_IN_COMMUNICATION, false, false))
        assertFalse(shouldRecoverPlaybackAfterScreenOffFocusLoss(false, AudioManager.MODE_RINGTONE, false, false))
        assertFalse(shouldRecoverPlaybackAfterScreenOffFocusLoss(false, AudioManager.MODE_NORMAL, true, false))
    }

    @Test
    fun pendingSelectionIgnoresEventsFromTheRetiredPlayersClock() {
        val exo = underlyingPlayer()
        exo.setMediaItem(MediaItem.fromUri("file:///previous-song"))
        exo.seekTo(90_000L)
        player.setQueue(listOf(song()), startPlaying = true)

        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(90_000L, exo.currentPosition)
        assertEquals(0L, player.getRealtimePositionMs())
        assertEquals(0L, player.currentPosition.value)
    }

    @Test
    fun seekingDuringQueueResolutionReplacesTheStaleRequestAndKeepsPauseIntent() {
        player.setQueue(listOf(song()), startPlaying = true)
        val original = player.queuePlaybackRequests.replayCache.single()
        player.pause()

        player.seekTo(35_000L)

        val replacement = player.queuePlaybackRequests.replayCache.single()
        assertTrue(replacement.requestId > original.requestId)
        assertEquals(original.song, replacement.song)
        assertEquals(35_000L, replacement.positionMs)
        assertFalse(replacement.resumePlayback)
        assertEquals(35_000L, player.currentPosition.value)
        assertEquals(35_000L, player.getRealtimePositionMs())
    }

    @Test
    fun controllerPitchUpdatesReachPlaybackAndLegacyStatePreservesPitch() {
        player.setControllerPlayback(DcLocalPlaybackState(speed = 1.5f, pitch = 0.75f, ready = true))
        assertEquals(1.5f, player.playbackSpeed.value, 0f)
        assertEquals(0.75f, player.playbackPitch.value, 0f)
        assertEquals(0.75f, underlyingPlayer().playbackParameters.pitch, 0f)
        player.setControllerPlayback(DcLocalPlaybackState(speed = 1.25f, ready = true))
        assertEquals(0.75f, player.playbackPitch.value, 0f)
        player.setControllerPlayback(null)
        assertEquals(0.75f, player.playbackPitch.value, 0f)
        assertFalse(player.isPlaying.value)
    }

    @Test
    fun leavingControllerModeAppliesThePublishedTempoToLocalPlayback() {
        player.setPlaybackPitch(1.25f)
        player.setControllerPlayback(DcLocalPlaybackState(speed = 1.75f, positionMs = 24_000L, ready = true))

        player.setControllerPlayback(null)
        player.setTempoPitch(1.75f, 1.25f)

        val exo = underlyingPlayer()
        assertEquals(1.75f, exo.playbackParameters.speed, 0f)
        assertEquals(1.25f, exo.playbackParameters.pitch, 0f)
        assertEquals(24_000L, player.getRealtimePositionMs())
        assertFalse(player.isPlaying.value)
    }

    @Test
    fun crossfadeControlsTheOutputOfAlreadyBufferedPcmWithoutBakingInTheEnvelope() {
        val exo = underlyingPlayer()
        val processor = normalizationProcessors().getValue(exo)
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val bufferedSample = processSample(processor)

        player.setTransitionVolume(0f)
        assertEquals(0f, exo.volume, 0f)
        assertEquals(bufferedSample, processSample(processor))

        player.setTransitionVolume(50f)
        assertEquals(0.5f, exo.volume, 0f)
        assertEquals(bufferedSample, processSample(processor))
    }

    @Test
    fun transientFocusLossPausesCrossfadeAndFocusGainResumesBothPlayers() {
        player.releaseAndAwaitScope()
        Dispatchers.resetMain()
        player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
        runBlocking {
            player.prepareAndPlay(song(), downloadedResource(), resumePlayback = true)
        }
        val incoming = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        ExoPlaybackPlayer::class.java
            .getDeclaredField("nextPlayer")
            .apply { isAccessible = true }
            .set(player, incoming)
        assertTrue(player.startNextPlayback())
        val primary = underlyingPlayer()

        fun flushPlaybackThread() {
            assertTrue(primary.createMessage { _, _ -> }.send().blockUntilDelivered(10_000L))
            shadowOf(Looper.getMainLooper()).idle()
        }

        // Preparation returns before Media3 requests audio focus on its playback thread.
        flushPlaybackThread()
        val audioManager = shadowOf(RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java))
        val focusListener = checkNotNull(audioManager.lastAudioFocusRequest).listener

        fun changeAudioFocus(focusChange: Int) {
            assertTrue(
                primary
                    .createMessage { _, _ -> focusListener.onAudioFocusChange(focusChange) }
                    .send()
                    .blockUntilDelivered(10_000L),
            )
            // The focus callback queues a second playback command before notifying the main looper.
            flushPlaybackThread()
        }

        assertTrue(incoming.playWhenReady)
        changeAudioFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertTrue(primary.playWhenReady)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS, primary.playbackSuppressionReason)
        assertFalse(incoming.playWhenReady)
        player.resumeNextPlayback()
        assertFalse(incoming.playWhenReady)
        assertTrue(player.startNextPlayback())
        assertFalse(incoming.playWhenReady)

        changeAudioFocus(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(primary.playWhenReady)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_NONE, primary.playbackSuppressionReason)
        assertTrue(incoming.playWhenReady)
        player.pause()
        assertFalse(incoming.playWhenReady)
        player.resume()
        assertTrue(incoming.playWhenReady)
        player.pause()
    }

    @Test
    fun pausedSurfaceAttachmentKeepsBothPlayersPausedAndExportsTheirIntent() {
        val incoming = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        ExoPlaybackPlayer::class.java
            .getDeclaredField("nextPlayer")
            .apply { isAccessible = true }
            .set(player, incoming)
        underlyingPlayer().playWhenReady = true
        assertTrue(player.startNextPlayback())
        player.pause()
        shadowOf(Looper.getMainLooper()).idle()
        PlaybackEventLog.clear()

        player.setVideoSurface(null)
        player.clearVideoSurface(null)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(player.wantsPlayback)
        assertFalse(player.isPlaying.value)
        assertFalse(incoming.playWhenReady)
        val state =
            PlaybackEventLog.events.value
                .single { it.message == "Android playback surface changed." }
                .details
        assertEquals("false", state["playWhenReady"])
        assertEquals("false", state["publishedPlaying"])
        assertEquals("false", state["nextWantsPlayback"])
        assertEquals("false", state["transitionPending"])
    }

    @Test
    fun disconnectPauseStopsBothPlayersAndCancelsPendingFocusRecovery() {
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(PowerManager::class.java)).turnScreenOn(false)
        val incoming = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        ExoPlaybackPlayer::class.java
            .getDeclaredField("nextPlayer")
            .apply { isAccessible = true }
            .set(player, incoming)
        underlyingPlayer().playWhenReady = true
        assertTrue(player.startNextPlayback())
        val listener =
            ExoPlaybackPlayer::class.java.getDeclaredField("playbackListener").let {
                it.isAccessible = true
                it.get(player) as Player.Listener
            }
        listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        val focusRecovery = ExoPlaybackPlayer::class.java.getDeclaredField("pendingFocusRecovery").apply { isAccessible = true }
        assertNotNull(focusRecovery.get(player))

        player.pause()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertFalse(underlyingPlayer().playWhenReady)
        assertFalse(incoming.playWhenReady)
        assertEquals(null, focusRecovery.get(player))
    }

    @Test
    fun screenOffFocusRecoveryDoesNotInterruptAnotherMusicApp() = verifyScreenOffFocusRecovery(otherMusicActive = true)

    @Test
    fun screenOffFocusRecoveryRetainsRecoveryWithoutCompetingMusic() = verifyScreenOffFocusRecovery(otherMusicActive = false)

    private fun verifyScreenOffFocusRecovery(otherMusicActive: Boolean) {
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(PowerManager::class.java)).turnScreenOn(false)
        val primary = underlyingPlayer()
        primary.pause()
        shadowOf(Looper.getMainLooper()).idle()
        ExoPlaybackPlayer::class.java.getDeclaredField("_wantsPlayback").let {
            it.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (it.get(player) as MutableStateFlow<Boolean>).value = true
        }
        val listener =
            ExoPlaybackPlayer::class.java.getDeclaredField("playbackListener").let {
                it.isAccessible = true
                it.get(player) as Player.Listener
            }
        listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        val recovery = ExoPlaybackPlayer::class.java.getDeclaredField("pendingFocusRecovery").apply { isAccessible = true }
        assertNotNull(recovery.get(player))
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)).setIsMusicActive(otherMusicActive)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertEquals(!otherMusicActive, primary.playWhenReady)
        player.pause()
    }

    @Test
    fun delayedFocusRecoveryCannotResumeAReplacementPlayer() {
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(PowerManager::class.java)).turnScreenOn(false)
        val interrupted = underlyingPlayer()
        interrupted.playWhenReady = true
        shadowOf(Looper.getMainLooper()).idle()
        val listener =
            ExoPlaybackPlayer::class.java.getDeclaredField("playbackListener").let {
                it.isAccessible = true
                it.get(player) as Player.Listener
            }
        listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        val replacement = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        ExoPlaybackPlayer::class.java
            .getDeclaredField("exo")
            .apply { isAccessible = true }
            .set(player, replacement)
        try {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertFalse(replacement.playWhenReady)
            assertFalse(player.wantsPlayback)
        } finally {
            ExoPlaybackPlayer::class.java
                .getDeclaredField("exo")
                .apply { isAccessible = true }
                .set(player, interrupted)
            replacement.release()
        }
    }

    @Test
    fun incomingPlayerIsMutedBeforePlaybackStartsEvenWithPreconfiguredPcm() {
        val incoming = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        val processor = NormalizationAudioProcessor { 1f }
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        normalizationProcessors()[incoming] = processor
        ExoPlaybackPlayer::class.java
            .getDeclaredField("nextPlayer")
            .apply { isAccessible = true }
            .set(player, incoming)
        underlyingPlayer().playWhenReady = true
        player.setNextVolume(0f)
        incoming.volume = 1f
        val startingVolumes = mutableListOf<Float>()
        incoming.addListener(
            object : Player.Listener {
                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    if (playWhenReady) startingVolumes += incoming.volume
                }
            },
        )

        assertTrue(player.startNextPlayback())
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(0f), startingVolumes)
        player.setNextVolume(50f)
        assertEquals(0.5f, incoming.volume, 0f)
    }

    @Test
    fun pendingRecoveryPreventsStartingACrossfadeOnTheRetiringSession() {
        val incoming = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        ExoPlaybackPlayer::class.java
            .getDeclaredField("nextPlayer")
            .apply { isAccessible = true }
            .set(player, incoming)
        val transitions =
            ExoPlaybackPlayer::class.java.getDeclaredField("playbackTransitions").let {
                it.isAccessible = true
                it.get(player) as PlaybackTransitionState
            }
        underlyingPlayer().playWhenReady = true
        transitions.beginRecovery(7)
        assertFalse(player.startNextPlayback())
        assertFalse(incoming.playWhenReady)
        player.finishPlaybackRecovery(7)
        assertTrue(player.startNextPlayback())
        assertTrue(incoming.playWhenReady)
    }

    @Test
    fun crossfadeKeepsBothPlayersRunningWithAudibleOutputUntilTheOutgoingTrackEnds() {
        val incoming = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        ExoPlaybackPlayer::class.java
            .getDeclaredField("nextPlayer")
            .apply { isAccessible = true }
            .set(player, incoming)
        player.setVolume(0.8f)
        player.setSleepTimerGain(0.5f)
        underlyingPlayer().playWhenReady = true
        assertTrue(player.startNextPlayback())
        for (step in 1 until 20) {
            val (outgoingGain, incomingGain) = playbackCrossfadeGains(step / 20f)
            player.setCrossfadeVolumes(outgoingGain * 100f, incomingGain * 100f)
            assertTrue(underlyingPlayer().playWhenReady)
            assertTrue(incoming.playWhenReady)
            assertEquals(0.4f * outgoingGain, underlyingPlayer().volume, 0.00001f)
            assertEquals(0.4f * incomingGain, incoming.volume, 0.00001f)
            assertTrue(underlyingPlayer().volume > 0f)
            assertTrue(incoming.volume > 0f)
        }
        player.pause()
        assertFalse(incoming.playWhenReady)
        player.resume()
        assertTrue(incoming.playWhenReady)
    }

    @Test
    fun preparedSuccessorStartsInEndCallbackBeforeQueueCompletionIsCollected() {
        val incoming = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        ExoPlaybackPlayer::class.java
            .getDeclaredField("nextPlayer")
            .apply { isAccessible = true }
            .set(player, incoming)
        ExoPlaybackPlayer::class.java
            .getDeclaredField("eofInstance")
            .apply { isAccessible = true }
            .setLong(player, 7L)
        @Suppress("UNCHECKED_CAST")
        val instanceState =
            ExoPlaybackPlayer::class.java.getDeclaredField("playbackInstanceIdState").let {
                it.isAccessible = true
                it.get(player) as MutableStateFlow<Long>
            }
        instanceState.value = 7L
        underlyingPlayer().playWhenReady = true
        val completions = mutableListOf<Long>()
        val collector = CoroutineScope(Dispatchers.Main)
        collector.launch { player.onEOFPlaybackInstance.collect { completions += it } }
        player.startPreloadedNextAtEof = {
            assertEquals(7L, it)
            assertTrue(completions.isEmpty())
            player.setNextVolume(100f)
            assertTrue(player.startNextPlayback())
        }
        val listener =
            ExoPlaybackPlayer::class.java.getDeclaredField("playbackListener").let {
                it.isAccessible = true
                it.get(player) as Player.Listener
            }

        listener.onPlaybackStateChanged(Player.STATE_ENDED)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(incoming.playWhenReady)
        assertEquals(1f, incoming.volume, 0f)
        assertEquals(listOf(7L), completions)
        collector.cancel()
    }

    @Suppress("UNCHECKED_CAST")
    private fun normalizationProcessors(): MutableMap<ExoPlayer, NormalizationAudioProcessor> =
        ExoPlaybackPlayer::class.java.getDeclaredField("normalizationProcessors").let {
            it.isAccessible = true
            it.get(player) as MutableMap<ExoPlayer, NormalizationAudioProcessor>
        }

    private fun processSample(processor: NormalizationAudioProcessor): Short {
        processor.queueInput(ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).putShort(0, 10_000))
        return processor.output.getShort(0)
    }

    private fun underlyingPlayer(): ExoPlayer =
        ExoPlaybackPlayer::class.java.getDeclaredField("exo").let {
            it.isAccessible = true
            it.get(player) as ExoPlayer
        }

    private fun song() = SongItem("song", "Song", null, emptyList(), null, duration = 120L)
}
