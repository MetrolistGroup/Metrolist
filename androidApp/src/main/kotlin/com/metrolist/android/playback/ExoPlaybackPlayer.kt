package com.metrolist.android.playback

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.VideoSize
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.common.util.Util
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.EventLogger
import com.metrolist.android.BuildConfig
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrVideoStream
import com.metrolist.innertubex.sabr.findSabrProtocolException
import com.metrolist.shared.deviceconnect.DcLocalPlaybackState
import com.metrolist.shared.deviceconnect.DeviceConnectControllerClock
import com.metrolist.shared.equalizer.AudioSpectrumMeter
import com.metrolist.shared.equalizer.EqualizerProfiles
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_LIVE
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isLive
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.playback.PlaybackFailure
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.playback.PlaybackFailureClassifier
import com.metrolist.shared.playback.PlaybackNetworkRecovery
import com.metrolist.shared.playback.PlaybackProgressTimeoutException
import com.metrolist.shared.playback.PlaybackProgressWatchdog
import com.metrolist.shared.playback.PlaybackRecoveryPolicy
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.playback.QueuePlaybackRequest
import com.metrolist.shared.playback.QueueRemoval
import com.metrolist.shared.playback.SeekableMediaSource
import com.metrolist.shared.playback.YouTubePlaybackReporter
import com.metrolist.shared.playback.asManuallyAddedQueueItem
import com.metrolist.shared.playback.ffmpeg.DirectPlaybackCache
import com.metrolist.shared.playback.ffmpeg.HttpRangeInputStream
import com.metrolist.shared.playback.ffmpeg.SabrInputStream
import com.metrolist.shared.playback.findPlaybackHttpStatusCode
import com.metrolist.shared.playback.playbackFailureCategoryFromSabr
import com.metrolist.shared.playback.playbackNormalizationGain
import com.metrolist.shared.playback.playbackOutputGain
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.utils.normalizePlaybackTempo
import com.metrolist.shared.youtube.benchmark.PlaybackBenchmarkPlayer
import com.metrolist.shared.youtube.logging.MetrolistInnerTubeLogger
import com.metrolist.shared.youtube.playback.PlaybackBandwidthMeter
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.sabrAudioPlaybackChunks
import com.metrolist.shared.youtube.playback.toPlaybackLogDetails
import com.metrolist.shared.youtube.playback.withClientPlaybackNonce
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.IOException
import java.util.WeakHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

private const val PREPARATION_TIMEOUT_MS = 15_000L
private const val SCREEN_OFF_FOCUS_RECOVERY_DELAY_MS = 800L
private const val RELEASE_STATS_TIMEOUT_MS = 2_000L
private const val MUSIC_VIDEO_TAG = "MusicVideo"
private const val RESTART_CURRENT_ITEM_THRESHOLD_MS = 5_000L
private const val SWITCH_READY_BUFFER_MS = 500L
private const val SWITCH_RETARGET_THRESHOLD_MS = 100L
private const val MAX_SWITCH_RETARGETS = 3
private const val STATE_UPDATE_INTERVAL_MS = 100L

internal fun queuePlaybackSongToRetry(
    selectedSong: SongItem?,
    loadedSong: SongItem?,
    hasLoadedTrack: Boolean,
    trackChangePending: Boolean,
): SongItem? {
    val song = selectedSong ?: loadedSong ?: return null
    return song.takeIf { trackChangePending || !hasLoadedTrack || loadedSong?.id != song.id }
}

internal fun queuePlaybackRequestFlow() =
    MutableSharedFlow<QueuePlaybackRequest>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

internal class PlaybackTransitionState {
    private val _pending = MutableStateFlow(false)
    private var queuePending = false
    private val recoveryIds = mutableSetOf<Long>()

    val pending: StateFlow<Boolean> = _pending.asStateFlow()
    val recovering: Boolean get() = recoveryIds.isNotEmpty()

    fun beginQueue() {
        queuePending = true
        publish()
    }

    fun finishQueue() {
        queuePending = false
        publish()
    }

    fun beginRecovery(transitionId: Long) {
        recoveryIds += transitionId
        publish()
    }

    fun finishRecovery(transitionId: Long) {
        recoveryIds -= transitionId
        publish()
    }

    fun clear() {
        queuePending = false
        recoveryIds.clear()
        publish()
    }

    private fun publish() {
        _pending.value = queuePending || recoveryIds.isNotEmpty()
    }
}

internal fun shouldRestartCurrentItem(
    playbackPositionMs: Long,
    currentQueueIndex: Int,
): Boolean = playbackPositionMs >= RESTART_CURRENT_ITEM_THRESHOLD_MS || currentQueueIndex <= 0

internal fun MusicQueue.selectFirstIfUnselected(): Boolean {
    if (currentSong() != null || items.value.isEmpty()) return false
    skipTo(0)
    return true
}

internal fun shouldRequestQueueCurrent(
    currentSong: SongItem?,
    queueSong: SongItem,
    pendingSong: SongItem?,
): Boolean = currentSong == null || (currentSong !== queueSong && pendingSong !== queueSong)

internal fun catchUpPlaybackPosition(
    requestedPositionMs: Long,
    sourcePositionAtPrepareMs: Long,
    sourcePositionNowMs: Long,
): Long = (requestedPositionMs + sourcePositionNowMs - sourcePositionAtPrepareMs).coerceAtLeast(0L)

internal fun shouldRetargetWarmedReplacement(
    targetPositionMs: Long,
    replacementPositionMs: Long,
    isPlaying: Boolean,
): Boolean = abs(targetPositionMs - replacementPositionMs) > if (isPlaying) SWITCH_RETARGET_THRESHOLD_MS else 0L

internal fun warmReplacementCatchUpSpeed(sourceSpeed: Float): Float = (sourceSpeed * 2f).coerceAtLeast(0.5f)

internal enum class WarmReplacementAction { RETARGET, CATCH_UP, DRAIN, WAIT_FOR_SOURCE, WAIT, COMMIT, FAILED }

internal fun warmReplacementAction(
    targetPositionMs: Long,
    replacementPositionMs: Long,
    sourcePlaying: Boolean,
    candidateReady: Boolean,
    candidatePlaying: Boolean,
    buffered: Boolean,
    retargets: Int,
    sourceSeekPending: Boolean = false,
    preservePosition: Boolean = false,
    catchUpAudioDrained: Boolean = true,
): WarmReplacementAction {
    if (!catchUpAudioDrained) return WarmReplacementAction.DRAIN
    if (!candidateReady) return WarmReplacementAction.WAIT
    if (shouldRetargetWarmedReplacement(targetPositionMs, replacementPositionMs, sourcePlaying) ||
        (preservePosition && replacementPositionMs < targetPositionMs)
    ) {
        if (preservePosition && replacementPositionMs > targetPositionMs && !sourceSeekPending) {
            return if (sourcePlaying) WarmReplacementAction.WAIT_FOR_SOURCE else WarmReplacementAction.FAILED
        }
        if (!sourcePlaying || retargets == 0 || sourceSeekPending) {
            return if (retargets < MAX_SWITCH_RETARGETS) WarmReplacementAction.RETARGET else WarmReplacementAction.FAILED
        }
        return if (targetPositionMs > replacementPositionMs) WarmReplacementAction.CATCH_UP else WarmReplacementAction.WAIT_FOR_SOURCE
    }
    return if ((!sourcePlaying || candidatePlaying) && buffered) WarmReplacementAction.COMMIT else WarmReplacementAction.WAIT
}

internal fun shouldEnableVideoTrack(
    hasVideo: Boolean,
    preserveWarmTrack: Boolean,
    hasSurface: Boolean,
): Boolean = hasVideo && (preserveWarmTrack || hasSurface)

internal fun shouldEnableVideoTrackAfterWarmup(
    hasVideo: Boolean,
    hasSurface: Boolean,
): Boolean = hasVideo && hasSurface

internal enum class AndroidPlaybackPlayerRole(
    val handlesAudioFocus: Boolean,
) {
    PRIMARY(true),
    SECONDARY(false),
}

internal fun transferPlaybackAudioFocus(
    retirePrimary: () -> Unit,
    enablePromotedPlayer: () -> Unit,
) {
    retirePrimary()
    enablePromotedPlayer()
}

internal fun replaceAudioOutput(
    outgoing: Player,
    incoming: Player,
    releaseOutgoing: () -> Unit,
    promoteIncoming: () -> Unit,
) {
    incoming.volume = 0f
    outgoing.volume = 0f
    outgoing.pause()
    // Volume and pause messages use separate playback threads; release must finish before unmuting.
    releaseOutgoing()
    promoteIncoming()
}

internal fun cancelSecondaryPlayback(
    detachPlayer: () -> Unit,
    closeSources: () -> Unit,
    stopReporting: () -> Unit,
    releasePlayer: () -> Unit,
) {
    detachPlayer()
    closeSources()
    stopReporting()
    releasePlayer()
}

internal fun effectivePlaybackVolume(
    userVolume: Float,
    audioOutputEnabled: Boolean,
): Float = if (audioOutputEnabled) userVolume else 0f

internal fun shouldRecoverPlaybackAfterScreenOffFocusLoss(
    isInteractive: Boolean,
    audioMode: Int,
    isCasting: Boolean,
    isMusicActive: Boolean,
): Boolean =
    !isMusicActive &&
        !isInteractive &&
        audioMode != AudioManager.MODE_IN_CALL &&
        audioMode != AudioManager.MODE_IN_COMMUNICATION &&
        audioMode != AudioManager.MODE_RINGTONE &&
        !isCasting

