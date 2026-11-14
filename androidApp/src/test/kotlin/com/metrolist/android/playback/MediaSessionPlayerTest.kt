package com.metrolist.android.playback

import androidx.media3.common.C
import androidx.media3.common.HeartRating
import androidx.media3.common.IllegalSeekPositionException
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.StarRating
import androidx.media3.common.ThumbRating
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.metrolist.shared.api.musicvideo.MusicVideoCounterpart
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.cast.CastConnectionStatus
import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.CastProtocol
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.deviceconnect.DcPlaybackAction
import com.metrolist.shared.deviceconnect.DcPlaybackCommand
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SearchResultPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueEntry
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.playback.QUEUE_SOURCE_ADDED_MANUALLY
import com.metrolist.shared.playback.QueuePlaybackRequest
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MediaSessionPlayerTest {
    @Before
    fun isolateSettings() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @After
    fun restoreSettings() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun scrobbleObserverPublishesResolvedDurationWithoutStartingAnotherListen() {
        val track = song("unknown")
        val backend = RecordingPlaybackPlayer(listOf(track))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val events = mutableListOf<Pair<SongItem?, Boolean>>()
        scope.launch { observeAndroidScrobbles(backend.player) { song, _, newPlayback -> events += song to newPlayback } }
        backend.isPlaying.value = true
        backend.duration.value = 180_000L
        assertEquals(180L, events.last().first?.duration)
        assertFalse(events.last().second)
        backend.duration.value = 0L
        assertEquals(null, events.last().first?.duration)
        backend.currentSong.value = track.copy(duration = 0L)
        backend.duration.value = 180_000L
        assertEquals(180L, events.last().first?.duration)
        assertFalse(events.last().second)
        scope.cancel()
    }

    @Test
    fun queueVideoAndFailedModeSwitchesPreserveViewerPreferenceAndForgetOldCounterparts() {
        Settings.shared.musicVideoPlaybackEnabled = true
        Settings.shared.videoViewerVisible = false
        val video = song("new-video").copy(musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
        val audio = song("new-audio")
        val backend = RecordingPlaybackPlayer(listOf(video))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val requestedSongs = mutableListOf<String>()
        val controller =
            AndroidVideoModeController(
                RuntimeEnvironment.getApplication(),
                backend.player,
                scope,
                MusicVideoResolver(
                    search = { _, _ -> SearchResultPage(emptyList()) },
                    counterpartLookup = { MusicVideoCounterpart(it.id, audio.id, positionMap = null) },
                    songLookup = { audio },
                ),
                withPlaybackLoading = { it() },
                fetchPlaybackResource = { song, _ ->
                    requestedSongs += song.id
                    null
                },
                onError = {},
            )
        try {
            controller.markQueueTarget("old-video")
            AndroidVideoModeController::class.java.getDeclaredField("videoModeOriginalAudioSong").apply {
                isAccessible = true
                set(controller, song("old-audio"))
            }
            controller.markQueueTarget(video.id)
            controller.onQueuePlaybackStarted(video, true)
            assertFalse(Settings.shared.videoViewerVisible)
            controller.setVideoMode(true)
            assertTrue(Settings.shared.videoViewerVisible)
            controller.setVideoMode(false)
            assertEquals(listOf(audio.id), requestedSongs)
            assertTrue(Settings.shared.videoViewerVisible)
            controller.onQueuePlaybackStarted(video, false)
            assertTrue(Settings.shared.videoViewerVisible)
            Settings.shared.videoViewerVisible = false
            controller.setVideoMode(true)
            assertFalse(Settings.shared.videoViewerVisible)
        } finally {
            controller.clearVideoMode()
            scope.cancel()
        }
    }

    @Test
    fun resumingHistoryDoesNotCreditThePrivateInterval() =
        runBlocking {
            Settings.shared.historyDuration = 30f
            Settings.shared.pauseListenHistory = true
            val track = song("private")
            val backend = RecordingPlaybackPlayer(listOf(track))
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val writes = mutableListOf<Long>()
            var now = 0L
            val history = AndroidListeningHistory(backend.player, scope, { _, time -> writes += time }, nowMs = { now })
            history.onPlaybackState(track, true, false)
            now += 60_000
            Settings.shared.pauseListenHistory = false
            history.onHistoryPausedChanged(false)
            now += 1_000
            history.flush()?.join()
            assertTrue(writes.isEmpty())
            scope.cancel()
        }

    @Test
    fun historyCountsReplaysAndFlushesTheLastPlayAfterServiceCancellation() =
        runBlocking {
            Settings.shared.historyDuration = 1f
            val repeated = song("repeated-history")
            val backend = RecordingPlaybackPlayer(listOf(repeated))
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val writes = mutableListOf<Pair<String, Long>>()
            var now = 0L
            val history = AndroidListeningHistory(backend.player, scope, { song, time -> writes += song.id to time }, nowMs = { now })
            history.start()
            backend.isPlaying.value = true
            now = 5_000L
            backend.queuePlaybackRequests.tryEmit(QueuePlaybackRequest(1, repeated, 0L, true))
            now = 7_000L
            backend.isPlaying.value = false
            now = 17_000L
            backend.isPlaying.value = true
            now = 20_000L
            val finalWrite = history.flush()
            scope.cancel()
            finalWrite?.join()
            assertEquals(listOf(repeated.id to 5_000L, repeated.id to 5_000L), writes)
            history.flush()?.join()
            assertEquals(2, writes.size)
        }

    @Test
    fun queueIndexAndMediaIdCommandsSelectExactDuplicateOccurrences() {
        val duplicate = song("duplicate")
        val backend = RecordingPlaybackPlayer(listOf(duplicate, song("other"), duplicate.copy()))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val gate = AutomotivePlaybackRequestGate()
        val adapter =
            MediaSessionPlayer(
                backend.player,
                MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                automotivePlaybackRequestGate = gate,
                scope = scope,
            )
        try {
            val selected = adapter.currentTimeline.getWindow(2, Timeline.Window()).mediaItem
            val stale = gate.resolve(gate.start(false), listOf(adapter.getMediaItemAt(0)), 0, 0L, replacesQueue = false)
            // Media3 maps the platform skipToQueueItem queue ID to this indexed command.
            adapter.seekToDefaultPosition(2)
            adapter.setMediaItems(stale.mediaItems.toMutableList(), stale.startIndex, stale.startPositionMs)
            assertEquals(2, backend.currentMediaItemIndex.value)
            assertEquals(selected.mediaId, adapter.currentMediaItem?.mediaId)
            adapter.seekTo(1, C.TIME_UNSET)
            assertEquals(1, backend.currentMediaItemIndex.value)
            assertEquals(0L, backend.currentPosition.value)
            adapter.removeMediaItem(0)
            adapter.setMediaItem(selected)
            assertEquals(1, backend.currentMediaItemIndex.value)
            assertEquals(selected.mediaId, adapter.currentMediaItem?.mediaId)
            assertEquals(
                listOf(1L, 2L),
                backend.queueState.value.entries
                    .map(MusicQueueEntry::key),
            )
            assertEquals(0, backend.setQueueCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun notificationSkipsAndRepeatWrapPreservePausedIntent() {
        val backend = RecordingPlaybackPlayer(listOf(song("first"), song("second")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val adapter =
            MediaSessionPlayer(
                backend.player,
                MutableStateFlow(Player.REPEAT_MODE_ALL),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                scope = scope,
            )
        try {
            for (playing in listOf(false, true)) {
                backend.wantsPlayback = playing
                adapter.seekToNextMediaItem()
                assertEquals(1, backend.currentMediaItemIndex.value)
                assertEquals(playing, backend.wantsPlayback)
                adapter.seekToNext()
                assertEquals(0, backend.currentMediaItemIndex.value)
                assertEquals(playing, backend.wantsPlayback)
                adapter.seekToPreviousMediaItem()
                assertEquals(1, backend.currentMediaItemIndex.value)
                assertEquals(playing, backend.wantsPlayback)
                adapter.seekToNextMediaItem()
                assertEquals(0, backend.currentMediaItemIndex.value)
                assertEquals(playing, backend.wantsPlayback)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun notificationNextWaitsForPlaylistContinuationBeforeWrapping() {
        val backend = RecordingPlaybackPlayer(listOf(song("last-loaded")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        var requests = 0
        val adapter =
            MediaSessionPlayer(
                backend.player,
                MutableStateFlow(Player.REPEAT_MODE_ALL),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                extendPlaylistBeforeSkip = {
                    requests++
                    true
                },
                scope = scope,
            )
        try {
            adapter.seekToNextMediaItem()
            adapter.seekToNext()
            assertEquals(2, requests)
            assertEquals(0L, backend.latestQueuePlaybackRequestId)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun volumeChangesPreserveSubPercentPrecisionWithoutChangingPlaybackIntent() {
        val backend = RecordingPlaybackPlayer(listOf(song("volume")))
        backend.wantsPlayback = true
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val adapter =
                MediaSessionPlayer(
                    backend.player,
                    MutableStateFlow(Player.REPEAT_MODE_OFF),
                    isPlaybackLockedProvider = { false },
                    isPlaybackLockedFlow = MutableStateFlow(false),
                    scope = scope,
                )
            for (requested in listOf(0.651f, 0.655f, 0.659f, 0f, 1f)) {
                adapter.volume = requested
                assertEquals(requested, backend.volume.value, 0f)
                assertEquals(requested, adapter.volume, 0f)
                assertTrue(backend.wantsPlayback)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun terminalErrorsNotifyControllersAndClearWhenPlaybackIsRetried() {
        val backend = RecordingPlaybackPlayer(listOf(song("failed")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val adapter =
                MediaSessionPlayer(
                    backend.player,
                    MutableStateFlow(Player.REPEAT_MODE_OFF),
                    isPlaybackLockedProvider = { false },
                    isPlaybackLockedFlow = MutableStateFlow(false),
                    scope = scope,
                )
            val changes = mutableListOf<PlaybackException?>()
            val errors = mutableListOf<PlaybackException>()
            adapter.addListener(
                object : Player.Listener {
                    override fun onPlayerErrorChanged(error: PlaybackException?) {
                        changes += error
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        errors += error
                    }
                },
            )
            val error = PlaybackException("failed", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            val queue = backend.player.songQueue.value
            val index = backend.player.currentMediaItemIndex.value
            adapter.setTerminalError(error)
            assertSame(error, adapter.playerError)
            assertEquals(Player.STATE_IDLE, adapter.playbackState)
            assertFalse(adapter.isPlaying)
            assertEquals(listOf(error), errors)
            adapter.play()
            assertEquals(null, adapter.playerError)
            assertEquals(listOf(error, null), changes)
            assertEquals(queue, backend.player.songQueue.value)
            assertEquals(index, backend.player.currentMediaItemIndex.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `external ratings set likes idempotently and publish current rating without changing the queue`() {
        val track = song("external-rating")
        val backend = RecordingPlaybackPlayer(listOf(track))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val adapter =
                MediaSessionPlayer(
                    backend.player,
                    MutableStateFlow(Player.REPEAT_MODE_OFF),
                    isPlaybackLockedProvider = { false },
                    isPlaybackLockedFlow = MutableStateFlow(false),
                    scope = scope,
                )
            var timelines = 0
            var metadataUpdates = 0
            adapter.addListener(
                object : Player.Listener {
                    override fun onTimelineChanged(
                        timeline: Timeline,
                        reason: Int,
                    ) {
                        timelines++
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        metadataUpdates++
                    }
                },
            )
            assertEquals(SessionResult.RESULT_SUCCESS, setMediaSessionRating(track, ThumbRating(true)))
            assertEquals(SessionResult.RESULT_SUCCESS, setMediaSessionRating(track, HeartRating(true)))
            assertTrue((adapter.mediaMetadata.userRating as ThumbRating).isThumbsUp)
            assertEquals(1, metadataUpdates)
            assertEquals(0, timelines)
            assertEquals(SessionResult.RESULT_SUCCESS, setMediaSessionRating(track, ThumbRating()))
            assertFalse(SongLikeState.isLiked(track.id))
            assertEquals(SessionError.ERROR_NOT_SUPPORTED, setMediaSessionRating(track, StarRating(5, 3f)))
            assertEquals(SessionError.ERROR_BAD_VALUE, setMediaSessionRating(null, ThumbRating(true)))
        } finally {
            SongLikeState.removeLike(track.id)
            scope.cancel()
        }
    }

    @Test
    fun `browse playback commands are available unless guest playback is locked`() {
        val locked = MutableStateFlow(false)
        val backend = RecordingPlaybackPlayer(emptyList())
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val adapter =
                MediaSessionPlayer(
                    backend.player,
                    MutableStateFlow(Player.REPEAT_MODE_OFF),
                    isPlaybackLockedProvider = { locked.value },
                    isPlaybackLockedFlow = locked,
                    scope = scope,
                )
            for (command in listOf(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_PREPARE, Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
                assertTrue(adapter.isCommandAvailable(command))
                assertTrue(automotivePlayerCommands().contains(command))
                assertTrue(isGuestRestrictedPlayerCommand(command))
            }
            locked.value = true
            assertFalse(adapter.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM))
            assertFalse(adapter.isCommandAvailable(Player.COMMAND_PREPARE))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `media session routes tempo and pitch to device connect`() {
        val backend = RecordingPlaybackPlayer(listOf(song("track")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val commands = mutableListOf<DcPlaybackCommand>()
        var locked = false
        try {
            val adapter =
                MediaSessionPlayer(
                    backend.player,
                    MutableStateFlow(Player.REPEAT_MODE_OFF),
                    isPlaybackLockedProvider = { locked },
                    isPlaybackLockedFlow = MutableStateFlow(false),
                    scope = scope,
                    deviceConnectInterceptor = { command, _ ->
                        commands += command
                        true
                    },
                )
            adapter.playbackParameters = PlaybackParameters(1.25f, 0.75f)
            assertEquals(
                listOf(DcPlaybackCommand(DcPlaybackAction.SPEED, speed = 1.25f), DcPlaybackCommand(DcPlaybackAction.PITCH, pitch = 0.75f)),
                commands,
            )
            assertEquals(PlaybackParameters.DEFAULT, adapter.playbackParameters)
            locked = true
            adapter.playbackParameters = PlaybackParameters(1f, 0.8f)
            assertEquals(PlaybackParameters(1f, 0.8f), adapter.playbackParameters)
            assertEquals(2, commands.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `media session keeps tempo and pitch independent`() {
        val backend = RecordingPlaybackPlayer(listOf(song("track")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val adapter =
                MediaSessionPlayer(
                    backend.player,
                    MutableStateFlow(Player.REPEAT_MODE_OFF),
                    isPlaybackLockedProvider = { false },
                    isPlaybackLockedFlow = MutableStateFlow(false),
                    scope = scope,
                )
            adapter.playbackParameters = PlaybackParameters(1.25f, 0.75f)
            assertEquals(PlaybackParameters(1.25f, 0.75f), adapter.playbackParameters)
            adapter.setPlaybackSpeed(1.5f)
            assertEquals(PlaybackParameters(1.5f, 0.75f), adapter.playbackParameters)
            adapter.playbackParameters = PlaybackParameters(1.5f, 1.2f)
            assertEquals(PlaybackParameters(1.5f, 1.2f), adapter.playbackParameters)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `remote seek buttons use configured increments and respect playback locks`() {
        Settings.shared.seekDurationSeconds = 10
        val backend = RecordingPlaybackPlayer(listOf(song("seekable")))
        backend.duration.value = 60_000L
        backend.currentPosition.value = 30_000L
        val locked = MutableStateFlow(false)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val adapter =
                MediaSessionPlayer(
                    backend.player,
                    MutableStateFlow(Player.REPEAT_MODE_OFF),
                    isPlaybackLockedProvider = { locked.value },
                    isPlaybackLockedFlow = locked,
                    scope = scope,
                )

            assertTrue(adapter.availableCommands.contains(Player.COMMAND_SEEK_BACK))
            assertTrue(adapter.availableCommands.contains(Player.COMMAND_SEEK_FORWARD))
            adapter.seekBack()
            assertEquals(20_000L, backend.currentPosition.value)
            adapter.seekForward()
            assertEquals(30_000L, backend.currentPosition.value)
            backend.currentPosition.value = 5_000L
            adapter.seekBack()
            assertEquals(0L, backend.currentPosition.value)
            backend.currentPosition.value = 55_000L
            adapter.seekForward()
            assertEquals(60_000L, backend.currentPosition.value)

            locked.value = true
            assertFalse(adapter.availableCommands.contains(Player.COMMAND_SEEK_BACK))
            assertFalse(adapter.availableCommands.contains(Player.COMMAND_SEEK_FORWARD))
            backend.currentPosition.value = 30_000L
            adapter.seekBack()
            assertEquals(30_000L, backend.currentPosition.value)
            adapter.seekForward()
            assertEquals(30_000L, backend.currentPosition.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `indexed seek is offered only for local unlocked playback`() {
        val backend = RecordingPlaybackPlayer(listOf(song("first"), song("second")))
        val locked = MutableStateFlow(false)
        val remote =
            MutableStateFlow(castState(generation = 1, durationMs = 10_000).copy(status = CastConnectionStatus.DISCONNECTED, device = null))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val adapter =
            MediaSessionPlayer(
                backend.player,
                MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { locked.value },
                isPlaybackLockedFlow = locked,
                remotePlaybackState = remote,
                scope = scope,
            )

        assertTrue(adapter.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM))
        locked.value = true
        assertFalse(adapter.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM))
        locked.value = false
        remote.value = castState(generation = 2, durationMs = 10_000)
        assertFalse(adapter.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM))
        adapter.seekTo(1, 0)
        assertEquals(0, backend.currentMediaItemIndex.value)

        scope.cancel()
    }

    @Test
    fun `scrobble observation keeps recovery and video replacements in one listen but detects replays`() {
        val repeated = song("repeated")
        val backend = RecordingPlaybackPlayer(listOf(repeated, repeated))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val events = mutableListOf<Triple<String?, Boolean, Boolean>>()
        scope.launch {
            observeAndroidScrobbles(backend.player) { song, playing, newPlayback ->
                events += Triple(song?.id, playing, newPlayback)
            }
        }

        assertEquals(0, backend.currentPosition.subscriptionCount.value)
        backend.currentPlaybackInstanceId = 1L
        backend.isPlaying.value = true
        backend.currentPlaybackInstanceId = 2L
        backend.currentPosition.value = 1L
        assertEquals(false, events.last().third)
        backend.currentPlaybackInstanceId = 3L
        backend.currentPosition.value = 2L
        assertEquals(false, events.last().third)

        backend.currentPlaybackInstanceId = 4L
        backend.queueState.value =
            backend.queueState.value.let { state ->
                state.copy(entries = state.entries.map { it.copy(key = it.key + 100) })
            }
        assertEquals(false, events.last().third)
        backend.currentPosition.value = 3L
        assertEquals(false, events.last().third)

        backend.queuePlaybackRequests.tryEmit(QueuePlaybackRequest(1, repeated, 0L, true))
        assertEquals(true, events.last().third)
        backend.currentPlaybackInstanceId = 5L
        backend.skipTo(1, 0L)
        assertEquals(true, events.last().third)
        val transitionEvents = events.size
        backend.currentPosition.value = 4L
        assertEquals(transitionEvents, events.size)
        backend.skipTo(0, 0L)
        assertEquals(false, events.last().third)
        backend.currentPlaybackInstanceId = 6L
        assertEquals(true, events.last().third)
        scope.cancel()
    }

    @Test
    fun `cast duration waits for the replacement media generation`() {
        val first = song("first")
        val next = song("next")
        val backend = RecordingPlaybackPlayer(listOf(first))
        val castState = MutableStateFlow(castState(generation = 1, durationMs = 10_000))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                remotePlaybackState = castState,
                scope = scope,
            )
        var timelineUpdates = 0
        adapter.addListener(
            object : Player.Listener {
                override fun onTimelineChanged(
                    timeline: Timeline,
                    reason: Int,
                ) {
                    timelineUpdates++
                }
            },
        )

        backend.player.setQueue(listOf(next))
        assertEquals(1, timelineUpdates)
        assertEquals("next", adapter.currentMediaItem?.mediaMetadata?.title)

        castState.value = castState(generation = 2, durationMs = 20_000)
        assertEquals(2, timelineUpdates)
        assertEquals(20_000, adapter.duration)

        scope.cancel()
    }

    @Test
    fun `cast generation refreshes known metadata duration after replacement`() {
        val first = song("first", duration = 10)
        val next = song("next", duration = 30)
        val backend = RecordingPlaybackPlayer(listOf(first))
        val castState = MutableStateFlow(castState(generation = 1, durationMs = 10_000))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                remotePlaybackState = castState,
                scope = scope,
            )
        var timelineUpdates = 0
        adapter.addListener(
            object : Player.Listener {
                override fun onTimelineChanged(
                    timeline: Timeline,
                    reason: Int,
                ) {
                    timelineUpdates++
                }
            },
        )

        backend.player.setQueue(listOf(next))
        assertEquals(1, timelineUpdates)
        assertEquals("next", adapter.currentMediaItem?.mediaMetadata?.title)

        castState.value = castState(generation = 2, durationMs = 29_000)
        assertEquals(2, timelineUpdates)
        assertEquals(29_000, adapter.duration)

        scope.cancel()
    }

    @Test
    fun `same cast generation publishes corrected duration without position churn`() {
        val backend = RecordingPlaybackPlayer(listOf(song("song")))
        val initialState = castState(generation = 1, durationMs = 10_000)
        val castState = MutableStateFlow(initialState)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                remotePlaybackState = castState,
                scope = scope,
            )
        val publishedDurations = mutableListOf<Long>()
        adapter.addListener(
            object : Player.Listener {
                override fun onTimelineChanged(
                    timeline: Timeline,
                    reason: Int,
                ) {
                    if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
                        publishedDurations += timeline.getWindow(0, Timeline.Window()).durationMs
                    }
                }
            },
        )

        castState.value = initialState.copy(durationMs = 12_000)
        castState.value = initialState.copy(durationMs = 12_000, positionMs = 1_000)
        castState.value = initialState.copy(durationMs = 13_000, positionMs = 1_000)

        assertEquals(listOf(12_000L, 13_000L), publishedDurations)

        scope.cancel()
    }

    @Test
    fun `cast connectivity changes publish the active duration source once`() {
        val backend = RecordingPlaybackPlayer(listOf(song("song")))
        backend.duration.value = 8_000
        val connectedState = castState(generation = 1, durationMs = 10_000)
        val castState = MutableStateFlow(connectedState)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                remotePlaybackState = castState,
                scope = scope,
            )
        val sourceDurations = mutableListOf<Long>()
        val commandUpdates = mutableListOf<Player.Commands>()
        adapter.addListener(
            object : Player.Listener {
                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    commandUpdates += availableCommands
                }

                override fun onTimelineChanged(
                    timeline: Timeline,
                    reason: Int,
                ) {
                    if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
                        sourceDurations += timeline.getWindow(0, Timeline.Window()).durationMs
                    }
                }
            },
        )

        castState.value = connectedState.copy(positionMs = 1_000)
        assertEquals(emptyList<Long>(), sourceDurations)

        castState.value = connectedState.copy(status = CastConnectionStatus.DISCONNECTED, device = null)
        assertEquals(listOf(8_000L), sourceDurations)

        castState.value = connectedState.copy(positionMs = 2_000)
        assertEquals(listOf(8_000L, 10_000L), sourceDurations)

        castState.value = connectedState.copy(positionMs = 3_000)
        assertEquals(listOf(8_000L, 10_000L), sourceDurations)
        assertEquals(2, commandUpdates.size)
        assertTrue(commandUpdates.all { it.contains(Player.COMMAND_PLAY_PAUSE) })

        scope.cancel()
    }

    @Test
    fun `local lifecycle changes preserve cast state without redundant callbacks`() {
        val backend =
            RecordingPlaybackPlayer(listOf(song("song"))).apply {
                playbackState.value = Player.STATE_READY
                wantsPlayback = true
                isPlaying.value = true
            }
        val remoteState = MutableStateFlow(castState(generation = 1, durationMs = 10_000).copy(isPlaying = true))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                remotePlaybackState = remoteState,
                scope = scope,
            )
        var isPlaying = adapter.isPlaying
        var wantsPlayback = adapter.playWhenReady
        var playbackState = adapter.playbackState
        var callbacks = 0
        adapter.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(value: Boolean) {
                    callbacks++
                    isPlaying = value
                }

                override fun onPlaybackStateChanged(state: Int) {
                    callbacks++
                    playbackState = state
                }

                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    callbacks++
                    wantsPlayback = playWhenReady
                }
            },
        )

        backend.isPlaying.value = false
        backend.wantsPlayback = false
        backend.playbackState.value = Player.STATE_IDLE
        assertEquals(true, isPlaying)
        assertEquals(true, wantsPlayback)
        assertEquals(Player.STATE_READY, playbackState)

        assertEquals(0, callbacks)
        remoteState.value = remoteState.value.copy(isPlaying = false)
        assertFalse(isPlaying)
        assertFalse(wantsPlayback)
        assertEquals(Player.STATE_READY, playbackState)
        assertTrue(callbacks > 0)
        scope.cancel()
    }

    @Test
    fun `runtime duration publishes for each consecutive duplicate occurrence`() {
        val duplicate = song("duplicate")
        val backend = RecordingPlaybackPlayer(listOf(duplicate, duplicate))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                scope = scope,
            )
        var sourceUpdates = 0
        adapter.addListener(
            object : Player.Listener {
                override fun onTimelineChanged(
                    timeline: Timeline,
                    reason: Int,
                ) {
                    if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) sourceUpdates++
                }
            },
        )

        backend.latestQueuePlaybackRequestId = 1
        backend.duration.value = 1_000
        assertEquals(1, sourceUpdates)

        backend.currentMediaItemIndex.value = 1
        backend.latestQueuePlaybackRequestId = 2
        backend.duration.value = 0
        backend.duration.value = 2_000
        assertEquals(2, sourceUpdates)

        scope.cancel()
    }

    @Test
    fun `playback lifecycle changes are forwarded without queue changes`() {
        val backend = RecordingPlaybackPlayer(listOf(song("restored")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                scope = scope,
            )
        var isPlaying = false
        var wantsPlayback = false
        var playbackState = Player.STATE_IDLE
        adapter.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(value: Boolean) {
                    isPlaying = value
                }

                override fun onPlaybackStateChanged(state: Int) {
                    playbackState = state
                }

                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    wantsPlayback = playWhenReady
                }
            },
        )

        backend.playbackState.value = Player.STATE_BUFFERING
        backend.wantsPlayback = true
        backend.isPlaying.value = true
        assertEquals(Player.STATE_BUFFERING, playbackState)
        assertEquals(true, wantsPlayback)
        assertEquals(true, isPlaying)

        backend.isPlaying.value = false
        backend.wantsPlayback = false
        backend.playbackState.value = Player.STATE_READY
        assertEquals(Player.STATE_READY, playbackState)
        assertEquals(false, wantsPlayback)
        assertEquals(false, isPlaying)

        backend.playbackState.value = Player.STATE_IDLE
        assertEquals(Player.STATE_IDLE, playbackState)
        assertEquals("restored", backend.currentSong.value?.id)

        scope.cancel()
    }

    @Test
    fun `stream recovery keeps transient idle session buffering`() {
        val transitions = PlaybackTransitionState()
        val backend = RecordingPlaybackPlayer(listOf(song("current")), transitions.pending)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                scope = scope,
            )
        var playbackState = Player.STATE_READY
        adapter.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    playbackState = state
                }
            },
        )

        backend.wantsPlayback = true
        transitions.beginRecovery(1)
        backend.playbackState.value = Player.STATE_IDLE
        assertEquals(Player.STATE_BUFFERING, adapter.playbackState)
        assertEquals(Player.STATE_BUFFERING, playbackState)

        adapter.pause()
        assertEquals(Player.STATE_READY, adapter.playbackState)
        assertEquals(Player.STATE_READY, playbackState)
        adapter.play()
        assertEquals(Player.STATE_BUFFERING, adapter.playbackState)
        transitions.finishRecovery(1)
        assertEquals(Player.STATE_IDLE, adapter.playbackState)
        assertEquals(Player.STATE_IDLE, playbackState)

        scope.cancel()
    }

    @Test
    fun `media item replacement autoplays while preserving queue order and selected occurrence`() {
        listOf(false, true).forEach { wasPlaying ->
            val freshSongs = listOf(song("first"), song("second"), song("third"))
            val songs = freshSongs.associateBy(SongItem::id)
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val automotiveBackend = RecordingPlaybackPlayer(listOf(song("old"))).apply { wantsPlayback = wasPlaying }
            var automotiveQueueReplacements = 0
            var appliedQueueTitle: String? = null
            var appliedShuffleMode: Boolean? = null
            var appliedStartIndex: Int? = null
            val requestGate =
                AutomotivePlaybackRequestGate { _, startIndex, queueTitle, shuffleModeEnabled, _, replacement ->
                    automotiveQueueReplacements++
                    appliedQueueTitle = queueTitle
                    appliedShuffleMode = shuffleModeEnabled
                    appliedStartIndex = startIndex
                    replacement()
                    if (shuffleModeEnabled == true) automotiveBackend.toggleShuffle()
                }
            val automotiveAdapter =
                MediaSessionPlayer(
                    musicPlayer = automotiveBackend.player,
                    repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                    isPlaybackLockedProvider = { false },
                    isPlaybackLockedFlow = MutableStateFlow(false),
                    mediaItemResolver = { item -> songs[AutomotiveMediaIds.songId(item.mediaId)] },
                    automotivePlaybackRequestGate = requestGate,
                    isAutomotiveSongBlocked = { false },
                    scope = scope,
                )
            val generation = requestGate.start(isGuestPlaybackLocked = false)
            val result =
                requestGate.resolve(
                    requestGeneration = generation,
                    mediaItems = freshSongs.map { mediaItem(AutomotiveMediaIds.song(it.id)) },
                    startIndex = 0,
                    startPositionMs = 0L,
                    queueTitle = "Automotive queue",
                    shuffleModeEnabled = true,
                )

            automotiveAdapter.setMediaItems(result.mediaItems.toMutableList(), result.startIndex, result.startPositionMs)

            assertEquals(1, automotiveQueueReplacements)
            assertEquals("Automotive queue", appliedQueueTitle)
            assertEquals(true, appliedShuffleMode)
            assertEquals(1, automotiveBackend.setQueueCalls)
            assertEquals(true, automotiveBackend.lastSetQueueStartPlaying)
            assertEquals(freshSongs, automotiveBackend.queueSnapshot.originalItems)
            assertEquals(freshSongs[appliedStartIndex!!], automotiveBackend.currentSong.value)
            automotiveBackend.toggleShuffle()
            assertEquals(freshSongs, automotiveBackend.songQueue.value)
            assertEquals(appliedStartIndex, automotiveBackend.currentMediaItemIndex.value)

            val regularBackend = RecordingPlaybackPlayer(listOf(song("old"))).apply { wantsPlayback = wasPlaying }
            val regularAdapter =
                MediaSessionPlayer(
                    musicPlayer = regularBackend.player,
                    repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                    isPlaybackLockedProvider = { false },
                    isPlaybackLockedFlow = MutableStateFlow(false),
                    mediaItemResolver = { songs[it.mediaId] },
                    automotivePlaybackRequestGate = requestGate,
                    isAutomotiveSongBlocked = { false },
                    scope = scope,
                )

            regularAdapter.setMediaItems(mutableListOf(mediaItem(freshSongs.first().id)), 0, 0L)

            assertEquals(1, regularBackend.setQueueCalls)
            assertEquals(true, regularBackend.lastSetQueueStartPlaying)
            scope.cancel()
        }
    }

    @Test
    fun `bulk automotive resolution rejects missing items and preserves explicit occurrences`() {
        val a = song("a")
        val b = song("b")
        val duplicate = song("duplicate")

        assertEquals(
            null,
            resolveAutomotiveSongRequest(3, 1, null, null, listOf(null, a, b)),
        )
        assertEquals(
            null,
            resolveAutomotiveSongRequest(3, 1, null, null, listOf(a, null, b)),
        )

        val cachedSelection = AutomotiveSongQueue(listOf(song("cached"), song("cached-next")), startIndex = 1)
        val explicitBulk =
            resolveAutomotiveSongRequest(
                requestedItemCount = 3,
                requestedStartIndex = 2,
                pinnedSongs = null,
                singleItemQueue = cachedSelection,
                individuallyResolvedSongs = listOf(duplicate, a, duplicate.copy()),
            )
        assertEquals(listOf("duplicate", "a", "duplicate"), explicitBulk?.songs?.map(SongItem::id))
        assertEquals(2, explicitBulk?.startIndex)

        val singleSelection = resolveAutomotiveSongRequest(1, 0, null, cachedSelection, emptyList())
        assertEquals(listOf("cached", "cached-next"), singleSelection?.songs?.map(SongItem::id))
        assertEquals(1, singleSelection?.startIndex)
    }

    @Test
    fun `empty automotive result is rejected before a newer selection and controller clear still works`() {
        val fresh = song("fresh")
        val backend = RecordingPlaybackPlayer(listOf(song("old")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val requestGate = AutomotivePlaybackRequestGate()
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                mediaItemResolver = { item -> fresh.takeIf { AutomotiveMediaIds.songId(item.mediaId) == it.id } },
                automotivePlaybackRequestGate = requestGate,
                isAutomotiveSongBlocked = { false },
                scope = scope,
            )
        val emptyGeneration = requestGate.start(isGuestPlaybackLocked = false)

        assertThrows(CancellationException::class.java) {
            requestGate.resolve(
                requestGeneration = emptyGeneration,
                mediaItems = emptyList(),
                startIndex = 0,
                startPositionMs = 0L,
            )
        }

        val freshGeneration = requestGate.start(isGuestPlaybackLocked = false)
        val freshResult =
            requestGate.resolve(
                requestGeneration = freshGeneration,
                mediaItems = listOf(mediaItem(AutomotiveMediaIds.song(fresh.id))),
                startIndex = 0,
                startPositionMs = 0L,
            )
        adapter.setMediaItems(freshResult.mediaItems.toMutableList(), freshResult.startIndex, freshResult.startPositionMs)

        assertEquals(listOf("fresh"), backend.songQueue.value.map(SongItem::id))
        assertEquals(1, backend.setQueueCalls)

        adapter.clearMediaItems()
        assertEquals(emptyList<SongItem>(), backend.songQueue.value)
        scope.cancel()
    }

    @Test
    fun `cached automotive selection reapplies block policy and preserves selected occurrence`() {
        val blockedArtistId = "blocked-artist"
        val cachedSongs =
            listOf(
                song("allowed"),
                song("blocked-song"),
                song("blocked-artist-song").copy(artists = listOf(ArtistTiny(blockedArtistId, "Blocked artist"))),
                song("selected"),
            )
        val cachedById = cachedSongs.associateBy(SongItem::id)
        val blockedSongIds = mutableSetOf<String>()
        val blockedArtistIds = mutableSetOf<String>()
        val backend = RecordingPlaybackPlayer(listOf(song("old")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        var queueReplacements = 0
        val requestGate =
            AutomotivePlaybackRequestGate { _, _, _, _, _, replacement ->
                queueReplacements++
                replacement()
            }
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                mediaItemResolver = { item -> cachedById[AutomotiveMediaIds.songId(item.mediaId)] },
                automotivePlaybackRequestGate = requestGate,
                isAutomotiveSongBlocked = { candidate ->
                    candidate.id in blockedSongIds ||
                        candidate.artists.any { artist -> artist.id?.let(blockedArtistIds::contains) == true }
                },
                scope = scope,
            )
        val cachedMediaItems = cachedSongs.map { mediaItem(AutomotiveMediaIds.song(it.id)) }
        blockedSongIds += "blocked-song"
        blockedArtistIds += blockedArtistId
        val generation = requestGate.start(isGuestPlaybackLocked = false)
        val result =
            requestGate.resolve(
                requestGeneration = generation,
                mediaItems = cachedMediaItems,
                startIndex = 3,
                startPositionMs = 0L,
            )

        adapter.setMediaItems(result.mediaItems.toMutableList(), result.startIndex, result.startPositionMs)

        assertEquals(listOf("allowed", "selected"), backend.songQueue.value.map(SongItem::id))
        assertEquals(1, backend.currentMediaItemIndex.value)
        assertEquals(true, backend.lastSetQueueStartPlaying)
        assertEquals(1, backend.setQueueCalls)
        assertEquals(1, queueReplacements)

        blockedSongIds += setOf("allowed", "selected")
        val allBlockedGeneration = requestGate.start(isGuestPlaybackLocked = false)
        val allBlockedResult =
            requestGate.resolve(
                requestGeneration = allBlockedGeneration,
                mediaItems = cachedMediaItems,
                startIndex = 3,
                startPositionMs = 0L,
            )
        adapter.setMediaItems(
            allBlockedResult.mediaItems.toMutableList(),
            allBlockedResult.startIndex,
            allBlockedResult.startPositionMs,
        )

        assertEquals(listOf("allowed", "selected"), backend.songQueue.value.map(SongItem::id))
        assertEquals(1, backend.setQueueCalls)
        assertEquals(1, queueReplacements)
        scope.cancel()
    }

    @Test
    fun `automotive queue selection keeps exact duplicate occurrence without replacement`() {
        val duplicate = song("duplicate")
        val backend = RecordingPlaybackPlayer(listOf(duplicate, duplicate.copy()))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        var queueReplacements = 0
        val requestGate =
            AutomotivePlaybackRequestGate { _, _, _, _, _, replacement ->
                queueReplacements++
                replacement()
            }
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                automotivePlaybackRequestGate = requestGate,
                isAutomotiveSongBlocked = { false },
                scope = scope,
            )
        val selected = adapter.getMediaItemAt(1)
        val generation = requestGate.start(isGuestPlaybackLocked = false)
        val result =
            requestGate.resolve(
                requestGeneration = generation,
                mediaItems = listOf(selected),
                startIndex = 0,
                startPositionMs = 500L,
                replacesQueue = false,
            )

        adapter.setMediaItems(result.mediaItems.toMutableList(), result.startIndex, result.startPositionMs)

        assertEquals(1, backend.currentMediaItemIndex.value)
        assertEquals(500L, backend.currentPosition.value)
        assertEquals(
            listOf(0L, 1L),
            backend.queueState.value.entries
                .map(MusicQueueEntry::key),
        )
        assertEquals(0, backend.setQueueCalls)
        assertEquals(0, queueReplacements)
        scope.cancel()
    }

    @Test
    fun `automotive queue selection routes through device connect`() {
        val backend = RecordingPlaybackPlayer(listOf(song("first"), song("selected")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val requestGate = AutomotivePlaybackRequestGate()
        var interceptedCommand: DcPlaybackCommand? = null
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                automotivePlaybackRequestGate = requestGate,
                isAutomotiveSongBlocked = { false },
                deviceConnectInterceptor = { command, _ ->
                    interceptedCommand = command
                    true
                },
                scope = scope,
            )
        val generation = requestGate.start(isGuestPlaybackLocked = false)
        val result =
            requestGate.resolve(
                requestGeneration = generation,
                mediaItems = listOf(adapter.getMediaItemAt(1)),
                startIndex = 0,
                startPositionMs = 500L,
                replacesQueue = false,
            )

        adapter.setMediaItems(result.mediaItems.toMutableList(), result.startIndex, result.startPositionMs)

        assertEquals(DcPlaybackAction.SELECT, interceptedCommand?.action)
        assertEquals(1, interceptedCommand?.index)
        assertEquals(500L, interceptedCommand?.positionMs)
        assertEquals(0, backend.currentMediaItemIndex.value)
        assertEquals(0, backend.setQueueCalls)
        scope.cancel()
    }

    @Test
    fun `voice resumption keeps duplicate occurrence queue and position`() {
        val duplicate = song("duplicate")
        val backend = RecordingPlaybackPlayer(listOf(duplicate, song("other"), duplicate.copy()))
        backend.skipTo(2, 1234L)
        val before = backend.queueState.value
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val requestGate = AutomotivePlaybackRequestGate()
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                automotivePlaybackRequestGate = requestGate,
                isAutomotiveSongBlocked = { false },
                scope = scope,
            )
        val generation = requestGate.start(isGuestPlaybackLocked = false)
        val result =
            requestGate.resolve(
                requestGeneration = generation,
                mediaItems = listOf(adapter.getMediaItemAt(2)),
                startIndex = 0,
                startPositionMs = 1234L,
                replacesQueue = false,
            )
        adapter.setMediaItems(result.mediaItems.toMutableList(), result.startIndex, result.startPositionMs)
        assertEquals(before, backend.queueState.value)
        assertEquals(1234L, backend.currentPosition.value)
        assertEquals(0, backend.setQueueCalls)

        val emptyGeneration = requestGate.start(isGuestPlaybackLocked = false)
        assertThrows(CancellationException::class.java) {
            requestGate.resolve(emptyGeneration, emptyList(), 0, 0L)
        }
        assertEquals(before, backend.queueState.value)
        scope.cancel()
    }

    @Test
    fun `overlapping automotive requests apply only newest result to backend`() {
        val songs = listOf(song("old"), song("fresh")).associateBy(SongItem::id)
        val backend = RecordingPlaybackPlayer(listOf(songs.getValue("old")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val requestGate = AutomotivePlaybackRequestGate()
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                mediaItemResolver = { item -> songs[AutomotiveMediaIds.songId(item.mediaId)] },
                automotivePlaybackRequestGate = requestGate,
                isAutomotiveSongBlocked = { false },
                scope = scope,
            )
        val staleStarted = CompletableDeferred<Unit>()
        val releaseStale = CompletableDeferred<Unit>()
        val staleFuture =
            scope.future {
                val generation = requestGate.start(isGuestPlaybackLocked = false)
                staleStarted.complete(Unit)
                releaseStale.await()
                requestGate.resolve(
                    requestGeneration = generation,
                    mediaItems = listOf(mediaItem(AutomotiveMediaIds.song("old"))),
                    startIndex = 0,
                    startPositionMs = 0L,
                )
            }
        runBlocking { staleStarted.await() }
        val freshFuture =
            scope.future {
                val generation = requestGate.start(isGuestPlaybackLocked = false)
                requestGate.resolve(
                    requestGeneration = generation,
                    mediaItems = listOf(mediaItem(AutomotiveMediaIds.song("fresh"))),
                    startIndex = 0,
                    startPositionMs = 0L,
                )
            }

        fun apply(result: MediaSession.MediaItemsWithStartPosition) {
            adapter.setMediaItems(result.mediaItems.toMutableList(), result.startIndex, result.startPositionMs)
        }

        apply(freshFuture.get())
        releaseStale.complete(Unit)
        assertThrows(CancellationException::class.java) { apply(staleFuture.get()) }

        assertTrue(staleFuture.isCancelled)
        assertEquals(1, backend.setQueueCalls)
        assertEquals(listOf("fresh"), backend.songQueue.value.map(SongItem::id))

        val delayedGeneration = requestGate.start(isGuestPlaybackLocked = false)
        val delayedResult =
            requestGate.resolve(
                requestGeneration = delayedGeneration,
                mediaItems = listOf(mediaItem(AutomotiveMediaIds.song("old"))),
                startIndex = 0,
                startPositionMs = 0L,
            )
        val latestGeneration = requestGate.start(isGuestPlaybackLocked = false)
        val latestResult =
            requestGate.resolve(
                requestGeneration = latestGeneration,
                mediaItems = listOf(mediaItem(AutomotiveMediaIds.song("fresh"))),
                startIndex = 0,
                startPositionMs = 0L,
            )

        apply(delayedResult)
        assertEquals(1, backend.setQueueCalls)
        apply(latestResult)
        assertEquals(2, backend.setQueueCalls)
        assertEquals(listOf("fresh"), backend.songQueue.value.map(SongItem::id))

        scope.cancel()
    }

    @Test
    fun `unresolved selection and guest requests cancel automotive application`() {
        val requestGate = AutomotivePlaybackRequestGate()
        val unresolved = requestGate.start(isGuestPlaybackLocked = false)

        assertThrows(CancellationException::class.java) {
            requestGate.resolve(
                requestGeneration = unresolved,
                mediaItems = listOf(mediaItem("unresolved")),
                startIndex = 0,
                startPositionMs = 0L,
                canApply = false,
            )
        }

        val superseded = requestGate.start(isGuestPlaybackLocked = false)
        assertThrows(CancellationException::class.java) {
            requestGate.start(isGuestPlaybackLocked = true)
        }
        assertThrows(CancellationException::class.java) {
            requestGate.resolve(
                requestGeneration = superseded,
                mediaItems = listOf(mediaItem("stale")),
                startIndex = 0,
                startPositionMs = 0L,
            )
        }
    }

    @Test
    fun `cached duplicate occurrence survives queue edits and is rejected after removal`() {
        val duplicate = song("duplicate")
        val backend = RecordingPlaybackPlayer(listOf(duplicate, song("other"), duplicate.copy()))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                scope = scope,
            )
        val cachedItem = adapter.getMediaItemAt(2)
        val selectedKey = AutomotiveMediaIds.queueSong(cachedItem.mediaId)!!.entryKey

        adapter.removeMediaItem(0)
        assertEquals(1, automotiveQueueIndex(cachedItem.mediaId, backend.queueState.value.entries))
        adapter.moveMediaItem(1, 0)
        assertEquals(0, automotiveQueueIndex(cachedItem.mediaId, backend.queueState.value.entries))
        adapter.setMediaItems(mutableListOf(cachedItem), 0, 0)
        assertEquals(0, backend.currentMediaItemIndex.value)
        assertEquals(0, backend.setQueueCalls)
        assertEquals(
            listOf(selectedKey, 1L),
            backend.queueState.value.entries
                .map(MusicQueueEntry::key),
        )

        adapter.removeMediaItem(0)
        adapter.setMediaItems(mutableListOf(cachedItem), 0, 0)
        assertEquals(
            listOf(1L),
            backend.queueState.value.entries
                .map(MusicQueueEntry::key),
        )

        scope.cancel()
    }

    @Test
    fun `equal duplicate reorder preserves occurrence ids for subsequent selection`() {
        val duplicate = song("duplicate")
        val backend = RecordingPlaybackPlayer(listOf(duplicate, duplicate.copy()))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                scope = scope,
            )
        val first = adapter.getMediaItemAt(0)
        val second = adapter.getMediaItemAt(1)

        adapter.setMediaItems(mutableListOf(second, first), 0, 0)
        assertEquals(
            listOf(1L, 0L),
            backend.queueState.value.entries
                .map(MusicQueueEntry::key),
        )
        assertEquals(listOf(second.mediaId, first.mediaId), List(2) { adapter.getMediaItemAt(it).mediaId })

        adapter.seekTo(1, 250)
        adapter.setMediaItems(mutableListOf(first), 0, 250)
        assertEquals(
            listOf(1L, 0L),
            backend.queueState.value.entries
                .map(MusicQueueEntry::key),
        )
        assertEquals(1, backend.currentMediaItemIndex.value)
        assertEquals(250, backend.currentPosition.value)

        scope.cancel()
    }

    @Test
    fun `replacement without reset keeps numeric position and falls back when invalid`() {
        val songs = listOf("a", "b", "c", "d", "e", "f", "g", "h").associateWith(::song)
        val backend = RecordingPlaybackPlayer(listOf(songs.getValue("a"), songs.getValue("b"), songs.getValue("c")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                mediaItemResolver = { songs[it.mediaId] },
                scope = scope,
            )
        backend.skipTo(2, 30_000)

        val currentItems = List(3, adapter::getMediaItemAt)
        adapter.setMediaItems(currentItems.reversed().toMutableList(), false)
        assertEquals(listOf("c", "b", "a"), backend.songQueue.value.map(SongItem::id))
        assertEquals(
            listOf(2L, 1L, 0L),
            backend.queueState.value.entries
                .map(MusicQueueEntry::key),
        )
        assertEquals(2, backend.currentMediaItemIndex.value)
        assertEquals(30_000, backend.currentPosition.value)

        adapter.setMediaItems(mutableListOf(mediaItem("d"), mediaItem("e"), mediaItem("f")), false)
        assertEquals(listOf("d", "e", "f"), backend.songQueue.value.map(SongItem::id))
        assertEquals(2, backend.currentMediaItemIndex.value)
        assertEquals(30_000, backend.currentPosition.value)

        adapter.setMediaItems(mutableListOf(mediaItem("g"), mediaItem("h")), false)
        assertEquals(0, backend.currentMediaItemIndex.value)
        assertEquals(0, backend.currentPosition.value)

        adapter.setMediaItems(mutableListOf(), false)
        assertEquals(emptyList<SongItem>(), backend.songQueue.value)
        assertEquals(-1, backend.currentMediaItemIndex.value)

        scope.cancel()
    }

    @Test
    fun `explicit start index controls replacement position`() {
        val songs = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i").associateWith(::song)
        val backend = RecordingPlaybackPlayer(listOf(songs.getValue("a"), songs.getValue("b"), songs.getValue("c")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                mediaItemResolver = { songs[it.mediaId] },
                scope = scope,
            )
        backend.skipTo(2, 30_000)

        adapter.setMediaItems(
            mutableListOf(mediaItem("d"), mediaItem("e"), mediaItem("f")),
            C.INDEX_UNSET,
            C.TIME_UNSET,
        )
        assertEquals(2, backend.currentMediaItemIndex.value)
        assertEquals(30_000, backend.currentPosition.value)

        adapter.setMediaItems(mutableListOf(mediaItem("g"), mediaItem("h"), mediaItem("i")), 1, C.TIME_UNSET)
        assertEquals(1, backend.currentMediaItemIndex.value)
        assertEquals(0, backend.currentPosition.value)

        assertThrows(IllegalSeekPositionException::class.java) {
            adapter.setMediaItems(mutableListOf(mediaItem("g"), mediaItem("h")), 2, 1_000)
        }

        scope.cancel()
    }

    @Test
    fun `cast connected empty mutations stop the receiver once`() {
        val songs = listOf("a", "b").associateWith(::song)
        val backend = RecordingPlaybackPlayer(listOf(songs.getValue("a")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val remoteState = MutableStateFlow(castState(generation = 1, durationMs = 1_000))
        var playbackLocked = false
        var remoteStops = 0
        val queueChanges = mutableListOf<Boolean>()
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { playbackLocked },
                isPlaybackLockedFlow = MutableStateFlow(false),
                remotePlaybackState = remoteState,
                onRemoteStop = { remoteStops++ },
                onQueueChanged = queueChanges::add,
                mediaItemResolver = { songs[it.mediaId] },
                scope = scope,
            )

        adapter.clearMediaItems()
        adapter.clearMediaItems()
        assertEquals(1, remoteStops)

        backend.player.setQueue(listOf(songs.getValue("a")))
        adapter.removeMediaItem(0)
        assertEquals(2, remoteStops)

        backend.player.setQueue(listOf(songs.getValue("a")))
        adapter.removeMediaItems(0, 1)
        assertEquals(3, remoteStops)

        backend.player.setQueue(listOf(songs.getValue("a")))
        adapter.replaceMediaItems(0, 1, listOf(mediaItem("b")))
        assertEquals(3, remoteStops)
        assertEquals(listOf("b"), backend.songQueue.value.map(SongItem::id))
        adapter.replaceMediaItems(0, 1, emptyList())
        adapter.replaceMediaItems(0, 0, emptyList())
        assertEquals(4, remoteStops)

        backend.player.setQueue(listOf(songs.getValue("a")))
        playbackLocked = true
        adapter.clearMediaItems()
        assertEquals(listOf("a"), backend.songQueue.value.map(SongItem::id))
        assertEquals(4, remoteStops)
        assertEquals(listOf(false, false, false, true, true), queueChanges)
        scope.cancel()
    }

    @Test
    fun `replacing the only paused queue item preserves the paused state`() {
        val songs = listOf("paused", "replacement").associateWith(::song)
        val backend = RecordingPlaybackPlayer(listOf(songs.getValue("paused")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                mediaItemResolver = { songs[it.mediaId] },
                scope = scope,
            )

        adapter.replaceMediaItems(0, 1, listOf(mediaItem("replacement")))

        assertEquals(listOf("replacement"), backend.songQueue.value.map(SongItem::id))
        assertEquals(false, backend.lastSetQueueStartPlaying)
        assertFalse(backend.wantsPlayback)
        scope.cancel()
    }

    @Test
    fun `clear then add selects the real core queue item and autoplays`() {
        val songs = listOf("old", "paused", "playing").associateWith(::song)
        val backend = RecordingPlaybackPlayer(listOf(songs.getValue("old")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                mediaItemResolver = { songs[it.mediaId] },
                scope = scope,
            )

        adapter.clearMediaItems()
        adapter.addMediaItem(mediaItem("paused"))

        assertEquals(0, backend.coreCurrentMediaItemIndex)
        assertEquals("paused", backend.coreCurrentSong?.id)
        assertEquals(QUEUE_SOURCE_ADDED_MANUALLY, backend.coreCurrentSong?.queueSourceTitle)
        assertEquals(true, backend.lastSetQueueStartPlaying)
        assertEquals(true, backend.lastSetQueuePreparePlayback)

        adapter.play()
        assertEquals(1, backend.resumeCalls)
        assertEquals("paused", backend.coreCurrentSong?.id)

        adapter.clearMediaItems()
        adapter.addMediaItem(mediaItem("playing"))
        assertEquals(0, backend.coreCurrentMediaItemIndex)
        assertEquals("playing", backend.coreCurrentSong?.id)
        assertEquals(true, backend.lastSetQueueStartPlaying)
        scope.cancel()
    }

    @Test
    fun browseQueueActionPreservesPlaybackAndAppendsDuplicateOccurrences() =
        runBlocking {
            for (playing in listOf(false, true)) {
                val current = song("current")
                val selected = song("selected")
                val backend = RecordingPlaybackPlayer(listOf(current, selected))
                val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
                try {
                    backend.currentPosition.value = 12_345L
                    backend.isPlaying.value = playing
                    val adapter =
                        MediaSessionPlayer(
                            musicPlayer = backend.player,
                            repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                            isPlaybackLockedProvider = { false },
                            isPlaybackLockedFlow = MutableStateFlow(false),
                            mediaItemResolver = { selected },
                            scope = scope,
                        )
                    val requestId = AutomotiveMediaIds.browseSong(AutomotiveMediaIds.SONGS, 1, selected.id)
                    val result =
                        addAutomotiveSongToQueue(
                            requestId,
                            { false },
                            { listOf(mediaItem(selected.id)) },
                            { adapter.addMediaItems(it.toMutableList()) },
                        )
                    assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
                    assertEquals(listOf("current", "selected", "selected"), backend.songQueue.value.map { it.id })
                    assertEquals(current.id, backend.currentSong.value?.id)
                    assertEquals(0, backend.currentMediaItemIndex.value)
                    assertEquals(12_345L, backend.currentPosition.value)
                    assertEquals(playing, backend.isPlaying.value)
                    assertEquals(0, backend.resumeCalls)
                } finally {
                    scope.cancel()
                }
            }
        }

    @Test
    fun `adding after dismissal replaces retained queue and autoplays`() {
        val songs = listOf("old", "added", "next").associateWith(::song)
        val backend = RecordingPlaybackPlayer(listOf(songs.getValue("old"), songs.getValue("added")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val queueChanges = mutableListOf<Boolean>()
        try {
            val adapter =
                MediaSessionPlayer(
                    musicPlayer = backend.player,
                    repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                    isPlaybackLockedProvider = { false },
                    isPlaybackLockedFlow = MutableStateFlow(false),
                    mediaItemResolver = { songs[it.mediaId] },
                    onQueueChanged = queueChanges::add,
                    scope = scope,
                )
            backend.currentSong.value = null
            backend.playbackState.value = Player.STATE_IDLE
            adapter.addMediaItems(mutableListOf(mediaItem("added"), mediaItem("next")))
            assertEquals(listOf("added", "next"), backend.songQueue.value.map(SongItem::id))
            assertEquals("added", backend.currentSong.value?.id)
            assertEquals(0, backend.coreCurrentMediaItemIndex)
            assertEquals(true, backend.lastSetQueueStartPlaying)
            assertEquals(true, backend.lastSetQueuePreparePlayback)
            assertEquals(listOf(true), queueChanges)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `empty mutation invalidates delayed recommendation completion`() {
        val backend = RecordingPlaybackPlayer(listOf(song("seed")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        var queueSessionId = 4L
        var recommendationGeneration = 7L
        val expectedSessionId = queueSessionId
        val expectedRecommendationGeneration = recommendationGeneration
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                onQueueChanged = { replacesQueue ->
                    if (replacesQueue || backend.songQueue.value.isEmpty()) {
                        queueSessionId++
                        recommendationGeneration++
                    }
                },
                scope = scope,
            )

        adapter.clearMediaItems()
        if (
            isCurrentRecommendationRequest(
                expectedSessionId,
                expectedRecommendationGeneration,
                queueSessionId,
                recommendationGeneration,
            )
        ) {
            backend.player.addToQueue(song("delayed-recommendation"))
        }

        assertEquals(emptyList<SongItem>(), backend.songQueue.value)
        assertEquals(
            false,
            isCurrentRecommendationRequest(expectedSessionId, expectedRecommendationGeneration, queueSessionId, recommendationGeneration),
        )
        scope.cancel()
    }

    @Test
    fun `advertised media item mutations update the playback queue`() {
        val songs = listOf("a", "b", "c", "d", "e", "f").associateWith(::song)
        val backend = RecordingPlaybackPlayer(listOf(songs.getValue("a"), songs.getValue("b")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val queueChanges = mutableListOf<Boolean>()
        val adapter =
            MediaSessionPlayer(
                musicPlayer = backend.player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                onQueueChanged = queueChanges::add,
                mediaItemResolver = { songs[it.mediaId] },
                scope = scope,
            )

        backend.skipTo(1, 0)
        adapter.setMediaItems(mutableListOf(mediaItem(AutomotiveMediaIds.queueSong(1L, "b"))), 0, 0)
        assertEquals(listOf("a", "b"), backend.songQueue.value.map(SongItem::id))
        assertEquals(1, backend.resumeCalls)

        adapter.addMediaItem(1, mediaItem("e"))
        adapter.addMediaItems(mutableListOf(mediaItem("c"), mediaItem("d")))
        assertEquals(listOf("a", "e", "b", "c", "d"), backend.songQueue.value.map(SongItem::id))

        adapter.moveMediaItems(1, 3, 3)
        assertEquals(listOf("a", "c", "d", "e", "b"), backend.songQueue.value.map(SongItem::id))

        adapter.replaceMediaItems(1, 3, listOf(mediaItem("f")))
        assertEquals(listOf("a", "f", "e", "b"), backend.songQueue.value.map(SongItem::id))

        adapter.removeMediaItems(2, 4)
        assertEquals(listOf("a", "f"), backend.songQueue.value.map(SongItem::id))

        adapter.setMediaItems(mutableListOf(mediaItem("c"), mediaItem("d")), 1, 1_200)
        assertEquals(listOf("c", "d"), backend.songQueue.value.map(SongItem::id))
        assertEquals(1, backend.currentMediaItemIndex.value)
        assertEquals(1_200, backend.currentPosition.value)
        assertEquals(true, backend.lastSetQueueStartPlaying)
        assertEquals(true, backend.lastSetQueuePreparePlayback)

        adapter.clearMediaItems()
        assertEquals(emptyList<SongItem>(), backend.songQueue.value)
        assertEquals(listOf(false, false, false, false, false, true, false), queueChanges)

        scope.cancel()
    }

    @Test
    fun mediaSessionSeeksKeepTheirCommandAndDirection() {
        val backend = RecordingPlaybackPlayer(listOf(song("track")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val adapter =
                MediaSessionPlayer(
                    backend.player,
                    MutableStateFlow(Player.REPEAT_MODE_OFF),
                    isPlaybackLockedProvider = { false },
                    isPlaybackLockedFlow = MutableStateFlow(false),
                    scope = scope,
                )
            Settings.shared.seekDurationSeconds = 10
            backend.duration.value = 120_000L
            backend.currentPosition.value = 50_000L
            adapter.seekBack()
            assertEquals(40_000L, backend.currentPosition.value)
            adapter.seekForward()
            assertEquals(50_000L, backend.currentPosition.value)
            adapter.seekTo(34_000L)
            assertEquals(34_000L, backend.currentPosition.value)
            assertEquals(listOf("seek_back", "seek_forward", "seek_to"), backend.seekSources.map { it.details["command"] })
            assertTrue(backend.seekSources.all { it.name == "media_session" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun unknownDurationSeekForwardPreservesProgressAndKnownDurationClamps() {
        val backend = RecordingPlaybackPlayer(listOf(song("track")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val adapter =
                MediaSessionPlayer(
                    backend.player,
                    MutableStateFlow(Player.REPEAT_MODE_OFF),
                    isPlaybackLockedProvider = { false },
                    isPlaybackLockedFlow = MutableStateFlow(false),
                    scope = scope,
                )
            Settings.shared.seekDurationSeconds = 10
            backend.currentPosition.value = 5_000
            for (duration in listOf(0L, C.TIME_UNSET)) {
                backend.duration.value = duration
                val before = backend.currentPosition.value
                adapter.seekForward()
                assertEquals(before + 10_000, backend.currentPosition.value)
            }
            backend.duration.value = 30_000
            adapter.seekForward()
            assertEquals(30_000L, backend.currentPosition.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun backendSpeedAndPitchChangesNotifyMediaControllers() {
        val backend = RecordingPlaybackPlayer(listOf(song("track")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val adapter =
                MediaSessionPlayer(
                    backend.player,
                    MutableStateFlow(Player.REPEAT_MODE_OFF),
                    isPlaybackLockedProvider = { false },
                    isPlaybackLockedFlow = MutableStateFlow(false),
                    scope = scope,
                )
            val parameters = mutableListOf<PlaybackParameters>()
            var events = 0
            adapter.addListener(
                object : Player.Listener {
                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                        parameters += playbackParameters
                    }

                    override fun onEvents(
                        player: Player,
                        changed: Player.Events,
                    ) {
                        if (changed.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) events++
                    }
                },
            )
            backend.playbackSpeed.value = 1.5f
            backend.playbackPitch.value = 0.8f
            assertEquals(listOf(PlaybackParameters(1.5f, 1f), PlaybackParameters(1.5f, 0.8f)), parameters)
            assertEquals(parameters.last(), adapter.playbackParameters)
            assertEquals(2, events)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun followingAnotherDeviceRejectsBulkEditsWithoutChangingItsLocalMirror() {
        val tracks = listOf(song("a"), song("b"), song("c"))
        val backend = RecordingPlaybackPlayer(tracks)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val adapter =
                MediaSessionPlayer(
                    backend.player,
                    MutableStateFlow(Player.REPEAT_MODE_OFF),
                    isPlaybackLockedProvider = { false },
                    isPlaybackLockedFlow = MutableStateFlow(false),
                    isDeviceConnectFollowing = { true },
                    mediaItemResolver = {
                        tracks.firstOrNull { track ->
                            track.id == it.mediaId
                        }
                    },
                    scope = scope,
                )
            adapter.moveMediaItems(0, 2, 1)
            adapter.removeMediaItems(0, 2)
            adapter.replaceMediaItems(0, 2, listOf(mediaItem("c")))
            assertEquals(tracks, backend.songQueue.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun queueAdditionsRecheckBlockedSongs() {
        val tracks = listOf(song("a"), song("blocked"), song("allowed"))
        val backend = RecordingPlaybackPlayer(tracks.take(1))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val adapter =
                MediaSessionPlayer(
                    backend.player,
                    MutableStateFlow(Player.REPEAT_MODE_OFF),
                    isPlaybackLockedProvider = { false },
                    isPlaybackLockedFlow = MutableStateFlow(false),
                    isAutomotiveSongBlocked = { it.id == "blocked" },
                    mediaItemResolver = {
                        tracks.firstOrNull { track ->
                            track.id == it.mediaId
                        }
                    },
                    scope = scope,
                )
            adapter.addMediaItems(mutableListOf(mediaItem("blocked"), mediaItem("allowed")))
            assertEquals(listOf("a", "allowed"), backend.songQueue.value.map { it.id })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun sleepTimerPausesTheActiveCastSession() {
        val backend = RecordingPlaybackPlayer(listOf(song("track")))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            var requestedPlayback: Boolean? = null
            val adapter =
                MediaSessionPlayer(
                    backend.player,
                    MutableStateFlow(Player.REPEAT_MODE_OFF),
                    isPlaybackLockedProvider = { false },
                    isPlaybackLockedFlow = MutableStateFlow(false),
                    remotePlaybackState = MutableStateFlow(castState(1, 60_000)),
                    onRemotePlaybackChange = { requestedPlayback = it },
                    scope = scope,
                )
            val timer = MusicSleepTimerPlayer(backend.player, pausePlayback = adapter::pause, cancelCrossfade = {})
            timer.pause()
            assertEquals(false, requestedPlayback)
        } finally {
            scope.cancel()
        }
    }

    private fun song(
        id: String,
        duration: Long? = null,
    ) = SongItem(id = id, title = id, artists = emptyList(), album = null, thumbnail = null, duration = duration)

    private fun mediaItem(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    private fun castState(
        generation: Long,
        durationMs: Long,
    ) = CastState(
        status = CastConnectionStatus.CONNECTED,
        device = CastDevice("device", "Receiver", CastProtocol.FCAST, "192.0.2.1", 46899),
        durationMs = durationMs,
        mediaGeneration = generation,
    )
}

@Suppress("UNCHECKED_CAST")
private class RecordingPlaybackPlayer(
    initialQueue: List<SongItem>,
    val playbackTransitionPending: StateFlow<Boolean> = MutableStateFlow(false),
) : InvocationHandler {
    val seekSources = mutableListOf<PlaybackSeekSource>()

    private val coreQueue = MusicQueue().apply { setQueue(initialQueue) }
    val currentSong = MutableStateFlow(coreQueue.currentSong())
    val isPlaying = MutableStateFlow(false)
    val volume = MutableStateFlow(1f)
    val playbackSpeed = MutableStateFlow(1f)
    val playbackPitch = MutableStateFlow(1f)
    val currentPosition = MutableStateFlow(0L)
    val bufferedPosition = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val currentPlaybackResource = MutableStateFlow<com.metrolist.shared.youtube.playback.PlaybackResource?>(null)
    val songQueue = MutableStateFlow(coreQueue.items.value)
    val currentMediaItemIndex = MutableStateFlow(coreQueue.currentIndex.value)
    val queueState = MutableStateFlow(coreQueue.state.value)
    val queuePlaybackRequests = MutableSharedFlow<QueuePlaybackRequest>(replay = 1)
    val onEof = MutableSharedFlow<Long>()
    val streamFailure = MutableSharedFlow<AndroidPlaybackRecoveryRequest>()
    val shuffleEnabled = MutableStateFlow(false)
    val loadError = MutableStateFlow<String?>(null)
    val playbackState = MutableStateFlow(Player.STATE_IDLE)
    private val wantsPlaybackFlow = MutableStateFlow(false)
    var wantsPlayback: Boolean
        get() = wantsPlaybackFlow.value
        set(value) {
            wantsPlaybackFlow.value = value
        }
    var setQueueCalls = 0
    var resumeCalls = 0
    var lastSetQueueStartPlaying: Boolean? = null
    var lastSetQueuePreparePlayback: Boolean? = null
    var latestQueuePlaybackRequestId = 0L
    val playbackInstanceId = MutableStateFlow(0L)
    var currentPlaybackInstanceId: Long
        get() = playbackInstanceId.value
        set(value) {
            playbackInstanceId.value = value
        }

    val coreCurrentMediaItemIndex: Int get() = coreQueue.currentIndex.value
    val coreCurrentSong: SongItem? get() = coreQueue.currentSong()
    val queueSnapshot get() = coreQueue.snapshot()

    val player: AndroidPlaybackPlayer =
        Proxy.newProxyInstance(
            AndroidPlaybackPlayer::class.java.classLoader,
            arrayOf(AndroidPlaybackPlayer::class.java),
            this,
        ) as AndroidPlaybackPlayer

    override fun invoke(
        proxy: Any,
        method: Method,
        args: Array<out Any?>?,
    ): Any? =
        when (method.name) {
            "getCurrentSong" -> {
                currentSong
            }

            "isPlaying" -> {
                isPlaying
            }

            "getWantsPlayback" -> {
                wantsPlayback
            }

            "getWantsPlaybackState" -> {
                wantsPlaybackFlow
            }

            "getPlaybackState" -> {
                playbackState.value
            }

            "getPlaybackStateFlow" -> {
                playbackState
            }

            "getPlaybackTransitionPending" -> {
                playbackTransitionPending
            }

            "getVolume" -> {
                volume
            }

            "setVolume" -> {
                volume.value = args!![0] as Float
            }

            "getPlaybackPitch" -> {
                playbackPitch
            }

            "playbackSpeed" -> {
                playbackSpeed.value
            }

            "setPlaybackSpeed" -> {
                playbackSpeed.value = args!![0] as Float
                Unit
            }

            "setPlaybackPitch" -> {
                playbackPitch.value = args!![0] as Float
                Unit
            }

            "setTempoPitch" -> {
                playbackSpeed.value = args!![0] as Float
                playbackPitch.value = args[1] as Float
                Unit
            }

            "getPlaybackSpeed" -> {
                playbackSpeed
            }

            "getCurrentPosition" -> {
                currentPosition
            }

            "getRealtimePositionMs" -> {
                currentPosition.value
            }

            "getBufferedPosition" -> {
                bufferedPosition
            }

            "getDuration" -> {
                duration
            }

            "getCurrentPlaybackResource" -> {
                currentPlaybackResource
            }

            "getSongQueue" -> {
                songQueue
            }

            "getCurrentMediaItemIndex" -> {
                currentMediaItemIndex
            }

            "getQueueState" -> {
                queueState
            }

            "getQueuePlaybackRequests" -> {
                queuePlaybackRequests
            }

            "getLatestQueuePlaybackRequestId" -> {
                latestQueuePlaybackRequestId
            }

            "getPlaybackInstanceId" -> {
                playbackInstanceId
            }

            "getCurrentPlaybackInstanceId" -> {
                currentPlaybackInstanceId
            }

            "getOnEOFPlaybackInstance" -> {
                onEof
            }

            "getStreamFailure" -> {
                streamFailure
            }

            "getShuffleEnabled" -> {
                shuffleEnabled
            }

            "getLoadError" -> {
                loadError
            }

            "insertQueueItems" -> {
                insert(args!![0] as Int, args[1] as List<SongItem>)
            }

            "resume" -> {
                resumeCalls++
                wantsPlayback = true
            }

            "pause" -> {
                wantsPlayback = false
            }

            "moveQueueItem" -> {
                move(args!![0] as Int, args[1] as Int)
            }

            "removeQueueItem" -> {
                remove(setOf(args!![0] as Int))
            }

            "removeQueueItems" -> {
                remove(args!![0] as Set<Int>)
                null
            }

            "updateQueueItems" -> {
                update(args!![0] as Map<Int, SongItem>)
            }

            "setQueue" -> {
                setQueue(args!!)
            }

            "skipToQueueItem" -> {
                skipTo(args!![0] as Int, args[1] as Long)
                wantsPlayback = args[2] as Boolean
            }

            "seekTo" -> {
                currentPosition.value = args!![0] as Long
                (args.getOrNull(1) as? PlaybackSeekSource)?.let(seekSources::add)
            }

            "toString" -> {
                "RecordingPlaybackPlayer"
            }

            "hashCode" -> {
                System.identityHashCode(proxy)
            }

            "equals" -> {
                proxy === args?.get(0)
            }

            else -> {
                defaultValue(method.returnType)
            }
        }

    private fun insert(
        index: Int,
        songs: List<SongItem>,
    ) {
        coreQueue.addToQueue(songs, insertIndex = index)
        publishCoreQueue()
    }

    private fun move(
        fromIndex: Int,
        toIndex: Int,
    ) {
        coreQueue.move(fromIndex, toIndex)
        publishCoreQueue()
    }

    private fun remove(indices: Set<Int>) {
        coreQueue.removeAt(indices)
        publishCoreQueue()
    }

    private fun update(updates: Map<Int, SongItem>) {
        coreQueue.updateItems(updates)
        publishCoreQueue()
    }

    private fun setQueue(args: Array<out Any?>) {
        setQueueCalls++
        coreQueue.setQueue(args[0] as List<SongItem>, args[1] as Int)
        publishCoreQueue()
        currentPosition.value = args[3] as Long
        lastSetQueueStartPlaying = args[2] as Boolean
        lastSetQueuePreparePlayback = args[4] as Boolean
    }

    fun skipTo(
        index: Int,
        positionMs: Long,
    ) {
        coreQueue.skipTo(index)
        publishCoreQueue()
        currentPosition.value = positionMs
    }

    fun toggleShuffle() {
        coreQueue.toggleShuffle()
        shuffleEnabled.value = coreQueue.isShuffled
        publishCoreQueue()
    }

    private fun publishCoreQueue() {
        songQueue.value = coreQueue.items.value
        currentMediaItemIndex.value = coreQueue.currentIndex.value
        currentSong.value = coreQueue.currentSong()
        queueState.value = coreQueue.state.value
    }

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            else -> null
        }
}
