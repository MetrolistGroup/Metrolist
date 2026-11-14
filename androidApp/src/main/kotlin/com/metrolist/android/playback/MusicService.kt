package com.metrolist.android.playback

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.database.ContentObserver
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Rating
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.android.App
import com.metrolist.android.MainActivity
import com.metrolist.android.R
import com.metrolist.android.cast.AndroidMdnsCastDiscovery
import com.metrolist.android.constants.MediaSessionConstants
import com.metrolist.android.data.PlayHistoryRepository
import com.metrolist.android.deviceconnect.AndroidDeviceConnect
import com.metrolist.android.listentogether.AndroidListenTogetherManager
import com.metrolist.android.models.PersistPlayerState
import com.metrolist.android.models.PlaybackPersistenceWriter
import com.metrolist.android.models.decodePersistPlayerState
import com.metrolist.android.models.playbackPersistenceJson
import com.metrolist.android.widget.EXTRA_WIDGET_COMMAND_TOKEN
import com.metrolist.android.widget.MetrolistWidgetManager
import com.metrolist.android.widget.MusicWidgetReceiver
import com.metrolist.android.widget.WIDGET_COMMAND_TOKEN
import com.metrolist.android.widget.WidgetControls
import com.metrolist.android.widget.sampleWidgetProgress
import com.metrolist.android.widget.shouldSampleWidgetProgress
import com.metrolist.android.widget.widgetScreenInteractive
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.sabr.findSabrProtocolException
import com.metrolist.shared.BuildConfig
import com.metrolist.shared.api.PlaylistPage
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.cast.CastConnectionStatus
import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.CastError
import com.metrolist.shared.cast.CastMedia
import com.metrolist.shared.cast.CastProtocol
import com.metrolist.shared.cast.JvmCastManager
import com.metrolist.shared.cast.castAudioQuality
import com.metrolist.shared.cast.forQueueEntry
import com.metrolist.shared.cast.isPlayableOn
import com.metrolist.shared.cast.shouldHandleCastPlaybackEnd
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.deviceconnect.DcPlaybackAction
import com.metrolist.shared.deviceconnect.DcPlaybackCommand
import com.metrolist.shared.deviceconnect.DcSyncError
import com.metrolist.shared.deviceconnect.DeviceConnectManager
import com.metrolist.shared.discord.DiscordActivityBuilder
import com.metrolist.shared.discord.DiscordDefaults
import com.metrolist.shared.discord.DiscordRpcManager
import com.metrolist.shared.discord.PresenceStatus
import com.metrolist.shared.discord.discordPlaybackTimestamps
import com.metrolist.shared.discord.resolveDiscordButtonLabel
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.playback_unable_to_play_queued_track
import com.metrolist.shared.generated.resources.search_no_results
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_LIVE
import com.metrolist.shared.model.RadioQueueChip
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.canSwitchToAudioTrack
import com.metrolist.shared.model.isLive
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.playback.PauseOnMuteController
import com.metrolist.shared.playback.PlaybackErrorContext
import com.metrolist.shared.playback.PlaybackFailure
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.playback.PlaybackFailureClassifier
import com.metrolist.shared.playback.PlaybackNetworkRecovery
import com.metrolist.shared.playback.PlaybackRecoveryAction
import com.metrolist.shared.playback.PlaybackRecoveryBudget
import com.metrolist.shared.playback.PlaybackRecoveryPolicy
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.playback.QueuePlaybackRequest
import com.metrolist.shared.playback.SleepTimerController
import com.metrolist.shared.playback.TokenlessStreamRenewal
import com.metrolist.shared.playback.asManuallyAddedQueueItem
import com.metrolist.shared.playback.enqueueOrStartQueue
import com.metrolist.shared.playback.findPlaybackHttpStatusCode
import com.metrolist.shared.playback.observePlaybackCheckpoints
import com.metrolist.shared.playback.shouldSkipAfterPlaybackFailure
import com.metrolist.shared.playback.sponsorblock.observeSponsorBlockSkipping
import com.metrolist.shared.scrobble.ScrobbleManager
import com.metrolist.shared.scrobble.ScrobbleSessions
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.MusicVideoQuality
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.settings.getAutomotivePinnedPlaylists
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.PodcastLibrary
import com.metrolist.shared.state.PodcastPlaybackProgress
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.utils.normalizePlaybackTempo
import com.metrolist.shared.youtube.benchmark.PlaybackBenchmarkRunner
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.StreamResolver
import com.metrolist.shared.youtube.playback.playbackErrorResource
import com.metrolist.shared.youtube.playback.toPlaybackLogDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import timber.log.Timber
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import android.provider.Settings as AndroidSettings
import com.metrolist.shared.generated.resources.Res as SharedRes
import com.metrolist.shared.generated.resources.shuffle as sharedShuffle
import com.metrolist.shared.generated.resources.start_radio as sharedStartRadio

private const val QUEUE_RESOLVE_RETRY_DELAY_MS = 350L

internal suspend fun observeAndroidScrobbles(
    player: AndroidPlaybackPlayer,
    onState: (SongItem?, Boolean, Boolean) -> Unit,
) {
    var previousInstance = player.currentPlaybackInstanceId
    var previousQueueKey = player.queueState.value.let { it.entries.getOrNull(it.currentIndex)?.key }
    var previousQueueIndex = player.queueState.value.currentIndex
    var previousRequestId =
        player.queuePlaybackRequests.replayCache
            .lastOrNull()
            ?.requestId
    merge(
        combine(
            player.currentSong,
            player.isPlaying,
            player.queueState,
            player.playbackInstanceId,
            player.duration,
        ) { _, _, _, _, _ -> Unit },
        player.queuePlaybackRequests.map { Unit },
    ).map {
        val queue = player.queueState.value
        val queueKey = queue.entries.getOrNull(queue.currentIndex)?.key
        Triple(
            player.currentSong.value?.let { song ->
                val duration =
                    player.duration.value
                        .takeIf { it > 0L }
                        ?.div(1000L)
                        ?.takeIf { it > 0L }
                if (!song.isLive && song.duration?.takeIf { it > 0L } == null && duration != null) song.copy(duration = duration) else song
            } to player.isPlaying.value,
            Triple(queueKey, queue.currentIndex, player.currentPlaybackInstanceId),
            player.queuePlaybackRequests.replayCache
                .lastOrNull()
                ?.requestId,
        )
    }.distinctUntilChanged()
        .collect { (songAndPlaying, queueAndInstance, requestId) ->
            val (song, playing) = songAndPlaying
            val (queueKey, queueIndex, instance) = queueAndInstance
            val queueOccurrenceChanged = queueIndex != previousQueueIndex && queueKey != previousQueueKey
            val newPlayback = requestId != previousRequestId || (queueOccurrenceChanged && instance != previousInstance)
            onState(song, playing, newPlayback)
            // Queue and instance flows may arrive in either order; retain the old occurrence until both change.
            if (!queueOccurrenceChanged || newPlayback) {
                if (queueKey != previousQueueKey || queueIndex != previousQueueIndex || requestId != previousRequestId) {
                    previousInstance = instance
                }
                previousQueueKey = queueKey
                previousQueueIndex = queueIndex
            }
            previousRequestId = requestId
        }
}