@OptIn(ExperimentalSabrApi::class)
class ExoPlaybackPlayer(
    context: Context,
    private val onPlaybackCommand: () -> Unit = {},
    private val isVideoTransitionPending: () -> Boolean = { false },
    private val isCasting: () -> Boolean = { false },
    private val sabrPlaybackCache: SabrPlaybackCache = SabrPlaybackCache(),
) : AndroidPlaybackPlayer {
    private val context = context.applicationContext
    private val powerManager = this.context.getSystemService(PowerManager::class.java)
    private val focusRecoveryHandler = Handler(Looper.getMainLooper())
    private var pendingFocusRecovery: Runnable? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val playbackReporters = linkedSetOf<YouTubePlaybackReporter>()
    private var playbackReporter =
        YouTubePlaybackReporter.create(scope, GlobalYouTubeRepository.instance).also(playbackReporters::add)
    private val queue = MusicQueue()
    private val playbackAudioAttributes =
        AudioAttributes
            .Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
    private val silenceSupport = mutableMapOf<ExoPlayer, SilenceSkippingSupport>()
    private val normalizationProcessors = WeakHashMap<ExoPlayer, NormalizationAudioProcessor>()
    private val audioTrackConfigurations = WeakHashMap<ExoPlayer, AudioTrackConfig>()
    private val playerInstanceIds = WeakHashMap<Player, Long>()
    private val pendingVolumeLogs = mutableMapOf<Player, Job>()
    private val systemAudioEffects = SystemAudioEffects(this.context)
    private var exo = createPlayer()
    private val _playbackState = MutableStateFlow(exo.playbackState)
    private val tempoPitchLock = Any()
    private val eventLogger = EventLogger(MUSIC_VIDEO_TAG)
    private val forwardedPlayerListeners = linkedSetOf<Player.Listener>()
    private var preferredAudioDevice: AudioDeviceInfo? = null
    private var offloadFailed = false
    private val progressWatchdog = PlaybackProgressWatchdog()
    private var audioOutputEnabled = true
    private var outputRecoveryState = AudioOutputRecoveryState()
    private val a2dpMonitor = BluetoothA2dpMonitor(this.context) { maybeRecoverAudioOutput(a2dpStateChanged = true) }

    private fun createPlayer(
        bufferForPlaybackMs: Int = 2_000,
        role: AndroidPlaybackPlayerRole = AndroidPlaybackPlayerRole.PRIMARY,
    ): ExoPlayer {
        val normalization = NormalizationAudioProcessor { it.normalizationGain() }
        lateinit var createdPlayer: ExoPlayer
        var routeObserved = false
        val silence =
            SilenceSkippingSupport(
                context,
                normalization,
                onAudioOutputEvent = { event, details ->
                    scope.launch {
                        recordPlaybackState(
                            "Android audio output $event.",
                            outputDiagnosticDetails(createdPlayer) + details,
                        )
                    }
                },
                onOutputSignal = { signal ->
                    scope.launch {
                        if (createdPlayer === exo) {
                            val initialRoute = !routeObserved
                            if (signal.routeId != null) routeObserved = true
                            maybeRecoverAudioOutput(signal.copy(initialRoute = initialRoute))
                        }
                    }
                },
            )
        silence.renderersFactory.setEnableAudioOutputPlaybackParameters(Settings.shared.audioTrackPlaybackParams)
        return ExoPlayer
            .Builder(context)
            .setRenderersFactory(silence.renderersFactory)
            .setLoadControl(createPlaybackLoadControl(bufferForPlaybackMs))
            .setAudioAttributes(playbackAudioAttributes, role.handlesAudioFocus)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                createdPlayer = this
                setHandleAudioBecomingNoisy(true)
                normalizationProcessors[this] = normalization
                silenceSupport[this] = silence
                silence.attach(this)
                systemAudioEffects.attach(this)
                val player = this
                PlaybackEventLog.info(
                    PlaybackEventKind.PLAYER,
                    "Android player created.",
                    details = mapOf("playerId" to Integer.toHexString(System.identityHashCode(player)), "playerRole" to role.name),
                )
                addListener(
                    object : Player.Listener {
                        private var lastVolume = player.volume

                        override fun onVolumeChanged(volume: Float) {
                            val muteChanged = (lastVolume == 0f) != (volume == 0f)
                            lastVolume = volume
                            if (muteChanged) {
                                pendingVolumeLogs.remove(player)?.cancel()
                                recordPlayerOutput(player, "volume")
                            } else if (player !in pendingVolumeLogs) {
                                pendingVolumeLogs[player] =
                                    scope.launch {
                                        delay(500L.milliseconds)
                                        pendingVolumeLogs.remove(player)
                                        recordPlayerOutput(player, "volume")
                                    }
                            }
                        }

                        override fun onEvents(
                            player: Player,
                            events: Player.Events,
                        ) {
                            if (events.containsAny(
                                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                                    Player.EVENT_IS_PLAYING_CHANGED,
                                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                                    Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                                )
                            ) {
                                pendingVolumeLogs.remove(player)?.cancel()
                                val changes =
                                    buildList {
                                        if (events.contains(
                                                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                                            )
                                        ) {
                                            add(if (player.playWhenReady) "play" else "pause")
                                        }
                                        if (events.contains(
                                                Player.EVENT_IS_PLAYING_CHANGED,
                                            )
                                        ) {
                                            add(if (player.isPlaying) "started" else "stopped")
                                        }
                                        if (events.contains(Player.EVENT_VOLUME_CHANGED)) add("volume")
                                        if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) add("state")
                                        if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) add("speed")
                                    }
                                recordPlayerOutput(player, changes.joinToString(" "))
                            }
                        }
                    },
                )
                addAnalyticsListener(
                    object : AnalyticsListener {
                        override fun onAudioTrackInitialized(
                            eventTime: AnalyticsListener.EventTime,
                            audioTrackConfig: AudioTrackConfig,
                        ) {
                            audioTrackConfigurations[player] = audioTrackConfig
                            if (player === exo) {
                                recordPlaybackState("Android AudioTrack initialized.")
                            } else {
                                recordPlayerOutput(player, "AudioTrack initialized")
                            }
                        }

                        override fun onAudioTrackReleased(
                            eventTime: AnalyticsListener.EventTime,
                            audioTrackConfig: AudioTrackConfig,
                        ) {
                            if (player === exo) {
                                recordPlaybackState("Android AudioTrack released.")
                            } else {
                                recordPlayerOutput(player, "AudioTrack released")
                            }
                            audioTrackConfigurations.remove(player)
                        }
                    },
                )
            }
    }

    private fun releasePlayer(player: ExoPlayer) {
        pendingVolumeLogs.remove(player)?.cancel()
        recordPlayerOutput(player, "release requested")
        systemAudioEffects.detach(player)
        silenceSupport.remove(player)?.close()
        player.release()
        recordPlayerOutput(player, "released")
    }

    private fun recordPlayerOutput(
        player: Player,
        action: String,
    ) {
        PlaybackEventLog.info(
            PlaybackEventKind.PLAYER,
            "Android player output $action.",
            videoId = player.currentMediaItem?.mediaId,
            details =
                mapOf(
                    "playerId" to Integer.toHexString(System.identityHashCode(player)),
                    "primaryPlayerId" to playerId(),
                    "playbackInstanceId" to (playerInstanceIds[player]?.toString() ?: "preparing"),
                    "activePlaybackInstanceId" to currentInstance.toString(),
                    "playerRole" to
                        when (player) {
                            exo -> "primary"
                            nextPlayer -> "crossfade"
                            else -> "replacement"
                        },
                    "playWhenReady" to player.playWhenReady.toString(),
                    "isPlaying" to player.isPlaying.toString(),
                    "playerVolume" to player.volume.toString(),
                    "playbackPositionMs" to player.currentPosition.toString(),
                    "playbackSpeed" to player.playbackParameters.speed.toString(),
                    "playerState" to playbackStateName(player.playbackState),
                ),
        )
    }

    private val instanceCounter = AtomicLong()
    private var eofInstance = 0L
    private var lastEofInstance = 0L
    private var currentResource: PlaybackResource? = null
    private val playbackInstanceIdState = MutableStateFlow(0L)
    override val playbackInstanceId = playbackInstanceIdState.asStateFlow()
    private var currentInstance: Long
        get() = playbackInstanceIdState.value
        set(value) {
            playbackInstanceIdState.value = value
            if (value > 0L) playerInstanceIds[exo] = value
        }
    private var recoveryReportingFloor: RecoveryReportingFloor? = null

    private var minimumPositionMs = 0L
    private var currentSongValue: SongItem? = null
    private var activeAudioSource: SeekableMediaSource? = null
    private var activeVideoSourcePrepared = false
    private var activeVideoSource: SeekableMediaSource? = null
        set(value) {
            field = value
            activeVideoSourcePrepared = false
        }
    private var nextPlayer: ExoPlayer? = null
    private var nextAudioSource: SeekableMediaSource? = null
    private var nextVideoSource: SeekableMediaSource? = null
    private var nextResource: PlaybackResource? = null
    private var nextToken: AndroidPlaybackPlayer.PlaybackPreloadToken? = null
    private var nextExpectedCurrentInstanceId = 0L
    private var nextExpectedCurrentVideoId: String? = null
    private var nextStarted = false
    private var nextHeldAtStart = false
    private var nextFailed = false
    private var nextPlaybackReporter: YouTubePlaybackReporter? = null
    private var nextPlayerListener: Player.Listener? = null
    private var nextProgressJob: Job? = null
    private var nextGeneration = 0L
    private var requestCounter = 0L
    private var playbackRecoveryCounter = 0L
    private var pendingNetworkRecovery: PlaybackFailure? = null
    private var queueTrackChangePending = false
    private var speculativeQueueReplacementPreparation = false
    private var controllerClock: DeviceConnectControllerClock? = null
    private var progressJob: Job? = null
    private var lastReportingSampleAtMs = Long.MIN_VALUE
    private var lastReportedInstance = 0L
    private var liveSessionStartElapsedRealtimeMs = 0L
    private var liveSessionAccumulatedMs = 0L

    private var sleepTimerGain = 1f
    private var transitionGain = 1f
    private var nextTransitionGain = 0f

    private val _currentSong = MutableStateFlow<SongItem?>(null)
    private val _isPlaying = MutableStateFlow(false)
    private val _wantsPlayback = MutableStateFlow(false)
    private val _volume = MutableStateFlow(1f)
    private val displayVolumeState = MutableStateFlow(_volume.value)
    private val _playbackSpeed = MutableStateFlow(1f)
    private val _playbackPitch = MutableStateFlow(1f)
    private val _currentPosition = MutableStateFlow(0L)
    private val _bufferedPosition = MutableStateFlow(0L)
    private val _duration = MutableStateFlow(0L)
    private val _currentPlaybackResource = MutableStateFlow<PlaybackResource?>(null)
    private val playbackTransitions = PlaybackTransitionState()
    private val _loadError = MutableStateFlow<String?>(null)
    private val queuePlaybackRequestsState = queuePlaybackRequestFlow()
    private val onEOFPlaybackInstanceState = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    private val streamFailureState = MutableSharedFlow<AndroidPlaybackRecoveryRequest>(extraBufferCapacity = 1)
    override val currentSong: StateFlow<SongItem?> = _currentSong.asStateFlow()
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    override val wantsPlayback: Boolean get() = controllerClock?.playback?.playing ?: exo.playWhenReady

    override fun setExternalReportingEnabled(enabled: Boolean) {
        playbackReporter.setReportingEnabled(enabled)
    }

    override val wantsPlaybackState: StateFlow<Boolean> = _wantsPlayback.asStateFlow()
    override val volume: StateFlow<Float> = displayVolumeState.asStateFlow()
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()
    override val playbackPitch: StateFlow<Float> = _playbackPitch.asStateFlow()
    override val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()
    override val nextPlaybackPositionMs: Long
        get() = nextPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
    override val nextPlaybackDurationMs: Long
        get() = nextPlayer?.duration?.takeIf { it >= 0L } ?: 0L
    override val isNextPlaybackRendering: Boolean
        get() = nextPlayer?.isPlaying == true
    override val bufferedPosition: StateFlow<Long> = _bufferedPosition.asStateFlow()
    override val duration: StateFlow<Long> = _duration.asStateFlow()
    override val currentPlaybackResource: StateFlow<PlaybackResource?> = _currentPlaybackResource.asStateFlow()
    override val playbackStateFlow: StateFlow<Int> = _playbackState.asStateFlow()
    override val playbackTransitionPending: StateFlow<Boolean> = playbackTransitions.pending
    override val songQueue: StateFlow<List<SongItem>> = queue.songs
    override val queueSnapshots: StateFlow<MusicQueueSnapshot> = queue.snapshots
    override val queueState = queue.state
    override val currentMediaItemIndex: StateFlow<Int> = queue.currentIndex
    override val queuePlaybackRequests: SharedFlow<QueuePlaybackRequest> = queuePlaybackRequestsState
    override val latestQueuePlaybackRequestId: Long get() = requestCounter
    override val onEOFPlaybackInstance: SharedFlow<Long> = onEOFPlaybackInstanceState
    override val streamFailure: SharedFlow<AndroidPlaybackRecoveryRequest> = streamFailureState
    override val shuffleEnabled: StateFlow<Boolean> = queue.shuffleEnabled
    override val currentPlaybackInstanceId: Long get() = currentInstance
    override val loadError: StateFlow<String?> = _loadError.asStateFlow()
    override var videoEnabled = false
    override var videoFrameListener: ((Any) -> Unit)? = null

    private lateinit var playbackListener: Player.Listener

    private fun createPlaybackListener(source: ExoPlayer): Player.Listener =
        object : Player.Listener {
            override fun onPlayWhenReadyChanged(
                playWhenReady: Boolean,
                reason: Int,
            ) {
                if (source !== exo || controllerClock != null) return
                scheduleScreenOffFocusRecovery(_wantsPlayback.value, playWhenReady, reason)
                syncNextPlayback()
                _wantsPlayback.value = playWhenReady
                recordPlaybackState("Android playback intent changed.", mapOf("playWhenReadyReason" to reason.toString()))
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (source !== exo || controllerClock != null) return
                syncNextPlayback()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (source !== exo || controllerClock != null) return
                _isPlaying.value = isPlaying
                sabrPlaybackCache.setPlaying(isPlaying)
                recordPlaybackState("Android audio rendering changed.")
                val isLive = currentResource?.isLive == true || currentSongValue?.isLive == true || exo.isCurrentMediaItemLive
                if (isLive) {
                    val now = SystemClock.elapsedRealtime()
                    if (isPlaying) {
                        liveSessionStartElapsedRealtimeMs = now
                    } else if (liveSessionStartElapsedRealtimeMs > 0L) {
                        liveSessionAccumulatedMs += (now - liveSessionStartElapsedRealtimeMs).coerceAtLeast(0L)
                        liveSessionStartElapsedRealtimeMs = 0L
                    }
                }
                Timber.tag(MUSIC_VIDEO_TAG).d(
                    "playing changed player=%s instance=%d videoId=%s hasVideo=%s playing=%s positionMs=%d",
                    playerId(),
                    currentInstance,
                    currentResource?.videoId,
                    currentResource?.hasVideo,
                    isPlaying,
                    exo.currentPosition.coerceAtLeast(0L),
                )
            }

            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                if (source !== exo || player !== source || controllerClock != null) return
                _wantsPlayback.value = player.playWhenReady
                updateProgressState(reportSample = false)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (source !== exo || controllerClock != null) return
                PlaybackEventLog.info(
                    PlaybackEventKind.PLAYER,
                    "Android native position discontinuity.",
                    videoId = currentResource?.videoId,
                    details =
                        mapOf(
                            "playbackInstanceId" to currentInstance.toString(),
                            "queueRequestId" to latestQueuePlaybackRequestId.toString(),
                            "playerId" to Integer.toHexString(System.identityHashCode(source)),
                            "reason" to reason.toString(),
                            "fromPositionMs" to oldPosition.positionMs.toString(),
                            "toPositionMs" to newPosition.positionMs.toString(),
                        ),
                )
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (source !== exo || controllerClock != null) return
                _playbackState.value = state
                recordPlaybackState("Android playback state changed.")
                if (state == Player.STATE_READY) {
                    activeVideoSourcePrepared = true
                    updateVideoProducer(activeVideoSource, activeVideoSurface != null)
                }
                Timber.tag(MUSIC_VIDEO_TAG).d(
                    "state changed player=%s instance=%d videoId=%s state=%s playWhenReady=%s loading=%s positionMs=%d bufferedMs=%d",
                    playerId(),
                    currentInstance,
                    currentResource?.videoId,
                    playbackStateName(state),
                    exo.playWhenReady,
                    exo.isLoading,
                    exo.currentPosition.coerceAtLeast(0L),
                    exo.bufferedPosition.coerceAtLeast(0L),
                )
                if (state == Player.STATE_ENDED) completePlaybackAtEof()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (source !== exo) return
                Timber.tag(MUSIC_VIDEO_TAG).d(
                    "video size player=%s instance=%d videoId=%s width=%d height=%d pixelRatio=%s",
                    playerId(),
                    currentInstance,
                    currentResource?.videoId,
                    videoSize.width,
                    videoSize.height,
                    videoSize.pixelWidthHeightRatio,
                )
            }

            override fun onRenderedFirstFrame() {
                if (source !== exo) return
                Timber.tag(MUSIC_VIDEO_TAG).d(
                    "first frame player=%s instance=%d videoId=%s positionMs=%d surface=%s format=%s",
                    playerId(),
                    currentInstance,
                    currentResource?.videoId,
                    exo.currentPosition.coerceAtLeast(0L),
                    surfaceName(activeVideoSurface),
                    exo.videoFormat,
                )
            }

            override fun onSurfaceSizeChanged(
                width: Int,
                height: Int,
            ) {
                if (source !== exo) return
                Timber.tag(MUSIC_VIDEO_TAG).d(
                    "surface size player=%s instance=%d width=%d height=%d surface=%s",
                    playerId(),
                    currentInstance,
                    width,
                    height,
                    surfaceName(activeVideoSurface),
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                if (source !== exo) return
                Timber.tag(MUSIC_VIDEO_TAG).e(
                    "player error player=%s instance=%d videoId=%s code=%d type=%s positionMs=%d hasVideo=%s",
                    playerId(),
                    currentInstance,
                    currentResource?.videoId,
                    error.errorCode,
                    error::class.simpleName,
                    exo.currentPosition.coerceAtLeast(0L),
                    currentResource?.hasVideo,
                )
                val isUncommittedQueueReplacement =
                    speculativeQueueReplacementPreparation && currentResource == null && currentInstance == 0L
                if (!shouldReportActivePlaybackError(isUncommittedQueueReplacement)) return
                if (shouldCompleteStuckPlayback(error, currentInstance, eofInstance)) {
                    completePlaybackAtEof()
                    return
                }
                playbackReporter.onPlaybackInterrupted(currentInstance, exo.currentPosition.coerceAtLeast(0L))
                reportPlaybackFailure(error)
            }
        }

    private lateinit var networkLoadListener: AnalyticsListener

    private fun createNetworkLoadListener(source: ExoPlayer): AnalyticsListener =
        object : AnalyticsListener {
            override fun onAudioSinkError(
                eventTime: AnalyticsListener.EventTime,
                audioSinkError: Exception,
            ) {
                if (source !== exo) return
                if (!offloadRequested()) return
                val errorCode =
                    when (audioSinkError) {
                        is AudioSink.InitializationException -> PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                        is AudioSink.WriteException -> PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
                        else -> return
                    }
                // Media3 can retry recoverable sink errors forever without calling onPlayerError.
                reportPlaybackFailure(PlaybackException("Audio offload output failed", audioSinkError, errorCode))
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                if (source !== exo) return
                PlaybackEventLog.warn(
                    PlaybackEventKind.PLAYER,
                    "Android audio output underrun.",
                    videoId = currentResource?.videoId,
                    clientName = currentResource?.clientName,
                    profileId = currentResource?.profileId,
                    details =
                        playbackStateDetails() +
                            mapOf(
                                "audioBufferBytes" to bufferSize.toString(),
                                "audioBufferMs" to bufferSizeMs.toString(),
                                "lastAudioFeedMs" to elapsedSinceLastFeedMs.toString(),
                            ),
                )
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean,
            ) {
                if (source !== exo) return
                val category = error.media3PlaybackFailureCategory()
                if (!wasCanceled && (PlaybackNetworkRecovery.handles(category) || PlaybackRecoveryPolicy.requiresFreshUrl(category))) {
                    reportPlaybackFailure(error)
                }
            }
        }

    private lateinit var offloadListener: ExoPlayer.AudioOffloadListener

    private fun createOffloadListener(source: ExoPlayer): ExoPlayer.AudioOffloadListener =
        object : ExoPlayer.AudioOffloadListener {
            override fun onSleepingForOffloadChanged(sleepingForOffload: Boolean) {
                if (source !== exo) return
                recordPlaybackState("Android offload sleep changed.")
            }
        }

    init {
        scope.launch {
            snapshotFlow {
                Triple(
                    EqualizerProfiles.configuration,
                    Settings.shared.skipSilence to Settings.shared.crossfadeEnabled,
                    Settings.shared.audioNormalization to Settings.shared.loudnessLevel,
                )
            }.combine(AudioSpectrumMeter.hasConsumers) { _, _ -> Unit }
                .collect { applyAudioPreferences() }
        }
        attachPlayerListeners(exo)
        scope.launch {
            ProcessLifecycleOwner
                .get()
                .lifecycle.currentStateFlow
                .map { it.isAtLeast(Lifecycle.State.STARTED) }
                .distinctUntilChanged()
                .collect { foreground ->
                    recordPlaybackState("Android playback foreground changed.", mapOf("appForeground" to foreground.toString()))
                }
        }
        Timber.tag(MUSIC_VIDEO_TAG).d("ExoPlayer created player=%s", playerId())
        progressJob =
            scope.launch {
                val appVisible =
                    ProcessLifecycleOwner
                        .get()
                        .lifecycle.currentStateFlow
                        .map { it.isAtLeast(Lifecycle.State.STARTED) }
                combine(
                    _isPlaying,
                    _wantsPlayback,
                    _playbackState,
                    appVisible,
                    _playbackSpeed,
                ) { playing, wantsPlayback, state, visible, speed ->
                    Triple(shouldSamplePlaybackProgress(playing, wantsPlayback, state), visible, speed)
                }.combine(snapshotFlow { Settings.shared.crossfadeEnabled to Settings.shared.crossfadeDuration }) { playback, crossfade ->
                    playback to crossfade
                }.collectLatest { (playback, crossfade) ->
                    val (sampleProgress, visible, speed) = playback
                    updateProgressState(reportSample = true)
                    // UI consumers interpolate between samples per frame; faster publishing redraws the window on every tick.
                    while (sampleProgress && controllerClock == null) {
                        val pollIntervalMs =
                            playbackPollIntervalMs(
                                fineProgressActive = visible,
                                positionMs = exo.currentPosition,
                                durationMs = exo.duration,
                                playbackSpeed = speed,
                                crossfadeEnabled = crossfade.first,
                                crossfadeDurationMs = (crossfade.second * 1_000f).toLong().coerceAtLeast(1_000L),
                            )
                        delay(pollIntervalMs.milliseconds)
                        updateProgressState(reportSample = true)
                    }
                }
            }
    }

    override fun play(
        song: SongItem,
        url: String,
        startPlaying: Boolean,
    ) = play(song, resourceFor(song, url), startPlaying)

    override fun play(
        song: SongItem,
        resource: PlaybackResource,
        startPlaying: Boolean,
    ) {
        onPlaybackCommand()
        cancelScreenOffFocusRecovery()
        requestCounter++
        outputRecoveryState = outputRecoveryState.cancelUserRecovery()
        playbackTransitions.clear()
        queueTrackChangePending = false
        val token = requestCounter
        scope.launch {
            prepareAndPlay(
                song = song,
                resource = resource,
                resumePlayback = startPlaying,
                shouldCommit = { requestCounter == token },
                onPrepareFailure = { error -> reportPlaybackFailure(error ?: IOException("Media3 audio preparation failed")) },
            )
        }
    }

    override fun setControllerPlayback(playback: DcLocalPlaybackState?) {
        cancelScreenOffFocusRecovery()
        if (playback == null) {
            if (controllerClock == null) return
            _currentPosition.value = getRealtimePositionMs()
            controllerClock = null
            displayVolumeState.value = _volume.value
            _isPlaying.value = false
            _wantsPlayback.value = false
            _playbackState.value = Player.STATE_IDLE
            return
        }
        if (controllerClock == null) {
            stop()
            exo.clearMediaItems()
        }
        controllerClock = DeviceConnectControllerClock(playback)
        displayVolumeState.value = playback.volume
        val song = playback.queue.displayedItems.getOrNull(playback.queue.currentIndex)
        currentSongValue = song
        _currentSong.value = song
        _currentPosition.value = playback.positionMs
        _duration.value = playback.durationMs
        _bufferedPosition.value = playback.positionMs
        setPlaybackSpeed(playback.speed)
        playback.pitch?.let(::setPlaybackPitch)
        _wantsPlayback.value = playback.playing
        _isPlaying.value = playback.playing && playback.ready
        _playbackState.value = if (playback.ready) Player.STATE_READY else Player.STATE_BUFFERING
    }

    override fun pause() {
        onPlaybackCommand()
        cancelScreenOffFocusRecovery()
        logCommand("pause")
        exo.pause()
        if (nextStarted) nextPlayer?.pause()
        maybeRecoverAudioOutput(userAction = AudioOutputUserAction.PAUSE)
    }

    override fun resume() {
        onPlaybackCommand()
        cancelScreenOffFocusRecovery()
        logCommand("resume")
        if (currentResource == null && retryPendingQueuePlayback()) return
        if (currentSong.value?.isLive == true) {
            recordSeek(exo, 0L, PlaybackSeekSource("queue_restore", mapOf("reason" to "resume_ended")))
            exo.seekToDefaultPosition()
        }
        exo.play()
        exo.playerError?.let(::reportPlaybackFailure)
        syncNextPlayback()
        maybeRecoverAudioOutput(userAction = AudioOutputUserAction.RESUME)
    }

    override fun stop() {
        onPlaybackCommand()
        cancelScreenOffFocusRecovery()
        outputRecoveryState = outputRecoveryState.cancelUserRecovery()
        controllerClock = null
        displayVolumeState.value = _volume.value
        requestCounter++
        playbackTransitions.clear()
        cancelNextInternal()
        val stoppedInstance = currentInstance
        val stoppedPosition = exo.currentPosition.coerceAtLeast(0L)
        Timber.tag(MUSIC_VIDEO_TAG).d(
            "stop player=%s instance=%d videoId=%s positionMs=%d hasVideo=%s",
            playerId(),
            stoppedInstance,
            currentResource?.videoId,
            stoppedPosition,
            currentResource?.hasVideo,
        )
        exo.pause()
        exo.stop()
        sabrPlaybackCache.release(activeAudioSource)
        activeAudioSource?.closePlaybackSource()
        activeAudioSource = null
        activeVideoSource?.closePlaybackSource()
        activeVideoSource = null
        currentResource = null
        updateOffloadPreferences(exo)
        currentSongValue = null
        queueTrackChangePending = false
        currentInstance = 0L
        liveSessionStartElapsedRealtimeMs = 0L
        liveSessionAccumulatedMs = 0L
        recoveryReportingFloor = null
        eofInstance = 0L
        _currentSong.value = null
        _isPlaying.value = false
        minimumPositionMs = 0L
        _currentPosition.value = 0L
        _bufferedPosition.value = 0L
        _duration.value = 0L
        _currentPlaybackResource.value = null
        _loadError.value = null
        if (stoppedInstance > 0L) playbackReporter.onPlaybackStopped(stoppedInstance, stoppedPosition)
    }

    override fun seekTo(position: Long) = seekTo(position, PlaybackSeekSource.Other)

    override fun seekTo(
        position: Long,
        source: PlaybackSeekSource,
    ) {
        if (currentSong.value?.isLive == true) return
        if (nextStarted) cancelNextInternal()
        val target = position.coerceAtLeast(0L)
        recordSeek(exo, target, source)
        if (queueTrackChangePending && currentResource == null) {
            request(queue.currentSong(), target, resumePlayback = exo.playWhenReady)
            return
        }
        minimumPositionMs = target
        _currentPosition.value = target
        Timber.tag(MUSIC_VIDEO_TAG).d(
            "seek player=%s instance=%d videoId=%s fromMs=%d toMs=%d hasVideo=%s",
            playerId(),
            currentInstance,
            currentResource?.videoId,
            exo.currentPosition.coerceAtLeast(0L),
            target,
            currentResource?.hasVideo,
        )
        playbackReporter.onSeek(
            playbackInstanceId = currentInstance,
            fromPositionMs = exo.currentPosition.coerceAtLeast(0L),
            toPositionMs = target,
            continuePlaying = exo.playWhenReady,
        )
        recoveryReportingFloor = null
        activeAudioSource?.updatePlaybackPosition(target)
        activeVideoSource?.updatePlaybackPosition(target)
        exo.seekTo(target)
        maybeRecoverAudioOutput(userAction = AudioOutputUserAction.SEEK)
    }

    private fun recordSeek(
        player: ExoPlayer,
        targetMs: Long,
        source: PlaybackSeekSource,
        videoId: String? = currentResource?.videoId,
    ) {
        PlaybackEventLog.info(
            PlaybackEventKind.PLAYER,
            "Android seek requested.",
            videoId = videoId,
            details =
                source.logDetails +
                    mapOf(
                        "playbackInstanceId" to currentInstance.toString(),
                        "queueRequestId" to latestQueuePlaybackRequestId.toString(),
                        "playerId" to Integer.toHexString(System.identityHashCode(player)),
                        "playerRole" to if (player === exo) "primary" else "secondary",
                        "fromPositionMs" to player.currentPosition.coerceAtLeast(0L).toString(),
                        "toPositionMs" to targetMs.toString(),
                    ),
        )
    }

    override fun setVolume(level: Float) {
        _volume.value = level.coerceIn(0f, 1f)
        if (controllerClock == null) displayVolumeState.value = _volume.value
        applyPlaybackGains()
    }

    override fun setSleepTimerGain(gain: Float) {
        sleepTimerGain = gain.coerceIn(0f, 1f)
        applyPlaybackGains()
    }

    override fun applyAudioPreferences() {
        normalizationProcessors.values.forEach { it.refreshGain() }
        applyPlaybackGains()
    }

    override fun setAudioOutputEnabled(enabled: Boolean) {
        audioOutputEnabled = enabled
        applyPlaybackGains()
    }

    override fun playbackSpeed() = _playbackSpeed.value

    private fun currentTempoPitch(): PlaybackParameters {
        synchronized(tempoPitchLock) {
            return PlaybackParameters(_playbackSpeed.value, _playbackPitch.value)
        }
    }

    private fun applyTempoPitch(
        speed: Float,
        pitch: Float,
    ) {
        val normalizedSpeed = normalizePlaybackTempo(speed) ?: return
        val normalizedPitch = normalizePlaybackTempo(pitch) ?: return
        val parameters: PlaybackParameters
        synchronized(tempoPitchLock) {
            if (_playbackSpeed.value == normalizedSpeed && _playbackPitch.value == normalizedPitch) return
            _playbackSpeed.value = normalizedSpeed
            _playbackPitch.value = normalizedPitch
            parameters = PlaybackParameters(normalizedSpeed, normalizedPitch)
            setPlaybackParameters(exo, parameters)
            nextPlayer?.let { setPlaybackParameters(it, parameters) }
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        val normalizedSpeed = normalizePlaybackTempo(speed) ?: return
        synchronized(tempoPitchLock) {
            if (_playbackSpeed.value == normalizedSpeed) return
            _playbackSpeed.value = normalizedSpeed
            val parameters = PlaybackParameters(normalizedSpeed, _playbackPitch.value)
            setPlaybackParameters(exo, parameters)
            nextPlayer?.let { setPlaybackParameters(it, parameters) }
        }
    }

    override fun setPlaybackPitch(pitch: Float) {
        val normalizedPitch = normalizePlaybackTempo(pitch) ?: return
        synchronized(tempoPitchLock) {
            if (_playbackPitch.value == normalizedPitch) return
            _playbackPitch.value = normalizedPitch
            val parameters = PlaybackParameters(_playbackSpeed.value, normalizedPitch)
            setPlaybackParameters(exo, parameters)
            nextPlayer?.let { setPlaybackParameters(it, parameters) }
        }
    }

    override fun setTempoPitch(
        speed: Float,
        pitch: Float,
    ) = applyTempoPitch(speed, pitch)

    override fun setQueue(
        songs: List<SongItem>,
        startIndex: Int,
        startPlaying: Boolean,
        startPositionMs: Long,
        preparePlayback: Boolean,
        recommendationsSongsOnly: Boolean,
    ) {
        cancelNextInternal()
        queue.setQueue(songs, startIndex, recommendationsSongsOnly)
        if (preparePlayback) {
            request(queue.currentSong(), startPositionMs, resumePlayback = startPlaying)
        } else {
            val current = queue.currentSong()
            currentSongValue = current
            _currentSong.value = current
            _duration.value = current?.duration?.times(1000L) ?: 0L
            _currentPosition.value = startPositionMs.coerceAtLeast(0L)
        }
    }

    override fun queueSnapshot(): MusicQueueSnapshot = queue.snapshot()

    override fun restoreQueue(
        snapshot: MusicQueueSnapshot,
        currentTrackChanged: Boolean,
    ) {
        requestCounter++
        playbackTransitions.clear()
        queue.restore(snapshot)
        queueTrackChangePending = queueTrackChangePending || currentTrackChanged
    }

    override fun cancelPendingQueuePlayback() {
        cancelScreenOffFocusRecovery()
        requestCounter++
        outputRecoveryState = outputRecoveryState.cancelUserRecovery()
        playbackTransitions.clear()
        queueTrackChangePending = true
    }

    override val playbackState: Int
        get() = if (controllerClock != null) _playbackState.value else exo.playbackState

    override val isReady: Boolean
        get() = controllerClock?.playback?.ready ?: (exo.playbackState == Player.STATE_READY)

    override fun appendPlaylistSongs(songs: List<SongItem>) {
        queue.appendPage(songs)
        cancelNextIfQueueTargetChanged()
        prepareEnqueuedCurrent(queue) { song, resume -> request(song, resumePlayback = resume) }
    }

    override fun insertQueueItems(
        index: Int,
        songs: List<SongItem>,
    ) {
        queue.addToQueue(
            songs.map(SongItem::asManuallyAddedQueueItem),
            preventDuplicates = Settings.shared.preventDuplicateTracksInQueue,
            insertIndex = index,
        )
        cancelNextIfQueueTargetChanged()
        prepareEnqueuedCurrent(queue) { song, resume -> request(song, resumePlayback = resume) }
    }

    override fun addToQueue(
        song: SongItem,
        playNext: Boolean,
    ) = addToQueue(listOf(song), playNext)

    override fun addToQueue(
        songs: List<SongItem>,
        playNext: Boolean,
    ) {
        enqueueSongs(queue, songs, playNext, Settings.shared.preventDuplicateTracksInQueue)
        cancelNextIfQueueTargetChanged()
        prepareEnqueuedCurrent(queue) { song, resume -> request(song, resumePlayback = resume) }
    }

    override fun removeQueueItem(index: Int) {
        removeQueueItems(setOf(index))
    }

    override fun removeQueueItems(indices: Set<Int>): QueueRemoval? {
        val removal = queue.removeAt(indices) ?: return null
        cancelNextIfQueueTargetChanged()
        syncCurrentSong(removal.items.any { it.index == removal.previousCurrentIndex })
        return removal
    }

    override fun restoreQueueItems(removal: QueueRemoval) {
        queue.restore(removal)
        cancelNextIfQueueTargetChanged()
        syncCurrentSong()
    }

    private fun syncCurrentSong(
        stopIfEmpty: Boolean = false,
        resumePlayback: Boolean = exo.playWhenReady,
    ) {
        val current = queue.currentSong()
        if (current == null) {
            if (stopIfEmpty) stop()
            return
        }
        val pending = queuePlaybackRequestsState.replayCache.lastOrNull()
        if (shouldRequestQueueCurrent(_currentSong.value, current, pending?.song)) request(current, resumePlayback = resumePlayback)
    }

    override fun moveQueueItem(
        fromIndex: Int,
        toIndex: Int,
        updatedItem: SongItem?,
    ) {
        queue.move(fromIndex, toIndex, updatedItem)
        cancelNextIfQueueTargetChanged()
    }

    override fun reorderQueue(order: List<Int>) {
        queue.reorder(order)
        cancelNextIfQueueTargetChanged()
    }

    override fun updateQueueItems(updates: Map<Int, SongItem>) {
        queue.updateItems(updates)
        cancelNextIfQueueTargetChanged()
    }

    override fun replaceUpcomingQueue(songs: List<SongItem>) {
        queue.replaceUpcomingQueue(songs)
        cancelNextIfQueueTargetChanged()
    }

    override var onPreloadedPlaybackCommitted: ((SongItem, PlaybackResource) -> Unit)? = null
    override var consumePreloadedNext: (() -> Boolean)? = null
    override var startPreloadedNextAtEof: ((Long) -> Unit)? = null

    override fun skipToNext(resumePlayback: Boolean) {
        advanceWithPreloadedNext(
            ready = resumePlayback && wantsPlayback && !nextStarted && nextPlayer?.playbackState == Player.STATE_READY,
            consume = { consumePreloadedNext?.invoke() == true },
            advance = { request(queue.next(), resumePlayback = resumePlayback) },
        )
    }

    override fun skipToPrevious(resumePlayback: Boolean) {
        val isLive = currentSong.value?.isLive == true
        if (!isLive && shouldRestartCurrentItem(exo.currentPosition, queue.currentIndex.value)) {
            Timber.tag(MUSIC_VIDEO_TAG).d(
                "previous restarts current item instance=%d videoId=%s positionMs=%d queueIndex=%d",
                currentInstance,
                currentResource?.videoId,
                exo.currentPosition,
                queue.currentIndex.value,
            )
            seekTo(0L, PlaybackSeekSource("queue_restore", mapOf("reason" to "previous_restart")))
        } else {
            Timber.tag(MUSIC_VIDEO_TAG).d(
                "previous selects queue item instance=%d videoId=%s positionMs=%d queueIndex=%d",
                currentInstance,
                currentResource?.videoId,
                exo.currentPosition,
                queue.currentIndex.value,
            )
            request(queue.previous(), resumePlayback = resumePlayback)
        }
    }

    override fun skipToQueueItem(
        index: Int,
        positionMs: Long,
        resumePlayback: Boolean,
    ) {
        if (resumePlayback && wantsPlayback && positionMs == 0L && index == queue.currentIndex.value + 1) {
            skipToNext()
        } else {
            request(queue.skipTo(index), positionMs, resumePlayback)
        }
    }

    override fun toggleShuffle(preserveHistory: Boolean): Boolean {
        queue.toggleShuffle(preserveHistory, legacyShuffle = Settings.shared.useLegacyShuffle)
        cancelNextIfQueueTargetChanged()
        return queue.isShuffled
    }

    override fun restoreShuffleMode(enabled: Boolean) {
        queue.restoreShuffleMode(enabled)
        cancelNextIfQueueTargetChanged()
    }

    override fun setPreferredDevice(device: Any?) {
        preferredAudioDevice = device as? AudioDeviceInfo
        exo.setPreferredAudioDevice(preferredAudioDevice)
        nextPlayer?.setPreferredAudioDevice(preferredAudioDevice)
        applyAudioPreferences()
    }

    override fun getAudioSessionId() = exo.audioSessionId

    private fun getLiveSessionPositionMs(): Long {
        val now = SystemClock.elapsedRealtime()
        val start = liveSessionStartElapsedRealtimeMs
        return if (exo.isPlaying && start > 0L) {
            liveSessionAccumulatedMs + (now - start).coerceAtLeast(0L)
        } else {
            liveSessionAccumulatedMs
        }
    }

    override fun getRealtimePositionMs(): Long =
        controllerClock?.positionMs()
            ?: run {
                val isLive = currentResource?.isLive == true || currentSongValue?.isLive == true || exo.isCurrentMediaItemLive
                if (isLive) {
                    getLiveSessionPositionMs()
                } else if (currentInstance == 0L) {
                    _currentPosition.value
                } else {
                    maxOf(minimumPositionMs, exo.currentPosition.coerceAtLeast(0L)).also { minimumPositionMs = it }
                }
            }

    override fun release() {
        cancelScreenOffFocusRecovery()
        a2dpMonitor.close()
        requestCounter++
        playbackTransitions.clear()
        cancelNextInternal()
        progressJob?.cancel()
        val stoppedInstance = currentInstance
        val stoppedPosition = exo.currentPosition.coerceAtLeast(0L)
        Timber.tag(MUSIC_VIDEO_TAG).d(
            "release player=%s instance=%d videoId=%s positionMs=%d surface=%s",
            playerId(),
            stoppedInstance,
            currentResource?.videoId,
            stoppedPosition,
            surfaceName(activeVideoSurface),
        )
        currentInstance = 0L
        liveSessionStartElapsedRealtimeMs = 0L
        liveSessionAccumulatedMs = 0L
        recoveryReportingFloor = null
        sabrPlaybackCache.release(activeAudioSource)
        activeAudioSource?.closePlaybackSource()
        activeAudioSource = null
        activeVideoSource?.closePlaybackSource()
        activeVideoSource = null
        sabrPlaybackCache.clear()
        releasePlayer(exo)
        if (stoppedInstance > 0L) playbackReporter.onPlaybackStopped(stoppedInstance, stoppedPosition)
        val reporters = playbackReporters.toList()
        scope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(RELEASE_STATS_TIMEOUT_MS.milliseconds) {
                    reporters.forEach { it.awaitPendingPings() }
                }
            } finally {
                scope.cancel()
            }
        }
    }

    private fun scheduleScreenOffFocusRecovery(
        wasPlaybackRequested: Boolean,
        playWhenReady: Boolean,
        reason: Int,
    ) {
        cancelScreenOffFocusRecovery()
        if (!wasPlaybackRequested || playWhenReady || reason != Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) return
        val interruptedPlayer = exo
        val interruptedInstance = currentInstance
        val interruptedRequest = requestCounter
        val recovery =
            Runnable {
                pendingFocusRecovery = null
                if (exo !== interruptedPlayer || currentInstance != interruptedInstance ||
                    requestCounter != interruptedRequest
                ) {
                    return@Runnable
                }
                if (interruptedPlayer.playWhenReady) return@Runnable
                val audioManager = context.getSystemService(AudioManager::class.java)
                if (shouldRecoverPlaybackAfterScreenOffFocusLoss(
                        powerManager.isInteractive,
                        audioManager.mode,
                        isCasting(),
                        audioManager.isMusicActive,
                    )
                ) {
                    recordPlaybackState("Android screen-off audio focus recovery requested.")
                    interruptedPlayer.play()
                }
            }
        pendingFocusRecovery = recovery
        focusRecoveryHandler.postDelayed(recovery, SCREEN_OFF_FOCUS_RECOVERY_DELAY_MS)
    }

    private fun cancelScreenOffFocusRecovery() {
        pendingFocusRecovery?.let(focusRecoveryHandler::removeCallbacks)
        pendingFocusRecovery = null
    }

    private var activeVideoSurface: Any? = null

    override fun setVideoSurface(surface: Any?) {
        Timber.tag(MUSIC_VIDEO_TAG).d(
            "surface attach player=%s instance=%d videoId=%s old=%s new=%s",
            playerId(),
            currentInstance,
            currentResource?.videoId,
            surfaceName(activeVideoSurface),
            surfaceName(surface),
        )
        activeVideoSurface = surface
        recordPlaybackState("Android playback surface changed.")
        updateVideoProducer(activeVideoSource, surface != null)
        setVideoTrackEnabled(exo, surface != null)
        applyVideoSurface(exo, surface)
    }

    override fun clearVideoSurface(surface: Any?) {
        if (surface == null || activeVideoSurface === surface) {
            Timber.tag(MUSIC_VIDEO_TAG).d(
                "surface clear player=%s instance=%d videoId=%s requested=%s active=%s",
                playerId(),
                currentInstance,
                currentResource?.videoId,
                surfaceName(surface),
                surfaceName(activeVideoSurface),
            )
            activeVideoSurface = null
            updateVideoProducer(activeVideoSource, false)
            removeVideoSurface(exo, surface)
            setVideoTrackEnabled(exo, false)
        } else {
            Timber.tag(MUSIC_VIDEO_TAG).d(
                "surface clear ignored player=%s instance=%d requested=%s active=%s",
                playerId(),
                currentInstance,
                surfaceName(surface),
                surfaceName(activeVideoSurface),
            )
        }
    }

    override fun getVideoSize(): VideoSize = exo.videoSize

    override fun addPlayerListener(listener: Player.Listener) {
        forwardedPlayerListeners += listener
        exo.addListener(listener)
    }

    override fun removePlayerListener(listener: Player.Listener) {
        forwardedPlayerListeners -= listener
        exo.removeListener(listener)
    }

    private fun attachPlayerListeners(player: ExoPlayer) {
        _playbackState.value = player.playbackState
        playbackListener = createPlaybackListener(player)
        networkLoadListener = createNetworkLoadListener(player)
        offloadListener = createOffloadListener(player)
        player.addListener(playbackListener)
        player.addAnalyticsListener(networkLoadListener)
        player.addAudioOffloadListener(offloadListener)
        forwardedPlayerListeners.forEach(player::addListener)
        if (BuildConfig.DEBUG) player.addAnalyticsListener(eventLogger)
    }

    private fun detachPlayerListeners(player: ExoPlayer) {
        player.removeListener(playbackListener)
        player.removeAnalyticsListener(networkLoadListener)
        player.removeAudioOffloadListener(offloadListener)
        forwardedPlayerListeners.forEach(player::removeListener)
        if (BuildConfig.DEBUG) player.removeAnalyticsListener(eventLogger)
    }

    override fun retryPendingQueuePlayback(
        positionMs: Long,
        resumePlayback: Boolean,
    ): Boolean {
        if (playbackTransitions.recovering) return false
        pendingNetworkRecovery
            ?.takeIf {
                it.playbackInstanceId == currentInstance && it.videoId == queue.currentSong()?.id
            }?.let { failure ->
                minimumPositionMs = positionMs.coerceAtLeast(0L)
                recordSeek(exo, minimumPositionMs, PlaybackSeekSource("recovery", mapOf("reason" to "network_retry")))
                exo.seekTo(minimumPositionMs)
                exo.playWhenReady = resumePlayback
                emitPlaybackFailure(failure.copy(playbackPositionMs = positionMs, resumePlayback = resumePlayback))
                return true
            }
        val song =
            queuePlaybackSongToRetry(
                selectedSong = queue.currentSong(),
                loadedSong = currentSongValue,
                hasLoadedTrack = currentInstance != 0L && currentResource != null && exo.playbackState != Player.STATE_IDLE,
                trackChangePending = queueTrackChangePending,
            ) ?: return false
        request(song, positionMs, resumePlayback)
        return true
    }

    override fun setPendingPlaybackIntent(
        requestId: Long,
        resumePlayback: Boolean,
    ) {
        if (queueTrackChangePending) setPendingPlaybackIntent(exo, requestId, requestCounter, resumePlayback)
    }

    override fun finishPlaybackTransition(requestId: Long) {
        if (requestCounter == requestId) playbackTransitions.finishQueue()
    }

    override fun finishPlaybackRecovery(transitionId: Long) {
        playbackTransitions.finishRecovery(transitionId)
    }

    override fun finishInterruptedPlayback(
        playbackInstanceId: Long,
        positionMs: Long,
    ) {
        if (currentInstance == playbackInstanceId) {
            playbackReporter.onPlaybackStopped(playbackInstanceId, positionMs)
            exo.stop()
        }
    }

    override suspend fun prepareNextTrack(
        resource: PlaybackResource,
        expectedCurrentPlaybackInstanceId: Long,
        expectedCurrentVideoId: String?,
        startPaused: Boolean,
        queueSongId: String,
        positionMs: Long,
        onFailure: (Throwable) -> Unit,
    ): AndroidPlaybackPlayer.PlaybackPreloadToken? =
        withContext(Dispatchers.Main.immediate) {
            if (
                currentInstance != expectedCurrentPlaybackInstanceId ||
                currentResource?.videoId != expectedCurrentVideoId
            ) {
                return@withContext null
            }
            PlaybackEventLog.info(
                PlaybackEventKind.PLAYER,
                "Android next player preparation started.",
                videoId = resource.videoId,
                details =
                    mapOf(
                        "queueSongId" to queueSongId,
                        "audioVideoId" to (resource.sabrBootstrap?.videoId ?: resource.musicVideoId ?: resource.videoId),
                        "currentPlaybackInstanceId" to expectedCurrentPlaybackInstanceId.toString(),
                        "playbackPositionMs" to positionMs.toString(),
                    ),
            )
            cancelNextInternal(preserveTransitionGain = !nextStarted && transitionGain < 1f)
            val candidate =
                createPlayer(
                    bufferForPlaybackMs = SWITCH_READY_BUFFER_MS.toInt(),
                    role = AndroidPlaybackPlayerRole.SECONDARY,
                )
            normalizationProcessors[candidate]?.resource = resource
            var candidateAudioSource: SeekableMediaSource? = null
            var candidateVideoSource: SeekableMediaSource? = null
            val generation = ++nextGeneration
            val loadFailure = CompletableDeferred<Throwable>()
            var preparing = true

            fun reportFailure(error: Throwable) {
                if (generation != nextGeneration || (!preparing && nextPlayer !== candidate)) return
                if (!loadFailure.complete(error)) return
                onFailure(error)
                if (nextPlayer === candidate) cancelNextInternal()
            }
            try {
                candidate.addAnalyticsListener(
                    object : AnalyticsListener {
                        override fun onLoadError(
                            eventTime: AnalyticsListener.EventTime,
                            loadEventInfo: LoadEventInfo,
                            mediaLoadData: MediaLoadData,
                            error: IOException,
                            wasCanceled: Boolean,
                        ) {
                            // A paused preload may never drain its buffer and surface onPlayerError.
                            if (wasCanceled) reportFailure(error)
                        }
                    },
                )
                candidate.volume = 0f
                setPlaybackParameters(candidate, currentTempoPitch())
                candidate.setPreferredAudioDevice(preferredAudioDevice)
                setVideoTrackEnabled(
                    candidate,
                    shouldEnableVideoTrack(
                        hasVideo = resource.hasVideo,
                        preserveWarmTrack = true,
                        hasSurface = activeVideoSurface != null,
                    ),
                )
                val mediaSource =
                    buildSource(
                        resource = resource,
                        onAudioSourceOpened = { candidateAudioSource = it },
                        onVideoSourceOpened = { candidateVideoSource = it },
                    )
                val preparation =
                    withTimeoutOrNull(PREPARATION_TIMEOUT_MS.milliseconds) {
                        when (
                            val ready =
                                coroutineScope {
                                    val preparation =
                                        async {
                                            awaitPlayerReady(candidate) {
                                                val initialPositionMs = positionMs.coerceAtLeast(0L)
                                                candidateAudioSource?.updatePlaybackPosition(initialPositionMs)
                                                candidateVideoSource?.updatePlaybackPosition(initialPositionMs)
                                                recordSeek(
                                                    candidate,
                                                    initialPositionMs,
                                                    PlaybackSeekSource(
                                                        "queue_restore",
                                                        mapOf("reason" to "preload"),
                                                    ),
                                                    resource.videoId,
                                                )
                                                candidate.setMediaSource(mediaSource, initialPositionMs)
                                                candidate.prepare()
                                            }
                                        }
                                    try {
                                        select {
                                            preparation.onAwait { it }
                                            loadFailure.onAwait { MediaPreparationResult.Failed(it) }
                                        }
                                    } finally {
                                        preparation.cancel()
                                    }
                                }
                        ) {
                            is MediaPreparationResult.Failed -> {
                                ready
                            }

                            MediaPreparationResult.Ready -> {
                                while (
                                    candidate.playbackState == Player.STATE_READY && !loadFailure.isCompleted &&
                                    !hasSwitchBuffer(candidate.bufferedPosition, candidate.currentPosition, candidate.duration)
                                ) {
                                    delay(50L.milliseconds)
                                }
                                if (loadFailure.isCompleted) {
                                    MediaPreparationResult.Failed(loadFailure.await())
                                } else if (candidate.playerError != null) {
                                    MediaPreparationResult.Failed(candidate.playerError!!)
                                } else {
                                    MediaPreparationResult.Ready
                                }
                            }
                        }
                    } ?: MediaPreparationResult.Failed(IOException("Media3 next-track preparation timed out"))
                if (preparation is MediaPreparationResult.Failed) {
                    reportFailure(preparation.error)
                    candidateAudioSource?.closePlaybackSource()
                    candidateVideoSource?.closePlaybackSource()
                    releasePlayer(candidate)
                    return@withContext null
                }
                if (
                    generation != nextGeneration ||
                    currentInstance != expectedCurrentPlaybackInstanceId ||
                    currentResource?.videoId != expectedCurrentVideoId
                ) {
                    candidateAudioSource?.closePlaybackSource()
                    candidateVideoSource?.closePlaybackSource()
                    releasePlayer(candidate)
                    return@withContext null
                }
                val token =
                    AndroidPlaybackPlayer.PlaybackPreloadToken(
                        generation = generation,
                        playerInstanceId = instanceCounter.incrementAndGet(),
                        videoId = resource.videoId,
                        queueSongId = queueSongId,
                    )
                playerInstanceIds[candidate] = token.playerInstanceId
                val reporter = YouTubePlaybackReporter.create(scope, GlobalYouTubeRepository.instance)
                val listener =
                    object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (controllerClock != null) return
                            if (!isCurrentNext(candidate, token)) return
                            nextProgressJob?.cancel()
                            reportNextPlaybackSample(candidate, token, resource, reporter)
                            if (isPlaying) {
                                nextProgressJob =
                                    scope.launch {
                                        while (isCurrentNext(candidate, token) && candidate.isPlaying) {
                                            delay(STATE_UPDATE_INTERVAL_MS.milliseconds)
                                            reportNextPlaybackSample(candidate, token, resource, reporter)
                                        }
                                    }
                            }
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (!isCurrentNext(candidate, token) || state != Player.STATE_ENDED) return
                            nextFailed = true
                            reporter.onPlaybackEnded(token.playerInstanceId, candidate.currentPosition.coerceAtLeast(0L))
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (!isCurrentNext(candidate, token)) return
                            nextFailed = true
                            reporter.onPlaybackInterrupted(token.playerInstanceId, candidate.currentPosition.coerceAtLeast(0L))
                            Timber.tag(MUSIC_VIDEO_TAG).e(
                                "next-track playback failed videoId=%s code=%d type=%s",
                                resource.videoId,
                                error.errorCode,
                                error::class.simpleName,
                            )
                            reportFailure(error)
                        }
                    }
                playbackReporters += reporter
                setPlaybackParameters(candidate, currentTempoPitch())
                candidate.setPreferredAudioDevice(preferredAudioDevice)
                nextPlayer = candidate
                nextAudioSource = candidateAudioSource
                nextVideoSource = candidateVideoSource
                setVideoTrackEnabled(candidate, false)
                (candidateVideoSource as? SabrInputStream)?.suspendProducer()
                nextResource = resource
                updateOffloadPreferences(candidate)
                nextToken = token
                nextExpectedCurrentInstanceId = expectedCurrentPlaybackInstanceId
                nextExpectedCurrentVideoId = expectedCurrentVideoId
                nextStarted = false
                nextFailed = false
                nextPlaybackReporter = reporter
                nextPlayerListener = listener
                preparing = false
                candidate.addListener(listener)
                nextStarted = !startPaused
                syncNextPlayback()
                token
            } catch (error: CancellationException) {
                candidateAudioSource?.closePlaybackSource()
                candidateVideoSource?.closePlaybackSource()
                releasePlayer(candidate)
                throw error
            } catch (error: Exception) {
                reportFailure(error)
                candidateAudioSource?.closePlaybackSource()
                candidateVideoSource?.closePlaybackSource()
                releasePlayer(candidate)
                Timber.tag(MUSIC_VIDEO_TAG).w(
                    "next-track prepare failed videoId=%s type=%s",
                    resource.videoId,
                    error::class.simpleName,
                )
                null
            }
        }

    override fun swapToPreloadedQueueNext(
        token: AndroidPlaybackPlayer.PlaybackPreloadToken,
        resource: PlaybackResource,
        song: SongItem?,
        transitionGain: Float,
    ): Boolean = swapToPreloaded(token, resource, song, transitionGain)

    override fun cancelNext() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cancelNextInternal()
        } else {
            scope.launch { cancelNextInternal() }
        }
    }

    override fun setTransitionVolume(level: Float) {
        transitionGain = (level / 100f).coerceIn(0f, 1f)
        applyPlaybackGains()
    }

    override fun setNextVolume(level: Float) {
        nextTransitionGain = (level / 100f).coerceIn(0f, 1f)
        applyPlaybackGains()
    }

    private fun setPlaybackParameters(
        player: ExoPlayer,
        parameters: PlaybackParameters,
    ) {
        if (parameters.speed == 1f && parameters.pitch == 1f) {
            player.playbackParameters = parameters
            updateOffloadPreferences(player, parameters)
        } else {
            updateOffloadPreferences(player, parameters)
            player.playbackParameters = parameters
        }
    }

    private fun updateOffloadPreferences(
        player: ExoPlayer,
        parameters: PlaybackParameters = player.playbackParameters,
    ) {
        val resource =
            if (player === exo) {
                currentResource
            } else if (player === nextPlayer) {
                nextResource
            } else {
                null
            }
        val normalizationGain = normalizationProcessors[player]?.normalizationGain
        val canOffload =
            player === exo && normalizationGain != null &&
                AudioOffloadEligibility(
                    equalizer = EqualizerProfiles.configuration,
                    normalizationGain = normalizationGain,
                    skipSilence = Settings.shared.skipSilence,
                    speed = parameters.speed,
                    pitch = parameters.pitch,
                    crossfade = Settings.shared.crossfadeEnabled,
                    meterActive = AudioSpectrumMeter.hasConsumers.value,
                    audioResourceAvailable = resource != null,
                    outputFailed = offloadFailed,
                ).canOffload
        val mode =
            if (canOffload) AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        val current = player.trackSelectionParameters.audioOffloadPreferences
        if (current.audioOffloadMode == mode && (!canOffload || current.isGaplessSupportRequired)) return
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(
                    AudioOffloadPreferences
                        .Builder()
                        .setAudioOffloadMode(mode)
                        .setIsGaplessSupportRequired(true)
                        .build(),
                ).build()
    }

    override fun setCrossfadeVolumes(
        outgoingLevel: Float,
        incomingLevel: Float,
    ) {
        transitionGain = (outgoingLevel / 100f).coerceIn(0f, 1f)
        nextTransitionGain = (incomingLevel / 100f).coerceIn(0f, 1f)
        applyPlaybackGains()
    }

    private fun applyPlaybackGains() {
        normalizationProcessors[exo]?.let {
            it.resource = currentResource
        }
        nextPlayer?.let { player ->
            normalizationProcessors[player]?.let {
                it.resource = nextResource
            }
        }
        updateOffloadPreferences(exo)
        nextPlayer?.let { updateOffloadPreferences(it) }
        // Output gain also covers PCM already buffered by a paused preload.
        exo.volume =
            playbackOutputGain(
                effectivePlaybackVolume(_volume.value, audioOutputEnabled),
                sleepTimerGain,
                transitionGain,
                (normalizationProcessors[exo]?.normalizationGain ?: 1f).coerceAtMost(1f),
            )
        nextPlayer?.let { player ->
            player.volume =
                playbackOutputGain(
                    effectivePlaybackVolume(_volume.value, audioOutputEnabled),
                    sleepTimerGain,
                    nextTransitionGain,
                    (normalizationProcessors[player]?.normalizationGain ?: 1f).coerceAtMost(1f),
                )
        }
    }

    private fun PlaybackResource?.normalizationGain(): Float =
        playbackNormalizationGain(
            loudnessDb = this?.loudnessDb,
            enabled = Settings.shared.audioNormalization,
            level = Settings.shared.loudnessLevel,
            perceptualLoudnessDb = this?.perceptualLoudnessDb,
        )

    override fun startNextPlayback(): Boolean {
        if (playbackTransitions.recovering) return false
        val candidate = nextPlayer ?: return false
        if (nextFailed || candidate.playerError != null || candidate.playbackState == Player.STATE_ENDED) return false
        nextStarted = true
        applyPlaybackGains()
        syncNextPlayback()
        return true
    }

    override fun holdNextPlaybackAtStart() {
        val candidate = nextPlayer ?: return
        if (!nextStarted) return
        candidate.pause()
        recordSeek(candidate, 0L, PlaybackSeekSource("replacement", mapOf("reason" to "crossfade_hold")))
        candidate.seekTo(0L)
        nextHeldAtStart = true
    }

    override fun resumeNextPlayback() {
        if (nextStarted) {
            nextHeldAtStart = false
            syncNextPlayback()
        }
    }

    private fun syncNextPlayback() {
        nextPlayer?.playWhenReady =
            exo.playWhenReady && nextStarted && !nextHeldAtStart &&
            exo.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
    }

    override fun isPreloadedPlaybackActive(token: AndroidPlaybackPlayer.PlaybackPreloadToken): Boolean {
        val candidate = nextPlayer ?: return false
        return token == nextToken &&
            !nextFailed &&
            candidate.playerError == null &&
            candidate.playbackState != Player.STATE_ENDED
    }

    private fun isCurrentNext(
        candidate: ExoPlayer,
        token: AndroidPlaybackPlayer.PlaybackPreloadToken,
    ): Boolean = candidate === nextPlayer && token == nextToken

    private fun reportNextPlaybackSample(
        candidate: ExoPlayer,
        token: AndroidPlaybackPlayer.PlaybackPreloadToken,
        resource: PlaybackResource,
        reporter: YouTubePlaybackReporter,
    ) {
        if (!isCurrentNext(candidate, token)) return
        reporter.onPlaybackSample(
            playbackInstanceId = token.playerInstanceId,
            resource = resource,
            positionMs = candidate.currentPosition.coerceAtLeast(0L),
            isRendering = candidate.isPlaying,
            wantsPlayback = candidate.playWhenReady,
            playbackSpeed = _playbackSpeed.value,
        )
    }

    private fun retirePlaybackReporter(reporter: YouTubePlaybackReporter) {
        scope.launch {
            reporter.awaitPendingPings()
            playbackReporters -= reporter
        }
    }

    private fun cancelNextIfQueueTargetChanged() {
        if (isPreloadedQueueTargetChanged(nextToken, queue.peekNext()?.id)) cancelNextInternal()
    }

    private fun cancelNextInternal(preserveTransitionGain: Boolean = false) {
        nextGeneration++
        val candidate = nextPlayer
        val token = nextToken
        val reporter = nextPlaybackReporter
        val listener = nextPlayerListener
        val audioSource = nextAudioSource
        val videoSource = nextVideoSource
        nextProgressJob?.cancel()
        nextProgressJob = null
        nextPlayerListener = null
        nextPlayer = null
        nextAudioSource = null
        nextVideoSource = null
        nextResource = null
        nextToken = null
        nextExpectedCurrentInstanceId = 0L
        nextExpectedCurrentVideoId = null
        nextStarted = false
        nextHeldAtStart = false
        nextFailed = false
        nextPlaybackReporter = null
        cancelSecondaryPlayback(
            detachPlayer = { listener?.let { candidate?.removeListener(it) } },
            closeSources = {
                audioSource?.closePlaybackSource()
                videoSource?.closePlaybackSource()
            },
            stopReporting = {
                if (token != null && reporter != null && candidate != null) {
                    reporter.onPlaybackStopped(token.playerInstanceId, candidate.currentPosition.coerceAtLeast(0L))
                    retirePlaybackReporter(reporter)
                }
            },
            releasePlayer = { candidate?.let(::releasePlayer) },
        )
        if (!preserveTransitionGain) transitionGain = 1f
        nextTransitionGain = 0f
        applyPlaybackGains()
    }

    private fun swapToPreloaded(
        token: AndroidPlaybackPlayer.PlaybackPreloadToken,
        resource: PlaybackResource,
        playbackSong: SongItem?,
        initialTransitionGain: Float,
    ): Boolean {
        val candidate = nextPlayer ?: return false
        if (
            token != nextToken ||
            resource != nextResource ||
            currentInstance != nextExpectedCurrentInstanceId ||
            currentResource?.videoId != nextExpectedCurrentVideoId ||
            nextFailed ||
            candidate.playerError != null ||
            candidate.playbackState == Player.STATE_ENDED
        ) {
            return false
        }
        val queueSong = queue.peekNext()?.takeIf { it.id == token.queueSongId } ?: return false
        val song = playbackSong ?: queueSong
        if (song.id != resource.videoId) return false
        val incomingReporter = nextPlaybackReporter ?: return false
        val previous = exo
        val previousReporter = playbackReporter
        val previousInstance = currentInstance
        val previousPosition = previous.currentPosition.coerceAtLeast(0L)
        val shouldPlay = previous.playWhenReady
        removeVideoSurface(previous, activeVideoSurface)
        detachPlayerListeners(previous)
        nextProgressJob?.cancel()
        nextProgressJob = null
        nextPlayerListener?.let(candidate::removeListener)
        nextPlayerListener = null
        exo = candidate
        sabrPlaybackCache.release(activeAudioSource)
        activeAudioSource?.closePlaybackSource()
        activeVideoSource?.closePlaybackSource()
        activeAudioSource = nextAudioSource
        activeVideoSource = nextVideoSource
        updateVideoProducer(activeVideoSource, activeVideoSurface != null)
        nextPlayer = null
        nextAudioSource = null
        nextVideoSource = null
        nextResource = null
        nextToken = null
        nextExpectedCurrentInstanceId = 0L
        nextExpectedCurrentVideoId = null
        nextStarted = false
        nextHeldAtStart = false
        nextFailed = false
        nextPlaybackReporter = null
        currentResource = resource
        _currentPlaybackResource.value = resource
        currentInstance = token.playerInstanceId
        playbackReporter = incomingReporter
        eofInstance = currentInstance
        lastEofInstance = 0L
        minimumPositionMs = candidate.currentPosition.coerceAtLeast(0L)
        _currentPosition.value = minimumPositionMs
        _bufferedPosition.value = candidate.bufferedPosition.coerceAtLeast(0L)
        val effectiveSong =
            if (resource.isLive && !song.isLive) {
                song.copy(musicVideoType = MUSIC_VIDEO_TYPE_LIVE)
            } else {
                song
            }
        _duration.value =
            playbackDurationMs(
                backendDurationMs = candidate.duration,
                selectedSong = effectiveSong,
                playbackPrepared = true,
                isLive = resource.isLive || effectiveSong.isLive || candidate.isCurrentMediaItemLive,
            )
        attachPlayerListeners(candidate)
        setVideoTrackEnabled(candidate, activeVideoSurface != null)
        applyVideoSurface(candidate, activeVideoSurface)
        transitionGain = initialTransitionGain.coerceIn(0f, 1f)
        nextTransitionGain = 0f
        transferPlaybackAudioFocus(
            retirePrimary = {
                previous.playWhenReady = false
                releasePlayer(previous)
            },
            enablePromotedPlayer = {
                applyPlaybackGains()
                candidate.setAudioAttributes(playbackAudioAttributes, true)
                candidate.playWhenReady = shouldPlay
            },
        )
        _isPlaying.value = candidate.isPlaying
        queue.next()
        if (effectiveSong.id != queueSong.id || (effectiveSong.isLive && !queueSong.isLive)) queue.replaceCurrent(effectiveSong)
        sabrPlaybackCache.start(effectiveSong.id, resource, activeAudioSource, candidate.isPlaying, newPlayback = true)
        onPreloadedPlaybackCommitted?.invoke(effectiveSong, resource)
        currentSongValue = effectiveSong
        _currentSong.value = effectiveSong
        _loadError.value = null
        PlaybackEventLog.info(
            PlaybackEventKind.PLAYER,
            "Android next player promoted to main.",
            videoId = resource.videoId,
            details =
                mapOf(
                    "queueSongId" to effectiveSong.id,
                    "playbackInstanceId" to currentInstance.toString(),
                    "playbackPositionMs" to getRealtimePositionMs().toString(),
                    "playWhenReady" to candidate.playWhenReady.toString(),
                ),
        )
        if (previousInstance > 0L) previousReporter.onPlaybackStopped(previousInstance, previousPosition)
        retirePlaybackReporter(previousReporter)
        return true
    }

    override fun invalidateActiveStreamForNetworkChange() {
        if (!shouldRecoverPlaybackOnNetworkChange(currentResource, exo.playerError != null, exo.playWhenReady)) return
        exo.playerError?.let(::reportPlaybackFailure)
    }

    override suspend fun refreshPlaybackResource(wantVideo: Boolean): Boolean {
        val song = currentSongValue ?: return false
        val resource = currentResource ?: return false
        val instance = currentInstance
        val resolver = GlobalYouTubeRepository.streamResolver ?: return false
        return try {
            val refreshed =
                resolver.refresh(
                    resource.videoId,
                    song.toAndroidContentHints(wantVideo = wantVideo),
                    sourceSong = song,
                )
            if (currentInstance != instance || currentSongValue?.id != song.id || currentResource !== resource) {
                false
            } else {
                closeActiveVideoSource()
                currentResource = refreshed
                _currentPlaybackResource.value = refreshed
                applyPlaybackGains()
                true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    override fun createSabrVideoSource(resource: PlaybackResource): SeekableMediaSource? {
        closeActiveVideoSource()
        return resource.sabrVideoBootstrap?.let { bootstrap ->
            SabrInputStream(
                mediaSize = bootstrap.selectedVideoContentLengthBytes ?: resource.videoContentLengthBytes,
                streamFactory = { initial, position ->
                    SabrVideoStream(
                        httpClient = GlobalYouTube.client,
                        bootstrap = bootstrap,
                        playbackPositionMs = position,
                        initialPlayerTimeMs = initial,
                        logger = MetrolistInnerTubeLogger,
                    ).chunks()
                },
                tempDirectory = context.cacheDir,
            ).also { activeVideoSource = it }
        }
    }

    override suspend fun prepareAndPlay(
        song: SongItem,
        resource: PlaybackResource,
        positionMs: Long,
        resumePlayback: Boolean,
        shouldCommit: () -> Boolean,
        onPrepareFailure: (Throwable?) -> Unit,
        recoveryPlaybackInstanceId: Long?,
        replaceCurrentQueueItem: Boolean,
        positionMsProvider: (() -> Long)?,
        playWhenReadyProvider: (() -> Boolean)?,
        replaceInForeground: Boolean,
        freshAudioSession: Boolean,
        stageQueueReplacement: Boolean,
        seekSource: PlaybackSeekSource?,
    ): Boolean =
        withContext(Dispatchers.Main.immediate) {
            Timber.tag(MUSIC_VIDEO_TAG).d(
                "prepare requested player=%s videoId=%s positionMs=%d resume=%s recoveryInstance=%s replaceQueueItem=%s %s",
                playerId(),
                resource.videoId,
                positionMs,
                resumePlayback,
                recoveryPlaybackInstanceId,
                replaceCurrentQueueItem,
                resourceSummary(resource),
            )
            val preparedResource =
                if (recoveryPlaybackInstanceId == null) {
                    resource
                } else {
                    if (
                        recoveryPlaybackInstanceId != currentInstance ||
                        currentResource?.videoId != resource.videoId
                    ) {
                        Timber.tag(MUSIC_VIDEO_TAG).d(
                            "prepare rejected stale recovery requestedInstance=%s currentInstance=%d requestedVideoId=%s currentVideoId=%s",
                            recoveryPlaybackInstanceId,
                            currentInstance,
                            resource.videoId,
                            currentResource?.videoId,
                        )
                        return@withContext false
                    }
                    currentResource
                        ?.playbackTracking
                        ?.clientPlaybackNonce
                        ?.let(resource::withClientPlaybackNonce)
                        ?: resource
                }
            if (!shouldCommit()) {
                Timber.tag(MUSIC_VIDEO_TAG).d(
                    "prepare discarded before commit videoId=%s currentInstance=%d recoveryInstance=%s",
                    preparedResource.videoId,
                    currentInstance,
                    recoveryPlaybackInstanceId,
                )
                return@withContext false
            }
            cancelNextInternal()

            val previousPlayer = exo
            val queueSongAtPreparation = queue.currentSong()?.let(SongMetadataState::apply)
            val previousAudioSource = activeAudioSource
            val previousVideoSource = activeVideoSource
            val previousInstance = currentInstance
            val previousPosition = previousPlayer.currentPosition.coerceAtLeast(0L)
            val prepareInBackground =
                stageQueueReplacement || (previousInstance > 0L && (freshAudioSession || !replaceInForeground) && !queueTrackChangePending)
            val preparationSeekSource =
                seekSource ?: PlaybackSeekSource(
                    if (recoveryPlaybackInstanceId != null) "recovery" else "queue_restore",
                    mapOf("reason" to if (freshAudioSession) "fresh_audio_session" else "prepare"),
                )
            val initialTargetPosition = if (recoveryPlaybackInstanceId != null) getRealtimePositionMs() else positionMs.coerceAtLeast(0L)

            val targetPlaying =
                playbackIntentForPreparation(
                    player = previousPlayer,
                    resumePlayback = resumePlayback,
                    preserveCurrentIntent = prepareInBackground || queueTrackChangePending || recoveryPlaybackInstanceId != null,
                    playWhenReadyProvider = playWhenReadyProvider,
                )

            fun warmingPlayback() = targetPlaying() && previousPlayer.isPlaying

            fun currentTargetPosition(): Long =
                if (recoveryPlaybackInstanceId != null) {
                    getRealtimePositionMs()
                } else {
                    positionMsProvider?.invoke()?.coerceAtLeast(0L)
                        ?: catchUpPlaybackPosition(
                            initialTargetPosition,
                            previousPosition,
                            previousPlayer.currentPosition,
                        )
                }
            val stalledSourceReplacement = recoveryPlaybackInstanceId != null && !previousPlayer.isPlaying
            var nextAudioSource: SeekableMediaSource? = null
            var nextVideoSource: SeekableMediaSource? = null
            Timber.tag(MUSIC_VIDEO_TAG).d(
                "media source building videoId=%s hasVideo=%s directVideo=%s sabrVideo=%s background=%s",
                preparedResource.videoId,
                preparedResource.hasVideo,
                preparedResource.hasDirectVideo,
                preparedResource.hasSabrVideo,
                prepareInBackground,
            )
            val preparationDetails =
                preparationSeekSource.logDetails +
                    mapOf(
                        "queueSongId" to (queueSongAtPreparation?.id ?: "none"),
                        "targetSongId" to song.id,
                        "audioVideoId" to (
                            preparedResource.sabrBootstrap?.videoId ?: preparedResource.musicVideoId ?: preparedResource.videoId
                        ),
                        "previousResourceId" to (currentResource?.videoId ?: "none"),
                        "previousPlaybackInstanceId" to previousInstance.toString(),
                        "queueRequestId" to latestQueuePlaybackRequestId.toString(),
                        "recoveryPlaybackInstanceId" to (recoveryPlaybackInstanceId?.toString() ?: "none"),
                        "replaceCurrentQueueItem" to replaceCurrentQueueItem.toString(),
                        "prepareInBackground" to prepareInBackground.toString(),
                        "freshAudioSession" to freshAudioSession.toString(),
                        "hasVideo" to preparedResource.hasVideo.toString(),
                    )
            PlaybackEventLog.info(
                PlaybackEventKind.PLAYER,
                "Android main player preparation started.",
                videoId = preparedResource.videoId,
                clientName = preparedResource.clientName,
                profileId = preparedResource.profileId,
                details = preparationDetails + mapOf("playbackPositionMs" to initialTargetPosition.toString()),
            )
            val cachedAudioSource =
                (previousAudioSource as? SabrInputStream)?.takeIf {
                    recoveryPlaybackInstanceId == null && currentResource?.hasSameSabrAudio(preparedResource) == true
                }
            val mediaSource =
                buildSource(
                    resource = preparedResource,
                    onAudioSourceOpened = { source -> nextAudioSource = source },
                    onVideoSourceOpened = { source -> nextVideoSource = source },
                    deferAudioStart = cachedAudioSource != null,
                )
            nextAudioSource?.updatePlaybackPosition(initialTargetPosition)
            nextVideoSource?.updatePlaybackPosition(initialTargetPosition)
            try {
                if (cachedAudioSource != null) {
                    withContext(Dispatchers.IO) {
                        (nextAudioSource as? SabrInputStream)?.shareCachedDataFrom(cachedAudioSource)
                    }
                }
            } catch (error: Throwable) {
                nextAudioSource?.closePlaybackSource()
                nextVideoSource?.closePlaybackSource()
                throw error
            }

            if (
                !isCurrentPreparedQueueReplacement(
                    requestIsCurrent = shouldCommit(),
                    playerIsCurrent = exo === previousPlayer,
                    expectedPlaybackInstanceId = previousInstance,
                    currentPlaybackInstanceId = currentInstance,
                )
            ) {
                nextAudioSource?.closePlaybackSource()
                nextVideoSource?.closePlaybackSource()
                return@withContext false
            }
            val candidate =
                if (prepareInBackground) {
                    createPlayer(
                        bufferForPlaybackMs = SWITCH_READY_BUFFER_MS.toInt(),
                        role = AndroidPlaybackPlayerRole.SECONDARY,
                    )
                } else {
                    previousPlayer
                }
            normalizationProcessors[candidate]?.resource = preparedResource
            setVideoTrackEnabled(
                candidate,
                shouldEnableVideoTrack(
                    hasVideo = preparedResource.hasVideo,
                    preserveWarmTrack = prepareInBackground,
                    hasSurface = activeVideoSurface != null,
                ),
            )
            var sourceCommitted = false
            var sourceSeekPending = false
            val sourceSeekListener =
                object : Player.Listener {
                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int,
                    ) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                            sourceSeekPending = true
                        }
                    }
                }
            if (prepareInBackground) previousPlayer.addListener(sourceSeekListener)
            try {
                if (prepareInBackground) {
                    candidate.volume = 0f
                    setPlaybackParameters(candidate, currentTempoPitch())
                    candidate.setPreferredAudioDevice(preferredAudioDevice)
                    candidate.playWhenReady = !stalledSourceReplacement && warmingPlayback()
                    val preparation =
                        if (stalledSourceReplacement) {
                            prepareStreamReplacement(candidate) {
                                recordSeek(candidate, initialTargetPosition, preparationSeekSource, preparedResource.videoId)
                                candidate.setMediaSource(mediaSource, initialTargetPosition)
                                candidate.prepare()
                            }
                        } else {
                            withTimeoutOrNull(PREPARATION_TIMEOUT_MS.milliseconds) {
                                when (
                                    val ready =
                                        awaitPlayerReady(candidate) {
                                            recordSeek(candidate, initialTargetPosition, preparationSeekSource, preparedResource.videoId)
                                            candidate.setMediaSource(mediaSource, initialTargetPosition)
                                            candidate.prepare()
                                        }
                                ) {
                                    is MediaPreparationResult.Failed -> {
                                        ready
                                    }

                                    MediaPreparationResult.Ready -> {
                                        var retargets = 0
                                        var catchUpUsed = false
                                        var playoutCheckpoint: AudioPlayoutTracker.Checkpoint? = null
                                        val playoutTracker = checkNotNull(silenceSupport[candidate]).playoutTracker
                                        while (shouldCommit() && exo === previousPlayer) {
                                            val liveTargetPosition = currentTargetPosition()
                                            candidate.playerError?.let {
                                                return@withTimeoutOrNull MediaPreparationResult.Failed(it)
                                            }
                                            when (
                                                warmReplacementAction(
                                                    targetPositionMs = liveTargetPosition,
                                                    replacementPositionMs = candidate.currentPosition,
                                                    sourcePlaying = warmingPlayback(),
                                                    candidateReady = candidate.playbackState == Player.STATE_READY,
                                                    candidatePlaying = candidate.isPlaying,
                                                    buffered =
                                                        hasSwitchBuffer(
                                                            candidate.bufferedPosition,
                                                            candidate.currentPosition,
                                                            candidate.duration,
                                                        ),
                                                    retargets = retargets,
                                                    sourceSeekPending = sourceSeekPending,
                                                    preservePosition = recoveryPlaybackInstanceId != null,
                                                    catchUpAudioDrained = playoutCheckpoint?.let(playoutTracker::hasPlayed) != false,
                                                )
                                            ) {
                                                WarmReplacementAction.RETARGET -> {
                                                    candidate.playWhenReady = warmingPlayback()
                                                    setPlaybackParameters(candidate, currentTempoPitch())
                                                    retargets++
                                                    sourceSeekPending = false
                                                    Timber.tag(MUSIC_VIDEO_TAG).d(
                                                        "replacement retarget videoId=%s fromMs=%d toMs=%d bufferedMs=%d",
                                                        preparedResource.videoId,
                                                        candidate.currentPosition,
                                                        liveTargetPosition,
                                                        candidate.bufferedPosition,
                                                    )
                                                    nextAudioSource?.updatePlaybackPosition(liveTargetPosition)
                                                    nextVideoSource?.updatePlaybackPosition(liveTargetPosition)
                                                    recordSeek(
                                                        candidate,
                                                        liveTargetPosition,
                                                        preparationSeekSource,
                                                        preparedResource.videoId,
                                                    )
                                                    candidate.seekTo(liveTargetPosition)
                                                }

                                                WarmReplacementAction.FAILED -> {
                                                    return@withTimeoutOrNull MediaPreparationResult.Failed(
                                                        IOException("Media3 replacement did not converge"),
                                                    )
                                                }

                                                WarmReplacementAction.CATCH_UP -> {
                                                    catchUpUsed = true
                                                    candidate.playWhenReady = true
                                                    val snapshot = currentTempoPitch()
                                                    val catchUpSpeed = warmReplacementCatchUpSpeed(snapshot.speed)
                                                    if (candidate.playbackParameters.speed !=
                                                        catchUpSpeed
                                                    ) {
                                                        setPlaybackParameters(candidate, PlaybackParameters(catchUpSpeed, snapshot.pitch))
                                                    }
                                                }

                                                WarmReplacementAction.DRAIN -> {
                                                    candidate.playWhenReady = true
                                                    setPlaybackParameters(candidate, currentTempoPitch())
                                                }

                                                WarmReplacementAction.WAIT_FOR_SOURCE -> {
                                                    candidate.playWhenReady = false
                                                    setPlaybackParameters(candidate, currentTempoPitch())
                                                }

                                                WarmReplacementAction.COMMIT -> {
                                                    val tempo = currentTempoPitch()
                                                    setPlaybackParameters(candidate, tempo)
                                                    if (!catchUpUsed) break
                                                    catchUpUsed = false
                                                    val checkpoint = CompletableDeferred<AudioPlayoutTracker.Checkpoint>()
                                                    candidate
                                                        .createMessage { _, _ ->
                                                            checkpoint.complete(playoutTracker.checkpoint(tempo))
                                                        }.send()
                                                    playoutCheckpoint = checkpoint.await()
                                                }

                                                WarmReplacementAction.WAIT -> {
                                                    candidate.playWhenReady = warmingPlayback()
                                                    setPlaybackParameters(candidate, currentTempoPitch())
                                                }
                                            }
                                            delay(50L.milliseconds)
                                        }
                                        MediaPreparationResult.Ready
                                    }
                                }
                            } ?: MediaPreparationResult.Failed(IOException("Media3 replacement preparation timed out"))
                        }
                    if (preparation is MediaPreparationResult.Failed) {
                        Timber.tag(MUSIC_VIDEO_TAG).w(
                            "replacement prepare failed videoId=%s type=%s",
                            preparedResource.videoId,
                            preparation.error::class.simpleName,
                        )
                        val failure =
                            createPlaybackFailure(
                                stage = "prepare",
                                error = preparation.error,
                                resource = preparedResource,
                                player = candidate,
                                playbackInstanceId = recoveryPlaybackInstanceId,
                            )
                        recordPlaybackFailure(
                            message = "Android Media3 preparation failed.",
                            failure = failure,
                            resource = preparedResource,
                            player = candidate,
                        )
                        onPrepareFailure(preparation.error)
                        return@withContext false
                    }
                }

                if (
                    !shouldCommit() ||
                    exo !== previousPlayer ||
                    (
                        recoveryPlaybackInstanceId != null &&
                            (
                                recoveryPlaybackInstanceId != currentInstance ||
                                    currentResource?.videoId != preparedResource.videoId
                            )
                    )
                ) {
                    Timber.tag(MUSIC_VIDEO_TAG).d(
                        "prepare discarded after warmup videoId=%s currentInstance=%d recoveryInstance=%s",
                        preparedResource.videoId,
                        currentInstance,
                        recoveryPlaybackInstanceId,
                    )
                    return@withContext false
                }

                val targetPosition =
                    if (prepareInBackground || recoveryPlaybackInstanceId != null) {
                        currentTargetPosition()
                    } else {
                        initialTargetPosition
                    }
                val continuePlaying = targetPlaying()
                if (freshAudioSession && (
                        candidate === previousPlayer || candidate.audioSessionId <= 0 ||
                            candidate.audioSessionId == previousPlayer.audioSessionId
                    )
                ) {
                    onPrepareFailure(IOException("Media3 replacement did not create a fresh audio session"))
                    return@withContext false
                }
                if (
                    prepareInBackground && !stalledSourceReplacement &&
                    (
                        shouldRetargetWarmedReplacement(targetPosition, candidate.currentPosition, warmingPlayback()) ||
                            (recoveryPlaybackInstanceId != null && candidate.currentPosition < targetPosition)
                    )
                ) {
                    onPrepareFailure(IOException("Media3 replacement lost position convergence"))
                    return@withContext false
                }
                val previousStopPosition = previousPlayer.currentPosition.coerceAtLeast(0L)
                val committedPosition =
                    when {
                        prepareInBackground && !stalledSourceReplacement -> candidate.currentPosition.coerceAtLeast(0L)
                        stalledSourceReplacement && !sourceSeekPending -> maxOf(targetPosition, candidate.currentPosition)
                        else -> targetPosition
                    }

                if (prepareInBackground) {
                    setPlaybackParameters(candidate, currentTempoPitch())
                    candidate.setPreferredAudioDevice(preferredAudioDevice)
                    nextAudioSource?.updatePlaybackPosition(committedPosition)
                    nextVideoSource?.updatePlaybackPosition(committedPosition)
                    if (stalledSourceReplacement) {
                        recordSeek(candidate, committedPosition, preparationSeekSource, preparedResource.videoId)
                        candidate.seekTo(committedPosition)
                    }
                    candidate.playWhenReady = continuePlaying
                    val surface = activeVideoSurface
                    removeVideoSurface(previousPlayer, surface)
                    detachPlayerListeners(previousPlayer)
                    replaceAudioOutput(
                        outgoing = previousPlayer,
                        incoming = candidate,
                        releaseOutgoing = { releasePlayer(previousPlayer) },
                        promoteIncoming = {
                            exo = candidate
                            currentResource = preparedResource
                            activeAudioSource = nextAudioSource
                            activeVideoSource = nextVideoSource
                            updateVideoProducer(activeVideoSource, activeVideoSurface != null)
                            sourceCommitted = true
                            attachPlayerListeners(candidate)
                            setVideoTrackEnabled(candidate, shouldEnableVideoTrackAfterWarmup(preparedResource.hasVideo, surface != null))
                            applyVideoSurface(candidate, surface)
                            candidate.setAudioAttributes(playbackAudioAttributes, true)
                            applyPlaybackGains()
                            candidate.playWhenReady = continuePlaying
                        },
                    )
                } else {
                    currentResource = preparedResource
                    applyPlaybackGains()
                    if (shouldAwaitQueueReplacementReadiness(prepareInBackground, replaceCurrentQueueItem)) {
                        val preparation =
                            try {
                                speculativeQueueReplacementPreparation = true
                                withTimeoutOrNull(PREPARATION_TIMEOUT_MS.milliseconds) {
                                    awaitPlayerReady(candidate) {
                                        recordSeek(candidate, targetPosition, preparationSeekSource, preparedResource.videoId)
                                        candidate.setMediaSource(mediaSource, targetPosition)
                                        candidate.prepare()
                                    }
                                }
                            } finally {
                                speculativeQueueReplacementPreparation = false
                            } ?: MediaPreparationResult.Failed(IOException("Media3 initial queue replacement preparation timed out"))
                        if (
                            !isCurrentPreparedQueueReplacement(
                                requestIsCurrent = shouldCommit(),
                                playerIsCurrent = exo === previousPlayer,
                                expectedPlaybackInstanceId = previousInstance,
                                currentPlaybackInstanceId = currentInstance,
                            )
                        ) {
                            Timber.tag(MUSIC_VIDEO_TAG).d(
                                "initial queue replacement discarded after readiness videoId=%s currentInstance=%d",
                                preparedResource.videoId,
                                currentInstance,
                            )
                            return@withContext false
                        }
                        if (preparation is MediaPreparationResult.Failed) {
                            Timber.tag(MUSIC_VIDEO_TAG).w(
                                "initial queue replacement prepare failed videoId=%s type=%s",
                                preparedResource.videoId,
                                preparation.error::class.simpleName,
                            )
                            val failure =
                                createPlaybackFailure(
                                    stage = "prepare",
                                    error = preparation.error,
                                    resource = preparedResource,
                                    player = candidate,
                                    playbackInstanceId = recoveryPlaybackInstanceId,
                                )
                            recordPlaybackFailure(
                                message = "Android Media3 preparation failed.",
                                failure = failure,
                                resource = preparedResource,
                                player = candidate,
                            )
                            onPrepareFailure(preparation.error)
                            return@withContext false
                        }
                    } else {
                        recordSeek(candidate, targetPosition, preparationSeekSource, preparedResource.videoId)
                        candidate.setMediaSource(mediaSource, targetPosition)
                        candidate.prepare()
                    }
                    activeAudioSource = nextAudioSource
                    activeVideoSource = nextVideoSource
                    updateVideoProducer(activeVideoSource, activeVideoSurface != null)
                    sourceCommitted = true
                }
                val currentQueueSong = queue.currentSong()?.let(SongMetadataState::apply)
                val baseSong =
                    if (!stageQueueReplacement && replaceCurrentQueueItem && queueSongAtPreparation != null &&
                        currentQueueSong?.id == queueSongAtPreparation.id
                    ) {
                        mergeSelectedQueueSong(song, queueSongAtPreparation, currentQueueSong)
                    } else {
                        song
                    }
                val committedSong =
                    if (preparedResource.isLive && !baseSong.isLive) {
                        baseSong.copy(musicVideoType = MUSIC_VIDEO_TYPE_LIVE)
                    } else {
                        baseSong
                    }
                sabrPlaybackCache.release(previousAudioSource)
                previousAudioSource?.closePlaybackSource()
                previousVideoSource?.closePlaybackSource()
                currentResource = preparedResource
                applyPlaybackGains()
                // Cold counterpart readiness can suspend; never restore the intent captured before it.
                if (!prepareInBackground) candidate.playWhenReady = targetPlaying()
                _wantsPlayback.value = candidate.playWhenReady
                sabrPlaybackCache.start(
                    committedSong.id,
                    preparedResource,
                    activeAudioSource,
                    candidate.isPlaying,
                    newPlayback = queueTrackChangePending || currentSongValue?.id != committedSong.id,
                )
                currentSongValue = committedSong
                _currentSong.value = committedSong
                _currentPlaybackResource.value = preparedResource
                _loadError.value = null
                currentInstance = instanceCounter.incrementAndGet()
                liveSessionStartElapsedRealtimeMs =
                    if (preparedResource.isLive && candidate.playWhenReady) {
                        SystemClock.elapsedRealtime()
                    } else {
                        0L
                    }
                liveSessionAccumulatedMs = 0L
                PlaybackEventLog.info(
                    PlaybackEventKind.PLAYER,
                    "Android main player preparation committed.",
                    videoId = preparedResource.videoId,
                    clientName = preparedResource.clientName,
                    profileId = preparedResource.profileId,
                    details =
                        preparationDetails +
                            mapOf(
                                "playbackInstanceId" to currentInstance.toString(),
                                "playerId" to playerId(),
                                "playbackPositionMs" to committedPosition.toString(),
                                "playWhenReady" to candidate.playWhenReady.toString(),
                                "volume" to candidate.volume.toString(),
                            ),
                )
                minimumPositionMs =
                    if (recoveryPlaybackInstanceId != null) maxOf(minimumPositionMs, committedPosition) else committedPosition
                queueTrackChangePending = false
                if (prepareInBackground) {
                    _isPlaying.value = candidate.isPlaying
                    updateProgressState(reportSample = false)
                }
                if (recoveryPlaybackInstanceId != null) {
                    recoveryReportingFloor = RecoveryReportingFloor(currentInstance, committedPosition)
                    playbackReporter.onPlaybackRecovered(
                        previousPlaybackInstanceId = recoveryPlaybackInstanceId,
                        playbackInstanceId = currentInstance,
                        positionMs = committedPosition,
                        format = preparedResource.itag,
                    )
                } else {
                    recoveryReportingFloor = null
                    if (previousInstance > 0L) playbackReporter.onPlaybackStopped(previousInstance, previousStopPosition)
                }
                eofInstance = currentInstance
                lastEofInstance = 0L
                if (!stageQueueReplacement && (replaceCurrentQueueItem || (preparedResource.isLive && currentQueueSong?.isLive != true))) {
                    queue.replaceCurrent(committedSong)
                }
                Timber.tag(MUSIC_VIDEO_TAG).d(
                    "prepare committed player=%s instance=%d videoId=%s positionMs=%d resume=%s background=%s audioSource=%s videoSource=%s",
                    playerId(),
                    currentInstance,
                    preparedResource.videoId,
                    committedPosition,
                    candidate.playWhenReady,
                    prepareInBackground,
                    if (preparedResource.isSabr) "sabr" else "direct",
                    when {
                        !preparedResource.hasVideo -> "none"
                        preparedResource.hasSabrVideo -> "sabr"
                        else -> "direct"
                    },
                )
                true
            } finally {
                previousPlayer.removeListener(sourceSeekListener)
                if (candidate !== exo) releasePlayer(candidate)
                if (!sourceCommitted) {
                    nextAudioSource?.closePlaybackSource()
                    nextVideoSource?.closePlaybackSource()
                }
            }
        }

    private fun updateVideoProducer(
        source: SeekableMediaSource?,
        visible: Boolean,
    ) {
        val sabr = source as? SabrInputStream ?: return
        if (visible) {
            sabr.resumeProducer(exo.currentPosition.coerceAtLeast(0L))
        } else if (activeVideoSourcePrepared || exo.playbackState == Player.STATE_READY) {
            // Merged sources still need their initial container probe before audio can become ready.
            activeVideoSourcePrepared = true
            setVideoTrackEnabled(exo, false)
            sabr.suspendProducer()
        }
    }

    private fun buildSource(
        resource: PlaybackResource,
        onAudioSourceOpened: (SeekableMediaSource) -> Unit = {},
        onVideoSourceOpened: (SeekableMediaSource) -> Unit = {},
        deferAudioStart: Boolean = false,
    ) = buildMediaSource(
        context = context,
        resource = resource,
        httpClient = GlobalYouTube.client,
        onAudioSourceOpened = onAudioSourceOpened,
        onVideoSourceOpened = onVideoSourceOpened,
        deferAudioStart = deferAudioStart,
    )

    private fun request(
        song: SongItem?,
        positionMs: Long = 0L,
        resumePlayback: Boolean = true,
    ) {
        cancelScreenOffFocusRecovery()
        song ?: return
        cancelNextInternal()
        val startPositionMs = positionMs.coerceAtLeast(0L)
        requestCounter++
        outputRecoveryState = outputRecoveryState.cancelUserRecovery()
        queueTrackChangePending = true
        playbackTransitions.beginQueue()
        val stoppedInstance = currentInstance
        val stoppedPosition = exo.currentPosition.coerceAtLeast(0L)
        stopForPlaybackRequest(exo, resumePlayback)
        closeActiveVideoSource()
        sabrPlaybackCache.release(activeAudioSource)
        activeAudioSource?.closePlaybackSource()
        activeAudioSource = null
        currentResource = null
        updateOffloadPreferences(exo)
        currentInstance = 0L
        liveSessionStartElapsedRealtimeMs = 0L
        liveSessionAccumulatedMs = 0L
        recoveryReportingFloor = null
        currentSongValue = song
        _currentSong.value = song
        _currentPlaybackResource.value = null
        minimumPositionMs = startPositionMs
        _currentPosition.value = startPositionMs
        _duration.value = song.duration?.times(1000L) ?: 0L
        _isPlaying.value = false
        _loadError.value = null
        if (stoppedInstance > 0L) playbackReporter.onPlaybackStopped(stoppedInstance, stoppedPosition)
        queuePlaybackRequestsState.tryEmit(
            QueuePlaybackRequest(requestCounter, song, startPositionMs, resumePlayback),
        )
    }

    private fun resourceFor(
        song: SongItem,
        url: String,
    ) = PlaybackResource(song.id, url, emptyMap(), null, null)

    private var lastAudioPositionLogMs = Long.MIN_VALUE
    private var lastEarlyStreamFailure: Throwable? = null

    private fun updateProgressState(reportSample: Boolean) {
        if (controllerClock != null) return
        val isLive = currentResource?.isLive == true || currentSongValue?.isLive == true || exo.isCurrentMediaItemLive
        val position = if (isLive) getLiveSessionPositionMs() else getRealtimePositionMs()
        _currentPosition.value = position
        _bufferedPosition.value = if (isLive) position else exo.bufferedPosition.coerceAtLeast(0L)
        _duration.value =
            playbackDurationMs(
                backendDurationMs = exo.duration,
                selectedSong = currentSongValue,
                playbackPrepared = currentInstance > 0L,
                isLive = isLive,
            )
        val now = SystemClock.elapsedRealtime()
        if (reportSample && (lastAudioPositionLogMs == Long.MIN_VALUE || now - lastAudioPositionLogMs >= 5_000L)) {
            lastAudioPositionLogMs = now
            recordPlaybackState("Android audio position sample.", mapOf("positionSampleElapsedRealtimeMs" to now.toString()))
        }
        if (reportSample &&
            (
                lastReportedInstance != currentInstance ||
                    lastReportingSampleAtMs == Long.MIN_VALUE ||
                    now - lastReportingSampleAtMs >= 1_000L ||
                    !exo.isPlaying
            )
        ) {
            lastReportingSampleAtMs = now
            lastReportedInstance = currentInstance
            reportingPositionForSample(currentInstance, position)?.let { reportingPosition ->
                playbackReporter.onPlaybackSample(
                    playbackInstanceId = currentInstance,
                    resource = currentResource,
                    positionMs = reportingPosition,
                    isRendering = exo.isPlaying,
                    wantsPlayback = exo.playWhenReady,
                    playbackSpeed = _playbackSpeed.value,
                )
            }
        }
        updateSourcePosition()
        val streamFailure = (activeAudioSource as? SabrInputStream)?.streamFailure
        if (streamFailure != null && streamFailure !== lastEarlyStreamFailure &&
            !playbackTransitions.recovering && !nextStarted && streamFailureState.subscriptionCount.value > 0
        ) {
            lastEarlyStreamFailure = streamFailure
            reportPlaybackFailure(streamFailure)
        }
        maybeRecoverAudioOutput()
        if (progressWatchdog.observe(
                playbackInstanceId = currentInstance,
                positionMs = position,
                durationMs = _duration.value,
                expectProgress =
                    shouldMonitorPlaybackProgress(
                        playWhenReady = exo.playWhenReady,
                        suppressionReason = exo.playbackSuppressionReason,
                        playbackState = exo.playbackState,
                        transitionPending = playbackTransitions.pending.value,
                        offloadRequested = offloadRequested(),
                    ),
                nowMs = now,
            )
        ) {
            reportPlaybackFailure(
                PlaybackException(
                    "Playback output stopped advancing",
                    PlaybackProgressTimeoutException(),
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                ),
            )
        }
    }

    private fun offloadRequested(): Boolean =
        !offloadFailed && exo.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode ==
            AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED

    private fun reportingPositionForSample(
        playbackInstanceId: Long,
        positionMs: Long,
    ): Long? {
        if (playbackInstanceId <= 0L) return null
        val floor = recoveryReportingFloor ?: return positionMs
        if (floor.playbackInstanceId != playbackInstanceId) {
            recoveryReportingFloor = null
            return positionMs
        }
        if (positionMs < floor.resumePositionMs) return null
        recoveryReportingFloor = null
        return positionMs
    }

    private fun updateSourcePosition() {
        activeAudioSource?.updatePlaybackPosition(_currentPosition.value)
        if (activeVideoSurface != null) activeVideoSource?.updatePlaybackPosition(_currentPosition.value)
    }

    private fun closeActiveVideoSource() {
        if (activeVideoSource != null) {
            Timber.tag(MUSIC_VIDEO_TAG).d(
                "video source close instance=%d videoId=%s positionMs=%d",
                currentInstance,
                currentResource?.videoId,
                _currentPosition.value,
            )
        }
        activeVideoSource?.closePlaybackSource()
        activeVideoSource = null
    }

    private fun completePlaybackAtEof() {
        if (controllerClock != null) return
        if (eofInstance != currentInstance || lastEofInstance == currentInstance) return
        val completedInstance = currentInstance
        lastEofInstance = completedInstance
        startPreloadedNextAtEof?.invoke(completedInstance)
        playbackReporter.onPlaybackEnded(completedInstance, exo.currentPosition.coerceAtLeast(0L))
        onEOFPlaybackInstanceState.tryEmit(completedInstance)
    }

    private fun reportPlaybackFailure(error: Throwable) {
        if (offloadRequested() && (error as? PlaybackException)?.errorCode in
            setOf(
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            )
        ) {
            offloadFailed = true
            updateOffloadPreferences(exo)
            nextPlayer?.let { updateOffloadPreferences(it) }
        }
        val failure =
            createPlaybackFailure(
                stage = "audio_stream",
                error = error,
                resource = currentResource,
                player = exo,
                playbackInstanceId = currentInstance,
            )
        if (!PlaybackNetworkRecovery.handles(failure.category) && !PlaybackRecoveryPolicy.requiresFreshUrl(failure.category)) {
            _loadError.value = "Media3 playback failed"
        }
        Timber.tag(MUSIC_VIDEO_TAG).e(
            "failure classified instance=%d videoId=%s category=%s mediaSourceFailure=%s hasVideo=%s type=%s",
            currentInstance,
            currentResource?.videoId,
            failure.category,
            failure.isMediaSourceFailure,
            currentResource?.hasVideo,
            error::class.simpleName,
        )
        recordPlaybackFailure(
            message = "Android Media3 playback failed.",
            failure = failure,
            resource = currentResource,
            player = exo,
        )
        emitPlaybackFailure(failure)
    }

    private fun maybeRecoverAudioOutput(
        signal: AudioOutputSignal? = null,
        userAction: AudioOutputUserAction? = null,
        a2dpStateChanged: Boolean = false,
    ) {
        if (signal?.routeId != null) a2dpMonitor.setRoute(signal.a2dpAddress)
        val decision =
            decideAudioOutputRecovery(
                state = outputRecoveryState,
                signal = signal,
                eligible =
                    controllerClock == null && !isCasting() && !isVideoTransitionPending() && audioOutputEnabled && exo.volume > 0f &&
                        currentInstance > 0L && currentResource != null && exo.isPlaying &&
                        !playbackTransitions.pending.value && !nextStarted && transitionGain == 1f &&
                        streamFailureState.subscriptionCount.value > 0,
                nowMs = SystemClock.elapsedRealtime(),
                a2dpPlaying = a2dpMonitor.playing,
                audioSessionId = exo.audioSessionId,
                userAction = userAction,
                a2dpStateChanged = a2dpStateChanged,
            )
        outputRecoveryState = decision.state
        if (!decision.rebuild) return
        recordPlaybackState(
            "Android Bluetooth output recovery requested.",
            mapOf(
                "outputRecoveryRouteId" to decision.state.routeId.toString(),
                "outputRecoverySignal" to decision.reason.orEmpty(),
            ),
        )
        emitPlaybackFailure(
            PlaybackFailure(
                stage = "audio_output",
                message = "Bluetooth audio output failure: ${decision.reason}",
                sourceName = "Media3",
                videoId = currentResource?.videoId,
                playbackInstanceId = currentInstance,
                playbackPositionMs = getRealtimePositionMs(),
                resumePlayback = wantsPlayback,
                isMediaSourceFailure = false,
                category = PlaybackFailureCategory.RENDERER,
            ),
            freshAudioSession = true,
        )
    }

    private fun emitPlaybackFailure(
        failure: PlaybackFailure,
        freshAudioSession: Boolean = false,
    ) {
        if (streamFailureState.subscriptionCount.value == 0 || playbackTransitions.recovering) return
        if (PlaybackNetworkRecovery.handles(failure.category)) pendingNetworkRecovery = failure
        val transitionId = ++playbackRecoveryCounter
        playbackTransitions.beginRecovery(transitionId)
        if (!streamFailureState.tryEmit(AndroidPlaybackRecoveryRequest(transitionId, failure, freshAudioSession))) {
            playbackTransitions.finishRecovery(transitionId)
        }
    }

    private fun createPlaybackFailure(
        stage: String,
        error: Throwable,
        resource: PlaybackResource?,
        player: Player,
        playbackInstanceId: Long?,
    ): PlaybackFailure {
        val httpStatusCode = error.findMedia3HttpStatusCode()
        val category = error.media3PlaybackFailureCategory()
        val mediaSourceFailure =
            (category == PlaybackFailureCategory.TIMEOUT && error.stuckPlayerCause() != null) ||
                (
                    (error as? PlaybackException)?.errorCode?.let { it in 2000..2999 }
                        ?: (category != PlaybackFailureCategory.DECODER && category != PlaybackFailureCategory.RENDERER)
                )
        return PlaybackFailure(
            stage = stage,
            message = "Media3 playback failed",
            cause = error,
            sourceName = "Media3",
            code = (error as? PlaybackException)?.errorCodeName,
            httpStatusCode = httpStatusCode,
            videoId = resource?.videoId,
            playbackInstanceId = playbackInstanceId,
            playbackPositionMs = if (player === exo) getRealtimePositionMs() else player.currentPosition.coerceAtLeast(0L),
            resumePlayback = player.playWhenReady,
            isMediaSourceFailure = mediaSourceFailure,
            shouldExcludePlaybackProfile = mediaSourceFailure,
            category = category,
        )
    }

    private fun recordPlaybackFailure(
        message: String,
        failure: PlaybackFailure,
        resource: PlaybackResource?,
        player: Player,
    ) {
        PlaybackEventLog.error(
            kind = PlaybackEventKind.PLAYER,
            message = message,
            videoId = failure.videoId,
            clientName = resource?.clientName,
            profileId = resource?.profileId,
            details =
                buildMap {
                    resource?.let { putAll(it.toPlaybackLogDetails()) }
                    putAll(failure.toPlaybackLogDetails())
                    put("playerState", playbackStateName(player.playbackState))
                    put("playerLoading", player.isLoading.toString())
                    put("screenInteractive", powerManager.isInteractive.toString())
                    put("powerSaveMode", powerManager.isPowerSaveMode.toString())
                    put("playbackSuppressionReason", player.playbackSuppressionReason.toString())
                    put("bufferedPositionMs", player.bufferedPosition.coerceAtLeast(0L).toString())
                    (failure.cause as? PlaybackException)?.let {
                        put("media3ErrorCode", it.errorCode.toString())
                        put("media3ErrorCodeName", it.errorCodeName)
                    }
                },
            error = failure.cause,
        )
    }

    private fun playbackStateDetails(): Map<String, String> =
        mapOf(
            "playbackInstanceId" to currentInstance.toString(),
            "playerId" to playerId(),
            "audioSessionId" to exo.audioSessionId.toString(),
            "playerVolume" to exo.volume.toString(),
            "userVolume" to _volume.value.toString(),
            "audioOutputEnabled" to audioOutputEnabled.toString(),
            "transitionGain" to transitionGain.toString(),
            "sleepTimerGain" to sleepTimerGain.toString(),
            "preferredAudioDevice" to preferredAudioDevice.diagnosticId(),
            "playerState" to playbackStateName(exo.playbackState),
            "playWhenReady" to exo.playWhenReady.toString(),
            "isPlaying" to exo.isPlaying.toString(),
            "publishedPlaying" to _isPlaying.value.toString(),
            "publishedWantsPlayback" to _wantsPlayback.value.toString(),
            "playbackPositionMs" to exo.currentPosition.coerceAtLeast(0L).toString(),
            "bufferedPositionMs" to exo.bufferedPosition.coerceAtLeast(0L).toString(),
            "playbackSuppressionReason" to exo.playbackSuppressionReason.toString(),
            "transitionPending" to playbackTransitions.pending.value.toString(),
            "offloadRequested" to offloadRequested().toString(),
            "audioTrackOffload" to audioTrackConfigurations[exo]?.offload.toString(),
            "audioTrackEncoding" to audioTrackConfigurations[exo]?.encoding.toString(),
            "audioTrackBufferBytes" to audioTrackConfigurations[exo]?.bufferSize.toString(),
            "sleepingForOffload" to exo.isSleepingForOffload.toString(),
            "normalizationGain" to normalizationProcessors[exo]?.normalizationGain.toString(),
            "audioTrackPlaybackParams" to Settings.shared.audioTrackPlaybackParams.toString(),
            "progressStalledMs" to progressWatchdog.stalledForMs(SystemClock.elapsedRealtime()).toString(),
            "nextPlayerId" to nextPlayer?.let { Integer.toHexString(System.identityHashCode(it)) }.toString(),
            "nextAudioSessionId" to nextPlayer?.audioSessionId.toString(),
            "nextPlayerVolume" to nextPlayer?.volume.toString(),
            "nextTransitionGain" to nextTransitionGain.toString(),
            "nextNormalizationGain" to nextPlayer?.let { normalizationProcessors[it]?.normalizationGain }.toString(),
            "nextStarted" to nextStarted.toString(),
            "nextHeldAtStart" to nextHeldAtStart.toString(),
            "nextPlaying" to (nextPlayer?.isPlaying == true).toString(),
            "nextWantsPlayback" to (nextPlayer?.playWhenReady == true).toString(),
            "screenInteractive" to powerManager.isInteractive.toString(),
        ) + silenceSupport[exo]?.audioDiagnosticDetails().orEmpty()

    private fun outputDiagnosticDetails(player: ExoPlayer): Map<String, String> =
        buildMap {
            putAll(silenceSupport[player]?.audioDiagnosticDetails().orEmpty())
            put("outputPlayerId", Integer.toHexString(System.identityHashCode(player)))
            put("outputPlayerVolume", player.volume.toString())
            put("outputPlayerWantsPlayback", player.playWhenReady.toString())
            put("outputPlayerSuppressionReason", player.playbackSuppressionReason.toString())
            put("outputNormalizationGain", normalizationProcessors[player]?.normalizationGain.toString())
            put(
                "outputPlayerRole",
                if (player === exo) {
                    "primary"
                } else if (player === nextPlayer) {
                    "next"
                } else {
                    "candidate_or_retired"
                },
            )
            val equalizer = EqualizerProfiles.configuration
            put("metroEqEnabled", (equalizer.profile != null).toString())
            put("metroEqPreampDb", equalizer.preampDb.toString())
            put("appSessionEffects", "none; MetroEQ and normalization process PCM")
            put("externalSessionEffects", "requires AudioFlinger dump")
            try {
                val manager = context.getSystemService(AudioManager::class.java)
                put("audioMode", manager.mode.toString())
                put("musicStreamVolume", manager.getStreamVolume(AudioManager.STREAM_MUSIC).toString())
                put("musicStreamMuted", manager.isStreamMute(AudioManager.STREAM_MUSIC).toString())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
                    put("spatializerEnabled", manager.spatializer.isEnabled.toString())
                    put("spatializerAvailable", manager.spatializer.isAvailable.toString())
                }
            } catch (error: RuntimeException) {
                put("audioManagerDiagnosticsError", error.javaClass.simpleName)
            }
        }

    private fun recordPlaybackState(
        message: String,
        details: Map<String, String> = emptyMap(),
    ) {
        PlaybackEventLog.info(
            PlaybackEventKind.PLAYER,
            message,
            videoId = currentResource?.videoId,
            clientName = currentResource?.clientName,
            profileId = currentResource?.profileId,
            details = playbackStateDetails() + details,
        )
    }

    private fun logCommand(command: String) {
        recordPlaybackState("Android playback command $command.")
        Timber.tag(MUSIC_VIDEO_TAG).d(
            "command=%s player=%s instance=%d videoId=%s positionMs=%d hasVideo=%s",
            command,
            playerId(),
            currentInstance,
            currentResource?.videoId,
            exo.currentPosition.coerceAtLeast(0L),
            currentResource?.hasVideo,
        )
    }

    private fun playerId(): String = Integer.toHexString(System.identityHashCode(exo))
}

