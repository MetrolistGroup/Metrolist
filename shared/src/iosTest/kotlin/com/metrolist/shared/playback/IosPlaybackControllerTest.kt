@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)

package com.metrolist.shared.playback

import androidx.compose.runtime.snapshots.Snapshot
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.responseDecodeDispatcher
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.listentogether.IosListenTogetherPlayback
import com.metrolist.shared.listentogether.ListenTogetherManager
import com.metrolist.shared.listentogether.ListenTogetherTrackResolver
import com.metrolist.shared.listentogether.PlaybackReadiness
import com.metrolist.shared.listentogether.network.MessageTypes
import com.metrolist.shared.listentogether.network.PlaybackActionPayload
import com.metrolist.shared.listentogether.network.PlaybackActions
import com.metrolist.shared.listentogether.network.PortableMessageCodec
import com.metrolist.shared.listentogether.network.ReconnectedPayload
import com.metrolist.shared.listentogether.network.RoomState
import com.metrolist.shared.listentogether.network.SyncStatePayload
import com.metrolist.shared.listentogether.network.TrackInfo
import com.metrolist.shared.listentogether.toSongItem
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.storage.IosPlaybackCache
import com.metrolist.shared.ui.playback.PlaybackError
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.StreamResolver
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.currentItem
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorFileDoesNotExist
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSUUID
import platform.Foundation.NSUnderlyingErrorKey
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import com.russhwolf.settings.Settings as KmpSettings

class IosPlaybackControllerTest {
    private val originalDecodeDispatcher = responseDecodeDispatcher

    @Test
    fun nativeNetworkFailuresStopWhileMissingFilesCanSkip() {
        assertEquals(PlaybackFailureCategory.TIMEOUT, iosNativePlaybackFailureCategory(NSError(NSURLErrorDomain, NSURLErrorTimedOut, null)))
        assertEquals(
            PlaybackFailureCategory.NETWORK,
            iosNativePlaybackFailureCategory(NSError(NSURLErrorDomain, NSURLErrorNotConnectedToInternet, null)),
        )
        assertEquals(
            PlaybackFailureCategory.UNAVAILABLE,
            iosNativePlaybackFailureCategory(NSError(NSURLErrorDomain, NSURLErrorFileDoesNotExist, null)),
        )
    }