class MusicService :
    MediaLibraryService(),
    KoinComponent {
    companion object {
        private val playbackPersistenceWriter =
            PlaybackPersistenceWriter(
                writePosition = { Settings.shared.persistentPlaybackPosition = it },
                writeStoredValue = { Settings.shared.persistentPlaybackState = it },
            )
        private var finalPlaybackPersistence: Job? = null
        private const val TAG = "MusicService"
        private const val MUSIC_VIDEO_TAG = "MusicVideo"
        const val ACTION_OPEN_PLAYER = "action_open_player"
        const val ACTION_REFRESH_AUTOMOTIVE_LIBRARY = "action_refresh_automotive_library"
        private const val HEALTHY_PLAYBACK_THRESHOLD_MS = 30_000L
        private const val MAX_HEALTHY_REQUEST_HISTORY = 64
        private const val MAX_CONSECUTIVE_FAILURE_SKIPS = 3
        private const val MAX_RADIO_CONTINUATION_PAGES = 10

        internal val running = MutableStateFlow(false)

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val binder = MusicBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val taskRemoved = MutableStateFlow(false)
    private val playbackBenchmark: PlaybackBenchmarkRunner by inject()
    private val streamResolver: StreamResolver by inject()
    private val tokenProvider: TokenProvider by inject()
    private val audioCacheManager: AudioCacheManager by inject()
    private val musicVideoResolver: MusicVideoResolver by inject()
    private val database: MusicDatabase by inject()
    private val widgetManager: MetrolistWidgetManager by inject()

    lateinit var player: AndroidPlaybackPlayer
        private set
    lateinit var castManager: JvmCastManager
        private set
    var media3Player: Player? = null
        private set
    private var mediaSession: MediaLibrarySession? = null
    internal var playbackCommandGeneration = 0L
        private set
    private lateinit var playbackRestoreJob: Job
    private lateinit var automotiveLibrary: AutomotiveMediaLibrary
    private val automotiveRootChildrenLimit = ConcurrentHashMap<MediaSession.ControllerInfo, Int>()
    private val automotiveRootBrowsableOnly = ConcurrentHashMap<MediaSession.ControllerInfo, Boolean>()
    private val automotivePlaybackRequestGate =
        AutomotivePlaybackRequestGate { songs, startIndex, queueTitle, shuffleModeEnabled, playlistPage, replacement ->
            val shuffleEnabled =
                newQueueShuffleEnabled(
                    requestedShuffle = shuffleModeEnabled,
                    currentShuffle = if (player.songQueue.value.isEmpty()) _shuffleModeEnabled.value else player.isShuffleEnabled,
                    persistentShuffle = Settings.shared.persistentShuffleAcrossQueues,
                )
            if (!AndroidDeviceConnect.intercept(
                    DcPlaybackCommand(
                        DcPlaybackAction.REPLACE_QUEUE,
                        songs = songs,
                        index = startIndex,
                        title = queueTitle,
                        finite = true,
                        enabled = shuffleEnabled,
                        preserveHistory = shuffleModeEnabled != true,
                    ),
                )
            ) {
                queueCoordinator.replaceQueueSession(songs, startIndex, queueTitle, finite = true, playlistPage = playlistPage) {
                    replacement()
                    if (shuffleEnabled) player.toggleShuffle(preserveHistory = shuffleModeEnabled != true)
                    _shuffleModeEnabled.value = player.isShuffleEnabled
                }
            }
        }
    private lateinit var scrobbleManager: ScrobbleManager

    private lateinit var syncPlayback: AndroidDeviceConnectPlayback
    private val podcastWriterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val podcastProgress by lazy {
        PodcastPlaybackProgress(
            writerScope = podcastWriterScope,
            save = { song, position, duration, metadata ->
                PodcastLibrary.updatePlaybackPosition(database, song, position, duration, metadata)
            },
            onError = { Timber.e(it, "Failed to persist podcast progress") },
        )
    }
    private lateinit var listeningHistory: AndroidListeningHistory
    private val externalReportingEnabled: Boolean get() = listeningHistory.enabled
    lateinit var sleepTimerController: SleepTimerController
        private set

    private lateinit var queueCoordinator: AndroidQueueCoordinator
    private lateinit var transitions: AndroidQueueTransitions
    internal val crossfadeState: StateFlow<AndroidCrossfadeState?> get() = transitions.crossfadeState
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()
    private val _shuffleModeEnabled = MutableStateFlow(false)
    val shuffleModeEnabled: StateFlow<Boolean> = _shuffleModeEnabled.asStateFlow()

    val isQueueLoading: StateFlow<Boolean> get() = queueCoordinator.isQueueLoading
    val isReconnecting = MutableStateFlow(false)
    val isPlaybackLoading: StateFlow<Boolean> get() = queueCoordinator.isPlaybackLoading
    val queueTitle: StateFlow<String?> get() = queueCoordinator.queueTitle

    internal fun setQueueTitleFromRemote(title: String?) = queueCoordinator.setTitle(title)

    val queueChips: StateFlow<List<RadioQueueChip>> get() = queueCoordinator.queueChips
    private lateinit var videoMode: AndroidVideoModeController
    val isVideoModeActive: StateFlow<Boolean> get() = videoMode.isVideoModeActive
    val isVideoModeResolving: StateFlow<Boolean> get() = videoMode.isVideoModeResolving
    val isVideoViewerVisible: StateFlow<Boolean> get() = videoMode.isVideoViewerVisible

    private val castDisconnectSuppression = CastDisconnectSuppression()
    private var streamRecoveryVideoId: String? = null
    private var streamRecoveryBudget: PlaybackRecoveryBudget? = null
    private var consecutiveFailureSkips = 0
    private val healthyQueueRequestIds = linkedSetOf<Long>()

    private var lastPersistenceState: PersistPlayerState? = null
    private val playbackPersistenceRequests = Channel<PlaybackPersistenceWriter.Request>(Channel.CONFLATED)

    private val playbackPersistenceLifecycle = PlaybackPersistenceLifecycle()

    private lateinit var audioManager: AudioManager
    private val pauseOnMute = PauseOnMuteController()
    private val volumeMuteObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(
                selfChange: Boolean,
                uri: Uri?,
            ) {
                handleDeviceVolumeChanged()
            }
        }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (!::player.isInitialized) {
            null
        } else if (intent?.getBooleanExtra("internal_bind", false) == true) {
            binder
        } else {
            super.onBind(intent)
        }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onCreate() {
        super.onCreate()
        if (!(application as App).infrastructureReady) {
            stopSelf()
            return
        }
        isRunning = true

        setupNotificationChannel()
        // An idle session with a restored queue but no current song renders as a blank "is running" card.
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)

        val mediaNotificationProvider =
            PromotedMediaNotificationProvider(
                DefaultMediaNotificationProvider
                    .Builder(this)
                    .setChannelId("playback")
                    .setChannelName(R.string.playback_notification_channel_name)
                    .build()
                    .apply {
                        setSmallIcon(R.drawable.ic_notification)
                    },
            )
        setMediaNotificationProvider(mediaNotificationProvider)

        castManager =
            JvmCastManager(
                scope = serviceScope,
                discoveryFactory = { AndroidMdnsCastDiscovery(this) },
                localAddresses = ::castNetworkAddresses,
                appVersion = BuildConfig.APP_VERSION,
            )
        player =
            ExoPlaybackPlayer(
                this,
                onPlaybackCommand = ::onPlaybackCommand,
                isVideoTransitionPending = { ::videoMode.isInitialized && videoMode.isVideoModeResolving.value },
                isCasting = { castManager.state.value.isConnected },
                sabrPlaybackCache = audioCacheManager.sabrPlaybackCache,
            )
        restorePlaybackParameters()
        queueCoordinator =
            AndroidQueueCoordinator(
                context = this,
                player = player,
                scope = serviceScope,
                _repeatMode = _repeatMode,
                _shuffleModeEnabled = _shuffleModeEnabled,
                onSave = ::savePlaybackState,
                onSessionReset = {
                    streamRecoveryVideoId = null
                    streamRecoveryBudget = null
                },
                onVideoSelected = { song -> videoMode.selectVideoForQueue(song) },
                castState = { castManager.state.value },
            )
        transitions =
            AndroidQueueTransitions(
                player = player,
                scope = serviceScope,
                audioCacheManager = audioCacheManager,
                musicVideoResolver = musicVideoResolver,
                queue = queueCoordinator,
                fetchPlaybackResource = { song, wantVideo -> fetchPlaybackResource(song, wantVideo).resource },
                episodeResumePosition = { song, duration ->
                    withContext(Dispatchers.IO) { PodcastLibrary.resumePosition(database, song, durationMs = duration) }
                },
                onPreloadFailure = { resource, song, wantVideo, error ->
                    streamResolver.reportPlaybackFailure(
                        videoId = resource.videoId,
                        clientName = resource.clientName,
                        responseCode = error.findPlaybackHttpStatusCode(),
                        profileId = resource.profileId,
                        hints = song.toAndroidContentHints(wantVideo),
                        category = PlaybackFailureClassifier.fromThrowable(error),
                    )
                },
                onAdvance = { advanceQueueAfterCurrent() },
            )
        queueCoordinator.transitions = transitions
        videoMode =
            AndroidVideoModeController(
                context = this,
                player = player,
                scope = serviceScope,
                musicVideoResolver = musicVideoResolver,
                withPlaybackLoading = { block -> withQueueLoading(isPlaybackLoading = true, block = block) },
                fetchPlaybackResource = { song, wantVideo -> fetchPlaybackResource(song, wantVideo).resource },
                onError = ::showPlaybackErrorToast,
            )
        player.onPreloadedPlaybackCommitted = videoMode::onPreloadedPlaybackCommitted
        automotiveLibrary = AutomotiveMediaLibrary(this, database)
        media3Player =
            MediaSessionPlayer(
                artworkUri = { mediaArtworkUri(this, it) },
                currentArtworkUri = { mediaArtworkUri(this, it, isCurrent = true) },
                musicPlayer = player,
                repeatMode = _repeatMode,
                shuffleModeEnabled = _shuffleModeEnabled,
                remotePlaybackState = castManager.state,
                onRemotePlaybackChange = ::setCastPlayback,
                onRemoteStop = ::stopCastPlayback,
                onRemoteSeek = ::seekCastTo,
                onRemoteVolumeChange = ::setCastVolume,
                onQueueChanged = ::synchronizeMediaSessionQueue,
                extendPlaylistBeforeSkip = ::extendPlaylistBeforeSkip,
                mediaItemResolver = automotiveLibrary::resolveCachedSong,
                automotivePlaybackRequestGate = automotivePlaybackRequestGate,
                deviceConnectInterceptor = AndroidDeviceConnect::intercept,
                isDeviceConnectFollowing = { AndroidDeviceConnect.manager.remote.isFollowing },
                scope = serviceScope,
                awaitLikes = { (application as App).startup.await() },
            )
        scrobbleManager = ScrobbleManager(serviceScope)
        listeningHistory = AndroidListeningHistory(player, serviceScope, getKoin().get<PlayHistoryRepository>()::recordLocalPlay)
        syncPlayback =
            AndroidDeviceConnectPlayback(
                context = this,
                player = player,
                queue = queueCoordinator,
                video = videoMode,
                transitions = transitions,
                scrobbleManager = scrobbleManager,
                history = listeningHistory,
                repeatMode = _repeatMode,
                shuffleModeEnabled = _shuffleModeEnabled,
                fetchPlaybackResource = { song, wantVideo -> fetchPlaybackResource(song, wantVideo).resource },
                isCasting = { castManager.state.value.status != CastConnectionStatus.DISCONNECTED },
                onSave = ::savePlaybackState,
                onDiscordUpdate = ::syncDiscordState,
            )
        sleepTimerController =
            SleepTimerController(
                scope = serviceScope,
                player =
                    MusicSleepTimerPlayer(player, pausePlayback = { media3Player?.pause() }) {
                        transitions.cancelAndroidCrossfade()
                        transitions.cancelNextTrackPreload()
                    },
                elapsedRealtimeMs = SystemClock::elapsedRealtime,
            )
        player.startPreloadedNextAtEof = { completedInstance ->
            if (!sleepTimerController.isStopAfterCurrentSong) queueCoordinator.tryStartPreloadedNextAtEof(completedInstance)
        }

        ScrobbleSessions
            .restorePersistedAccounts()

        registerVolumeMuteObserver()
        observeAndroidAutoConnection()
        setupMediaSession()
        observePlaybackEvents()
        observeCastState()
        listeningHistory.start()

        playbackRestoreJob =
            serviceScope.launch {
                (application as App).startup.await()
                if (Settings.shared.hasSavedVolume) player.setVolume(Settings.shared.volume)
                playbackPersistenceLifecycle.restore { restorePersistedPlayback() }
                savePlaybackState()
                AndroidDeviceConnect.manager.remote.attach(syncPlayback.port)
            }
        startPlaybackPersistence()
        startWidgetUpdates()
        startWidgetProgressUpdates()
        observeSettingsChanges()

        running.value = true
        observeServiceLifetime()
        Timber.tag(TAG).d("MusicService created backend=Media3")
    }

    private fun observeServiceLifetime() {
        serviceScope.launch {
            ProcessLifecycleOwner.get().lifecycle.currentStateFlow.collect { state ->
                if (state.isAtLeast(Lifecycle.State.STARTED)) taskRemoved.value = false
            }
        }
        serviceScope.launch {
            awaitPlaybackRestore()
            val localPlaybackRequired =
                combine(player.wantsPlaybackState, player.playbackStateFlow) { requested, state ->
                    requested && state != Player.STATE_IDLE && state != Player.STATE_ENDED
                }
            val playbackRequired =
                combine(
                    localPlaybackRequired,
                    isPlaybackLoading,
                    castManager.state,
                    AndroidDeviceConnect.manager.state,
                    AndroidListenTogetherManager.playbackRequired,
                ) { requested, loading, cast, devices, room ->
                    requested || loading || cast.isConnected || room ||
                        devices.peers.any { it.state == DeviceConnectManager.PeerState.CONNECTED }
                }
            observePlaybackServiceLifetime(taskRemoved, playbackRequired, snapshotFlow { Settings.shared.stopMusicOnTaskClear }) {
                saveFinalPlaybackState()?.join()
                running.value = false
                pauseAllPlayersAndStopSelf()
            }
        }
    }

    private fun observeAndroidAutoConnection() {
        serviceScope.launch {
            val disconnectState = AndroidAutoDisconnectState()
            androidAutoConnectionTypes(this@MusicService).collect { type ->
                if (disconnectState.onConnectionTypeChanged(
                        type,
                        isRemotePlayback = castManager.state.value.isConnected || AndroidDeviceConnect.manager.remote.isFollowing,
                    )
                ) {
                    automotivePlaybackRequestGate.cancelPendingRequests()
                    pauseOnMute.cancelResume()
                    // Pause even after focus loss so its delayed recovery cannot restart on the phone speaker.
                    player.pause()
                }
            }
        }
    }

    internal fun restorePlaybackParameters() {
        val speed = normalizePlaybackTempo(Settings.shared.playbackSpeed) ?: 1f
        val pitch = normalizePlaybackTempo(Settings.shared.playbackPitch) ?: 1f
        Settings.shared.playbackSpeed = speed
        Settings.shared.playbackPitch = pitch
        player.setTempoPitch(speed, pitch)
    }

    internal fun onPlaybackCommand() {
        playbackCommandGeneration++
        playbackBenchmark.cancel()
        pauseOnMute.cancelResume()
    }

    private fun registerVolumeMuteObserver() {
        audioManager = getSystemService(AudioManager::class.java)
        contentResolver.registerContentObserver(
            AndroidSettings.System.CONTENT_URI,
            true,
            volumeMuteObserver,
        )
        handleDeviceVolumeChanged()
    }

    private fun handleDeviceVolumeChanged() {
        val muted =
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 ||
                audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
        pauseOnMute.onVolumeChanged(
            muted = muted,
            enabled = Settings.shared.pauseOnMute,
            playing = player.isPlaying.value,
            pause = player::pause,
            resume = { if (!castManager.state.value.isConnected) player.resume() },
        )
    }

    private fun castNetworkAddresses(): List<InetAddress> {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return emptyList()
        return connectivityManager
            .getLinkProperties(network)
            ?.linkAddresses
            ?.map { it.address }
            ?.filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress }
            .orEmpty()
    }

    private fun observeCastState() {
        serviceScope.launch {
            var wasConnected = false
            var handledEndGeneration: Long? = null
            castManager.state.collect { state ->
                val suppressConnectionLoss = castDisconnectSuppression.onConnectionState(state.isConnected)
                val connectionLost = wasConnected && !state.isConnected && !suppressConnectionLoss
                wasConnected = state.isConnected
                if (!state.isConnected) {
                    handledEndGeneration = null
                }
                if (connectionLost) restoreLocalPlaybackAfterCast(state.positionMs, state.isPlaying)
                if (shouldHandleCastPlaybackEnd(state, handledEndGeneration)) {
                    handledEndGeneration = state.mediaGeneration
                    handlePlaybackCompletion()
                }
            }
        }
    }

    fun startCastDiscovery() = castManager.startDiscovery()

    fun stopCastDiscovery() = castManager.stopDiscovery()

    private fun canStartCast(): Boolean {
        val sync = AndroidDeviceConnect.manager.remote
        val guest = AndroidListenTogetherManager.isGuestPlaybackLocked
        if (!guest && !sync.isActive) return true
        sync.reportError(if (guest) DcSyncError.NOT_ALLOWED else DcSyncError.CONFLICT)
        return false
    }

    fun castTo(device: CastDevice) {
        if (!Settings.shared.castEnabled) return
        if (!canStartCast()) return
        val song = player.currentSong.value
        if (song == null) {
            castManager.showError(CastError.NO_MEDIA)
            return
        }
        val sessionId = queueCoordinator.queueSessionId
        val requestId = player.latestQueuePlaybackRequestId
        val playbackInstanceId = player.currentPlaybackInstanceId

        fun isCurrentHandoff(): Boolean =
            isCurrentQueuePlaybackTarget(
                sessionId,
                queueCoordinator.queueSessionId,
                requestId,
                player.latestQueuePlaybackRequestId,
                song.id,
                player.currentSong.value?.id,
            ) && player.currentPlaybackInstanceId == playbackInstanceId
        serviceScope.launch {
            val resource = resolveCastResource(song, device.protocol)
            if (!isCurrentHandoff()) return@launch
            if (!canStartCast()) return@launch
            if (resource == null) {
                castManager.showError(CastError.UNSUPPORTED_STREAM)
                return@launch
            }
            val previousCastState = castManager.state.value.takeIf { it.isConnected }
            val position = previousCastState?.positionMs ?: player.getRealtimePositionMs()
            val playing = previousCastState?.isPlaying ?: player.isPlaying.value
            val duration = previousCastState?.durationMs?.takeIf { it > 0L } ?: player.duration.value
            val volume = previousCastState?.volume ?: player.volume.value
            if (previousCastState == null && playing) player.pause()
            previousCastState?.let { castDisconnectSuppression.beginReplacement() }
            val connected =
                try {
                    castManager.cast(
                        device = device,
                        media =
                            CastMedia(
                                song,
                                song.previewThumbnailUrl,
                                resource,
                                duration,
                                player.queueState.value.let {
                                    it.entries.getOrNull(it.currentIndex)
                                },
                            ),
                        positionMs = position,
                        autoplay = playing,
                        volume = volume,
                        shouldCommit = ::isCurrentHandoff,
                    )
                } finally {
                    previousCastState?.let {
                        castDisconnectSuppression.finishReplacement(
                            disconnected = !castManager.state.value.isConnected,
                        )
                    }
                }
            if (!connected && isCurrentHandoff()) restoreLocalPlaybackAfterCast(position, playing)
        }
    }

    private suspend fun resolveCastResource(
        song: SongItem,
        protocol: CastProtocol?,
    ): PlaybackResource? =
        player.currentPlaybackResource.value?.takeIf {
            it.videoId == song.id && it.clientName != "local" && !it.isSabr && it.contentLengthBytes != null && it.mimeType != null &&
                it.isPlayableOn(protocol)
        } ?: withContext(Dispatchers.IO) {
            runCatching {
                streamResolver.resolveRemote(
                    song.id,
                    song.toAndroidContentHints(wantVideo = false).copy(
                        playbackClientOverrideId = null,
                        sabrFirst = false,
                    ),
                    castAudioQuality(protocol) ?: playbackAudioQuality(),
                    sourceSong = song,
                )
            }.getOrNull()
        }

    fun disconnectCast() {
        serviceScope.launch {
            val remoteState = castManager.state.value
            castDisconnectSuppression.beginExplicitDisconnect()
            try {
                castManager.disconnect()
            } finally {
                castDisconnectSuppression.finishExplicitDisconnect(
                    disconnected = !castManager.state.value.isConnected,
                )
            }
            restoreLocalPlaybackAfterCast(remoteState.positionMs, remoteState.isPlaying)
        }
    }

    private fun restoreLocalPlaybackAfterCast(
        positionMs: Long,
        resumePlayback: Boolean,
    ) {
        if (player.retryPendingQueuePlayback(positionMs, resumePlayback)) return
        player.seekTo(positionMs, PlaybackSeekSource("queue_restore", mapOf("reason" to "cast_disconnect")))
        if (resumePlayback) player.resume() else player.pause()
    }

    fun toggleCastPlayback() = setCastPlayback(!castManager.state.value.isPlaying)

    fun setCastPlayback(playing: Boolean) {
        serviceScope.launch { if (playing) castManager.play() else castManager.pause() }
    }

    fun stopCastPlayback() {
        serviceScope.launch { castManager.stop() }
    }

    fun seekCastTo(positionMs: Long) {
        serviceScope.launch {
            castManager.seekTo(positionMs)
            updateWidgetProgress()
        }
    }

    fun seekTo(
        positionMs: Long,
        source: PlaybackSeekSource = PlaybackSeekSource.Other,
    ) {
        if (player.currentSong.value?.isLive == true) return
        transitions.cancelAndroidCrossfade()
        player.seekTo(positionMs, source)
    }

    fun setCastVolume(volume: Float) {
        serviceScope.launch { castManager.setVolume(volume) }
    }

    private fun observeSettingsChanges() {
        serviceScope.launch {
            snapshotFlow { Settings.shared.pauseOnMute }.collect {
                pauseOnMute.reset()
                handleDeviceVolumeChanged()
            }
        }
        serviceScope.launch {
            combine(player.playbackSpeed, player.duration) { _, _ -> Unit }.collect { syncDiscordState() }
        }
        serviceScope.launch {
            DiscordRpcManager.settingsRevision.collect { syncDiscordState() }
        }
        serviceScope.launch {
            combine(
                snapshotFlow { Settings.shared.enableDiscordIntegration },
                player.isPlaying,
                listeningHistory.reportingEnabled,
            ) { enabled, playing, reporting -> enabled && playing && reporting }
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    if (!enabled) {
                        DiscordRpcManager.clear()
                        return@collectLatest
                    }
                    while (isActive) {
                        syncDiscordState()
                        delay(30_000.milliseconds)
                    }
                }
        }
    }

    private fun setupNotificationChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat
                .Builder("playback", NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.playback_notification_channel_name))
                .build(),
        )
    }

    private fun setupMediaSession() {
        val sessionActivityPendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    action = ACTION_OPEN_PLAYER
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        mediaSession =
            MediaLibrarySession
                .Builder(this, media3Player!!, AutomotiveSessionCallback())
                .setCommandButtonsForMediaItems(
                    listOf(
                        CommandButton
                            .Builder(CommandButton.ICON_QUEUE_ADD)
                            .setCustomIconResId(R.drawable.ic_notification_queue_add)
                            .setDisplayName(getString(R.string.add_to_queue))
                            .setSessionCommand(MediaSessionConstants.CommandAddToQueue)
                            .setIconUri(Uri.parse("android.resource://$packageName/${R.drawable.ic_notification_queue_add}"))
                            .build(),
                    ),
                ).setSessionActivity(sessionActivityPendingIntent)
                .setMediaButtonPreferences(
                    ImmutableList.copyOf(transportButtons() + listOf(createLikeButton(), createRepeatButton(), createShuffleButton())),
                ).build()
                .also(::addSession)

        serviceScope.launch {
            (application as App).startup.await()
            automotiveLibrary.observeLikedSongs { count ->
                mediaSession?.notifyChildrenChanged(AutomotiveMediaIds.LIKED, count, null)
            }
        }
        serviceScope.launch {
            (application as App).startup.await()
            val startRadioLabel = getString(SharedRes.string.sharedStartRadio)
            combine(player.currentSong, SongLikeState.likedSongsFlow, _repeatMode, _shuffleModeEnabled) { song, _, repeatMode, shuffle ->
                listOf(song?.id, song?.id?.let(SongLikeState::isLiked), repeatMode, shuffle)
            }.distinctUntilChanged().collect {
                mediaSession?.setMediaButtonPreferences(
                    ImmutableList.copyOf(
                        transportButtons() +
                            listOf(
                                createLikeButton(),
                                createRepeatButton(),
                                createShuffleButton(),
                                createRadioButton(startRadioLabel),
                                createAddToPlaylistButton(),
                            ),
                    ),
                )
            }
        }
    }

    private fun observePlaybackEvents() {
        serviceScope.launch {
            combine(
                player.isPlaying,
                player.wantsPlaybackState,
                player.playbackStateFlow,
                player.currentSong,
                player.duration,
            ) { _, _, _, _, _ -> Unit }.collect { AndroidDeviceConnect.manager.remote.localStateChanged() }
        }
        serviceScope.launch {
            merge(
                player.currentSong.map { Unit },
                player.currentPosition.map { Unit },
                player.isPlaying.map { Unit },
            ).collect { updatePodcastProgress() }
        }
        serviceScope.launch {
            observeSponsorBlockSkipping(
                currentSong = player.currentSong,
                currentPosition = player.currentPosition,
                duration = player.duration,
                isPlaying = player.isPlaying,
                enabled = snapshotFlow { Settings.shared.sponsorBlockEnabled },
                canSeek = {
                    Settings.shared.sponsorBlockEnabled && !castManager.state.value.isConnected &&
                        !AndroidListenTogetherManager.isGuestPlaybackLocked && !AndroidDeviceConnect.manager.remote.isActive
                },
                seekTo = { position ->
                    updatePendingRestorePosition(position)
                    player.seekTo(position, PlaybackSeekSource("sponsorblock"))
                },
                onSkipped = { PlaybackSnackbarEvents.emit(getString(R.string.sponsorblock_skipped)) },
            )
        }
        serviceScope.launch {
            releaseIdleTokenProvider(player.wantsPlaybackState, isPlaybackLoading, tokenProvider)
        }
        serviceScope.launch {
            observeAndroidScrobbles(player) { song, playing, newPlayback ->
                scrobbleManager.onPlayerStateChanged(playing, song, newPlayback)
            }
        }
        serviceScope.launch {
            player.isPlaying.collectLatest { isPlaying ->
                if (isPlaying) {
                    Settings.shared.pendingContinuePlayback = false
                    pauseOnMute.cancelResume()
                }
                if (Settings.shared.enableDiscordIntegration) syncDiscordState()
            }
        }

        serviceScope.launch {
            player.currentSong.collectLatest { song ->
                videoMode.onSongChanged(song)
                if (Settings.shared.enableDiscordIntegration) syncDiscordState()
            }
        }
        serviceScope.launch {
            SongMetadataState.revision.collectLatest {
                if (Settings.shared.enableDiscordIntegration) syncDiscordState()
            }
        }

        serviceScope.launch {
            player.queuePlaybackRequests.collectLatest { request ->
                try {
                    playQueueRequest(request)
                } finally {
                    player.finishPlaybackTransition(request.requestId)
                }
            }
        }

        serviceScope.launch {
            PlaybackNetworkStateStore.state.collect { network ->
                if (network.connected) player.invalidateActiveStreamForNetworkChange()
            }
        }

        serviceScope.launch {
            TokenlessStreamRenewal.observe(
                player.currentPlaybackResource,
                player.currentPosition,
                isActive = {
                    player.wantsPlayback && !player.playbackTransitionPending.value && !transitions.isCrossfading &&
                        !castManager.state.value.isConnected && !AndroidDeviceConnect.manager.remote.isFollowing
                },
            ) { resource ->
                val song = player.currentSong.value ?: return@observe false
                val instance = player.currentPlaybackInstanceId
                val request = player.latestQueuePlaybackRequestId
                val isCurrent = {
                    player.currentPlaybackInstanceId == instance && player.latestQueuePlaybackRequestId == request &&
                        player.currentPlaybackResource.value === resource && !player.playbackTransitionPending.value &&
                        !transitions.isCrossfading && !castManager.state.value.isConnected &&
                        !AndroidDeviceConnect.manager.remote.isFollowing
                }
                TokenlessStreamRenewal.renew(
                    resource,
                    player::getRealtimePositionMs,
                    isCurrent,
                    resolve = { excluded ->
                        streamResolver.resolveRemote(
                            song.id,
                            song
                                .toAndroidContentHints(wantVideo = Settings.shared.videoViewerVisible)
                                .copy(playbackClientOverrideId = null),
                            playbackAudioQuality(),
                            song,
                            refresh = true,
                            excludedProfiles = excluded,
                        )
                    },
                ) { fresh ->
                    player.prepareAndPlay(
                        song,
                        fresh,
                        positionMs = player.getRealtimePositionMs(),
                        resumePlayback = player.wantsPlayback,
                        positionMsProvider = player::getRealtimePositionMs,
                        playWhenReadyProvider = { player.wantsPlayback },
                        shouldCommit = isCurrent,
                        recoveryPlaybackInstanceId = instance,
                        seekSource = PlaybackSeekSource("recovery", mapOf("reason" to "tokenless_renewal")),
                    )
                }
            }
        }

        serviceScope.launch {
            player.streamFailure.collectLatest { request ->
                try {
                    (media3Player as? MediaSessionPlayer)?.setTerminalError(null)
                    handleStreamFailure(request.failure, request.freshAudioSession)
                } finally {
                    player.finishPlaybackRecovery(request.transitionId)
                }
            }
        }

        serviceScope.launch {
            player.onEOFPlaybackInstance.collect(::handlePlaybackCompletion)
        }
        serviceScope.launch {
            combine(player.currentSong, player.currentPlaybackResource) { _, _ ->
                player.currentSong.value to player.currentPlaybackResource.value
            }.distinctUntilChanged().collect { (song, resource) ->
                when {
                    song == null -> {
                        PlaybackErrorReporter.clearActivePlayback()
                    }

                    resource?.videoId == song.id -> {
                        PlaybackErrorReporter.setActivePlayback(song, resource)
                    }

                    resource != null -> {
                        PlaybackEventLog.warn(
                            PlaybackEventKind.PLAYER,
                            "Android playback media and metadata IDs differ.",
                            videoId = resource.videoId,
                            details =
                                mapOf(
                                    "mediaId" to resource.videoId,
                                    "metadataId" to song.id,
                                    "queueRequestId" to player.latestQueuePlaybackRequestId.toString(),
                                    "playbackInstanceId" to player.currentPlaybackInstanceId.toString(),
                                    "isPlaying" to player.isPlaying.value.toString(),
                                    "transitionPending" to player.playbackTransitionPending.value.toString(),
                                ),
                        )
                    }
                }
            }
        }
        transitions.observe(sleepTimerController)
        queueCoordinator.observeAutoLoadMore()
        serviceScope.launch {
            combine(
                player.currentPosition
                    .map {
                        it >= HEALTHY_PLAYBACK_THRESHOLD_MS
                    }.distinctUntilChanged(),
                player.currentPlaybackResource,
                player.currentSong,
            ) { healthy, resource, song ->
                Triple(healthy, resource, song)
            }.collectLatest { (healthy, resource, song) ->
                val requestId = player.latestQueuePlaybackRequestId
                if (
                    healthy &&
                    resource != null &&
                    song != null &&
                    requestId !in healthyQueueRequestIds
                ) {
                    consecutiveFailureSkips = 0
                    healthyQueueRequestIds += requestId
                    while (healthyQueueRequestIds.size > MAX_HEALTHY_REQUEST_HISTORY) {
                        healthyQueueRequestIds.remove(healthyQueueRequestIds.first())
                    }
                    withContext(Dispatchers.IO) {
                        streamResolver.reportPlaybackSuccess(
                            profileId = resource.profileId,
                            hints = song.toAndroidContentHints(wantVideo = Settings.shared.videoViewerVisible),
                        )
                    }
                }
            }
        }
    }

    private fun publishTerminalPlaybackError(error: Throwable?) {
        val code = automotivePlaybackErrorCode(error)
        val needsLogin = code == PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED
        player.pause()
        val extras =
            Bundle().apply {
                if (needsLogin) {
                    putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, getString(R.string.sign_in))
                    putParcelable(
                        MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT,
                        PendingIntent.getActivity(
                            this@MusicService,
                            0,
                            Intent(this@MusicService, MainActivity::class.java).setAction(Intent.ACTION_APPLICATION_PREFERENCES),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
            }
        val message = getString(if (needsLogin) R.string.sign_in_to_youtube_music else R.string.playback_unable_to_play_queued_track)
        (media3Player as? MediaSessionPlayer)?.setTerminalError(PlaybackException(message, error, code, extras))
    }

    private suspend fun skipFailedTrackOrStop(error: Throwable?) {
        if (AndroidListenTogetherManager.isGuestPlaybackLocked || AndroidDeviceConnect.manager.remote.isFollowing ||
            consecutiveFailureSkips >= MAX_CONSECUTIVE_FAILURE_SKIPS
        ) {
            publishTerminalPlaybackError(error)
            return
        }
        consecutiveFailureSkips++
        // A terminal error would idle the session while the next track plays and drop its notification.
        advanceQueueAfterCurrent()
    }

    private suspend fun handleStreamFailure(
        failure: PlaybackFailure,
        freshAudioSession: Boolean = false,
    ) {
        Timber.tag(TAG).e("Playback failure stage=%s category=%s", failure.stage, failure.category)
        val song = player.currentSong.value ?: return
        val videoId = failure.videoId ?: song.id
        val failedPlaybackInstanceId = failure.playbackInstanceId ?: return
        val queueRequestId = player.latestQueuePlaybackRequestId
        if (
            videoId != song.id ||
            failedPlaybackInstanceId != player.currentPlaybackInstanceId
        ) {
            return
        }
        if (freshAudioSession) {
            val resource = player.currentPlaybackResource.value ?: return
            if (transitions.crossfadeState.value != null || videoMode.isVideoModeResolving.value) return
            transitions.cancelNextTrackPreload()
            val previousSessionId = player.getAudioSessionId()
            val recovered =
                try {
                    player.prepareAndPlay(
                        song = song,
                        resource = resource,
                        positionMs = player.getRealtimePositionMs(),
                        resumePlayback = player.wantsPlayback,
                        positionMsProvider = player::getRealtimePositionMs,
                        playWhenReadyProvider = { player.wantsPlayback },
                        shouldCommit = {
                            player.currentPlaybackInstanceId == failedPlaybackInstanceId &&
                                player.latestQueuePlaybackRequestId == queueRequestId &&
                                player.currentPlaybackResource.value === resource &&
                                transitions.crossfadeState.value == null && !videoMode.isVideoModeResolving.value
                        },
                        recoveryPlaybackInstanceId = failedPlaybackInstanceId,
                        freshAudioSession = true,
                        seekSource = PlaybackSeekSource("recovery", mapOf("reason" to failure.message)),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    PlaybackEventLog.warn(PlaybackEventKind.RECOVERY, "Android Bluetooth output replacement failed.", error = error)
                    false
                }
            PlaybackEventLog.info(
                PlaybackEventKind.RECOVERY,
                "Android Bluetooth output replacement finished.",
                videoId = videoId,
                details =
                    mapOf(
                        "recovered" to recovered.toString(),
                        "previousAudioSessionId" to previousSessionId.toString(),
                        "audioSessionId" to player.getAudioSessionId().toString(),
                        "playbackPositionMs" to player.getRealtimePositionMs().toString(),
                    ),
            )
            return
        }
        transitions.cancelAndroidCrossfade()
        transitions.cancelNextTrackPreload()
        if (!PlaybackRecoveryPolicy.canRecover(failure)) {
            failure.cause?.let { cause ->
                PlaybackErrorReporter.report(
                    cause,
                    PlaybackErrorContext(stage = failure.stage, videoId = videoId, recovered = false, song = song),
                )
            }
            player.finishInterruptedPlayback(failedPlaybackInstanceId, failure.playbackPositionMs)
            if (shouldAdvanceAfterPlaybackFailure(
                    player.wantsPlayback,
                    failure.category,
                    PlaybackNetworkStateStore.current.connected,
                    repeatMode = repeatMode.value,
                    autoSkipNextOnError = Settings.shared.autoSkipNextOnError,
                )
            ) {
                skipFailedTrackOrStop(failure.cause)
            } else {
                publishTerminalPlaybackError(failure.cause)
            }
            return
        }

        if (streamRecoveryVideoId != videoId) {
            streamRecoveryVideoId = videoId
            streamRecoveryBudget = PlaybackRecoveryBudget.starting(SystemClock.elapsedRealtime())
        }

        val hints = song.toAndroidContentHints(wantVideo = Settings.shared.videoViewerVisible)
        var failedResource = player.currentPlaybackResource.value
        var lastFailure = failure.cause ?: IllegalStateException(failure.message)
        var lastHttpStatusCode = failure.httpStatusCode ?: lastFailure.findMedia3HttpStatusCode()
        var failureCategory =
            failure.category.takeUnless { it == PlaybackFailureCategory.UNKNOWN }
                ?: lastHttpStatusCode?.let(PlaybackFailureClassifier::fromHttpStatus)
                ?: lastFailure.media3PlaybackFailureCategory()
        val excludedProfiles =
            PlaybackRecoveryPolicy
                .recoveryExclusions(
                    failureCategory,
                    failedResource,
                    failure.playbackPositionMs,
                ).toMutableSet()
        var budget =
            (streamRecoveryBudget ?: PlaybackRecoveryBudget.starting(SystemClock.elapsedRealtime()))
                .forPlaybackFailure(failure.playbackPositionMs, SystemClock.elapsedRealtime(), failureCategory)
        var retryNetworkResource = true
        PlaybackEventLog.warn(
            kind = PlaybackEventKind.RECOVERY,
            message = "Android playback recovery started.",
            videoId = videoId,
            clientName = failedResource?.clientName,
            profileId = failedResource?.profileId,
            details = failure.toPlaybackLogDetails(),
            error = failure.cause,
        )
        while (
            !(failure.isMediaSourceFailure && failureCategory == PlaybackFailureCategory.NETWORK) &&
            budget.canStart(SystemClock.elapsedRealtime()) &&
            player.currentPlaybackInstanceId == failedPlaybackInstanceId &&
            player.latestQueuePlaybackRequestId == queueRequestId &&
            player.currentSong.value?.id == videoId
        ) {
            val decision =
                PlaybackRecoveryPolicy.decide(
                    category = failureCategory,
                    budget = budget,
                    nowMs = SystemClock.elapsedRealtime(),
                    resource = failedResource,
                    positionMs = failure.playbackPositionMs,
                )
            budget = decision.budget
            if (decision.action == PlaybackRecoveryAction.NONE) break
            excludedProfiles += PlaybackRecoveryPolicy.recoveryExclusions(failureCategory, failedResource, failure.playbackPositionMs)
            PlaybackEventLog.info(
                kind = PlaybackEventKind.RECOVERY,
                message = "Trying Android playback recovery action ${decision.action}.",
                videoId = videoId,
                clientName = failedResource?.clientName,
                profileId = failedResource?.profileId,
                details =
                    mapOf(
                        "recoveryAction" to decision.action.name,
                        "failureCategory" to failureCategory.name,
                        "httpStatus" to (lastHttpStatusCode?.toString() ?: "unknown"),
                        "attemptsRemaining" to budget.attemptsRemaining.toString(),
                        "requestsRemaining" to budget.requestsRemaining.toString(),
                    ),
            )

            failedResource
                ?.takeIf {
                    decision.action == PlaybackRecoveryAction.RETRY_CLIENT ||
                        failureCategory == PlaybackFailureCategory.CDN_FORBIDDEN ||
                        failureCategory == PlaybackFailureCategory.SABR_PROTOCOL ||
                        failureCategory == PlaybackFailureCategory.SABR_ATTESTATION
                }?.let { resource ->
                    streamResolver.reportPlaybackFailure(
                        videoId = videoId,
                        clientName = resource.clientName,
                        responseCode = lastHttpStatusCode ?: lastFailure.findPlaybackHttpStatusCode(),
                        profileId = resource.profileId,
                        hints = hints,
                        category = failureCategory,
                    )
                }

            val recoveryResource =
                recoverPlaybackResource(
                    streamResolver = streamResolver,
                    retryNetworkResource = retryNetworkResource,
                    now = Clock.System.now(),
                    action = decision.action,
                    currentResource = failedResource,
                    failure = lastFailure,
                    videoId = videoId,
                    hints = hints,
                    sourceSong = song,
                    budget = budget,
                    positionMs = failure.playbackPositionMs,
                    excludedProfiles = excludedProfiles,
                    category = failureCategory,
                )
            if (decision.action == PlaybackRecoveryAction.INVALIDATE_NETWORK) retryNetworkResource = false
            budget = recoveryResource.budget
            streamRecoveryBudget = budget
            val freshResource = recoveryResource.resource
            if (freshResource == null) {
                lastFailure = recoveryResource.failure ?: lastFailure
                failureCategory = lastFailure.media3PlaybackFailureCategory()
                break
            }

            var prepareFailure: Throwable? = null
            val recovered =
                player.prepareAndPlay(
                    song = song,
                    resource = freshResource,
                    positionMs = player.getRealtimePositionMs(),
                    resumePlayback = player.wantsPlayback,
                    positionMsProvider = player::getRealtimePositionMs,
                    playWhenReadyProvider = { player.wantsPlayback },
                    shouldCommit = {
                        player.currentPlaybackInstanceId == failedPlaybackInstanceId &&
                            player.latestQueuePlaybackRequestId == queueRequestId &&
                            player.currentSong.value?.id == videoId
                    },
                    onPrepareFailure = { prepareFailure = it },
                    recoveryPlaybackInstanceId = failedPlaybackInstanceId,
                    seekSource = PlaybackSeekSource("recovery", mapOf("reason" to failure.stage, "category" to failure.category.name)),
                )
            if (recovered) {
                videoMode.onQueuePlaybackStarted(song, freshResource.hasVideo, freshResource)
                PlaybackEventLog.info(
                    kind = PlaybackEventKind.RECOVERY,
                    message = "Android playback recovered.",
                    videoId = videoId,
                    clientName = freshResource.clientName,
                    profileId = freshResource.profileId,
                    details =
                        mapOf(
                            "recoveryAction" to decision.action.name,
                            "playbackPositionMs" to failure.playbackPositionMs.toString(),
                            "transport" to if (freshResource.isSabr) "SABR" else "Direct",
                        ),
                )
                return
            }

            failedResource = freshResource
            lastFailure = prepareFailure ?: IllegalStateException("Refreshed stream could not be prepared")
            lastHttpStatusCode = lastFailure.findMedia3HttpStatusCode() ?: lastFailure.findPlaybackHttpStatusCode()
            failureCategory =
                lastHttpStatusCode?.let(PlaybackFailureClassifier::fromHttpStatus)
                    ?: lastFailure.media3PlaybackFailureCategory()
            if (PlaybackRecoveryPolicy.requiresFreshUrl(failureCategory)) {
                budget = budget.forPlaybackFailure(failure.playbackPositionMs, SystemClock.elapsedRealtime(), failureCategory)
            }
        }

        val networkFailure = PlaybackNetworkRecovery.handles(failure, failureCategory)
        if (networkFailure) {
            val recovered =
                PlaybackNetworkRecovery.recover(
                    isCurrent = {
                        player.currentPlaybackInstanceId == failedPlaybackInstanceId &&
                            player.latestQueuePlaybackRequestId == queueRequestId && player.currentSong.value?.id == videoId
                    },
                    connected = { PlaybackNetworkStateStore.current.connected },
                    wantsPlayback = { player.wantsPlayback },
                    positionMs = player::getRealtimePositionMs,
                    onReconnecting = { isReconnecting.value = it },
                    stateChanges = merge(player.wantsPlaybackState.map { Unit }, PlaybackNetworkStateStore.state.map { Unit }),
                ) {
                    val recoveryContext = currentCoroutineContext()
                    try {
                        streamResolver.invalidateNetworkScope(PlaybackNetworkStateStore.current)
                        val excluded =
                            excludedProfiles +
                                PlaybackRecoveryPolicy.recoveryExclusions(failureCategory, failedResource, failure.playbackPositionMs)
                        val fresh =
                            withContext(Dispatchers.IO) {
                                streamResolver.resolveRemote(
                                    videoId,
                                    if (excluded.isEmpty()) hints else hints.copy(playbackClientOverrideId = null),
                                    playbackAudioQuality(),
                                    sourceSong = song,
                                    refresh = true,
                                    excludedProfiles = excluded,
                                )
                            }
                        player.prepareAndPlay(
                            song = song,
                            resource = fresh,
                            positionMs = player.getRealtimePositionMs(),
                            resumePlayback = player.wantsPlayback,
                            positionMsProvider = player::getRealtimePositionMs,
                            playWhenReadyProvider = { player.wantsPlayback },
                            shouldCommit = {
                                recoveryContext.isActive && player.currentPlaybackInstanceId == failedPlaybackInstanceId &&
                                    player.latestQueuePlaybackRequestId == queueRequestId && player.currentSong.value?.id == videoId
                            },
                            onPrepareFailure = { it?.let { error -> lastFailure = error } },
                            recoveryPlaybackInstanceId = failedPlaybackInstanceId,
                            seekSource =
                                PlaybackSeekSource(
                                    "recovery",
                                    mapOf("reason" to failure.stage, "category" to failure.category.name),
                                ),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        lastFailure = error
                        false
                    }
                }
            if (recovered) {
                streamRecoveryBudget =
                    PlaybackRecoveryBudget
                        .starting(
                            SystemClock.elapsedRealtime(),
                        ).copy(urlRefreshesRemaining = budget.urlRefreshesRemaining)
                return
            }
        }

        if (
            player.currentPlaybackInstanceId == failedPlaybackInstanceId &&
            player.latestQueuePlaybackRequestId == queueRequestId &&
            player.currentSong.value?.id == videoId
        ) {
            Timber.tag(TAG).e("Stream recovery exhausted type=%s", lastFailure::class.simpleName)
            PlaybackErrorReporter.report(
                lastFailure,
                PlaybackErrorContext(
                    stage = "stream_retry_exhausted",
                    videoId = videoId,
                    clientName = failedResource?.clientName,
                    profileId = failedResource?.profileId,
                    recovered = false,
                    song = song,
                    resource = failedResource,
                ),
            )
            PlaybackEventLog.error(
                kind = PlaybackEventKind.RECOVERY,
                message = "Android playback recovery exhausted.",
                videoId = videoId,
                clientName = failedResource?.clientName,
                profileId = failedResource?.profileId,
                details =
                    mapOf(
                        "failureCategory" to failureCategory.name,
                        "httpStatus" to (lastHttpStatusCode?.toString() ?: "unknown"),
                        "attemptsRemaining" to budget.attemptsRemaining.toString(),
                        "requestsRemaining" to budget.requestsRemaining.toString(),
                    ),
                error = lastFailure,
            )
            if (networkFailure) player.pause()
            player.finishInterruptedPlayback(failedPlaybackInstanceId, player.getRealtimePositionMs())
            streamRecoveryBudget = null
            if (shouldAdvanceAfterPlaybackFailure(
                    player.wantsPlayback,
                    failureCategory,
                    PlaybackNetworkStateStore.current.connected,
                    originalCategory = failure.category,
                    repeatMode = repeatMode.value,
                    autoSkipNextOnError = Settings.shared.autoSkipNextOnError,
                )
            ) {
                skipFailedTrackOrStop(lastFailure)
            } else {
                publishTerminalPlaybackError(lastFailure)
            }
        }
    }

    private suspend fun playQueueRequest(
        request: QueuePlaybackRequest,
        stagedShouldCommit: (() -> Boolean)? = null,
    ): Boolean {
        playbackBenchmark.cancel()
        (media3Player as? MediaSessionPlayer)?.setTerminalError(null)
        transitions.cancelAndroidCrossfade()
        transitions.cancelNextTrackPreload()
        val remoteState = castManager.state.value.takeIf { it.isConnected }
        videoMode.clearVideoMode(resetViewerPreference = false)
        val sessionId = queueCoordinator.queueSessionId

        fun isCurrentRequest(
            queueSongId: String = request.song.id,
            expectedWantVideo: Boolean? = null,
        ): Boolean =
            if (stagedShouldCommit == null) {
                isCurrentQueueRequest(sessionId, request, queueSongId, expectedWantVideo)
            } else {
                stagedShouldCommit() &&
                    (
                        expectedWantVideo == null ||
                            isQueuePlaybackModeCurrent(
                                song = request.song,
                                requestedWantVideo = expectedWantVideo,
                                currentVideoViewerVisible = Settings.shared.videoViewerVisible,
                                videoPlaybackEnabled = Settings.shared.musicVideoPlaybackEnabled,
                            )
                    )
            }
        podcastProgress.flush()?.join()
        val requestedPosition =
            if (stagedShouldCommit == null) queueCoordinator.takeRestorePosition(request.song, request.positionMs) else request.positionMs
        val targetPosition =
            withContext(Dispatchers.IO) {
                PodcastLibrary.resumePosition(
                    database,
                    request.song,
                    requestedPosition.takeIf {
                        it > 0L || AndroidListenTogetherManager.isGuestPlaybackLocked || AndroidDeviceConnect.manager.remote.isActive
                    },
                )
            }
        if (!isCurrentRequest()) return false
        if (remoteState != null) {
            val resource = withQueueLoading(isPlaybackLoading = true) { resolveCastResource(request.song, remoteState.device?.protocol) }
            if (!isCurrentRequest()) return false
            if (resource == null) castManager.showError(CastError.UNSUPPORTED_STREAM)
            val remoteAutoplay = remoteState.isPlaying || remoteState.hasEnded
            when (
                routeCastQueueReplacement(
                    suppression = castDisconnectSuppression,
                    isCurrentRequest = { isCurrentRequest() },
                    isConnected = { castManager.state.value.isConnected },
                    replace = {
                        resource != null &&
                            castManager.replaceMedia(
                                media =
                                    CastMedia(
                                        request.song,
                                        request.song.previewThumbnailUrl,
                                        resource,
                                        player.duration.value,
                                        player.queueState.value.let { it.entries.getOrNull(it.currentIndex) },
                                    ),
                                positionMs = targetPosition,
                                autoplay = remoteAutoplay,
                                volume = remoteState.volume,
                            )
                    },
                    disconnect = { castManager.disconnect() },
                    onFailure = { error ->
                        Timber.tag(TAG).w("Cast queue replacement failed type=%s", error::class.simpleName)
                    },
                )
            ) {
                CastQueueReplacementResult.REMOTE -> {
                    streamRecoveryVideoId = null
                    streamRecoveryBudget = null
                    if ((!queueCoordinator.isFiniteQueue || Settings.shared.autoLoadMore) &&
                        queueCoordinator.recommendationBootstrapJob == null
                    ) {
                        bootstrapRecommendations(queueCoordinator.recommendationSeedSongId ?: request.song.id)
                    }
                    return true
                }

                CastQueueReplacementResult.STALE -> {
                    return false
                }

                CastQueueReplacementResult.LOCAL -> {
                    player.setPendingPlaybackIntent(request.requestId, remoteAutoplay)
                }
            }
        }
        if (!PlaybackNetworkStateStore.current.connected && !request.song.isLocal &&
            !request.song.id.startsWith("local_") && audioCacheManager.getCachedPlaybackResource(request.song, touch = false) == null
        ) {
            if (stagedShouldCommit == null) skipUnavailableOfflineSong(sessionId, request)
            return false
        }
        val wantVideo =
            shouldRequestQueueVideo(
                isVideoSong = request.song.isVideoSong,
                isVideoModeActive = Settings.shared.videoViewerVisible,
                videoPlaybackEnabled = Settings.shared.musicVideoPlaybackEnabled,
                isLocal = request.song.isLocal || request.song.id.startsWith("local_"),
                isPodcast = request.song.isPodcast,
            )
        PlaybackEventLog.info(
            PlaybackEventKind.QUEUE,
            "Android queue playback requested.",
            videoId = request.song.id,
            details =
                request.song.toPlaybackLogDetails() +
                    mapOf(
                        "wantVideo" to wantVideo.toString(),
                        "videoViewerVisible" to Settings.shared.videoViewerVisible.toString(),
                        "queueRequestId" to request.requestId.toString(),
                        "playbackPositionMs" to targetPosition.toString(),
                    ),
        )
        val sourceSong = SongMetadataState.apply(request.song)
        val sync = AndroidDeviceConnect.manager.remote
        val syncSessionId = sync.state.value.sessionId
        val syncQueueRevision = sync.queueRevision
        val syncTrackRevision = sync.trackRevision
        var playbackSong = request.song
        var playbackPosition = targetPosition
        var requestHandledBySync = false

        fun canCommitSelection(): Boolean =
            isCurrentRequest(expectedWantVideo = wantVideo) &&
                canCommitAudioTrackSelection(
                    sourceSongId = sourceSong.id,
                    selectedSongId = playbackSong.id,
                    deviceConnectActive = sync.isActive,
                    listenTogetherGuestPlaybackLocked = AndroidListenTogetherManager.isGuestPlaybackLocked,
                )
        var errorReason: String? = null
        var playbackFailure: Throwable? = null
        var urlRecoveryBudget: PlaybackRecoveryBudget? = null
        val started =
            withQueueLoading(isPlaybackLoading = true) {
                val cachedAudio =
                    if (!wantVideo && sourceSong.canSwitchToAudioTrack) {
                        audioCacheManager.getCachedPlaybackResource(sourceSong)
                    } else {
                        null
                    }
                val selection =
                    resolveAndroidAudioTrackSelection(
                        sourceSong = sourceSong,
                        positionMs = targetPosition,
                        wantVideo = wantVideo,
                        cachedAudio = cachedAudio,
                        allowCounterpartLookup =
                            PlaybackNetworkStateStore.current.connected && !AndroidListenTogetherManager.isGuestPlaybackLocked,
                        isCounterpartAllowed = { !BlockedContent.isBlocked(it) },
                        resolveMusicTrack = musicVideoResolver::resolveMusicTrack,
                    )
                playbackSong = selection.song
                playbackPosition = selection.positionMs
                if (!isCurrentRequest(expectedWantVideo = wantVideo)) return@withQueueLoading false
                if (sync.isActive && playbackSong.id != sourceSong.id) {
                    requestHandledBySync = true
                    return@withQueueLoading sync.intercept(
                        DcPlaybackCommand(
                            DcPlaybackAction.REPLACE_CURRENT,
                            songs = listOf(playbackSong),
                            positionMs = playbackPosition,
                            expectedTrackRevision = syncTrackRevision,
                        ),
                        expectedQueueRevision = syncQueueRevision,
                        expectedSessionId = syncSessionId,
                    )
                }
                var hints = playbackSong.toAndroidContentHints(wantVideo = wantVideo)
                var resolveBudget = queuePlaybackResolveBudget(SystemClock.elapsedRealtime(), hints.playbackClientOverrideId)
                val resolveTimeoutMessage = getString(Res.string.playback_unable_to_play_queued_track)

                suspend fun resolveWithinBudget(video: Boolean = wantVideo): PlaybackFetchResult =
                    withQueuePlaybackResolveDeadline(resolveBudget.deadlineAtMs, SystemClock::elapsedRealtime) {
                        fetchPlaybackResource(playbackSong, wantVideo = video)
                    } ?: PlaybackFetchResult(
                        errorReason = resolveTimeoutMessage,
                        failure = TimeoutException("Queue stream resolution exceeded its 30000ms deadline"),
                    )
                var result =
                    selection.cachedAudio?.let { PlaybackFetchResult(resource = it) }
                        ?: resolveWithinBudget()
                while (
                    result.resource == null &&
                    result.failure != null &&
                    PlaybackNetworkStateStore.current.connected &&
                    resolveBudget.canStart(SystemClock.elapsedRealtime())
                ) {
                    if (!canCommitSelection()) return@withQueueLoading false
                    val category = PlaybackFailureClassifier.fromThrowable(result.failure)
                    val decision =
                        PlaybackRecoveryPolicy.decide(
                            category = category,
                            budget = resolveBudget,
                            nowMs = SystemClock.elapsedRealtime(),
                        )
                    resolveBudget = decision.budget
                    if (decision.action == PlaybackRecoveryAction.NONE) break

                    delay(QUEUE_RESOLVE_RETRY_DELAY_MS.milliseconds)
                    if (!canCommitSelection()) return@withQueueLoading false
                    result =
                        if (decision.action == PlaybackRecoveryAction.RETRY_SAME_URL ||
                            decision.action == PlaybackRecoveryAction.REBUILD_RENDERER
                        ) {
                            resolveWithinBudget()
                        } else {
                            val recovery =
                                withQueuePlaybackResolveDeadline(resolveBudget.deadlineAtMs, SystemClock::elapsedRealtime) {
                                    recoverPlaybackResource(
                                        streamResolver = streamResolver,
                                        retryNetworkResource = false,
                                        now = Clock.System.now(),
                                        action = decision.action,
                                        currentResource = null,
                                        videoId = playbackSong.id,
                                        hints = hints,
                                        sourceSong = playbackSong,
                                        budget = resolveBudget,
                                        category = category,
                                    )
                                } ?: RecoveryResourceResult(
                                    resource = null,
                                    budget = resolveBudget.consumeRequest(),
                                    failure =
                                        TimeoutException(
                                            "Queue stream recovery exceeded its 30000ms deadline during ${decision.action}",
                                        ).apply { initCause(result.failure) },
                                )
                            resolveBudget = recovery.budget
                            PlaybackFetchResult(
                                resource = recovery.resource,
                                failure = recovery.failure,
                                errorReason =
                                    recovery.failure?.let {
                                        getString(
                                            it.playbackErrorResource(Settings.shared.innerTubeCookie.isNotBlank()),
                                        )
                                    }
                                        ?: resolveTimeoutMessage,
                            )
                        }
                }
                if (result.resource == null) {
                    fallbackToOriginalAudioSelection(sourceSong, playbackSong, targetPosition, cachedAudio)?.let { fallback ->
                        playbackSong = fallback.song
                        playbackPosition = fallback.positionMs
                        hints = playbackSong.toAndroidContentHints(wantVideo = false)
                        result = fallback.cachedAudio?.let { PlaybackFetchResult(resource = it) } ?: resolveWithinBudget(video = false)
                    }
                }
                errorReason = result.errorReason
                playbackFailure = result.failure
                var resource = result.resource ?: return@withQueueLoading false
                if (playbackSong.isPodcast && playbackSong.duration == null) {
                    playbackPosition =
                        withContext(Dispatchers.IO) {
                            PodcastLibrary.resumePosition(
                                database,
                                playbackSong,
                                requestedPosition.takeIf { it > 0L || AndroidListenTogetherManager.isGuestPlaybackLocked || sync.isActive },
                                durationMs = resource.durationSeconds?.times(1000L) ?: 0L,
                            )
                        }
                }
                var budget = PlaybackRecoveryBudget.starting(SystemClock.elapsedRealtime())
                while (budget.canStart(SystemClock.elapsedRealtime())) {
                    if (!canCommitSelection()) return@withQueueLoading false
                    val currentQueueSong =
                        selectionQueueSong(stagedQueueReplacement = stagedShouldCommit != null, sourceSong = sourceSong) {
                            player.songQueue.value
                                .getOrNull(player.currentMediaItemIndex.value)
                                ?.let(SongMetadataState::apply)
                        } ?: return@withQueueLoading false
                    val mergedSong = mergeSelectedQueueSong(playbackSong, sourceSong, currentQueueSong)
                    playbackSong =
                        if (resource.isLive && !mergedSong.isLive) {
                            mergedSong.copy(musicVideoType = MUSIC_VIDEO_TYPE_LIVE)
                        } else {
                            mergedSong
                        }
                    var prepareFailure: Throwable? = null
                    if (wantVideo && resource.hasVideo) {
                        videoMode.markQueueTarget(playbackSong.id)
                    }
                    if (
                        player.prepareAndPlay(
                            song = playbackSong,
                            resource = resource,
                            positionMs = playbackPosition,
                            resumePlayback = request.resumePlayback,
                            shouldCommit = ::canCommitSelection,
                            playWhenReadyProvider = { if (stagedShouldCommit == null) player.wantsPlayback else false },
                            positionMsProvider = if (stagedShouldCommit == null) null else ({ playbackPosition }),
                            stageQueueReplacement = stagedShouldCommit != null,
                            onPrepareFailure = { prepareFailure = it },
                            replaceCurrentQueueItem = playbackSong.id != request.song.id,
                        )
                    ) {
                        if (budget.urlRefreshesRemaining < 2) {
                            urlRecoveryBudget =
                                PlaybackRecoveryBudget.starting(SystemClock.elapsedRealtime()).copy(
                                    urlRefreshesRemaining = budget.urlRefreshesRemaining,
                                )
                        }
                        return@withQueueLoading true
                    }
                    if (!canCommitSelection()) return@withQueueLoading false

                    val currentFailure = prepareFailure ?: IllegalStateException("Prepared stream could not start")
                    val fallback = fallbackToOriginalAudioSelection(sourceSong, playbackSong, targetPosition, cachedAudio)
                    if (fallback != null) {
                        playbackSong = fallback.song
                        playbackPosition = fallback.positionMs
                        hints = playbackSong.toAndroidContentHints(wantVideo = false)
                        val originalAudio =
                            fallback.cachedAudio?.let { PlaybackFetchResult(resource = it) } ?: resolveWithinBudget(video = false)
                        errorReason = originalAudio.errorReason
                        playbackFailure = originalAudio.failure ?: currentFailure
                        resource = originalAudio.resource ?: break
                        continue
                    }
                    playbackFailure = currentFailure
                    val httpStatusCode =
                        currentFailure.findMedia3HttpStatusCode() ?: currentFailure.findPlaybackHttpStatusCode()
                    val category =
                        httpStatusCode?.let(PlaybackFailureClassifier::fromHttpStatus)
                            ?: currentFailure.media3PlaybackFailureCategory()
                    if (PlaybackRecoveryPolicy.requiresFreshUrl(category)) {
                        budget = budget.forPlaybackFailure(playbackPosition, SystemClock.elapsedRealtime(), category)
                    }
                    val decision =
                        PlaybackRecoveryPolicy.decide(
                            category = category,
                            budget = budget,
                            nowMs = SystemClock.elapsedRealtime(),
                        )
                    budget = decision.budget
                    val action = decision.action
                    if (action == PlaybackRecoveryAction.NONE) break

                    if (
                        action == PlaybackRecoveryAction.RETRY_CLIENT ||
                        (
                            currentFailure.isMediaTransportFailure() &&
                                (
                                    category == PlaybackFailureCategory.CDN_FORBIDDEN ||
                                        category == PlaybackFailureCategory.SABR_PROTOCOL ||
                                        category == PlaybackFailureCategory.SABR_ATTESTATION
                                )
                        )
                    ) {
                        streamResolver.reportPlaybackFailure(
                            videoId = playbackSong.id,
                            clientName = resource.clientName,
                            responseCode = httpStatusCode,
                            profileId = resource.profileId,
                            hints = hints,
                            category = category,
                        )
                    }
                    val recovery =
                        recoverPlaybackResource(
                            streamResolver = streamResolver,
                            retryNetworkResource = false,
                            now = Clock.System.now(),
                            action = action,
                            currentResource = resource,
                            failure = currentFailure,
                            videoId = playbackSong.id,
                            hints = hints,
                            sourceSong = playbackSong,
                            budget = budget,
                            category = category,
                        )
                    budget = recovery.budget
                    resource = recovery.resource ?: run {
                        playbackFailure = recovery.failure ?: currentFailure
                        errorReason =
                            recovery.failure?.let { getString(it.playbackErrorResource(Settings.shared.innerTubeCookie.isNotBlank())) }
                        return@withQueueLoading false
                    }
                }
                false
            }

        if (requestHandledBySync) return false
        if (!isCurrentRequest(playbackSong.id, expectedWantVideo = wantVideo)) return false
        if (!started) {
            if (stagedShouldCommit != null) return false
            if (!PlaybackNetworkStateStore.current.connected) {
                if (!playbackSong.isLocal && !playbackSong.id.startsWith("local_") &&
                    audioCacheManager.getCachedPlaybackResource(playbackSong, touch = false) == null
                ) {
                    skipUnavailableOfflineSong(sessionId, request)
                } else {
                    player.pause()
                }
                return false
            }
            playbackFailure?.let { error ->
                PlaybackErrorReporter.report(
                    error,
                    PlaybackErrorContext(stage = "queue_start", videoId = playbackSong.id, recovered = false, song = playbackSong),
                )
            }
            PlaybackEventLog.error(
                kind = PlaybackEventKind.PLAYER,
                message = "Android queue playback could not start.",
                videoId = playbackSong.id,
                details =
                    mapOf(
                        "requestedVideo" to wantVideo.toString(),
                        "playbackPositionMs" to playbackPosition.toString(),
                    ),
                error = playbackFailure,
            )
            showPlaybackErrorToast(
                errorReason ?: getString(Res.string.playback_unable_to_play_queued_track),
            )
            if (shouldAdvanceAfterPlaybackFailure(
                    player.wantsPlayback,
                    PlaybackFailureClassifier.fromThrowable(playbackFailure),
                    PlaybackNetworkStateStore.current.connected,
                    repeatMode = repeatMode.value,
                    autoSkipNextOnError = Settings.shared.autoSkipNextOnError,
                )
            ) {
                skipFailedTrackOrStop(playbackFailure)
            } else {
                publishTerminalPlaybackError(playbackFailure)
            }
            return false
        }

        streamRecoveryVideoId = playbackSong.id.takeIf { urlRecoveryBudget != null }
        streamRecoveryBudget = urlRecoveryBudget
        val hasVideo =
            shouldActivateQueueVideoMode(
                videoRequested = wantVideo,
                resolvedResourceHasVideo = player.currentPlaybackResource.value?.hasVideo == true,
            )
        videoMode.onQueuePlaybackStarted(playbackSong, hasVideo, player.currentPlaybackResource.value)
        if (stagedShouldCommit != null) return true
        syncPlayback.onLocalTrackLoaded()
        AndroidDeviceConnect.manager.remote.localStateChanged(trackChanged = true)
        if ((!queueCoordinator.isFiniteQueue || Settings.shared.autoLoadMore) && queueCoordinator.recommendationBootstrapJob == null) {
            bootstrapRecommendations(queueCoordinator.recommendationSeedSongId ?: request.song.id)
        }
        return true
    }

    private suspend fun offlineAvailability(): suspend (String) -> Boolean {
        val ids =
            if (PlaybackNetworkStateStore.current.connected) {
                emptySet()
            } else {
                audioCacheManager.cachedSongIds()
            }
        return { it in ids }
    }

    private suspend fun skipUnavailableOfflineSong(
        sessionId: Long,
        request: QueuePlaybackRequest,
    ) {
        val available = audioCacheManager.cachedSongIds(player.songQueue.value.mapTo(mutableSetOf()) { it.id })
        val next =
            nextOfflineQueueIndex(
                player.songQueue.value,
                player.currentMediaItemIndex.value,
                _repeatMode.value == Player.REPEAT_MODE_ALL,
            ) { song ->
                song.isLocal || song.id.startsWith("local_") || song.id in available
            }
        if (!isCurrentQueueRequest(sessionId, request)) return
        if (next != null && !AndroidListenTogetherManager.isGuestPlaybackLocked && !AndroidDeviceConnect.manager.remote.isActive) {
            player.skipToQueueItem(next, resumePlayback = player.wantsPlayback)
        } else {
            player.pause()
        }
    }

    private fun isCurrentQueueRequest(
        sessionId: Long,
        request: QueuePlaybackRequest,
        queueSongId: String = request.song.id,
        expectedWantVideo: Boolean? = null,
    ): Boolean =
        isCurrentQueuePlaybackTarget(
            expectedSessionId = sessionId,
            currentSessionId = queueCoordinator.queueSessionId,
            expectedRequestId = request.requestId,
            currentRequestId = player.latestQueuePlaybackRequestId,
            expectedQueueSongId = queueSongId,
            currentQueueSongId =
                player.songQueue.value
                    .getOrNull(player.currentMediaItemIndex.value)
                    ?.id,
        ) &&
            (
                expectedWantVideo == null ||
                    isQueuePlaybackModeCurrent(
                        song = request.song,
                        requestedWantVideo = expectedWantVideo,
                        currentVideoViewerVisible = Settings.shared.videoViewerVisible,
                        videoPlaybackEnabled = Settings.shared.musicVideoPlaybackEnabled,
                    )
            )

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

    // Before Android 13 the notification draws these, so Previous restarts the song first. Later versions build media
    // controls from the platform session, where Media3 drops the skip actions for any button in these slots; that
    // greys out previous/next in OEM panels such as HyperOS's. Their default skip actions already restart first.
    private fun transportButtons(): List<CommandButton> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) listOf(createPreviousButton(), createNextButton()) else emptyList()

    private fun createPreviousButton(): CommandButton =
        CommandButton
            .Builder(CommandButton.ICON_PREVIOUS)
            .setCustomIconResId(R.drawable.ic_notification_skip_previous)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
            .setDisplayName(getString(R.string.previous))
            .setSlots(CommandButton.SLOT_BACK)
            .build()

    private fun createNextButton(): CommandButton =
        CommandButton
            .Builder(CommandButton.ICON_NEXT)
            .setCustomIconResId(R.drawable.ic_notification_skip_next)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
            .setDisplayName(getString(R.string.next))
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()

    private fun createLikeButton(): CommandButton {
        val song = player.currentSong.value
        val isLiked = song?.id?.let(SongLikeState::isLiked) == true
        return CommandButton
            .Builder(if (isLiked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setCustomIconResId(if (isLiked) R.drawable.ic_notification_heart else R.drawable.ic_notification_heart_outline)
            .setDisplayName(getString(if (isLiked) R.string.remove_from_favorites else R.string.add_to_favorites))
            .setSessionCommand(MediaSessionConstants.CommandToggleLike)
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .setEnabled(song != null)
            .build()
    }

    private fun createShuffleButton(): CommandButton =
        CommandButton
            .Builder(if (_shuffleModeEnabled.value) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            .setCustomIconResId(if (_shuffleModeEnabled.value) R.drawable.ic_notification_shuffle_on else R.drawable.shuffle)
            .setDisplayName(getString(R.string.shuffle))
            .setSessionCommand(MediaSessionConstants.CommandToggleShuffle)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .setEnabled(player.currentSong.value != null)
            .build()

    private fun createRepeatButton(): CommandButton {
        val mode = _repeatMode.value
        val icon =
            when (mode) {
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                else -> CommandButton.ICON_REPEAT_OFF
            }
        val label =
            when (mode) {
                Player.REPEAT_MODE_ALL -> R.string.repeat_mode_all
                Player.REPEAT_MODE_ONE -> R.string.repeat_mode_one
                else -> R.string.repeat_mode_off
            }
        val iconRes =
            when (mode) {
                Player.REPEAT_MODE_ALL -> R.drawable.ic_notification_repeat
                Player.REPEAT_MODE_ONE -> R.drawable.ic_notification_repeat_one
                else -> R.drawable.ic_notification_repeat_off
            }
        return CommandButton
            .Builder(icon)
            .setCustomIconResId(iconRes)
            .setDisplayName(getString(label))
            .setSessionCommand(MediaSessionConstants.CommandToggleRepeat)
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .setEnabled(player.currentSong.value != null)
            .build()
    }

    private fun createAddToPlaylistButton(): CommandButton =
        CommandButton
            .Builder(CommandButton.ICON_PLAYLIST_ADD)
            .setCustomIconResId(R.drawable.ic_notification_playlist_add)
            .setDisplayName(getString(R.string.add_to_playlist))
            .setSessionCommand(MediaSessionConstants.CommandAddToTargetPlaylist)
            .setEnabled(player.currentSong.value != null)
            .build()

    private fun createRadioButton(label: String): CommandButton =
        CommandButton
            .Builder(CommandButton.ICON_RADIO)
            .setCustomIconResId(R.drawable.ic_notification_radio)
            .setDisplayName(label)
            .setSessionCommand(MediaSessionConstants.CommandToggleStartRadio)
            .setEnabled(player.currentSong.value != null)
            .build()

    internal suspend fun promoteWidgetPlayback(): Boolean {
        val session = mediaSession ?: return false
        return try {
            onUpdateNotificationAsync(session, true).await()
            isPlaybackOngoing
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w(error, "Widget playback foreground promotion failed")
            false
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (!::player.isInitialized) return START_NOT_STICKY
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == MusicWidgetReceiver.ACTION_UPDATE_WIDGET) updateWidgetsNow()
        if (intent?.action == ACTION_REFRESH_AUTOMOTIVE_LIBRARY) {
            mediaSession?.let { session ->
                for (browser in session.getSubscribedControllers(AutomotiveMediaIds.ROOT)) {
                    val count =
                        automotiveLibrary
                            .rootChildren(
                                automotiveRootBrowsableOnly[browser] ?: true,
                                automotiveRootChildrenLimit[browser] ?: 4,
                            ).size
                    session.notifyChildrenChanged(browser, AutomotiveMediaIds.ROOT, count, null)
                }
                session.notifyChildrenChanged(
                    AutomotiveMediaIds.PINNED_PLAYLISTS,
                    Settings.shared.getAutomotivePinnedPlaylists().size,
                    null,
                )
                session.notifyChildrenChanged(
                    AutomotiveMediaIds.COLLECTIONS,
                    automotiveLibrary.rootChildren(browsableOnly = true, childLimit = Int.MAX_VALUE).size,
                    null,
                )
            }
        }
        if (intent?.getStringExtra(EXTRA_WIDGET_COMMAND_TOKEN) == WIDGET_COMMAND_TOKEN) {
            when (intent.action) {
                MusicWidgetReceiver.ACTION_PLAY_PAUSE -> {
                    if (media3Player?.playWhenReady == true) media3Player?.pause() else media3Player?.play()
                }

                MusicWidgetReceiver.ACTION_LIKE -> {
                    toggleFavorite()
                }

                MusicWidgetReceiver.ACTION_NEXT -> {
                    media3Player?.seekToNext()
                }

                MusicWidgetReceiver.ACTION_PREVIOUS -> {
                    media3Player?.seekToPrevious()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun toggleFavorite() {
        val song = player.currentSong.value ?: return
        SongLikeState.toggleLike(song)
        updateWidgetsNow()
    }

    private fun startWidgetUpdates() {
        serviceScope.launch {
            combine(
                combine(
                    combine(
                        widgetSongState(player.currentSong, SongLikeState.likedSongsFlow),
                        widgetPlaybackRequested(player.wantsPlaybackState, castManager.state),
                    ) { (song, liked), requested -> Triple(song, liked, requested) },
                    player.queueSnapshots,
                    _repeatMode,
                    AndroidListenTogetherManager.isGuestPlaybackLockedFlow,
                    combine(
                        player.currentPlaybackResource,
                        player.playbackInstanceId,
                        queueCoordinator.playlistPageState,
                        castManager.state.map { Triple(it.isConnected, it.isPlaying, it.durationMs) }.distinctUntilChanged(),
                    ) { resource, _, _, _ -> resource },
                ) { songState, _, _, _, _ -> songState },
                player.isPlaying,
                player.duration,
                SongMetadataState.revision,
                snapshotFlow {
                    Settings.shared.artistNameAliasesJson
                },
            ) { songState, _, _, _, _ -> songState }
                .collectLatest { (song, liked, requested) -> updateWidgetsSnapshot(song, liked, requested) }
        }
    }

    private fun updateWidgetsNow() {
        serviceScope.launch { updateWidgetsSnapshot() }
    }

    private suspend fun updateWidgetsSnapshot(
        currentSong: SongItem? = player.currentSong.value,
        isLiked: Boolean = currentSong?.let { SongLikeState.isLiked(it.id) } == true,
        playbackRequested: Boolean = media3Player?.playWhenReady == true,
    ) {
        val state =
            playbackWidgetState(
                song = currentSong?.let(SongMetadataState::apply),
                isPlaying = media3Player?.isPlaying == true,
                isLiked = isLiked,
                duration = widgetDuration(),
                currentPosition = media3Player?.currentPosition ?: player.getRealtimePositionMs(),
                idleTitle = getString(R.string.not_playing),
                idleArtist = getString(R.string.tap_to_play),
            )
        widgetManager.updateWidgets(
            title = state.title,
            artist = state.artist,
            artworkUri = state.artworkUri,
            isPlaying = state.isPlaying,
            isLiked = state.isLiked,
            duration = state.duration,
            currentPosition = state.currentPosition,
            songId = currentSong?.id,
            playbackInstanceId = player.currentPlaybackInstanceId,
            controls =
                WidgetControls(
                    canPlayPause = !AndroidListenTogetherManager.isGuestPlaybackLocked,
                    canPrevious =
                        currentSong != null &&
                            canSkipToPrevious(
                                true,
                                false,
                                player.queueSnapshot(),
                                _repeatMode.value,
                                AndroidListenTogetherManager.isGuestPlaybackLocked,
                                currentSong.isLive || player.currentPlaybackResource.value?.isLive == true,
                            ),
                    canNext =
                        canSkipToNext(
                            true,
                            false,
                            player.queueSnapshot(),
                            _repeatMode.value,
                            AndroidListenTogetherManager.isGuestPlaybackLocked,
                            hasPlaylistContinuation = queueCoordinator.playlistPage?.hasMore == true,
                        ),
                    canLike = currentSong != null,
                    playbackRequested = playbackRequested,
                ),
        )
    }

    private fun widgetDuration(): Long =
        if (player.currentSong.value?.isLive == true || player.currentPlaybackResource.value?.isLive == true) {
            0
        } else {
            media3Player?.duration ?: player.duration.value
        }

    private fun startWidgetProgressUpdates() {
        val interactive =
            widgetScreenInteractive(this)
                .onEach { if (it) updateWidgetProgressNow() }
                .stateIn(serviceScope, SharingStarted.Eagerly, false)
        val playback =
            combine(
                player.isPlaying,
                combine(player.playbackStateFlow, player.currentPlaybackResource) { state, _ -> state },
                player.currentSong,
                player.duration,
                castManager.state.map { Triple(it.isConnected, it.isPlaying, it.durationMs) }.distinctUntilChanged(),
            ) { _, _, _, _, _ ->
                val session = media3Player
                Triple(session?.isPlaying == true, session?.playbackState == Player.STATE_READY, player.currentSong.value != null)
            }
        val active =
            combine(playback, interactive, widgetManager.hasProgressWidgets) { (playing, ready, hasSong), screenOn, hasWidget ->
                shouldSampleWidgetProgress(playing, ready, hasSong, widgetDuration(), screenOn, hasWidget)
            }
        serviceScope.launch {
            sampleWidgetProgress(active) { updateWidgetProgress() }
        }
        serviceScope.launch {
            castManager.state
                .map { it.seekGeneration }
                .distinctUntilChanged()
                .collect { updateWidgetProgress() }
        }
        player.addPlayerListener(
            object : Player.Listener {
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    updateWidgetProgressNow()
                }
            },
        )
    }

    private fun updateWidgetProgressNow() {
        serviceScope.launch { updateWidgetProgress() }
    }

    private suspend fun updateWidgetProgress() {
        val power = requireNotNull(getSystemService(PowerManager::class.java))
        val keyguard = requireNotNull(getSystemService(KeyguardManager::class.java))
        if (!power.isInteractive || keyguard.isKeyguardLocked || !widgetManager.hasProgressWidgets.value) return
        val song = player.currentSong.value ?: return
        val duration = widgetDuration()
        if (duration <= 0) return
        widgetManager.updateProgress(
            song.id,
            player.currentPlaybackInstanceId,
            duration,
            media3Player?.currentPosition ?: player.getRealtimePositionMs(),
        )
    }

    private fun updatePodcastProgress() {
        if (player.currentPlaybackInstanceId == 0L) return
        podcastProgress.update(
            player.currentSong.value,
            player.getRealtimePositionMs(),
            player.duration.value,
            player.isPlaying.value,
        )
    }

    private fun flushPodcastProgress(): Job? {
        updatePodcastProgress()
        return podcastProgress.flush()
    }

    private fun startPlaybackPersistence() {
        player.addPlayerListener(
            object : Player.Listener {
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason != Player.DISCONTINUITY_REASON_SEEK && reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) return
                    updatePodcastProgress()
                    podcastProgress.flush(force = true)
                    savePlaybackState()
                }
            },
        )
        serviceScope.launch {
            ProcessLifecycleOwner
                .get()
                .lifecycle.currentStateFlow
                .map { it.isAtLeast(Lifecycle.State.STARTED) }
                .distinctUntilChanged()
                .collect { foreground ->
                    if (foreground) {
                        // Requests fail while Android restricts background network; that is not a YouTube outage.
                        streamResolver.resetExtractionBackoff()
                    } else {
                        flushPodcastProgress()
                        saveFinalPlaybackState()
                    }
                }
        }
        serviceScope.launch(Dispatchers.IO) {
            for (request in playbackPersistenceRequests) {
                try {
                    playbackPersistenceWriter.write(request)
                } catch (error: Exception) {
                    Timber.e(error, "Failed to persist playback")
                }
            }
        }
        serviceScope.launch {
            val playing =
                combine(player.isPlaying, castManager.state, player.queueState) { localPlaying, remote, queueState ->
                    remote.forQueueEntry(queueState)?.isPlaying ?: localPlaying
                }
            observePlaybackCheckpoints(playing, snapshotFlow { Settings.shared.persistentQueue }) {
                savePlaybackState()
            }
        }
        serviceScope.launch {
            combine(castManager.state, player.queueState) { remote, queue ->
                remote.forQueueEntry(queue)?.let { Triple(it.isPlaying, it.positionMs.takeUnless { _ -> it.isPlaying }, it.seekGeneration) }
            }.distinctUntilChanged().collect { if (it != null) savePlaybackState() }
        }
        serviceScope.launch {
            combine(player.isPlaying, player.currentPosition) { playing, position -> position.takeUnless { playing } }
                .distinctUntilChanged()
                .collect { position -> if (position != null) savePlaybackState() }
        }
        serviceScope.launch {
            combine(player.queueSnapshots, repeatMode) { queue, repeat -> queue to repeat }
                .distinctUntilChanged()
                .collect { savePlaybackState() }
        }
        serviceScope.launch {
            snapshotFlow { Settings.shared.persistentQueue }
                .distinctUntilChanged()
                .collect { saveFinalPlaybackState() }
        }
    }

    private fun buildPersistPlayerState(): PersistPlayerState? =
        if (playbackPersistenceLifecycle.restoring) null else queueCoordinator.buildPersistPlayerState()

    private fun savePlaybackState() {
        if (!playbackPersistenceLifecycle.restoring) queueCoordinator.saveShuffleMode()
        if (!playbackPersistenceLifecycle.canSave(Settings.shared.persistentQueue)) return
        val state = buildPersistPlayerState()
        if (!shouldPersistPlaybackState(lastPersistenceState, state)) return
        lastPersistenceState = state
        playbackPersistenceRequests.trySend(playbackPersistenceWriter.request(state))
    }

    private fun saveFinalPlaybackState(): Job? {
        if (!playbackPersistenceLifecycle.restoring) queueCoordinator.saveShuffleMode()
        if (!playbackPersistenceLifecycle.canSave(Settings.shared.persistentQueue)) return finalPlaybackPersistence
        val request = playbackPersistenceWriter.request(buildPersistPlayerState())
        finalPlaybackPersistence =
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { playbackPersistenceWriter.write(request) }
                    .onFailure { Timber.e(it, "Failed to persist playback") }
            }
        return finalPlaybackPersistence
    }

    internal suspend fun awaitPlaybackRestore() = playbackRestoreJob.join()

    private suspend fun restorePersistedPlayback() {
        queueCoordinator.restoreShuffleMode()
        if (!Settings.shared.persistentQueue) {
            Settings.shared.pendingContinuePlayback = false
            return
        }
        val expectedQueueSessionId = queueCoordinator.queueSessionId
        try {
            finalPlaybackPersistence?.join()
            val raw = withContext(Dispatchers.IO) { Settings.shared.persistentPlaybackState }
            if (raw.isBlank()) {
                Settings.shared.pendingContinuePlayback = false
                return
            }
            val restoreModes = Settings.shared.rememberShuffleAndRepeat
            val blocked = BlockedContent.snapshot()
            val position = withContext(Dispatchers.IO) { Settings.shared.persistentPlaybackPosition }
            val (state, restoredQueue) =
                withContext(Dispatchers.Default) {
                    decodePersistPlayerState(raw, position).restoredPlaybackState(restoreModes, blocked::isBlocked)
                }
            val restoredSongs = restoredQueue.displayedItems
            if (restoredSongs.isEmpty()) {
                Settings.shared.pendingContinuePlayback = false
                return
            }
            val restored = queueCoordinator.restorePersistedQueue(state, restoredQueue, expectedQueueSessionId, restoreModes)
            if (restored) {
                Settings.shared.pendingContinuePlayback = Settings.shared.promptContinuePlayback
                updateWidgetsSnapshot()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to restore playback")
            Settings.shared.pendingContinuePlayback = false
        }
    }

    private suspend fun handlePlaybackCompletion(completedPlaybackInstanceId: Long? = null) {
        flushPodcastProgress()
        savePlaybackState()
        if (sleepTimerController.isStopAfterCurrentSong) {
            sleepTimerController.onEndOfMediaItem()
        } else {
            advanceQueueAfterCurrent(completedPlaybackInstanceId)
        }
    }

    private fun resetQueueSession(
        seedSongId: String? = null,
        queueTitle: String? = null,
        finite: Boolean = false,
        sessionId: Long? = null,
    ) {
        queueCoordinator.resetQueueSession(seedSongId, queueTitle, finite, sessionId)
    }

    private fun synchronizeMediaSessionQueue(replacesQueue: Boolean) = queueCoordinator.synchronizeMediaSessionQueue(replacesQueue)

    private fun replaceQueueSession(
        songs: List<SongItem>,
        startIndex: Int,
        queueTitle: String?,
        finite: Boolean,
        replacement: () -> Unit,
    ) = queueCoordinator.replaceQueueSession(songs, startIndex, queueTitle, finite, replacement = replacement)

    fun addToQueue(
        songs: List<SongItem>,
        playNext: Boolean,
    ) {
        enqueueOrStartQueue(
            songs = songs,
            currentSong = player.currentSong.value,
            preventDuplicates = Settings.shared.preventDuplicateTracksInQueue,
            startQueue = { additions, startPlaying ->
                val prepared = additions.map(SongItem::asManuallyAddedQueueItem)
                replaceQueueSession(prepared, 0, null, finite = true) {
                    player.setQueue(prepared, startPlaying = startPlaying, preparePlayback = true)
                    _shuffleModeEnabled.value = player.isShuffleEnabled
                }
            },
            enqueue = { additions ->
                player.addToQueue(additions, playNext)
            },
        )
    }

    fun playQueue(
        songs: List<SongItem>,
        startIndex: Int = 0,
        queueTitle: String? = null,
        finite: Boolean? = null,
        fromRemote: Boolean = false,
        shuffleModeEnabled: Boolean? = null,
        playlistPage: PlaylistPage? = null,
        songsOnly: Boolean = false,
        startPositionMs: Long = 0L,
        startPlaying: Boolean = true,
        startVideo: Boolean = false,
    ) {
        playbackBenchmark.cancel()
        queueCoordinator.playQueue(
            songs,
            startIndex,
            queueTitle,
            finite,
            fromRemote,
            shuffleModeEnabled,
            playlistPage,
            songsOnly,
            startPositionMs,
            startPlaying,
            startVideo,
        )
    }

    suspend fun playQueueFromRemote(
        songs: List<SongItem>,
        startIndex: Int,
        title: String?,
    ): Boolean {
        if (castManager.state.value.isConnected) {
            playQueue(songs, startIndex, queueTitle = title, fromRemote = true, startPlaying = false)
            return true
        }
        player.pause()
        player.cancelPendingQueuePlayback()
        val requestId = player.latestQueuePlaybackRequestId
        val loaded =
            queueCoordinator.playQueueFromRemote(songs, startIndex, title) { song, isCurrent ->
                playQueueRequest(QueuePlaybackRequest(requestId, song, resumePlayback = false)) {
                    isCurrent() && requestId == player.latestQueuePlaybackRequestId && !castManager.state.value.isConnected
                }
            }
        if (loaded) {
            syncPlayback.onLocalTrackLoaded()
            if (Settings.shared.autoLoadMore && queueCoordinator.recommendationBootstrapJob == null) {
                bootstrapRecommendations(queueCoordinator.recommendationSeedSongId ?: songs.getOrNull(startIndex)?.id ?: return true)
            }
        }
        return loaded
    }

    fun extendPlaylistBeforeSkip(resumePlayback: Boolean? = null): Boolean = queueCoordinator.extendPlaylistBeforeSkip(resumePlayback)

    fun playMix(seed: SongItem) {
        playbackBenchmark.cancel()
        queueCoordinator.playMix(seed)
    }

    fun updatePendingRestorePosition(position: Long) = queueCoordinator.updatePendingRestorePosition(position)

    private fun bootstrapRecommendations(seedSongId: String) = queueCoordinator.bootstrapRecommendations(seedSongId)

    fun selectQueueChip(chipId: String) = queueCoordinator.selectQueueChip(chipId)

    private suspend fun advanceQueueAfterCurrent(completedPlaybackInstanceId: Long? = null) =
        queueCoordinator.advanceQueueAfterCurrent(completedPlaybackInstanceId)

    private suspend fun extendRadioQueue(shouldCommit: () -> Boolean): Boolean = queueCoordinator.extendRadioQueue(shouldCommit)

    fun toggleVideoMode() = videoMode.toggleVideoMode()

    fun setVideoMode(wantVideo: Boolean) = videoMode.setVideoMode(wantVideo)

    fun changeMusicVideoResolution(quality: MusicVideoQuality) = videoMode.changeMusicVideoResolution(quality)

    private suspend fun <T> withQueueLoading(
        isPlaybackLoading: Boolean = false,
        block: suspend () -> T,
    ): T = queueCoordinator.withQueueLoading(isPlaybackLoading, block)

    private suspend fun fetchPlaybackResource(
        song: SongItem,
        wantVideo: Boolean = song.isVideoSong,
    ): PlaybackFetchResult =
        withContext(Dispatchers.IO) {
            val connected = PlaybackNetworkStateStore.current.connected
            cachedAudioResource(wantVideo && connected) { audioCacheManager.getCachedPlaybackResource(song) }
                ?.let { return@withContext PlaybackFetchResult(resource = it) }
            if (!connected && !song.isLocal && !song.id.startsWith("local_")) return@withContext PlaybackFetchResult()
            val hints = song.toAndroidContentHints(wantVideo = wantVideo)
            Timber.tag(MUSIC_VIDEO_TAG).d(
                "resource resolve start videoId=%s wantVideo=%s songIsVideo=%s quality=%s maxVideoHeight=%s",
                song.id,
                wantVideo,
                song.isVideoSong,
                Settings.shared.musicVideoQuality,
                hints.maxVideoHeight,
            )
            try {
                val resource =
                    streamResolver.resolve(
                        song.id,
                        hints,
                        playbackAudioQuality(),
                        sourceSong = song,
                    )
                Timber.tag(MUSIC_VIDEO_TAG).d(
                    "resource resolve success videoId=%s wantVideo=%s client=%s profile=%s hasVideo=%s directVideo=%s sabrVideo=%s audioItag=%s videoItag=%s videoMime=%s videoCodec=%s videoSize=%sx%s availableHeights=%s",
                    song.id,
                    wantVideo,
                    resource.clientName,
                    resource.profileId,
                    resource.hasVideo,
                    resource.hasDirectVideo,
                    resource.hasSabrVideo,
                    resource.itag,
                    resource.videoItag,
                    resource.videoMimeType,
                    resource.videoCodecs,
                    resource.videoWidth,
                    resource.videoHeight,
                    resource.availableVideoHeights,
                )
                PlaybackFetchResult(resource = resource)
            } catch (e: CancellationException) {
                Timber.tag(MUSIC_VIDEO_TAG).d("resource resolve cancelled videoId=%s wantVideo=%s", song.id, wantVideo)
                throw e
            } catch (e: Exception) {
                Timber.tag(MUSIC_VIDEO_TAG).w(e, "resource resolve failed videoId=%s wantVideo=%s", song.id, wantVideo)
                if (wantVideo) {
                    cachedAudioResource(wantVideo = true, videoResolutionFailed = true) {
                        audioCacheManager.getCachedPlaybackResource(song)
                    }?.let { return@withContext PlaybackFetchResult(resource = it) }
                    try {
                        Timber.tag(MUSIC_VIDEO_TAG).d("resource audio fallback start videoId=%s", song.id)
                        val audioResource =
                            streamResolver.resolve(
                                song.id,
                                song.toAndroidContentHints(wantVideo = false),
                                playbackAudioQuality(),
                                sourceSong = song,
                            )
                        Timber.tag(MUSIC_VIDEO_TAG).d(
                            "resource audio fallback success videoId=%s client=%s profile=%s audioItag=%s",
                            song.id,
                            audioResource.clientName,
                            audioResource.profileId,
                            audioResource.itag,
                        )
                        return@withContext PlaybackFetchResult(resource = audioResource)
                    } catch (e2: CancellationException) {
                        Timber.tag(MUSIC_VIDEO_TAG).d("resource audio fallback cancelled videoId=%s", song.id)
                        throw e2
                    } catch (fallbackError: Exception) {
                        Timber.tag(MUSIC_VIDEO_TAG).w(fallbackError, "resource audio fallback failed videoId=%s", song.id)
                    }
                }
                PlaybackFetchResult(
                    failure = e,
                    errorReason = getString(e.playbackErrorResource(Settings.shared.innerTubeCookie.isNotBlank())),
                )
            }
        }

    internal fun playFromSearch(request: VoiceSearchRequest) {
        serviceScope.launch {
            val generation = automotivePlaybackRequestGate.start(AndroidListenTogetherManager.isGuestPlaybackLocked)
            val result =
                try {
                    resolveVoicePlayback(request, generation)
                } catch (error: IllegalArgumentException) {
                    return@launch
                }
            media3Player?.apply {
                setMediaItems(result.mediaItems, result.startIndex, result.startPositionMs)
                prepare()
                play()
            }
        }
    }

    private suspend fun resolveVoicePlayback(
        request: VoiceSearchRequest,
        generation: Long,
    ): MediaSession.MediaItemsWithStartPosition {
        playbackRestoreJob.join()
        if (request.matchQuery.isBlank()) {
            val state = player.queueState.value
            val current = state.entries.getOrNull(state.currentIndex)
            if (current != null && !PlaybackNetworkStateStore.current.connected) {
                val playable =
                    offlineAutomotiveQueue(
                        AutomotiveSongResolution(state.entries.map { it.song }, state.currentIndex),
                        false,
                        offlineAvailability(),
                    )
                if (playable != null) {
                    return automotivePlaybackRequestGate.resolve(
                        generation,
                        playable.songs.map(automotiveLibrary::playableItem),
                        playable.startIndex,
                        if (playable.songs[playable.startIndex].id == current.song.id) player.currentPosition.value else C.TIME_UNSET,
                        canApply = !AndroidListenTogetherManager.isGuestPlaybackLocked,
                    )
                }
            }
            if (current != null && !BlockedContent.isBlocked(current.song) && PlaybackNetworkStateStore.current.connected) {
                Settings.shared.pendingContinuePlayback = false
                val item =
                    automotiveLibrary
                        .playableItem(current.song)
                        .buildUpon()
                        .setMediaId(AutomotiveMediaIds.queueSong(current.key, current.song.id))
                        .build()
                return automotivePlaybackRequestGate.resolve(
                    generation,
                    listOf(item),
                    0,
                    player.currentPosition.value,
                    replacesQueue = false,
                    canApply = !AndroidListenTogetherManager.isGuestPlaybackLocked,
                )
            }
        }
        val voiceQueue = resolveVoiceSongs(request)
        val songs =
            offlineAutomotiveQueue(
                AutomotiveSongResolution(voiceQueue.songs, 0),
                PlaybackNetworkStateStore.current.connected,
                offlineAvailability(),
            )?.songs.orEmpty()
        return automotivePlaybackRequestGate
            .resolve(
                generation,
                songs.map(automotiveLibrary::playableItem),
                0,
                C.TIME_UNSET,
                queueTitle = request.matchQuery.takeIf(String::isNotBlank),
                playlistPage = voiceQueue.playlistPage,
                shuffleModeEnabled = false,
                canApply = !AndroidListenTogetherManager.isGuestPlaybackLocked,
            ).also { Settings.shared.pendingContinuePlayback = false }
    }

    private suspend fun resolveVoiceSongs(request: VoiceSearchRequest): AutomotiveVoiceQueue {
        val songs =
            try {
                withContext(Dispatchers.IO) { automotiveLibrary.resolveVoiceSearch(request) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "Voice search failed")
                AutomotiveVoiceQueue(emptyList())
            }
        if (songs.songs.isEmpty()) {
            val message = getString(Res.string.search_no_results)
            showPlaybackErrorToast(message)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            throw IllegalArgumentException(message)
        }
        return songs
    }

    private fun showPlaybackErrorToast(reason: String) {
        PlaybackSnackbarEvents.emit(getString(R.string.playback_error_message, reason))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::player.isInitialized) {
            widgetManager.refreshProgressDemand()
            updateWidgetsNow()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        playbackBenchmark.cancel()
        super.onTaskRemoved(rootIntent)
        if (!::player.isInitialized) return
        saveFinalPlaybackState()
        flushPodcastProgress()
        taskRemoved.value = true
    }

    override fun onDestroy() {
        playbackBenchmark.cancel()
        if (!::player.isInitialized) {
            podcastWriterScope.cancel()
            serviceScope.cancel()
            super.onDestroy()
            return
        }
        val checkpoint = saveFinalPlaybackState()
        updatePodcastProgress()
        val podcastWrite = podcastProgress.flush()
        if (podcastWrite == null) podcastWriterScope.cancel() else podcastWrite.invokeOnCompletion { podcastWriterScope.cancel() }
        val historyWrite = listeningHistory.flush()
        runBlocking {
            awaitFinalPlaybackCheckpoint(checkpoint)
            awaitFinalPlaybackCheckpoint(historyWrite)
            awaitFinalPlaybackCheckpoint(podcastWrite)
        }
        isRunning = false
        running.value = false
        if (Settings.shared.enableDiscordIntegration) {
            DiscordRpcManager.clear()
        }
        syncPlayback.release()
        AndroidDeviceConnect.manager.remote.detach(syncPlayback.port)
        castManager.closeImmediately()
        serviceScope.cancel()
        contentResolver.unregisterContentObserver(volumeMuteObserver)
        mediaSession?.release()
        MediaArtworkProvider.currentArtwork = null
        player.release()
        super.onDestroy()
    }

    private data class PlaybackFetchResult(
        val resource: PlaybackResource? = null,
        val errorReason: String? = null,
        val failure: Throwable? = null,
    )

    private fun isAuthorizedExternalMediaController(controller: MediaSession.ControllerInfo): Boolean =
        controller.isPackageNameVerified ||
            packageManager.getPackagesForUid(controller.uid)?.contains(controller.packageName) == true

    private fun isAssistantController(controller: MediaSession.ControllerInfo): Boolean {
        val packages =
            listOf("assistant", "voice_interaction_service")
                .mapNotNull { key ->
                    AndroidSettings.Secure
                        .getString(contentResolver, key)
                        ?.let(ComponentName::unflattenFromString)
                        ?.packageName
                }.toSet()
        return isVoiceSearchController(controller.packageName, controller.isPackageNameVerified, packages)
    }

    private inner class AutomotiveSessionCallback : MediaLibrarySession.Callback {
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> =
            if (controller.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) {
                // Legacy browsers block main until this future completes; startup and restore also need main.
                Futures.immediateFuture(connectionResult(session, controller))
            } else {
                serviceScope.future {
                    (application as App).startup.await()
                    playbackRestoreJob.join()
                    connectionResult(session, controller)
                }
            }

        private fun connectionResult(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            Timber.tag(TAG).d(
                "Media controller connected trusted=%s version=%d",
                controller.isTrusted,
                controller.controllerVersion,
            )
            if (!canBrowseLibrary(session, controller)) {
                val authorized = isAuthorizedExternalMediaController(controller)
                val commands = MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_COMMANDS.buildUpon()
                if (authorized) {
                    commands.add(SessionCommand.COMMAND_CODE_SESSION_SET_RATING)
                    commands.add(MediaSessionConstants.CommandToggleLike)
                    commands.add(MediaSessionConstants.CommandToggleShuffle)
                    commands.add(MediaSessionConstants.CommandToggleRepeat)
                    MediaArtworkProvider.allowedPackages += controller.packageName
                }
                return MediaSession.ConnectionResult.accept(
                    commands.build(),
                    if (isAssistantController(controller)) {
                        automotivePlayerCommands()
                    } else if (authorized || controller.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) {
                        // Platform bridges can lack a verified package; media-ID requests can only select the active queue.
                        externalTransportPlayerCommands()
                    } else {
                        MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_PLAYER_COMMANDS
                    },
                )
            }
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                    .buildUpon()
                    .add(MediaSessionConstants.CommandToggleLike)
                    .add(MediaSessionConstants.CommandToggleRepeat)
                    .add(MediaSessionConstants.CommandToggleShuffle)
                    .add(MediaSessionConstants.CommandToggleStartRadio)
                    .add(MediaSessionConstants.CommandAddToQueue)
                    .add(MediaSessionConstants.CommandAddToTargetPlaylist)
                    .build()
            MediaArtworkProvider.allowedPackages += controller.packageName
            return MediaSession.ConnectionResult.accept(
                sessionCommands,
                automotivePlayerCommands(),
            )
        }

        private fun canBrowseLibrary(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): Boolean =
            canBrowseAutomotiveLibrary(
                isTrusted = controller.isTrusted || session.isMediaNotificationController(controller),
                isPackageNameVerified = controller.isPackageNameVerified,
                isAutoCompanion = session.isAutoCompanionController(controller),
            )

        override fun onDisconnected(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ) {
            automotiveRootBrowsableOnly.remove(controller)
            automotiveRootChildrenLimit.remove(controller)
            if (session.connectedControllers.none { it != controller && it.packageName == controller.packageName }) {
                MediaArtworkProvider.allowedPackages -= controller.packageName
            }
            Timber.tag(TAG).d("Media controller disconnected")
            super.onDisconnected(session, controller)
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            if (AndroidListenTogetherManager.isGuestPlaybackLocked && isGuestRestrictedPlayerCommand(playerCommand)) {
                return SessionError.ERROR_PERMISSION_DENIED
            }
            if (playerCommand in
                setOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
            ) {
                playbackBenchmark.cancel()
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }

        override fun onSetRating(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            rating: Rating,
        ): ListenableFuture<SessionResult> = setSongRating(player.currentSong.value, rating)

        override fun onSetRating(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaId: String,
            rating: Rating,
        ): ListenableFuture<SessionResult> {
            val id = AutomotiveMediaIds.queueSong(mediaId)?.songId ?: AutomotiveMediaIds.songId(mediaId) ?: mediaId
            val song = player.songQueue.value.firstOrNull { it.id == id } ?: player.currentSong.value?.takeIf { it.id == id }
            return setSongRating(song, rating)
        }

        private fun setSongRating(
            song: SongItem?,
            rating: Rating,
        ): ListenableFuture<SessionResult> {
            val result = setMediaSessionRating(song, rating)
            if (result == SessionResult.RESULT_SUCCESS) updateWidgetsNow()
            return Futures.immediateFuture(SessionResult(result))
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                MediaSessionConstants.ACTION_ADD_TO_TARGET_PLAYLIST -> {
                    val song = player.currentSong.value
                    val playlistId = Settings.shared.androidAutoTargetPlaylist
                    return serviceScope.future {
                        val result =
                            addAutomotiveSongToPlaylist(
                                this@MusicService,
                                database,
                                GlobalYouTubeRepository.instance,
                                playlistId,
                                song,
                            )
                        result.sessionError?.let { Toast.makeText(this@MusicService, it.message, Toast.LENGTH_SHORT).show() }
                        result
                    }
                }

                MediaSessionConstants.ACTION_ADD_TO_QUEUE -> {
                    return serviceScope.future {
                        addAutomotiveSongToQueue(
                            mediaId = args.getString(MediaConstants.EXTRA_KEY_MEDIA_ID),
                            isPlaybackLocked = { AndroidListenTogetherManager.isGuestPlaybackLocked },
                            resolve = { item ->
                                (application as App).startup.await()
                                playbackRestoreJob.join()
                                resolveMediaAdditions(listOf(item))
                            },
                            append = { items -> session.player.addMediaItems(items) },
                        )
                    }
                }

                MediaSessionConstants.ACTION_TOGGLE_LIKE -> {
                    toggleFavorite()
                }

                MediaSessionConstants.ACTION_TOGGLE_REPEAT -> {
                    if (AndroidListenTogetherManager.isGuestPlaybackLocked) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    }
                    media3Player?.repeatMode = nextMediaSessionRepeatMode(_repeatMode.value)
                }

                MediaSessionConstants.ACTION_TOGGLE_SHUFFLE -> {
                    if (AndroidListenTogetherManager.isGuestPlaybackLocked) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    }
                    media3Player?.shuffleModeEnabled = !player.isShuffleEnabled
                }

                MediaSessionConstants.ACTION_TOGGLE_START_RADIO -> {
                    if (AndroidListenTogetherManager.isGuestPlaybackLocked) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    }
                    player.currentSong.value?.let(::playMix)
                }

                else -> {
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val browsableOnly =
                params?.extras?.getBoolean(MediaConstants.EXTRA_KEY_ROOT_CHILDREN_BROWSABLE_ONLY, true) ?: true
            automotiveRootBrowsableOnly[browser] = browsableOnly
            automotiveRootChildrenLimit[browser] =
                params?.extras?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 4)?.takeIf { it > 0 } ?: 4
            serviceScope.launch {
                (application as App).startup.await()
                automotiveLibrary.refreshLikedSongs()
            }
            PlaybackEventLog.info(
                PlaybackEventKind.PLAYER,
                "Automotive library root requested.",
                details =
                    mapOf(
                        "browsableOnly" to browsableOnly.toString(),
                        "maxCommandsForMediaItems" to browser.maxCommandsForMediaItems.toString(),
                    ),
            )
            return Futures.immediateFuture(LibraryResult.ofItem(automotiveLibrary.rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            serviceScope.future {
                (application as App).startup.await()
                if (page < 0 || pageSize <= 0) {
                    return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
                }
                try {
                    val items =
                        automotiveLibrary.children(
                            parentId = parentId,
                            page = page,
                            pageSize = pageSize,
                            rootBrowsableOnly = automotiveRootBrowsableOnly[browser] ?: true,
                            rootChildLimit = automotiveRootChildrenLimit[browser] ?: 4,
                        )
                    Timber.tag(TAG).d(
                        "Automotive library children loaded root=%s page=%d count=%d",
                        parentId == AutomotiveMediaIds.ROOT,
                        page,
                        items.size,
                    )
                    LibraryResult.ofItemList(items, params)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.tag(TAG).e(error, "Automotive library children failed")
                    LibraryResult.ofError(SessionError.ERROR_IO, params)
                }
            }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            serviceScope.future {
                (application as App).startup.await()
                try {
                    automotiveLibrary.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                        ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.tag(TAG).e(error, "Automotive library item lookup failed")
                    LibraryResult.ofError(SessionError.ERROR_IO)
                }
            }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> =
            serviceScope.future {
                (application as App).startup.await()
                if (query.isBlank()) {
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
                } else {
                    try {
                        val count = automotiveLibrary.searchSongs(query).size
                        session.notifySearchResultChanged(browser, query, count, params)
                        LibraryResult.ofVoid(params)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Timber.tag(TAG).e(error, "Automotive library search failed")
                        LibraryResult.ofError(SessionError.ERROR_IO, params)
                    }
                }
            }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            serviceScope.future {
                (application as App).startup.await()
                if (query.isBlank() || page < 0 || pageSize <= 0) {
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
                } else {
                    try {
                        LibraryResult.ofItemList(automotiveLibrary.search(query, page, pageSize), params)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Timber.tag(TAG).e(error, "Automotive library search results failed")
                        LibraryResult.ofError(SessionError.ERROR_IO, params)
                    }
                }
            }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<List<MediaItem>> =
            serviceScope.future {
                (application as App).startup.await()
                if (AndroidListenTogetherManager.isGuestPlaybackLocked) throw CancellationException()
                val playable = resolveMediaAdditions(mediaItems)
                if (playable.isEmpty() || AndroidListenTogetherManager.isGuestPlaybackLocked) throw CancellationException()
                playable
            }

        private suspend fun resolveMediaAdditions(mediaItems: List<MediaItem>): List<MediaItem> {
            val resolved =
                mediaItems.flatMap { item ->
                    val query = item.requestMetadata.searchQuery
                    if (query != null) {
                        val queue = resolveVoiceSongs(voiceSearchRequest(query, item.requestMetadata.extras))
                        queue.playlistPage?.let { page ->
                            GlobalYouTubeRepository.instance.completePlaylistPage(page, applyContentFilters = false).songs
                        } ?: queue.songs
                    } else {
                        automotiveLibrary.resolveAddition(item)
                    }
                }
            val playable =
                offlineAutomotiveQueue(
                    AutomotiveSongResolution(BlockedContent.filterSongs(resolved), 0),
                    PlaybackNetworkStateStore.current.connected,
                    offlineAvailability(),
                )?.songs.orEmpty()
            return playable.map(automotiveLibrary::playableItem)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            serviceScope.future {
                (application as App).startup.await()
                if (!isForPlayback) {
                    playbackRestoreJob.join()
                    return@future MediaSession.MediaItemsWithStartPosition(
                        listOfNotNull(
                            player.songQueue.value.getOrNull(player.currentMediaItemIndex.value),
                        ).map(automotiveLibrary::playableItem),
                        0,
                        player.currentPosition.value,
                    )
                }
                val generation = automotivePlaybackRequestGate.start(AndroidListenTogetherManager.isGuestPlaybackLocked)
                resolveVoicePlayback(VoiceSearchRequest(""), generation)
            }

        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            serviceScope.future {
                (application as App).startup.await()
                if (!canResolveMediaSessionItems(
                        canBrowse = canBrowseLibrary(session, controller),
                        isAssistant = isAssistantController(controller),
                        mediaItems = mediaItems,
                        entries = player.queueState.value.entries,
                    )
                ) {
                    throw CancellationException()
                }
                val requestGeneration =
                    automotivePlaybackRequestGate.start(AndroidListenTogetherManager.isGuestPlaybackLocked)
                val requestedItem = mediaItems.singleOrNull()
                val searchQuery = requestedItem?.requestMetadata?.searchQuery
                if (searchQuery != null) {
                    return@future resolveVoicePlayback(
                        voiceSearchRequest(searchQuery, requestedItem.requestMetadata.extras),
                        requestGeneration,
                    )
                }
                val selectedIndex = startIndex.takeIf(mediaItems.indices::contains) ?: 0
                val selectedItem = mediaItems.getOrNull(selectedIndex)
                val queueSelection = selectedItem?.mediaId?.let(AutomotiveMediaIds::queueSong)
                if (queueSelection != null) {
                    val activeQueueIndex = automotiveQueueIndex(selectedItem.mediaId, player.queueState.value.entries)
                    if (activeQueueIndex != null && !PlaybackNetworkStateStore.current.connected) {
                        val playable =
                            offlineAutomotiveQueue(
                                AutomotiveSongResolution(player.songQueue.value, activeQueueIndex),
                                false,
                                offlineAvailability(),
                            )
                        return@future automotivePlaybackRequestGate.resolve(
                            requestGeneration,
                            playable?.songs.orEmpty().map(automotiveLibrary::playableItem),
                            playable?.startIndex ?: 0,
                            startPositionMs,
                            canApply = playable != null && !AndroidListenTogetherManager.isGuestPlaybackLocked,
                        )
                    }
                    return@future automotivePlaybackRequestGate.resolve(
                        requestGeneration = requestGeneration,
                        mediaItems = mediaItems,
                        startIndex = selectedIndex,
                        startPositionMs = startPositionMs,
                        replacesQueue = false,
                        canApply =
                            activeQueueIndex != null &&
                                !AndroidListenTogetherManager.isGuestPlaybackLocked,
                    )
                }

                val pinnedQueue = requestedItem?.let { automotiveLibrary.resolvePinnedPlaylist(it) }
                val songQueue = requestedItem?.let { automotiveLibrary.resolveSongQueue(it) }
                val individuallyResolvedSongs =
                    if (mediaItems.size != 1 || (pinnedQueue == null && songQueue == null)) {
                        mediaItems.map { item ->
                            if (requestedItem != null && AutomotiveMediaIds.browseSong(item.mediaId) != null && songQueue == null) {
                                null
                            } else {
                                automotiveLibrary.resolveSong(item)
                            }
                        }
                    } else {
                        emptyList()
                    }
                val resolution =
                    resolveAutomotiveSongRequest(
                        requestedItemCount = mediaItems.size,
                        requestedStartIndex = startIndex,
                        pinnedSongs = pinnedQueue?.songs,
                        singleItemQueue = songQueue,
                        individuallyResolvedSongs = individuallyResolvedSongs,
                    )
                val playable =
                    resolution?.let {
                        offlineAutomotiveQueue(
                            it,
                            PlaybackNetworkStateStore.current.connected,
                            offlineAvailability(),
                        )
                    }
                val songs = playable?.songs.orEmpty()
                automotivePlaybackRequestGate.resolve(
                    requestGeneration = requestGeneration,
                    mediaItems = songs.map(automotiveLibrary::playableItem),
                    startIndex = playable?.startIndex ?: startIndex,
                    startPositionMs = if (songQueue?.shuffle == true) C.TIME_UNSET else startPositionMs,
                    queueTitle = pinnedQueue?.title,
                    shuffleModeEnabled = pinnedQueue?.shuffle ?: songQueue?.shuffle?.takeIf { it },
                    playlistPage = pinnedQueue?.playlistPage ?: songQueue?.playlistPage,
                    canApply = playable != null && !AndroidListenTogetherManager.isGuestPlaybackLocked,
                )
            }
    }

    private fun syncDiscordState() {
        if (!externalReportingEnabled || !Settings.shared.enableDiscordIntegration || !player.isPlaying.value) {
            DiscordRpcManager.clear()
            return
        }

        val song =
            player.currentSong.value?.let(SongMetadataState::apply) ?: run {
                DiscordRpcManager.clear()
                return
            }
        val isPlaying = player.isPlaying.value

        val timestamps =
            discordPlaybackTimestamps(
                now = System.currentTimeMillis(),
                positionMs = player.currentPosition.value,
                durationMs = player.duration.value,
                speed = player.playbackSpeed(),
                playing = isPlaying,
            )

        val artistName = song.artists.joinToString { it.displayName }.ifEmpty { getString(R.string.unknown_artist) }
        val songTitle = song.title

        val settings = Settings.shared
        val activity =
            DiscordActivityBuilder.build(
                song = song,
                artistName = artistName,
                albumName = song.album?.name,
                artistThumbnail =
                    song.artists
                        .firstOrNull()
                        ?.thumbnail
                        .takeIf { settings.discordRpcShowArtistIcon },
                songTitle = songTitle,
                startTimestamp = timestamps.start,
                endTimestamp = timestamps.end,
                advancedMode = settings.discordAdvancedMode,
                activityType = settings.discordActivityType.toIntOrNull() ?: 2,
                activityName = settings.discordActivityName,
                stateTemplate = settings.discordStateTemplate,
                detailsTemplate = settings.discordDetailsTemplate,
                btn1Enabled = settings.discordButton1Enabled,
                btn1Label =
                    resolveDiscordButtonLabel(
                        settings.discordButton1Text,
                        DiscordDefaults.BUTTON1_LABEL,
                        getString(R.string.discord_button1_text_default),
                    ),
                btn1Url = settings.discordButton1Url,
                btn2Enabled = settings.discordButton2Enabled,
                btn2Label =
                    resolveDiscordButtonLabel(
                        settings.discordButton2Text,
                        DiscordDefaults.BUTTON2_LABEL,
                        getString(R.string.discord_button2_text_default),
                    ),
                btn2Url = settings.discordButton2Url,
                basicBtn1Label = getString(R.string.discord_button1_text_default),
                basicBtn2Label = getString(R.string.discord_button2_text_default),
                unknownAlbum = getString(R.string.discord_unknown_album),
            )

        DiscordRpcManager.setActivity(
            activity = activity,
            songId = song.id,
            isPlaying = isPlaying,
            status = PresenceStatus.Online,
        )
    }
}

internal fun shouldAdvanceAfterPlaybackFailure(
    wantsPlayback: Boolean,
    category: PlaybackFailureCategory,
    networkConnected: Boolean,
    originalCategory: PlaybackFailureCategory = category,
    repeatMode: Int = Player.REPEAT_MODE_OFF,
    autoSkipNextOnError: Boolean,
): Boolean =
    shouldSkipAfterPlaybackFailure(
        autoSkipNextOnError = autoSkipNextOnError,
        wantsPlayback = wantsPlayback,
        networkConnected = networkConnected,
        repeatOne = repeatMode == Player.REPEAT_MODE_ONE,
        category = category,
        originalCategory = originalCategory,
    )