internal class ExoPlaybackBenchmarkPlayer(
    context: Context,
) : PlaybackBenchmarkPlayer {
    private val context = context.applicationContext
    private val silence = SilenceSkippingSupport(this.context)
    private val exo =
        ExoPlayer
            .Builder(this.context)
            .setRenderersFactory(silence.renderersFactory)
            .build()
            .also(silence::attach)
    private val activeSources = mutableListOf<SeekableMediaSource>()

    @Volatile
    private var playbackFailure: Throwable? = null

    override val failure: Throwable?
        get() = playbackFailure

    init {
        exo.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackFailure = error
                }
            },
        )
    }

    override suspend fun prepare(
        resource: PlaybackResource,
        httpClient: HttpClient,
        onResponseStatus: (Int) -> Unit,
    ): Boolean =
        withContext(Dispatchers.Main.immediate) {
            playbackFailure = null
            exo.stop()
            activeSources.forEach(SeekableMediaSource::close)
            activeSources.clear()
            val result =
                withTimeoutOrNull(PREPARATION_TIMEOUT_MS.milliseconds) {
                    awaitPlayerReady(exo) {
                        exo.setMediaSource(
                            buildMediaSource(
                                context = context,
                                resource = resource,
                                httpClient = httpClient,
                                onResponseStatus = onResponseStatus,
                                onAudioSourceOpened = { activeSources += it },
                                onVideoSourceOpened = { activeSources += it },
                            ),
                        )
                        exo.prepare()
                    }
                }
            result == MediaPreparationResult.Ready
        }

    override suspend fun play() =
        withContext(Dispatchers.Main.immediate) {
            exo.play()
        }

    override suspend fun seekTo(positionMs: Long) =
        withContext(Dispatchers.Main.immediate) {
            activeSources.forEach { it.updatePlaybackPosition(positionMs.coerceAtLeast(0L)) }
            PlaybackSeekSource("other", mapOf("reason" to "benchmark")).record(exo.currentPosition, positionMs.coerceAtLeast(0L), null)
            exo.seekTo(positionMs.coerceAtLeast(0L))
        }

    override suspend fun currentPosition(): Long =
        withContext(Dispatchers.Main.immediate) {
            exo.currentPosition.coerceAtLeast(0L).also { position -> activeSources.forEach { it.updatePlaybackPosition(position) } }
        }

    override suspend fun duration(): Long =
        withContext(Dispatchers.Main.immediate) {
            exo.duration.takeIf { it >= 0L } ?: 0L
        }

    override suspend fun isPlaying(): Boolean =
        withContext(Dispatchers.Main.immediate) {
            exo.isPlaying
        }

    override suspend fun wantsPlayback(): Boolean =
        withContext(Dispatchers.Main.immediate) {
            exo.playWhenReady
        }

    override suspend fun close() =
        withContext(Dispatchers.Main.immediate) {
            activeSources.forEach(SeekableMediaSource::close)
            activeSources.clear()
            silence.close()
            exo.release()
        }
}

