package com.metrolist.shared.playback

import androidx.compose.runtime.snapshotFlow
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrVideoStream
import com.metrolist.innertubex.sabr.findSabrProtocolException
import com.metrolist.shared.deviceconnect.DcLocalPlaybackState
import com.metrolist.shared.deviceconnect.DeviceConnectControllerClock
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_LIVE
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isLive
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.playback.PlaybackFailureClassifier
import com.metrolist.shared.playback.ffmpeg.AudioPlayer
import com.metrolist.shared.playback.ffmpeg.AudioPlayerFailureSource
import com.metrolist.shared.playback.ffmpeg.AudioRenderer
import com.metrolist.shared.playback.ffmpeg.DirectPlaybackCache
import com.metrolist.shared.playback.ffmpeg.Frame
import com.metrolist.shared.playback.ffmpeg.HttpRangeInputStream
import com.metrolist.shared.playback.ffmpeg.SabrInputStream
import com.metrolist.shared.playback.ffmpeg.directMediaHttpClient
import com.metrolist.shared.playback.ffmpeg.isAudioOutputUnavailable
import com.metrolist.shared.playback.playbackFailureCategoryFromSabr
import com.metrolist.shared.playback.sponsorblock.SponsorBlockClient
import com.metrolist.shared.playback.sponsorblock.SponsorBlockSegment
import com.metrolist.shared.playback.sponsorblock.canSkipPodcastSegments
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.youtube.extraction.models.toContentHints
import com.metrolist.shared.youtube.logging.MetrolistInnerTubeLogger
import com.metrolist.shared.youtube.playback.PlaybackErrorMessages
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.sabrAudioPlaybackChunks
import com.metrolist.shared.youtube.playback.toPlaybackLogDetails
import com.metrolist.shared.youtube.playback.withClientPlaybackNonce
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

