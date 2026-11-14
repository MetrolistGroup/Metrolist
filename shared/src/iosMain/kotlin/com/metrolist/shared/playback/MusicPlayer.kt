package com.metrolist.shared.playback

import androidx.compose.runtime.snapshotFlow
import coil3.BitmapImage
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Size
import com.metrolist.shared.listentogether.PlaybackReadiness
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.storage.IosPlaybackCache
import com.metrolist.shared.storage.IosPlaybackCapture
import com.metrolist.shared.ui.playback.preferredPlaybackDurationMs
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.normalizePlaybackTempo
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.withFreshClientPlaybackNonce
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Image
import org.jetbrains.skia.impl.use
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.IOBufferDuration
import platform.AVFAudio.currentRoute
import platform.AVFAudio.outputVolume
import platform.AVFAudio.sampleRate
import platform.AVFAudio.setActive
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAudioTimePitchAlgorithmSpectral
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeErrorKey
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemNewAccessLogEntryNotification
import platform.AVFoundation.AVPlayerItemNewErrorLogEntryNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerItemTimeJumpedNotification
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.CMTimeRangeValue
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.audioTimePitchAlgorithm
import platform.AVFoundation.automaticallyWaitsToMinimizeStalling
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.error
import platform.AVFoundation.loadedTimeRanges
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.prerollAtRate
import platform.AVFoundation.rate
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.status
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorFileDoesNotExist
import platform.Foundation.NSURLErrorNoPermissionsToReadFile
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSUnderlyingErrorKey
import platform.Foundation.NSValue
import platform.Foundation.create
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyMediaType
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackQueueCount
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackQueueIndex
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage
import platform.darwin.NSObjectProtocol
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.TimeSource

internal sealed interface IosPlaybackOverlapEvent {
    val outgoing: Pair<Long, SongItem>
    val incoming: Pair<Long, SongItem>

    data class Started(
        override val outgoing: Pair<Long, SongItem>,
        override val incoming: Pair<Long, SongItem>,
    ) : IosPlaybackOverlapEvent

    data class Finished(
        override val outgoing: Pair<Long, SongItem>,
        override val incoming: Pair<Long, SongItem>,
    ) : IosPlaybackOverlapEvent

    data class Cancelled(
        override val outgoing: Pair<Long, SongItem>,
        override val incoming: Pair<Long, SongItem>,
        val resumed: Pair<Long, SongItem>,
    ) : IosPlaybackOverlapEvent
}