internal fun stopForPlaybackRequest(
    player: Player,
    resumePlayback: Boolean,
) {
    player.stop()
    player.playWhenReady = resumePlayback
}

internal fun setPendingPlaybackIntent(
    player: Player,
    requestId: Long,
    currentRequestId: Long,
    resumePlayback: Boolean,
) {
    if (requestId == currentRequestId) player.playWhenReady = resumePlayback
}

internal fun playbackIntentForPreparation(
    player: Player,
    resumePlayback: Boolean,
    preserveCurrentIntent: Boolean,
    playWhenReadyProvider: (() -> Boolean)? = null,
): () -> Boolean {
    // Queue requests establish intent before discovery; only standalone cold loads initialize it here.
    if (!preserveCurrentIntent) player.playWhenReady = playWhenReadyProvider?.invoke() ?: resumePlayback
    return playWhenReadyProvider ?: { player.playWhenReady }
}

internal fun shouldReportActivePlaybackError(isSpeculativeQueueReplacement: Boolean): Boolean = !isSpeculativeQueueReplacement

internal suspend fun prepareStreamReplacement(
    player: Player,
    prepare: () -> Unit,
): MediaPreparationResult {
    player.playWhenReady = false
    return withTimeoutOrNull(5_000L) {
        awaitPlayerReady(player, prepare)
    } ?: MediaPreparationResult.Failed(TimeoutException("Stream replacement source did not become ready"))
}