@OptIn(ExperimentalSabrApi::class)
class MusicPlayer(
    private val playbackCacheCapture: (PlaybackResource) -> DirectPlaybackCache.Capture? = { null },
    private val mediaHttpClient: OkHttpClient = directMediaHttpClient,
    private val rendererFactory: (Int, Int) -> AudioRenderer = ::AudioRenderer,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val playbackReporters = linkedSetOf<YouTubePlaybackReporter>()
    private var playbackReporter =
        YouTubePlaybackReporter(scope, GlobalYouTubeRepository.instance).also(playbackReporters::add)

    @Volatile
    var isCrossfading = false
        set(value) {
            field = value
            sampleChanges.trySend(Unit)
        }

    private val _playbackStateChanges = MutableStateFlow(0L)
    val playbackStateChanges: StateFlow<Long> = _playbackStateChanges.asStateFlow()

    private val sampleChanges = Channel<Unit>(Channel.CONFLATED)
    private val playerLock = Any()
    private var earlyStreamFailureInstanceId = 0L
    private var progressUiVisible = false
    private var transitionSampling: Triple<Long, Long?, Long?>? = null
    private var crossfadeSampling: Pair<Long, Long?>? = null
    private var sponsorBlockSampling: Pair<String?, List<SponsorBlockSegment>> = null to emptyList()

    fun setProgressUiVisible(visible: Boolean) {
        synchronized(playerLock) {
            if (progressUiVisible == visible) return
            progressUiVisible = visible
            sampleChanges.trySend(Unit)
        }
    }

    fun setTransitionSampling(
        instanceId: Long,
        remainingOutputMs: Long?,
        preparationRemainingMs: Long? = null,
    ) {
        synchronized(playerLock) {
            val next = Triple(instanceId, remainingOutputMs, preparationRemainingMs)
            if (transitionSampling == next) return
            transitionSampling = next
            sampleChanges.trySend(Unit)
        }
    }

    fun setCrossfadeSampling(remainingOutputMs: Long?) {
        synchronized(playerLock) {
            crossfadeSampling = currentPlaybackInstanceId to remainingOutputMs
            sampleChanges.trySend(Unit)
        }
    }

    private var audioPlayer: AudioPlayer
    private var nextPlayer: AudioPlayer? = null
    private var primaryVideoId: String? = null
    private var nextVideoId: String? = null
    private var nextPlaybackResource: PlaybackResource? = null
        set(value) {
            if (field == value) return
            field = value
            _playbackStateChanges.update { it + 1L }
        }
    private var nextPlaybackReporter: YouTubePlaybackReporter? = null

    private var activeAudioIsLocal = false
    private var nextExpectedCurrentInstanceId = 0L
    private var nextExpectedCurrentVideoId: String? = null
    private var nextStartsPaused = false
    private var nextGeneration = 0L
    private var recoveryReportingFloor: RecoveryReportingFloor? = null
    private val playbackInstanceCounter = AtomicLong(0L)
    private val queuePlaybackRequestCounter = AtomicLong(0L)
    private val pendingTransitionCounter = AtomicLong(0L)
    private var currentPendingTransitionId = 0L

    @Volatile
    var currentPlaybackInstanceId = 0L
        private set(value) {
            field = value
            _playbackStateChanges.update { it + 1L }
        }

    private var nextPlaybackInstanceId = 0L
    private var currentVolume = 1f
    private var sleepTimerGain = 1f
    private var transitionGain = 1f
    private var nextTransitionGain = 0f

    @Volatile
    private var audioOutputEnabled = true

    var videoFrameListener: ((Any) -> Unit)? = null
        @Suppress("UNCHECKED_CAST")
        set(value) {
            field = value
            val listener = value as? ((Frame) -> Unit)
            audioPlayer.videoFrameListener = listener
            nextPlayer?.videoFrameListener = listener
        }

    var videoEnabled = false
        set(value) {
            field = value
            audioPlayer.videoEnabled = value
            nextPlayer?.videoEnabled = value
        }

    @Volatile
    private var reportedNextPlayerFailure = false

    val nextPlayerFailed: Boolean
        get() = synchronized(playerLock) { reportedNextPlayerFailure || nextPlayer == null || nextPlayer?.hasFailed == true }

    @Volatile private var controllerClock: DeviceConnectControllerClock? = null

    private val _currentSong = MutableStateFlow<SongItem?>(null)
    val currentSong: StateFlow<SongItem?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _isStalled = MutableStateFlow(false)
    val isStalled: StateFlow<Boolean> = _isStalled.asStateFlow()

    private var pendingPlaybackIntent: Boolean? = null

    val wantsPlayback: Boolean
        get() = controllerClock?.playback?.playing ?: synchronized(playerLock) { pendingPlaybackIntent ?: audioPlayer.wantsPlayback }

    fun setExternalReportingEnabled(enabled: Boolean) {
        playbackReporter.setReportingEnabled(enabled)
    }

    val playbackReady: Boolean
        get() = synchronized(playerLock) { currentPlaybackInstanceId != 0L && audioPlayer.isPrepared && !audioPlayer.hasFailed }

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1f)
    private val _playbackPitch = MutableStateFlow(1f)
    val playbackPitch: StateFlow<Float> = _playbackPitch.asStateFlow()
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition: StateFlow<Long> = _bufferedPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _onEOF = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onEOF: SharedFlow<Unit> = _onEOF.asSharedFlow()

    private val _onEOFPlaybackInstance = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val onEOFPlaybackInstance: SharedFlow<Long> = _onEOFPlaybackInstance.asSharedFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _loadFailure = MutableStateFlow<PlaybackFailure?>(null)
    val loadFailure: StateFlow<PlaybackFailure?> = _loadFailure.asStateFlow()

    private val _streamError = MutableSharedFlow<String>()
    val streamError: SharedFlow<String> = _streamError.asSharedFlow()

    private val _streamFailure = MutableSharedFlow<PlaybackFailure>()
    val streamFailure: SharedFlow<PlaybackFailure> = _streamFailure.asSharedFlow()

    private val _currentPlaybackResource = MutableStateFlow<PlaybackResource?>(null)
    val currentPlaybackResource: StateFlow<PlaybackResource?> = _currentPlaybackResource.asStateFlow()

    private val _queuePlaybackRequests =
        MutableSharedFlow<QueuePlaybackRequest>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val queuePlaybackRequests: SharedFlow<QueuePlaybackRequest> = _queuePlaybackRequests.asSharedFlow()
    val latestQueuePlaybackRequestId: Long
        get() = queuePlaybackRequestCounter.get()

    private val queue = MusicQueue()
    val songQueue: StateFlow<List<SongItem>> = queue.songs
    val currentMediaItemIndex: StateFlow<Int> = queue.currentIndex

    init {
        audioPlayer = createAudioPlayer()
        val settings = Settings.shared
        scope.launch {
            snapshotFlow { settings.audioNormalization to settings.loudnessLevel }
                .collectLatest { synchronized(playerLock) { applyPlaybackGains() } }
        }

        scope.launch {
            combine(currentSong, snapshotFlow { settings.sponsorBlockEnabled }) { song, enabled -> song to enabled }
                .collectLatest { (song, enabled) ->
                    val segments =
                        if (canSkipPodcastSegments(song, enabled)) {
                            SponsorBlockClient.shared.segments(song, enabled)
                        } else {
                            emptyList()
                        }
                    synchronized(playerLock) {
                        sponsorBlockSampling = song?.id to segments
                        sampleChanges.trySend(Unit)
                    }
                }
        }
        scope.launch {
            var lastPlaying = false
            var healthyPlaybackInstanceId = 0L
            val progressWatchdog = PlaybackProgressWatchdog()
            var stateChanged = true
            while (isActive) {
                currentSong.first { it != null }
                sampleChanges.tryReceive()
                val (expectedSampleInstanceId, needsSampling) =
                    synchronized(playerLock) {
                        currentPlaybackInstanceId to (audioPlayer.needsProgressSampling || nextPlayer?.needsProgressSampling == true)
                    }
                if (stateChanged && needsSampling) {
                    delay(50.milliseconds)
                }
                var healthyPlayback: Pair<PlaybackResource, SongItem>? = null
                var stalledPlayer: AudioPlayer? = null
                var stalledPlaybackInstanceId = 0L
                var sampledSong: SongItem? = null
                var sampledInstanceId = 0L
                var bufferedPosition = 0L
                var stalled = false
                val (playing, position, duration) =
                    synchronized(playerLock) {
                        if (currentPlaybackInstanceId != expectedSampleInstanceId) {
                            stateChanged = true
                            return@synchronized null
                        }
                        sampledSong = _currentSong.value
                        sampledInstanceId = currentPlaybackInstanceId
                        val preloadPlayer = nextPlayer
                        val preloadResource = nextPlaybackResource
                        val preloadReporter = nextPlaybackReporter
                        if (
                            preloadPlayer != null &&
                            preloadResource != null &&
                            preloadReporter != null &&
                            nextPlaybackInstanceId > 0L
                        ) {
                            preloadReporter.onPlaybackSample(
                                playbackInstanceId = nextPlaybackInstanceId,
                                resource = preloadResource,
                                positionMs = preloadPlayer.getCurrentPosition(),
                                isRendering = preloadPlayer.isPlaying(),
                                wantsPlayback = preloadPlayer.wantsPlayback,
                                playbackSpeed = _playbackSpeed.value,
                            )
                        }
                        if (controllerClock != null) {
                            Triple(_isPlaying.value, _currentPosition.value, _duration.value)
                        } else if (currentPlaybackInstanceId == 0L) {
                            Triple(false, _currentPosition.value, _duration.value)
                        } else {
                            val playbackPlayer = audioPlayer
                            val playing = playbackPlayer.isPlaying()
                            val position = playbackPlayer.getCurrentPosition()
                            if (playing && position >= 30_000L && healthyPlaybackInstanceId != currentPlaybackInstanceId) {
                                val resource = currentPlaybackResource.value
                                val song = _currentSong.value
                                if (resource != null && song != null && resource.videoId == song.id) {
                                    healthyPlaybackInstanceId = currentPlaybackInstanceId
                                    healthyPlayback = resource to song
                                }
                            }
                            reportingPositionForSample(currentPlaybackInstanceId, position)?.let { reportingPosition ->
                                playbackReporter.onPlaybackSample(
                                    playbackInstanceId = currentPlaybackInstanceId,
                                    resource = currentPlaybackResource.value,
                                    positionMs = reportingPosition,
                                    isRendering = playing,
                                    wantsPlayback = playbackPlayer.wantsPlayback,
                                    playbackSpeed = _playbackSpeed.value,
                                )
                            }
                            val duration = playbackPlayer.getDuration()
                            bufferedPosition = playbackPlayer.getBufferedPositionMs()
                            if (
                                progressWatchdog.observe(
                                    playbackInstanceId = currentPlaybackInstanceId,
                                    positionMs = position,
                                    durationMs = duration,
                                    expectProgress = playbackPlayer.needsProgressSampling,
                                    nowMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()),
                                )
                            ) {
                                stalledPlayer = playbackPlayer
                                stalledPlaybackInstanceId = currentPlaybackInstanceId
                            }
                            stalled = progressWatchdog.stalledForMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())) >= STALL_INDICATOR_MS
                            Triple(playing, position, duration)
                        }
                    } ?: continue
                healthyPlayback?.let { (resource, song) ->
                    scope.launch(Dispatchers.IO) {
                        GlobalYouTubeRepository.streamResolver?.reportPlaybackSuccess(
                            resource.profileId,
                            song.toContentHints().copy(wantVideo = resource.hasVideo),
                        )
                    }
                }
                stalledPlayer?.let { playbackPlayer ->
                    val resource = currentPlaybackResource.value
                    PlaybackEventLog.warn(
                        PlaybackEventKind.PLAYER,
                        "Playback position stopped advancing; requesting bounded recovery.",
                        videoId = resource?.videoId ?: _currentSong.value?.id,
                        clientName = resource?.clientName,
                        profileId = resource?.profileId,
                        details = mapOf("playbackInstanceId" to stalledPlaybackInstanceId.toString()),
                    )
                    playbackPlayer.reportPlaybackStall(PlaybackProgressTimeoutException())
                }
                requestBufferedStreamRecovery()?.let { _streamFailure.emit(it) }
                if (playing != lastPlaying) {
                    lastPlaying = playing
                    val resource = currentPlaybackResource.value
                    PlaybackEventLog.info(
                        PlaybackEventKind.PLAYER,
                        if (playing) "Audio is actually playing." else "Audio is not rendering (paused or stalled).",
                        videoId = resource?.videoId ?: _currentSong.value?.id,
                        clientName = resource?.clientName,
                        profileId = resource?.profileId,
                    )
                }
                synchronized(playerLock) {
                    if (sampledInstanceId == currentPlaybackInstanceId && controllerClock == null) {
                        _isPlaying.value = playing
                        _isStalled.value = stalled
                        _currentPosition.value = position
                        _bufferedPosition.value = boundedBufferedPosition(position, bufferedPosition, duration)
                        _duration.value = duration
                    }
                }
                synchronized(playerLock) {
                    if (duration > 0L && sampledInstanceId == currentPlaybackInstanceId) {
                        queue.updateCurrentDuration((duration + 999L) / 1000L, sampledSong)?.let { updatedSong ->
                            if (_currentSong.value?.id == updatedSong.id) _currentSong.value = updatedSong
                        }
                    }
                }
                val waitMs =
                    synchronized(playerLock) {
                        if (!audioPlayer.needsProgressSampling && nextPlayer?.needsProgressSampling != true) {
                            null
                        } else {
                            desktopPlaybackSampleDelayMs(
                                visible = progressUiVisible,
                                crossfading = isCrossfading,
                                starting = !playing,
                                positionMs = position,
                                durationMs = duration,
                                speed = _playbackSpeed.value,
                                transitionRemainingMs =
                                    listOfNotNull(
                                        transitionSampling?.takeIf { it.first == sampledInstanceId }?.second,
                                        crossfadeSampling?.takeIf { it.first == sampledInstanceId }?.second,
                                    ).maxOrNull(),
                                preparationRemainingMs = transitionSampling?.takeIf { it.first == sampledInstanceId }?.third,
                                segment =
                                    sponsorBlockSampling.takeIf { it.first == sampledSong?.id }?.second?.firstOrNull {
                                        it.endMs > position && (duration <= 0L || it.endMs <= duration)
                                    },
                            )
                        }
                    }
                stateChanged =
                    if (waitMs == null) {
                        sampleChanges.receive()
                        true
                    } else {
                        withTimeoutOrNull(waitMs) {
                            sampleChanges.receive()
                            true
                        } == true
                    }
            }
        }
    }

    fun play(
        song: SongItem,
        url: String,
    ) {
        play(song, url, expectedPendingTransitionId = null)
    }

    fun playPending(
        song: SongItem,
        url: String,
        pendingTransitionId: Long,
        startPaused: Boolean = false,
        shouldCommit: () -> Boolean = { true },
    ): Boolean = play(song, url, expectedPendingTransitionId = pendingTransitionId, startPaused, shouldCommit)

    private fun play(
        song: SongItem,
        url: String,
        expectedPendingTransitionId: Long?,
        startPaused: Boolean = false,
        shouldCommit: () -> Boolean = { true },
    ): Boolean {
        if (!shouldCommit()) return false
        cancelNext()
        val (playbackPlayer, playbackInstanceId) =
            replacePrimaryPlayer(song.id, resource = null, expectedPendingTransitionId, startPaused) ?: return false
        _loadError.value = null
        _loadFailure.value = null
        _currentSong.value = song
        val prepared = playbackPlayer.prepareFile(url, startPaused = true)
        return finishPreparingPlayback(playbackPlayer, playbackInstanceId, song.id, prepared, startPaused, shouldCommit)
    }

    fun play(
        song: SongItem,
        resource: PlaybackResource,
        startPaused: Boolean = false,
    ) {
        play(song, resource, expectedPendingTransitionId = null, startPaused = startPaused)
    }

    fun playPending(
        song: SongItem,
        resource: PlaybackResource,
        pendingTransitionId: Long,
        startPaused: Boolean = false,
        shouldCommit: () -> Boolean = { true },
    ): Boolean = play(song, resource, expectedPendingTransitionId = pendingTransitionId, startPaused, shouldCommit)

    private fun play(
        song: SongItem,
        resource: PlaybackResource,
        expectedPendingTransitionId: Long?,
        startPaused: Boolean = false,
        shouldCommit: () -> Boolean = { true },
    ): Boolean {
        if (!shouldCommit()) return false
        cancelNext()
        val (playbackPlayer, playbackInstanceId) =
            replacePrimaryPlayer(resource.videoId, resource, expectedPendingTransitionId, startPaused) ?: return false
        _loadError.value = null
        _loadFailure.value = null
        _currentPosition.value = 0L
        _bufferedPosition.value = 0L
        _duration.value = 0L
        val effectiveSong =
            if (resource.isLive && !song.isLive) {
                song.copy(musicVideoType = MUSIC_VIDEO_TYPE_LIVE)
            } else {
                song
            }
        _currentSong.value = effectiveSong
        PlaybackEventLog.info(
            PlaybackEventKind.PLAYER,
            "Preparing ${if (resource.isSabr) "SABR" else "direct"} playback via ${resource.clientName}/${resource.profileId}.",
            videoId = resource.videoId,
            clientName = resource.clientName,
            profileId = resource.profileId,
            details = song.toPlaybackLogDetails() + resource.toPlaybackLogDetails(),
        )
        val prepared = playbackPlayer.prepareResource(resource, startPaused = true)
        return finishPreparingPlayback(playbackPlayer, playbackInstanceId, resource.videoId, prepared, startPaused, shouldCommit)
    }

    private fun finishPreparingPlayback(
        playbackPlayer: AudioPlayer,
        playbackInstanceId: Long,
        videoId: String,
        prepared: Boolean,
        startPaused: Boolean,
        shouldCommit: () -> Boolean,
    ): Boolean {
        val accepted =
            synchronized(playerLock) {
                val canCommit = shouldCommit()
                if (audioPlayer !== playbackPlayer || currentPlaybackInstanceId != playbackInstanceId) {
                    false
                } else if (!canCommit) {
                    primaryVideoId = null
                    currentPlaybackInstanceId = 0L
                    recoveryReportingFloor = null
                    _currentPlaybackResource.value = null
                    false
                } else if (!prepared) {
                    reportPrepareFailure(videoId, playbackInstanceId, playbackPlayer.prepareFailure, resumePlayback = !startPaused)
                    false
                } else {
                    if (pendingPlaybackIntent == true) playbackPlayer.resume()
                    pendingPlaybackIntent = null
                    true
                }
            }
        if (!accepted) playbackPlayer.close()
        return accepted
    }

    fun shouldDeferSabrCache(
        videoId: String,
        completedInstanceId: Long,
    ): Boolean =
        synchronized(playerLock) {
            shouldDeferSabrCache(
                songId = videoId,
                current = _currentPlaybackResource.value,
                currentInstanceId = currentPlaybackInstanceId,
                completedInstanceId = completedInstanceId,
                preloaded = nextPlaybackResource?.let { it.isSabr && (it.videoId == videoId || it.musicVideoId == videoId) } == true,
            )
        }

    fun playPaused(
        song: SongItem,
        resource: PlaybackResource,
    ): Boolean = play(song, resource, expectedPendingTransitionId = null, startPaused = true)

    fun playRecovered(
        song: SongItem,
        resource: PlaybackResource,
        positionMs: Long,
        resumePlayback: Boolean,
        handoffPosition: ((Long) -> Long)? = null,
        shouldCommit: () -> Boolean = { true },
        onPrepareFailure: (Throwable?) -> Unit = {},
        recoveryPlaybackInstanceId: Long? = null,
        replaceCurrentQueueItem: Boolean = false,
        warmReplacement: Boolean = false,
    ): Boolean {
        val preparedResource =
            synchronized(playerLock) {
                if (recoveryPlaybackInstanceId == null) {
                    resource
                } else {
                    if (
                        currentPlaybackInstanceId != recoveryPlaybackInstanceId ||
                        primaryVideoId != resource.videoId
                    ) {
                        return false
                    }
                    currentPlaybackResource.value
                        ?.playbackTracking
                        ?.clientPlaybackNonce
                        ?.let(resource::withClientPlaybackNonce)
                        ?: resource
                }
            }
        val candidate =
            createAudioPlayer().also {
                it.setVolume(0f)
                it.setNormalizationGain(preparedResource.normalizationGain())
                it.videoEnabled = videoEnabled
                it.videoFrameListener = audioPlayer.videoFrameListener
            }
        PlaybackEventLog.info(
            PlaybackEventKind.PLAYER,
            "Preparing ${if (preparedResource.isSabr) "SABR" else "direct"} playback candidate via " +
                "${preparedResource.clientName}/${preparedResource.profileId}.",
            videoId = preparedResource.videoId,
            clientName = preparedResource.clientName,
            profileId = preparedResource.profileId,
            details = song.toPlaybackLogDetails() + preparedResource.toPlaybackLogDetails(),
        )
        if (!candidate.prepareResource(preparedResource, startPaused = true)) {
            onPrepareFailure(candidate.prepareFailure)
            PlaybackEventLog.debug(
                PlaybackEventKind.PLAYER,
                "Playback candidate preparation did not complete.",
                videoId = preparedResource.videoId,
                clientName = preparedResource.clientName,
                profileId = preparedResource.profileId,
                error = candidate.prepareFailure,
            )
            candidate.close()
            return false
        }
        var interruptedSource: AudioPlayer? = null

        fun resumeInterruptedSource() {
            if (warmReplacement) return
            val source = interruptedSource ?: return
            synchronized(playerLock) {
                if (audioPlayer === source && source.wantsPlayback) source.resume()
            }
        }
        var targetPositionMs =
            if (handoffPosition == null) {
                positionMs
            } else {
                val position =
                    synchronized(playerLock) {
                        if (
                            recoveryPlaybackInstanceId == null ||
                            currentPlaybackInstanceId != recoveryPlaybackInstanceId ||
                            primaryVideoId != preparedResource.videoId
                        ) {
                            null
                        } else {
                            audioPlayer.let { source ->
                                interruptedSource = source
                                if (!warmReplacement && source.wantsPlayback) source.pauseForHandoff()
                                handoffPosition(source.getCurrentPosition())
                            }
                        }
                    }
                if (position == null) {
                    candidate.close()
                    return false
                }
                position
            }.coerceAtLeast(0L)
        val warmingReplacement = warmReplacement && interruptedSource != null
        if (warmingReplacement) {
            val source = interruptedSource
            val ready =
                runBlocking {
                    withTimeoutOrNull(5_000L) {
                        var stalled = false

                        fun advancing() = source.wantsPlayback && !stalled
                        while (shouldCommit()) {
                            val playing = advancing()
                            val positionMs = source.getCurrentPosition()
                            val durationMs = source.getDuration()
                            val canSeekAhead = durationMs <= 0L || durationMs - positionMs >= 1_000L
                            targetPositionMs = positionMs + if (playing && canSeekAhead) 1_000L else 0L
                            if (!candidate.seekToAwait(targetPositionMs, RECOVERY_SEEK_TIMEOUT_MS) || !candidate.prebuffer()) {
                                onPrepareFailure(candidate.prepareFailure)
                                return@withTimeoutOrNull false
                            }
                            var lastPositionMs = source.getCurrentPosition()
                            var lastProgress = TimeSource.Monotonic.markNow()
                            while (shouldCommit() && playing == advancing() && lastPositionMs < targetPositionMs - 20L) {
                                delay(10L)
                                val positionMs = source.getCurrentPosition()
                                if (positionMs != lastPositionMs) {
                                    lastPositionMs = positionMs
                                    lastProgress = TimeSource.Monotonic.markNow()
                                } else if (lastProgress.elapsedNow() >= WARM_REPLACEMENT_STALL) {
                                    stalled = true
                                }
                            }
                            if (!shouldCommit()) return@withTimeoutOrNull false
                            if (playing == advancing() && abs(source.getCurrentPosition() - targetPositionMs) <= 100L) {
                                return@withTimeoutOrNull true
                            }
                        }
                        false
                    } == true
                }
            if (!ready) {
                candidate.close()
                return false
            }
        } else if (targetPositionMs > 0L && !candidate.seekToAwait(targetPositionMs, RECOVERY_SEEK_TIMEOUT_MS)) {
            onPrepareFailure(candidate.prepareFailure ?: IllegalStateException("Audio seek did not become ready"))
            candidate.close()
            resumeInterruptedSource()
            return false
        }
        if (!warmingReplacement && !candidate.prebuffer()) {
            onPrepareFailure(candidate.prepareFailure ?: IllegalStateException("Audio prebuffer did not become ready"))
            candidate.close()
            resumeInterruptedSource()
            return false
        }
        if (!shouldCommit()) {
            candidate.close()
            resumeInterruptedSource()
            return false
        }
        cancelNext()
        var committed = false
        var committedInstanceId = 0L
        var resumeCommittedPlayback = resumePlayback
        var stoppedPlayback: Pair<Long, Long>? = null
        val previous =
            synchronized(playerLock) {
                if (!shouldCommit()) return@synchronized null
                if (
                    recoveryPlaybackInstanceId != null &&
                    (
                        currentPlaybackInstanceId != recoveryPlaybackInstanceId ||
                            primaryVideoId != preparedResource.videoId
                    )
                ) {
                    return@synchronized null
                }
                val old = audioPlayer
                if (interruptedSource === old) resumeCommittedPlayback = old.wantsPlayback
                if (warmingReplacement) old.pauseForHandoff()
                if (recoveryPlaybackInstanceId == null) {
                    stoppedPlayback = currentPlaybackInstanceId.takeIf { it > 0L }?.let { it to old.getCurrentPosition() }
                }
                candidate.setAudioOutputEnabled(audioOutputEnabled)
                audioPlayer = candidate
                _currentPlaybackResource.value = preparedResource
                activeAudioIsLocal = preparedResource.clientName == "local"
                applyPlaybackGains()
                val nextPlaybackInstanceId = playbackInstanceCounter.incrementAndGet()
                currentPlaybackInstanceId = nextPlaybackInstanceId
                committedInstanceId = nextPlaybackInstanceId
                primaryVideoId = preparedResource.videoId
                currentPendingTransitionId = 0L
                if (replaceCurrentQueueItem) queue.replaceCurrent(song)
                if (resumeCommittedPlayback && interruptedSource != null) candidate.resume()
                if (recoveryPlaybackInstanceId != null) {
                    recoveryReportingFloor =
                        RecoveryReportingFloor(
                            playbackInstanceId = nextPlaybackInstanceId,
                            resumePositionMs = targetPositionMs,
                        )
                    playbackReporter.onPlaybackRecovered(
                        previousPlaybackInstanceId = recoveryPlaybackInstanceId,
                        playbackInstanceId = nextPlaybackInstanceId,
                        positionMs = targetPositionMs,
                        format = preparedResource.itag,
                    )
                } else {
                    recoveryReportingFloor = null
                }
                committed = true
                old
            }
        if (!committed) {
            candidate.close()
            resumeInterruptedSource()
            return false
        }
        previous?.closeAsync()
        stoppedPlayback?.let { (instanceId, stoppedPositionMs) ->
            playbackReporter.onPlaybackStopped(instanceId, stoppedPositionMs)
        }
        return synchronized(playerLock) {
            if (audioPlayer !== candidate || currentPlaybackInstanceId != committedInstanceId) return@synchronized false
            _loadError.value = null
            _loadFailure.value = null
            _currentPosition.value = targetPositionMs
            _duration.value = candidate.getDuration()
            _currentSong.value = song
            if (resumeCommittedPlayback && interruptedSource == null) candidate.resume()
            true
        }
    }

    suspend fun prepareAndPlay(
        song: SongItem,
        resource: PlaybackResource,
        positionMs: Long = 0L,
        resumePlayback: Boolean = true,
        shouldCommit: () -> Boolean = { true },
        onPrepareFailure: (Throwable?) -> Unit = {},
        recoveryPlaybackInstanceId: Long? = null,
        replaceCurrentQueueItem: Boolean = false,
    ): Boolean =
        withContext(Dispatchers.IO) {
            playRecovered(
                song = song,
                resource = resource,
                positionMs = positionMs,
                resumePlayback = resumePlayback,
                shouldCommit = shouldCommit,
                onPrepareFailure = onPrepareFailure,
                recoveryPlaybackInstanceId = recoveryPlaybackInstanceId,
                replaceCurrentQueueItem = replaceCurrentQueueItem,
            )
        }

    fun setControllerPlayback(playback: DcLocalPlaybackState?) {
        if (playback == null) {
            synchronized(playerLock) {
                if (controllerClock == null) return
                _currentPosition.value = currentPlaybackPositionMs()
                controllerClock = null
                _isPlaying.value = false
            }
            return
        }
        if (controllerClock == null) stop()
        synchronized(playerLock) {
            controllerClock = DeviceConnectControllerClock(playback)
            _currentSong.value = playback.queue.displayedItems.getOrNull(playback.queue.currentIndex)
            _currentPosition.value = playback.positionMs
            _duration.value = playback.durationMs
            _bufferedPosition.value = playback.positionMs
            _playbackSpeed.value = playback.speed
            playback.pitch?.let { _playbackPitch.value = it }
            _isPlaying.value = playback.playing && playback.ready
        }
    }

    fun pause() {
        PlaybackEventLog.info(
            PlaybackEventKind.PLAYER,
            "Pause requested.",
            videoId = _currentSong.value?.id,
            clientName = currentPlaybackResource.value?.clientName,
            profileId = currentPlaybackResource.value?.profileId,
        )
        synchronized(playerLock) {
            if (pendingPlaybackIntent != null) pendingPlaybackIntent = false
            audioPlayer.pause()
            if (isCrossfading) nextPlayer?.pause()
        }
    }

    fun resume() {
        PlaybackEventLog.info(
            PlaybackEventKind.PLAYER,
            "Resume requested.",
            videoId = _currentSong.value?.id,
            clientName = currentPlaybackResource.value?.clientName,
            profileId = currentPlaybackResource.value?.profileId,
        )
        synchronized(playerLock) {
            if (pendingPlaybackIntent != null) pendingPlaybackIntent = true
            audioPlayer.resume()
            if (isCrossfading && !nextStartsPaused) nextPlayer?.resume()
        }
    }

    fun stop() {
        stopPlayback(replacementSong = null)
    }

    fun beginPendingPlayback(song: SongItem): Long = stopPlayback(replacementSong = song)

    private fun stopPlayback(replacementSong: SongItem?): Long {
        controllerClock = null
        cancelNext()
        var stoppedPlayback: Pair<Long, Long>? = null
        var pendingTransitionId = 0L
        synchronized(playerLock) {
            pendingTransitionId = pendingTransitionCounter.incrementAndGet()
            currentPendingTransitionId = pendingTransitionId
            stoppedPlayback = currentPlaybackInstanceId.takeIf { it > 0L }?.let { it to audioPlayer.getCurrentPosition() }
            primaryVideoId = null
            currentPlaybackInstanceId = 0L
            recoveryReportingFloor = null
            _currentPlaybackResource.value = null
            activeAudioIsLocal = false
            pendingPlaybackIntent = if (replacementSong != null) pendingPlaybackIntent ?: audioPlayer.wantsPlayback else null
            audioPlayer.closeAsync()
            _loadError.value = null
            _loadFailure.value = null
            _currentPosition.value = 0L
            _bufferedPosition.value = 0L
            _duration.value = replacementSong?.duration?.times(1000L) ?: 0L
            _isPlaying.value = false
            _currentSong.value = replacementSong
        }
        stoppedPlayback?.let { (instanceId, positionMs) ->
            playbackReporter.onPlaybackStopped(instanceId, positionMs)
        }
        return pendingTransitionId
    }

    fun seekTo(position: Long) {
        if (_currentSong.value?.isLive == true) return
        PlaybackEventLog.info(
            PlaybackEventKind.PLAYER,
            "Seek requested to ${position}ms.",
            videoId = _currentSong.value?.id,
            clientName = currentPlaybackResource.value?.clientName,
            profileId = currentPlaybackResource.value?.profileId,
        )
        val (playbackPlayer, playbackInstanceId) =
            synchronized(playerLock) {
                recoveryReportingFloor = null
                audioPlayer to currentPlaybackInstanceId
            }
        playbackReporter.onSeek(
            playbackInstanceId = playbackInstanceId,
            fromPositionMs = playbackPlayer.getCurrentPosition(),
            toPositionMs = position,
            continuePlaying = playbackPlayer.wantsPlayback,
        )
        playbackPlayer.seekTo(position)
    }

    fun finishInterruptedPlayback(
        playbackInstanceId: Long,
        positionMs: Long,
    ) {
        synchronized(playerLock) {
            if (currentPlaybackInstanceId == playbackInstanceId) {
                playbackReporter.onPlaybackStopped(playbackInstanceId, positionMs)
            }
        }
    }

    fun setVolume(level: Int) {
        synchronized(playerLock) {
            currentVolume = (level / 100f).coerceIn(0f, 1f)
            _volume.value = currentVolume
            applyPlaybackGains()
        }
    }

    fun setTransitionVolume(level: Float) {
        synchronized(playerLock) {
            transitionGain = (level / 100f).coerceIn(0f, 1f)
            applyPlaybackGains()
        }
    }

    fun setSleepTimerGain(gain: Float) {
        synchronized(playerLock) {
            sleepTimerGain = gain.coerceIn(0f, 1f)
            applyPlaybackGains()
        }
    }

    val outputVolume: Float
        get() = synchronized(playerLock) { outputGain(_currentPlaybackResource.value, transitionGain) }

    private fun outputGain(
        resource: PlaybackResource?,
        transition: Float,
    ): Float =
        playbackOutputGain(
            currentVolume,
            sleepTimerGain,
            transition,
            resource.normalizationGain().coerceAtMost(1f),
        )

    private fun PlaybackResource?.normalizationGain(): Float =
        playbackNormalizationGain(
            this?.loudnessDb,
            Settings.shared.audioNormalization,
            Settings.shared.loudnessLevel,
            this?.perceptualLoudnessDb,
        )

    private fun applyPlaybackGains() {
        audioPlayer.setNormalizationGain(_currentPlaybackResource.value.normalizationGain())
        nextPlayer?.setNormalizationGain(nextPlaybackResource.normalizationGain())
        audioPlayer.setVolume(outputGain(_currentPlaybackResource.value, transitionGain))
        nextPlayer?.setVolume(outputGain(nextPlaybackResource, nextTransitionGain))
    }

    fun setAudioOutputEnabled(enabled: Boolean) {
        synchronized(playerLock) {
            if (audioOutputEnabled == enabled) return
            audioOutputEnabled = enabled
            audioPlayer.setAudioOutputEnabled(enabled)
            nextPlayer?.setAudioOutputEnabled(enabled)
        }
    }

    fun playbackSpeed(): Float = _playbackSpeed.value

    fun setPlaybackPitch(pitch: Float) {
        require(pitch.isFinite() && pitch in 0.5f..2f)
        synchronized(playerLock) {
            audioPlayer.setPlaybackPitch(pitch)
            nextPlayer?.setPlaybackPitch(pitch)
            _playbackPitch.value = pitch
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        require(speed.isFinite() && speed in 0.5f..2f)
        synchronized(playerLock) {
            audioPlayer.setPlaybackSpeed(speed)
            nextPlayer?.setPlaybackSpeed(speed)
            _playbackSpeed.value = speed
            sampleChanges.trySend(Unit)
        }
    }

    fun setQueue(
        songs: List<SongItem>,
        startIndex: Int = 0,
        startPlaying: Boolean = false,
    ) {
        synchronized(playerLock) {
            cancelNext()
            queue.setQueue(songs, startIndex)
            if (startPlaying) queue.currentSong()?.let(::requestQueuePlayback)
        }
    }

    fun addToQueue(
        song: SongItem,
        playNext: Boolean = false,
    ) {
        synchronized(playerLock) {
            cancelNext()
            if (playNext) queue.playNext(song) else queue.addToQueue(song)
        }
    }

    fun removeQueueItem(index: Int) {
        synchronized(playerLock) {
            cancelNext()
            val removal = queue.removeAt(setOf(index)) ?: return
            val current = queue.currentSong()
            if (current == null) {
                if (removal.items.any { it.index == removal.previousCurrentIndex }) stop()
                return
            }

            val pending = _queuePlaybackRequests.replayCache.lastOrNull()
            val requestAlreadyMatches = pending?.song === current
            if (_currentSong.value !== current && !requestAlreadyMatches) {
                requestQueuePlayback(current)
            }
        }
    }

    fun moveQueueItem(
        from: Int,
        to: Int,
    ) {
        synchronized(playerLock) {
            cancelNext()
            queue.move(from, to)
        }
    }

    fun replaceUpcomingQueue(songs: List<SongItem>) {
        synchronized(playerLock) {
            cancelNext()
            queue.replaceUpcomingQueue(songs)
        }
    }

    fun skipToNext() {
        synchronized(playerLock) {
            queue.next()?.let(::requestQueuePlayback)
        }
    }

    fun skipToPrevious() {
        synchronized(playerLock) {
            queue.previous()?.let(::requestQueuePlayback)
        }
    }

    fun skipToQueueItem(index: Int) {
        synchronized(playerLock) {
            queue.skipTo(index)?.let(::requestQueuePlayback)
        }
    }

    fun toggleShuffle(): Boolean {
        synchronized(playerLock) {
            cancelNext()
            queue.toggleShuffle()
            return queue.isShuffled
        }
    }

    fun restoreShuffleMode(enabled: Boolean) {
        synchronized(playerLock) {
            queue.restoreShuffleMode(enabled)
        }
    }

    val isShuffleEnabled: Boolean
        get() = queue.isShuffled

    fun retryPendingQueuePlayback(): Boolean {
        synchronized(playerLock) {
            if (!shouldRetryPlayback(playbackReady, _loadFailure.value != null)) return false
            val pending = _currentSong.value ?: return false
            val current = queue.currentSong()?.takeIf { it === pending } ?: return false
            requestQueuePlayback(current)
            return true
        }
    }

    private fun requestQueuePlayback(song: SongItem) {
        val request =
            QueuePlaybackRequest(
                requestId = queuePlaybackRequestCounter.incrementAndGet(),
                song = song,
            )
        beginPendingPlayback(song)
        _queuePlaybackRequests.tryEmit(
            request,
        )
    }

    fun setPreferredDevice(device: Any?) {
        audioPlayer.setPreferredDevice(device)
        nextPlayer?.setPreferredDevice(device)
    }

    fun getAudioSessionId(): Int = audioPlayer.getAudioSessionId()

    fun release() {
        var releasedPlayer: AudioPlayer? = null
        val playback =
            synchronized(playerLock) {
                val stopped = currentPlaybackInstanceId.takeIf { it > 0L }?.let { it to audioPlayer.getCurrentPosition() }
                primaryVideoId = null
                currentPlaybackInstanceId = 0L
                recoveryReportingFloor = null
                _currentPlaybackResource.value = null
                activeAudioIsLocal = false
                releasedPlayer = audioPlayer
                stopped
            }
        releasedPlayer?.closeAsync()
        playback?.let { (instanceId, positionMs) -> playbackReporter.onPlaybackStopped(instanceId, positionMs) }
        cancelNext()
        val reporters = synchronized(playerLock) { playbackReporters.toList() }
        runBlocking {
            withTimeoutOrNull(RELEASE_STATS_TIMEOUT_MS.milliseconds) {
                reporters.forEach { it.awaitPendingPings() }
            }
        }
        scope.cancel()
    }

    fun prepareNextTrack(
        resource: PlaybackResource,
        expectedCurrentPlaybackInstanceId: Long = currentPlaybackInstanceId,
        expectedCurrentVideoId: String? = primaryVideoId,
        startPaused: Boolean = false,
        shouldCommit: () -> Boolean = { true },
    ): PlaybackPreloadToken? {
        val preloadPlayer =
            createAudioPlayer().also {
                it.videoEnabled = videoEnabled
                it.videoFrameListener = videoFrameListener
            }
        val preloadReporter = YouTubePlaybackReporter(scope, GlobalYouTubeRepository.instance)
        var previousPlayer: AudioPlayer? = null
        var previousReporter: YouTubePlaybackReporter? = null
        var previousPlaybackInstanceId = 0L
        val generation =
            synchronized(playerLock) {
                if (!shouldCommit() || currentPlaybackInstanceId != expectedCurrentPlaybackInstanceId ||
                    primaryVideoId != expectedCurrentVideoId
                ) {
                    return null
                }
                nextGeneration++
                previousPlayer = nextPlayer
                previousReporter = nextPlaybackReporter
                previousPlaybackInstanceId = nextPlaybackInstanceId

                preloadPlayer.setAudioOutputEnabled(audioOutputEnabled)
                nextPlayer = preloadPlayer
                nextVideoId = resource.videoId
                nextPlaybackResource = resource
                nextTransitionGain = 0f
                applyPlaybackGains()
                nextPlaybackReporter = preloadReporter
                playbackReporters += preloadReporter
                nextPlaybackInstanceId = playbackInstanceCounter.incrementAndGet()
                nextExpectedCurrentInstanceId = expectedCurrentPlaybackInstanceId
                nextExpectedCurrentVideoId = expectedCurrentVideoId
                nextStartsPaused = startPaused
                reportedNextPlayerFailure = false
                nextGeneration
            }
        previousReporter?.let { reporter ->
            reporter.onPlaybackStopped(previousPlaybackInstanceId, previousPlayer?.getCurrentPosition() ?: 0L)
            retirePlaybackReporter(reporter)
        }
        previousPlayer?.close()
        return try {
            val prepared = preloadPlayer.prepareResource(resource, startPaused = true)
            if (!prepared) {
                discardPreparedNext(preloadPlayer, generation)
                return null
            }
            if (!preloadPlayer.prebuffer()) {
                discardPreparedNext(preloadPlayer, generation)
                return null
            }
            val token =
                synchronized(playerLock) {
                    if (
                        !shouldCommit() || nextPlayer !== preloadPlayer || nextGeneration != generation ||
                        currentPlaybackInstanceId != expectedCurrentPlaybackInstanceId ||
                        primaryVideoId != expectedCurrentVideoId
                    ) {
                        null
                    } else {
                        if (!startPaused && audioPlayer.wantsPlayback) preloadPlayer.resume()
                        PlaybackPreloadToken(generation, nextPlaybackInstanceId, resource.videoId)
                    }
                }
            if (token == null) discardPreparedNext(preloadPlayer, generation)
            token
        } catch (e: Exception) {
            PlaybackEventLog.warn(
                PlaybackEventKind.PLAYER,
                "Next-track preparation failed.",
                videoId = resource.videoId,
                clientName = resource.clientName,
                profileId = resource.profileId,
                details = mapOf("failureType" to (e::class.simpleName ?: "unknown")),
            )
            discardPreparedNext(preloadPlayer, generation)
            null
        }
    }

    private fun discardPreparedNext(
        player: AudioPlayer,
        generation: Long,
    ) {
        var reporter: YouTubePlaybackReporter? = null
        var playbackInstanceId = 0L
        synchronized(playerLock) {
            if (nextPlayer === player && nextGeneration == generation) {
                reporter = nextPlaybackReporter
                playbackInstanceId = nextPlaybackInstanceId
                nextGeneration++
                nextPlayer = null
                nextVideoId = null
                nextPlaybackResource = null
                nextPlaybackReporter = null
                nextExpectedCurrentInstanceId = 0L
                nextExpectedCurrentVideoId = null
                nextStartsPaused = false
                nextPlaybackInstanceId = 0L
                reportedNextPlayerFailure = false
            }
        }
        reporter?.let {
            it.onPlaybackStopped(playbackInstanceId, player.getCurrentPosition())
            retirePlaybackReporter(it)
        }
        player.close()
    }

    fun setNextVolume(level: Float) {
        synchronized(playerLock) {
            nextTransitionGain = (level / 100f).coerceIn(0f, 1f)
            applyPlaybackGains()
        }
    }

    val nextPlayerPosition: Long
        get() = synchronized(playerLock) { nextPlayer?.getCurrentPosition() ?: 0L }

    fun isPreloadedNextActive(token: PlaybackPreloadToken): Boolean =
        synchronized(playerLock) {
            val next = nextPlayer ?: return false
            !next.hasFailed && token.generation == nextGeneration && token.playerInstanceId == nextPlaybackInstanceId &&
                currentPlaybackInstanceId == nextExpectedCurrentInstanceId && primaryVideoId == nextExpectedCurrentVideoId
        }

    fun swapToNext(
        token: PlaybackPreloadToken,
        resource: PlaybackResource? = null,
        initialTransitionGain: Float = 1f,
        startPlaying: Boolean? = null,
        beforeResume: () -> Unit = {},
    ): Boolean {
        var stoppedPlayback: Pair<Long, Long>? = null
        var previousPlayer: AudioPlayer? = null
        var previousReporter: YouTubePlaybackReporter? = null
        var resumePromotedPlayer = false
        synchronized(playerLock) {
            val next = nextPlayer ?: return false
            if (next.hasFailed) return false
            if (
                token.generation != nextGeneration || token.playerInstanceId != nextPlaybackInstanceId ||
                currentPlaybackInstanceId != nextExpectedCurrentInstanceId ||
                primaryVideoId != nextExpectedCurrentVideoId || token.videoId != nextVideoId
            ) {
                return false
            }
            val activeResource = resource ?: nextPlaybackResource ?: return false
            val promotedReporter = nextPlaybackReporter ?: return false
            stoppedPlayback = currentPlaybackInstanceId.takeIf { it > 0L }?.let { it to audioPlayer.getCurrentPosition() }
            previousPlayer = audioPlayer
            previousReporter = playbackReporter

            next.setAudioOutputEnabled(audioOutputEnabled)
            audioPlayer = next
            playbackReporter = promotedReporter
            resumePromotedPlayer = startPlaying ?: nextStartsPaused
            if (startPlaying == false) next.pause()
            _currentPlaybackResource.value = activeResource
            activeAudioIsLocal = nextPlaybackResource?.clientName == "local"
            nextPlayer = null
            primaryVideoId = nextVideoId
            nextVideoId = null
            nextPlaybackResource = null
            nextPlaybackReporter = null
            nextExpectedCurrentInstanceId = 0L
            nextExpectedCurrentVideoId = null
            nextStartsPaused = false
            currentPlaybackInstanceId = nextPlaybackInstanceId
            recoveryReportingFloor = null
            nextPlaybackInstanceId = 0L
            reportedNextPlayerFailure = false
            transitionGain = initialTransitionGain.coerceIn(0f, 1f)
            nextTransitionGain = 0f
            applyPlaybackGains()
            beforeResume()
            _currentPosition.value = next.getCurrentPosition()
            _bufferedPosition.value = next.getBufferedPositionMs()
            _duration.value = next.getDuration()
            previousPlayer.closeAsync()
            if (resumePromotedPlayer) next.resume()
            _isPlaying.value = resumePromotedPlayer || next.isPlaying()
        }
        isCrossfading = initialTransitionGain < 1f
        stoppedPlayback?.let { (instanceId, positionMs) ->
            previousReporter?.let { reporter ->
                reporter.onPlaybackStopped(instanceId, positionMs)
                retirePlaybackReporter(reporter)
            }
        }
        return true
    }

    fun swapToPreloadedQueueNext(
        token: PlaybackPreloadToken,
        resource: PlaybackResource,
    ): Boolean {
        val nextSong = queue.peekNext()?.takeIf { it.id == token.videoId } ?: return false
        return swapToNext(
            token,
            resource,
            beforeResume = {
                queue.next()
                _currentSong.value = nextSong
                _loadError.value = null
                _loadFailure.value = null
            },
        )
    }

    fun cancelNext() {
        var cancelledReporter: YouTubePlaybackReporter? = null
        var cancelledPlaybackInstanceId = 0L
        val cancelledPlayer =
            synchronized(playerLock) {
                val player = nextPlayer
                cancelledReporter = nextPlaybackReporter
                cancelledPlaybackInstanceId = nextPlaybackInstanceId
                nextGeneration++
                nextPlayer = null
                nextVideoId = null
                nextPlaybackResource = null
                nextPlaybackReporter = null
                nextExpectedCurrentInstanceId = 0L
                nextExpectedCurrentVideoId = null
                nextStartsPaused = false
                nextPlaybackInstanceId = 0L
                reportedNextPlayerFailure = false
                transitionGain = 1f
                nextTransitionGain = 0f
                applyPlaybackGains()
                player
            }
        if (cancelledPlayer != null) {
            cancelledReporter?.let {
                it.onPlaybackStopped(cancelledPlaybackInstanceId, cancelledPlayer.getCurrentPosition())
                retirePlaybackReporter(it)
            }
            cancelledPlayer.closeAsync()
        }
    }

    private fun retirePlaybackReporter(reporter: YouTubePlaybackReporter) {
        scope.launch {
            reporter.awaitPendingPings()
            synchronized(playerLock) { playbackReporters -= reporter }
        }
    }

    fun setCurrentSong(
        song: SongItem,
        resource: PlaybackResource? = null,
    ) {
        synchronized(playerLock) {
            _currentSong.value = song
            if (resource != null) {
                _currentPlaybackResource.value = resource
                applyPlaybackGains()
            }
        }
    }

    fun attachPlaybackResource(resource: PlaybackResource) {
        synchronized(playerLock) {
            if (resource.videoId != primaryVideoId) return
            _currentPlaybackResource.value = resource
            applyPlaybackGains()
        }
    }

    fun invalidateActiveStreamForNetworkChange(forceRecovery: Boolean = false) {
        val failure =
            synchronized(playerLock) {
                val song = _currentSong.value ?: return
                val resource = _currentPlaybackResource.value ?: return
                if (resource.videoId != song.id || activeAudioIsLocal) return
                // Route snapshots can change while the active connection still carries audio.
                if (!forceRecovery && !audioPlayer.hasFailed) return
                PlaybackFailure(
                    stage = "network_change",
                    message = "Playback network changed",
                    cause = PlaybackNetworkChangedException(),
                    videoId = song.id,
                    playbackInstanceId = currentPlaybackInstanceId,
                    playbackPositionMs = audioPlayer.getCurrentPosition(),
                    resumePlayback = audioPlayer.wantsPlayback,
                    shouldExcludePlaybackProfile = false,
                    category = PlaybackFailureCategory.NETWORK,
                )
            }
        scope.launch { _streamFailure.emit(failure) }
    }

    suspend fun refreshPlaybackResource(wantVideo: Boolean): Boolean {
        val snapshot =
            synchronized(playerLock) {
                val song = _currentSong.value ?: return false
                val resource = _currentPlaybackResource.value ?: return false
                Triple(song, resource, currentPlaybackInstanceId)
            }
        val (song, resource, playbackInstanceId) = snapshot
        val resolver = GlobalYouTubeRepository.streamResolver ?: return false

        return try {
            val hints = song.toContentHints().copy(wantVideo = wantVideo)
            val refreshed = resolver.refresh(resource.videoId, hints, sourceSong = song)
            synchronized(playerLock) {
                if (
                    currentPlaybackInstanceId != playbackInstanceId ||
                    _currentSong.value?.id != song.id ||
                    _currentPlaybackResource.value !== resource
                ) {
                    return false
                }
                _currentPlaybackResource.value = refreshed
                applyPlaybackGains()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun currentPlaybackPositionMs(): Long =
        synchronized(playerLock) {
            controllerClock?.positionMs()
                ?: if (currentPlaybackInstanceId == 0L) _currentPosition.value else audioPlayer.getCurrentPosition()
        }

    fun currentAudioPtsMs(): Long? =
        synchronized(playerLock) {
            audioPlayer.currentAudioPtsMs()
        }

    private fun reportPrepareFailure(
        videoId: String,
        playbackInstanceId: Long,
        cause: Throwable?,
        resumePlayback: Boolean,
    ) {
        val failure =
            PlaybackFailure(
                stage = "audio_prepare",
                message = "Unable to prepare audio",
                cause = cause,
                videoId = videoId,
                playbackInstanceId = playbackInstanceId,
                resumePlayback = resumePlayback,
                isReportable = cause?.isAudioOutputUnavailable() != true,
                isMediaSourceFailure = cause?.isAudioOutputUnavailable() != true,
                shouldExcludePlaybackProfile = cause?.isMediaTransportFailure() == true,
                category =
                    if (cause?.isAudioOutputUnavailable() == true) {
                        PlaybackFailureCategory.RENDERER
                    } else {
                        PlaybackFailureClassifier.fromThrowable(cause)
                    },
            )
        val (resource, logDetails) =
            synchronized(playerLock) {
                if (currentPlaybackInstanceId != playbackInstanceId) return
                val resource = currentPlaybackResource.value
                if (
                    resource != null && !resource.isSabr &&
                    !resource.url.startsWith("http://", ignoreCase = true) &&
                    !resource.url.startsWith("https://", ignoreCase = true)
                ) {
                    _loadError.value = failure.message
                    _loadFailure.value = failure
                }
                resource to playerLogDetails()
            }
        scope.launch {
            if (synchronized(playerLock) { currentPlaybackInstanceId != playbackInstanceId }) return@launch
            _streamFailure.emit(failure)
            PlaybackEventLog.error(
                PlaybackEventKind.PLAYER,
                "Unable to prepare audio for $videoId. ${PlaybackErrorMessages.describe(cause)}",
                videoId = videoId,
                clientName = resource?.clientName,
                profileId = resource?.profileId,
                details = logDetails,
                error = cause,
            )
        }
    }

    private fun playerLogDetails(extra: Map<String, String> = emptyMap()): Map<String, String> =
        buildMap {
            putAll(extra)
            _currentSong.value?.let { putAll(it.toPlaybackLogDetails()) }
            currentPlaybackResource.value?.let { putAll(it.toPlaybackLogDetails()) }
            put("positionMs", audioPlayer.getCurrentPosition().toString())
            put("durationMs", audioPlayer.getDuration().toString())
            put("isPlaying", audioPlayer.isPlaying().toString())
        }

    private fun requestBufferedStreamRecovery(): PlaybackFailure? =
        synchronized(playerLock) {
            if (controllerClock != null || isCrossfading || currentPlaybackInstanceId == 0L ||
                earlyStreamFailureInstanceId == currentPlaybackInstanceId || _streamFailure.subscriptionCount.value == 0 ||
                audioPlayer.hasFailed ||
                currentPlaybackResource.value?.isSabr != true
            ) {
                return null
            }
            val error = audioPlayer.streamFailure ?: return null
            earlyStreamFailureInstanceId = currentPlaybackInstanceId
            PlaybackFailure(
                stage = "audio_stream",
                message = "SABR producer failed while audio remains buffered",
                cause = error,
                sourceName = AudioPlayerFailureSource.STREAM.name,
                videoId = primaryVideoId,
                playbackInstanceId = currentPlaybackInstanceId,
                playbackPositionMs = audioPlayer.getCurrentPosition(),
                resumePlayback = audioPlayer.wantsPlayback,
                sabrFailureKind = error.findSabrProtocolException()?.kind,
                shouldExcludePlaybackProfile = true,
            )
        }

    private fun Throwable.isMediaTransportFailure(): Boolean {
        if (findSabrProtocolException() != null) return true
        val visited = mutableSetOf<Throwable>()
        var current: Throwable? = this
        while (current != null && visited.add(current)) {
            if (current is IOException) return true
            current = current.cause
        }
        return false
    }

    private fun createAudioPlayer(): AudioPlayer =
        AudioPlayer(
            rendererFactory = rendererFactory,
            onStateChanged = {
                sampleChanges.trySend(Unit)
                _playbackStateChanges.update { it + 1L }
            },
            onPlaybackCompleted = { completedPlayer ->
                scope.launch {
                    val completed =
                        synchronized(playerLock) {
                            when (completedPlayer) {
                                audioPlayer -> {
                                    currentPlaybackInstanceId
                                        .takeIf { it > 0L && primaryVideoId != null }
                                        ?.let { Triple(it, playbackReporter, true) }
                                }

                                nextPlayer -> {
                                    reportedNextPlayerFailure = true
                                    _playbackStateChanges.update { it + 1L }
                                    nextPlaybackReporter?.let { Triple(nextPlaybackInstanceId, it, false) }
                                }

                                else -> {
                                    null
                                }
                            }
                        }
                    if (completed != null) {
                        completed.second.onPlaybackEnded(
                            playbackInstanceId = completed.first,
                            positionMs = completedPlayer.getCurrentPosition(),
                        )
                        if (completed.third) {
                            _onEOFPlaybackInstance.emit(completed.first)
                            _onEOF.emit(Unit)
                        } else {
                            retirePlaybackReporter(completed.second)
                        }
                    }
                }
            },
            onPlaybackFailed = { failedPlayer, error ->
                scope.launch {
                    val failedPositionMs = failedPlayer.getCurrentPosition()
                    val resumePlayback = failedPlayer.shouldResumeAfterFailure
                    var failedReporter: YouTubePlaybackReporter? = null
                    var failedPlaybackInstanceId = 0L
                    var failedNextPlayer = false
                    val failedPlayback: Pair<String, Long>? =
                        synchronized(playerLock) {
                            when (failedPlayer) {
                                audioPlayer -> {
                                    failedReporter = playbackReporter
                                    failedPlaybackInstanceId = currentPlaybackInstanceId
                                    primaryVideoId
                                        ?.takeUnless {
                                            earlyStreamFailureInstanceId == currentPlaybackInstanceId &&
                                                failedPlayer.failureSource == AudioPlayerFailureSource.STREAM
                                        }?.let { it to currentPlaybackInstanceId }
                                }

                                nextPlayer -> {
                                    failedReporter = nextPlaybackReporter
                                    failedPlaybackInstanceId = nextPlaybackInstanceId
                                    failedNextPlayer = true
                                    nextPlayer = null
                                    nextVideoId = null
                                    nextPlaybackResource = null
                                    nextPlaybackReporter = null
                                    nextExpectedCurrentInstanceId = 0L
                                    nextExpectedCurrentVideoId = null
                                    nextStartsPaused = false
                                    nextPlaybackInstanceId = 0L
                                    reportedNextPlayerFailure = true
                                    _playbackStateChanges.update { it + 1L }
                                    null
                                }

                                else -> {
                                    null
                                }
                            }
                        }
                    if (failedNextPlayer) failedPlayer.closeAsync()
                    failedReporter?.let { reporter ->
                        if (failedNextPlayer) {
                            reporter.onPlaybackStopped(failedPlaybackInstanceId, failedPositionMs)
                            retirePlaybackReporter(reporter)
                        } else {
                            reporter.onPlaybackInterrupted(failedPlaybackInstanceId, failedPositionMs)
                        }
                    }
                    if (failedPlayback != null) {
                        _streamError.emit("Audio stream failed")
                        _streamFailure.emit(
                            PlaybackFailure(
                                stage = "audio_stream",
                                message = "Audio stream failed",
                                cause = error,
                                sourceName = failedPlayer.failureSource?.name,
                                videoId = failedPlayback.first,
                                playbackInstanceId = failedPlayback.second,
                                sabrFailureKind = error.findSabrProtocolException()?.kind,
                                playbackPositionMs = failedPositionMs,
                                resumePlayback = resumePlayback,
                                isReportable = !error.isAudioOutputUnavailable(),
                                isMediaSourceFailure = failedPlayer.failureSource != AudioPlayerFailureSource.RENDERER,
                                shouldExcludePlaybackProfile = failedPlayer.failureSource == AudioPlayerFailureSource.STREAM,
                                category =
                                    when (failedPlayer.failureSource) {
                                        AudioPlayerFailureSource.DECODER -> {
                                            PlaybackFailureCategory.DECODER
                                        }

                                        AudioPlayerFailureSource.RENDERER -> {
                                            PlaybackFailureCategory.RENDERER
                                        }

                                        AudioPlayerFailureSource.STREAM -> {
                                            error.findSabrProtocolException()?.kind?.let(::playbackFailureCategoryFromSabr)
                                                ?: PlaybackFailureClassifier.fromThrowable(error)
                                        }

                                        null -> {
                                            PlaybackFailureClassifier.fromThrowable(error)
                                        }
                                    },
                            ),
                        )
                        val resource = currentPlaybackResource.value
                        PlaybackEventLog.error(
                            PlaybackEventKind.STREAM,
                            "Audio stream failed for ${failedPlayback.first} at ${failedPositionMs}ms. ${PlaybackErrorMessages.describe(
                                error,
                            )}",
                            videoId = failedPlayback.first,
                            clientName = resource?.clientName,
                            profileId = resource?.profileId,
                            details =
                                playerLogDetails(
                                    mapOf(
                                        "failedPositionMs" to failedPositionMs.toString(),
                                        "resumePlayback" to resumePlayback.toString(),
                                    ),
                                ),
                            error = error,
                        )
                    }
                }
            },
        ).also {
            it.setPlaybackSpeed(_playbackSpeed.value)
            it.setPlaybackPitch(_playbackPitch.value)
            it.setAudioOutputEnabled(audioOutputEnabled)
        }

    private fun replacePrimaryPlayer(
        videoId: String,
        resource: PlaybackResource?,
        expectedPendingTransitionId: Long? = null,
        startPaused: Boolean,
    ): Pair<AudioPlayer, Long>? {
        var stoppedPlayback: Pair<Long, Long>? = null
        var previousPlayer: AudioPlayer? = null
        val replacement =
            synchronized(playerLock) {
                if (
                    expectedPendingTransitionId != null &&
                    currentPendingTransitionId != expectedPendingTransitionId
                ) {
                    return@synchronized null
                }
                stoppedPlayback = currentPlaybackInstanceId.takeIf { it > 0L }?.let { it to audioPlayer.getCurrentPosition() }
                previousPlayer = audioPlayer
                pendingPlaybackIntent = !startPaused
                audioPlayer =
                    createAudioPlayer().also {
                        it.videoEnabled = videoEnabled
                        it.videoFrameListener = videoFrameListener
                    }
                _currentPlaybackResource.value = resource
                activeAudioIsLocal = resource?.clientName == "local"
                transitionGain = 1f
                applyPlaybackGains()
                currentPlaybackInstanceId = playbackInstanceCounter.incrementAndGet()
                recoveryReportingFloor = null
                primaryVideoId = videoId
                currentPendingTransitionId = 0L
                audioPlayer to currentPlaybackInstanceId
            }
        if (replacement == null) return null
        previousPlayer?.closeAsync()
        stoppedPlayback?.let { (instanceId, positionMs) ->
            playbackReporter.onPlaybackStopped(instanceId, positionMs)
        }
        return replacement
    }

    private fun reportingPositionForSample(
        playbackInstanceId: Long,
        positionMs: Long,
    ): Long? {
        val floor = recoveryReportingFloor ?: return positionMs
        if (floor.playbackInstanceId != playbackInstanceId) {
            recoveryReportingFloor = null
            return positionMs
        }
        if (positionMs < floor.resumePositionMs) return null
        recoveryReportingFloor = null
        return positionMs
    }

    private fun AudioPlayer.prepareResource(
        resource: PlaybackResource,
        startPaused: Boolean,
    ): Boolean =
        resource.sabrInputStream()?.let { input ->
            prepareStream(input, resource.maximumSabrInputSize(), startPaused)
        } ?: resource.httpRangeInputStream()?.let { input ->
            prepareStream(input, resource.maximumDirectInputSize(), startPaused)
        } ?: prepareFile(resource.url, resource.headers, startPaused)

    private fun PlaybackResource.httpRangeInputStream(): HttpRangeInputStream? {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return null
        val mediaSize = contentLengthBytes?.takeIf { it > 0L } ?: return null
        // Native FFmpeg URL opening cannot reliably cancel; bound direct media through the HTTP stream.
        return HttpRangeInputStream(
            httpClient = mediaHttpClient,
            url = url,
            requestHeaders = headers,
            mediaSize = mediaSize,
            chunkSize =
                (
                    rangeChunkSizeBytes
                        .takeIf { it > 0L }
                        ?: DEFAULT_HTTP_RANGE_CHUNK_BYTES
                ).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
            cacheCapture = playbackCacheCapture(this),
        )
    }

    private fun PlaybackResource.sabrInputStream(): SabrInputStream? {
        val bootstrap = sabrBootstrap ?: return null
        return SabrInputStream(
            mediaSize = bootstrap.contentLengthBytes ?: contentLengthBytes,
            expectedDurationMs = bootstrap.durationMs,
            streamFactory = { initialPlayerTimeMs, playbackPositionMs ->
                sabrAudioPlaybackChunks(
                    httpClient = GlobalYouTube.client,
                    bootstrap = bootstrap,
                    tokenSupplier = sabrTokenSupplier,
                    onAttestationPending = onSabrAttestationPending,
                    playbackPositionMs = playbackPositionMs,
                    initialPlayerTimeMs = initialPlayerTimeMs,
                    logger = MetrolistInnerTubeLogger,
                )
            },
        )
    }

    fun createSabrVideoSource(resource: PlaybackResource): SeekableMediaSource? {
        val bootstrap = resource.sabrVideoBootstrap ?: return null
        if (bootstrap.selectedVideoFormat == null) return null
        return SabrInputStream(
            mediaSize = bootstrap.selectedVideoContentLengthBytes ?: resource.videoContentLengthBytes,
            streamFactory = { initialPlayerTimeMs, playbackPositionMs ->
                SabrVideoStream(
                    httpClient = GlobalYouTube.client,
                    bootstrap = bootstrap,
                    playbackPositionMs = playbackPositionMs,
                    initialPlayerTimeMs = initialPlayerTimeMs,
                    logger = MetrolistInnerTubeLogger,
                ).chunks()
            },
        )
    }

    private fun PlaybackResource.maximumSabrInputSize(): Int =
        (sabrBootstrap?.contentLengthBytes ?: contentLengthBytes)
            ?.coerceIn(1L, Int.MAX_VALUE.toLong() - 8L)
            ?.toInt()
            ?: 1

    private fun PlaybackResource.maximumDirectInputSize(): Int =
        contentLengthBytes
            ?.coerceIn(1L, Int.MAX_VALUE.toLong() - 8L)
            ?.toInt()
            ?: 1

    private data class RecoveryReportingFloor(
        val playbackInstanceId: Long,
        val resumePositionMs: Long,
    )

    data class PlaybackPreloadToken(
        val generation: Long,
        val playerInstanceId: Long,
        val videoId: String,
    )

    companion object {
        private const val STALL_INDICATOR_MS = 1_000L
        private const val DEFAULT_HTTP_RANGE_CHUNK_BYTES = 1_048_576L
        private const val RELEASE_STATS_TIMEOUT_MS = 1_000L
        private const val RECOVERY_SEEK_TIMEOUT_MS = 15_000L
    }
}

internal fun boundedBufferedPosition(
    positionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
): Long = if (durationMs > 0L) maxOf(positionMs, bufferedPositionMs).coerceIn(0L, durationMs) else 0L

internal fun shouldRetryPlayback(
    playbackReady: Boolean,
    hasLoadFailure: Boolean,
): Boolean = !playbackReady || hasLoadFailure

internal fun shouldDeferSabrCache(
    songId: String,
    current: PlaybackResource?,
    currentInstanceId: Long,
    completedInstanceId: Long,
    preloaded: Boolean,
): Boolean =
    preloaded || (
        current?.isSabr == true && currentInstanceId != completedInstanceId &&
            (current.videoId == songId || current.musicVideoId == songId)
    )