    @Test
    fun resolutionFailuresHonorAutoSkipAndBoundRepeatAllFailureLoops() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            Settings.shared.autoLoadMore = false
            Settings.shared.persistentQueue = false
            for (autoSkip in listOf(false, true)) {
                Settings.shared.autoSkipNextOnError = autoSkip
                val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
                val player = MusicPlayer()
                val requests = mutableListOf<String>()
                val resolver =
                    object : StreamResolver by fileResolver {
                        override suspend fun resolve(
                            videoId: String,
                            hints: ContentHints,
                            audioQuality: AudioQuality,
                            sourceSong: SongItem?,
                        ): PlaybackResource {
                            requests += videoId
                            throw IllegalStateException("Unavailable track")
                        }
                    }
                val controllerScope = CoroutineScope(backgroundScope.coroutineContext + Job())
                try {
                    val controller = IosPlaybackController(player, resolver, database, controllerScope, persistenceDispatcher = dispatcher)
                    val songs = listOf(song("first"), song("second"))
                    controller.playQueue(songs, 0, null)
                    controller.toggleRepeat()
                    runCurrent()
                    assertEquals(if (autoSkip) listOf("first", "second", "first", "second") else listOf("first"), requests)
                    assertEquals(if (autoSkip) 1 else 0, controller.state.value.currentQueueIndex)
                    assertEquals(PlaybackError.STREAM_UNAVAILABLE, controller.state.value.error)
                    assertFalse(controller.state.value.isPlaying)
                    assertEquals(songs, controller.state.value.queue)
                } finally {
                    controllerScope.cancel()
                    player.release()
                    database.close()
                }
            }
        }

    @Test
    fun nativeFailuresHonorAutoSkipAfterRecoveryWithoutLosingTheQueue() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            Settings.shared.autoLoadMore = false
            Settings.shared.persistentQueue = false
            for (autoSkip in listOf(false, true)) {
                Settings.shared.autoSkipNextOnError = autoSkip
                val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
                val player = MusicPlayer()
                val controllerScope = CoroutineScope(backgroundScope.coroutineContext + Job())
                try {
                    val controller =
                        IosPlaybackController(player, fileResolver, database, controllerScope, persistenceDispatcher = dispatcher)
                    val songs = listOf(song("first"), song("second"))
                    controller.playQueue(songs, 0, null)
                    runCurrent()
                    player.handlePlaybackFailed()
                    runCurrent()
                    assertEquals(if (autoSkip) 1 else 0, controller.state.value.currentQueueIndex)
                    assertEquals(songs, controller.state.value.queue)
                    if (!autoSkip) {
                        assertEquals(PlaybackError.STREAM_UNAVAILABLE, controller.state.value.error)
                        assertFalse(controller.state.value.isPlaying)
                        controller.togglePlayPause()
                        runCurrent()
                        assertNull(controller.state.value.error)
                        assertEquals(0, controller.state.value.currentQueueIndex)
                    }
                } finally {
                    controllerScope.cancel()
                    player.release()
                    database.close()
                }
            }
        }

    @Test
    fun headphoneRemovalDuringInterruptionCancelsAutomaticResume() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val player = MusicPlayer()
            try {
                val first = song("first")
                player.play(first, fileResolver.resolve(first.id, ContentHints(), AudioQuality.MP4, first))
                assertTrue(player.isPlaying.value)
                postAudioInterruption(AVAudioSessionInterruptionTypeBegan)
                assertFalse(player.isPlaying.value)
                NSNotificationCenter.defaultCenter.postNotificationName(
                    AVAudioSessionRouteChangeNotification,
                    `object` = null,
                    userInfo =
                        mapOf(
                            AVAudioSessionRouteChangeReasonKey to
                                NSNumber(unsignedLong = AVAudioSessionRouteChangeReasonOldDeviceUnavailable),
                        ),
                )
                postAudioInterruption(AVAudioSessionInterruptionTypeEnded)
                assertFalse(player.isPlaying.value)
                assertFalse(player.wantsPlayback)
            } finally {
                player.release()
            }
        }

    @Test
    fun unavailableQueueSeedsResolveTheNormalizedSelectionAndEmptySelectionsLeavePlaybackAlone() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.autoLoadMore = false
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            val requests = mutableListOf<String>()
            val resolution = CompletableDeferred<Unit>()
            val resolver =
                object : StreamResolver by fileResolver {
                    override suspend fun resolve(
                        videoId: String,
                        hints: ContentHints,
                        audioQuality: AudioQuality,
                        sourceSong: SongItem?,
                    ): PlaybackResource {
                        requests += videoId
                        check(videoId != "unavailable")
                        resolution.await()
                        return fileResolver.resolve(videoId, hints, audioQuality, sourceSong)
                    }
                }
            val controller = IosPlaybackController(player, resolver, database, backgroundScope)
            try {
                val unavailable = song("unavailable").copy(isUnavailable = true)
                val songs = listOf(unavailable, song("first"), song("second"))
                controller.playQueue(songs, 0, "Album")
                runCurrent()
                assertEquals(listOf("first"), requests)
                assertEquals(songs[1], controller.state.value.currentSong)
                assertEquals(songs.drop(1), controller.state.value.queue)
                assertEquals(0, controller.state.value.currentQueueIndex)
                val pendingState = controller.state.value
                controller.playQueue(listOf(unavailable), 0, "Unavailable album")
                controller.play(unavailable)
                val unavailableRemote = launch { controller.playQueueFromListenTogether(listOf(unavailable), 0, "Unavailable room") }
                runCurrent()
                assertTrue(unavailableRemote.isCompleted)
                assertEquals(pendingState, controller.state.value)
                resolution.complete(Unit)
                runCurrent()
                assertEquals(songs[1], player.currentSong.value)
                assertNull(controller.state.value.error)
                Settings.shared.keepPausedOnSongChange = true
                player.pause()
                val repeated = song("first").copy()
                val duplicates = songs + repeated
                controller.play(repeated, duplicates)
                runCurrent()
                assertEquals(2, player.currentMediaItemIndex.value)
                assertEquals(duplicates.drop(1), player.songQueue.value)
                assertFalse(player.isPlaying.value)
                val sameOccurrences = songs + songs[1]
                controller.playQueue(sameOccurrences, sameOccurrences.lastIndex, "Repeated album")
                runCurrent()
                assertEquals(2, player.currentMediaItemIndex.value)
                assertFalse(player.isPlaying.value)
                Settings.shared.persistentShuffleAcrossQueues = true
                player.toggleShuffle()
                controller.play(unavailable, songs)
                runCurrent()
                assertTrue(player.isShuffleEnabled)
                assertEquals(songs[1], player.currentSong.value)
                assertEquals(player.currentSong.value, player.songQueue.value[player.currentMediaItemIndex.value])
                assertFalse(player.isPlaying.value)
                assertFalse("unavailable" in requests)
            } finally {
                resolution.complete(Unit)
                backgroundScope.cancel()
                player.release()
                database.close()
            }
        }

    @Test
    fun audioSessionActivationWaitsForPlaybackAndResumesAfterInterruption() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            var activations = 0
            val player = MusicPlayer(activateAudioSession = { activations++ }, outputVolume = { 1f })
            try {
                assertEquals(0, activations)
                val first = song("first")
                val resource =
                    fileResolver
                        .resolve(
                            first.id,
                            ContentHints(),
                            AudioQuality.MP4,
                            first,
                        ).copy(mimeType = "application/vnd.apple.mpegurl")
                player.play(first, resource, startPlaying = false)
                runCurrent()
                assertEquals(0, activations)
                player.resume()
                assertEquals(1, activations)
                postAudioInterruption(AVAudioSessionInterruptionTypeBegan)
                assertFalse(player.isPlaying.value)
                postAudioInterruption(AVAudioSessionInterruptionTypeEnded)
                assertEquals(2, activations)
                assertTrue(player.isPlaying.value)
                player.pause()
                player.play(first, resource)
                assertEquals(3, activations)
            } finally {
                player.release()
            }
        }

    @Test
    fun tempoAndPitchPersistIndependentlyAndVarispeedLinksUpdates() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            val controllerScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val controller = IosPlaybackController(player, fileResolver, database, controllerScope)
            try {
                controller.setPlaybackSpeed(1.25f)
                controller.setPlaybackPitch(0.75f)
                assertEquals(1.25f, Settings.shared.playbackSpeed)
                assertEquals(0.75f, Settings.shared.playbackPitch)
                assertEquals(1.25f, player.playbackSpeed.value)
                assertEquals(0.75f, player.playbackPitch.value)
                controller.setPlaybackSpeed(Float.NaN)
                controller.setPlaybackPitch(Float.NEGATIVE_INFINITY)
                assertEquals(1.25f, Settings.shared.playbackSpeed)
                assertEquals(0.75f, Settings.shared.playbackPitch)
                Settings.shared.varispeed = true
                controller.setPlaybackSpeed(1.5f)
                assertEquals(1.5f, Settings.shared.playbackSpeed)
                assertEquals(1.5f, Settings.shared.playbackPitch)
                assertEquals(1.5f, player.playbackPitch.value)
            } finally {
                controllerScope.cancel()
                player.release()
                database.close()
            }
        }

    @Test
    fun idleQueueInsertionsSelectTheFirstSongWithoutStartingPlayback() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.autoLoadMore = false
            for (playNext in listOf(false, true)) {
                val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
                val player = MusicPlayer()
                val controllerScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
                val controller =
                    IosPlaybackController(
                        player,
                        fileResolver,
                        database,
                        controllerScope,
                        persistenceDispatcher = StandardTestDispatcher(testScheduler),
                    )
                try {
                    val first = song("first")
                    if (playNext) controller.playNext(listOf(first)) else controller.addToQueue(listOf(first))
                    runCurrent()
                    assertEquals(
                        first.id,
                        controller.state.value.currentSong
                            ?.id,
                    )
                    assertEquals(0, controller.state.value.currentQueueIndex)
                    assertFalse(player.isPlaying.value)
                } finally {
                    controllerScope.cancel()
                    player.release()
                    database.close()
                }
            }
        }

    @Test
    fun optingInKeepsPausedIntentIncludingPendingRequestsAndRepeatAll() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.keepPausedOnSongChange = true
            Settings.shared.autoLoadMore = false
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            val controller = IosPlaybackController(player, fileResolver, database, backgroundScope)
            try {
                val songs = listOf(song("first"), song("second"), song("third"))
                controller.addToQueue(songs)
                runCurrent()
                assertFalse(player.isPlaying.value)
                controller.skipToNext()
                controller.skipToNext()
                runCurrent()
                assertEquals("third", player.currentSong.value?.id)
                assertFalse(player.isPlaying.value)
                controller.skipToPrevious()
                runCurrent()
                assertEquals("second", player.currentSong.value?.id)
                assertFalse(player.isPlaying.value)
                controller.skipToNext()
                controller.toggleRepeat()
                controller.skipToNext()
                runCurrent()
                assertEquals("first", player.currentSong.value?.id)
                assertFalse(player.isPlaying.value)
                controller.playQueueIndex(1)
                runCurrent()
                assertEquals("second", player.currentSong.value?.id)
                assertFalse(player.isPlaying.value)
                controller.play(songs[0], songs)
                runCurrent()
                assertFalse(player.isPlaying.value)
                Settings.shared.keepPausedOnSongChange = false
                player.skipToNext()
                runCurrent()
                assertEquals("second", player.currentSong.value?.id)
                assertFalse(player.isPlaying.value)
                player.skipToPrevious()
                runCurrent()
                assertEquals("first", player.currentSong.value?.id)
                assertFalse(player.isPlaying.value)
                controller.skipToNext()
                runCurrent()
                assertTrue(player.isPlaying.value)
                controller.skipToNext()
                runCurrent()
                assertEquals("third", player.currentSong.value?.id)
                assertTrue(player.isPlaying.value)
            } finally {
                player.release()
                database.close()
            }
        }

    private fun TestScope.persistedState(settings: Settings): QueuePlaybackState {
        runCurrent()
        return decodeQueuePlaybackState(settings.persistentPlaybackState, settings.persistentPlaybackPosition)
    }

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        responseDecodeDispatcher = originalDecodeDispatcher
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun listenTogetherLocksLocalControlsButRemoteQueueAndLeaveWork() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.autoLoadMore = false
            Settings.shared.persistentShuffleAcrossQueues = true
            Settings.shared.playbackSpeed = 1.25f
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            val client = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
            val controller =
                IosPlaybackController(
                    player,
                    fileResolver,
                    database,
                    backgroundScope,
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                )
            val playback = IosListenTogetherPlayback(controller, player, backgroundScope)
            val manager =
                ListenTogetherManager(
                    callerScope = backgroundScope,
                    httpClient = client,
                    playback = playback,
                    resolver = ListenTogetherTrackResolver { it.toSongItem() },
                    monotonicClockMs = { testScheduler.currentTime },
                    wallClockMs = { testScheduler.currentTime },
                    timestampFormatter = Long::toString,
                    sessionPersistenceEnabled = false,
                )
            try {
                controller.bindListenTogether(manager)
                val old = song("old")
                player.setQueue(listOf(old, song("other")), startPlaying = false)
                player.play(old, fileResolver.resolve(old.id, ContentHints(), AudioQuality.MP4, old))
                assertTrue(player.isPlaying.value)
                player.toggleShuffle()
                manager.handleReconnected(
                    ReconnectedPayload(
                        roomCode = "ROOM1234",
                        userId = "guest",
                        state = RoomState("ROOM1234", "host", emptyList(), isPlaying = false, position = 0L, lastUpdate = 0L),
                        isHost = false,
                    ),
                )
                runCurrent()
                assertFalse(player.isPlaying.value)
                assertEquals(old, playback.currentSong.value)
                val remote = listOf(song("current"), song("next"), song("next"))
                val loading = launch { playback.playQueueFromRemote(remote, title = "Room") }
                runCurrent()
                loading.join()
                runCurrent()
                assertEquals(remote, controller.state.value.queue)
                assertFalse(controller.state.value.isShuffled)
                assertFalse(player.isPlaying.value)
                manager.handleBinary(
                    PortableMessageCodec().encode(
                        MessageTypes.SYNC_STATE,
                        SyncStatePayload(
                            remote.first().toTrackInfo(),
                            isPlaying = false,
                            position = 0L,
                            lastUpdate = 0L,
                            queue = remote.drop(1).map { it.toTrackInfo() },
                        ),
                    ),
                )
                runCurrent()
                playback.setPlayingFromRemote(true)
                postAudioInterruption(AVAudioSessionInterruptionTypeBegan)
                assertFalse(player.isPlaying.value)
                postAudioInterruption(AVAudioSessionInterruptionTypeEnded)
                assertTrue(player.isPlaying.value)
                postAudioInterruption(AVAudioSessionInterruptionTypeBegan)
                assertFalse(player.isPlaying.value)
                playback.setPlayingFromRemote(false)
                postAudioInterruption(AVAudioSessionInterruptionTypeEnded)
                assertFalse(player.isPlaying.value)
                controller.play(song("local"))
                controller.seekTo(12_000)
                controller.skipToNext()
                controller.togglePlayPause()
                controller.addToQueue(listOf(song("local")))
                controller.removeQueueItems(setOf(1))
                controller.moveQueueItem(1, 2)
                controller.toggleShuffle()
                controller.setPlaybackSpeed(2f)
                runCurrent()
                assertEquals(remote, controller.state.value.queue)
                assertEquals(0L, player.currentPosition.value)
                assertEquals(1f, player.playbackSpeed())
                assertEquals(MPRemoteCommandHandlerStatusCommandFailed, player.handleRemoteSkip(next = true))
                assertFalse(MPRemoteCommandCenter.sharedCommandCenter().playCommand.enabled)
                playback.replaceUpcomingQueueFromRemote(listOf(song("replacement"), song("replacement")))
                playback.addToQueueFromRemote(song("last"))
                runCurrent()
                assertEquals(
                    listOf("current", "replacement", "replacement", "last"),
                    controller.state.value.queue
                        .map { it.id },
                )
                manager.leaveRoomAndWait()
                runCurrent()
                assertFalse(manager.isGuestPlaybackLocked)
                assertTrue(MPRemoteCommandCenter.sharedCommandCenter().playCommand.enabled)
                assertEquals(1.25f, player.playbackSpeed())
                controller.addToQueue(listOf(song("local")))
                runCurrent()
                assertEquals(
                    "local",
                    controller.state.value.queue
                        .last()
                        .id,
                )
            } finally {
                manager.close()
                client.close()
                backgroundScope.cancel()
                player.release()
                database.close()
            }
        }

    @Test
    fun listenTogetherObservesCommittedPlaybackDuringPendingAndFailedResolution() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.autoLoadMore = false
            val incoming = song("incoming").copy(duration = 240L)
            var resource = CompletableDeferred<PlaybackResource>()
            val requestedIds = mutableListOf<String>()
            val heldResolver =
                object : StreamResolver {
                    override suspend fun resolve(
                        videoId: String,
                        hints: ContentHints,
                        audioQuality: AudioQuality,
                        sourceSong: SongItem?,
                    ): PlaybackResource {
                        requestedIds += videoId
                        return if (videoId ==
                            incoming.id
                        ) {
                            resource.await()
                        } else {
                            fileResolver.resolve(videoId, hints, audioQuality, sourceSong)
                        }
                    }

                    override suspend fun refresh(
                        videoId: String,
                        hints: ContentHints,
                        audioQuality: AudioQuality,
                        sourceSong: SongItem?,
                    ) = resolve(videoId, hints, audioQuality, sourceSong)
                }
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            try {
                val controller =
                    IosPlaybackController(
                        player,
                        heldResolver,
                        database,
                        backgroundScope,
                        persistenceDispatcher = StandardTestDispatcher(testScheduler),
                    )
                val playback = IosListenTogetherPlayback(controller, player, backgroundScope)
                val old = song("old")
                val previousQueue = listOf(song("previous"), old, song("next"))
                controller.playQueue(previousQueue, 1, "Local")
                runCurrent()
                val previousInstance = playback.playbackInstanceId()
                assertEquals(old, playback.currentSong.value)
                assertTrue(playback.playing.value)

                val requestedQueue = listOf(incoming, song("incoming-next"))
                controller.playQueue(requestedQueue, 0, "New")
                controller.seekTo(12_000L)
                runCurrent()
                assertTrue(controller.state.value.isLoading)
                assertEquals(incoming, controller.state.value.currentSong)
                assertEquals(12_000L, controller.state.value.positionMs)
                assertEquals(old, playback.currentSong.value)
                assertEquals(previousQueue, playback.queue.value)
                assertEquals(1, playback.currentQueueIndex())
                assertEquals(player.currentPosition.value, playback.currentPositionMs())
                assertEquals(player.currentPosition.value, playback.positionMs.value)
                assertEquals(old.duration!! * 1000L, playback.durationMs.value)
                assertFalse(playback.playing.value)
                assertEquals(PlaybackReadiness.BUFFERING, playback.readiness.value)

                resource.completeExceptionally(IllegalStateException("Resolution failed"))
                runCurrent()
                assertEquals(PlaybackError.STREAM_UNAVAILABLE, controller.state.value.error)
                assertEquals(incoming, controller.state.value.currentSong)
                assertEquals(old, playback.currentSong.value)
                assertEquals(previousQueue, playback.queue.value)
                assertEquals(previousInstance, playback.playbackInstanceId())
                assertEquals(PlaybackReadiness.FAILED, playback.readiness.value)

                resource = CompletableDeferred()
                controller.togglePlayPause()
                runCurrent()
                assertEquals(old, playback.currentSong.value)
                assertEquals(listOf(old.id, incoming.id, incoming.id), requestedIds)
                assertTrue(controller.state.value.isLoading)
                resource.complete(fileResolver.resolve(incoming.id, ContentHints(), AudioQuality.MP4, incoming))
                runCurrent()
                assertEquals(incoming, playback.currentSong.value)
                assertEquals(requestedQueue, playback.queue.value)
                assertEquals(0, playback.currentQueueIndex())
                assertNotEquals(previousInstance, playback.playbackInstanceId())
            } finally {
                backgroundScope.cancel()
                player.release()
                database.close()
            }
        }

    @Test
    fun cancelledListenTogetherLoadRestoresLocalQueueState() = exerciseRejectedListenTogetherLoad(cancel = true)

    @Test
    fun failedRoomLoadAndRetryRejectHostPlayAndRestoreLocalQueueAfterLeaving() = exerciseRejectedListenTogetherLoad(cancel = false)

    private fun exerciseRejectedListenTogetherLoad(cancel: Boolean) =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            responseDecodeDispatcher = dispatcher
            Settings.shared.persistentQueue = true
            Settings.shared.autoLoadMore = true
            val incoming = song("incoming")
            var resource = CompletableDeferred<PlaybackResource>()
            val heldResolver =
                object : StreamResolver {
                    override suspend fun resolve(
                        videoId: String,
                        hints: ContentHints,
                        audioQuality: AudioQuality,
                        sourceSong: SongItem?,
                    ) = if (videoId == incoming.id) resource.await() else fileResolver.resolve(videoId, hints, audioQuality, sourceSong)

                    override suspend fun refresh(
                        videoId: String,
                        hints: ContentHints,
                        audioQuality: AudioQuality,
                        sourceSong: SongItem?,
                    ) = resolve(videoId, hints, audioQuality, sourceSong)
                }
            val engine =
                MockEngine.create {
                    this.dispatcher = dispatcher
                    addHandler {
                        respond(
                            radioResponse(listOf("suggestion"), continuation = null),
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            val client =
                HttpClient(engine) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val repository = YouTubeRepository(InnerTube(client))
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            val controller =
                IosPlaybackController(
                    player,
                    heldResolver,
                    database,
                    backgroundScope,
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                ) {
                    repository
                }
            val playback = IosListenTogetherPlayback(controller, player, backgroundScope)
            val manager =
                ListenTogetherManager(
                    callerScope = backgroundScope,
                    httpClient = client,
                    playback = playback,
                    resolver = ListenTogetherTrackResolver { it.toSongItem() },
                    monotonicClockMs = { testScheduler.currentTime },
                    wallClockMs = { testScheduler.currentTime },
                    timestampFormatter = Long::toString,
                    sessionPersistenceEnabled = false,
                )
            controller.bindListenTogether(manager)
            val hostPlay =
                PortableMessageCodec().encode(
                    MessageTypes.SYNC_PLAYBACK,
                    PlaybackActionPayload(action = PlaybackActions.PLAY, trackId = incoming.id),
                )
            try {
                val old = song("old")
                controller.playQueue(listOf(old, song("other"), song("third")), 0, "Local station")
                runCurrent()
                controller.toggleShuffle()
                runCurrent()
                val previousQueue = player.queueSnapshot()
                val previousState = controller.state.value
                val previousPersisted =
                    persistedState(Settings.shared)
                assertTrue(previousState.isShuffled)
                assertTrue(previousState.queueChips.isNotEmpty())
                assertTrue(previousState.suggestedTracks.isNotEmpty())
                assertEquals(old.id, previousPersisted.radioSeedSongId)
                manager.handleReconnected(
                    ReconnectedPayload(
                        "ROOM1234",
                        "guest",
                        RoomState("ROOM1234", "host", emptyList(), isPlaying = false, position = 0L, lastUpdate = 0L),
                        isHost = false,
                    ),
                )
                runCurrent()

                val loading = launch { controller.playQueueFromListenTogether(listOf(incoming), 0, "Room") }
                runCurrent()
                assertTrue(controller.state.value.isLoading)
                assertFalse(controller.state.value.isShuffled)
                if (cancel) {
                    loading.cancelAndJoin()
                    resource.complete(fileResolver.resolve(incoming.id, ContentHints(), AudioQuality.MP4, incoming))
                } else {
                    resource.completeExceptionally(IllegalStateException("Resolution failed"))
                    runCurrent()
                    loading.join()
                    assertEquals(PlaybackError.STREAM_UNAVAILABLE, controller.state.value.error)
                    manager.handleBinary(hostPlay)
                    runCurrent()
                    assertFalse(player.isPlaying.value)
                    assertEquals(old, player.currentSong.value)
                    assertEquals(PlaybackReadiness.FAILED, playback.readiness.value)
                    resource = CompletableDeferred()
                    val retry = launch { controller.playQueueFromListenTogether(listOf(incoming), 0, "Room") }
                    runCurrent()
                    resource.completeExceptionally(IllegalStateException("Retry failed"))
                    runCurrent()
                    retry.join()
                    manager.handleBinary(hostPlay)
                    runCurrent()
                    assertFalse(player.isPlaying.value)
                    assertEquals(PlaybackReadiness.FAILED, playback.readiness.value)
                }
                runCurrent()
                assertEquals(old, player.currentSong.value)
                assertFalse(player.isPlaying.value)
                assertFalse(controller.state.value.isLoading)
                assertEquals(previousQueue, player.queueSnapshot())
                assertEquals(previousState.queue, controller.state.value.queue)
                assertTrue(controller.state.value.isShuffled)
                assertEquals(previousState.queueTitle, controller.state.value.queueTitle)
                assertEquals(previousState.queueChips, controller.state.value.queueChips)
                assertEquals(previousState.suggestedTracks, controller.state.value.suggestedTracks)
                val restored = persistedState(Settings.shared)
                assertEquals(previousPersisted.copy(positionMs = restored.positionMs, revision = restored.revision), restored)
                manager.leaveRoomAndWait()
                runCurrent()
                assertNull(controller.state.value.error)
                controller.togglePlayPause()
                runCurrent()
                assertTrue(player.isPlaying.value)
                assertEquals(old, player.currentSong.value)

                controller.addToQueue(listOf(song("local")))
                runCurrent()
                assertEquals(previousState.queue + song("local"), controller.state.value.queue)
                assertTrue(controller.state.value.isShuffled)

                resource =
                    CompletableDeferred<PlaybackResource>().apply {
                        complete(fileResolver.resolve(incoming.id, ContentHints(), AudioQuality.MP4, incoming))
                    }
                val replacement = launch { controller.playQueueFromListenTogether(listOf(incoming), 0, "Room") }
                runCurrent()
                replacement.join()
                runCurrent()
                assertEquals(listOf(incoming), controller.state.value.queue)
                assertFalse(controller.state.value.isShuffled)
                assertEquals("Room", controller.state.value.queueTitle)
                assertTrue(
                    controller.state.value.queueChips
                        .isEmpty(),
                )
                assertTrue(
                    controller.state.value.suggestedTracks
                        .isEmpty(),
                )
                val remote = persistedState(Settings.shared)
                assertNull(remote.radioSeedSongId)
                assertNull(remote.radioPlaylistId)
                assertNull(remote.radioParams)
                assertTrue(remote.radioQueueFinite)
                controller.setPlayingFromListenTogether(true)
                assertTrue(player.isPlaying.value)
                assertEquals(incoming, player.currentSong.value)
            } finally {
                manager.close()
                backgroundScope.cancel()
                player.release()
                client.close()
                database.close()
            }
        }

    @Test
    fun failedRoomLoadAndCancelledRetryKeepHostQueueAndPositionChangesAwayFromLocalPlayback() =
        exerciseIsolatedRoomActions(localLoaded = true)

    @Test
    fun restoredSameSongMetadataDoesNotLetRoomActionsModifyLocalPlaybackBeforeLoading() = exerciseIsolatedRoomActions(localLoaded = false)

    private fun exerciseIsolatedRoomActions(localLoaded: Boolean) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.autoLoadMore = false
            Settings.shared.persistentQueue = true
            val incoming = song(if (localLoaded) "incoming" else "old")
            var resource = CompletableDeferred<PlaybackResource>()
            val heldResolver =
                object : StreamResolver {
                    override suspend fun resolve(
                        videoId: String,
                        hints: ContentHints,
                        audioQuality: AudioQuality,
                        sourceSong: SongItem?,
                    ) = if (videoId == incoming.id) resource.await() else fileResolver.resolve(videoId, hints, audioQuality, sourceSong)

                    override suspend fun refresh(
                        videoId: String,
                        hints: ContentHints,
                        audioQuality: AudioQuality,
                        sourceSong: SongItem?,
                    ) = resolve(videoId, hints, audioQuality, sourceSong)
                }
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            val client = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
            val controller =
                IosPlaybackController(
                    player,
                    heldResolver,
                    database,
                    backgroundScope,
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                )
            val playback = IosListenTogetherPlayback(controller, player, backgroundScope)
            val manager =
                ListenTogetherManager(
                    callerScope = backgroundScope,
                    httpClient = client,
                    playback = playback,
                    resolver = ListenTogetherTrackResolver { it.toSongItem() },
                    monotonicClockMs = { testScheduler.currentTime },
                    wallClockMs = { testScheduler.currentTime },
                    timestampFormatter = Long::toString,
                    sessionPersistenceEnabled = false,
                )
            val codec = PortableMessageCodec()
            val hostActions =
                listOf(
                    PlaybackActionPayload(PlaybackActions.SYNC_QUEUE, queue = listOf(song("host-next").toTrackInfo())),
                    PlaybackActionPayload(PlaybackActions.QUEUE_CLEAR),
                    PlaybackActionPayload(PlaybackActions.SEEK, position = 99_000L),
                    PlaybackActionPayload(PlaybackActions.PLAY, position = 99_000L),
                    PlaybackActionPayload(PlaybackActions.PAUSE, position = 99_000L),
                    PlaybackActionPayload(PlaybackActions.SKIP_NEXT),
                    PlaybackActionPayload(PlaybackActions.SKIP_PREV),
                )
            try {
                controller.bindListenTogether(manager)
                val old = song("old")
                val localSongs = listOf(song("previous"), old, song("next"))
                if (localLoaded) {
                    controller.playQueue(localSongs, 1, "Local")
                } else {
                    player.setQueue(localSongs, 1, startPlaying = false)
                }
                runCurrent()
                if (localLoaded) assertEquals(old, playback.currentSong.value) else assertNull(playback.currentSong.value)
                controller.seekTo(12_000L)
                runCurrent()
                val localQueue = player.queueSnapshot()
                val localPersisted =
                    persistedState(Settings.shared)
                manager.handleReconnected(
                    ReconnectedPayload(
                        "ROOM1234",
                        "guest",
                        RoomState(
                            "ROOM1234",
                            "host",
                            emptyList(),
                            currentTrack = incoming.toTrackInfo(),
                            isPlaying = true,
                            position = 33_000L,
                            lastUpdate = 0L,
                        ),
                        isHost = false,
                    ),
                )
                runCurrent()
                assertTrue(controller.state.value.isLoading)
                resource.completeExceptionally(IllegalStateException("Resolution failed"))
                runCurrent()
                advanceTimeBy(50L.milliseconds)
                runCurrent()
                assertEquals(PlaybackReadiness.FAILED, playback.readiness.value)
                for (action in hostActions) manager.handleBinary(codec.encode(MessageTypes.SYNC_PLAYBACK, action))
                playback.addToQueueFromRemote(song("host-added"))
                runCurrent()
                assertEquals(localQueue, player.queueSnapshot())
                assertEquals(12_000L, player.currentPosition.value)
                assertEquals(old, player.currentSong.value)
                assertFalse(player.isPlaying.value)

                resource = CompletableDeferred()
                val retry = launch { playback.playQueueFromRemote(listOf(incoming), title = "Room") }
                runCurrent()
                playback.replaceUpcomingQueueFromRemote(listOf(song("pending-next")))
                playback.addToQueueFromRemote(song("pending-added"))
                playback.seekToFromRemote(45_000L)
                runCurrent()
                assertEquals(listOf(incoming, song("pending-next"), song("pending-added")), controller.state.value.queue)
                assertEquals(45_000L, controller.state.value.positionMs)
                assertEquals(localQueue, player.queueSnapshot())
                assertEquals(12_000L, player.currentPosition.value)
                retry.cancelAndJoin()
                runCurrent()
                assertNull(controller.state.value.error)
                for (action in hostActions) manager.handleBinary(codec.encode(MessageTypes.SYNC_PLAYBACK, action))
                playback.addToQueueFromRemote(song("host-added-after-cancellation"))
                runCurrent()
                assertEquals(localQueue, player.queueSnapshot())
                assertEquals(12_000L, player.currentPosition.value)
                assertEquals(old, player.currentSong.value)
                assertFalse(player.isPlaying.value)

                manager.leaveRoomAndWait()
                runCurrent()
                assertEquals(localQueue, player.queueSnapshot())
                val restored = persistedState(Settings.shared)
                assertEquals(localPersisted.copy(revision = restored.revision), restored)
                if (!localLoaded) resource.complete(fileResolver.resolve(old.id, ContentHints(), AudioQuality.MP4, old))
                controller.seekTo(21_000L)
                controller.addToQueue(listOf(song("local-added")))
                controller.togglePlayPause()
                runCurrent()
                assertEquals(21_000L, player.currentPosition.value)
                assertEquals(localQueue.displayedItems + song("local-added"), controller.state.value.queue)
                assertTrue(player.isPlaying.value)
            } finally {
                manager.close()
                client.close()
                backgroundScope.cancel()
                player.release()
                database.close()
            }
        }

    @Test
    fun staleListenTogetherFailureAndCancellationDoNotDiscardNewerPlayback() {
        for (remoteReplacement in listOf(false, true)) {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                Settings.shared.autoLoadMore = false
                Settings.shared.persistentQueue = true
                Settings.shared.persistentPlaybackState = ""
                val incoming = song("incoming")
                val newer = song("newer")
                val incomingResource = CompletableDeferred<PlaybackResource>()
                val newerResource = CompletableDeferred<PlaybackResource>()
                val heldResolver =
                    object : StreamResolver {
                        override suspend fun resolve(
                            videoId: String,
                            hints: ContentHints,
                            audioQuality: AudioQuality,
                            sourceSong: SongItem?,
                        ) = when (videoId) {
                            incoming.id -> withContext(NonCancellable) { incomingResource.await() }
                            newer.id -> newerResource.await()
                            else -> fileResolver.resolve(videoId, hints, audioQuality, sourceSong)
                        }

                        override suspend fun refresh(
                            videoId: String,
                            hints: ContentHints,
                            audioQuality: AudioQuality,
                            sourceSong: SongItem?,
                        ) = resolve(videoId, hints, audioQuality, sourceSong)
                    }
                val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
                val player = MusicPlayer()
                try {
                    val controller =
                        IosPlaybackController(
                            player,
                            heldResolver,
                            database,
                            backgroundScope,
                            persistenceDispatcher = StandardTestDispatcher(testScheduler),
                        )
                    controller.playQueue(listOf(song("old")), 0, "Local")
                    runCurrent()
                    val stale = launch { controller.playQueueFromListenTogether(listOf(incoming), 0, "Stale room") }
                    runCurrent()
                    val newerLoad =
                        if (remoteReplacement) {
                            launch { controller.playQueueFromListenTogether(listOf(newer), 0, "New room") }
                        } else {
                            controller.playQueue(listOf(newer), 0, "New local")
                            null
                        }
                    runCurrent()
                    stale.cancelAndJoin()
                    incomingResource.completeExceptionally(IllegalStateException("Late resolution failure"))
                    runCurrent()
                    assertEquals(newer, controller.state.value.currentSong)
                    assertEquals(listOf(newer), controller.state.value.queue)
                    assertTrue(controller.state.value.isLoading)
                    newerResource.complete(fileResolver.resolve(newer.id, ContentHints(), AudioQuality.MP4, newer))
                    runCurrent()
                    newerLoad?.join()
                    runCurrent()
                    assertEquals(newer, player.currentSong.value)
                    assertEquals(listOf(newer), controller.state.value.queue)
                    assertEquals(if (remoteReplacement) "New room" else "New local", controller.state.value.queueTitle)
                    assertEquals(!remoteReplacement, player.isPlaying.value)
                    assertEquals(!remoteReplacement, controller.state.value.isPlaying)
                    assertEquals(!remoteReplacement && player.isWaitingToPlay.value, controller.state.value.isLoading)
                    assertNull(controller.state.value.error)
                    val persisted =
                        persistedState(Settings.shared)
                    assertEquals(listOf(newer), persisted.queue.displayedItems)
                    assertEquals(controller.state.value.queueTitle, persisted.queueTitle)
                } finally {
                    backgroundScope.cancel()
                    player.release()
                    database.close()
                }
            }
        }
    }

    @Test
    fun cancellingTheCallerAfterRemoteCommitDoesNotPauseCommittedPlayback() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.autoLoadMore = false
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            try {
                val controller =
                    IosPlaybackController(
                        player,
                        fileResolver,
                        database,
                        backgroundScope,
                        persistenceDispatcher = StandardTestDispatcher(testScheduler),
                    )
                val incoming = song("incoming")
                val loading =
                    launch(start = CoroutineStart.LAZY) {
                        controller.playQueueFromListenTogether(listOf(incoming), 0, "Room")
                    }
                val cancellation =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        player.playbackInstance.first { it?.second == incoming }
                        controller.setPlayingFromListenTogether(true)
                        loading.cancel()
                    }
                loading.start()
                runCurrent()
                cancellation.join()
                loading.join()
                runCurrent()
                assertTrue(loading.isCancelled)
                assertEquals(incoming, player.currentSong.value)
                assertEquals("Room", controller.state.value.queueTitle)
                assertTrue(controller.state.value.isPlaying)
                assertEquals(player.isWaitingToPlay.value, controller.state.value.isLoading)
                assertTrue(player.isPlaying.value)
            } finally {
                backgroundScope.cancel()
                player.release()
                database.close()
            }
        }

    @Test
    fun durationMetadataKeepsTheNativePlaybackInstance() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val player = MusicPlayer()
            val song = song("unknown-duration").copy(duration = null)
            val url = "file://${NSTemporaryDirectory()}${NSUUID().UUIDString}.wav"
            try {
                player.setQueue(listOf(song), startPlaying = false)
                player.play(song, url)
                val instanceId = assertNotNull(player.playbackInstance.value).first
                player.updatePlaybackDuration(182_400L)
                assertEquals(183_000L, player.duration.value)
                assertEquals(0L, player.bufferedPosition.value, "Duration alone must not claim loaded audio")
                val updated = assertNotNull(player.playbackInstance.value)
                assertEquals(instanceId, updated.first)
                assertEquals(183L, updated.second.duration)
                assertEquals(updated.second, player.currentSong.value)
                assertEquals(updated.second, player.songQueue.value.single())
                player.play(updated.second, url)
                assertNotEquals(instanceId, assertNotNull(player.playbackInstance.value).first)
                player.notifyPlaybackEnded()
                assertNull(player.playbackInstance.value)
                player.play(updated.second, url)
                player.stop()
                assertNull(player.playbackInstance.value)
            } finally {
                player.release()
            }
        }

    @Test
    fun playbackEndAutomaticallyStartsTheNextQueuedSong() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.autoLoadMore = false
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            try {
                val controller =
                    IosPlaybackController(
                        player,
                        fileResolver,
                        database,
                        backgroundScope,
                        persistenceDispatcher = StandardTestDispatcher(testScheduler),
                    )
                val songs = listOf(song("first"), song("second"))
                controller.play(songs.first(), songs)
                runCurrent()

                player.notifyPlaybackEnded()
                runCurrent()

                assertEquals("second", player.currentSong.value?.id)
                assertTrue(player.isPlaying.value)
            } finally {
                backgroundScope.cancel()
                player.release()
                database.close()
            }
        }

    @Test
    fun metadataChangesAndPausesDoNotSplitPlaybackHistory() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.historyDuration = 30f
            Settings.shared.pauseListenHistory = false
            val timeSource = TestTimeSource()
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            try {
                val controller =
                    IosPlaybackController(
                        player,
                        fileResolver,
                        database,
                        backgroundScope,
                        timeSource = timeSource,
                        persistenceDispatcher = StandardTestDispatcher(testScheduler),
                    )
                runCurrent()
                val song = song("history").copy(duration = null)
                val updated = song.copy(duration = 180L)
                controller.updateListeningPlayback(1L to song, playing = true)
                timeSource += 20.seconds
                controller.updateListeningPlayback(1L to updated, playing = true)
                timeSource += 40.seconds
                controller.updateListeningPlayback(1L to updated.copy(duration = 181L), playing = false)
                runCurrent()
                assertTrue(
                    database
                        .dao()
                        .events()
                        .first()
                        .isEmpty(),
                )
                timeSource += 100.seconds
                controller.updateListeningPlayback(1L to updated, playing = true)
                timeSource += 10.seconds
                controller.updateListeningPlayback(2L to updated, playing = true)
                timeSource += 35.seconds
                controller.updateListeningPlayback(null, playing = false)
                val events = database.dao().events().first { it.size >= 2 }
                assertEquals(listOf(35_000L, 70_000L), events.map { it.event.playTime }.sorted())
            } finally {
                backgroundScope.cancel()
                player.release()
                database.close()
            }
        }

    @Test
    fun privacyPausesExcludeTimeFromBothCrossfadeSessions() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.historyDuration = 0f
            val timeSource = TestTimeSource()
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            try {
                val controller =
                    IosPlaybackController(
                        player,
                        fileResolver,
                        database,
                        backgroundScope,
                        timeSource = timeSource,
                        persistenceDispatcher = StandardTestDispatcher(testScheduler),
                    )
                runCurrent()
                val outgoing = song("outgoing")
                val incoming = song("incoming")
                controller.updateListeningPlayback(1L to outgoing, true)
                timeSource += 10.seconds
                controller.handlePlaybackOverlapEvent(IosPlaybackOverlapEvent.Started(1L to outgoing, 2L to incoming))
                timeSource += 2.seconds
                Settings.shared.pauseListenHistory = true
                controller.updateListeningHistoryPrivacy(true)
                timeSource += 60.seconds
                Settings.shared.pauseListenHistory = false
                controller.updateListeningHistoryPrivacy(false)
                timeSource += 3.seconds
                controller.handlePlaybackOverlapEvent(IosPlaybackOverlapEvent.Finished(1L to outgoing, 2L to incoming))
                controller.updateListeningPlayback(null, false)
                val events = database.dao().events().first { it.size == 2 }
                assertEquals(mapOf("outgoing" to 15_000L, "incoming" to 5_000L), events.associate { it.event.songId to it.event.playTime })
            } finally {
                backgroundScope.cancel()
                player.release()
                database.close()
            }
        }

    @Test
    fun crossfadeHandoffCountsIncomingFromItsFirstAudibleSample() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.historyDuration = 0f
            val timeSource = TestTimeSource()
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            try {
                val controller =
                    IosPlaybackController(
                        player,
                        fileResolver,
                        database,
                        backgroundScope,
                        timeSource = timeSource,
                        persistenceDispatcher = StandardTestDispatcher(testScheduler),
                    )
                runCurrent()
                val outgoing = song("outgoing")
                val incoming = song("incoming")
                controller.updateListeningPlayback(1L to outgoing, playing = true)
                timeSource += 10.seconds
                controller.handlePlaybackOverlapEvent(IosPlaybackOverlapEvent.Started(1L to outgoing, 2L to incoming))
                timeSource += 5.seconds
                controller.handlePlaybackOverlapEvent(IosPlaybackOverlapEvent.Finished(1L to outgoing, 2L to incoming))
                timeSource += 20.seconds
                controller.updateListeningPlayback(null, playing = false)

                val events = database.dao().events().first { it.size == 2 }
                assertEquals(mapOf("outgoing" to 15_000L, "incoming" to 25_000L), events.associate { it.event.songId to it.event.playTime })
            } finally {
                backgroundScope.cancel()
                player.release()
                database.close()
            }
        }

    @Test
    fun cancelledCrossfadeKeepsOutgoingTimeAndExcludesPausedIntervals() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.historyDuration = 20f
            val timeSource = TestTimeSource()
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            try {
                val controller =
                    IosPlaybackController(
                        player,
                        fileResolver,
                        database,
                        backgroundScope,
                        timeSource = timeSource,
                        persistenceDispatcher = StandardTestDispatcher(testScheduler),
                    )
                runCurrent()
                val outgoing = song("outgoing")
                val incoming = song("incoming")
                controller.updateListeningPlayback(1L to outgoing, playing = true)
                timeSource += 10.seconds
                controller.handlePlaybackOverlapEvent(IosPlaybackOverlapEvent.Started(1L to outgoing, 2L to incoming))
                timeSource += 5.seconds
                controller.handlePlaybackOverlapEvent(IosPlaybackOverlapEvent.Cancelled(1L to outgoing, 2L to incoming, 3L to outgoing))
                timeSource += 10.seconds
                controller.updateListeningPlayback(3L to outgoing, playing = false)
                timeSource += 60.seconds
                controller.updateListeningPlayback(3L to outgoing, playing = true)
                timeSource += 5.seconds
                controller.updateListeningPlayback(null, playing = false)

                val events = database.dao().events().first { it.isNotEmpty() }
                assertEquals(listOf("outgoing" to 20_000L), events.map { it.event.songId to it.event.playTime })
            } finally {
                backgroundScope.cancel()
                player.release()
                database.close()
            }
        }

    @Test
    fun repeatedSongOverlapKeepsIncomingInstanceAcrossHandoff() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.historyDuration = 0f
            val timeSource = TestTimeSource()
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            try {
                val controller =
                    IosPlaybackController(
                        player,
                        fileResolver,
                        database,
                        backgroundScope,
                        timeSource = timeSource,
                        persistenceDispatcher = StandardTestDispatcher(testScheduler),
                    )
                runCurrent()
                val repeated = song("repeated")
                controller.updateListeningPlayback(1L to repeated, playing = true)
                timeSource += 10.seconds
                controller.handlePlaybackOverlapEvent(IosPlaybackOverlapEvent.Started(1L to repeated, 2L to repeated))
                timeSource += 5.seconds
                controller.handlePlaybackOverlapEvent(IosPlaybackOverlapEvent.Finished(1L to repeated, 2L to repeated))
                timeSource += 10.seconds
                controller.updateListeningPlayback(2L to repeated, playing = true)
                timeSource += 5.seconds
                controller.updateListeningPlayback(null, playing = false)

                val events = database.dao().events().first { it.size == 2 }
                assertEquals(listOf(15_000L, 20_000L), events.map { it.event.playTime }.sorted())
            } finally {
                backgroundScope.cancel()
                player.release()
                database.close()
            }
        }

    @Test
    fun savedAudioQualityResolvesAnAppleCompatibleStreamAndUpdatesPlayback() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.audioQuality = "HIGH"
            val requestedQualities = mutableListOf<AudioQuality>()
            var rejectedQuality: AudioQuality? = null
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            val resolver =
                object : StreamResolver {
                    override suspend fun resolve(
                        videoId: String,
                        hints: ContentHints,
                        audioQuality: AudioQuality,
                        sourceSong: SongItem?,
                    ): PlaybackResource {
                        assertFalse(hints.wantVideo)
                        assertTrue(hints.allowSabr)
                        assertTrue(hints.allowBoundedRange)
                        requestedQualities += audioQuality
                        check(audioQuality != rejectedQuality) { "Test stream unavailable" }
                        return PlaybackResource(
                            videoId = videoId,
                            url = "file://${NSTemporaryDirectory()}${NSUUID().UUIDString}.m4a",
                            headers = emptyMap(),
                            loudnessDb = null,
                            expiresAt = null,
                            requireBoundedRange = false,
                            mimeType = if (audioQuality == AudioQuality.LOW) "audio/webm" else "audio/mp4",
                        )
                    }

                    override suspend fun refresh(
                        videoId: String,
                        hints: ContentHints,
                        audioQuality: AudioQuality,
                        sourceSong: SongItem?,
                    ): PlaybackResource = resolve(videoId, hints, audioQuality, sourceSong)
                }
            try {
                val controller =
                    IosPlaybackController(
                        player,
                        resolver,
                        database,
                        backgroundScope,
                        persistenceDispatcher = StandardTestDispatcher(testScheduler),
                    )
                val song = song("quality").copy(musicVideoType = "MUSIC_VIDEO_TYPE_UGC")
                controller.play(song)
                runCurrent()
                assertEquals(listOf(AudioQuality.HIGH), requestedQualities)
                val playbackInstanceId = assertNotNull(player.playbackInstance.value).first
                val initialResource = assertNotNull(player.currentPlaybackResource.value)
                player.seekTo(12_000L)
                player.seekTo(42_000L)
                runCurrent()

                Snapshot.withMutableSnapshot { Settings.shared.audioQuality = "LOW" }
                runCurrent()
                assertEquals(listOf(AudioQuality.HIGH, AudioQuality.LOW, AudioQuality.MP4), requestedQualities)
                assertEquals("audio/mp4", player.currentPlaybackResource.value?.mimeType)
                assertEquals(42_000L, player.currentPosition.value)
                assertTrue(player.isPlaying.value)
                assertEquals(playbackInstanceId, player.playbackInstance.value?.first)
                player.handlePlaybackFailed(NSError(domain = "test-reload", code = -1, userInfo = emptyMap<Any?, Any?>()))
                assertSame(initialResource, player.currentPlaybackResource.value)
                assertTrue(player.isPlaying.value)
                assertNull(player.loadError.value)

                player.pause()
                runCurrent()
                Snapshot.withMutableSnapshot { Settings.shared.audioQuality = "MP4" }
                runCurrent()
                assertEquals(42_000L, player.currentPosition.value)
                assertFalse(player.isPlaying.value)
                assertEquals(playbackInstanceId, player.playbackInstance.value?.first)
                val workingResource = assertNotNull(player.currentPlaybackResource.value)

                rejectedQuality = AudioQuality.HIGH
                Snapshot.withMutableSnapshot { Settings.shared.audioQuality = "HIGH" }
                runCurrent()
                assertSame(workingResource, player.currentPlaybackResource.value)
                assertFalse(player.isPlaying.value)
                assertEquals(playbackInstanceId, player.playbackInstance.value?.first)
                assertNull(controller.state.value.error)
            } finally {
                backgroundScope.cancel()
                player.release()
                database.close()
            }
        }

    @Test
    fun remoteSkipCommandsFollowQueueRepeatAndPositionCapabilities() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.autoLoadMore = false
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            val commands = MPRemoteCommandCenter.sharedCommandCenter()
            try {
                val controller =
                    IosPlaybackController(
                        player,
                        fileResolver,
                        database,
                        backgroundScope,
                        persistenceDispatcher = StandardTestDispatcher(testScheduler),
                    )
                runCurrent()
                assertFalse(commands.skipForwardCommand.enabled)
                assertFalse(commands.skipBackwardCommand.enabled)
                assertFalse(commands.nextTrackCommand.enabled)
                assertFalse(commands.previousTrackCommand.enabled)
                assertEquals(MPRemoteCommandHandlerStatusCommandFailed, player.handleRemoteSkip(next = true))
                val songs = listOf(song("first"), song("last"))
                player.setQueue(songs, startIndex = 1, startPlaying = false)
                runCurrent()
                assertFalse(commands.nextTrackCommand.enabled)
                assertTrue(commands.previousTrackCommand.enabled)
                assertEquals(MPRemoteCommandHandlerStatusSuccess, player.handleRemoteSkip(next = false))
                runCurrent()
                assertEquals(0, controller.state.value.currentQueueIndex)
                assertTrue(commands.nextTrackCommand.enabled)
                assertFalse(commands.previousTrackCommand.enabled)
                assertEquals(MPRemoteCommandHandlerStatusCommandFailed, player.handleRemoteSkip(next = false))
                controller.toggleRepeat()
                runCurrent()
                assertTrue(commands.previousTrackCommand.enabled)
                assertEquals(MPRemoteCommandHandlerStatusSuccess, player.handleRemoteSkip(next = false))
                runCurrent()
                assertEquals(1, controller.state.value.currentQueueIndex)
                assertTrue(commands.nextTrackCommand.enabled)
                controller.toggleRepeat()
                runCurrent()
                assertFalse(commands.nextTrackCommand.enabled)
                assertEquals(MPRemoteCommandHandlerStatusCommandFailed, player.handleRemoteSkip(next = true))
                controller.playQueueIndex(0)
                runCurrent()
                assertFalse(commands.previousTrackCommand.enabled)
                player.seekTo(4_000L)
                runCurrent()
                assertTrue(commands.previousTrackCommand.enabled)
                assertEquals(MPRemoteCommandHandlerStatusSuccess, player.handleRemoteSkip(next = false))
                runCurrent()
                assertEquals(0L, player.currentPosition.value)
                assertEquals(0, controller.state.value.currentQueueIndex)
                assertFalse(commands.previousTrackCommand.enabled)
            } finally {
                backgroundScope.cancel()
                player.release()
                database.close()
            }
        }

    @Test
    fun pendingSeeksAndPreviousSurviveResolutionAndRetry() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.autoLoadMore = false
            Settings.shared.persistentQueue = true
            val songs = listOf(song("first"), song("resumed"))
            Settings.shared.persistentPlaybackState =
                Json.encodeToString(
                    QueuePlaybackState(
                        MusicQueueSnapshot(songs, songs, 1, isShuffled = false),
                        positionMs = 12_000L,
                    ),
                )
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            var resolution = CompletableDeferred<PlaybackResource>()
            val resolver =
                object : StreamResolver {
                    override suspend fun resolve(
                        videoId: String,
                        hints: ContentHints,
                        audioQuality: AudioQuality,
                        sourceSong: SongItem?,
                    ): PlaybackResource = resolution.await()

                    override suspend fun refresh(
                        videoId: String,
                        hints: ContentHints,
                        audioQuality: AudioQuality,
                        sourceSong: SongItem?,
                    ): PlaybackResource = resolve(videoId, hints, audioQuality, sourceSong)
                }

            fun persistedPosition() = persistedState(Settings.shared).positionMs
            try {
                val controller =
                    IosPlaybackController(
                        player,
                        resolver,
                        database,
                        backgroundScope,
                        persistenceDispatcher = StandardTestDispatcher(testScheduler),
                    )
                val resource = fileResolver.resolve(songs[1].id, ContentHints(), AudioQuality.MP4, songs[1])
                runCurrent()
                controller.skipToPrevious()
                assertEquals(0L, persistedPosition())
                controller.seekTo(12_000L)
                assertEquals(12_000L, persistedPosition())
                controller.togglePlayPause()
                runCurrent()
                assertTrue(controller.state.value.isLoading)
                controller.seekTo(18_000L)
                runCurrent()
                assertEquals(18_000L, controller.state.value.positionMs)
                assertEquals(18_000L, persistedPosition())
                assertEquals(12_000L, player.currentPosition.value)
                player.requestSeek(15_000L, relative = true)
                runCurrent()
                assertEquals(33_000L, controller.state.value.positionMs)
                player.requestSeek(Long.MAX_VALUE, relative = true)
                runCurrent()
                assertEquals(180_000L, controller.state.value.positionMs)
                player.requestSeek(7_000L)
                runCurrent()
                assertEquals(7_000L, controller.state.value.positionMs)
                player.requestSeek(-15_000L, relative = true)
                runCurrent()
                assertEquals(0L, controller.state.value.positionMs)
                controller.seekTo(33_000L)
                resolution.complete(resource)
                runCurrent()
                assertEquals(33_000L, player.currentPosition.value)
                assertTrue(controller.state.value.isPlaying)
                assertEquals(player.isWaitingToPlay.value, controller.state.value.isLoading)

                player.handlePlaybackFailed()
                runCurrent()
                resolution = CompletableDeferred()
                controller.togglePlayPause()
                runCurrent()
                assertEquals(33_000L, controller.state.value.positionMs)
                controller.skipToPrevious()
                runCurrent()
                assertEquals(0L, controller.state.value.positionMs)
                assertEquals(1, controller.state.value.currentQueueIndex)
                resolution.completeExceptionally(IllegalStateException("Test stream unavailable"))
                runCurrent()
                assertEquals(PlaybackError.STREAM_UNAVAILABLE, controller.state.value.error)
                assertEquals(0L, persistedPosition())
                controller.seekTo(42_000L)
                runCurrent()
                assertEquals(42_000L, persistedPosition())
                resolution = CompletableDeferred()
                controller.togglePlayPause()
                runCurrent()
                assertEquals(MPRemoteCommandHandlerStatusSuccess, player.handleRemoteSkip(next = false))
                runCurrent()
                assertEquals(0L, controller.state.value.positionMs)
                resolution.complete(resource)
                runCurrent()
                assertNull(controller.state.value.error)
                assertTrue(controller.state.value.isPlaying)
                assertEquals(player.isWaitingToPlay.value, controller.state.value.isLoading)
                assertEquals(songs[1], player.currentSong.value)
                assertEquals(0L, player.currentPosition.value)
                assertEquals(0L, persistedPosition())
                controller.seekTo(6_000L)
                assertEquals(6_000L, player.currentPosition.value)
            } finally {
                backgroundScope.cancel()
                player.release()
                runCurrent()
                database.close()
            }
        }

    @Test
    fun undoPreservesDisplayedAndCanonicalOrderWithoutRemovingTheActiveSong() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val player = MusicPlayer()
            val songs = (0..4).map { song("song-$it") }
            try {
                for (shuffled in listOf(false, true)) {
                    for (indices in listOf(setOf(2), setOf(1, 3), setOf(0, 2))) {
                        player.setQueue(songs, 0, startPlaying = false)
                        if (shuffled) player.toggleShuffle()
                        val displayed = player.songQueue.value
                        player.removeQueueItem(0)
                        assertEquals(displayed, player.songQueue.value)
                        val removal = assertNotNull(player.removeQueueItems(indices))
                        val removedIndices = indices - 0
                        assertEquals(removedIndices.sorted(), removal.items.map { it.index })
                        assertEquals(displayed.filterIndexed { index, _ -> index !in removedIndices }, player.songQueue.value)
                        assertEquals(songs.first(), player.currentSong.value)
                        player.restoreQueueItems(listOf(removal))
                        assertEquals(displayed, player.songQueue.value)
                        assertEquals(0, player.currentMediaItemIndex.value)
                        val tail = song("tail")
                        player.syncQueue(displayed + tail, 0)
                        if (shuffled) player.toggleShuffle()
                        assertEquals(songs + tail, player.songQueue.value)
                    }
                }
            } finally {
                player.release()
            }
        }

    @Test
    fun replacementQueueRespectsPersistentShuffleAndPreservesTheRequestedSong() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val player = MusicPlayer()
            val replacement = listOf(song("first"), song("second"), song("third"), song("fourth"))
            try {
                for (legacy in listOf(false, true)) {
                    Settings.shared.useLegacyShuffle = legacy
                    for (persistentShuffle in listOf(false, true)) {
                        Settings.shared.persistentShuffleAcrossQueues = persistentShuffle
                        player.setQueue(listOf(song("old-first"), song("old-last")), 0, startPlaying = false)
                        player.toggleShuffle()
                        player.syncQueue(replacement, 2)
                        val snapshot = player.queueSnapshot()
                        val expectedIndex = if (legacy && persistentShuffle) 0 else 2
                        assertEquals(persistentShuffle, snapshot.isShuffled)
                        assertEquals(expectedIndex, snapshot.currentIndex)
                        assertSame(snapshot.displayedItems[snapshot.currentIndex], replacement[2])
                        assertEquals(replacement, snapshot.originalItems)
                        assertEquals(replacement.toSet(), snapshot.displayedItems.toSet())
                        Settings.shared.persistentShuffleAcrossQueues = false
                        player.syncQueue(snapshot.displayedItems, snapshot.currentIndex)
                        assertEquals(snapshot, player.queueSnapshot())
                        if (persistentShuffle) player.toggleShuffle()
                        // Turning shuffle off restores the original order in both modes.
                        assertEquals(replacement, player.songQueue.value)
                        assertEquals(2, player.currentMediaItemIndex.value)
                    }
                }
            } finally {
                player.release()
            }
        }

    @Test
    fun queueEditsRespectDuplicatePreferenceAcrossPendingResolutionRetryAndUnshuffle() {
        for (shuffled in listOf(false, true)) {
            for (preventDuplicates in listOf(false, true)) {
                exercisePendingQueueEdits(shuffled, preventDuplicates)
            }
        }
    }

    private fun exercisePendingQueueEdits(
        shuffled: Boolean,
        preventDuplicates: Boolean,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        Settings.shared.persistentQueue = true
        Settings.shared.persistentPlaybackState = ""
        Settings.shared.persistentShuffleAcrossQueues = shuffled
        Settings.shared.preventDuplicateTracksInQueue = preventDuplicates
        val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
        val player = MusicPlayer()
        var resolution = CompletableDeferred<PlaybackResource>()
        val requestedIds = mutableListOf<String>()
        val resolver =
            object : StreamResolver {
                override suspend fun resolve(
                    videoId: String,
                    hints: ContentHints,
                    audioQuality: AudioQuality,
                    sourceSong: SongItem?,
                ): PlaybackResource {
                    requestedIds += videoId
                    return resolution.await().copy(videoId = videoId)
                }

                override suspend fun refresh(
                    videoId: String,
                    hints: ContentHints,
                    audioQuality: AudioQuality,
                    sourceSong: SongItem?,
                ): PlaybackResource = resolve(videoId, hints, audioQuality, sourceSong)
            }
        val songs = listOf(song("a"), song("b"), song("c"), song("d"))
        val tail = song("tail")
        val additions = listOf(tail, songs.last(), tail, songs[1])
        val batch = listOf(song("x"), song("y"), song("x"))
        val expectedBatch = if (preventDuplicates) batch.distinctBy(SongItem::id) else batch
        val resource =
            PlaybackResource(
                videoId = songs[1].id,
                url = "file://${NSTemporaryDirectory()}${NSUUID().UUIDString}.m4a",
                headers = emptyMap(),
                loudnessDb = null,
                expiresAt = null,
                mimeType = "audio/mp4",
                requireBoundedRange = false,
            )

        fun persistedQueue() = persistedState(Settings.shared).queue
        try {
            player.setQueue(listOf(song("old-a"), song("old-b")), startPlaying = false)
            if (shuffled) player.toggleShuffle()
            val previous = player.queueSnapshot()
            val controller =
                IosPlaybackController(
                    player,
                    resolver,
                    database,
                    backgroundScope,
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            controller.play(songs[1], songs)
            runCurrent()
            assertTrue(controller.state.value.isLoading)
            assertEquals(songs[1], controller.state.value.currentSong)
            assertEquals(songs, persistedQueue().originalItems)
            val expected = MusicQueue().apply { restore(persistedQueue()) }

            controller.addToQueue(additions)
            expected.addToQueue(additions, preventDuplicates)
            controller.playNext(batch)
            expected.playNext(batch, preventDuplicates)
            val removedIndex = expected.items.value.indexOf(songs[0])
            val removal = assertNotNull(controller.removeQueueItems(setOf(removedIndex)))
            val expectedRemoval = assertNotNull(expected.removeAt(setOf(removedIndex)))
            assertEquals(expectedRemoval, removal)
            val tailIndex = expected.items.value.indexOf(tail)
            controller.moveQueueItem(tailIndex, 0)
            expected.move(tailIndex, 0)
            controller.restoreQueueItems(listOf(removal))
            expected.restore(expectedRemoval)
            runCurrent()
            assertEquals(expected.items.value, controller.state.value.queue)
            assertEquals(expected.currentIndex.value, controller.state.value.currentQueueIndex)
            assertEquals(expected.snapshot(), persistedQueue())
            assertEquals(previous, player.queueSnapshot())

            resolution.complete(resource)
            runCurrent()
            assertTrue(controller.state.value.isPlaying)
            assertEquals(player.isWaitingToPlay.value, controller.state.value.isLoading)
            assertEquals(expected.snapshot(), player.queueSnapshot())
            if (shuffled) {
                controller.toggleShuffle()
                expected.toggleShuffle(legacyShuffle = Settings.shared.useLegacyShuffle)
            }
            runCurrent()
            assertEquals(expected.items.value, player.songQueue.value)
            assertEquals(expectedBatch, player.songQueue.value.filter { it.id in setOf("x", "y") })
            assertEquals(songs[1], player.currentSong.value)

            expected.restore(player.queueSnapshot())
            controller.addToQueue(additions)
            expected.addToQueue(additions, preventDuplicates)
            controller.playNext(batch)
            expected.playNext(batch, preventDuplicates)
            runCurrent()
            assertEquals(expected.snapshot(), player.queueSnapshot())

            val beforeFailure = player.queueSnapshot()
            val failedSong = song("failed")
            resolution = CompletableDeferred()
            controller.play(failedSong, listOf(failedSong, song("other")))
            runCurrent()
            controller.togglePlayPause()
            player.resume()
            runCurrent()
            assertTrue(controller.state.value.isLoading)
            assertFalse(player.isPlaying.value)
            assertEquals(failedSong, controller.state.value.currentSong)
            controller.addToQueue(listOf(tail))
            resolution.completeExceptionally(IllegalStateException("Test stream unavailable"))
            runCurrent()
            assertEquals(PlaybackError.STREAM_UNAVAILABLE, controller.state.value.error)
            assertEquals(beforeFailure, player.queueSnapshot())
            player.resume()
            assertFalse(player.isPlaying.value)
            controller.playNext(batch)
            controller.toggleShuffle()
            runCurrent()
            assertTrue(controller.state.value.isShuffled)
            assertEquals(failedSong, controller.state.value.queue[controller.state.value.currentQueueIndex])
            val retryQueue = persistedQueue()
            assertEquals(listOf(failedSong) + expectedBatch + listOf(song("other"), tail), retryQueue.originalItems)
            resolution = CompletableDeferred()
            controller.togglePlayPause()
            runCurrent()
            assertEquals(listOf(songs[1].id, failedSong.id, failedSong.id), requestedIds)
            assertTrue(controller.state.value.isLoading)
            resolution.complete(resource)
            runCurrent()
            assertNull(controller.state.value.error)
            assertEquals(retryQueue, player.queueSnapshot())
            assertEquals(failedSong, player.currentSong.value)
            player.pause()
            player.resume()
            assertTrue(player.isPlaying.value)
        } finally {
            backgroundScope.cancel()
            player.release()
            runCurrent()
            database.close()
        }
    }

    @Test
    fun roundTripsShuffledQueueAndProcessesPlaybackEvents() {
        exercisePlayback(rememberModes = false)
        exercisePlayback(rememberModes = true)
    }

    private fun exercisePlayback(rememberModes: Boolean) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val settings = Settings.shared
            val songs = listOf(song("first"), song("second"), song("third"), song("fourth"))
            settings.useLegacyShuffle = false
            settings.persistentQueue = true
            settings.rememberShuffleAndRepeat = rememberModes
            settings.promptContinuePlayback = rememberModes
            settings.pendingContinuePlayback = true
            settings.persistentPlaybackState =
                buildJsonObject {
                    put("queue", Json.encodeToJsonElement(songs))
                    put("currentIndex", 1)
                    put("positionMs", 12_000L)
                }.toString()
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            val resolved = CompletableDeferred<PlaybackResource>()
            var resolutions = 0
            var failResolution = false
            var rejectResource = false
            var invalidations = 0
            val resolver =
                object : StreamResolver {
                    override suspend fun resolve(
                        videoId: String,
                        hints: ContentHints,
                        audioQuality: AudioQuality,
                        sourceSong: SongItem?,
                    ): PlaybackResource {
                        resolutions++
                        check(!failResolution) { "Test stream unavailable" }
                        val resource = resolved.await()
                        return if (rejectResource) resource.copy(url = "${resource.url}.missing") else resource
                    }

                    override suspend fun reportPlaybackFailure(
                        videoId: String,
                        clientName: String?,
                        responseCode: Int?,
                        profileId: String?,
                        hints: ContentHints,
                        category: PlaybackFailureCategory,
                    ) {
                        assertEquals(songs[1].id, videoId)
                        invalidations++
                        rejectResource = false
                    }

                    override suspend fun refresh(
                        videoId: String,
                        hints: ContentHints,
                        audioQuality: AudioQuality,
                        sourceSong: SongItem?,
                    ): PlaybackResource {
                        invalidations++
                        rejectResource = false
                        return resolve(videoId, hints, audioQuality, sourceSong)
                    }
                }
            val mediaPath = "${NSTemporaryDirectory()}${NSUUID().UUIDString}.m4a"
            try {
                val savingJob =
                    backgroundScope.launch {
                        IosPlaybackController(
                            player,
                            resolver,
                            database,
                            this,
                            persistenceDispatcher = StandardTestDispatcher(testScheduler),
                        ).apply {
                            toggleShuffle()
                            toggleRepeat()
                            toggleRepeat()
                        }
                    }
                runCurrent()
                val savedQueue = player.queueSnapshot()
                assertEquals(songs, savedQueue.originalItems)
                assertTrue(savedQueue.isShuffled)
                assertEquals(songs.take(2), savedQueue.displayedItems.take(2))
                assertEquals(songs.drop(2).toSet(), savedQueue.displayedItems.drop(2).toSet())
                assertEquals(1, savedQueue.currentIndex)
                advanceTimeBy(300L.milliseconds)
                runCurrent()
                val persisted = persistedState(settings)
                assertEquals(savedQueue, persisted.queue)
                assertEquals(RepeatMode.ONE, persisted.repeatMode)
                assertEquals(12_000L, persisted.positionMs)
                assertEquals(rememberModes, settings.pendingContinuePlayback)
                savingJob.cancel()
                runCurrent()
                player.setQueue(songs.reversed(), 0, startPlaying = false)
                player.seekTo(0L)

                val controller =
                    IosPlaybackController(
                        player,
                        resolver,
                        database,
                        backgroundScope,
                        persistenceDispatcher = StandardTestDispatcher(testScheduler),
                    )
                runCurrent()
                assertEquals(savedQueue.forRestore(rememberModes), player.queueSnapshot())
                assertEquals(12_000L, player.currentPosition.value)
                assertEquals(rememberModes, player.isShuffleEnabled)
                assertEquals(rememberModes, controller.state.value.isShuffled)
                assertEquals(if (rememberModes) RepeatMode.ONE else RepeatMode.OFF, controller.state.value.repeatMode)
                assertEquals(rememberModes, settings.pendingContinuePlayback)
                if (rememberModes) controller.toggleRepeat()
                assertFalse(player.isPlaying.value)
                assertNull(player.currentPlaybackResource.value)

                controller.togglePlayPause()
                runCurrent()
                assertTrue(controller.state.value.isLoading)
                assertEquals(12_000L, controller.state.value.positionMs)
                resolved.complete(
                    PlaybackResource(
                        videoId = songs[1].id,
                        url = "file://$mediaPath",
                        headers = emptyMap(),
                        loudnessDb = null,
                        expiresAt = null,
                        mimeType = "audio/mp4",
                        requireBoundedRange = false,
                    ),
                )
                runCurrent()
                assertEquals(12_000L, player.currentPosition.value)
                assertEquals(songs[1], player.currentSong.value)
                assertEquals(rememberModes, player.isShuffleEnabled)
                if (rememberModes) controller.toggleShuffle()
                assertFalse(player.isShuffleEnabled)
                assertEquals(songs, player.songQueue.value)

                val removal = assertNotNull(controller.removeQueueItems(setOf(0, 1)))
                assertEquals(listOf(IndexedQueueItem(0, songs[0])), removal.items)
                val secondRemoval = assertNotNull(controller.removeQueueItems(setOf(1)))
                assertEquals(listOf(songs[1], songs[3]), player.songQueue.value)
                assertEquals(songs[1], player.currentSong.value)
                controller.restoreQueueItems(listOf(removal, secondRemoval))
                assertEquals(songs, player.songQueue.value)
                assertEquals(1, player.currentMediaItemIndex.value)

                controller.toggleRepeat()
                controller.toggleRepeat()
                runCurrent()
                val previousResolutions = resolutions
                player.notifyPlaybackEnded()
                runCurrent()
                assertEquals(previousResolutions + 1, resolutions, "Repeat one did not resolve a new playback instance")
                assertTrue(player.isPlaying.value)

                rejectResource = true
                controller.play(songs[1], songs)
                runCurrent()
                player.handlePlaybackFailed(
                    NSError(
                        domain = "native-test",
                        code = -1,
                        userInfo =
                            mapOf(
                                NSLocalizedDescriptionKey to mediaPath,
                                NSUnderlyingErrorKey to
                                    NSError(
                                        domain = "underlying-test",
                                        code = -2,
                                        userInfo = mapOf(NSLocalizedDescriptionKey to mediaPath),
                                    ),
                            ),
                    ),
                )
                runCurrent()
                assertEquals("playback-failed native-test:-1 underlying-test:-2", player.loadError.value)
                assertEquals(PlaybackError.STREAM_UNAVAILABLE, controller.state.value.error)
                controller.togglePlayPause()
                runCurrent()
                assertEquals(1, invalidations, "Retry did not invalidate the failed resource")
                assertEquals(resolved.await(), player.currentPlaybackResource.value)
                assertNull(player.loadError.value)

                failResolution = true
                val failedSong = song("failed")
                controller.play(failedSong, songs + failedSong)
                runCurrent()
                assertEquals(
                    "failed",
                    controller.state.value.currentSong
                        ?.id,
                )
                assertEquals(PlaybackError.STREAM_UNAVAILABLE, controller.state.value.error)
                assertFalse(controller.state.value.isPlaying)
                assertFalse(player.isPlaying.value)
            } finally {
                backgroundScope.cancel()
                player.release()
                runCurrent()
                database.close()
            }
        }

    @Test
    fun backgroundCheckpointFailureDoesNotCrashAndCanRetry() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            var failWrites = false
            var failedWrites = 0
            val backing = MapSettings()
            val storage =
                object : KmpSettings by backing {
                    override fun putString(
                        key: String,
                        value: String,
                    ) {
                        if (failWrites && key == "persistentPlaybackPosition") {
                            failedWrites++
                            error("Test checkpoint write failure")
                        }
                        backing.putString(key, value)
                    }
                }
            val settings = Settings(storage)
            Settings.replaceSharedForTest(settings)
            settings.persistentQueue = true
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            try {
                player.setQueue(listOf(song("checkpoint")))
                val controller =
                    IosPlaybackController(
                        player,
                        fileResolver,
                        database,
                        backgroundScope,
                        persistenceDispatcher = dispatcher,
                    )
                runCurrent()
                failWrites = true
                controller.seekTo(18_000L)
                runCurrent()
                NSNotificationCenter.defaultCenter.postNotificationName(UIApplicationDidEnterBackgroundNotification, null)
                runCurrent()
                assertTrue(failedWrites >= 2)

                failWrites = false
                NSNotificationCenter.defaultCenter.postNotificationName(UIApplicationDidEnterBackgroundNotification, null)
                runCurrent()
                assertEquals(18_000L, persistedState(settings).positionMs)
            } finally {
                failWrites = false
                backgroundScope.cancel()
                player.release()
                runCurrent()
                database.close()
            }
        }

    @Test
    fun captureFailuresRetryAacOnceAndPreservePlaybackState() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            Settings.shared.autoLoadMore = false
            Settings.shared.audioQuality = "HIGH"
            for (admitted in listOf(false, true)) {
                for (playing in listOf(false, true)) {
                    val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
                    val player = MusicPlayer()
                    val client = HttpClient(MockEngine { awaitCancellation() })
                    val controllerScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
                    val cache = IosPlaybackCache(client, controllerScope)
                    val requests = mutableListOf<AudioQuality>()
                    var invalidations = 0
                    val songs = listOf(song("capture"), song("next"))
                    val aac = fileResolver.resolve(songs.first().id, ContentHints(), AudioQuality.MP4, songs.first())
                    val opus =
                        aac.copy(
                            url = "https://rr1.googlevideo.com/videoplayback",
                            mimeType = "audio/webm",
                            codecs = "opus",
                            itag = 251,
                            contentLengthBytes = 4096L,
                            requireBoundedRange = true,
                        )
                    val resolver =
                        object : StreamResolver by fileResolver {
                            override suspend fun resolve(
                                videoId: String,
                                hints: ContentHints,
                                audioQuality: AudioQuality,
                                sourceSong: SongItem?,
                            ): PlaybackResource {
                                requests += audioQuality
                                return if (audioQuality == AudioQuality.MP4) aac else opus
                            }

                            override suspend fun reportPlaybackFailure(
                                videoId: String,
                                clientName: String?,
                                responseCode: Int?,
                                profileId: String?,
                                hints: ContentHints,
                                category: PlaybackFailureCategory,
                            ) {
                                invalidations++
                            }
                        }
                    try {
                        val controller =
                            IosPlaybackController(player, resolver, database, controllerScope, persistenceDispatcher = dispatcher)
                        if (admitted) player.playbackCache = cache
                        val load =
                            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                controller.playQueueFromListenTogether(songs, 0, "Capture")
                            }
                        controller.seekTo(12_000L)
                        controller.setPlayingFromListenTogether(playing)
                        runCurrent()
                        assertTrue(load.isCompleted)
                        if (admitted) {
                            assertEquals(listOf(AudioQuality.HIGH), requests)
                            assertEquals(opus, player.currentPlaybackResource.value)
                            player.handlePlaybackFailed()
                            runCurrent()
                        }
                        assertEquals(listOf(AudioQuality.HIGH, AudioQuality.MP4), requests)
                        assertEquals(0, invalidations)
                        assertEquals(aac, player.currentPlaybackResource.value)
                        assertEquals(songs, player.songQueue.value)
                        assertEquals(0, player.currentMediaItemIndex.value)
                        assertEquals(12_000L, player.currentPosition.value)
                        assertEquals(playing, player.isPlaying.value)
                        assertNull(player.loadError.value)
                        assertNull(controller.state.value.error)
                        assertEquals("HIGH", Settings.shared.audioQuality)

                        player.play(
                            songs.first(),
                            aac.copy(url = "https://rr1.googlevideo.com/videoplayback?aac=1", requireBoundedRange = true),
                            startPlaying = playing,
                            startPositionMs = 12_000L,
                        )
                        if (player.loadError.value == null) player.handlePlaybackFailed()
                        runCurrent()
                        assertEquals(listOf(AudioQuality.HIGH, AudioQuality.MP4), requests)
                        assertNotNull(player.loadError.value)
                        assertEquals(PlaybackError.STREAM_UNAVAILABLE, controller.state.value.error)
                    } finally {
                        controllerScope.cancel()
                        player.release()
                        cache.close()
                        client.close()
                        database.close()
                    }
                }
            }
        }

    @Test
    fun nativeResumeRetriesFailedStreamAtSavedPosition() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            Settings.shared.autoLoadMore = false
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            var resolutions = 0
            var invalidations = 0
            val resolver =
                object : StreamResolver by fileResolver {
                    override suspend fun resolve(
                        videoId: String,
                        hints: ContentHints,
                        audioQuality: AudioQuality,
                        sourceSong: SongItem?,
                    ): PlaybackResource {
                        resolutions++
                        return fileResolver.resolve(videoId, hints, audioQuality, sourceSong)
                    }

                    override suspend fun reportPlaybackFailure(
                        videoId: String,
                        clientName: String?,
                        responseCode: Int?,
                        profileId: String?,
                        hints: ContentHints,
                        category: PlaybackFailureCategory,
                    ) {
                        invalidations++
                    }
                }
            try {
                val controller =
                    IosPlaybackController(
                        player,
                        resolver,
                        database,
                        backgroundScope,
                        persistenceDispatcher = dispatcher,
                    )
                val songs = listOf(song("failed"), song("next"))
                controller.play(songs.first(), songs)
                runCurrent()
                controller.seekTo(12_000L)
                player.handlePlaybackFailed()
                runCurrent()
                assertNotNull(player.loadError.value)
                assertFalse(player.isPlaying.value)

                player.resume()
                assertFalse(player.isPlaying.value)
                runCurrent()
                assertEquals(2, resolutions)
                assertEquals(1, invalidations)
                assertNull(player.loadError.value)
                assertNull(controller.state.value.error)
                assertEquals(songs.first(), player.currentSong.value)
                assertEquals(12_000L, player.currentPosition.value)
                assertTrue(player.isPlaying.value)
            } finally {
                backgroundScope.cancel()
                player.release()
                runCurrent()
                database.close()
            }
        }

    @Test
    fun persistenceChangesApplyToPausedAndDisabledStartupQueues() {
        for (initiallyEnabled in listOf(false, true)) {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val settings = Settings.shared
                val duplicate = song("saved")
                val songs = listOf(duplicate, song("middle"), duplicate)
                val saved =
                    QueuePlaybackState(
                        MusicQueue()
                            .apply {
                                setQueue(songs, startIndex = 2)
                                toggleShuffle()
                            }.snapshot(),
                    )
                settings.rememberShuffleAndRepeat = false
                settings.persistentQueue = initiallyEnabled
                settings.persistentPlaybackState = Json.encodeToString(saved)
                val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
                val player = MusicPlayer()
                try {
                    val controller =
                        IosPlaybackController(
                            player,
                            fileResolver,
                            database,
                            backgroundScope,
                            persistenceDispatcher = StandardTestDispatcher(testScheduler),
                        )
                    runCurrent()
                    assertFalse(player.isPlaying.value)
                    assertEquals(if (initiallyEnabled) songs else emptyList(), controller.state.value.queue)
                    if (initiallyEnabled) {
                        Snapshot.withMutableSnapshot { settings.persistentQueue = false }
                        runCurrent()
                    }
                    assertEquals("", settings.persistentPlaybackState)
                    Snapshot.withMutableSnapshot { settings.persistentQueue = true }
                    runCurrent()
                    if (initiallyEnabled) {
                        assertEquals(
                            saved.queue.forRestore(rememberShuffle = false),
                            persistedState(settings).queue,
                        )
                    } else {
                        assertEquals("", settings.persistentPlaybackState)
                        assertTrue(
                            controller.state.value.queue
                                .isEmpty(),
                        )
                    }
                } finally {
                    backgroundScope.cancel()
                    player.release()
                    runCurrent()
                    database.close()
                }
            }
        }
    }

    @Test
    fun videoLookupCannotOverrideNewPlaybackOrSurviveCancellation() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            responseDecodeDispatcher = dispatcher
            Settings.shared.autoLoadMore = false
            var lookupStarted = CompletableDeferred<Unit>()
            var lookupReply = CompletableDeferred<Unit>()
            var metadataAvailable = true
            val client =
                HttpClient(
                    MockEngine.create {
                        this.dispatcher = dispatcher
                        addHandler {
                            lookupStarted.complete(Unit)
                            withContext(NonCancellable) { lookupReply.await() }
                            respond(
                                if (metadataAvailable) {
                                    """{"queueDatas":[{"content":{"playlistPanelVideoRenderer":{
                                        "videoId":"linked-video","title":{"runs":[{"text":"Linked song"}]},
                                        "longBylineText":{"runs":[{"text":"Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}}]},
                                        "lengthText":{"runs":[{"text":"3:20"}]}
                                    }}}]}"""
                                } else {
                                    "{}"
                                },
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val repository = YouTubeRepository(InnerTube(client))
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            try {
                val controller =
                    IosPlaybackController(
                        player,
                        fileResolver,
                        database,
                        backgroundScope,
                        persistenceDispatcher = StandardTestDispatcher(testScheduler),
                    ) {
                        repository
                    }
                for (available in listOf(false, true)) {
                    for (action in listOf(null, "play", "cancel")) {
                        metadataAvailable = available
                        lookupStarted = CompletableDeferred()
                        lookupReply = CompletableDeferred()
                        controller.play(song("current"))
                        runCurrent()
                        var result: Boolean? = null
                        val lookup = launch { result = controller.resolveAndPlayVideo("linked-video") }
                        runCurrent()
                        assertTrue(lookupStarted.isCompleted)
                        when (action) {
                            "play" -> controller.play(song("replacement"))
                            "cancel" -> lookup.cancel()
                        }
                        lookupReply.complete(Unit)
                        runCurrent()
                        assertTrue(lookup.isCompleted)
                        if (action == null) {
                            assertEquals(available, result)
                        } else {
                            assertTrue(lookup.isCancelled)
                            assertNull(result)
                        }
                        val expectedId =
                            when {
                                action == "play" -> "replacement"
                                action == null && available -> "linked-video"
                                else -> "current"
                            }
                        assertEquals(
                            expectedId,
                            controller.state.value.currentSong
                                ?.id,
                        )
                    }
                }
            } finally {
                lookupReply.complete(Unit)
                backgroundScope.cancel()
                player.release()
                client.close()
                runCurrent()
                database.close()
            }
        }

    @Test
    fun radioForCurrentSongKeepsThePlaybackInstanceAndPosition() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            responseDecodeDispatcher = dispatcher
            Settings.shared.autoLoadMore = false
            val client =
                HttpClient(
                    MockEngine.create {
                        this.dispatcher = dispatcher
                        addHandler {
                            respond(
                                radioResponse(listOf("recommendation"), null),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val repository = YouTubeRepository(InnerTube(client))
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            try {
                val controller =
                    IosPlaybackController(
                        player,
                        fileResolver,
                        database,
                        backgroundScope,
                        persistenceDispatcher = dispatcher,
                    ) { repository }
                val seed = song("seed")
                controller.play(seed, listOf(song("previous"), seed, song("next")))
                runCurrent()
                player.pause()
                player.seekTo(42_000L)
                runCurrent()
                val instance = assertNotNull(player.playbackInstance.value)
                val resource = assertNotNull(player.currentPlaybackResource.value)

                controller.startRadio(seed)
                runCurrent()

                assertEquals(instance, player.playbackInstance.value)
                assertSame(resource, player.currentPlaybackResource.value)
                assertEquals(42_000L, player.currentPosition.value)
                assertFalse(player.isPlaying.value)
                assertEquals(listOf(seed), player.songQueue.value)
                assertEquals(
                    listOf("recommendation"),
                    controller.state.value.suggestedTracks
                        .map { it.id },
                )

                controller.startRadio(song("different"))
                runCurrent()
                assertNotEquals(instance, player.playbackInstance.value)
                assertEquals("different", player.currentSong.value?.id)
                assertEquals(0L, player.currentPosition.value)
            } finally {
                backgroundScope.cancel()
                player.release()
                client.close()
                runCurrent()
                database.close()
            }
        }

    @Test
    fun radioSkipsFilteredPagesAndStopsContinuationCycles() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            responseDecodeDispatcher = dispatcher
            Settings.shared.setSongBlocked("blocked", true)
            val responses = ArrayDeque<String>()
            var requests = 0
            val client =
                HttpClient(
                    MockEngine.create {
                        this.dispatcher = dispatcher
                        addHandler {
                            requests++
                            respond(responses.removeFirst(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val repository = YouTubeRepository(InnerTube(client))
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            try {
                val controller =
                    IosPlaybackController(
                        player,
                        fileResolver,
                        database,
                        backgroundScope,
                        persistenceDispatcher = StandardTestDispatcher(testScheduler),
                    ) {
                        repository
                    }
                for (autoLoad in listOf(false, true)) {
                    val firstId = "first-$autoLoad"
                    Settings.shared.autoLoadMore = autoLoad
                    requests = 0
                    responses.clear()
                    responses.addAll(
                        listOf(
                            radioResponse(listOf("seed", "blocked"), "first-page"),
                            radioResponse(listOf("blocked"), "second-page"),
                            radioResponse(listOf(firstId, firstId), "third-page"),
                            radioResponse(listOf("seed", firstId, "blocked"), "fourth-page"),
                            radioResponse(emptyList(), "fifth-page"),
                            radioResponse(listOf("last"), null),
                        ),
                    )
                    controller.startRadio(song("seed"))
                    runCurrent()
                    assertEquals(3, requests)
                    assertEquals(
                        listOf(firstId),
                        controller.state.value.suggestedTracks
                            .map { it.id },
                    )
                    assertTrue(
                        controller.state.value.queueChips
                            .isNotEmpty(),
                    )
                    assertFalse(controller.state.value.isQueueLoading)
                    assertTrue(controller.canSkipNext)
                    controller.playSuggestedTrack(song(firstId))
                    runCurrent()
                    assertEquals(if (autoLoad) 6 else 3, requests)
                    assertEquals(
                        if (autoLoad) listOf("last") else emptyList(),
                        controller.state.value.suggestedTracks
                            .map { it.id },
                    )
                    assertFalse(controller.state.value.isQueueLoading)
                }

                requests = 0
                responses.addAll(
                    listOf(
                        radioResponse(listOf("seed"), "first-page"),
                        radioResponse(listOf("blocked"), "second-page"),
                        radioResponse(listOf("seed"), "first-page"),
                    ),
                )
                controller.startRadio(song("seed"))
                runCurrent()
                assertEquals(3, requests)
                assertTrue(responses.isEmpty())
                assertTrue(
                    controller.state.value.suggestedTracks
                        .isEmpty(),
                )
                assertFalse(controller.state.value.isQueueLoading)
                assertFalse(controller.canSkipNext)
            } finally {
                backgroundScope.cancel()
                player.release()
                client.close()
                runCurrent()
                database.close()
            }
        }

    @Test
    fun radioQueueReplacementsPreferFreshSuggestionsButAllowExhaustedRepeats() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            responseDecodeDispatcher = dispatcher
            Settings.shared.autoLoadMore = false
            val responses =
                ArrayDeque(
                    listOf(
                        radioResponse(listOf("seed", "recent"), "fresh-page"),
                        radioResponse(listOf("seed", "recent"), "fresh-page"),
                        radioResponse(listOf("recent", "fresh", "fresh"), null),
                        radioResponse(listOf("seed", "recent"), null),
                    ),
                )
            var requests = 0
            val client =
                HttpClient(
                    MockEngine.create {
                        this.dispatcher = dispatcher
                        addHandler {
                            requests++
                            respond(responses.removeFirst(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val repository = YouTubeRepository(InnerTube(client))
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            try {
                val controller =
                    IosPlaybackController(
                        player,
                        fileResolver,
                        database,
                        backgroundScope,
                        persistenceDispatcher = dispatcher,
                    ) { repository }
                for ((expectedId, expectedRequests) in listOf("recent" to 1, "fresh" to 3, "recent" to 4)) {
                    controller.startRadio(song("seed"))
                    runCurrent()
                    assertEquals(expectedRequests, requests)
                    assertEquals(
                        listOf(expectedId),
                        controller.state.value.suggestedTracks
                            .map { it.id },
                    )
                    assertEquals(listOf("seed"), player.songQueue.value.map { it.id })
                    assertFalse(controller.state.value.isQueueLoading)
                }
                assertTrue(responses.isEmpty())
            } finally {
                backgroundScope.cancel()
                player.release()
                client.close()
                runCurrent()
                database.close()
            }
        }

    @Test
    fun nativeCompletionAdvancesWithReadyOrPendingRadioRecommendationsWithoutUiSubscribers() {
        for (recommendationsPending in listOf(false, true)) {
            runTest {
                val dispatcher = StandardTestDispatcher(testScheduler)
                Dispatchers.setMain(dispatcher)
                responseDecodeDispatcher = dispatcher
                Settings.shared.autoLoadMore = true
                val radioStarted = CompletableDeferred<Unit>()
                val radioReply = CompletableDeferred<Unit>()
                val client =
                    HttpClient(
                        MockEngine.create {
                            this.dispatcher = dispatcher
                            addHandler {
                                radioStarted.complete(Unit)
                                radioReply.await()
                                respond(
                                    radioResponse(listOf("seed", "next"), continuation = null),
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }
                        },
                    ) {
                        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                    }
                val repository = YouTubeRepository(InnerTube(client))
                val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
                val nativePlayer = AVPlayer()
                val player = MusicPlayer(initialPlayer = nativePlayer) { 1f }
                val resolvedSongs = mutableListOf<String>()
                val resolver =
                    object : StreamResolver {
                        override suspend fun resolve(
                            videoId: String,
                            hints: ContentHints,
                            audioQuality: AudioQuality,
                            sourceSong: SongItem?,
                        ): PlaybackResource {
                            resolvedSongs += videoId
                            return fileResolver.resolve(videoId, hints, audioQuality, sourceSong)
                        }

                        override suspend fun refresh(
                            videoId: String,
                            hints: ContentHints,
                            audioQuality: AudioQuality,
                            sourceSong: SongItem?,
                        ) = resolve(videoId, hints, audioQuality, sourceSong)
                    }
                try {
                    val controller =
                        IosPlaybackController(
                            player,
                            resolver,
                            database,
                            backgroundScope,
                            persistenceDispatcher = dispatcher,
                        ) { repository }
                    controller.startRadio(song("seed").copy(duration = 1L))
                    runCurrent()
                    assertTrue(radioStarted.isCompleted)
                    assertEquals("seed", player.currentSong.value?.id)
                    if (!recommendationsPending) {
                        radioReply.complete(Unit)
                        runCurrent()
                        assertEquals(listOf("seed", "next"), resolvedSongs)
                    }
                    NSNotificationCenter.defaultCenter.postNotificationName(
                        AVPlayerItemDidPlayToEndTimeNotification,
                        nativePlayer.currentItem,
                    )
                    runCurrent()
                    if (recommendationsPending) assertEquals("seed", player.currentSong.value?.id)
                    radioReply.complete(Unit)
                    runCurrent()
                    assertEquals("next", player.currentSong.value?.id)
                    assertEquals(listOf("seed", "next"), player.songQueue.value.map { it.id })
                } finally {
                    radioReply.complete(Unit)
                    backgroundScope.cancel()
                    player.release()
                    client.close()
                    database.close()
                }
            }
        }
    }

    @Test
    fun radioRetriesAndStationChangesRespectAutoLoadPreferences() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            responseDecodeDispatcher = dispatcher
            Settings.shared.persistentQueue = true
            val continuationStarted = CompletableDeferred<Unit>()
            val continuationReply = CompletableDeferred<Unit>()
            val chipReply = CompletableDeferred<Unit>()
            val policyContinuationReply = CompletableDeferred<Unit>()
            var requests = 0
            val engine =
                MockEngine.create {
                    this.dispatcher = dispatcher
                    addHandler {
                        val request = ++requests
                        if (request == 3) {
                            continuationStarted.complete(Unit)
                            withContext(NonCancellable) { continuationReply.await() }
                        }
                        if (request == 5) chipReply.await()
                        if (request == 8) withContext(NonCancellable) { policyContinuationReply.await() }
                        if (request == 2 || request == 5) {
                            respond("{}", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
                        } else {
                            respond(
                                radioResponse(
                                    when (request) {
                                        1 -> listOf("seed", "suggestion", "remaining", "picked")
                                        4 -> listOf("seed", "station-suggestion", "station-remaining", "station-picked")
                                        8 -> listOf("late-policy")
                                        else -> listOf("recommendation-$request")
                                    },
                                    continuation = "next-page".takeIf { request <= 9 },
                                ),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }
                }
            val client =
                HttpClient(engine) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val repository = YouTubeRepository(InnerTube(client))
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val player = MusicPlayer()
            try {
                val controller =
                    IosPlaybackController(
                        player,
                        fileResolver,
                        database,
                        backgroundScope,
                        persistenceDispatcher = StandardTestDispatcher(testScheduler),
                    ) {
                        repository
                    }
                controller.playQueue(listOf(song("seed")), 0, "station")
                runCurrent()
                val initialSuggestions =
                    controller.state.value.suggestedTracks
                        .map { it.id }
                assertEquals(listOf("suggestion", "remaining", "picked").sorted(), initialSuggestions.sorted())
                val (firstId, remainingId, pickedId) = initialSuggestions
                assertTrue(
                    controller.state.value.queueChips
                        .isNotEmpty(),
                )
                assertTrue(controller.canSkipNext)
                assertTrue(MPRemoteCommandCenter.sharedCommandCenter().nextTrackCommand.enabled)
                player.notifyPlaybackEnded()
                runCurrent()
                assertEquals(firstId, player.currentSong.value?.id)
                assertEquals(listOf("seed", firstId), player.songQueue.value.map { it.id })
                assertEquals(2, requests)
                assertTrue(
                    decodeQueuePlaybackState(
                        Settings.shared.persistentPlaybackState,
                        Settings.shared.persistentPlaybackPosition,
                    ).radioQueueFinite,
                )
                Snapshot.withMutableSnapshot { Settings.shared.autoLoadMore = false }
                runCurrent()
                assertFalse(controller.canSkipNext)
                assertFalse(MPRemoteCommandCenter.sharedCommandCenter().nextTrackCommand.enabled)
                player.notifyPlaybackEnded()
                runCurrent()
                assertEquals(MPRemoteCommandHandlerStatusCommandFailed, player.handleRemoteSkip(next = true))
                runCurrent()
                assertEquals(firstId, player.currentSong.value?.id)
                assertEquals(listOf("seed", firstId), player.songQueue.value.map { it.id })
                assertEquals(
                    initialSuggestions.drop(1),
                    controller.state.value.suggestedTracks
                        .map { it.id },
                )
                assertEquals(2, requests)
                Snapshot.withMutableSnapshot { Settings.shared.autoLoadMore = true }
                val chips = controller.state.value.queueChips
                controller.playSuggestedTrack(song(pickedId))
                runCurrent()
                assertEquals(pickedId, player.currentSong.value?.id)
                assertEquals(listOf("seed", firstId, pickedId), player.songQueue.value.map { it.id })
                assertTrue(
                    decodeQueuePlaybackState(
                        Settings.shared.persistentPlaybackState,
                        Settings.shared.persistentPlaybackPosition,
                    ).radioQueueFinite,
                )
                assertEquals(
                    listOf(remainingId),
                    controller.state.value.suggestedTracks
                        .map { it.id },
                )
                assertEquals("station", controller.state.value.queueTitle)
                assertEquals(chips, controller.state.value.queueChips)
                assertEquals(3, requests)
                assertTrue(continuationStarted.isCompleted)
                controller.playSuggestedTrack(song("not-suggested"))
                runCurrent()
                assertEquals(pickedId, player.currentSong.value?.id)
                assertTrue(MPRemoteCommandCenter.sharedCommandCenter().nextTrackCommand.enabled)
                assertEquals(MPRemoteCommandHandlerStatusSuccess, player.handleRemoteSkip(next = true))
                runCurrent()
                assertEquals(remainingId, player.currentSong.value?.id)
                assertEquals(listOf("seed", firstId, pickedId, remainingId), player.songQueue.value.map { it.id })
                assertEquals(3, requests)

                val album = (0..3).map { song("album-$it") }
                controller.play(album.first(), album)
                runCurrent()
                continuationReply.complete(Unit)
                runCurrent()
                assertEquals(album, player.songQueue.value)
                assertNull(controller.state.value.queueTitle)
                assertTrue(
                    controller.state.value.suggestedTracks
                        .isEmpty(),
                )
                assertTrue(
                    controller.state.value.queueChips
                        .isEmpty(),
                )
                assertFalse(controller.state.value.isQueueLoading)
                val saved = persistedState(Settings.shared)
                assertNull(saved.radioSeedSongId)
                assertNull(saved.radioPlaylistId)
                assertNull(saved.radioParams)
                controller.playQueueIndex(album.lastIndex)
                runCurrent()
                player.notifyPlaybackEnded()
                runCurrent()
                assertEquals(album.last(), player.currentSong.value)
                assertEquals(album, player.songQueue.value)
                assertEquals(3, requests)

                controller.playQueue(listOf(song("seed")), 0, "station")
                runCurrent()
                assertEquals(4, requests)
                val stationSuggestions =
                    controller.state.value.suggestedTracks
                        .map { it.id }
                assertEquals(listOf("station-suggestion", "station-remaining", "station-picked").sorted(), stationSuggestions.sorted())
                val previousStation =
                    persistedState(Settings.shared)
                controller.selectQueueChip("other")
                runCurrent()
                assertTrue(controller.state.value.isQueueLoading)
                assertEquals(chips, controller.state.value.queueChips)
                assertEquals(
                    stationSuggestions,
                    controller.state.value.suggestedTracks
                        .map { it.id },
                )
                player.notifyPlaybackEnded()
                runCurrent()
                assertEquals(stationSuggestions.first(), player.currentSong.value?.id)
                chipReply.complete(Unit)
                runCurrent()
                assertFalse(controller.state.value.isQueueLoading)
                assertEquals(chips, controller.state.value.queueChips)
                assertEquals(
                    stationSuggestions.drop(1),
                    controller.state.value.suggestedTracks
                        .map { it.id },
                )
                val retainedStation =
                    persistedState(Settings.shared)
                assertEquals(previousStation.radioPlaylistId, retainedStation.radioPlaylistId)
                assertEquals(previousStation.radioParams, retainedStation.radioParams)
                assertTrue(retainedStation.radioQueueFinite)
                assertEquals(5, requests)
                controller.selectQueueChip("other")
                runCurrent()
                assertEquals(6, requests)
                assertEquals(
                    "other",
                    controller.state.value.queueChips
                        .single { it.isSelected }
                        .id,
                )
                assertEquals(
                    listOf("recommendation-6"),
                    controller.state.value.suggestedTracks
                        .map { it.id },
                )
                val newStation =
                    persistedState(Settings.shared)
                assertEquals(chips.single { it.id == "other" }.playlistId, newStation.radioPlaylistId)
                assertFalse(newStation.radioQueueFinite)

                for (finiteQueue in listOf(album, album.take(1))) {
                    for (mode in listOf(RepeatMode.OFF, RepeatMode.ALL, RepeatMode.ONE)) {
                        Settings.shared.autoLoadMore = mode != RepeatMode.OFF
                        Settings.shared.disableLoadMoreWhenRepeatAll = true
                        controller.setRepeatMode(mode)
                        controller.playQueue(finiteQueue, 0, "finite")
                        runCurrent()
                        assertTrue(
                            controller.state.value.suggestedTracks
                                .isEmpty(),
                        )
                        assertTrue(
                            decodeQueuePlaybackState(
                                Settings.shared.persistentPlaybackState,
                                Settings.shared.persistentPlaybackPosition,
                            ).radioQueueFinite,
                        )
                        controller.playQueueIndex(finiteQueue.lastIndex)
                        runCurrent()
                        assertEquals(mode == RepeatMode.ALL, controller.canSkipNext)
                        player.notifyPlaybackEnded()
                        runCurrent()
                        assertEquals(if (mode == RepeatMode.ALL) finiteQueue.first() else finiteQueue.last(), player.currentSong.value)
                        assertEquals(finiteQueue, player.songQueue.value)
                        assertEquals(6, requests)
                    }
                }

                Settings.shared.autoLoadMore = true
                Settings.shared.disableLoadMoreWhenRepeatAll = false
                controller.setRepeatMode(RepeatMode.ALL)
                controller.playQueue(album, 0, "finite")
                runCurrent()
                assertEquals(7, requests)
                assertEquals(
                    listOf("recommendation-7"),
                    controller.state.value.suggestedTracks
                        .map { it.id },
                )
                controller.toggleRepeat()
                controller.toggleRepeat()
                Settings.shared.autoLoadMore = false
                controller.playQueueIndex(album.lastIndex)
                runCurrent()
                player.notifyPlaybackEnded()
                runCurrent()
                assertEquals(album.last(), player.currentSong.value)
                assertEquals(album, player.songQueue.value)
                assertEquals(7, requests)

                controller.playQueueIndex(0)
                runCurrent()
                Settings.shared.autoLoadMore = true
                controller.playQueueIndex(album.lastIndex)
                runCurrent()
                assertEquals(8, requests)
                assertTrue(controller.state.value.isQueueLoading)
                Settings.shared.autoLoadMore = false
                policyContinuationReply.complete(Unit)
                runCurrent()
                assertEquals(
                    listOf("recommendation-7"),
                    controller.state.value.suggestedTracks
                        .map { it.id },
                )
                assertFalse(controller.state.value.isQueueLoading)

                controller.startRadio(song("seed"))
                runCurrent()
                assertTrue(controller.canSkipNext)
                player.notifyPlaybackEnded()
                runCurrent()
                assertEquals(listOf("seed", "recommendation-9"), player.songQueue.value.map { it.id })
                assertEquals(9, requests)
                assertFalse(
                    decodeQueuePlaybackState(
                        Settings.shared.persistentPlaybackState,
                        Settings.shared.persistentPlaybackPosition,
                    ).radioQueueFinite,
                )

                for ((mode, enableViaSettings) in listOf(
                    RepeatMode.ONE to false,
                    RepeatMode.ALL to false,
                    RepeatMode.ALL to true,
                    RepeatMode.OFF to true,
                )) {
                    val previousRequests = requests
                    Snapshot.withMutableSnapshot {
                        Settings.shared.autoLoadMore = mode != RepeatMode.OFF
                        Settings.shared.disableLoadMoreWhenRepeatAll = true
                    }
                    controller.setRepeatMode(mode)
                    controller.playQueue(album, 0, "finite")
                    runCurrent()
                    assertEquals(previousRequests, requests)
                    assertTrue(
                        controller.state.value.suggestedTracks
                            .isEmpty(),
                    )
                    assertEquals(
                        album.first().id,
                        decodeQueuePlaybackState(
                            Settings.shared.persistentPlaybackState,
                            Settings.shared.persistentPlaybackPosition,
                        ).radioSeedSongId,
                    )
                    if (enableViaSettings) {
                        Snapshot.withMutableSnapshot {
                            if (mode == RepeatMode.ALL) {
                                Settings.shared.disableLoadMoreWhenRepeatAll = false
                            } else {
                                Settings.shared.autoLoadMore = true
                            }
                        }
                    } else {
                        controller.setRepeatMode(RepeatMode.OFF)
                    }
                    runCurrent()
                    assertEquals(previousRequests + 1, requests)
                    val recommendationId = "recommendation-${previousRequests + 1}"
                    assertEquals(
                        listOf(recommendationId),
                        controller.state.value.suggestedTracks
                            .map { it.id },
                    )
                    assertEquals(album, player.songQueue.value)

                    Snapshot.withMutableSnapshot { Settings.shared.autoLoadMore = false }
                    runCurrent()
                    Snapshot.withMutableSnapshot { Settings.shared.autoLoadMore = true }
                    runCurrent()
                    assertEquals(previousRequests + 1, requests)
                    controller.setRepeatMode(RepeatMode.OFF)
                    controller.playQueueIndex(album.lastIndex)
                    runCurrent()
                    player.notifyPlaybackEnded()
                    runCurrent()
                    assertEquals(recommendationId, player.currentSong.value?.id)
                    assertEquals(album.map { it.id } + recommendationId, player.songQueue.value.map { it.id })
                    assertEquals(previousRequests + 1, requests)
                }
            } finally {
                continuationReply.complete(Unit)
                chipReply.complete(Unit)
                policyContinuationReply.complete(Unit)
                backgroundScope.cancel()
                player.release()
                client.close()
                runCurrent()
                database.close()
            }
        }

    private fun IosPlaybackController.setRepeatMode(mode: RepeatMode) {
        repeat((mode.ordinal - state.value.repeatMode.ordinal + RepeatMode.entries.size) % RepeatMode.entries.size) {
            toggleRepeat()
        }
    }

    private fun radioResponse(
        ids: List<String>,
        continuation: String? = "next-page",
    ): String {
        val songs =
            buildList {
                ids.mapTo(this) {
                    """{"playlistPanelVideoRenderer":{"videoId":"$it","title":{"runs":[{"text":"$it"}]}}}"""
                }
                if (continuation != null) {
                    add("""{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$continuation"}}}}""")
                }
            }.joinToString(",")
        return """
            {"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{
              "watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"musicQueueRenderer":{
                "content":{"playlistPanelRenderer":{"contents":[$songs]}},
                "subHeaderChipCloud":{"chipCloudRenderer":{"chips":[{"chipCloudChipRenderer":{
                  "uniqueId":"chip", "text":{"runs":[{"text":"Station"}]}, "isSelected":true,
                  "navigationEndpoint":{"queueUpdateCommand":{"fetchContentsCommand":{"watchEndpoint":{"playlistId":"RDseed"}}}}
                }},{"chipCloudChipRenderer":{
                  "uniqueId":"other", "text":{"runs":[{"text":"Other station"}]},
                  "navigationEndpoint":{"queueUpdateCommand":{"fetchContentsCommand":{"watchEndpoint":{"playlistId":"RDother"}}}}
                }}]}}
              }}}}]}
            }}}}
            """.trimIndent()
    }

    private fun postAudioInterruption(type: ULong) {
        NSNotificationCenter.defaultCenter.postNotificationName(
            AVAudioSessionInterruptionNotification,
            `object` = null,
            userInfo =
                mapOf(
                    AVAudioSessionInterruptionTypeKey to NSNumber(unsignedLong = type),
                    AVAudioSessionInterruptionOptionKey to NSNumber(unsignedLong = AVAudioSessionInterruptionOptionShouldResume),
                ),
        )
    }

    private fun SongItem.toTrackInfo() =
        TrackInfo(id = id, title = title, artist = artists.joinToString { it.name }, duration = duration?.times(1000L) ?: 0L)

    private val fileResolver =
        object : StreamResolver {
            override suspend fun resolve(
                videoId: String,
                hints: ContentHints,
                audioQuality: AudioQuality,
                sourceSong: SongItem?,
            ) = PlaybackResource(
                videoId,
                "file://${NSTemporaryDirectory()}$videoId.m4a",
                emptyMap(),
                null,
                null,
                mimeType = "audio/mp4",
                requireBoundedRange = false,
            )

            override suspend fun refresh(
                videoId: String,
                hints: ContentHints,
                audioQuality: AudioQuality,
                sourceSong: SongItem?,
            ) = resolve(videoId, hints, audioQuality, sourceSong)
        }

    private fun song(id: String) = SongItem(id = id, title = id, thumbnail = null, artists = emptyList(), album = null, duration = 180L)
}
