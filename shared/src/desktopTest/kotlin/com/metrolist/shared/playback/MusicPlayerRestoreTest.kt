package com.metrolist.shared.playback

import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.ffmpeg.AudioPlayer
import com.metrolist.shared.playback.ffmpeg.AudioRenderer
import com.metrolist.shared.playback.ffmpeg.PlaybackPositionInput
import com.metrolist.shared.settings.LoudnessLevel
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.PodcastPlaybackProgress
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.SourceDataLine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MusicPlayerRestoreTest {
    @Test
    fun sabrProducerFailureRequestsRecoveryWhileTheRendererKeepsPlaying() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val file = Files.createTempFile("buffered-sabr-failure", ".wav")
            val output = TestPlaybackLine()
            val player = MusicPlayer(rendererFactory = { rate, channels -> AudioRenderer(rate, channels, output.line) })
            val failures = Channel<PlaybackFailure>(Channel.UNLIMITED)
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { player.streamFailure.collect(failures::send) }
            val bytes = silentWav(samples = 8_000 * 20)
            val input =
                object : ByteArrayInputStream(bytes), PlaybackPositionInput {
                    @Volatile var failure: Throwable? = null
                    override val streamFailure: Throwable? get() = failure

                    override fun updatePlaybackPosition(positionMs: Long) {}
                }
            try {
                Files.write(file, bytes)
                val song = SongItem("song", "Song", null, emptyList(), null)
                assertTrue(player.playPaused(song, resource(song.id, file.toString())))
                val audio =
                    MusicPlayer::class.java
                        .getDeclaredField("audioPlayer")
                        .apply { isAccessible = true }
                        .get(player) as AudioPlayer
                assertTrue(audio.prepareStream(input, bytes.size, startPaused = true))
                player.attachPlaybackResource(sabrAudioResource(bytes.size.toLong(), durationMs = 20_000))
                player.resume()
                output.awaitBuffered()
                val error = SabrProtocolException("Pending with token", SabrFailureKind.ATTESTATION_REQUIRED)
                input.failure = error
                val failure = withTimeout(5_000) { failures.receive() }
                assertSame(error, failure.cause)
                assertEquals(PlaybackFailureCategory.SABR_ATTESTATION, failure.category)
                assertTrue(failure.shouldExcludePlaybackProfile)
                assertTrue(audio.isPlaying())
                assertFalse(audio.hasFailed)
                assertTrue(audio.getBufferedPositionMs() > audio.getCurrentPosition())
                assertNull(withTimeoutOrNull(500) { failures.receive() })
            } finally {
                player.release()
                observer.cancel()
                failures.close()
                Files.deleteIfExists(file)
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun endedTrackPublishesTheSeekPositionAndResumesPlayback() =
        runBlocking {
            val file = Files.createTempFile("metrolist-ended-track", ".wav")
            val output = TestPlaybackLine(automatic = true)
            val player = MusicPlayer(rendererFactory = { rate, channels -> AudioRenderer(rate, channels, output.line) })
            val completions = Channel<Long>(Channel.UNLIMITED)
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { player.onEOFPlaybackInstance.collect(completions::send) }
            try {
                Files.write(file, silentWav())
                val song = SongItem("ended", "Ended", null, emptyList(), null)
                player.play(song, resource(song.id, file.toString()))
                val instanceId = player.currentPlaybackInstanceId
                assertEquals(instanceId, withTimeout(5_000L) { completions.receive() })
                withTimeout(5_000L) { player.isPlaying.first { !it } }
                assertFalse(player.wantsPlayback)

                output.automatic = false
                player.seekTo(500L)
                withTimeout(5_000L) { player.currentPosition.first { it == 500L } }
                assertEquals(500L, player.currentPlaybackPositionMs())
                assertFalse(player.wantsPlayback)
                player.resume()
                output.advanceUntil(8_800L)
                withTimeout(5_000L) { player.isPlaying.first { it } }
                assertEquals(600L, player.currentPlaybackPositionMs())
                assertEquals(instanceId, player.currentPlaybackInstanceId)
                assertTrue(player.wantsPlayback)
                assertTrue(completions.tryReceive().isFailure)
                output.automatic = true
                assertEquals(instanceId, withTimeout(5_000L) { completions.receive() })
            } finally {
                observer.cancel()
                player.release()
                Files.deleteIfExists(file)
            }
        }

    @Test
    fun trackReplacementPreservesPausedIntentWithoutStartingAudio() {
        val file = Files.createTempFile("metrolist-paused-skip", ".wav")
        val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
        try {
            Files.write(file, silentWav(samples = 8_000 * 10))
            val first = SongItem("first", "First", null, emptyList(), null)
            val second = first.copy(id = "second", title = "Second")
            player.play(first, resource(first.id, file.toString()), startPaused = true)
            player.play(second, resource(second.id, file.toString()), startPaused = !player.wantsPlayback)
            assertEquals(second, player.currentSong.value)
            assertTrue(player.playbackReady)
            assertFalse(player.wantsPlayback)
            assertEquals(0L, player.currentPlaybackPositionMs())
            player.resume()
            player.play(first, resource(first.id, file.toString()), startPaused = !player.wantsPlayback)
            assertEquals(first, player.currentSong.value)
            assertTrue(player.wantsPlayback)
        } finally {
            player.release()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun rapidSelectionsPreservePlaybackIntentWhileTheFinalOutputIsPreparing() {
        for (pauseDuringPreparation in listOf(false, true)) {
            val file = Files.createTempFile("metrolist-rapid-selection", ".wav")
            val preparing = CountDownLatch(1)
            val finishPreparing = CountDownLatch(1)
            val player =
                MusicPlayer(rendererFactory = { rate, channels ->
                    preparing.countDown()
                    check(finishPreparing.await(5, TimeUnit.SECONDS))
                    testAudioRenderer(rate, channels)
                })
            try {
                Files.write(file, silentWav(samples = 8_000 * 10))
                player.resume()
                val first = SongItem("first", "First", null, emptyList(), null)
                val second = first.copy(id = "second")
                val last = first.copy(id = "last")
                val stale = player.beginPendingPlayback(first)
                player.beginPendingPlayback(second)
                val finalSelection = player.beginPendingPlayback(last)
                assertTrue(player.wantsPlayback)
                assertFalse(player.isPlaying.value)
                assertFalse(player.playPending(first, file.toString(), stale))
                val preparation =
                    Thread {
                        player.playPending(last, file.toString(), finalSelection, startPaused = !player.wantsPlayback)
                    }.apply { start() }
                assertTrue(preparing.await(5, TimeUnit.SECONDS))
                assertTrue(player.wantsPlayback)
                assertSame(last, player.currentSong.value)
                player.pause()
                if (!pauseDuringPreparation) player.resume()
                assertEquals(!pauseDuringPreparation, player.wantsPlayback)
                finishPreparing.countDown()
                preparation.join(5_000L)
                assertFalse(preparation.isAlive)
                assertTrue(player.playbackReady)
                assertEquals(!pauseDuringPreparation, player.wantsPlayback)
                player.pause()
                player.beginPendingPlayback(first)
                assertFalse(player.wantsPlayback)
            } finally {
                finishPreparing.countDown()
                player.release()
                Files.deleteIfExists(file)
            }
        }
    }

    @Test
    fun networkChangesLeaveLocalAudioPlayingEvenAfterRemoteVideoMetadataAttaches() =
        runBlocking {
            val file = Files.createTempFile("metrolist-local-network", ".wav")
            lateinit var output: TestPlaybackLine
            val player =
                MusicPlayer(rendererFactory = { rate, channels ->
                    output = TestPlaybackLine()
                    AudioRenderer(rate, channels, output.line)
                })
            val failures = Channel<PlaybackFailure>(Channel.UNLIMITED)
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { player.streamFailure.collect(failures::send) }
            try {
                Files.write(file, silentWav(samples = 8_000 * 10))
                val song = SongItem("local-audio", "Local audio", null, emptyList(), null)
                for (profile in listOf("device-file", "cached-audio", "downloaded-audio")) {
                    val local = resource(song.id, file.toString()).copy(clientName = "local", profileId = profile)
                    assertTrue(player.playPaused(song, local))
                    player.resume()
                    val instance = player.currentPlaybackInstanceId
                    val position = player.currentPlaybackPositionMs()
                    player.invalidateActiveStreamForNetworkChange()
                    assertNull(withTimeoutOrNull(250L) { failures.receive() }, profile)

                    player.attachPlaybackResource(
                        local.copy(url = "https://media.example.test/video", clientName = "remote", profileId = "video"),
                    )
                    player.invalidateActiveStreamForNetworkChange()
                    assertNull(withTimeoutOrNull(250L) { failures.receive() }, "$profile with video metadata")
                    assertTrue(player.playbackReady)
                    assertTrue(player.wantsPlayback)
                    assertEquals(instance, player.currentPlaybackInstanceId)
                    output.advanceUntil(800L)
                    assertTrue(player.currentPlaybackPositionMs() > position)
                }
            } finally {
                observer.cancel()
                failures.close()
                player.release()
                Files.deleteIfExists(file)
            }
        }

    @Test
    fun networkChangesLeaveHealthyRemotePlaybackAndManualRetryAvailable() =
        runBlocking {
            val file = Files.createTempFile("metrolist-network-change", ".wav")
            val output = TestPlaybackLine()
            val player = MusicPlayer(rendererFactory = { rate, channels -> AudioRenderer(rate, channels, output.line) })
            val failures = Channel<PlaybackFailure>(Channel.UNLIMITED)
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { player.streamFailure.collect(failures::send) }
            try {
                Files.write(file, silentWav(samples = 8_000 * 10))
                val song = SongItem("remote-audio", "Remote audio", null, emptyList(), null)
                assertTrue(player.playPaused(song, resource(song.id, file.toString()).copy(clientName = "WEB_REMIX")))
                player.invalidateActiveStreamForNetworkChange()
                assertNull(withTimeoutOrNull(250L) { failures.receive() })

                player.resume()
                output.awaitBuffered()
                val instance = player.currentPlaybackInstanceId
                val position = player.currentPlaybackPositionMs()
                repeat(3) { player.invalidateActiveStreamForNetworkChange() }
                assertNull(withTimeoutOrNull(250L) { failures.receive() })
                assertTrue(player.playbackReady)
                assertTrue(player.wantsPlayback)
                assertEquals(instance, player.currentPlaybackInstanceId)
                output.advanceUntil(800L)
                assertTrue(player.currentPlaybackPositionMs() > position)

                player.invalidateActiveStreamForNetworkChange(forceRecovery = true)
                val failure = withTimeout(5_000L) { failures.receive() }
                assertEquals("network_change", failure.stage)
                assertEquals(instance, failure.playbackInstanceId)
            } finally {
                observer.cancel()
                failures.close()
                player.release()
                Files.deleteIfExists(file)
            }
        }

    @Test
    fun rejectedDirectLoadEmitsTypedFailureAndPreservesPlaybackIntent() =
        runBlocking {
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(
                                chain
                                    .request()
                                    .url.pathSegments
                                    .last()
                                    .toInt(),
                            ).message("Rejected")
                            .body(ByteArray(0).toResponseBody())
                            .build()
                    }.build()
            try {
                for (responseStatus in listOf(401, 403, 404, 410, 416)) {
                    for (startPaused in listOf(false, true)) {
                        val player = MusicPlayer(mediaHttpClient = client, rendererFactory = ::testAudioRenderer)
                        val failures = Channel<PlaybackFailure>(Channel.UNLIMITED)
                        val observer = launch(start = CoroutineStart.UNDISPATCHED) { player.streamFailure.collect(failures::send) }
                        try {
                            val song = SongItem("rejected", "Rejected", null, emptyList(), null)
                            val resource =
                                resource(song.id, "https://media.example.test/$responseStatus").copy(contentLengthBytes = 32L)
                            val transition = player.beginPendingPlayback(song)

                            assertFalse(player.playPending(song, resource, transition, startPaused))
                            val failure = withTimeout(5_000) { failures.receive() }
                            assertEquals("audio_prepare", failure.stage)
                            assertEquals(responseStatus, failure.httpStatusCode, failure.toString())
                            assertEquals(responseStatus, failure.cause?.findPlaybackHttpStatusCode())
                            assertEquals(PlaybackFailureClassifier.fromHttpStatus(responseStatus), failure.category)
                            assertEquals(!startPaused, failure.resumePlayback)
                            assertEquals(player.currentPlaybackInstanceId, failure.playbackInstanceId)
                            assertNull(player.loadFailure.value)
                        } finally {
                            observer.cancel()
                            player.release()
                        }
                    }
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }

    @Test
    fun crossfadePreparationPreservesPauseAndRejectsCancelledOrStaleRequests() =
        runBlocking {
            val file = Files.createTempFile("metrolist-crossfade-pause", ".wav")
            val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
            try {
                Files.write(file, silentWav())
                val current = SongItem("current", "Current", null, emptyList(), null)
                val next = resource("next", file.toString())
                assertTrue(player.playPaused(current, resource(current.id, file.toString())))
                player.isCrossfading = true
                val token = assertNotNull(player.prepareNextTrack(next))
                assertEquals(0L, player.nextPlayerPosition, "Paused outgoing playback must not consume the incoming intro")

                assertNull(player.prepareNextTrack(resource("cancelled", file.toString()), shouldCommit = { false }))
                assertNull(player.prepareNextTrack(resource("stale", file.toString()), expectedCurrentPlaybackInstanceId = -1L))
                player.resume()
                player.pause()
                assertTrue(player.swapToNext(token, next), "Rejected preparations must not replace the current preload")
                assertFalse(player.wantsPlayback)
            } finally {
                player.release()
                Files.deleteIfExists(file)
            }
        }

    @Test
    fun pausedPreloadCreationAndReleaseWakeStateObservers() {
        val file = Files.createTempFile("metrolist-preload-events", ".wav")
        val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
        try {
            Files.write(file, silentWav())
            val current = SongItem("current", "Current", null, emptyList(), null)
            assertTrue(player.playPaused(current, resource(current.id, file.toString())))
            val beforePreload = player.playbackStateChanges.value
            assertNotNull(player.prepareNextTrack(resource("next", file.toString()), startPaused = true))
            assertTrue(player.playbackStateChanges.value > beforePreload)
            val beforeRelease = player.playbackStateChanges.value
            player.cancelNext()
            assertTrue(player.playbackStateChanges.value > beforeRelease)
            assertFalse(player.wantsPlayback)
        } finally {
            player.release()
            Files.deleteIfExists(file)
        }
    }

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun bufferedPositionHandlesPlaybackPastReportedDuration() {
        assertEquals(1_000L, boundedBufferedPosition(positionMs = 1_001L, bufferedPositionMs = 800L, durationMs = 1_000L))
        assertEquals(700L, boundedBufferedPosition(positionMs = 500L, bufferedPositionMs = 700L, durationMs = 1_000L))
        assertEquals(0L, boundedBufferedPosition(positionMs = 500L, bufferedPositionMs = 700L, durationMs = 0L))
    }

    @Test
    fun failedPreparedPlaybackCanBeRetried() {
        assertFalse(shouldRetryPlayback(playbackReady = true, hasLoadFailure = false))
        assertTrue(shouldRetryPlayback(playbackReady = false, hasLoadFailure = false))
        assertTrue(shouldRetryPlayback(playbackReady = true, hasLoadFailure = true))
    }

    @Test
    fun pendingDevicePlaybackPreparesSilentlyAndRejectsStaleTransitions() =
        runBlocking {
            val file = Files.createTempFile("metrolist-device-playback", ".wav")
            val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
            try {
                Files.write(file, silentWav())
                val song = SongItem("sync", "Sync", null, emptyList(), null)
                val oldTransition = player.beginPendingPlayback(song)
                val currentTransition = player.beginPendingPlayback(song)
                assertFalse(player.playPending(song, file.toString(), oldTransition, startPaused = true))
                assertTrue(player.playPending(song, file.toString(), currentTransition, startPaused = true))
                assertTrue(player.playbackReady)
                assertFalse(player.wantsPlayback)
                assertFalse(player.isPlaying.value)

                val nextTransition = player.beginPendingPlayback(song)
                assertFalse(
                    player.playPending(
                        song,
                        resource(song.id, file.toString()),
                        nextTransition,
                        startPaused = true,
                        shouldCommit = { false },
                    ),
                )
                assertFalse(player.wantsPlayback)
            } finally {
                player.release()
                Files.deleteIfExists(file)
            }
        }

    @Test
    fun cancelledPreparationRetainsTheSelectedSongAndAllowsRetry() {
        val file = Files.createTempFile("metrolist-cancelled-playback", ".wav")
        try {
            Files.write(file, silentWav())
            for (useResource in listOf(false, true)) {
                val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
                try {
                    val song = SongItem("sync", "Sync", null, emptyList(), null)
                    player.setQueue(listOf(song), startIndex = 0, startPlaying = false)
                    val transition = player.beginPendingPlayback(song)
                    val shouldCommit = { !player.playbackReady }

                    val played =
                        if (useResource) {
                            player.playPending(song, resource(song.id, file.toString()), transition, true, shouldCommit)
                        } else {
                            player.playPending(song, file.toString(), transition, true, shouldCommit)
                        }

                    assertFalse(played)
                    assertEquals(song.id, player.currentSong.value?.id)
                    assertEquals(0L, player.currentPlaybackInstanceId)
                    assertNull(player.currentPlaybackResource.value)
                    assertNull(player.loadFailure.value)
                    assertFalse(player.playbackReady)
                    assertFalse(player.wantsPlayback)
                    assertTrue(player.retryPendingQueuePlayback())
                    val retry = player.beginPendingPlayback(song)
                    assertTrue(player.playPending(song, file.toString(), retry, startPaused = true))
                    assertTrue(player.playbackReady)
                } finally {
                    player.release()
                }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun cancelledPreparationDoesNotClearANewerPlayback() {
        val file = Files.createTempFile("metrolist-replaced-playback", ".wav")
        try {
            Files.write(file, silentWav())
            for (useResource in listOf(false, true)) {
                val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
                try {
                    val first = SongItem("first", "First", null, emptyList(), null)
                    val second = SongItem("second", "Second", null, emptyList(), null)
                    val replacementResource = resource(second.id, file.toString())
                    val transition = player.beginPendingPlayback(first)
                    var replacementInstance = 0L
                    val shouldCommit = {
                        if (!player.playbackReady) {
                            true
                        } else {
                            assertTrue(player.playPaused(second, replacementResource))
                            replacementInstance = player.currentPlaybackInstanceId
                            false
                        }
                    }

                    val played =
                        if (useResource) {
                            player.playPending(first, resource(first.id, file.toString()), transition, true, shouldCommit)
                        } else {
                            player.playPending(first, file.toString(), transition, true, shouldCommit)
                        }

                    assertFalse(played)
                    assertTrue(replacementInstance > 0L)
                    assertEquals(replacementInstance, player.currentPlaybackInstanceId)
                    assertSame(second, player.currentSong.value)
                    assertSame(replacementResource, player.currentPlaybackResource.value)
                    assertTrue(player.playbackReady)
                    assertFalse(player.wantsPlayback)
                    assertNull(player.loadFailure.value)
                } finally {
                    player.release()
                }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun transitionGainDoesNotOverwriteTheRequestedVolume() =
        runBlocking {
            val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
            try {
                player.setVolume(70)
                player.setTransitionVolume(0f)

                assertEquals(0.7f, player.volume.value)
            } finally {
                player.release()
            }
        }

    @Test
    fun audioOutputGateDoesNotOverwriteTheRequestedVolume() {
        val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
        try {
            player.setVolume(70)
            player.setAudioOutputEnabled(false)
            assertEquals(0.7f, player.volume.value)

            player.setVolume(45)
            assertEquals(0.45f, player.volume.value)

            player.setAudioOutputEnabled(true)
            assertEquals(0.45f, player.volume.value)
        } finally {
            player.release()
        }
    }

    @Test
    fun queueTransitionRequestsResolutionWithoutOpeningAnEmptySource() =
        runBlocking {
            val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
            val songs =
                listOf(
                    SongItem("first", "First", null, emptyList(), null),
                    SongItem("second", "Second", null, emptyList(), null),
                )
            try {
                player.setQueue(songs, startIndex = 0, startPlaying = false)
                player.skipToQueueItem(1)

                assertEquals(QueuePlaybackRequest(1L, songs[1]), player.queuePlaybackRequests.first())
                assertEquals(0L, player.currentPlaybackInstanceId)
                assertSame(songs[1], player.currentSong.value)
                assertNull(player.currentPlaybackResource.value)
            } finally {
                player.release()
            }
        }

    @Test
    fun retryingPendingQueuePlaybackReplacesTheInFlightRequest() =
        runBlocking {
            val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
            val song = SongItem("pending", "Pending", null, emptyList(), null)
            try {
                player.setQueue(listOf(song), startIndex = 0, startPlaying = true)

                assertTrue(player.retryPendingQueuePlayback())

                assertEquals(QueuePlaybackRequest(2L, song), player.queuePlaybackRequests.first())
                assertEquals(song.id, player.currentSong.value?.id)
                assertNull(player.currentPlaybackResource.value)
                assertEquals(0L, player.currentPlaybackInstanceId)
            } finally {
                player.release()
            }
        }

    @Test
    fun stalePreparedPlaybackIsNotCommitted() =
        runBlocking {
            val file = Files.createTempFile("metrolist-stale-playback", ".wav")
            val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
            try {
                Files.write(file, silentWav())
                val song = SongItem("id", "Title", null, emptyList(), null)

                assertFalse(
                    player.prepareAndPlay(
                        song = song,
                        resource = resource(song.id, file.toString()),
                        shouldCommit = { false },
                    ),
                )
                assertNull(player.currentSong.value)
                assertNull(player.currentPlaybackResource.value)
            } finally {
                player.release()
                Files.deleteIfExists(file)
            }
        }

    @Test
    fun removingAnInFlightItemRequestsItsReplacement() =
        runBlocking {
            val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
            val songs =
                listOf(
                    SongItem("first", "First", null, emptyList(), null),
                    SongItem("second", "Second", null, emptyList(), null),
                )
            try {
                player.setQueue(songs, startIndex = 0, startPlaying = true)

                player.removeQueueItem(0)

                assertEquals(QueuePlaybackRequest(2L, songs[1]), player.queuePlaybackRequests.first())
            } finally {
                player.release()
            }
        }

    @Test
    fun removingAnEarlierItemKeepsTheCurrentInFlightRequest() =
        runBlocking {
            val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
            val songs =
                listOf(
                    SongItem("first", "First", null, emptyList(), null),
                    SongItem("second", "Second", null, emptyList(), null),
                )
            try {
                player.setQueue(songs, startIndex = 1, startPlaying = true)

                player.removeQueueItem(0)

                assertEquals(1L, player.latestQueuePlaybackRequestId)
                assertEquals(QueuePlaybackRequest(1L, songs[1]), player.queuePlaybackRequests.first())
            } finally {
                player.release()
            }
        }

    @Test
    fun removingAnEarlierItemDoesNotRestartActivePlayback() {
        val file = Files.createTempFile("metrolist-active-queue", ".wav")
        val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
        val songs =
            listOf(
                SongItem("first", "First", null, emptyList(), null),
                SongItem("second", "Second", null, emptyList(), null),
            )
        try {
            Files.write(file, silentWav())
            player.setQueue(songs, startIndex = 1, startPlaying = true)
            val prepared = player.playPaused(songs[1], resource(songs[1].id, file.toString()))
            assertTrue(prepared, "playPaused failed: ${player.loadFailure.value?.category}")

            player.removeQueueItem(0)

            assertEquals(1L, player.latestQueuePlaybackRequestId)
            assertEquals(songs[1].id, player.currentSong.value?.id)
            assertEquals(0, player.currentMediaItemIndex.value)
        } finally {
            player.release()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun pendingQueueSongDoesNotReceiveThePreviousPlayersDuration() =
        runBlocking {
            val file = Files.createTempFile("metrolist-pending-duration", ".wav")
            val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
            val current = SongItem("current", "Current", null, emptyList(), null)
            val pending = SongItem("pending", "Pending", null, emptyList(), null)
            try {
                Files.write(file, silentWav())
                assertTrue(player.playPaused(current, resource(current.id, file.toString())))

                player.setQueue(listOf(pending), startIndex = 0, startPlaying = true)

                assertSame(pending, player.songQueue.value.single())
                assertNull(
                    player.songQueue.value
                        .single()
                        .duration,
                )
            } finally {
                player.release()
                Files.deleteIfExists(file)
            }
        }

    @Test
    fun pendingPlaybackStopsTheOldPlayerAndPublishesTheSelectedSong() {
        val file = Files.createTempFile("metrolist-pending-playback", ".wav")
        val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
        val current = SongItem("current", "Current", null, emptyList(), null)
        val pending = SongItem("pending", "Pending", null, emptyList(), null)
        try {
            Files.write(file, silentWav())
            assertTrue(player.playPaused(current, resource(current.id, file.toString())))

            player.beginPendingPlayback(pending)

            assertSame(pending, player.currentSong.value)
            assertNull(player.currentPlaybackResource.value)
            assertEquals(0L, player.currentPlaybackInstanceId)
            assertFalse(player.isPlaying.value)
        } finally {
            player.release()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun stalePendingTransitionCannotReplaceNewSelection() {
        val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
        val first = SongItem("first", "First", null, emptyList(), null)
        val second = SongItem("second", "Second", null, emptyList(), null)

        val staleTransitionId = player.beginPendingPlayback(first)
        player.beginPendingPlayback(second)

        assertFalse(player.playPending(first, "missing-file", staleTransitionId))
        assertSame(second, player.currentSong.value)
        assertEquals(0L, player.currentPlaybackInstanceId)
        player.release()
    }

    @Test
    fun failedPausedLoadRetainsCurrentSongForRetry() {
        val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
        val song = SongItem("id", "Title", null, emptyList(), null)

        assertFalse(
            player.playPaused(
                song,
                PlaybackResource(
                    videoId = song.id,
                    url = "/path/that/does/not/exist.wav",
                    headers = emptyMap(),
                    loudnessDb = null,
                    expiresAt = null,
                ),
            ),
        )
        assertNotNull(player.currentSong.value)
        assertNotNull(player.loadFailure.value)

        player.release()
    }

    @Test
    fun failedRecoveryKeepsPreparedPlaybackResource() {
        val file = Files.createTempFile("metrolist-player-recovery", ".wav")
        val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
        try {
            Files.write(file, silentWav())
            val song = SongItem("id", "Title", null, emptyList(), null)
            val original = resource(song.id, file.toString())

            val prepared = player.playPaused(song, original)
            assertTrue(prepared, "playPaused failed: ${player.loadFailure.value?.category}")
            assertFalse(player.playRecovered(song, resource(song.id, "/missing/recovery.wav"), 500, false))

            assertSame(original, player.currentPlaybackResource.value)
            assertEquals(song.id, player.currentSong.value?.id)
        } finally {
            player.release()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun recoveryHandoffUsesTheLiveSourcePositionAndIntent() {
        val file = Files.createTempFile("metrolist-player-handoff", ".wav")
        val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
        try {
            Files.write(file, silentWav())
            val song = SongItem("id", "Title", null, emptyList(), null)
            val resource = resource(song.id, file.toString())
            assertTrue(player.playPaused(song, resource))
            player.resume()
            assertTrue(player.wantsPlayback)
            var observedPosition = -1L
            val originalInstance = player.currentPlaybackInstanceId

            assertTrue(
                player.playRecovered(
                    song = song,
                    resource = resource,
                    positionMs = 50L,
                    resumePlayback = true,
                    handoffPosition = { position ->
                        observedPosition = position
                        player.pause()
                        250L
                    },
                    shouldCommit = { player.currentPlaybackInstanceId == originalInstance },
                    recoveryPlaybackInstanceId = originalInstance,
                ),
            )

            assertTrue(observedPosition >= 0L)
            assertEquals(250L, player.currentPosition.value)
            assertFalse(player.wantsPlayback)
        } finally {
            player.release()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun recoveryPrebufferRetainsRejectedRangeStatusAndOutgoingPlayback() {
        val file = Files.createTempFile("metrolist-player-rejected-recovery", ".wav")
        val bytes = silentWav(samples = 8_000 * 120)
        Files.write(file, bytes)
        try {
            for (warmReplacement in listOf(false, true)) {
                val reject = AtomicBoolean()
                val rejectedRequests = AtomicInteger()
                val client =
                    OkHttpClient
                        .Builder()
                        .addInterceptor { chain ->
                            val (start, end) =
                                checkNotNull(chain.request().header("Range")).removePrefix("bytes=").split('-').map(String::toInt)
                            val rejected = reject.get()
                            if (rejected) rejectedRequests.incrementAndGet()
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(if (rejected) 403 else 206)
                                .message(if (rejected) "Forbidden" else "Partial Content")
                                .header("Content-Range", "bytes $start-$end/${bytes.size}")
                                .body((if (rejected) byteArrayOf() else bytes.copyOfRange(start, end + 1)).toResponseBody())
                                .build()
                        }.build()
                val output = TestPlaybackLine()
                lateinit var sourceRenderer: AudioRenderer
                var created = 0
                val player =
                    MusicPlayer(mediaHttpClient = client, rendererFactory = { rate, channels ->
                        if (++created == 1) {
                            AudioRenderer(rate, channels, output.line).also { sourceRenderer = it }
                        } else {
                            testAudioRenderer(rate, channels)
                        }
                    })
                try {
                    val song = SongItem("id", "Title", null, emptyList(), null)
                    val original = resource(song.id, file.toString())
                    player.play(song, original)
                    output.awaitBuffered()
                    sourceRenderer.resetPosition(59_500L)
                    val instance = player.currentPlaybackInstanceId
                    var failure: Throwable? = null
                    assertFalse(
                        player.playRecovered(
                            song,
                            resource(song.id, "https://media.example.test/audio.wav").copy(
                                contentLengthBytes = bytes.size.toLong(),
                                rangeChunkSizeBytes = 65_536L,
                            ),
                            positionMs = 0L,
                            resumePlayback = true,
                            handoffPosition = {
                                reject.set(true)
                                it
                            },
                            onPrepareFailure = { failure = it },
                            recoveryPlaybackInstanceId = instance,
                            warmReplacement = warmReplacement,
                        ),
                    )
                    assertTrue(rejectedRequests.get() > 0)
                    assertEquals(403, failure?.findPlaybackHttpStatusCode(), failure.toString())
                    assertEquals(PlaybackFailureCategory.CDN_FORBIDDEN, PlaybackFailureClassifier.fromThrowable(assertNotNull(failure)))
                    assertSame(original, player.currentPlaybackResource.value)
                    assertEquals(instance, player.currentPlaybackInstanceId)
                    assertTrue(output.line.isRunning)
                    assertTrue(player.wantsPlayback)
                    assertNull(player.loadFailure.value)
                } finally {
                    player.release()
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdownNow()
                }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun cancelledWarmReplacementNeverPausesTheSource() {
        val file = Files.createTempFile("metrolist-player-warm-cancel", ".wav")
        val output = TestPlaybackLine()
        var created = 0
        val player =
            MusicPlayer(rendererFactory = { rate, channels ->
                if (++created == 1) AudioRenderer(rate, channels, output.line) else testAudioRenderer(rate, channels)
            })
        try {
            Files.write(file, silentWav(samples = 8_000 * 10))
            val song = SongItem("id", "Title", null, emptyList(), null)
            val original = resource(song.id, file.toString())
            player.play(song, original)
            assertEquals(original, player.currentPlaybackResource.value)
            output.awaitBuffered()
            val instance = player.currentPlaybackInstanceId
            var current = true
            assertFalse(
                player.playRecovered(
                    song,
                    original.copy(profileId = "WEB_REMIX__po"),
                    positionMs = 0L,
                    resumePlayback = true,
                    handoffPosition = {
                        assertTrue(output.line.isRunning)
                        current = false
                        it
                    },
                    shouldCommit = { current },
                    recoveryPlaybackInstanceId = instance,
                    warmReplacement = true,
                ),
            )
            assertSame(original, player.currentPlaybackResource.value)
            assertEquals(instance, player.currentPlaybackInstanceId)
            assertTrue(output.line.isRunning)
            assertTrue(player.wantsPlayback)
        } finally {
            player.release()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun failedWarmRecoveryRetainsTheAudibleSourceAndPlaybackInstance() {
        val file = Files.createTempFile("metrolist-player-warm-source", ".wav")
        val empty = Files.createTempFile("metrolist-player-warm-empty", ".wav")
        val output = TestPlaybackLine()
        var created = 0
        val player =
            MusicPlayer(rendererFactory = { rate, channels ->
                if (++created == 1) AudioRenderer(rate, channels, output.line) else testAudioRenderer(rate, channels)
            })
        try {
            Files.write(file, silentWav(samples = 8_000 * 10))
            Files.write(empty, silentWav(samples = 0))
            val song = SongItem("id", "Title", null, emptyList(), null)
            val original = resource(song.id, file.toString())
            player.play(song, original)
            output.awaitBuffered()
            val instance = player.currentPlaybackInstanceId
            assertFalse(
                player.playRecovered(
                    song,
                    resource(song.id, empty.toString()),
                    positionMs = 0L,
                    resumePlayback = true,
                    handoffPosition = { it },
                    recoveryPlaybackInstanceId = instance,
                    warmReplacement = true,
                ),
            )
            assertSame(original, player.currentPlaybackResource.value)
            assertEquals(instance, player.currentPlaybackInstanceId)
            assertEquals(2, created)
            assertTrue(output.line.isRunning)
            assertTrue(player.wantsPlayback)
        } finally {
            player.release()
            Files.deleteIfExists(file)
            Files.deleteIfExists(empty)
        }
    }

    @Test
    fun warmReplacementHandsOffFromAStalledSource() {
        val file = Files.createTempFile("metrolist-player-warm-stalled", ".wav")
        val output = TestPlaybackLine()
        var created = 0
        val player =
            MusicPlayer(rendererFactory = { rate, channels ->
                if (++created == 1) AudioRenderer(rate, channels, output.line) else testAudioRenderer(rate, channels)
            })
        try {
            Files.write(file, silentWav(samples = 8_000 * 10))
            val song = SongItem("id", "Title", null, emptyList(), null)
            val original = resource(song.id, file.toString())
            player.play(song, original)
            output.awaitBuffered()
            val fresh = original.copy(profileId = "WEB_REMIX__po")
            assertTrue(
                player.playRecovered(
                    song,
                    fresh,
                    positionMs = 0L,
                    resumePlayback = true,
                    handoffPosition = { it },
                    recoveryPlaybackInstanceId = player.currentPlaybackInstanceId,
                    warmReplacement = true,
                ),
            )
            assertEquals(fresh.profileId, player.currentPlaybackResource.value?.profileId)
            assertTrue(player.wantsPlayback)
        } finally {
            player.release()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun warmReplacementNearTheEndUsesTheCurrentPosition() {
        val file = Files.createTempFile("metrolist-player-warm-end", ".wav")
        val output = TestPlaybackLine()
        var created = 0
        lateinit var sourceRenderer: AudioRenderer
        val player =
            MusicPlayer(rendererFactory = { rate, channels ->
                if (++created == 1) {
                    AudioRenderer(rate, channels, output.line).also { sourceRenderer = it }
                } else {
                    testAudioRenderer(rate, channels)
                }
            })
        try {
            Files.write(file, silentWav(samples = 8_000 * 10))
            val song = SongItem("id", "Title", null, emptyList(), null)
            val original = resource(song.id, file.toString())
            player.play(song, original)
            output.awaitBuffered()
            sourceRenderer.resetPosition(9_500L)
            val fresh = original.copy(profileId = "WEB_REMIX__po")
            assertTrue(
                player.playRecovered(
                    song,
                    fresh,
                    positionMs = 0L,
                    resumePlayback = true,
                    handoffPosition = { it },
                    recoveryPlaybackInstanceId = player.currentPlaybackInstanceId,
                    warmReplacement = true,
                ),
            )
            assertEquals(fresh.profileId, player.currentPlaybackResource.value?.profileId)
            assertEquals(9_500L, player.currentPosition.value)
            assertTrue(player.wantsPlayback)
        } finally {
            player.release()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun warmReplacementPreservesThePausedSourcePosition() {
        val file = Files.createTempFile("metrolist-player-warm-paused", ".wav")
        lateinit var renderer: AudioRenderer
        val player = MusicPlayer(rendererFactory = { rate, channels -> testAudioRenderer(rate, channels).also { renderer = it } })
        try {
            Files.write(file, silentWav())
            val song = SongItem("id", "Title", null, emptyList(), null)
            val original = resource(song.id, file.toString())
            assertTrue(player.playPaused(song, original))
            renderer.resetPosition(250L)
            val fresh = original.copy(profileId = "WEB_REMIX__po")
            assertTrue(
                player.playRecovered(
                    song,
                    fresh,
                    positionMs = 0L,
                    resumePlayback = true,
                    handoffPosition = { it },
                    recoveryPlaybackInstanceId = player.currentPlaybackInstanceId,
                    warmReplacement = true,
                ),
            )
            assertEquals(fresh.profileId, player.currentPlaybackResource.value?.profileId)
            assertEquals(250L, player.currentPosition.value)
            assertFalse(player.wantsPlayback)
        } finally {
            player.release()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun promotionPublishesQueueSongAndProgressTogetherAfterSeekAndPause() {
        val currentFile = Files.createTempFile("metrolist-player-gapless-current", ".wav")
        val nextFile = Files.createTempFile("metrolist-player-gapless-next", ".wav")
        val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
        try {
            Files.write(currentFile, silentWav())
            Files.write(nextFile, silentWav())
            val current = SongItem("current", "Current", null, emptyList(), null)
            val next = SongItem("next", "Next", null, emptyList(), null)
            val currentResource = resource(current.id, currentFile.toString())
            val nextResource = resource(next.id, nextFile.toString())

            assertTrue(player.playPaused(current, currentResource))
            val token =
                assertNotNull(
                    player.prepareNextTrack(
                        resource = nextResource,
                        expectedCurrentPlaybackInstanceId = player.currentPlaybackInstanceId,
                        expectedCurrentVideoId = current.id,
                        startPaused = true,
                    ),
                )

            assertTrue(player.isPreloadedNextActive(token))
            assertFalse(player.isPreloadedNextActive(token.copy(generation = token.generation + 1L)))
            assertEquals(0L, player.nextPlayerPosition)
            val queue = MusicQueue().apply { setQueue(listOf(current, next)) }
            player.seekTo(500L)
            player.pause()

            assertTrue(
                player.swapToNext(token, nextResource) {
                    queue.getNext()
                    player.setCurrentSong(next, nextResource)
                },
            )
            assertEquals(next.id, player.currentPlaybackResource.value?.videoId)
            assertEquals(next.id, player.currentSong.value?.id)
            assertEquals(next.id, queue.currentSong()?.id)
            assertEquals(1, queue.currentIndex.value)
            assertTrue(player.currentPosition.value < 500L)
            assertTrue(player.isPlaying.value)
            assertFalse(player.isPreloadedNextActive(token))
            assertFalse(player.swapToNext(token, nextResource) { queue.getNext() })
            assertEquals(1, queue.currentIndex.value)
        } finally {
            player.release()
            Files.deleteIfExists(currentFile)
            Files.deleteIfExists(nextFile)
        }
    }

    @Test
    fun normalizationSurvivesPreloadLiveChangesPromotionAndCancellation() =
        runBlocking {
            val file = Files.createTempFile("metrolist-normalization", ".wav")
            val player = MusicPlayer(rendererFactory = ::testAudioRenderer)
            try {
                Files.write(file, silentWav())
                Settings.shared.audioNormalization = true
                val outgoing = SongItem("outgoing", "Outgoing", null, emptyList(), null)
                val incoming = SongItem("incoming", "Incoming", null, emptyList(), null)
                val outgoingResource = resource(outgoing.id, file.toString()).copy(loudnessDb = 0.0)
                val incomingResource = resource(incoming.id, file.toString()).copy(loudnessDb = -6.0)
                player.setVolume(80)
                player.setSleepTimerGain(0.5f)
                assertTrue(player.playPaused(outgoing, outgoingResource))
                assertEquals(0.17867343f, player.outputVolume, 0.000001f)
                val token = assertNotNull(player.prepareNextTrack(incomingResource, startPaused = true))
                assertEquals(0.17867343f, player.outputVolume, 0.000001f)
                val outgoingPlayer =
                    MusicPlayer::class.java
                        .getDeclaredField(
                            "audioPlayer",
                        ).apply { isAccessible = true }
                        .get(player) as AudioPlayer
                val incomingPlayer =
                    MusicPlayer::class.java
                        .getDeclaredField(
                            "nextPlayer",
                        ).apply { isAccessible = true }
                        .get(player) as AudioPlayer
                assertEquals(0f, incomingPlayer.getVolume())
                player.setTransitionVolume(50f)
                player.setNextVolume(50f)
                assertEquals(0.08933672f, player.outputVolume, 0.000001f)
                assertEquals(0.08933672f, outgoingPlayer.getVolume(), 0.000001f)
                assertEquals(0.1782502f, incomingPlayer.getVolume(), 0.000001f)
                Settings.shared.loudnessLevel = LoudnessLevel.QUIET
                player.setVolume(80)
                assertEquals(0.05023772f, outgoingPlayer.getVolume(), 0.000001f)
                assertEquals(0.10023745f, incomingPlayer.getVolume(), 0.000001f)
                Settings.shared.audioNormalization = false
                player.setVolume(80)
                assertEquals(0.2f, outgoingPlayer.getVolume(), 0.000001f)
                assertEquals(0.2f, incomingPlayer.getVolume(), 0.000001f)
                Settings.shared.audioNormalization = true
                player.setTransitionVolume(0f)
                player.setNextVolume(100f)
                assertEquals(0.2004749f, incomingPlayer.getVolume(), 0.000001f)
                assertTrue(player.swapToNext(token, incomingResource))
                assertEquals(0.2004749f, incomingPlayer.getVolume(), 0.000001f)
                assertEquals(0.2004749f, player.outputVolume, 0.000001f)
                player.setTransitionVolume(50f)
                player.cancelNext()
                assertEquals(0.2004749f, player.outputVolume, 0.000001f)
                assertEquals(0.8f, player.volume.value)
                player.attachPlaybackResource(outgoingResource)
                assertEquals(incomingResource, player.currentPlaybackResource.value)
                player.attachPlaybackResource(incomingResource.copy(loudnessDb = null))
                assertEquals(0.4f, player.outputVolume, 0.000001f)

                fun pcmGain(audio: AudioPlayer): Float =
                    (
                        AudioPlayer::class.java
                            .getDeclaredField(
                                "renderer",
                            ).apply { isAccessible = true }
                            .get(audio) as AudioRenderer
                    ).normalizationGain
                Settings.shared.loudnessLevel = LoudnessLevel.BALANCED
                player.attachPlaybackResource(incomingResource.copy(perceptualLoudnessDb = -17.0))
                assertEquals(1.4125376f, pcmGain(incomingPlayer), 0.000001f)
                assertEquals(0.4f, incomingPlayer.getVolume(), 0.000001f)
                val boostedResource = outgoingResource.copy(perceptualLoudnessDb = -16.0)
                val boostedToken = assertNotNull(player.prepareNextTrack(boostedResource, startPaused = true))
                val boostedPlayer =
                    MusicPlayer::class.java
                        .getDeclaredField(
                            "nextPlayer",
                        ).apply { isAccessible = true }
                        .get(player) as AudioPlayer
                assertEquals(1.2589254f, pcmGain(boostedPlayer), 0.000001f)
                assertEquals(1.4125376f, pcmGain(incomingPlayer), 0.000001f)
                player.setTransitionVolume(50f)
                player.setNextVolume(50f)
                assertEquals(0.2f, incomingPlayer.getVolume(), 0.000001f)
                assertEquals(0.2f, boostedPlayer.getVolume(), 0.000001f)
                Settings.shared.audioNormalization = false
                player.setVolume(80)
                assertEquals(1f, pcmGain(incomingPlayer))
                Settings.shared.audioNormalization = true
                player.setNextVolume(100f)
                player.setTransitionVolume(0f)
                assertTrue(player.swapToNext(boostedToken, boostedResource))
                assertEquals(1.2589254f, pcmGain(boostedPlayer), 0.000001f)
                assertEquals(0.4f, boostedPlayer.getVolume(), 0.000001f)
            } finally {
                player.release()
                Files.deleteIfExists(file)
            }
        }

    @Test
    fun pendingPodcastDoesNotCheckpointTheClosingTracksPosition() =
        runBlocking {
            val file = Files.createTempFile("metrolist-pending-podcast", ".wav")
            val closing = CountDownLatch(1)
            val finishClose = CountDownLatch(1)
            val output = TestPlaybackLine(automatic = false)
            val line =
                Proxy.newProxyInstance(SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)) { _, method, args ->
                    if (method.name == "close") {
                        closing.countDown()
                        check(finishClose.await(5, TimeUnit.SECONDS))
                    }
                    method.invoke(output.line, *(args ?: emptyArray()))
                } as SourceDataLine
            lateinit var renderer: AudioRenderer
            val player = MusicPlayer(rendererFactory = { rate, channels -> AudioRenderer(rate, channels, line).also { renderer = it } })
            val podcast =
                SongItem("podcast", "Podcast", null, emptyList(), null, musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE")
            val savedPositions = mutableMapOf(podcast.id to 60_000L)
            val progress =
                PodcastPlaybackProgress(
                    writerScope = this,
                    save = { song, position, _, _ -> savedPositions[song.id] = position },
                    onError = { throw it },
                )
            val observer =
                launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    player.currentSong.first { it == podcast }
                    progress.update(
                        player.currentSong.value,
                        player.currentPlaybackPositionMs(),
                        player.duration.value,
                        player.isPlaying.value,
                    )
                }
            try {
                Files.write(file, silentWav())
                val song = SongItem("outgoing", "Outgoing", null, emptyList(), null)
                assertTrue(player.playPaused(song, resource(song.id, file.toString())))
                renderer.resetPosition(120_000L)
                assertEquals(120_000L, player.currentPlaybackPositionMs())
                player.setPlaybackSpeed(1f)
                withTimeout(5_000L) { player.currentPosition.first { it == 120_000L } }

                player.beginPendingPlayback(podcast)
                assertTrue(closing.await(5, TimeUnit.SECONDS))
                assertEquals(120_000L, renderer.getPlayedPositionMs(), "The old renderer has not finished closing")
                assertEquals(0L, player.currentPlaybackInstanceId)
                assertEquals(0L, player.currentPlaybackPositionMs())

                observer.join()
                progress.update(player.currentSong.value, player.currentPlaybackPositionMs(), player.duration.value, player.isPlaying.value)
                progress.flush()?.join()
                assertEquals(60_000L, savedPositions[podcast.id], "Unresolved playback must preserve the saved resume point")
            } finally {
                observer.cancel()
                finishClose.countDown()
                player.release()
                Files.deleteIfExists(file)
            }
        }

    private fun resource(
        videoId: String,
        url: String,
    ) = PlaybackResource(
        videoId = videoId,
        url = url,
        headers = emptyMap(),
        loudnessDb = null,
        expiresAt = null,
    )

    private fun silentWav(samples: Int = 8_000): ByteArray {
        val sampleRate = 8_000
        val dataSize = samples * 2
        val bytes = ByteArray(44 + dataSize)

        fun putAscii(
            offset: Int,
            value: String,
        ) = value.toByteArray(Charsets.US_ASCII).copyInto(bytes, offset)

        fun putInt(
            offset: Int,
            value: Int,
        ) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)

        fun putShort(
            offset: Int,
            value: Int,
        ) = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())

        putAscii(0, "RIFF")
        putInt(4, bytes.size - 8)
        putAscii(8, "WAVEfmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2)
        putShort(32, 2)
        putShort(34, 16)
        putAscii(36, "data")
        putInt(40, dataSize)
        return bytes
    }
}