internal suspend fun awaitPlayerReady(
    player: Player,
    prepare: () -> Unit,
): MediaPreparationResult {
    val result = CompletableDeferred<MediaPreparationResult>()
    val listener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) result.complete(MediaPreparationResult.Ready)
            }

            override fun onPlayerError(error: PlaybackException) {
                result.complete(MediaPreparationResult.Failed(error))
            }
        }
    try {
        player.addListener(listener)
        prepare()
        return result.await()
    } catch (cancelled: CancellationException) {
        player.stop()
        throw cancelled
    } finally {
        // Stay on the caller's player dispatcher; cancellation handlers run on the cancelling thread.
        player.removeListener(listener)
    }
}

@OptIn(ExperimentalSabrApi::class)
private fun buildAudioMediaSource(
    context: Context,
    resource: PlaybackResource,
    httpClient: HttpClient,
    onResponseStatus: (Int) -> Unit = {},
    onAudioSourceOpened: (SeekableMediaSource) -> Unit = {},
    deferStart: Boolean = false,
): MediaSource {
    val factory: DataSource.Factory =
        if (resource.isSabr) {
            val bootstrap = resource.sabrBootstrap ?: error("Missing SABR audio bootstrap")
            val source =
                SabrInputStream(
                    mediaSize = bootstrap.contentLengthBytes ?: resource.contentLengthBytes,
                    expectedDurationMs = bootstrap.durationMs,
                    streamFactory = { initial, position ->
                        sabrAudioPlaybackChunks(
                            httpClient = httpClient,
                            bootstrap = bootstrap,
                            tokenSupplier = resource.sabrTokenSupplier,
                            onAttestationPending = resource.onSabrAttestationPending,
                            playbackPositionMs = position,
                            initialPlayerTimeMs = initial,
                            onResponse = { diagnostics ->
                                diagnostics.httpStatus?.let(onResponseStatus)
                                PlaybackBandwidthMeter.recordSample(diagnostics.responseBytes, diagnostics.networkReadMs)
                            },
                            logger = MetrolistInnerTubeLogger,
                        )
                    },
                    tempDirectory = context.cacheDir,
                    startImmediately = !deferStart,
                ).also(onAudioSourceOpened)
            DataSource.Factory { SeekableMediaDataSource(source) }
        } else {
            buildDirectDataSourceFactory(context, resource, false, onResponseStatus, onAudioSourceOpened)
        }
    return buildStreamingMediaSource(
        factory,
        if (resource.isSabr) "metrolist://sabr/${resource.videoId}" else resource.url,
        resource.mimeType,
        keepBufferDuringNetworkRecovery = true,
    )
}