@OptIn(ExperimentalForeignApi::class)
class MusicPlayer internal constructor(
    initialPlayer: AVPlayer? = null,
    private val activateAudioSession: () -> Unit = { AVAudioSession.sharedInstance().setActive(true, error = null) },
    private val outputVolume: () -> Float,
) {
    constructor() : this(outputVolume = { AVAudioSession.sharedInstance().outputVolume })

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var playbackReporter = YouTubePlaybackReporter(scope, GlobalYouTubeRepository.instance)

    private var player: AVPlayer = initialPlayer ?: newPlayer()
    internal var playbackCache: IosPlaybackCache? = null
    internal val sabrPreparationFailures = MutableSharedFlow<IosAudioCaptureFailure>(extraBufferCapacity = 8)
    internal var audioCaptureFailure: IosAudioCaptureFailure? = null
        private set
    private var playbackCapture: IosPlaybackCapture? = null
        set(value) {
            if (field !== value) playbackCache?.release(field)
            field = value
        }
    private var preparedPlayback: PreparedPlayback? = null
    private var crossfadeStartMs: Long? = null
    private var crossfadeEndMs: Long? = null
    private var crossfadeAwaitingIncoming = false
    private var crossfadeOutgoingInstance: Pair<Long, SongItem>? = null
    private var crossfadeIncomingInstance: Pair<Long, SongItem>? = null
    private var crossfadePlaybackReporter: YouTubePlaybackReporter? = null
    private val crossfadeReportingActiveFlow = MutableStateFlow(false)
    internal val isCrossfadeReportingActive: StateFlow<Boolean> = crossfadeReportingActiveFlow.asStateFlow()
    private val crossfadeCacheInstanceFlow = MutableStateFlow<Pair<Long, SongItem>?>(null)
    internal val crossfadeCacheInstance: StateFlow<Pair<Long, SongItem>?> = crossfadeCacheInstanceFlow.asStateFlow()
    private val crossfadeReportingEvents = MutableSharedFlow<IosPlaybackOverlapEvent>(extraBufferCapacity = 8)
    internal val playbackOverlapEvents: SharedFlow<IosPlaybackOverlapEvent> = crossfadeReportingEvents.asSharedFlow()
    private val completedPlaybackInstanceEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    internal val completedPlaybackInstances: SharedFlow<Long> = completedPlaybackInstanceEvents.asSharedFlow()
    private var silenceAnalysisJob: Job? = null
    private var silenceMapSongId: String? = null
    private var silenceMapPlaybackInstanceId: Long? = null
    private var silenceIntervals: List<SilenceInterval> = emptyList()
    private var lastSilenceSkipEndMs = 0L
    private var nextPlaybackInstanceId = 0L
    private var playbackInstanceId = 0L
    private val sampleChanges = Channel<Unit>(Channel.CONFLATED)
    private var playbackObservation: IosPlaybackObservation? = null
    private var loadedRangesDirty = true
    private var loadedRanges = emptyList<Pair<Long, Long>>()
    private var uiObserving = false
    private var isPositionSeekPending = false
    private var volumeObservation: IosKeyValueObservation? = null
    private var stopAfterSongFade = false
    internal val sleepTimerProgress = MutableSharedFlow<Pair<Long, Long>>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    internal fun setUiObserving(observing: Boolean) {
        uiObserving = observing
        sampleChanges.trySend(Unit)
    }

    internal fun setStopAfterSongFade(enabled: Boolean) {
        if (stopAfterSongFade == enabled) return
        stopAfterSongFade = enabled
        sampleChanges.trySend(Unit)
    }

    internal fun currentPositionMs(): Long = nativePositionMs().coerceAtMost(_duration.value.takeIf { it > 0L } ?: Long.MAX_VALUE)

    private fun nativePositionMs(): Long {
        if (isResourceReloadSeekPending || isPositionSeekPending || player.currentItem == null) {
            return _currentPosition.value
        }
        val seconds = CMTimeGetSeconds(player.currentTime())
        return if (seconds.isFinite() && seconds >= 0.0) {
            (seconds * 1000).roundToLong()
        } else {
            _currentPosition.value
        }
    }

    internal var failedPlaybackCategory = PlaybackFailureCategory.UNKNOWN
        private set
    internal var failedPlaybackWanted = false
        private set

    internal var wantsPlayback = false
        private set(value) {
            field = value
            sampleChanges.trySend(Unit)
        }
    private var wasPlayingBeforeInterruption = false
    private val pauseOnMute = PauseOnMuteController()
    private val itemStateObserverTokens = mutableListOf<NSObjectProtocol>()
    private var endObserverToken: NSObjectProtocol? = null
    private var failObserverToken: NSObjectProtocol? = null
    private var interruptionObserverToken: NSObjectProtocol? = null
    private var routeChangeObserverToken: NSObjectProtocol? = null
    private val remoteTargets = mutableListOf<Pair<MPRemoteCommand, Any>>()
    private var publishedDurationMs = -1L
    private var metadataDurationSeconds: Long? = null
    private var artworkJob: Job? = null
    private var nowPlayingArtwork: MPMediaItemArtwork? = null
    private var didEmitEof = false
    private var pendingEofPlaybackInstanceId: Long? = null
    private var isQueueTransitionPending = false
    private var resourceReloadGeneration = 0L
    private var isResourceReloadSeekPending = false
    private var audioProcessingReady = true
    private var audioTapInstalled = false
    private var audioTapGain = MutableStateFlow(1f)
    private val activePitchAvailability = MutableStateFlow(MutableStateFlow(false))

    @OptIn(ExperimentalCoroutinesApi::class)
    val isPitchProcessingAvailable: StateFlow<Boolean> =
        activePitchAvailability
            .flatMapLatest {
                it
            }.stateIn(scope, SharingStarted.Eagerly, false)

    private data class PreparedPlayback(
        val song: SongItem,
        var resource: PlaybackResource,
        val player: AVPlayer,
        val item: AVPlayerItem,
        val asset: AVAsset,
        val queueIndex: Int,
        val capture: IosPlaybackCapture? = null,
        var audioProcessingReady: Boolean = true,
        var audioTapInstalled: Boolean = false,
        val audioTapGain: MutableStateFlow<Float> = MutableStateFlow(1f),
        val pitchAvailability: MutableStateFlow<Boolean> = MutableStateFlow(canUseIosAudioProcessingTap(resource, resource.url)),
        var observations: List<IosKeyValueObservation> = emptyList(),
        var positionObserver: Any? = null,
    ) {
        fun closeObservations() {
            observations.forEach { it.close() }
            observations = emptyList()
            positionObserver?.let(player::removeTimeObserver)
            positionObserver = null
        }
    }

    private data class ResourceReloadFallback(
        val item: AVPlayerItem,
        val resource: PlaybackResource?,
        val metadataDurationSeconds: Long?,
        val durationMs: Long,
        val audioTapInstalled: Boolean,
        val audioTapGain: MutableStateFlow<Float>,
        val pitchAvailability: MutableStateFlow<Boolean>,
        val capture: IosPlaybackCapture?,
    )

    private var resourceReloadFallback: ResourceReloadFallback? = null
        set(value) {
            if (field !== value) playbackCache?.release(field?.capture)
            field = value
        }

    private val _currentSong = MutableStateFlow<SongItem?>(null)
    val currentSong: StateFlow<SongItem?> = _currentSong.asStateFlow()
    private val playbackInstanceFlow = MutableStateFlow<Pair<Long, SongItem>?>(null)
    internal val playbackInstance: StateFlow<Pair<Long, SongItem>?> = playbackInstanceFlow.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _isWaitingToPlay = MutableStateFlow(false)
    val isWaitingToPlay: StateFlow<Boolean> = _isWaitingToPlay.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()
    private var sleepTimerGain: Float = 1f

    fun setSleepTimerGain(gain: Float) {
        sleepTimerGain = gain.coerceIn(0f, 1f)
        applyPlaybackVolume()
    }

    private val _playbackSpeed = MutableStateFlow(normalizePlaybackTempo(Settings.shared.playbackSpeed) ?: 1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()
    private val _playbackPitch = MutableStateFlow(normalizePlaybackTempo(Settings.shared.playbackPitch) ?: 1f)
    val playbackPitch: StateFlow<Float> = _playbackPitch.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition: StateFlow<Long> = _bufferedPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _currentPlaybackResource = MutableStateFlow<PlaybackResource?>(null)
    val currentPlaybackResource: StateFlow<PlaybackResource?> = _currentPlaybackResource.asStateFlow()

    private val transitionRequestsFlow = MutableSharedFlow<SongItem>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val transitionRequests: SharedFlow<SongItem> = transitionRequestsFlow.asSharedFlow()

    private val _queuePlaybackRequests =
        MutableSharedFlow<QueuePlaybackRequest>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val queuePlaybackRequests: SharedFlow<QueuePlaybackRequest> = _queuePlaybackRequests.asSharedFlow()

    private val nextTrackRequestFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val nextTrackRequests: SharedFlow<Unit> = nextTrackRequestFlow.asSharedFlow()
    private val previousTrackRequestFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val previousTrackRequests: SharedFlow<Unit> = previousTrackRequestFlow.asSharedFlow()
    private val seekRequestFlow =
        MutableSharedFlow<Pair<Long, Boolean>>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val seekRequests: SharedFlow<Pair<Long, Boolean>> = seekRequestFlow.asSharedFlow()

    private var _latestQueuePlaybackRequestId: Long = 0L
    val latestQueuePlaybackRequestId: Long
        get() = _latestQueuePlaybackRequestId

    private val _onEOF =
        MutableSharedFlow<Long>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val onEOF: SharedFlow<Long> = _onEOF.asSharedFlow()

    internal var isListenTogetherGuest: () -> Boolean = { false }
    internal var isListenTogetherActive: () -> Boolean = { false }
    private val readinessFlow = MutableStateFlow(PlaybackReadiness.IDLE)
    internal val readiness: StateFlow<PlaybackReadiness> = readinessFlow.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val queue = MusicQueue()
    val songQueue: StateFlow<List<SongItem>> = queue.songs
    val currentMediaItemIndex: StateFlow<Int> = queue.currentIndex

    var videoEnabled: Boolean = false
    var videoFrameListener: ((Any) -> Unit)? = null

    val isShuffleEnabled: Boolean
        get() = queue.isShuffled

    init {
        configureAudioSession(activate = false)
        observeAudioSessionNotifications()
        configureRemoteCommands()

        scope.launch {
            snapshotFlow { Settings.shared.audioNormalization to Settings.shared.loudnessLevel }.distinctUntilChanged().collect {
                applyPlaybackVolume()
            }
        }
        scope.launch {
            snapshotFlow { Triple(Settings.shared.crossfadeEnabled, Settings.shared.crossfadeDuration, Settings.shared.crossfadeGapless) }
                .distinctUntilChanged()
                .collect { (enabled, _, _) ->
                    if (!enabled) cancelActiveCrossfade()
                    sampleChanges.trySend(Unit)
                }
        }
        scope.launch {
            combine(
                snapshotFlow { Settings.shared.pauseOnMute },
                _isPlaying,
                pauseOnMute.isPausedByMute,
            ) { enabled, playing, pausedByMute ->
                enabled to (playing || pausedByMute)
            }.distinctUntilChanged().collectLatest { (enabled, active) ->
                if (!enabled) {
                    pauseOnMute.reset()
                } else if (active) {
                    val observation =
                        IosKeyValueObservation(AVAudioSession.sharedInstance(), listOf("outputVolume"), scope) {
                            outputVolumeChanged()
                        }
                    volumeObservation = observation
                    try {
                        pauseOnMute.reset(muted = outputVolume() <= 0f)
                        awaitCancellation()
                    } finally {
                        observation.close()
                        if (volumeObservation === observation) volumeObservation = null
                    }
                }
            }
        }
        scope.launch {
            snapshotFlow { Settings.shared.skipSilence }.distinctUntilChanged().collect { enabled ->
                if (enabled) {
                    val song = _currentSong.value
                    val resource = _currentPlaybackResource.value
                    if (song != null && resource != null) startSilenceAnalysis(song, resource)
                } else {
                    silenceAnalysisJob?.cancel()
                    silenceAnalysisJob = null
                    silenceMapSongId = null
                    silenceMapPlaybackInstanceId = null
                    silenceIntervals = emptyList()
                }
                sampleChanges.trySend(Unit)
            }
        }
        scope.launch {
            combine(songQueue, currentMediaItemIndex) { _, _ -> Unit }.collect { sampleChanges.trySend(Unit) }
        }
        scope.launch {
            var lastPublishedRendering: Boolean? = null
            for (signal in sampleChanges) {
                if (_currentSong.value == null) continue
                val activePlayer = player
                val isRendering = activePlayer.timeControlStatus == AVPlayerTimeControlStatusPlaying
                _isWaitingToPlay.value = activePlayer.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
                val rawCurrentSec = CMTimeGetSeconds(activePlayer.currentTime())
                val currentSec = if (rawCurrentSec.isFinite() && rawCurrentSec >= 0.0) rawCurrentSec else null
                val currentItem = activePlayer.currentItem
                if (currentItem != null) {
                    if (currentItem.status == AVPlayerItemStatusFailed) {
                        handlePlaybackFailed()
                    } else {
                        readinessFlow.value =
                            if (currentItem.status ==
                                AVPlayerItemStatusReadyToPlay
                            ) {
                                PlaybackReadiness.READY
                            } else {
                                PlaybackReadiness.BUFFERING
                            }
                        if (currentItem.status == AVPlayerItemStatusReadyToPlay && isRendering) resourceReloadFallback = null
                        val durSec = CMTimeGetSeconds(currentItem.duration)
                        val actualDurMs = if (durSec.isFinite() && durSec > 0) (durSec * 1000).toLong() else 0L
                        val durationMs = updatePlaybackDuration(actualDurMs)
                        val nativeEndMs = correctedNativeDurationMs(durationMs, actualDurMs)
                        val currentMs = currentSec?.let { (it * 1000).roundToLong() } ?: 0L
                        if (loadedRangesDirty) {
                            loadedRangesDirty = false
                            loadedRanges =
                                currentItem.loadedTimeRanges.mapNotNull { value ->
                                    val range = (value as? NSValue)?.CMTimeRangeValue ?: return@mapNotNull null
                                    val start = range.useContents { CMTimeGetSeconds(start.readValue()) }
                                    val end = CMTimeGetSeconds(CMTimeRangeGetEnd(range))
                                    if (start.isFinite() && end.isFinite() && start >= 0.0 && end >= start) {
                                        (start * 1000).toLong() to (end * 1000).toLong()
                                    } else {
                                        null
                                    }
                                }
                        }
                        _bufferedPosition.value = loadedPlaybackBufferEndMs(currentMs, durationMs, loadedRanges)
                        if (currentSec != null && !isResourceReloadSeekPending && !isPositionSeekPending) {
                            _currentPosition.value = currentMs.coerceAtMost(_duration.value.takeIf { it > 0L } ?: Long.MAX_VALUE)
                            skipDetectedSilence(currentMs)
                            if (!isPositionSeekPending) updatePreparedPlayback(currentMs, nativeEndMs.takeIf { it > 0L } ?: durationMs)
                            if (player == activePlayer && nativeEndMs != actualDurMs && currentMs >= nativeEndMs) {
                                notifyPlaybackEnded()
                            }
                        }
                    }
                }

                val currentlyRendering =
                    if (player == activePlayer) isRendering else player.timeControlStatus == AVPlayerTimeControlStatusPlaying
                if (lastPublishedRendering != currentlyRendering) {
                    lastPublishedRendering = currentlyRendering
                    if (wantsPlayback || !currentlyRendering) updateNowPlayingInfo()
                }

                val overlap = crossfadeIncomingInstance?.takeIf { crossfadeReportingActiveFlow.value }
                val overlapPlayer = preparedPlayback?.takeIf { overlap != null }?.player
                val overlapPositionSeconds = overlapPlayer?.let { CMTimeGetSeconds(it.currentTime()) }
                if (overlap != null && overlapPlayer != null) {
                    playbackReporter.onPlaybackSample(
                        playbackInstanceId = requireNotNull(crossfadeOutgoingInstance).first,
                        resource = _currentPlaybackResource.value,
                        positionMs = _currentPosition.value,
                        isRendering = currentlyRendering,
                        wantsPlayback = wantsPlayback,
                        playbackSpeed = _playbackSpeed.value,
                    )
                    requireNotNull(crossfadePlaybackReporter).onPlaybackSample(
                        playbackInstanceId = overlap.first,
                        resource = preparedPlayback?.resource,
                        positionMs =
                            overlapPositionSeconds
                                ?.takeIf { it.isFinite() && it >= 0.0 }
                                ?.let { (it * 1000).toLong() }
                                ?: 0L,
                        isRendering = overlapPlayer.timeControlStatus == AVPlayerTimeControlStatusPlaying,
                        wantsPlayback = wantsPlayback,
                        playbackSpeed = _playbackSpeed.value,
                    )
                } else {
                    playbackReporter.onPlaybackSample(
                        playbackInstanceId = playbackInstanceId,
                        resource = _currentPlaybackResource.value,
                        positionMs = _currentPosition.value,
                        isRendering = currentlyRendering,
                        wantsPlayback = wantsPlayback,
                        playbackSpeed = _playbackSpeed.value,
                    )
                }
                sleepTimerProgress.tryEmit(_currentPosition.value to _duration.value)
                updatePlaybackObservation()
            }
        }
    }

    private fun newPlayer(): AVPlayer =
        AVPlayer().apply {
            automaticallyWaitsToMinimizeStalling = true
        }

    internal fun updatePlaybackDuration(nativeDurationMs: Long): Long {
        val durationMs = preferredPlaybackDurationMs(metadataDurationSeconds, nativeDurationMs)
        if (durationMs <= 0L) return durationMs
        _duration.value = durationMs
        if (publishedDurationMs != durationMs) updateNowPlayingInfo()
        if (metadataDurationSeconds == null && nativeDurationMs > 0L && !isQueueTransitionPending &&
            queue.currentItem()?.id == _currentSong.value?.id
        ) {
            queue.updateCurrentDuration((durationMs + 999L) / 1000L)?.let { updatedSong ->
                _currentSong.value = updatedSong
                playbackInstanceFlow.value =
                    playbackInstanceFlow.value?.let { instance ->
                        if (instance.second.id == updatedSong.id) instance.first to updatedSong else instance
                    }
            }
        }
        return durationMs
    }

    private fun configureAudioSession(activate: Boolean = true) {
        runCatching {
            val session = AVAudioSession.sharedInstance()
            // Reapplying the category to an active session can reconfigure the audio route on resume.
            if (session.category != AVAudioSessionCategoryPlayback) {
                session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            }
            if (activate) {
                activateAudioSession()
                logAudioSession("iOS audio session activated")
            }
        }
    }

    private fun logAudioSession(
        message: String,
        reason: String? = null,
    ) {
        val session = AVAudioSession.sharedInstance()
        PlaybackEventLog.info(
            PlaybackEventKind.PLAYER,
            message,
            details =
                buildMap {
                    put("category", session.category.orEmpty())
                    put("mode", session.mode.orEmpty())
                    put("categoryOptions", session.categoryOptions.toString())
                    put(
                        "outputPorts",
                        session.currentRoute.outputs
                            .filterIsInstance<AVAudioSessionPortDescription>()
                            .joinToString(",") { it.portType.orEmpty() },
                    )
                    put(
                        "inputPorts",
                        session.currentRoute.inputs
                            .filterIsInstance<AVAudioSessionPortDescription>()
                            .joinToString(",") { it.portType.orEmpty() },
                    )
                    put("sessionSampleRate", session.sampleRate.toString())
                    put("ioBufferDuration", session.IOBufferDuration.toString())
                    reason?.let { put("routeChangeReason", it) }
                },
        )
    }

    private fun observeAudioSessionNotifications() {
        val center = NSNotificationCenter.defaultCenter
        interruptionObserverToken =
            center.addObserverForName(
                name = AVAudioSessionInterruptionNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { notification ->
                val userInfo = notification?.userInfo ?: return@addObserverForName
                val typeNumber = userInfo[AVAudioSessionInterruptionTypeKey] as? NSNumber ?: return@addObserverForName
                if (typeNumber.unsignedLongValue == AVAudioSessionInterruptionTypeBegan) {
                    if (wantsPlayback) {
                        pause()
                        wasPlayingBeforeInterruption = true
                    }
                } else if (typeNumber.unsignedLongValue == AVAudioSessionInterruptionTypeEnded) {
                    val optionsNumber = userInfo[AVAudioSessionInterruptionOptionKey] as? NSNumber
                    val options = optionsNumber?.unsignedLongValue ?: 0UL
                    val shouldResume = (options and AVAudioSessionInterruptionOptionShouldResume) != 0UL
                    if (wasPlayingBeforeInterruption) {
                        wasPlayingBeforeInterruption = false
                        if (shouldResume) {
                            resume()
                        }
                    }
                }
            }

        routeChangeObserverToken =
            center.addObserverForName(
                name = AVAudioSessionRouteChangeNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { notification ->
                val userInfo = notification?.userInfo ?: return@addObserverForName
                val reasonNumber = userInfo[AVAudioSessionRouteChangeReasonKey] as? NSNumber ?: return@addObserverForName
                logAudioSession("iOS audio route changed", reasonNumber.unsignedLongValue.toString())
                if (reasonNumber.unsignedLongValue == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) {
                    wasPlayingBeforeInterruption = false
                    if (wantsPlayback) {
                        pause()
                    }
                }
            }
    }

    fun play(
        song: SongItem,
        url: String,
    ) {
        play(song, url, emptyMap(), resource = null)
    }

    private fun play(
        song: SongItem,
        url: String,
        headers: Map<String, String>,
        resource: PlaybackResource?,
        preservePlaybackInstance: Boolean = false,
        startPlaying: Boolean = true,
        startPositionMs: Long = 0L,
    ): Boolean {
        pauseOnMute.cancelResume()
        val wasPlaying = wantsPlayback
        val positionMs = if (preservePlaybackInstance) currentPositionMs() else startPositionMs.coerceAtLeast(0L)
        if (!preservePlaybackInstance) _loadError.value = null

        val nsUrl =
            NSURL.URLWithString(url) ?: run {
                if (!preservePlaybackInstance) _loadError.value = "invalid-playback-url"
                return false
            }
        if (preservePlaybackInstance) _loadError.value = null
        audioCaptureFailure = null
        val reusablePrepared =
            preparedPlayback?.takeIf {
                !preservePlaybackInstance && it.song.id == song.id && it.resource.url == url &&
                    it.resource.headers == headers
            }

        if (!preservePlaybackInstance) {
            silenceAnalysisJob?.cancel()
            silenceAnalysisJob = null
            silenceMapSongId = null
            silenceMapPlaybackInstanceId = null
            silenceIntervals = emptyList()
            resourceReloadGeneration++
            isResourceReloadSeekPending = false
            resourceReloadFallback = null
            if (reusablePrepared != null) {
                reusablePrepared.closeObservations()
                reusablePrepared.player.pause()
                preparedPlayback = null
                crossfadeStartMs = null
                crossfadeEndMs = null
                crossfadeAwaitingIncoming = false
            } else {
                cancelPreparedPlayback()
            }
            finishPlaybackReporting()
            cleanupItemObservers()
            playbackInstanceId = ++nextPlaybackInstanceId
            playbackInstanceFlow.value = playbackInstanceId to song
            _currentPosition.value = positionMs
        } else {
            if (resourceReloadFallback == null) {
                player.currentItem?.let { previousItem ->
                    resourceReloadFallback =
                        ResourceReloadFallback(
                            item = previousItem,
                            resource = _currentPlaybackResource.value,
                            metadataDurationSeconds = metadataDurationSeconds,
                            durationMs = _duration.value,
                            audioTapInstalled = audioTapInstalled,
                            audioTapGain = audioTapGain,
                            pitchAvailability = activePitchAvailability.value,
                            capture = playbackCapture?.also { it.users++ },
                        )
                }
            }
            cleanupItemObservers()
            isResourceReloadSeekPending = false
        }
        readinessFlow.value = PlaybackReadiness.BUFFERING
        _currentPlaybackResource.value = resource
        _currentSong.value = song
        metadataDurationSeconds = song.duration?.takeIf { it > 0L } ?: resource?.durationSeconds?.takeIf { it > 0L }
        _duration.value = preferredPlaybackDurationMs(metadataDurationSeconds, nativeDurationMs = 0L)
        _bufferedPosition.value = 0L
        publishedDurationMs = -1L
        didEmitEof = false
        pendingEofPlaybackInstanceId = null
        if (!preservePlaybackInstance) {
            wasPlayingBeforeInterruption = false
            loadNowPlayingArtwork(song)
        }

        val capture = if (reusablePrepared != null) reusablePrepared.capture else resource?.let { playbackCache?.acquire(it) }
        if (resource != null && resource.requiresIosAudioCapture && capture == null) {
            audioCaptureFailure = IosAudioCaptureFailure(resource, if (preservePlaybackInstance) wasPlaying else startPlaying)
            val fallback = resourceReloadFallback
            if (preservePlaybackInstance && fallback != null) {
                if (resource.isSabr) sabrPreparationFailures.tryEmit(checkNotNull(audioCaptureFailure))
                restoreReloadFallback(fallback)
            } else {
                _loadError.value = "unsupported-audio-representation"
            }
            return false
        }
        val asset =
            reusablePrepared?.asset ?: capture?.asset ?: AVURLAsset(
                uRL = nsUrl,
                options = headers.takeIf { it.isNotEmpty() }?.let { mapOf("AVURLAssetHTTPHeaderFieldsKey" to it) },
            )
        val playerItem = reusablePrepared?.item ?: AVPlayerItem(asset = asset)
        playerItem.audioTimePitchAlgorithm = AVAudioTimePitchAlgorithmSpectral
        if (reusablePrepared != null) {
            val previousPlayer = player
            previousPlayer.pause()
            previousPlayer.replaceCurrentItemWithPlayerItem(null)
            player = reusablePrepared.player
            preparedPlayback = null
            val generation = resourceReloadGeneration
            isPositionSeekPending = true
            player.seekToTime(
                CMTimeMakeWithSeconds(0.0, 600),
                toleranceBefore = CMTimeMakeWithSeconds(0.0, 600),
                toleranceAfter = CMTimeMakeWithSeconds(0.0, 600),
            ) {
                if (generation == resourceReloadGeneration) {
                    isPositionSeekPending = false
                    sampleChanges.trySend(Unit)
                }
            }
        } else {
            player.replaceCurrentItemWithPlayerItem(playerItem)
        }
        if (playbackCapture === capture) playbackCache?.release(capture) else playbackCapture = capture
        audioTapInstalled = reusablePrepared?.audioTapInstalled ?: false
        audioTapGain = reusablePrepared?.audioTapGain ?: MutableStateFlow(1f)
        activePitchAvailability.value = reusablePrepared?.pitchAvailability ?: MutableStateFlow(canUseIosAudioProcessingTap(resource, url))
        audioProcessingReady = reusablePrepared?.audioProcessingReady ?: !canUseIosAudioProcessingTap(resource, url)
        if (reusablePrepared == null && !audioProcessingReady) {
            installIosAudioProcessingTap(
                scope,
                asset,
                playerItem,
                audioTapGain,
                playbackPitch,
                activePitchAvailability.value,
            ) { installed ->
                scope.launch(Dispatchers.Main.immediate) {
                    // currentItem may return a different Kotlin wrapper, so compare the native items.
                    if (player.currentItem != playerItem) return@launch
                    audioTapInstalled = installed
                    audioProcessingReady = true
                    applyPlaybackVolume()
                    if (wantsPlayback && !isResourceReloadSeekPending) {
                        configureAudioSession()
                        player.play()
                        if (_playbackSpeed.value != 1f) player.rate = _playbackSpeed.value
                    }
                }
            }
        }
        observePlayerItem(playerItem)
        resource?.let { startSilenceAnalysis(song, it) }
        if (preservePlaybackInstance) {
            playbackReporter.onPlaybackRecovered(
                previousPlaybackInstanceId = playbackInstanceId,
                playbackInstanceId = playbackInstanceId,
                positionMs = positionMs,
                format = resource?.itag,
            )
        }
        if (!preservePlaybackInstance) isQueueTransitionPending = false
        applyPlaybackVolume()
        if (!preservePlaybackInstance) {
            wantsPlayback = startPlaying
            _isPlaying.value = startPlaying
        }
        if (preservePlaybackInstance || positionMs > 0L) {
            val generation = ++resourceReloadGeneration
            val instanceId = playbackInstanceId
            if (positionMs > 0L) {
                isResourceReloadSeekPending = true
                player.pause()
                val targetTime = CMTimeMakeWithSeconds(positionMs.toDouble() / 1000.0, 600)
                val zeroCMTime = CMTimeMakeWithSeconds(0.0, 600)
                player.seekToTime(targetTime, toleranceBefore = zeroCMTime, toleranceAfter = zeroCMTime) {
                    if (generation == resourceReloadGeneration) {
                        isResourceReloadSeekPending = false
                        sampleChanges.trySend(Unit)
                        if (wantsPlayback && playbackInstanceId == instanceId && audioProcessingReady) {
                            configureAudioSession()
                            player.play()
                            if (_playbackSpeed.value != 1f) player.rate = _playbackSpeed.value
                        }
                    }
                }
            } else if (wasPlaying && audioProcessingReady) {
                configureAudioSession()
                player.play()
                if (_playbackSpeed.value != 1f) player.rate = _playbackSpeed.value
            } else {
                player.pause()
            }
            _currentPosition.value = positionMs
        } else {
            if (startPlaying && audioProcessingReady) {
                configureAudioSession()
                player.play()
                if (_playbackSpeed.value != 1f) player.rate = _playbackSpeed.value
            } else {
                player.pause()
            }
            wantsPlayback = startPlaying
            _isPlaying.value = startPlaying
        }
        updateNowPlayingInfo()
        return true
    }

    internal fun replacePlaybackResource(
        song: SongItem,
        resource: PlaybackResource,
    ): Boolean {
        if (_currentSong.value?.id != song.id || playbackInstanceId == 0L) return false
        cancelActiveCrossfade()
        return play(song, resource.url, resource.headers, resource, preservePlaybackInstance = true)
    }

    internal suspend fun replacePlaybackResourceInBackground(
        song: SongItem,
        resource: PlaybackResource,
        shouldCommit: () -> Boolean,
    ): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (!shouldCommit() || _currentSong.value?.id != song.id) return@withContext false
            val source = player
            val instance = playbackInstanceId
            val prepared = createPreparedPlayback(song, resource, queue.currentIndex.value) ?: return@withContext false
            var committed = false

            fun isCurrent() = shouldCommit() && player === source && playbackInstanceId == instance
            try {
                withTimeoutOrNull(if (resource.isSabr) IOS_SABR_PREPARATION_TIMEOUT_MS else 5_000L) {
                    while (isCurrent() && (prepared.item.status != AVPlayerItemStatusReadyToPlay || !prepared.audioProcessingReady)) {
                        if (prepared.item.status == AVPlayerItemStatusFailed) {
                            return@withTimeoutOrNull false
                        }
                        delay(20L)
                    }
                    var stalled = false

                    fun advancing() = wantsPlayback && !stalled
                    while (isCurrent()) {
                        val playing = advancing()
                        val targetMs = currentPositionMs() + if (playing) 1_000L else 0L
                        val sought =
                            suspendCancellableCoroutine<Boolean> { continuation ->
                                val time = CMTimeMakeWithSeconds(targetMs.toDouble() / 1000.0, 600)
                                val zero = CMTimeMakeWithSeconds(0.0, 600)
                                prepared.player.seekToTime(time, toleranceBefore = zero, toleranceAfter = zero) { finished ->
                                    if (continuation.isActive) continuation.resume(finished)
                                }
                            }
                        if (!sought || !isCurrent()) return@withTimeoutOrNull false
                        val ready =
                            suspendCancellableCoroutine<Boolean> { continuation ->
                                prepared.player.prerollAtRate(_playbackSpeed.value) { finished ->
                                    if (continuation.isActive) continuation.resume(finished)
                                }
                            }
                        if (!ready) return@withTimeoutOrNull false
                        var lastPositionMs = currentPositionMs()
                        var lastProgress = TimeSource.Monotonic.markNow()
                        while (isCurrent() && playing == advancing() && lastPositionMs < targetMs - 20L) {
                            delay(10L)
                            val positionMs = currentPositionMs()
                            if (positionMs != lastPositionMs) {
                                lastPositionMs = positionMs
                                lastProgress = TimeSource.Monotonic.markNow()
                            } else if (lastProgress.elapsedNow() >= WARM_REPLACEMENT_STALL) {
                                stalled = true
                            }
                        }
                        if (!isCurrent()) return@withTimeoutOrNull false
                        if (playing != advancing() || abs(currentPositionMs() - targetMs) > 100L) continue

                        cancelPreparedPlayback()
                        cleanupItemObservers()
                        prepared.closeObservations()
                        source.pause()
                        player = prepared.player
                        if (playbackCapture ===
                            prepared.capture
                        ) {
                            playbackCache?.release(prepared.capture)
                        } else {
                            playbackCapture = prepared.capture
                        }
                        audioProcessingReady = prepared.audioProcessingReady
                        audioTapInstalled = prepared.audioTapInstalled
                        audioTapGain = prepared.audioTapGain
                        activePitchAvailability.value = prepared.pitchAvailability
                        resourceReloadGeneration++
                        isResourceReloadSeekPending = false
                        resourceReloadFallback = null
                        _currentPosition.value = targetMs
                        _currentPlaybackResource.value = resource
                        _loadError.value = null
                        metadataDurationSeconds = song.duration?.takeIf { it > 0L } ?: resource.durationSeconds?.takeIf { it > 0L }
                        observePlayerItem(prepared.item)
                        playbackReporter.onPlaybackRecovered(instance, instance, targetMs, format = resource.itag)
                        applyPlaybackVolume()
                        if (wantsPlayback) {
                            player.play()
                            if (_playbackSpeed.value != 1f) player.rate = _playbackSpeed.value
                        }
                        source.replaceCurrentItemWithPlayerItem(null)
                        committed = true
                        updateNowPlayingInfo()
                        return@withTimeoutOrNull true
                    }
                    false
                } ?: false
            } finally {
                if (!committed) {
                    if (resource.isSabr && isCurrent() && currentCoroutineContext().isActive) {
                        sabrPreparationFailures.tryEmit(IosAudioCaptureFailure(resource, wantsPlayback, prepared.capture?.failure))
                    }
                    prepared.closeObservations()
                    prepared.player.pause()
                    prepared.player.replaceCurrentItemWithPlayerItem(null)
                    playbackCache?.release(prepared.capture)
                }
            }
        }

    fun play(
        song: SongItem,
        resource: PlaybackResource,
        startPlaying: Boolean = true,
        startPositionMs: Long = 0L,
    ) {
        play(song, resource.url, resource.headers, resource, startPlaying = startPlaying, startPositionMs = startPositionMs)
    }

    fun pause() {
        failedPlaybackWanted = false
        _currentPosition.value = currentPositionMs()
        pauseOnMute.cancelResume()
        wasPlayingBeforeInterruption = false
        cancelActiveCrossfade()
        player.pause()
        wantsPlayback = false
        _isPlaying.value = false
        playbackReporter.onPlaybackSample(
            playbackInstanceId = playbackInstanceId,
            resource = _currentPlaybackResource.value,
            positionMs = _currentPosition.value,
            isRendering = false,
            wantsPlayback = false,
            playbackSpeed = _playbackSpeed.value,
        )
        updateNowPlayingInfo()
    }

    internal fun suspendForQueueTransition(nextVideoId: String? = null) {
        if (preparedPlayback?.song?.id != nextVideoId) cancelPreparedPlayback()
        if (_currentSong.value?.id != nextVideoId) {
            resourceReloadFallback = null
            if (_currentPlaybackResource.value?.isSabr == true) playbackCapture = null
        }
        pendingEofPlaybackInstanceId = null
        isQueueTransitionPending = true
        pause()
    }

    internal fun cancelQueueTransition() {
        isQueueTransitionPending = false
    }

    fun resume() {
        pauseOnMute.cancelResume()
        if (isQueueTransitionPending) return
        if (_currentPlaybackResource.value == null || _loadError.value != null) {
            _currentSong.value?.let { emitQueuePlayback(it) }
            return
        }
        if (isResourceReloadSeekPending || !audioProcessingReady) {
            wantsPlayback = true
            _isPlaying.value = true
            updateNowPlayingInfo()
            return
        }
        configureAudioSession()
        player.play()
        if (_playbackSpeed.value != 1f) {
            player.rate = _playbackSpeed.value
        }
        wantsPlayback = true
        _isPlaying.value = true
        updateNowPlayingInfo()
    }

    internal fun outputVolumeChanged() {
        if (Settings.shared.pauseOnMute && (_isPlaying.value || pauseOnMute.isPausedByMute.value)) {
            pauseOnMute.onVolumeChanged(
                muted = outputVolume() <= 0f,
                enabled = Settings.shared.pauseOnMute,
                playing = _isPlaying.value,
                pause = ::pause,
                resume = ::resume,
            )
        }
    }

    fun stop() {
        pendingEofPlaybackInstanceId = null
        didEmitEof = false
        pauseOnMute.cancelResume()
        readinessFlow.value = PlaybackReadiness.IDLE
        isQueueTransitionPending = false
        cancelPreparedPlayback()
        silenceAnalysisJob?.cancel()
        silenceAnalysisJob = null
        silenceMapSongId = null
        silenceMapPlaybackInstanceId = null
        silenceIntervals = emptyList()
        resourceReloadGeneration++
        isResourceReloadSeekPending = false
        resourceReloadFallback = null
        finishPlaybackReporting()
        cleanupItemObservers()
        player.replaceCurrentItemWithPlayerItem(null)
        playbackCapture = null
        _currentPlaybackResource.value = null
        player.pause()
        wantsPlayback = false
        _isPlaying.value = false
        _currentPosition.value = 0L
        artworkJob?.cancel()
        artworkJob = null
        nowPlayingArtwork = null
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
    }

    fun seekTo(position: Long) {
        _currentPosition.value = currentPositionMs()
        sampleChanges.trySend(Unit)
        cancelActiveCrossfade()
        lastSilenceSkipEndMs = 0L
        val resumeAfterReloadSeek = isResourceReloadSeekPending && wantsPlayback
        val seekGeneration = ++resourceReloadGeneration
        val seekPlaybackInstanceId = playbackInstanceId
        isResourceReloadSeekPending = false
        didEmitEof = false
        pendingEofPlaybackInstanceId = null
        playbackReporter.onSeek(
            playbackInstanceId = playbackInstanceId,
            fromPositionMs = _currentPosition.value,
            toPositionMs = position,
            continuePlaying = wantsPlayback,
        )
        isPositionSeekPending = true
        val seconds = position.toDouble() / 1000.0
        val targetTime = CMTimeMakeWithSeconds(seconds, 600)
        val zeroCMTime = CMTimeMakeWithSeconds(0.0, 600)
        player.seekToTime(
            targetTime,
            toleranceBefore = zeroCMTime,
            toleranceAfter = zeroCMTime,
        ) { _ ->
            if (seekGeneration == resourceReloadGeneration && seekPlaybackInstanceId == playbackInstanceId) {
                isPositionSeekPending = false
                sampleChanges.trySend(Unit)
                if (resumeAfterReloadSeek && wantsPlayback) {
                    configureAudioSession()
                    player.play()
                }
                if (wantsPlayback && _playbackSpeed.value != 1f) {
                    player.rate = _playbackSpeed.value
                }
            }
        }
        _currentPosition.value = position
        updateNowPlayingInfo()
    }

    fun setVolume(level: Int) {
        _volume.value = level.coerceIn(0, 100).toFloat() / 100f
        applyPlaybackVolume()
    }

    private fun applyPlaybackVolume(positionMs: Long = nativePositionMs()) {
        val normalizationEnabled = Settings.shared.audioNormalization
        val level = Settings.shared.loudnessLevel
        val resource = _currentPlaybackResource.value
        val nextResource = preparedPlayback?.resource
        val currentNormalizationGain =
            playbackNormalizationGain(resource?.loudnessDb, normalizationEnabled, level, resource?.perceptualLoudnessDb)
        val nextNormalizationGain =
            playbackNormalizationGain(nextResource?.loudnessDb, normalizationEnabled, level, nextResource?.perceptualLoudnessDb)
        audioTapGain.value = currentNormalizationGain.coerceAtLeast(1f)
        preparedPlayback?.audioTapGain?.value = nextNormalizationGain.coerceAtLeast(1f)
        val progress =
            if (crossfadeStartMs != null && crossfadeEndMs != null) {
                playbackCrossfadeProgress(crossfadeStartMs!!, crossfadeEndMs!!, positionMs)
            } else {
                0f
            }
        val (outgoingGain, incomingGain) = playbackCrossfadeGains(progress)
        player.volume =
            playbackOutputGain(
                _volume.value,
                sleepTimerGain,
                outgoingGain,
                if (audioTapInstalled) currentNormalizationGain.coerceAtMost(1f) else currentNormalizationGain,
            )
        val incomingNormalizationGain =
            if (preparedPlayback?.audioTapInstalled ==
                true
            ) {
                nextNormalizationGain.coerceAtMost(1f)
            } else {
                nextNormalizationGain
            }
        preparedPlayback?.player?.volume =
            playbackOutputGain(
                _volume.value,
                sleepTimerGain,
                incomingGain,
                incomingNormalizationGain,
            )
    }

    private fun isNextQueueIndex(index: Int): Boolean {
        val currentIndex = queue.currentIndex.value
        return index == currentIndex + 1 || (index == 0 && currentIndex == queue.songs.value.lastIndex)
    }

    internal fun prepareNext(
        song: SongItem,
        resource: PlaybackResource,
        queueIndex: Int,
    ) {
        if (isListenTogetherActive()) return
        if (queue.songs.value
                .getOrNull(queueIndex)
                ?.id != song.id || !isNextQueueIndex(queueIndex)
        ) {
            return
        }
        preparedPlayback?.takeIf { it.song.id == song.id && it.resource.url == resource.url && it.queueIndex == queueIndex }?.let { return }
        cancelPreparedPlayback()
        preparedPlayback = createPreparedPlayback(song, resource, queueIndex)
    }

    private fun createPreparedPlayback(
        song: SongItem,
        resource: PlaybackResource,
        queueIndex: Int,
    ): PreparedPlayback? {
        val url = NSURL.URLWithString(resource.url) ?: return null
        val capture = playbackCache?.acquire(resource)
        if (resource.requiresIosAudioCapture && capture == null) {
            if (resource.isSabr) sabrPreparationFailures.tryEmit(IosAudioCaptureFailure(resource, wantsPlayback))
            return null
        }
        val asset =
            capture?.asset ?: AVURLAsset(
                uRL = url,
                options =
                    resource.headers.takeIf { it.isNotEmpty() }?.let {
                        mapOf("AVURLAssetHTTPHeaderFieldsKey" to it)
                    },
            )
        val item = AVPlayerItem(asset = asset)
        item.audioTimePitchAlgorithm = AVAudioTimePitchAlgorithmSpectral
        val nextPlayer =
            newPlayer().apply {
                replaceCurrentItemWithPlayerItem(item)
                volume = 0f
            }
        val prepared =
            PreparedPlayback(
                song,
                resource,
                nextPlayer,
                item,
                asset,
                queueIndex,
                capture = capture,
                audioProcessingReady = !canUseIosAudioProcessingTap(resource, resource.url),
            )
        prepared.observations =
            listOf(
                IosKeyValueObservation(nextPlayer, listOf("timeControlStatus", "rate"), scope) { sampleChanges.trySend(Unit) },
                IosKeyValueObservation(item, listOf("status", "duration"), scope) { sampleChanges.trySend(Unit) },
            )
        prepared.positionObserver =
            nextPlayer.addPeriodicTimeObserverForInterval(CMTimeMakeWithSeconds(1.0, 1000), null) {
                if (preparedPlayback === prepared && crossfadeReportingActiveFlow.value) sampleChanges.trySend(Unit)
            }
        sampleChanges.trySend(Unit)
        if (!prepared.audioProcessingReady) {
            installIosAudioProcessingTap(
                scope,
                asset,
                item,
                prepared.audioTapGain,
                playbackPitch,
                prepared.pitchAvailability,
            ) { installed ->
                scope.launch(Dispatchers.Main.immediate) {
                    prepared.audioTapInstalled = installed
                    prepared.audioProcessingReady = true
                    applyPlaybackVolume()
                    if (preparedPlayback === prepared) {
                        sampleChanges.trySend(Unit)
                    }
                    if (player.currentItem == item) {
                        audioTapInstalled = installed
                        audioProcessingReady = true
                        applyPlaybackVolume()
                        if (wantsPlayback && !isResourceReloadSeekPending) {
                            configureAudioSession()
                            player.play()
                            if (_playbackSpeed.value != 1f) player.rate = _playbackSpeed.value
                        }
                    }
                }
            }
        }
        return prepared
    }

    internal fun cancelCrossfadeTransition() {
        cancelPreparedPlayback()
    }

    private fun beginCrossfadeReporting(prepared: PreparedPlayback) {
        if (crossfadeReportingActiveFlow.value) return
        val outgoingSong = _currentSong.value ?: return
        val outgoing =
            playbackInstanceFlow.value?.takeIf { it.first == playbackInstanceId }
                ?: (playbackInstanceId to outgoingSong)
        val incoming = ++nextPlaybackInstanceId to prepared.song
        crossfadeOutgoingInstance = outgoing
        crossfadeIncomingInstance = incoming
        crossfadeCacheInstanceFlow.value = outgoing
        crossfadePlaybackReporter = YouTubePlaybackReporter(scope, GlobalYouTubeRepository.instance)
        playbackInstanceId = incoming.first
        crossfadeReportingActiveFlow.value = true
        crossfadeReportingEvents.tryEmit(IosPlaybackOverlapEvent.Started(outgoing, incoming))
        playbackInstanceFlow.value = incoming
        sampleChanges.trySend(Unit)
    }

    private fun cancelCrossfadeReporting(prepared: PreparedPlayback) {
        val incoming = crossfadeIncomingInstance ?: return
        val outgoing = crossfadeOutgoingInstance ?: return
        val seconds = CMTimeGetSeconds(prepared.player.currentTime())
        val positionMs = if (seconds.isFinite() && seconds >= 0.0) (seconds * 1000).toLong() else 0L
        crossfadePlaybackReporter?.onPlaybackStopped(incoming.first, positionMs)
        prepared.resource = prepared.resource.withFreshClientPlaybackNonce()
        prepared.capture?.bind(prepared.resource)
        crossfadePlaybackReporter = null
        val resumed = outgoing
        playbackInstanceId = resumed.first
        crossfadeOutgoingInstance = null
        crossfadeIncomingInstance = null
        crossfadeReportingEvents.tryEmit(IosPlaybackOverlapEvent.Cancelled(outgoing, incoming, resumed))
        playbackInstanceFlow.value = resumed
        crossfadeReportingActiveFlow.value = false
        crossfadeCacheInstanceFlow.value = null
    }

    private fun cancelActiveCrossfade() {
        val prepared = preparedPlayback ?: return
        if (crossfadeStartMs == null && !crossfadeAwaitingIncoming) return
        cancelCrossfadeReporting(prepared)
        prepared.player.pause()
        prepared.player.seekToTime(
            CMTimeMakeWithSeconds(0.0, 600),
            toleranceBefore = CMTimeMakeWithSeconds(0.0, 600),
            toleranceAfter = CMTimeMakeWithSeconds(0.0, 600),
        )
        crossfadeStartMs = null
        crossfadeEndMs = null
        crossfadeAwaitingIncoming = false
        applyPlaybackVolume()
        sampleChanges.trySend(Unit)
    }

    private fun cancelPreparedPlayback() {
        preparedPlayback?.let { prepared ->
            prepared.closeObservations()
            cancelCrossfadeReporting(prepared)
            prepared.player.pause()
            prepared.player.replaceCurrentItemWithPlayerItem(null)
            playbackCache?.release(prepared.capture)
        }
        preparedPlayback = null
        crossfadeStartMs = null
        crossfadeEndMs = null
        crossfadeAwaitingIncoming = false
        applyPlaybackVolume()
        sampleChanges.trySend(Unit)
    }

    private fun preparedFadeDurationMs(
        prepared: PreparedPlayback,
        durationMs: Long,
    ): Long {
        val incomingDuration = CMTimeGetSeconds(prepared.item.duration)
        val knownIncomingDurationMs =
            if (!incomingDuration.isFinite() || incomingDuration <= 0.0) {
                prepared.song.duration?.times(1000L) ?: prepared.resource.durationSeconds?.times(1000L) ?: Long.MAX_VALUE
            } else {
                (incomingDuration * 1000).toLong()
            }
        return playbackCrossfadeDurationMs(
            enabled = Settings.shared.crossfadeEnabled,
            preserveSameAlbumGapless = Settings.shared.crossfadeGapless,
            sameAlbum = songsShareAlbum(_currentSong.value, prepared.song),
            configuredDurationMs = (Settings.shared.crossfadeDuration * 1000f).toLong(),
            outgoingDurationMs = durationMs,
            incomingDurationMs = knownIncomingDurationMs,
        )
    }

    private fun playbackEndMs(): Long {
        val seconds = player.currentItem?.let { CMTimeGetSeconds(it.duration) }
        val nativeMs = seconds?.takeIf { it.isFinite() && it > 0.0 }?.let { (it * 1000).toLong() } ?: 0L
        return correctedNativeDurationMs(_duration.value, nativeMs).takeIf { it > 0L } ?: _duration.value
    }

    private fun updatePlaybackObservation() {
        val positionMs = nativePositionMs()
        val endMs = playbackEndMs()
        val fadeDurationMs = preparedPlayback?.let { preparedFadeDurationMs(it, endMs) } ?: 0L
        val boundary =
            if (wantsPlayback && !isPositionSeekPending && !isResourceReloadSeekPending) {
                nextIosPlaybackBoundaryMs(
                    positionMs,
                    endMs,
                    fadeDurationMs,
                    silenceIntervals.takeIf { Settings.shared.skipSilence && !isListenTogetherActive() }.orEmpty(),
                    lastSilenceSkipEndMs,
                    preparationDurationMs = _duration.value,
                )
            } else {
                null
            }
        val sleepFadeStart = (_duration.value - 60_000L).coerceAtLeast(1L).takeIf { stopAfterSongFade && _duration.value > 0L }
        val nextBoundary = listOfNotNull(boundary, sleepFadeStart?.takeIf { wantsPlayback && it > positionMs }).minOrNull()
        playbackObservation?.update(
            iosPlaybackSampleIntervalMs(uiObserving),
            nextBoundary,
            fadeIntervalMs =
                when {
                    !wantsPlayback || isPositionSeekPending || isResourceReloadSeekPending -> null
                    crossfadeStartMs != null -> 20L
                    sleepFadeStart != null && positionMs >= sleepFadeStart -> 100L
                    else -> null
                },
        )
    }

    private fun updateFade() {
        if (!wantsPlayback || isPositionSeekPending || isResourceReloadSeekPending) return
        val positionMs = nativePositionMs()
        val previousPlayer = player
        val wasCrossfading = crossfadeStartMs != null
        if (wasCrossfading) updatePreparedPlayback(positionMs, playbackEndMs())
        if (stopAfterSongFade) sleepTimerProgress.tryEmit(positionMs to _duration.value)
        if (previousPlayer != player || (wasCrossfading && crossfadeStartMs == null)) sampleChanges.trySend(Unit)
    }

    private fun updatePreparedPlayback(
        positionMs: Long,
        durationMs: Long,
    ) {
        val prepared = preparedPlayback ?: return
        if (prepared.item.status == AVPlayerItemStatusFailed && prepared.resource.isSabr) {
            sabrPreparationFailures.tryEmit(IosAudioCaptureFailure(prepared.resource, wantsPlayback, prepared.capture?.failure))
            prepared.capture?.markFailed()
        }
        if (prepared.item.status == AVPlayerItemStatusFailed || queue.songs.value
                .getOrNull(prepared.queueIndex)
                ?.id != prepared.song.id ||
            !isNextQueueIndex(prepared.queueIndex)
        ) {
            cancelPreparedPlayback()
            return
        }
        if (!wantsPlayback || durationMs <= 0L) return
        if (!prepared.audioProcessingReady) {
            if (crossfadeStartMs != null) {
                cancelCrossfadeReporting(prepared)
                crossfadeStartMs = null
                crossfadeEndMs = null
                crossfadeAwaitingIncoming = true
                applyPlaybackVolume()
            }
            return
        }
        if (prepared.item.status != AVPlayerItemStatusReadyToPlay) {
            if (crossfadeStartMs != null) {
                cancelCrossfadeReporting(prepared)
                crossfadeStartMs = null
                crossfadeEndMs = null
                crossfadeAwaitingIncoming = true
                applyPlaybackVolume()
            }
            return
        }
        if (crossfadeStartMs != null && prepared.player.timeControlStatus != AVPlayerTimeControlStatusPlaying) {
            cancelCrossfadeReporting(prepared)
            prepared.player.play()
            crossfadeStartMs = null
            crossfadeEndMs = null
            crossfadeAwaitingIncoming = true
            applyPlaybackVolume()
            return
        }
        if (crossfadeStartMs == null) {
            val fadeDuration = preparedFadeDurationMs(prepared, durationMs)
            if (fadeDuration == 0L && crossfadeAwaitingIncoming) {
                cancelActiveCrossfade()
                return
            }
            if (durationMs - positionMs in 1L..fadeDuration && positionMs > 0L) {
                if (!crossfadeAwaitingIncoming) {
                    crossfadeAwaitingIncoming = true
                    prepared.player.play()
                    if (_playbackSpeed.value != 1f) prepared.player.rate = _playbackSpeed.value
                }
                if (shouldStartPlaybackCrossfade(
                        currentPositionMs = positionMs,
                        durationMs = durationMs,
                        fadeDurationMs = fadeDuration,
                        incomingIsRendering = prepared.player.timeControlStatus == AVPlayerTimeControlStatusPlaying,
                    )
                ) {
                    crossfadeStartMs = positionMs
                    crossfadeEndMs = durationMs
                    crossfadeAwaitingIncoming = false
                }
            }
        }
        val startMs = crossfadeStartMs
        val endMs = crossfadeEndMs
        if (startMs != null && endMs != null) {
            val progress = playbackCrossfadeProgress(startMs, endMs, positionMs)
            applyPlaybackVolume(positionMs)
            if (progress > 0f && prepared.player.timeControlStatus == AVPlayerTimeControlStatusPlaying) {
                beginCrossfadeReporting(prepared)
            }
            if (progress >= 1f) finishPreparedPlaybackTransition(requireIncomingPlaying = true)
        }
    }

    private fun finishPreparedPlaybackTransition(requireIncomingPlaying: Boolean = false): Boolean {
        val prepared = preparedPlayback ?: return false
        if (prepared.item.status != AVPlayerItemStatusReadyToPlay ||
            (requireIncomingPlaying && prepared.player.timeControlStatus != AVPlayerTimeControlStatusPlaying)
        ) {
            cancelPreparedPlayback()
            return false
        }
        if (queue.songs.value
                .getOrNull(prepared.queueIndex)
                ?.id != prepared.song.id || !isNextQueueIndex(prepared.queueIndex)
        ) {
            cancelPreparedPlayback()
            return false
        }
        _currentPosition.value = currentPositionMs()
        val oldPlayer = player
        prepared.closeObservations()
        val isSameSongOccurrence = _currentSong.value?.id == prepared.song.id
        val outgoingInstance = crossfadeOutgoingInstance
        val incomingInstance = crossfadeIncomingInstance
        val transitionedSong = queue.skipTo(prepared.queueIndex) ?: return false
        if (incomingInstance == null) {
            completedPlaybackInstanceEvents.tryEmit(playbackInstanceId)
            playbackReporter.onPlaybackEnded(playbackInstanceId, _currentPosition.value)
        } else {
            val outgoing = requireNotNull(outgoingInstance)
            val incomingReporter = requireNotNull(crossfadePlaybackReporter)
            val incomingSeconds = CMTimeGetSeconds(prepared.player.currentTime())
            incomingReporter.onPlaybackSample(
                playbackInstanceId = incomingInstance.first,
                resource = prepared.resource,
                positionMs = if (incomingSeconds.isFinite() && incomingSeconds >= 0.0) (incomingSeconds * 1000).toLong() else 0L,
                isRendering = prepared.player.timeControlStatus == AVPlayerTimeControlStatusPlaying,
                wantsPlayback = wantsPlayback,
                playbackSpeed = _playbackSpeed.value,
            )
            playbackReporter.onPlaybackEnded(outgoing.first, _currentPosition.value)
            playbackReporter = incomingReporter
            crossfadePlaybackReporter = null
        }
        cleanupItemObservers()
        oldPlayer.pause()
        oldPlayer.replaceCurrentItemWithPlayerItem(null)
        val incomingWasPlaying = crossfadeStartMs != null
        player = prepared.player
        if (playbackCapture === prepared.capture) playbackCache?.release(prepared.capture) else playbackCapture = prepared.capture
        audioProcessingReady = prepared.audioProcessingReady
        audioTapInstalled = prepared.audioTapInstalled
        audioTapGain = prepared.audioTapGain
        activePitchAvailability.value = prepared.pitchAvailability
        preparedPlayback = null
        crossfadeStartMs = null
        crossfadeEndMs = null
        crossfadeAwaitingIncoming = false
        _currentPlaybackResource.value = prepared.resource
        _currentSong.value = transitionedSong
        metadataDurationSeconds = transitionedSong.duration?.takeIf { it > 0L } ?: prepared.resource.durationSeconds?.takeIf { it > 0L }
        if (incomingInstance != null) {
            playbackInstanceId = incomingInstance.first
            crossfadeOutgoingInstance = null
            crossfadeIncomingInstance = null
            crossfadeReportingEvents.tryEmit(IosPlaybackOverlapEvent.Finished(outgoingInstance ?: incomingInstance, incomingInstance))
            playbackInstanceFlow.value = incomingInstance.first to transitionedSong
            crossfadeReportingActiveFlow.value = false
            crossfadeCacheInstanceFlow.value = null
        } else {
            playbackInstanceId = ++nextPlaybackInstanceId
            playbackInstanceFlow.value = playbackInstanceId to transitionedSong
        }
        val incomingSeconds = CMTimeGetSeconds(player.currentTime())
        _currentPosition.value = if (!incomingSeconds.isNaN() && incomingSeconds > 0.0) (incomingSeconds * 1000).toLong() else 0L
        _duration.value =
            preferredPlaybackDurationMs(
                metadataDurationSeconds,
                (CMTimeGetSeconds(prepared.item.duration).takeIf { it.isFinite() && it > 0.0 }?.times(1000))?.toLong() ?: 0L,
            )
        _bufferedPosition.value = _duration.value
        publishedDurationMs = -1L
        didEmitEof = false
        pendingEofPlaybackInstanceId = null
        _loadError.value = null
        wasPlayingBeforeInterruption = false
        resourceReloadGeneration++
        isResourceReloadSeekPending = false
        resourceReloadFallback = null
        observePlayerItem(prepared.item)
        if (!incomingWasPlaying && wantsPlayback && audioProcessingReady) {
            configureAudioSession()
            player.play()
            if (_playbackSpeed.value != 1f) player.rate = _playbackSpeed.value
        }
        _isPlaying.value = wantsPlayback
        startSilenceAnalysis(transitionedSong, prepared.resource)
        applyPlaybackVolume()
        loadNowPlayingArtwork(transitionedSong)
        updateNowPlayingInfo()
        if (isSameSongOccurrence && incomingInstance == null) transitionRequestsFlow.tryEmit(transitionedSong)
        return true
    }

    internal fun analyzeSilenceFromCache(
        song: SongItem,
        resource: PlaybackResource,
    ) {
        if (_currentSong.value?.id == song.id) startSilenceAnalysis(song, resource)
    }

    private fun startSilenceAnalysis(
        song: SongItem,
        resource: PlaybackResource,
    ) {
        val analysisPlaybackInstanceId = playbackInstanceIdForCurrentPlayer(playbackInstanceId, crossfadeOutgoingInstance?.first)
        if (silenceMapSongId == song.id && silenceMapPlaybackInstanceId == analysisPlaybackInstanceId && silenceAnalysisJob != null) return
        silenceAnalysisJob?.cancel()
        silenceAnalysisJob = null
        silenceMapSongId = null
        silenceMapPlaybackInstanceId = null
        silenceIntervals = emptyList()
        if (!Settings.shared.skipSilence || !resource.url.startsWith("file:", ignoreCase = true)) return
        silenceMapSongId = song.id
        silenceMapPlaybackInstanceId = analysisPlaybackInstanceId
        lastSilenceSkipEndMs = 0L
        silenceAnalysisJob =
            scope.launch {
                val intervals = analyzeIosSilenceMap(resource.url)
                if (playbackInstanceIdForCurrentPlayer(
                        playbackInstanceId,
                        crossfadeOutgoingInstance?.first,
                    ) == analysisPlaybackInstanceId &&
                    _currentSong.value?.id == song.id
                ) {
                    silenceIntervals = intervals
                    sampleChanges.trySend(Unit)
                }
            }
    }

    private fun skipDetectedSilence(positionMs: Long) {
        val currentPlayerInstanceId = playbackInstanceIdForCurrentPlayer(playbackInstanceId, crossfadeOutgoingInstance?.first)
        if (isListenTogetherActive() || !wantsPlayback || !Settings.shared.skipSilence || silenceMapSongId != _currentSong.value?.id ||
            silenceMapPlaybackInstanceId != currentPlayerInstanceId
        ) {
            return
        }
        val silence =
            silenceIntervals.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs && it.endMs > lastSilenceSkipEndMs }
                ?: return
        if (silence.endMs - positionMs < 100L) return
        lastSilenceSkipEndMs = silence.endMs
        playbackReporter.onSeek(currentPlayerInstanceId, positionMs, silence.endMs, continuePlaying = wantsPlayback)
        val target = CMTimeMakeWithSeconds(silence.endMs.toDouble() / 1000.0, 600)
        val zero = CMTimeMakeWithSeconds(0.0, 600)
        val generation = ++resourceReloadGeneration
        isPositionSeekPending = true
        player.seekToTime(target, toleranceBefore = zero, toleranceAfter = zero) {
            if (generation == resourceReloadGeneration) {
                isPositionSeekPending = false
                sampleChanges.trySend(Unit)
            }
        }
        _currentPosition.value = silence.endMs
    }

    fun playbackSpeed(): Float = _playbackSpeed.value

    fun setPlaybackSpeed(speed: Float) {
        val clamped = normalizePlaybackTempo(speed) ?: return
        _playbackSpeed.value = clamped
        if (_isPlaying.value && !isResourceReloadSeekPending) player.rate = clamped
        if (crossfadeStartMs != null) preparedPlayback?.player?.rate = clamped
        updateNowPlayingInfo()
    }

    fun setPlaybackPitch(pitch: Float) {
        _playbackPitch.value = normalizePlaybackTempo(pitch) ?: return
    }

    fun setQueue(
        songs: List<SongItem>,
        startIndex: Int = 0,
        startPlaying: Boolean = false,
    ) {
        restoreQueue(MusicQueueSnapshot(songs, songs, startIndex, isShuffled = false))
        if (startPlaying && startIndex in songs.indices) {
            emitQueuePlayback(songs[startIndex])
        }
    }

    internal fun queueSnapshot(): MusicQueueSnapshot = queue.snapshot()

    internal fun <T> editQueue(edit: MusicQueue.() -> T): T = queue.edit().also { updateNowPlayingInfo() }

    internal fun restoreQueue(snapshot: MusicQueueSnapshot) {
        queue.restore(snapshot)
        queue.currentItem()?.let { song ->
            _currentSong.value = song
            metadataDurationSeconds = song.duration?.takeIf { it > 0L }
            val durationMs = preferredPlaybackDurationMs(metadataDurationSeconds, nativeDurationMs = 0L)
            _duration.value = durationMs
            loadNowPlayingArtwork(song)
            updateNowPlayingInfo()
        }
    }

    fun addToQueue(
        song: SongItem,
        playNext: Boolean = false,
    ) {
        if (playNext) {
            queue.playNext(song, Settings.shared.preventDuplicateTracksInQueue)
        } else {
            queue.addToQueue(song, Settings.shared.preventDuplicateTracksInQueue)
        }
    }

    fun removeQueueItem(index: Int) {
        removeQueueItems(setOf(index))
    }

    internal fun removeQueueItems(indices: Set<Int>): QueueRemoval? {
        val removal = queue.removeAt(indices - queue.currentIndex.value) ?: return null
        updateNowPlayingInfo()
        return removal
    }

    internal fun restoreQueueItems(removals: List<QueueRemoval>) {
        queue.restore(removals)
        updateNowPlayingInfo()
    }

    fun moveQueueItem(
        from: Int,
        to: Int,
    ) {
        queue.move(from, to)
    }

    fun replaceUpcomingQueue(songs: List<SongItem>) {
        queue.replaceUpcomingQueue(songs)
    }

    fun syncQueue(
        songs: List<SongItem>,
        index: Int,
    ) {
        editQueue { syncQueue(songs, index, Settings.shared.persistentShuffleAcrossQueues, Settings.shared.useLegacyShuffle) }
    }

    fun skipToNext() {
        nextTrackRequestFlow.tryEmit(Unit)
    }

    fun skipToPrevious() {
        previousTrackRequestFlow.tryEmit(Unit)
    }

    fun skipToQueueItem(index: Int) {
        queue.skipTo(index)?.let { song ->
            emitQueuePlayback(song)
        }
    }

    fun toggleShuffle(): Boolean {
        queue.toggleShuffle(legacyShuffle = Settings.shared.useLegacyShuffle)
        updateNowPlayingInfo()
        return queue.isShuffled
    }

    fun restoreShuffleMode(enabled: Boolean) {
        queue.restoreShuffleMode(enabled)
    }

    fun setPreferredDevice(device: Any?) {}

    fun getAudioSessionId(): Int = 0

    fun release() {
        volumeObservation?.close()
        volumeObservation = null
        cancelPreparedPlayback()
        silenceAnalysisJob?.cancel()
        silenceAnalysisJob = null
        resourceReloadGeneration++
        isResourceReloadSeekPending = false
        resourceReloadFallback = null
        finishPlaybackReporting()
        cleanupItemObservers()
        interruptionObserverToken?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        interruptionObserverToken = null
        routeChangeObserverToken?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        routeChangeObserverToken = null
        player.replaceCurrentItemWithPlayerItem(null)
        playbackCapture = null
        player.pause()
        remoteTargets.forEach { (command, target) -> command.removeTarget(target) }
        remoteTargets.clear()
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
        scope.cancel()
    }

    private fun restoreReloadFallback(fallback: ResourceReloadFallback) {
        fallback.capture?.let { it.users++ }
        if (playbackCapture === fallback.capture) playbackCache?.release(fallback.capture) else playbackCapture = fallback.capture
        resourceReloadFallback = null
        val positionMs = _currentPosition.value.coerceAtMost(fallback.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
        player.pause()
        cleanupItemObservers()
        player.replaceCurrentItemWithPlayerItem(fallback.item)
        observePlayerItem(fallback.item)
        audioTapInstalled = fallback.audioTapInstalled
        audioTapGain = fallback.audioTapGain
        activePitchAvailability.value = fallback.pitchAvailability
        _currentPlaybackResource.value = fallback.resource
        metadataDurationSeconds = fallback.metadataDurationSeconds
        _duration.value = fallback.durationMs
        _bufferedPosition.value = fallback.durationMs
        _currentPosition.value = positionMs
        didEmitEof = false
        pendingEofPlaybackInstanceId = null
        val instanceId = playbackInstanceId
        val generation = ++resourceReloadGeneration
        if (positionMs > 0L) {
            isResourceReloadSeekPending = true
            val targetTime = CMTimeMakeWithSeconds(positionMs.toDouble() / 1000.0, 600)
            val zeroCMTime = CMTimeMakeWithSeconds(0.0, 600)
            player.seekToTime(targetTime, toleranceBefore = zeroCMTime, toleranceAfter = zeroCMTime) {
                if (generation == resourceReloadGeneration) {
                    isResourceReloadSeekPending = false
                    sampleChanges.trySend(Unit)
                    if (wantsPlayback && playbackInstanceId == instanceId) {
                        configureAudioSession()
                        player.play()
                        if (_playbackSpeed.value != 1f) player.rate = _playbackSpeed.value
                    }
                }
            }
        } else {
            isResourceReloadSeekPending = false
            if (wantsPlayback) {
                configureAudioSession()
                player.play()
                if (_playbackSpeed.value != 1f) player.rate = _playbackSpeed.value
            }
        }
        playbackReporter.onPlaybackRecovered(
            previousPlaybackInstanceId = instanceId,
            playbackInstanceId = instanceId,
            positionMs = positionMs,
            format = fallback.resource?.itag,
        )
        applyPlaybackVolume()
        updateNowPlayingInfo()
    }

    private fun observePlayerItem(playerItem: AVPlayerItem) {
        playbackObservation =
            IosPlaybackObservation(
                player,
                playerItem,
                scope,
                onSample = { sampleChanges.trySend(Unit) },
                onItemChange = { key ->
                    if (key == "loadedTimeRanges") loadedRangesDirty = true
                    sampleChanges.trySend(Unit)
                },
                onFade = { updateFade() },
            )
        // Objective-C getters can return different Kotlin wrappers for the same native item.
        listOf(
            AVPlayerItemNewAccessLogEntryNotification,
            AVPlayerItemNewErrorLogEntryNotification,
            AVPlayerItemTimeJumpedNotification,
        ).forEach { name ->
            itemStateObserverTokens +=
                NSNotificationCenter.defaultCenter.addObserverForName(
                    name = name,
                    `object` = playerItem,
                    queue = NSOperationQueue.mainQueue,
                ) { if (player.currentItem == playerItem) sampleChanges.trySend(Unit) }
        }
        sampleChanges.trySend(Unit)
        endObserverToken =
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = AVPlayerItemDidPlayToEndTimeNotification,
                `object` = playerItem,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                if (player.currentItem == playerItem) notifyPlaybackEnded()
            }
        failObserverToken =
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = AVPlayerItemFailedToPlayToEndTimeNotification,
                `object` = playerItem,
                queue = NSOperationQueue.mainQueue,
            ) { notification ->
                if (player.currentItem == playerItem) {
                    handlePlaybackFailed(
                        notification?.userInfo?.get(AVPlayerItemFailedToPlayToEndTimeErrorKey) as? NSError ?: playerItem.error,
                    )
                }
            }
    }

    internal fun handlePlaybackFailed(error: NSError? = player.currentItem?.error) {
        if (_loadError.value == null) {
            failedPlaybackWanted = wantsPlayback
            failedPlaybackCategory = iosNativePlaybackFailureCategory(error)
            val failedResource = _currentPlaybackResource.value
            playbackCapture?.let { capture ->
                capture.markFailed()
                val song = _currentSong.value
                val resource = _currentPlaybackResource.value
                if (resource != null && resource.requiresIosAudioCapture) {
                    audioCaptureFailure = IosAudioCaptureFailure(resource, wantsPlayback, capture.failure)
                } else if (resourceReloadFallback == null && playbackInstanceId > 0L && song != null && resource != null) {
                    if (play(song, resource.url, resource.headers, resource, preservePlaybackInstance = true)) {
                        resourceReloadFallback = null
                        return
                    }
                }
            }
            resourceReloadFallback?.let { fallback ->
                if (failedResource?.isSabr == true) {
                    sabrPreparationFailures.tryEmit(IosAudioCaptureFailure(failedResource, wantsPlayback, audioCaptureFailure?.cause))
                }
                restoreReloadFallback(fallback)
                Logger.w("IosMusicPlayer", "Audio quality replacement failed; restored previous stream")
                return
            }
            PlaybackEventLog.error(
                kind = PlaybackEventKind.PLAYER,
                message = "AVPlayer playback failed",
                videoId = failedResource?.videoId,
                clientName = failedResource?.clientName,
                details =
                    buildMap {
                        error?.let {
                            it.domain?.let { domain -> put("errorDomain", domain) }
                            put("errorCode", it.code.toString())
                        }
                    },
            )
            _loadError.value =
                generateSequence(error) { it.userInfo[NSUnderlyingErrorKey] as? NSError }
                    .take(4)
                    .joinToString(prefix = "playback-failed", separator = "") { " ${it.domain}:${it.code}" }
            readinessFlow.value = PlaybackReadiness.FAILED
            cancelPreparedPlayback()
            player.pause()
            wantsPlayback = false
            _isPlaying.value = false
            finishPlaybackReporting()
            updateNowPlayingInfo()
        }
    }

    internal fun notifyPlaybackEnded() {
        if (didEmitEof || !wantsPlayback) return
        _currentPosition.value = currentPositionMs()
        val crossfadeWasPending = crossfadeStartMs != null || crossfadeAwaitingIncoming
        preparedPlayback
            ?.takeIf {
                it.item.status == AVPlayerItemStatusReadyToPlay && queue.songs.value
                    .getOrNull(it.queueIndex)
                    ?.id == it.song.id &&
                    isNextQueueIndex(it.queueIndex) &&
                    (!crossfadeWasPending || it.player.timeControlStatus == AVPlayerTimeControlStatusPlaying)
            }?.let {
                if (finishPreparedPlaybackTransition(requireIncomingPlaying = crossfadeWasPending)) return
            }
        if (crossfadeWasPending) cancelPreparedPlayback()
        resourceReloadFallback = null
        didEmitEof = true
        val endedPlaybackInstanceId = playbackInstanceId
        pendingEofPlaybackInstanceId = endedPlaybackInstanceId
        readinessFlow.value = PlaybackReadiness.IDLE
        playbackReporter.onPlaybackEnded(playbackInstanceId, _currentPosition.value)
        playbackInstanceId = 0L
        playbackInstanceFlow.value = null
        wantsPlayback = false
        _isPlaying.value = false
        updateNowPlayingInfo()
        _onEOF.tryEmit(endedPlaybackInstanceId)
    }

    internal fun consumePendingEof(playbackInstanceId: Long): Boolean {
        if (pendingEofPlaybackInstanceId != playbackInstanceId) return false
        pendingEofPlaybackInstanceId = null
        return true
    }

    private fun configureRemoteCommands() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        register(center.playCommand) {
            if (isListenTogetherGuest()) return@register MPRemoteCommandHandlerStatusCommandFailed
            resume()
            MPRemoteCommandHandlerStatusSuccess
        }
        register(center.pauseCommand) {
            if (isListenTogetherGuest()) return@register MPRemoteCommandHandlerStatusCommandFailed
            pause()
            MPRemoteCommandHandlerStatusSuccess
        }
        register(center.togglePlayPauseCommand) {
            if (isListenTogetherGuest()) return@register MPRemoteCommandHandlerStatusCommandFailed
            if (_isPlaying.value) pause() else resume()
            MPRemoteCommandHandlerStatusSuccess
        }
        register(center.nextTrackCommand) { handleRemoteSkip(next = true) }
        register(center.previousTrackCommand) { handleRemoteSkip(next = false) }
        updateRemoteSkipAvailability(canSkipNext = false, canSkipPrevious = false)
        center.skipForwardCommand.enabled = false
        center.skipBackwardCommand.enabled = false
        register(center.changePlaybackPositionCommand) { event ->
            if (isListenTogetherGuest()) return@register MPRemoteCommandHandlerStatusCommandFailed
            val position =
                (event as? MPChangePlaybackPositionCommandEvent)?.positionTime
                    ?: return@register MPRemoteCommandHandlerStatusCommandFailed
            requestSeek((position * 1000).toLong())
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    internal fun updateRemotePlaybackLock() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        val enabled = !isListenTogetherGuest()
        center.playCommand.enabled = enabled
        center.pauseCommand.enabled = enabled
        center.togglePlayPauseCommand.enabled = enabled
        center.changePlaybackPositionCommand.enabled = enabled
    }

    internal fun requestSeek(
        positionMs: Long,
        relative: Boolean = false,
    ) {
        if (isListenTogetherGuest()) return
        seekRequestFlow.tryEmit(positionMs to relative)
    }

    internal fun updateRemoteSkipAvailability(
        canSkipNext: Boolean,
        canSkipPrevious: Boolean,
    ) {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        center.nextTrackCommand.enabled = canSkipNext
        center.previousTrackCommand.enabled = canSkipPrevious
    }

    internal fun handleRemoteSkip(next: Boolean): Long {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        val command = if (next) center.nextTrackCommand else center.previousTrackCommand
        if (isListenTogetherGuest() || !command.enabled) return MPRemoteCommandHandlerStatusCommandFailed
        if (next) skipToNext() else skipToPrevious()
        return MPRemoteCommandHandlerStatusSuccess
    }

    private fun register(
        command: MPRemoteCommand,
        handler: (Any?) -> Long,
    ) {
        command.enabled = true
        remoteTargets += command to command.addTargetWithHandler(handler)
    }

    private fun updateNowPlayingInfo() {
        val song = _currentSong.value ?: return
        publishedDurationMs = _duration.value
        val queueList = queue.songs.value
        val queueIndex = queue.currentIndex.value
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo =
            mutableMapOf<Any?, Any?>(
                MPMediaItemPropertyTitle to song.title,
                MPMediaItemPropertyArtist to song.artists.joinToString { it.name },
                MPMediaItemPropertyPlaybackDuration to _duration.value / 1000.0,
                MPNowPlayingInfoPropertyElapsedPlaybackTime to currentPositionMs() / 1000.0,
                MPNowPlayingInfoPropertyPlaybackRate to
                    if (_isPlaying.value && player.timeControlStatus == AVPlayerTimeControlStatusPlaying) _playbackSpeed.value else 0f,
                MPNowPlayingInfoPropertyMediaType to 1L,
                MPNowPlayingInfoPropertyPlaybackQueueIndex to if (queueIndex >= 0) queueIndex else 0,
                MPNowPlayingInfoPropertyPlaybackQueueCount to queueList.size,
            ).apply {
                song.album?.name?.let { put(MPMediaItemPropertyAlbumTitle, it) }
                nowPlayingArtwork?.let { put(MPMediaItemPropertyArtwork, it) }
            }
    }

    @OptIn(BetaInteropApi::class)
    private fun loadNowPlayingArtwork(song: SongItem) {
        artworkJob?.cancel()
        nowPlayingArtwork = null
        val artworkUrl = song.thumbnail ?: return
        artworkJob =
            scope.launch {
                val context = PlatformContext.INSTANCE
                val request =
                    ImageRequest
                        .Builder(context)
                        .data(artworkUrl)
                        .size(600, 600)
                        .maxBitmapSize(Size(600, 600))
                        .precision(Precision.EXACT)
                        .build()
                val bitmap =
                    ((SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image as? BitmapImage)?.bitmap
                        ?: return@launch
                val image =
                    withContext(Dispatchers.Default) {
                        Image.makeFromBitmap(bitmap).use { skiaImage ->
                            skiaImage.encodeToData()?.use { data ->
                                data.bytes.usePinned { bytes ->
                                    UIImage.imageWithData(NSData.create(bytes.addressOf(0), data.size.toULong()))
                                }
                            }
                        }
                    } ?: return@launch
                if (_currentSong.value?.id != song.id) return@launch
                nowPlayingArtwork = MPMediaItemArtwork(CGSizeMake(600.0, 600.0)) { image }
                updateNowPlayingInfo()
            }
    }

    private fun emitQueuePlayback(song: SongItem) {
        suspendForQueueTransition()
        _latestQueuePlaybackRequestId++
        _queuePlaybackRequests.tryEmit(
            QueuePlaybackRequest(
                requestId = _latestQueuePlaybackRequestId,
                song = song,
            ),
        )
    }

    private fun finishPlaybackReporting() {
        _currentPosition.value = currentPositionMs()
        if (playbackInstanceId > 0L) {
            playbackReporter.onPlaybackStopped(playbackInstanceId, _currentPosition.value)
            playbackInstanceId = 0L
        }
        playbackInstanceFlow.value = null
    }

    private fun cleanupItemObservers() {
        playbackObservation?.close()
        playbackObservation = null
        isPositionSeekPending = false
        loadedRangesDirty = true
        loadedRanges = emptyList()
        itemStateObserverTokens.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        itemStateObserverTokens.clear()
        endObserverToken?.let {
            NSNotificationCenter.defaultCenter.removeObserver(it)
            endObserverToken = null
        }
        failObserverToken?.let {
            NSNotificationCenter.defaultCenter.removeObserver(it)
            failObserverToken = null
        }
    }
}

internal fun iosNativePlaybackFailureCategory(error: NSError?): PlaybackFailureCategory {
    val networkError =
        generateSequence(error) { it.userInfo[NSUnderlyingErrorKey] as? NSError }
            .take(4)
            .firstOrNull { it.domain == NSURLErrorDomain }
            ?: return PlaybackFailureCategory.UNKNOWN
    return when (networkError.code) {
        NSURLErrorTimedOut -> PlaybackFailureCategory.TIMEOUT
        NSURLErrorFileDoesNotExist, NSURLErrorNoPermissionsToReadFile -> PlaybackFailureCategory.UNAVAILABLE
        else -> PlaybackFailureCategory.NETWORK
    }
}