@OptIn(ExperimentalSabrApi::class)
internal fun buildVideoMediaSource(
    context: Context,
    resource: PlaybackResource,
    httpClient: HttpClient,
    onResponseStatus: (Int) -> Unit = {},
    onVideoSourceOpened: (SeekableMediaSource) -> Unit = {},
    sabrSeekPrerollMs: Long = SabrInputStream.DEFAULT_SEEK_PREROLL_MS,
    cacheDataSource: ((DataSource.Factory) -> DataSource.Factory)? = null,
): MediaSource? {
    val factory =
        buildVideoDataSourceFactory(
            context,
            resource,
            httpClient,
            onResponseStatus,
            onVideoSourceOpened,
            sabrSeekPrerollMs,
            deferStart = cacheDataSource != null,
        ) ?: return null
    val videoUri = if (resource.hasSabrVideo) "metrolist://sabr-video/${resource.videoId}" else resource.videoUrl ?: return null
    val mimeType = resource.videoMimeType ?: resource.mimeType.takeIf { resource.videoUrl == resource.url }
    return buildStreamingMediaSource(cacheDataSource?.invoke(factory) ?: factory, videoUri, mimeType)
}

@OptIn(ExperimentalSabrApi::class)
internal fun buildVideoDataSourceFactory(
    context: Context,
    resource: PlaybackResource,
    httpClient: HttpClient,
    onResponseStatus: (Int) -> Unit = {},
    onVideoSourceOpened: (SeekableMediaSource) -> Unit = {},
    sabrSeekPrerollMs: Long = SabrInputStream.DEFAULT_SEEK_PREROLL_MS,
    deferStart: Boolean = false,
): DataSource.Factory? {
    if (!resource.hasVideo) return null
    return if (resource.hasSabrVideo) {
        val bootstrap = resource.sabrVideoBootstrap ?: return null
        val source =
            SabrInputStream(
                mediaSize = bootstrap.selectedVideoContentLengthBytes ?: resource.videoContentLengthBytes,
                streamFactory = { initial, position ->
                    SabrVideoStream(
                        httpClient = httpClient,
                        bootstrap = bootstrap,
                        playbackPositionMs = position,
                        onResponse = { diagnostics ->
                            PlaybackBandwidthMeter.recordSample(diagnostics.responseBytes, diagnostics.networkReadMs)
                        },
                        initialPlayerTimeMs = initial,
                        logger = MetrolistInnerTubeLogger,
                    ).chunks()
                },
                tempDirectory = context.cacheDir,
                seekPrerollMs = sabrSeekPrerollMs,
                startImmediately = !deferStart,
            ).also(onVideoSourceOpened)
        DataSource.Factory { SeekableMediaDataSource(source) }
    } else if (resource.hasDirectVideo) {
        buildDirectDataSourceFactory(context, resource, true, onResponseStatus, onVideoSourceOpened)
    } else {
        return null
    }
}

internal fun shouldRetryPlaybackLoad(
    error: IOException,
    errorCount: Int,
): Boolean =
    error.findSabrProtocolException() == null &&
        (error.findPlaybackHttpStatusCode() ?: error.findMedia3HttpStatusCode()) !in setOf(401, 403, 404, 410) &&
        errorCount <= 3

internal class PlaybackLoadErrorHandlingPolicy(
    private val keepBufferDuringNetworkRecovery: Boolean = false,
) : DefaultLoadErrorHandlingPolicy() {
    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        if (keepBufferDuringNetworkRecovery) Int.MAX_VALUE else super.getMinimumLoadableRetryCount(dataType)

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (keepBufferDuringNetworkRecovery && PlaybackNetworkRecovery.handles(loadErrorInfo.exception.media3PlaybackFailureCategory())) {
            // Active playback owns the bounded reconnect wait; keep Media3's buffered samples meanwhile.
            (loadErrorInfo.errorCount * 1_000L).coerceAtMost(10_000L)
        } else if (shouldRetryPlaybackLoad(loadErrorInfo.exception, loadErrorInfo.errorCount)) {
            super.getRetryDelayMsFor(loadErrorInfo)
        } else {
            C.TIME_UNSET
        }
}

internal fun buildStreamingMediaSource(
    dataSourceFactory: DataSource.Factory,
    uri: String,
    mimeType: String?,
    keepBufferDuringNetworkRecovery: Boolean = false,
): MediaSource {
    val item =
        MediaItem
            .Builder()
            .setUri(uri)
            .setMimeType(mimeType)
            .build()
    val type = Util.inferContentTypeForUriAndMimeType(checkNotNull(item.localConfiguration).uri, mimeType)
    val factory =
        if (type == C.CONTENT_TYPE_OTHER) {
            // The default 1 MiB check interval can parse a minute of audio before applying backpressure.
            ProgressiveMediaSource
                .Factory(dataSourceFactory)
                .setContinueLoadingCheckIntervalBytes(65_536)
                .setLoadErrorHandlingPolicy(PlaybackLoadErrorHandlingPolicy(keepBufferDuringNetworkRecovery))
        } else {
            DefaultMediaSourceFactory(
                dataSourceFactory,
            ).setLoadErrorHandlingPolicy(PlaybackLoadErrorHandlingPolicy(keepBufferDuringNetworkRecovery))
        }
    return factory.createMediaSource(item)
}

private fun buildDirectDataSourceFactory(
    context: Context,
    resource: PlaybackResource,
    video: Boolean,
    onResponseStatus: (Int) -> Unit,
    onSourceOpened: (SeekableMediaSource) -> Unit,
): DataSource.Factory {
    val size = if (video) resource.videoContentLengthBytes else resource.contentLengthBytes
    val url = if (video) checkNotNull(resource.videoUrl) else resource.url
    val boundedHttp = url.startsWith("https://")
    if ((boundedHttp || resource.requireBoundedRange || resource.useRangeChunks) && size != null && size > 0L) {
        val source =
            HttpRangeInputStream(
                url = url,
                requestHeaders = resource.headers,
                mediaSize = size,
                chunkSize = directMediaChunkSize(resource, video),
                targetRequestDurationMs = if (video) null else 2_000L,
                cacheCapture =
                    if (!video && Settings.shared.maxSongCacheSize != 0) {
                        DirectPlaybackCache.begin(
                            resource,
                            playbackCaptureDirectory(context),
                            Settings.shared.maxSongCacheSize.let { if (it < 0) 512L * 1024 * 1024 else it * 1024L * 1024L },
                        )
                    } else {
                        null
                    },
                onResponseStatus = onResponseStatus,
            ).also(onSourceOpened)
        return DataSource.Factory { SeekableMediaDataSource(source) }
    }
    return DefaultDataSource.Factory(
        context,
        StatusReportingHttpDataSourceFactory(
            DefaultHttpDataSource.Factory().setDefaultRequestProperties(resource.headers),
            onResponseStatus,
        ),
    )
}

internal fun buildMediaSource(
    context: Context,
    resource: PlaybackResource,
    httpClient: HttpClient,
    onResponseStatus: (Int) -> Unit = {},
    onAudioSourceOpened: (SeekableMediaSource) -> Unit = {},
    onVideoSourceOpened: (SeekableMediaSource) -> Unit = {},
    deferAudioStart: Boolean = false,
): MediaSource {
    val audioSource = buildAudioMediaSource(context, resource, httpClient, onResponseStatus, onAudioSourceOpened, deferAudioStart)
    val videoSource =
        if (resource.videoUrl == resource.url) {
            null
        } else {
            buildVideoMediaSource(context, resource, httpClient, onResponseStatus, onVideoSourceOpened)
        }
    Timber.tag(MUSIC_VIDEO_TAG).d(
        "media source built videoId=%s merged=%s audioMime=%s videoMime=%s videoItag=%s size=%sx%s",
        resource.videoId,
        videoSource != null,
        resource.mimeType,
        resource.videoMimeType,
        resource.videoItag,
        resource.videoWidth,
        resource.videoHeight,
    )

    return if (videoSource != null) {
        MergingMediaSource(true, true, audioSource, videoSource)
    } else {
        audioSource
    }
}

private fun playbackStateName(state: Int): String =
    when (state) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "unknown($state)"
    }

internal fun Throwable.findMedia3HttpStatusCode(): Int? =
    generateSequence(this) { current -> current.cause?.takeUnless { it === current } }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
        ?.responseCode

private fun hasSwitchBuffer(
    bufferedPositionMs: Long,
    targetPositionMs: Long,
    durationMs: Long,
): Boolean =
    bufferedPositionMs - targetPositionMs >= SWITCH_READY_BUFFER_MS ||
        (durationMs >= 0L && bufferedPositionMs >= durationMs)

private fun setVideoTrackEnabled(
    player: ExoPlayer,
    enabled: Boolean,
) {
    player.trackSelectionParameters =
        player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
}

private fun applyVideoSurface(
    player: ExoPlayer,
    surface: Any?,
) {
    when (surface) {
        is Surface -> player.setVideoSurface(surface)
        is SurfaceHolder -> player.setVideoSurfaceHolder(surface)
        is SurfaceView -> player.setVideoSurfaceView(surface)
        is TextureView -> player.setVideoTextureView(surface)
        else -> player.clearVideoSurface()
    }
}

private fun removeVideoSurface(
    player: ExoPlayer,
    surface: Any?,
) {
    when (surface) {
        is Surface -> player.clearVideoSurface(surface)
        is SurfaceHolder -> player.clearVideoSurfaceHolder(surface)
        is SurfaceView -> player.clearVideoSurfaceView(surface)
        is TextureView -> player.clearVideoTextureView(surface)
        else -> player.clearVideoSurface()
    }
}

private fun surfaceName(surface: Any?): String =
    surface?.let { "${it::class.java.simpleName}@${Integer.toHexString(System.identityHashCode(it))}" } ?: "none"

private fun resourceSummary(resource: PlaybackResource): String =
    "client=${resource.clientName} profile=${resource.profileId} musicVideoId=${resource.musicVideoId} " +
        "audioItag=${resource.itag} audioMime=${resource.mimeType} audioCodec=${resource.codecs} audioBitrate=${resource.bitrate} " +
        "hasVideo=${resource.hasVideo} directVideo=${resource.hasDirectVideo} sabrVideo=${resource.hasSabrVideo} " +
        "videoItag=${resource.videoItag} videoMime=${resource.videoMimeType} videoCodec=${resource.videoCodecs} " +
        "videoSize=${resource.videoWidth}x${resource.videoHeight} videoBitrate=${resource.videoBitrate} " +
        "availableHeights=${resource.availableVideoHeights}"

private class SeekableMediaDataSource(
    private val source: SeekableMediaSource,
) : BaseDataSource(true) {
    private var opened = false
    private var uri = android.net.Uri.EMPTY
    private var readPosition = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        source.seekToMediaPosition(dataSpec.position)
        readPosition = dataSpec.position
        val available = source.mediaSize?.minus(dataSpec.position)?.coerceAtLeast(0L)
        bytesRemaining =
            when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> available?.let { minOf(it, dataSpec.length) } ?: dataSpec.length
                available != null -> available
                else -> C.LENGTH_UNSET.toLong()
            }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val requested =
            if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
        val count = source.readAtMediaPosition(readPosition, buffer, offset, requested)
        if (count <= 0) return C.RESULT_END_OF_INPUT
        readPosition += count
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    override fun getUri(): android.net.Uri = uri
}

private class StatusReportingHttpDataSourceFactory(
    private val delegate: DefaultHttpDataSource.Factory,
    private val onResponseStatus: (Int) -> Unit,
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        val dataSource = delegate.createDataSource()
        return object : DataSource by dataSource {
            override fun open(dataSpec: DataSpec): Long =
                try {
                    dataSource.open(dataSpec)
                } finally {
                    dataSource.responseCode.takeIf { it > 0 }?.let(onResponseStatus)
                }
        }
    }
}

private data class RecoveryReportingFloor(
    val playbackInstanceId: Long,
    val resumePositionMs: Long,
)

internal sealed interface MediaPreparationResult {
    data object Ready : MediaPreparationResult

    data class Failed(
        val error: Throwable,
    ) : MediaPreparationResult
}

internal fun shouldRecoverPlaybackOnNetworkChange(
    resource: PlaybackResource?,
    hasError: Boolean,
    wantsPlayback: Boolean,
): Boolean = resource != null && resource.clientName != "local" && hasError && wantsPlayback

internal fun prepareEnqueuedCurrent(
    queue: MusicQueue,
    request: (SongItem, Boolean) -> Unit,
) {
    if (queue.selectFirstIfUnselected()) queue.currentSong()?.let { request(it, true) }
}

private fun SeekableMediaSource.closePlaybackSource() {
    if (this is SabrInputStream) closeAsync() else close()
}

internal fun directMediaChunkSize(
    resource: PlaybackResource,
    video: Boolean,
): Int {
    val limit = resource.rangeChunkSizeBytes.coerceIn(1L, if (video) Int.MAX_VALUE.toLong() else 1_048_576L)
    val bitrate = if (video) resource.videoBitrate else resource.bitrate
    val size = if (video) resource.videoContentLengthBytes else resource.contentLengthBytes
    val duration = resource.durationSeconds?.takeIf { it > 0L }
    val bytesPerSecond =
        bitrate?.takeIf { it > 0 }?.let { it.toLong() / 8L }
            ?: if (size != null && size > 0L && duration != null) size / duration else null
    val secondsPerRange = if (video) 4L else 60L
    return minOf(limit, bytesPerSecond?.coerceIn(1L, Int.MAX_VALUE / secondsPerRange)?.times(secondsPerRange) ?: limit).toInt()
}

internal inline fun advanceWithPreloadedNext(
    ready: Boolean,
    consume: () -> Boolean,
    advance: () -> Unit,
) {
    if (!ready || !consume()) advance()
}

private fun Throwable.stuckPlayerCause(): StuckPlayerException? =
    generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }
        .filterIsInstance<StuckPlayerException>()
        .firstOrNull()

internal fun shouldCompleteStuckPlayback(
    error: Throwable,
    playbackInstanceId: Long,
    eofInstanceId: Long,
): Boolean =
    playbackInstanceId > 0L && playbackInstanceId == eofInstanceId &&
        error.stuckPlayerCause()?.stuckType == StuckPlayerException.STUCK_PLAYING_NOT_ENDING

internal fun Throwable.media3PlaybackFailureCategory(): PlaybackFailureCategory =
    findSabrProtocolException()?.kind?.let(::playbackFailureCategoryFromSabr)
        ?: findMedia3HttpStatusCode()?.let(PlaybackFailureClassifier::fromHttpStatus)
        // Media3 wraps stuck playback in ERROR_CODE_TIMEOUT, outside its source-error range.
        ?: stuckPlayerCause()?.let {
            when (it.stuckType) {
                StuckPlayerException.STUCK_BUFFERING_NOT_LOADING,
                StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS,
                -> PlaybackFailureCategory.TIMEOUT

                else -> PlaybackFailureCategory.RENDERER
            }
        }
        ?: when ((this as? PlaybackException)?.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> PlaybackFailureCategory.DECODER

            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            -> PlaybackFailureCategory.RENDERER

            else -> PlaybackFailureClassifier.fromThrowable(this)
        }
