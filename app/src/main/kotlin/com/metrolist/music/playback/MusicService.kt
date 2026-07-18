/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:Suppress("DEPRECATION")

package com.metrolist.music.playback

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.SQLException
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import com.metrolist.music.playback.audio.VolumeNormalizationAudioProcessor
import com.metrolist.music.utils.safeDataStoreEdit
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.Preferences
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.MoreExecutors
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.strategy.ContentHints
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.lastfm.LastFM
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.constants.AndroidAutoTargetPlaylistKey
import com.metrolist.music.constants.AudioNormalizationKey
import com.metrolist.music.constants.AudioOffload
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.constants.AudioTrackPlaybackParamsKey
import com.metrolist.music.constants.AutoDownloadOnLikeKey
import com.metrolist.music.constants.AutoLoadMoreKey
import com.metrolist.music.constants.AutoSkipNextOnErrorKey
import com.metrolist.music.constants.StreamSourceAndroidVRKey
import com.metrolist.music.constants.StreamSourceTVHTML5Key
import com.metrolist.music.constants.StreamSourceVisionOSKey
import com.metrolist.music.constants.StreamSourceWebCreatorKey
import com.metrolist.music.constants.StreamSourceWebRemixKey
import com.metrolist.music.constants.AutoplayKey
import com.metrolist.music.constants.CrossfadeDurationKey
import com.metrolist.music.constants.CrossfadeEnabledKey
import com.metrolist.music.constants.CrossfadeGaplessKey
import com.metrolist.music.constants.DisableLoadMoreWhenRepeatAllKey
import com.metrolist.music.constants.DiscordActivityNameKey
import com.metrolist.music.constants.DiscordActivityTypeKey
import com.metrolist.music.constants.DiscordAdvancedModeKey
import com.metrolist.music.constants.DiscordButton1EnabledKey
import com.metrolist.music.constants.DiscordButton1LabelKey
import com.metrolist.music.constants.DiscordButton1UrlKey
import com.metrolist.music.constants.DiscordButton2EnabledKey
import com.metrolist.music.constants.DiscordButton2LabelKey
import com.metrolist.music.constants.DiscordButton2UrlKey
import com.metrolist.music.constants.DiscordDetailsTemplateKey
import com.metrolist.music.constants.DiscordStateTemplateKey
import com.metrolist.music.constants.DiscordUserStatusKey
import com.metrolist.music.constants.EnableDiscordRPCKey
import com.metrolist.music.discord.DiscordActivity
import com.metrolist.music.discord.DiscordDefaults
import com.metrolist.music.discord.DiscordRpcManager
import com.metrolist.music.discord.DiscordActivityBuilder
import com.metrolist.music.discord.DiscordTemplateRenderer
import com.metrolist.music.discord.PresenceStatus
import com.metrolist.music.constants.EnableLastFMScrobblingKey
import com.metrolist.music.constants.EnableSongCacheKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HistoryDuration
import com.metrolist.music.constants.LastFMUseNowPlaying
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.constants.MediaSessionConstants.CommandAddToTargetPlaylist
import com.metrolist.music.constants.MediaSessionConstants.CommandToggleLike
import com.metrolist.music.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.metrolist.music.constants.MediaSessionConstants.CommandToggleShuffle
import com.metrolist.music.constants.MediaSessionConstants.CommandToggleStartRadio
import com.metrolist.music.constants.PauseListenHistoryKey
import com.metrolist.music.constants.PauseOnMute
import com.metrolist.music.constants.PersistentQueueKey
import com.metrolist.music.constants.PersistentShuffleAcrossQueuesKey
import com.metrolist.music.constants.PlayerVolumeKey
import com.metrolist.music.constants.PreventDuplicateTracksInQueueKey
import com.metrolist.music.constants.RememberShuffleAndRepeatKey
import com.metrolist.music.constants.RepeatModeKey
import com.metrolist.music.constants.ResumeOnBluetoothConnectKey
import com.metrolist.music.constants.ScrobbleDelayPercentKey
import com.metrolist.music.constants.ScrobbleDelaySecondsKey
import com.metrolist.music.constants.ScrobbleMinSongDurationKey
import com.metrolist.music.constants.ShowLyricsKey
import com.metrolist.music.constants.ShuffleModeKey
import com.metrolist.music.constants.ShufflePlaylistFirstKey
import com.metrolist.music.constants.SimilarContent
import com.metrolist.music.constants.SkipSilenceInstantKey
import com.metrolist.music.constants.SkipSilenceKey
import com.metrolist.music.constants.StopMusicOnTaskClearKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.RelatedSongMap
import com.metrolist.music.db.entities.Song
import com.metrolist.music.di.DownloadCache
import com.metrolist.music.di.PlayerCache
import com.metrolist.music.eq.EqualizerService
import com.metrolist.music.eq.audio.CustomEqualizerAudioProcessor
import com.metrolist.music.eq.data.EQProfileRepository
import com.metrolist.music.extensions.SilentHandler
import com.metrolist.music.extensions.collect
import com.metrolist.music.extensions.collectLatest
import com.metrolist.music.extensions.currentMetadata
import com.metrolist.music.extensions.findNextMediaItemById
import com.metrolist.music.extensions.mediaItems
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.setOffloadEnabled
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.toPersistQueue
import com.metrolist.music.extensions.toQueue
import com.metrolist.music.lyrics.LyricsHelper
import com.metrolist.music.models.PersistPlayerState
import com.metrolist.music.models.PersistQueue
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.alarm.MusicAlarmScheduler
import com.metrolist.music.playback.alarm.MusicAlarmStore
import com.metrolist.music.playback.audio.SilenceDetectorAudioProcessor
import com.metrolist.music.playback.queues.EmptyQueue
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.playback.queues.YouTubePlaylistQueue
import com.metrolist.music.playback.queues.filterExplicit
import com.metrolist.music.playback.queues.filterVideoSongs
import com.metrolist.music.constants.LoudnessLevel
import com.metrolist.music.constants.LoudnessLevelKey
import com.metrolist.music.utils.CoilBitmapLoader
import com.metrolist.music.utils.NetworkConnectivityObserver
import com.metrolist.music.utils.ScrobbleManager
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.getArtistSeparator
import com.metrolist.music.utils.joinToArtistString
import com.metrolist.music.utils.YTPlayerUtils
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import com.metrolist.music.widget.MetrolistWidgetManager
import com.metrolist.music.widget.MusicWidgetReceiver
import com.metrolist.music.widget.PlaylistWidgetReceiver
import com.metrolist.music.ui.utils.resize
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.random.Random
import java.util.Collections

private const val INSTANT_SILENCE_SKIP_STEP_MS = 15_000L
private const val INSTANT_SILENCE_SKIP_SETTLE_MS = 350L

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class MusicService :
    MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    @Inject
    lateinit var equalizerService: EqualizerService

    @Inject
    lateinit var eqProfileRepository: EQProfileRepository

    @Inject
    lateinit var widgetManager: MetrolistWidgetManager

    @Inject
    lateinit var listenTogetherManager: com.metrolist.music.listentogether.ListenTogetherManager

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastAudioFocusState = AudioManager.AUDIOFOCUS_NONE
    private var wasPlayingBeforeAudioFocusLoss = false
    private var hasAudioFocus = false
    private var reentrantFocusGain = false
    private var wasPlayingBeforeVolumeMute = false
    private var isPausedByVolumeMute = false

    private var crossfadeEnabled = false
    private var crossfadeDuration = 5000f
    private var crossfadeGapless = true
    private var crossfadeTriggerJob: Job? = null

    private val secondaryPlayerListener =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Timber.tag(TAG).e(error, "Secondary player error")
                secondaryPlayer?.stop()
                secondaryPlayer?.clearMediaItems()
                secondaryPlayer = null
            }
        }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val binder = MusicBinder()

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    private lateinit var connectivityManager: ConnectivityManager
    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(false)
    val currentStreamClient = MutableStateFlow<String?>(null)

    private lateinit var audioQuality: com.metrolist.music.constants.AudioQuality

    private var currentQueue: Queue = EmptyQueue
    var queueTitle: String? = null

    val currentMediaMetadata = MutableStateFlow<com.metrolist.music.models.MediaMetadata?>(null)
    private val currentSong =
        currentMediaMetadata
            .flatMapLatest { mediaMetadata ->
                database.song(mediaMetadata?.id)
            }.stateIn(scope, SharingStarted.Lazily, null)
    private val currentFormat =
        currentMediaMetadata.flatMapLatest { mediaMetadata ->
            database.format(mediaMetadata?.id)
        }

    lateinit var playerVolume: MutableStateFlow<Float>
    val isMuted = MutableStateFlow(false)
    private val sleepTimerVolumeMultiplier = MutableStateFlow(1f)
    private val audioFocusVolumeMultiplier = MutableStateFlow(1f)

    fun toggleMute() {
        val newMutedState = !isMuted.value
        isMuted.value = newMutedState
        applyEffectiveVolume()
    }

    fun setMuted(muted: Boolean) {
        isMuted.value = muted
        applyEffectiveVolume()
    }

    private fun calculateEffectiveVolume(
        volume: Float = playerVolume.value,
        muted: Boolean = isMuted.value,
        sleepTimerMultiplier: Float = sleepTimerVolumeMultiplier.value,
        focusMultiplier: Float = audioFocusVolumeMultiplier.value,
    ): Float {
        if (muted) return 0f
        return (volume * sleepTimerMultiplier * focusMultiplier).coerceIn(0f, 1f)
    }

    private fun applyEffectiveVolume() {
        if (!::player.isInitialized || isCrossfading) return
        player.volume = calculateEffectiveVolume()
    }

    var sleepTimer: SleepTimer? = null

    @Inject
    @PlayerCache
    lateinit var playerCache: Cache

    @Inject
    @DownloadCache
    lateinit var downloadCache: Cache

    lateinit var player: ExoPlayer
        private set
    private var secondaryPlayer: ExoPlayer? = null
    private var fadingPlayer: ExoPlayer? = null
    private var isCrossfading = false
    private var crossfadeJob: Job? = null
    private var isRunning = false
    private var mediaSession: MediaLibrarySession? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    private val playerInitialized = MutableStateFlow(false)
    val isPlayerReady: kotlinx.coroutines.flow.StateFlow<Boolean> = playerInitialized.asStateFlow()

    // Single batch-read of all DataStore preferences needed during onCreate().
    // Populated once at the very top of onCreate() to replace 15+ individual
    // runBlocking reads that were each blocking the main thread.
    @Volatile
    private var startupPrefs: Preferences? = null

    private val _playerFlow = MutableStateFlow<ExoPlayer?>(null)
    val playerFlow = _playerFlow.asStateFlow()

    private val playerSilenceProcessors = HashMap<Player, SilenceDetectorAudioProcessor>()

    private val instantSilenceSkipEnabled = MutableStateFlow(false)

    private var isAudioEffectSessionOpened = false
    private var openedAudioEffectSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private val playerNormalizationProcessors = HashMap<Player, VolumeNormalizationAudioProcessor>()

    private var loudnessSetupJob: Job? = null
    private var loudnessSetupGeneration: Long = 0L

    @Volatile
    private var normalizationEnabledCached: Boolean = false

    @Volatile
    private var loudnessLevelCached: LoudnessLevel = LoudnessLevel.BALANCED

    private var cachedNormalizationGainMb: Int? = null
    private var cachedNormalizationEnabled: Boolean = false

    @Volatile private var discordRpcEnabled = false
    @Volatile private var lastDiscordReconnectAttemptAtMs: Long = 0L
    @Volatile private var discordIntentionalDisconnect = false
    @Volatile private var isScreenOff = false
    private val screenOffHandler = Handler(Looper.getMainLooper())
    private val screenOffTimeout = Runnable {
        Timber.tag("DiscordSvc").i("screenOffTimeout: isPlaying=%s, isReady=%s",
            player.isPlaying, DiscordRpcManager.isReady())
        if (!player.isPlaying && DiscordRpcManager.isReady()) {
            Timber.tag("DiscordSvc").i("screenOffTimeout: disconnecting after long idle")
            discordIntentionalDisconnect = true
            DiscordRpcManager.disconnect()
        }
    }
    private val pauseTimeout = Runnable {
        Timber.tag("DiscordSvc").i("pauseTimeout: isPlaying=%s, isReady=%s",
            player.isPlaying, DiscordRpcManager.isReady())
        if (!player.isPlaying && DiscordRpcManager.isReady()) {
            Timber.tag("DiscordSvc").i("pauseTimeout: disconnecting after 1m paused")
            discordIntentionalDisconnect = true
            DiscordRpcManager.disconnect()
        }
    }
    private var lastPlaybackSpeed = 1.0f

    @Volatile
    private var latestMediaNotification: Notification? = null

    private var scrobbleManager: ScrobbleManager? = null

    val automixItems = MutableStateFlow<List<MediaItem>>(emptyList())

    // Tracks the original queue size to distinguish original items from auto-added ones
    private var originalQueueSize: Int = 0

    private var consecutivePlaybackErr = 0
    private var retryJob: Job? = null
    private var retryCount = 0
    // True only when stopOnError() paused playback purely because of a network outage
    // (waitOnNetworkError exhausting its attempts). Lets triggerRetry() know it's safe —
    // and necessary — to explicitly resume playback once connectivity returns, rather than
    // leaving the player "prepared but paused" forever.
    private var pausedDueToNetworkError = false
    private var silenceSkipJob: Job? = null

    // Cached preferences to avoid runBlocking DataStore reads in hot paths
    @Volatile
    private var cachedPersistentQueue = true
    @Volatile
    private var cachedAutoplay = true
    @Volatile
    private var cachedDisableLoadMoreWhenRepeatAll = false
    @Volatile
    private var cachedHideExplicit = false
    @Volatile
    private var cachedHideVideoSongs = false
    @Volatile
    private var cachedShufflePlaylistFirst = false
    @Volatile
    private var cachedAutoLoadMore = true

    // URL cache for stream URLs - class-level so it can be invalidated on errors
    private val songUrlCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Pair<String, Long>>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Long>>): Boolean {
                return size > 500
            }
        }
    )

    // Tracks mediaIds for which a recoverSong() coroutine is currently in flight.
    //
    // resolveDataSpec() (createDataSourceFactory()) calls recoverSong() on every
    // dataSpec/chunk resolution, not just once per song. For a song cached a long
    // time ago the cache index can be fragmented into many small CacheSpans
    // (interrupted downloads, LeastRecentlyUsedCacheEvictor reclaiming arbitrary
    // spans), so a single playback can re-resolve dozens or hundreds of times in a
    // very short window. Without this guard, every single resolve would launch its
    // own coroutine doing a Room read + a hop to Dispatchers.Main + a Room
    // transaction — all redundant, since they all converge on the same mediaId and
    // mostly no-op. If those launches outpace how fast they can drain (e.g. the
    // Main thread is busy with playback/UI work), dozens of them pile up in memory
    // at once, which is enough to blow past this app's heap limit on low-RAM
    // devices and surface as an OutOfMemoryError that looks like a leak.
    //
    // This set makes recoverSong() effectively a no-op while a call for the same
    // mediaId is already running, so at most one is ever in flight per song.
    private val recoveringSongs = Collections.synchronizedSet(mutableSetOf<String>())

    private val sessionKey
        get() = YouTube.dataSyncId.takeIf { !it.isNullOrBlank() }
            ?: YouTube.visitorData.takeIf { !it.isNullOrBlank() }
            ?: ""

    private fun cacheKey(mediaId: String) = "${sessionKey}:$mediaId"

    private val playbackUrlCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
                return size > 500
            }
        }
    )

    // Flag to bypass cache when quality changes - forces fresh stream fetch
    private val bypassCacheForQualityChange = mutableSetOf<String>()

    private var currentMediaIdRetryCount = mutableMapOf<String, Int>()
    private val MAX_RETRY_PER_SONG = 3
    private val RETRY_DELAY_MS = 1000L

    // Track failed songs to prevent infinite retry loops
    private val recentlyFailedSongs = mutableSetOf<String>()
    private var failedSongsClearJob: Job? = null

    var castConnectionHandler: CastConnectionHandler? = null
        private set

    private val screenStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOff = true
                        Timber.tag("DiscordSvc").i("SCREEN_OFF: cancelling pause timeout, delaying disconnect 10m")
                        screenOffHandler.removeCallbacks(pauseTimeout)
                        screenOffHandler.postDelayed(screenOffTimeout, 600_000)
                    }

                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOff = false
                        Timber.tag("DiscordSvc").i("SCREEN_ON: removing disconnect delay")
                        screenOffHandler.removeCallbacks(screenOffTimeout)
                        screenOffHandler.removeCallbacks(pauseTimeout)
                        discordIntentionalDisconnect = false
                        syncDiscordState()
                    }
                }
            }
        }

    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                super.onAudioDevicesAdded(addedDevices)
                val hasBluetooth =
                    addedDevices?.any {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    } == true

                if (hasBluetooth) {
                    if (dataStore.get(ResumeOnBluetoothConnectKey, false)) {
                        if (player.playbackState == Player.STATE_READY && !player.isPlaying) {
                            player.play()
                        }
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        shutdownDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()

        setListener(
            object : MediaSessionService.Listener {
                override fun onForegroundServiceStartNotAllowedException() {
                    handleForegroundServiceStartNotAllowed(null)
                }
            },
        )

        playerInitialized.value = false

        // Call startForeground() as early as possible to satisfy the
        // 5-second timeout imposed by Context.startForegroundService().
        // On some OEMs (e.g. MIUI), even a DataStore read can be slow
        // enough to miss the window, so we promote before any I/O.
        ensureForegroundChannelExists()
        if (!ensureStartedAsForegroundOrStop()) {
            return
        }

        // Read ALL startup preferences in one shot so that subsequent code
        // never calls dataStore.get() (which does runBlocking internally).
        // This consolidates ~15 main-thread-blocking DataStore reads into 1.
        startupPrefs = runBlocking(Dispatchers.IO) { dataStore.data.first() }

        // 3. Connect the processor to the service
        // handled in createExoPlayer

        seedLoudnessCacheFromPrefs()

        val defaultMediaNotificationProvider =
            DefaultMediaNotificationProvider(
                this,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player,
            ).apply {
                setSmallIcon(R.drawable.small_icon)
            }

        setMediaNotificationProvider(
            object : MediaNotification.Provider {
                override fun createNotification(
                    mediaSession: MediaSession,
                    mediaButtonPreferences: ImmutableList<CommandButton>,
                    actionFactory: MediaNotification.ActionFactory,
                    onNotificationChangedCallback: MediaNotification.Provider.Callback,
                ): MediaNotification {
                    val trackingCallback =
                        MediaNotification.Provider.Callback { notification ->
                            latestMediaNotification = notification.notification
                            onNotificationChangedCallback.onNotificationChanged(notification)
                        }

                    return defaultMediaNotificationProvider
                        .createNotification(
                            mediaSession,
                            mediaButtonPreferences,
                            actionFactory,
                            trackingCallback,
                        ).also { mediaNotification ->
                            latestMediaNotification = mediaNotification.notification
                        }
                }

                override fun handleCustomCommand(
                    session: MediaSession,
                    action: String,
                    extras: Bundle,
                ): Boolean = defaultMediaNotificationProvider.handleCustomCommand(session, action, extras)

                override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
                    defaultMediaNotificationProvider.notificationChannelInfo
            },
        )
        player = createExoPlayer(prefs = startupPrefs!!)
        player.addListener(this@MusicService)
        sleepTimer =
            SleepTimer(scope, player) { multiplier ->
                sleepTimerVolumeMultiplier.value = multiplier
            }
        player.addListener(sleepTimer!!)

        playerInitialized.value = true
        Timber.tag(TAG).d("Player successfully initialized")

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupAudioFocusRequest()

        mediaLibrarySessionCallback.apply {
            service = this@MusicService
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
            addToTargetPlaylist = ::addToTargetPlaylist
        }
        mediaSession =
            MediaLibrarySession
                .Builder(this, player, mediaLibrarySessionCallback)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setBitmapLoader(CoilBitmapLoader(this, scope))
                .build()
        player.repeatMode = startupPrefs!![RepeatModeKey] ?: REPEAT_MODE_OFF

        if (startupPrefs!![RememberShuffleAndRepeatKey] ?: true) {
            player.shuffleModeEnabled = startupPrefs!![ShuffleModeKey] ?: false
        }

        // Keep a connected controller so that notification works
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                controllerFuture?.get()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to initialize MediaController")
                controllerFuture = null
                stopSelf()
            }
        }, MoreExecutors.directExecutor())

        connectivityManager = getSystemService()!!
        connectivityObserver = NetworkConnectivityObserver(this)

        val screenStateFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        registerReceiver(screenStateReceiver, screenStateFilter)

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        audioQuality = startupPrefs!![AudioQualityKey]?.let { value ->
            if (value == "VERY_HIGH") com.metrolist.music.constants.AudioQuality.HIGH
            else com.metrolist.music.constants.AudioQuality.entries.find { it.name == value }
        } ?: com.metrolist.music.constants.AudioQuality.AUTO
        playerVolume = MutableStateFlow((startupPrefs!![PlayerVolumeKey] ?: 1f).coerceIn(0f, 1f))

        initializeCast()

        // Collecting this flow activates the internal map that updates lyricsProviders in LyricsHelper
        lyricsHelper.preferred.collectLatest(scope) {}

        // 4. Watch for EQ profile changes
        scope.launch {
            eqProfileRepository.activeProfile.collect { profile ->
                if (profile != null) {
                    val result = equalizerService.applyProfile(profile)
                    if (result.isSuccess && player.playbackState == Player.STATE_READY && player.isPlaying) {
                        // Instant update: flush buffers and seek slightly to re-process audio
                        player.seekTo(player.currentPosition)
                    }
                } else {
                    equalizerService.disable()
                    if (player.playbackState == Player.STATE_READY && player.isPlaying) {
                        player.seekTo(player.currentPosition)
                    }
                }
            }
        }

        scope.launch {
            connectivityObserver.networkStatus.collect { isConnected ->
                isNetworkConnected.value = isConnected
                if (isConnected && waitingForNetworkConnection.value) {
                    triggerRetry()
                }
                if (isConnected && DiscordRpcManager.isReady()) {
                    Timber.tag("DiscordSvc").i("Network reconnected, syncing RPC")
                    syncDiscordState()
                }
            }
        }

        // Watch for audio quality setting changes
        var isFirstQualityEmit = true
        scope.launch {
            dataStore.data
                .map {
                    it[AudioQualityKey]?.let { value ->
                        if (value == "VERY_HIGH") com.metrolist.music.constants.AudioQuality.HIGH
                        else com.metrolist.music.constants.AudioQuality.entries.find { it.name == value }
                    } ?: com.metrolist.music.constants.AudioQuality.AUTO
                }.distinctUntilChanged()
                .collect { newQuality ->
                    val oldQuality = audioQuality
                    audioQuality = newQuality

                    if (isFirstQualityEmit) {
                        isFirstQualityEmit = false
                        Timber.tag(TAG).i("QUALITY INIT: $newQuality")
                        return@collect
                    }

                    Timber.tag(TAG).i("QUALITY CHANGED: $oldQuality -> $newQuality")

                    val mediaId = player.currentMediaItem?.mediaId ?: return@collect
                    val currentPosition = player.currentPosition
                    val wasPlaying = player.isPlaying
                    val currentIndex = player.currentMediaItemIndex

                    Timber.tag(TAG).i("RELOADING STREAM: $mediaId at position ${currentPosition}ms")

                    songUrlCache.remove(mediaId)

                    // CRITICAL: Clear caches synchronously to prevent format parsing errors
                    runBlocking(Dispatchers.IO) {
                        try {
                            playerCache.removeResource(mediaId)
                            downloadCache.removeResource(mediaId)
                            Timber.tag(TAG).d("Cleared player and download cache for $mediaId")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to clear cache for $mediaId")
                        }
                    }

                    // Set bypass flag so resolver skips cache checks
                    bypassCacheForQualityChange.add(mediaId)
                    Timber.tag(TAG).d("Set bypass cache flag for $mediaId")

                    player.stop()
                    player.seekTo(currentIndex, currentPosition)
                    player.prepare()
                    if (wasPlaying) {
                        player.play()
                    }
                }
        }

        combine(
            playerVolume,
            isMuted,
            sleepTimerVolumeMultiplier,
            audioFocusVolumeMultiplier,
        ) { volume, muted, timerMultiplier, focusMultiplier ->
            calculateEffectiveVolume(
                volume = volume,
                muted = muted,
                sleepTimerMultiplier = timerMultiplier,
                focusMultiplier = focusMultiplier,
            )
        }.collectLatest(scope) {
            if (!isCrossfading) {
                player.volume = it
            }
        }

        playerVolume.debounce(1000).collect(scope) { volume ->
            safeDataStoreEdit { settings ->
                settings[PlayerVolumeKey] = volume
            }
        }

        currentSong.debounce(1000).collect(scope) { song ->
            updateNotification()
            updateWidgetUI(player.isPlaying)
        }

        combine(
            currentMediaMetadata.distinctUntilChangedBy { it?.id },
            dataStore.data.map { it[ShowLyricsKey] ?: false }.distinctUntilChanged(),
        ) { mediaMetadata, showLyrics ->
            mediaMetadata to showLyrics
        }.collectLatest(scope) { (mediaMetadata, showLyrics) ->
            if (showLyrics && mediaMetadata != null && database
                    .lyrics(mediaMetadata.id)
                    .first() == null
            ) {
                val lyricsWithProvider = lyricsHelper.getLyrics(mediaMetadata)
                database.query {
                    upsert(
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = lyricsWithProvider.lyrics,
                            provider = lyricsWithProvider.provider,
                        ),
                    )
                }
            }
        }

        dataStore.data
            .map { (it[SkipSilenceKey] ?: false) to (it[SkipSilenceInstantKey] ?: false) }
            .distinctUntilChanged()
            .collectLatest(scope) { (skipSilence, instantSkip) ->
                player.skipSilenceEnabled = skipSilence
                secondaryPlayer?.skipSilenceEnabled = skipSilence

                val enableInstant = skipSilence && instantSkip
                instantSilenceSkipEnabled.value = enableInstant

                playerSilenceProcessors.values.forEach { processor ->
                    processor.instantModeEnabled = enableInstant
                    if (!enableInstant) {
                        processor.resetTracking()
                    }
                }

                if (!enableInstant) {
                    silenceSkipJob?.cancel()
                }
            }

        combine(
            currentFormat,
            dataStore.data
                .map { it[AudioNormalizationKey] ?: true }
                .distinctUntilChanged(),
            dataStore.data
                .map { prefs -> prefs[LoudnessLevelKey].toEnum(LoudnessLevel.BALANCED) }
                .distinctUntilChanged(),
        ) { format, normalizeAudio, loudnessLevel ->
            Triple(format, normalizeAudio, loudnessLevel)
        }.collectLatest(scope) { (format, normalizeAudio, loudnessLevel) ->
            normalizationEnabledCached = normalizeAudio
            loudnessLevelCached = loudnessLevel
            setupAudioNormalization()
        }

        combine(
            dataStore.data.map { it[AudioOffload] ?: false },
            dataStore.data.map { it[CrossfadeEnabledKey] ?: false },
        ) { offloadPref, crossfadeEnabled ->
            // Force disable offload if crossfade is enabled to prevent volume ramp issues
            if (crossfadeEnabled) false else offloadPref
        }.distinctUntilChanged()
            .collectLatest(scope) { useOffload ->
                player.setOffloadEnabled(useOffload)
                secondaryPlayer?.setOffloadEnabled(useOffload)
            }

        var isFirstAudioTrackParamsEmit = true
        dataStore.data
            .map { it[AudioTrackPlaybackParamsKey] ?: true }
            .distinctUntilChanged()
            .collectLatest(scope) { useAudioTrackParams ->
                if (isFirstAudioTrackParamsEmit) {
                    isFirstAudioTrackParamsEmit = false
                    return@collectLatest
                }

                Timber.tag("MusicService").i("AudioTrackPlaybackParams changed to: $useAudioTrackParams")

                val currentIndex = player.currentMediaItemIndex
                val currentPosition = player.currentPosition
                val playWhenReady = player.playWhenReady
                val repeatMode = player.repeatMode
                val shuffleModeEnabled = player.shuffleModeEnabled
                val playbackParameters = player.playbackParameters
                val volume = player.volume
                val mediaItems = List(player.mediaItemCount) { index ->
                    player.getMediaItemAt(index)
                }

                player.removeListener(this)
                sleepTimer?.let { player.removeListener(it) }
                playerNormalizationProcessors.remove(player)
                playerSilenceProcessors.remove(player)
                player.release()

                val newPlayer = createExoPlayer()
                newPlayer.addListener(this@MusicService)
                sleepTimer?.let { newPlayer.addListener(it) }

                sleepTimer?.player = newPlayer

                try {
                    mediaSession?.let { (it as MediaSession).player = newPlayer }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to swap player in MediaSession")
                }

                newPlayer.setMediaItems(mediaItems, currentIndex, currentPosition)
                newPlayer.repeatMode = repeatMode
                newPlayer.shuffleModeEnabled = shuffleModeEnabled
                newPlayer.playbackParameters = playbackParameters
                newPlayer.volume = volume
                newPlayer.playWhenReady = playWhenReady
                newPlayer.prepare()

                player = newPlayer
                _playerFlow.value = newPlayer

                Timber.tag("MusicService").i("Player recreated with AudioTrackPlaybackParams: $useAudioTrackParams")
            }

        // Initialize Discord RPC manager (rehydrates token, reconnects gateway)
        if (!DiscordRpcManager.isInitialized()) {
            DiscordRpcManager.init(this@MusicService)
        }

        dataStore.data
            .map { it[EnableDiscordRPCKey] ?: true }
            .debounce(300)
            .distinctUntilChanged()
            .collect(scope) { enabled ->
                Timber.tag("DiscordSvc").i("RPC toggle: enabled=%s, isReady=%s, hasToken=%s",
                    enabled, DiscordRpcManager.isReady(), DiscordRpcManager.getAccessToken() != null)
                discordRpcEnabled = enabled
                if (enabled) {
                    discordIntentionalDisconnect = false
                    if (DiscordRpcManager.isReady()) {
                        Timber.tag("DiscordSvc").i("RPC toggle: already ready, syncing RPC")
                        syncDiscordState()
                    } else if (DiscordRpcManager.getAccessToken() != null) {
                        Timber.tag("DiscordSvc").i("RPC toggle: not ready but has token, reconnecting")
                        scope.launch(Dispatchers.IO) {
                            if (!DiscordRpcManager.isInitialized()) {
                                Timber.tag("DiscordSvc").i("RPC toggle: initializing")
                                DiscordRpcManager.init(this@MusicService)
                            }
                            DiscordRpcManager.reconnectWithToken(DiscordRpcManager.getAccessToken()!!)
                        }
                    } else {
                        Timber.tag("DiscordSvc").w("RPC toggle: enabled but no token and not ready")
                    }
                } else if (DiscordRpcManager.isReady()) {
                    Timber.tag("DiscordSvc").i("RPC toggle: disabled, disconnecting")
                    scope.launch(Dispatchers.IO) {
                        DiscordRpcManager.disconnect()
                    }
                }
            }

        DiscordRpcManager.accessTokenFlow.collectLatest(scope) { token ->
                Timber.tag("DiscordSvc").i("Token change: hasToken=%s, initialized=%s, authorized=%s, enabled=%s",
                    token != null, DiscordRpcManager.isInitialized(), DiscordRpcManager.isAuthorized(), discordRpcEnabled)
                if (token == null) {
                    if (DiscordRpcManager.isReady()) {
                        Timber.tag("DiscordSvc").i("Token change: empty token, disconnecting")
                        DiscordRpcManager.disconnect()
                    }
                    return@collectLatest
                }
                if (!discordRpcEnabled) {
                    Timber.tag("DiscordSvc").i("Token change: RPC disabled, skipping reconnect")
                    return@collectLatest
                }
                if (!DiscordRpcManager.isInitialized()) {
                    Timber.tag("DiscordSvc").i("Token change: initializing")
                    DiscordRpcManager.init(this@MusicService)
                }
                if (!DiscordRpcManager.isAuthorized()) {
                    Timber.tag("DiscordSvc").i("Token change: reconnecting with token")
                    DiscordRpcManager.reconnectWithToken(token)
                } else {
                    Timber.tag("DiscordSvc").i("Token change: already authorized, skipping reconnect")
                }
            }

        scope.launch {
            DiscordRpcManager.connectionStatus.collect { status ->
                Timber.tag("DiscordSvc").i("Status change: %s (discordRpcEnabled=%s, playing=%s)",
                    status, discordRpcEnabled, player.isPlaying)
                if (status == DiscordRpcManager.Status.Connected && discordRpcEnabled) {
                    Timber.tag("DiscordSvc").i("Status change: connected, syncing RPC")
                    syncDiscordState()
                }
            }
        }

        scope.launch {
            DiscordRpcManager.settingsChanged.collect {
                if (discordRpcEnabled && DiscordRpcManager.isReady()) {
                    Timber.tag("DiscordSvc").i("Settings changed, syncing RPC")
                    syncDiscordState()
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(5_000)
                Timber.tag("DiscordSvc").v("polling: periodic syncDiscordState tick")
                syncDiscordState()
            }
        }

        dataStore.data
            .map { it[EnableLastFMScrobblingKey] ?: false }
            .debounce(300)
            .distinctUntilChanged()
            .collect(scope) { enabled ->
                if (enabled && scrobbleManager == null) {
                    val delayPercent = dataStore.get(ScrobbleDelayPercentKey, LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT)
                    val minSongDuration =
                        dataStore.get(ScrobbleMinSongDurationKey, LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION)
                    val delaySeconds = dataStore.get(ScrobbleDelaySecondsKey, LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS)
                    scrobbleManager =
                        ScrobbleManager(
                            scope,
                            minSongDuration = minSongDuration,
                            scrobbleDelayPercent = delayPercent,
                            scrobbleDelaySeconds = delaySeconds,
                        )
                    scrobbleManager?.useNowPlaying = dataStore.get(LastFMUseNowPlaying, false)
                } else if (!enabled && scrobbleManager != null) {
                    scrobbleManager?.destroy()
                    scrobbleManager = null
                }
            }

        dataStore.data
            .map { it[LastFMUseNowPlaying] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                scrobbleManager?.useNowPlaying = it
            }

        dataStore.data
            .map { prefs ->
                Triple(
                    prefs[ScrobbleDelayPercentKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT,
                    prefs[ScrobbleMinSongDurationKey] ?: LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION,
                    prefs[ScrobbleDelaySecondsKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS,
                )
            }.distinctUntilChanged()
            .collect(scope) { (delayPercent, minSongDuration, delaySeconds) ->
                scrobbleManager?.let {
                    it.scrobbleDelayPercent = delayPercent
                    it.minSongDuration = minSongDuration
                    it.scrobbleDelaySeconds = delaySeconds
                }
            }

        combine(
            dataStore.data.map { prefs ->
                Triple(
                    prefs[CrossfadeEnabledKey] ?: false,
                    prefs[CrossfadeDurationKey] ?: 5f,
                    prefs[CrossfadeGaplessKey] ?: true,
                )
            },
            listenTogetherManager.roomState,
        ) { (enabled, duration, gapless), roomState ->
            Triple(enabled && roomState == null, duration, gapless)
        }.distinctUntilChanged()
            .collect(scope) { (enabled, duration, gapless) ->
                crossfadeEnabled = enabled
                crossfadeDuration = duration * 1000f // Convert to ms
                crossfadeGapless = gapless
            }

        // Observe and cache common preferences to avoid runBlocking reads in playback callbacks
        scope.launch {
            dataStore.data.map { it[PersistentQueueKey] ?: true }.distinctUntilChanged().collect { cachedPersistentQueue = it }
        }
        scope.launch {
            dataStore.data.map { it[AutoplayKey] ?: true }.distinctUntilChanged().collect { cachedAutoplay = it }
        }
        scope.launch {
            dataStore.data.map { it[DisableLoadMoreWhenRepeatAllKey] ?: false }.distinctUntilChanged().collect { cachedDisableLoadMoreWhenRepeatAll = it }
        }
        scope.launch {
            dataStore.data.map { it[HideExplicitKey] ?: false }.distinctUntilChanged().collect { cachedHideExplicit = it }
        }
        scope.launch {
            dataStore.data.map { it[HideVideoSongsKey] ?: false }.distinctUntilChanged().collect { cachedHideVideoSongs = it }
        }
        scope.launch {
            dataStore.data.map { it[ShufflePlaylistFirstKey] ?: false }.distinctUntilChanged().collect { cachedShufflePlaylistFirst = it }
        }
        scope.launch {
            dataStore.data.map { it[AutoLoadMoreKey] ?: true }.distinctUntilChanged().collect { cachedAutoLoadMore = it }
        }
        // Keep YTPlayerUtils in sync with the stream source toggles (Settings → Stream sources).
        // Map to the derived set + distinctUntilChanged so an unrelated preference write doesn't
        // rebuild the set and rewrite the @Volatile field on every DataStore emission.
        scope.launch {
            dataStore.data
                .map { prefs ->
                    buildSet {
                        if (prefs[StreamSourceWebRemixKey] == false) add("WEB_REMIX")
                        if (prefs[StreamSourceTVHTML5Key] == false) add("TVHTML5")
                        if (prefs[StreamSourceAndroidVRKey] == false) add("ANDROID_VR")
                        if (prefs[StreamSourceVisionOSKey] == false) add("VISIONOS")
                        if (prefs[StreamSourceWebCreatorKey] == false) add("WEB_CREATOR")
                    }
                }
                .distinctUntilChanged()
                .collect { YTPlayerUtils.disabledStreamClients = it }
        }

        if (startupPrefs!![PersistentQueueKey] ?: true) {
            val queueFile = filesDir.resolve(PERSISTENT_QUEUE_FILE)
            if (queueFile.exists()) {
                runCatching {
                    queueFile.inputStream().use { fis ->
                        ObjectInputStream(fis).use { oos ->
                            oos.readObject() as PersistQueue
                        }
                    }
                }.onSuccess { queue ->
                    runCatching {
                        val restoredQueue = queue.toQueue()
                        scope.launch {
                            playerInitialized.first { it }
                            if (isActive) {
                                playQueue(
                                    queue = restoredQueue,
                                    playWhenReady = false,
                                )
                            }
                        }
                    }.onFailure { error ->
                        Timber.tag(TAG).w(error, "Failed to restore persisted queue, clearing data")
                        clearPersistedQueueFiles()
                    }
                }.onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to read persisted queue, clearing data")
                    clearPersistedQueueFiles()
                }
            }

            val automixFile = filesDir.resolve(PERSISTENT_AUTOMIX_FILE)
            if (automixFile.exists()) {
                runCatching {
                    automixFile.inputStream().use { fis ->
                        ObjectInputStream(fis).use { oos ->
                            oos.readObject() as PersistQueue
                        }
                    }
                }.onSuccess { queue ->
                    runCatching {
                        automixItems.value = queue.items.map { it.toMediaItem() }
                    }.onFailure { error ->
                        Timber.tag(TAG).w(error, "Failed to restore automix queue, clearing data")
                        clearPersistedQueueFiles()
                    }
                }.onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to read automix queue, clearing data")
                    clearPersistedQueueFiles()
                }
            }

            val playerStateFile = filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE)
            if (playerStateFile.exists()) {
                runCatching {
                    playerStateFile.inputStream().use { fis ->
                        ObjectInputStream(fis).use { oos ->
                            oos.readObject() as PersistPlayerState
                        }
                    }
                }.onSuccess { playerState ->
                    scope.launch {
                        delay(1000) // Wait for queue to be loaded
                        // Don't restore repeat/shuffle from playerState as they are already set from DataStore (source of truth)
                        // player.repeatMode = playerState.repeatMode
                        // player.shuffleModeEnabled = playerState.shuffleModeEnabled
                        playerVolume.value = playerState.volume

                        if (playerState.currentMediaItemIndex < player.mediaItemCount) {
                            player.seekTo(playerState.currentMediaItemIndex, playerState.currentPosition)
                        }
                    }
                }.onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to read player state, clearing data")
                    clearPersistedQueueFiles()
                }
            }
        }

        // Save queue periodically to prevent queue loss from crash or force kill
        scope.launch {
            while (isActive) {
                delay(15.seconds)
                if (cachedPersistentQueue) {
                    saveQueueToDisk()
                }
                val currentMetadata = player.currentMediaItem?.metadata
                if (currentMetadata?.isEpisode == true && player.isPlaying && player.currentPosition > 0) {
                    previousEpisodePosition = player.currentPosition
                    saveEpisodePosition(currentMetadata.id, player.currentPosition)
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(10.seconds)
                if (cachedPersistentQueue && player.isPlaying) {
                    saveQueueToDisk()
                }
            }
        }
    }

    private fun createExoPlayer(prefs: Preferences? = null): ExoPlayer {
        val normalizationProcessor = VolumeNormalizationAudioProcessor().also {
            it.enabled = cachedNormalizationEnabled
            cachedNormalizationGainMb?.let { gain -> it.setTargetGain(gain) }
        }
        val eqProcessor = CustomEqualizerAudioProcessor()
        equalizerService.addAudioProcessor(eqProcessor)

        val silenceProcessor = SilenceDetectorAudioProcessor { handleLongSilenceDetected() }

        // Set initial state — use pre-read prefs when available, otherwise fall back to DataStore
        val useAudioTrackPlaybackParams = if (prefs != null) {
            val skipSilence = prefs[SkipSilenceKey] ?: false
            val instantSkip = prefs[SkipSilenceInstantKey] ?: false
            silenceProcessor.instantModeEnabled = skipSilence && instantSkip
            prefs[AudioTrackPlaybackParamsKey] ?: true
        } else {
            runBlocking {
                val skipSilence = dataStore.get(SkipSilenceKey, false)
                val instantSkip = dataStore.get(SkipSilenceInstantKey, false)
                silenceProcessor.instantModeEnabled = skipSilence && instantSkip
                dataStore.get(AudioTrackPlaybackParamsKey, true)
            }
        }

        val player =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(createMediaSourceFactory())
                .setRenderersFactory(createRenderersFactory(normalizationProcessor, eqProcessor, silenceProcessor, useAudioTrackPlaybackParams))
                .setLoadControl(
                    // Start playback once ~750ms is buffered (media3's default is 1000ms) so first
                    // audio is audible a touch sooner. min/max/after-rebuffer match the media3 1.x
                    // defaults (50s / 50s / 2000ms) so buffering and post-stall recovery are unchanged.
                    DefaultLoadControl
                        .Builder()
                        .setBufferDurationsMs(50_000, 50_000, 750, 2_000)
                        .build(),
                )
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false,
                ).setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                .setDeviceVolumeControlEnabled(true)
                .build()

        playerNormalizationProcessors[player] = normalizationProcessor
        playerSilenceProcessors[player] = silenceProcessor

        if (prefs != null) {
            val offload = prefs[AudioOffload] ?: false
            val crossfade = prefs[CrossfadeEnabledKey] ?: false
            player.setOffloadEnabled(if (crossfade) false else offload)
            player.skipSilenceEnabled = prefs[SkipSilenceKey] ?: false
        } else {
            player.apply {
                runBlocking {
                    val offload = dataStore.get(AudioOffload, false)
                    val crossfade = dataStore.get(CrossfadeEnabledKey, false)
                    setOffloadEnabled(if (crossfade) false else offload)
                    skipSilenceEnabled = dataStore.get(SkipSilenceKey, false)
                }
            }
        }
        player.addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))

        // Cleanup handled manually in onDestroy/release
        _playerFlow.value = player
        return player
    }

    private fun setupAudioFocusRequest() {
        audioFocusRequest =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes
                        .Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                ).setOnAudioFocusChangeListener { focusChange ->
                    handleAudioFocusChange(focusChange)
                }.setAcceptsDelayedFocusGain(true)
                .build()
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            -> {
                hasAudioFocus = true
                audioFocusVolumeMultiplier.value = 1f

                if (wasPlayingBeforeAudioFocusLoss && !player.isPlaying && !reentrantFocusGain) {
                    reentrantFocusGain = true
                    scope.launch {
                        delay(300)
                        if (hasAudioFocus && wasPlayingBeforeAudioFocusLoss && !player.isPlaying) {
                            if (castConnectionHandler?.isCasting?.value != true) {
                                player.play()
                            }
                            wasPlayingBeforeAudioFocusLoss = false
                        }
                        reentrantFocusGain = false
                    }
                }

                applyEffectiveVolume()
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                audioFocusVolumeMultiplier.value = 1f
                wasPlayingBeforeAudioFocusLoss = player.isPlaying
                if (player.isPlaying) {
                    player.pause()
                }
                abandonAudioFocus()
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                audioFocusVolumeMultiplier.value = 1f
                wasPlayingBeforeAudioFocusLoss = player.isPlaying
                if (player.isPlaying) {
                    player.pause()
                }
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasAudioFocus = false
                audioFocusVolumeMultiplier.value = 0.2f
                wasPlayingBeforeAudioFocusLoss = player.isPlaying
                if (player.isPlaying) {
                    applyEffectiveVolume()
                }
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                hasAudioFocus = true
                audioFocusVolumeMultiplier.value = 1f
                applyEffectiveVolume()
                lastAudioFocusState = focusChange
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        audioFocusRequest?.let { request ->
            val result = audioManager.requestAudioFocus(request)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            return hasAudioFocus
        }
        return false
    }

    private fun abandonAudioFocus() {
        if (hasAudioFocus) {
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
                hasAudioFocus = false
            }
        }
    }

    private fun clearPersistedQueueFiles() {
        runCatching { filesDir.resolve(PERSISTENT_QUEUE_FILE).delete() }
        runCatching { filesDir.resolve(PERSISTENT_AUTOMIX_FILE).delete() }
        runCatching { filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE).delete() }
    }

    fun hasAudioFocusForPlayback(): Boolean = hasAudioFocus

    private fun waitOnNetworkError() {
        if (waitingForNetworkConnection.value) return

        // Always arm the reconnect listener (connectivityObserver.networkStatus.collect)
        // before anything else can return early. Previously, hitting MAX_RETRY_COUNT while
        // still offline called stopOnError() and returned WITHOUT setting
        // waitingForNetworkConnection = true. That meant the "isConnected && waitingFor...
        // -> triggerRetry()" listener never fired once the network actually came back —
        // the player was left paused in a post-error state, and since ExoPlayer requires an
        // explicit prepare() after a fatal error before play() does anything, no song would
        // play again until the app was killed and relaunched (which recreates the player).
        waitingForNetworkConnection.value = true
        pausedDueToNetworkError = false

        if (retryCount >= MAX_RETRY_COUNT) {
            Timber.tag(TAG).w("Max retry count ($MAX_RETRY_COUNT) reached, pausing until network returns")
            pausedDueToNetworkError = true
            stopOnError()
            retryCount = 0
            // Don't schedule another backoff job — we're out of attempts for now — but stay
            // "waiting" so the connectivity listener can still auto-resume on reconnect.
            retryJob?.cancel()
            return
        }

        retryJob?.cancel()
        retryJob =
            scope.launch {
                // Exponential backoff: 3s, 6s, 12s, 24s... max 30s
                val delayMs = minOf(3000L * (1 shl retryCount), 30000L)
                Timber.tag(TAG).d("Waiting ${delayMs}ms before retry attempt ${retryCount + 1}/$MAX_RETRY_COUNT")
                delay(delayMs)

                if (isNetworkConnected.value && waitingForNetworkConnection.value) {
                    retryCount++
                    triggerRetry()
                }
                // If still offline when the timer fires, just let the job end — we stay
                // "waiting" and the connectivityObserver listener (not this job) is what
                // will catch the eventual reconnection and call triggerRetry().
            }
    }

    private fun triggerRetry() {
        waitingForNetworkConnection.value = false
        retryJob?.cancel()
        val shouldResumePlayback = pausedDueToNetworkError
        pausedDueToNetworkError = false

        if (player.currentMediaItem != null) {
            // After 3+ failed retries, try to refresh the stream URL by seeking to current position
            // This forces ExoPlayer to re-resolve the data source and get a fresh URL
            if (retryCount > 3) {
                Timber.tag(TAG).d("Retry count > 3, attempting to refresh stream URL")
                val currentPosition = player.currentPosition
                player.seekTo(player.currentMediaItemIndex, currentPosition)
            }
            player.prepare()
            if (shouldResumePlayback) {
                // We explicitly paused this ourselves (stopOnError) purely because of the
                // network outage — playWhenReady is now false, so prepare() alone would just
                // sit there "ready but paused" until the user manually pressed play again on
                // this exact item. Resume explicitly so reconnecting actually resumes audio.
                player.playWhenReady = true
            }
            // Otherwise (we never force-paused), leave playWhenReady as-is and let the
            // player auto-resume on its own — this avoids stealing audio focus on ordinary
            // mid-stream retries where the user never lost the "should be playing" intent.
        }
    }

    private fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 2
        val nextWindowIndex = player.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            if (castConnectionHandler?.isCasting?.value != true) {
                player.play()
            }
            return
        }

        player.pause()
        consecutivePlaybackErr = 0
    }

    private fun stopOnError() {
        player.pause()
    }

    private fun updateNotification(isLiked: Boolean? = currentSong.value?.song?.let { if (it.isEpisode) it.inLibrary != null else it.liked }) {
        mediaSession?.setCustomLayout(
            listOf(
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            if (isLiked == true) {
                                R.string.action_remove_like
                            } else {
                                R.string.action_like
                            },
                        ),
                    ).setIconResId(if (isLiked == true) R.drawable.ic_heart else R.drawable.ic_heart_outline)
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> throw IllegalStateException()
                            },
                        ),
                    ).setIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat
                            REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> throw IllegalStateException()
                        },
                    ).setSessionCommand(CommandToggleRepeatMode)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(getString(if (player.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle)
                    .setSessionCommand(CommandToggleShuffle)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(getString(R.string.start_radio))
                    .setIconResId(R.drawable.radio)
                    .setSessionCommand(CommandToggleStartRadio)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(getString(R.string.android_auto_target_playlist))
                    .setIconResId(R.drawable.playlist_add)
                    .setSessionCommand(CommandAddToTargetPlaylist)
                    .setEnabled(currentSong.value != null)
                    .build(),
            ),
        )
    }

    /**
     * Registers / refreshes song metadata (title, duration, isVideo, related songs)
     * for [mediaId]. Pure metadata bookkeeping only — does NOT touch [dateDownload].
     *
     * Looks across player, secondaryPlayer and fadingPlayer so metadata is still
     * found correctly while a crossfade swap is in progress.
     *
     * Safe to call frequently (e.g. on every dataSpec resolution) since it no longer
     * has any side effect tied to caching completeness.
     */
    private suspend fun recoverSong(
        mediaId: String,
        playbackData: YTPlayerUtils.PlaybackData? = null,
    ) {
        val song = database.song(mediaId).first()
        val mediaMetadata =
            withContext(Dispatchers.Main) {
                player.findNextMediaItemById(mediaId)?.metadata
                    ?: secondaryPlayer?.findNextMediaItemById(mediaId)?.metadata
                    ?: fadingPlayer?.findNextMediaItemById(mediaId)?.metadata
            }

        if (mediaMetadata == null && song == null) return

        val duration =
            song?.song?.duration?.takeIf { it != -1 }
                ?: mediaMetadata?.duration?.takeIf { it != -1 }
                ?: (
                    playbackData?.videoDetails ?: YTPlayerUtils
                        .playerResponseForMetadata(mediaId)
                        .getOrNull()
                        ?.videoDetails
                )?.lengthSeconds?.toInt()
                ?: -1

        database.query {
            if (song == null && mediaMetadata != null) {
                insert(mediaMetadata.copy(duration = duration))
            } else if (song != null) {
                var updatedSong = song.song
                if (song.song.duration == -1) {
                    updatedSong = updatedSong.copy(duration = duration)
                }
                if (mediaMetadata != null && song.song.isVideo != mediaMetadata.isVideoSong) {
                    updatedSong = updatedSong.copy(isVideo = mediaMetadata.isVideoSong)
                }
                if (updatedSong != song.song) {
                    update(updatedSong)
                }
            }
        }

        if (!database.hasRelatedSongs(mediaId)) {
            val relatedEndpoint =
                YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint
                    ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id,
                        )
                    }.forEach(::insert)
            }
        }
    }

    /**
     * Launches [recoverSong] for [mediaId] unless a call for the same mediaId is
     * already in flight, in which case this is a no-op.
     *
     * recoverSong() is called from resolveDataSpec() on every dataSpec/chunk
     * resolution rather than once per song, so without this guard a heavily
     * fragmented (e.g. long-cached) file can fan out dozens of redundant,
     * concurrent recoverSong() coroutines — each doing a Room read, a hop to
     * Dispatchers.Main, and a Room transaction — for work that's already done
     * after the first one completes. Always call this instead of launching
     * recoverSong() directly.
     */
    private fun recoverSongDeduped(
        mediaId: String,
        playbackData: YTPlayerUtils.PlaybackData? = null,
    ) {
        if (!recoveringSongs.add(mediaId)) return
        scope.launch(Dispatchers.IO) {
            try {
                recoverSong(mediaId, playbackData)
            } finally {
                recoveringSongs.remove(mediaId)
            }
        }
    }

    /**
     * Marks [mediaId] as belonging to the Cache Playlist by setting [dateDownload],
     * but ONLY if the full file (byte 0 through contentLength) is actually present
     * in playerCache. This must only be called from a genuine "track finished
     * naturally" signal (see onMediaItemTransition's AUTO-reason handling) —
     * never from raw dataSpec/chunk resolution, since the player's background
     * prefetch can finish downloading a short file in seconds, long before the
     * user has actually listened to it (or even if they skipped away early).
     *
     * No-op if already marked downloaded, or if we don't yet know the file's
     * contentLength (FormatEntity not fetched yet).
     */
    private suspend fun markCachedIfFullyDownloaded(mediaId: String) {
        val song = database.song(mediaId).first() ?: return
        if (song.song.dateDownload != null || song.song.isDownloaded) return
        val contentLength = song.format?.contentLength ?: return
        if (!playerCache.isCached(mediaId, 0, contentLength)) return
        database.query {
            update(song.song.copy(dateDownload = java.time.LocalDateTime.now()))
        }
    }

    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
    ) {
        if (!playerInitialized.value) {
            Timber.tag(TAG).w("playQueue called before player initialization, queuing request")
            scope.launch {
                playerInitialized.first { it }
                playQueue(queue, playWhenReady)
            }
            return
        }

        currentQueue = queue
        queueTitle = null
        val persistShuffleAcrossQueues = dataStore.get(PersistentShuffleAcrossQueuesKey, false)
        val previousShuffleEnabled = player.shuffleModeEnabled
        if (!persistShuffleAcrossQueues) {
            player.shuffleModeEnabled = false
        }
        originalQueueSize = 0
        if (queue.preloadItem != null) {
            player.setMediaItem(queue.preloadItem!!.toMediaItem())
            player.prepare()
            player.playWhenReady = playWhenReady
        }
        scope.launch(SilentHandler) {
            val initialStatus =
                withContext(Dispatchers.IO) {
                    queue
                        .getInitialStatus()
                        .filterExplicit(dataStore.get(HideExplicitKey, false))
                        .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                }
            if (queue.preloadItem != null && player.playbackState == STATE_IDLE) return@launch
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            if (initialStatus.items.isEmpty()) return@launch
            // Track original queue size for shuffle playlist first feature
            originalQueueSize = initialStatus.items.size
            if (queue.preloadItem != null) {
                player.addMediaItems(
                    0,
                    initialStatus.items.subList(0, initialStatus.mediaItemIndex),
                )
                player.addMediaItems(
                    initialStatus.items.subList(
                        initialStatus.mediaItemIndex + 1,
                        initialStatus.items.size,
                    ),
                )
            } else {
                player.setMediaItems(
                    initialStatus.items,
                    if (initialStatus.mediaItemIndex >
                        0
                    ) {
                        initialStatus.mediaItemIndex
                    } else {
                        0
                    },
                    initialStatus.position,
                )
                player.prepare()
                player.playWhenReady = playWhenReady
            }

            if (player.shuffleModeEnabled) {
                val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
                applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
            }
        }
    }

    fun startRadioSeamlessly() {
        if (!playerInitialized.value) {
            Timber.tag(TAG).w("startRadioSeamlessly called before player initialization")
            return
        }

        val currentMediaMetadata = player.currentMetadata ?: return

        val currentIndex = player.currentMediaItemIndex
        val currentMediaId = currentMediaMetadata.id

        scope.launch(SilentHandler) {
            // Use simple videoId to let YouTube personalize recommendations
            val radioQueue =
                YouTubeQueue(
                    endpoint =
                        WatchEndpoint(
                            videoId = currentMediaId,
                        ),
                )

            try {
                val initialStatus =
                    withContext(Dispatchers.IO) {
                        radioQueue
                            .getInitialStatus()
                            .filterExplicit(dataStore.get(HideExplicitKey, false))
                            .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                    }

                if (initialStatus.title != null) {
                    queueTitle = initialStatus.title
                }

                val radioItems =
                    initialStatus.items.filter { item ->
                        item.mediaId != currentMediaId
                    }

                if (radioItems.isNotEmpty()) {
                    val itemCount = player.mediaItemCount

                    if (itemCount > currentIndex + 1) {
                        player.removeMediaItems(currentIndex + 1, itemCount)
                    }

                    player.addMediaItems(currentIndex + 1, radioItems)
                    if (player.shuffleModeEnabled) {
                        val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
                        applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
                    }
                }

                currentQueue = radioQueue
            } catch (e: Exception) {
                try {
                    val nextResult =
                        withContext(Dispatchers.IO) {
                            YouTube.next(WatchEndpoint(videoId = currentMediaId)).getOrNull()
                        }
                    nextResult?.relatedEndpoint?.let { relatedEndpoint ->
                        val relatedPage =
                            withContext(Dispatchers.IO) {
                                YouTube.related(relatedEndpoint).getOrNull()
                            }
                        relatedPage?.songs?.let { songs ->
                            val radioItems =
                                songs
                                    .filter { it.id != currentMediaId }
                                    .map { it.toMediaItem() }
                                    .filterExplicit(cachedHideExplicit)
                                    .filterVideoSongs(cachedHideVideoSongs)

                            if (radioItems.isNotEmpty()) {
                                val itemCount = player.mediaItemCount
                                if (itemCount > currentIndex + 1) {
                                    player.removeMediaItems(currentIndex + 1, itemCount)
                                }
                                player.addMediaItems(currentIndex + 1, radioItems)
                                if (player.shuffleModeEnabled) {
                                    applyShuffleOrder(
                                        player.currentMediaItemIndex,
                                        player.mediaItemCount,
                                        cachedShufflePlaylistFirst,
                                    )
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun getAutomixAlbum(albumId: String) {
        scope.launch(SilentHandler) {
            YouTube
                .album(albumId)
                .onSuccess {
                    getAutomix(it.album.playlistId)
                }
        }
    }

    fun getAutomix(playlistId: String) {
        if (dataStore.get(SimilarContent, true) &&
            !(dataStore.get(DisableLoadMoreWhenRepeatAllKey, false) && player.repeatMode == REPEAT_MODE_ALL)
        ) {
            scope.launch(SilentHandler) {
                try {
                    YouTube
                        .next(WatchEndpoint(playlistId = playlistId))
                        .onSuccess { firstResult ->
                            YouTube
                                .next(WatchEndpoint(playlistId = firstResult.endpoint.playlistId))
                                .onSuccess { secondResult ->
                                    automixItems.value =
                                        secondResult.items.map { song ->
                                            song.toMediaItem()
                                        }
                                }.onFailure {
                                    if (firstResult.items.isNotEmpty()) {
                                        automixItems.value =
                                            firstResult.items.map { song ->
                                                song.toMediaItem()
                                            }
                                    }
                                }
                        }.onFailure {
                            val currentSong = player.currentMetadata
                            if (currentSong != null) {
                                // Use simple videoId for better personalized recommendations
                                YouTube
                                    .next(
                                        WatchEndpoint(
                                            videoId = currentSong.id,
                                        ),
                                    ).onSuccess { radioResult ->
                                        val filteredItems =
                                            radioResult.items
                                                .filter { it.id != currentSong.id }
                                                .map { it.toMediaItem() }
                                        if (filteredItems.isNotEmpty()) {
                                            automixItems.value = filteredItems
                                        }
                                    }.onFailure {
                                        YouTube
                                            .next(WatchEndpoint(videoId = currentSong.id))
                                            .getOrNull()
                                            ?.relatedEndpoint
                                            ?.let { relatedEndpoint ->
                                                YouTube.related(relatedEndpoint).onSuccess { relatedPage ->
                                                    val relatedItems =
                                                        relatedPage.songs
                                                            .filter { it.id != currentSong.id }
                                                            .map { it.toMediaItem() }
                                                    if (relatedItems.isNotEmpty()) {
                                                        automixItems.value = relatedItems
                                                    }
                                                }
                                            }
                                    }
                            }
                        }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun addToQueueAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        addToQueue(listOf(item))
    }

    fun playNextAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        playNext(listOf(item))
    }

    fun clearAutomix() {
        automixItems.value = emptyList()
    }

    fun playNext(items: List<MediaItem>) {
        // If queue is empty or player is idle, play immediately instead
        if (player.mediaItemCount == 0 || player.playbackState == STATE_IDLE) {
            player.setMediaItems(items)
            player.prepare()
            if (castConnectionHandler?.isCasting?.value != true) {
                player.play()
            }
            return
        }

        if (dataStore.get(PreventDuplicateTracksInQueueKey, false)) {
            val itemIds = items.map { it.mediaId }.toSet()
            val indicesToRemove = mutableListOf<Int>()
            val currentIndex = player.currentMediaItemIndex

            for (i in 0 until player.mediaItemCount) {
                if (i != currentIndex && player.getMediaItemAt(i).mediaId in itemIds) {
                    indicesToRemove.add(i)
                }
            }

            // Remove from highest index to lowest to maintain index stability
            indicesToRemove.sortedDescending().forEach { index ->
                player.removeMediaItem(index)
            }
        }

        val insertIndex = player.currentMediaItemIndex + 1
        val shuffleEnabled = player.shuffleModeEnabled

        // Insert items immediately after the current item in the window/index space
        player.addMediaItems(insertIndex, items)
        player.prepare()

        if (shuffleEnabled) {
            // Rebuild shuffle order so that newly inserted items are played next
            val timeline = player.currentTimeline
            if (!timeline.isEmpty) {
                val size = timeline.windowCount
                val currentIndex = player.currentMediaItemIndex

                // Newly inserted indices are a contiguous range [insertIndex, insertIndex + items.size)
                val newIndices = (insertIndex until (insertIndex + items.size)).toSet()

                // Collect existing shuffle traversal order excluding current index
                val orderAfter = mutableListOf<Int>()
                var idx = currentIndex
                while (true) {
                    idx = timeline.getNextWindowIndex(idx, Player.REPEAT_MODE_OFF, /*shuffleModeEnabled=*/true)
                    if (idx == C.INDEX_UNSET) break
                    if (idx != currentIndex) orderAfter.add(idx)
                }

                val prevList = mutableListOf<Int>()
                var pIdx = currentIndex
                while (true) {
                    pIdx = timeline.getPreviousWindowIndex(pIdx, Player.REPEAT_MODE_OFF, /*shuffleModeEnabled=*/true)
                    if (pIdx == C.INDEX_UNSET) break
                    if (pIdx != currentIndex) prevList.add(pIdx)
                }
                prevList.reverse() // preserve original forward order

                val existingOrder = (prevList + orderAfter).filter { it != currentIndex && it !in newIndices }

                // Build new shuffle order: current -> newly inserted (in insertion order) -> rest
                val nextBlock = (insertIndex until (insertIndex + items.size)).toList()
                val finalOrder = IntArray(size)
                var pos = 0
                prevList
                    .filter { it !in newIndices }
                    .forEach { if (it in 0 until size) finalOrder[pos++] = it }
                finalOrder[pos++] = currentIndex
                nextBlock.forEach { if (it in 0 until size) finalOrder[pos++] = it }
                orderAfter
                    .filter { it !in newIndices }
                    .forEach { if (pos < size) finalOrder[pos++] = it }

                // Fill any missing indices (safety) to ensure a full permutation
                if (pos < size) {
                    for (i in 0 until size) {
                        if (!finalOrder.contains(i)) {
                            finalOrder[pos++] = i
                            if (pos == size) break
                        }
                    }
                }

                player.setShuffleOrder(DefaultShuffleOrder(finalOrder, System.currentTimeMillis()))
            }
        }
    }

    fun addToQueue(items: List<MediaItem>) {
        if (dataStore.get(PreventDuplicateTracksInQueueKey, false)) {
            val itemIds = items.map { it.mediaId }.toSet()
            val indicesToRemove = mutableListOf<Int>()
            val currentIndex = player.currentMediaItemIndex

            for (i in 0 until player.mediaItemCount) {
                if (i != currentIndex && player.getMediaItemAt(i).mediaId in itemIds) {
                    indicesToRemove.add(i)
                }
            }

            // Remove from highest index to lowest to maintain index stability
            indicesToRemove.sortedDescending().forEach { index ->
                player.removeMediaItem(index)
            }
        }

        player.addMediaItems(items)
        if (player.shuffleModeEnabled) {
            val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
            applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
        }
        player.prepare()
    }

    fun toggleLibrary() {
        scope.launch {
            val songToToggle = currentSong.first()
            songToToggle?.let {
                val isInLibrary = it.song.inLibrary != null
                val token = if (isInLibrary) it.song.libraryRemoveToken else it.song.libraryAddToken

                token?.let { feedbackToken ->
                    YouTube.feedback(listOf(feedbackToken))
                }

                database.query {
                    update(it.song.toggleLibrary())
                }
                currentMediaMetadata.value = player.currentMetadata
            }
        }
    }

    fun toggleLike() {
        scope.launch {
            val songToToggle = currentSong.first()
            songToToggle?.let { librarySong ->
                val songEntity = librarySong.song

                // For podcast episodes, toggle save for later instead of like
                if (songEntity.isEpisode) {
                    toggleEpisodeSaveForLater(songEntity)
                    return@let
                }

                val song = songEntity.toggleLike()

                updateNotification(isLiked = song.liked)
                updateWidgetUI(player.isPlaying, isLiked = song.liked)

                database.query {
                    update(song)
                    syncUtils.likeSong(song)

                    if (dataStore.get(AutoDownloadOnLikeKey, false) && song.liked) {
                        val downloadRequest =
                            androidx.media3.exoplayer.offline.DownloadRequest
                                .Builder(song.id, song.id.toUri())
                                .setCustomCacheKey(song.id)
                                .setData(song.title.toByteArray())
                                .build()
                        androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                            this@MusicService,
                            ExoDownloadService::class.java,
                            downloadRequest,
                            false,
                        )
                    }
                }
                currentMediaMetadata.value = player.currentMetadata
            }
        }
    }

    fun addToTargetPlaylist() {
        scope.launch {
            val currentSong = currentSong.first() ?: return@launch
            val targetPlaylistId = dataStore.get(AndroidAutoTargetPlaylistKey, MediaSessionConstants.TARGET_PLAYLIST_AUTO)

            if (targetPlaylistId == MediaSessionConstants.TARGET_PLAYLIST_AUTO) {
                Handler(Looper.getMainLooper()).post {
                    Toast
                        .makeText(
                            this@MusicService,
                            getString(R.string.android_auto_target_playlist_not_set),
                            Toast.LENGTH_SHORT,
                        ).show()
                }
                return@launch
            }

            val targetPlaylist = database.playlist(targetPlaylistId).first()
            if (targetPlaylist != null) {
                database.addSongsToPlaylist(targetPlaylist, listOf(currentSong.id to null), prepend = true)
            }
        }
    }

    private suspend fun toggleEpisodeSaveForLater(songEntity: com.metrolist.music.db.entities.SongEntity) {
        val isCurrentlySaved = songEntity.inLibrary != null
        val shouldBeSaved = !isCurrentlySaved

        updateNotification(isLiked = shouldBeSaved)
        updateWidgetUI(player.isPlaying, isLiked = shouldBeSaved)

        // Update database first (optimistic update)
        // Also ensure isEpisode = true so it appears in saved episodes list
        database.query {
            update(
                songEntity.copy(
                    inLibrary = if (isCurrentlySaved) null else java.time.LocalDateTime.now(),
                    isEpisode = true,
                ),
            )
        }
        currentMediaMetadata.value = player.currentMetadata

        // Sync with YouTube (handles login check internally)
        val setVideoId = if (isCurrentlySaved) database.getSetVideoId(songEntity.id)?.setVideoId else null
        syncUtils.saveEpisode(songEntity.id, shouldBeSaved, setVideoId)
    }

    fun toggleStartRadio() {
        startRadioSeamlessly()
    }

    private fun seedLoudnessCacheFromPrefs() {
        val prefs = startupPrefs!!
        normalizationEnabledCached = prefs[AudioNormalizationKey] ?: true
        loudnessLevelCached = prefs[LoudnessLevelKey].toEnum(LoudnessLevel.BALANCED)

        Timber.tag(TAG).d(
            "Seeded loudness cache: normalization=$normalizationEnabledCached, level=$loudnessLevelCached"
        )
    }

    private fun applyCachedAudioNormalizationNow() {
        if (isCrossfading) return
        try {
            val gain = cachedNormalizationGainMb
            if (cachedNormalizationEnabled && gain != null) {
                playerNormalizationProcessors.values.forEach {
                    it.setTargetGain(gain)
                    it.enabled = true
                }
            } else {
                playerNormalizationProcessors.values.forEach { it.enabled = false }
            }
        } catch (e: Exception) {
            reportException(e)
            playerNormalizationProcessors.values.forEach { it.enabled = false }
        }
    }

    private fun setupAudioNormalization() {
        val requestGeneration = ++loudnessSetupGeneration
        loudnessSetupJob?.cancel()

        loudnessSetupJob = scope.launch {
            try {
                val currentMediaId = withContext(Dispatchers.Main) {
                    player.currentMediaItem?.mediaId
                }

                val normalizeAudio = normalizationEnabledCached

                if (normalizeAudio && currentMediaId != null) {
                    val format = withContext(Dispatchers.IO) {
                        database.format(currentMediaId).first()
                    }

                    val targetLufs = loudnessLevelCached.targetLufs

                    Timber.tag(TAG).d("Audio normalization enabled: $normalizeAudio")
                    
                    val measuredLufs: Double? = format?.perceptualLoudnessDb
                        ?: format?.loudnessDb?.let { it + LoudnessLevel.AGGRESSIVE.targetLufs }

                    withContext(Dispatchers.Main) {
                        if (!isActive || requestGeneration != loudnessSetupGeneration) return@withContext
                        if (player.currentMediaItem?.mediaId != currentMediaId) return@withContext

                        when {
                            measuredLufs != null -> {
                                val loudnessDb = measuredLufs - targetLufs
                                val targetGain = (-loudnessDb * 100.0).toInt()
                                val clampedGain = targetGain.coerceIn(MIN_GAIN_MB, MAX_GAIN_MB)

                                cachedNormalizationGainMb = clampedGain
                                cachedNormalizationEnabled = true
                                if (isCrossfading) {
                                    playerNormalizationProcessors[player]?.let {
                                        it.setTargetGain(clampedGain)
                                        it.enabled = true
                                    }
                                } else {
                                    playerNormalizationProcessors.values.forEach {
                                        it.setTargetGain(clampedGain)
                                        it.enabled = true
                                    }
                                }
                            }
                            format == null -> {
                                Timber.tag(TAG).d("Loudness row not ready yet; keeping cached normalization state")
                                if (isCrossfading) return@withContext
                            }
                            else -> {
                                cachedNormalizationGainMb = 0
                                cachedNormalizationEnabled = false
                                if (isCrossfading) return@withContext
                                playerNormalizationProcessors.values.forEach {
                                    it.setTargetGain(0)
                                    it.enabled = false
                                }
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (!isActive || requestGeneration != loudnessSetupGeneration) return@withContext
                        cachedNormalizationGainMb = null
                        cachedNormalizationEnabled = false
                        playerNormalizationProcessors.values.forEach { it.enabled = false }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                playerNormalizationProcessors.values.forEach { it.enabled = false }
            }
        }
    }

    private fun openAudioEffectSession() {
        val audioSessionId = player.audioSessionId
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) {
            return
        }

        if (isAudioEffectSessionOpened && openedAudioEffectSessionId == audioSessionId) {
            return
        }

        if (isAudioEffectSessionOpened && openedAudioEffectSessionId > 0) {
            closeAudioEffectSession(sessionIdOverride = openedAudioEffectSessionId, clearNormalizationCache = false)
        }

        isAudioEffectSessionOpened = true
        openedAudioEffectSessionId = audioSessionId

        sendBroadcast(
            Intent(android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }

    private fun closeAudioEffectSession(sessionIdOverride: Int? = null, clearNormalizationCache: Boolean = true) {
        val sessionIdToClose = sessionIdOverride ?: openedAudioEffectSessionId

        loudnessSetupGeneration++
        loudnessSetupJob?.cancel()
        loudnessSetupJob = null

        // Guard: only release/reset state if closing the currently active session
        val isClosingCurrentSession =
            isAudioEffectSessionOpened &&
                    openedAudioEffectSessionId != C.AUDIO_SESSION_ID_UNSET &&
                    sessionIdToClose == openedAudioEffectSessionId

        if (isClosingCurrentSession) {

            isAudioEffectSessionOpened = false
            openedAudioEffectSessionId = C.AUDIO_SESSION_ID_UNSET
        }

        if (sessionIdToClose != C.AUDIO_SESSION_ID_UNSET && sessionIdToClose > 0) {
            sendBroadcast(
                Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionIdToClose)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                },
            )
        }
    }

    private var previousMediaItemIndex = C.INDEX_UNSET
    private var previousEpisodeId: String? = null

    // Tracks the mediaId that was playing immediately before the current
    // onMediaItemTransition call, so we can decide whether IT finished
    // naturally (and is therefore safe to mark as fully cached).
    private var lastTransitionedMediaId: String? = null
    private var previousEpisodePosition: Long = 0L

    /**
     * Save podcast episode playback position to database.
     * Only saves if the item is an episode and position is meaningful (> 3 seconds).
     */
    private fun saveEpisodePosition(
        episodeId: String,
        positionMs: Long,
    ) {
        if (positionMs < 3000) return // Don't save if less than 3 seconds played
        scope.launch(Dispatchers.IO + SilentHandler) {
            database.updatePlaybackPosition(episodeId, positionMs)
            Timber.tag(TAG).d("Saved episode position: $episodeId at ${positionMs}ms")
        }
    }

    /**
     * Restore podcast episode playback position from database.
     * Seeks to saved position if available.
     */
    private fun restoreEpisodePosition(episodeId: String) {
        scope.launch(Dispatchers.IO + SilentHandler) {
            val savedPosition = database.getPlaybackPosition(episodeId)
            if (savedPosition != null && savedPosition > 0) {
                withContext(Dispatchers.Main) {
                    if (player.currentMediaItem?.mediaId == episodeId) {
                        player.seekTo(savedPosition)
                        Timber.tag(TAG).d("Restored episode position: $episodeId to ${savedPosition}ms")
                    }
                }
            }
        }
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        // The track that was playing before this transition only gets marked as
        // "fully cached" if it advanced AUTOmatically (i.e. it actually finished),
        // never on a manual skip/seek. lastTransitionedMediaId must be read BEFORE
        // it gets overwritten below.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            lastTransitionedMediaId?.let { previousId ->
                scope.launch(Dispatchers.IO) { markCachedIfFullyDownloaded(previousId) }
            }
        }
        lastTransitionedMediaId = mediaItem?.mediaId

        previousEpisodeId?.let { episodeId ->
            if (previousEpisodePosition > 0) {
                saveEpisodePosition(episodeId, previousEpisodePosition)
            }
        }
        previousEpisodeId = null
        previousEpisodePosition = 0L

        val newMetadata = mediaItem?.metadata
        if (newMetadata?.isEpisode == true) {
            previousEpisodeId = newMetadata.id
            scope.launch {
                delay(100)
                restoreEpisodePosition(newMetadata.id)
            }
        }

        // Force Repeat One if the player ignored it and auto-advanced
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            if (player.repeatMode == REPEAT_MODE_ONE &&
                previousMediaItemIndex != C.INDEX_UNSET &&
                previousMediaItemIndex != player.currentMediaItemIndex
            ) {
                player.seekTo(previousMediaItemIndex, 0)
            }
        }
        previousMediaItemIndex = player.currentMediaItemIndex

        lastPlaybackSpeed = -1.0f // force update song

        setupAudioNormalization()

        scrobbleManager?.onSongStop()
        if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
            scrobbleManager?.onSongStart(player.currentMetadata, duration = player.duration)
        }

        // Skip if this change was triggered by Cast sync (to prevent loops)
        if (castConnectionHandler?.isCasting?.value == true &&
            castConnectionHandler?.isSyncingFromCast != true &&
            mediaItem != null
        ) {
            val metadata = mediaItem.metadata
            if (metadata != null) {
                // Try to navigate to the item if it's already in Cast queue
                // This avoids a full reload which causes the widget to refresh
                val navigated = castConnectionHandler?.navigateToMediaIfInQueue(metadata.id) ?: false
                if (!navigated) {
                    castConnectionHandler?.loadMedia(metadata)
                }
            }
        }

        if (cachedAutoLoadMore &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
            currentQueue.hasNextPage() &&
            !(cachedDisableLoadMoreWhenRepeatAll && player.repeatMode == REPEAT_MODE_ALL)
        ) {
            scope.launch(SilentHandler) {
                val mediaItems =
                    withContext(Dispatchers.IO) {
                        currentQueue
                            .nextPage()
                            .filterExplicit(cachedHideExplicit)
                            .filterVideoSongs(cachedHideVideoSongs)
                    }
                if (player.playbackState != STATE_IDLE && mediaItems.isNotEmpty()) {
                    player.addMediaItems(mediaItems)
                    if (player.shuffleModeEnabled) {
                        applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, cachedShufflePlaylistFirst)
                    }
                }
            }
        }

        if (cachedPersistentQueue) {
            saveQueueToDisk()
        }
    }

    override fun onPlaybackStateChanged(
        @Player.State playbackState: Int,
    ) {
        if (playbackState == Player.STATE_ENDED) {
            // Check sleep timer guard - don't autoplay/repeat if sleep timer will pause
            val timer = sleepTimer ?: return
            if (timer.isActive && timer.pauseWhenSongEnd) {
                return
            }

            val repeatMode = player.repeatMode

            if (repeatMode == REPEAT_MODE_ALL && player.mediaItemCount > 0) {
                player.seekTo(0, 0)
                player.prepare()
                player.play()
                return
            }

            if (repeatMode == REPEAT_MODE_ONE) {
                player.seekTo(player.currentMediaItemIndex, 0)
                player.prepare()
                player.play()
                return
            }

            if (cachedAutoplay && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                if (castConnectionHandler?.isCasting?.value != true) {
                    player.play()
                }
            }
        }

        // Save state when playback state changes (but not during silence skipping)
        if (cachedPersistentQueue && !isSilenceSkipping) {
            saveQueueToDisk()
        }

        if (playbackState == Player.STATE_READY) {
            consecutivePlaybackErr = 0
            retryCount = 0
            waitingForNetworkConnection.value = false
            retryJob?.cancel()

            player.currentMediaItem?.mediaId?.let { mediaId ->
                resetRetryCount(mediaId)
                Timber.tag(TAG).d("Playback successful for $mediaId, reset retry count")
            }
            scheduleCrossfade()
        }

        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            scrobbleManager?.onSongStop()
        }
    }

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ) {
        // Safety net: if local player tries to start while casting, immediately pause it
        if (playWhenReady && castConnectionHandler?.isCasting?.value == true) {
            player.pause()
            return
        }

        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
            if (playWhenReady) {
                isPausedByVolumeMute = false
            }

            if (!playWhenReady && !isPausedByVolumeMute) {
                wasPlayingBeforeVolumeMute = false
            }
        }

        if (!playWhenReady) {
            val currentMetadata = player.currentMediaItem?.metadata
            if (currentMetadata?.isEpisode == true && player.currentPosition > 0) {
                saveEpisodePosition(currentMetadata.id, player.currentPosition)
                previousEpisodePosition = player.currentPosition
            }
        }

        if (playWhenReady) {
            applyCachedAudioNormalizationNow()
        }
    }

    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
            )
        ) {
            scheduleCrossfade()
            val isBufferingOrReady =
                player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
            if (isBufferingOrReady && player.playWhenReady) {
                val focusGranted = requestAudioFocus()
                if (focusGranted) {
                    openAudioEffectSession()
                }
            } else if (player.playbackState == Player.STATE_IDLE) {
                closeAudioEffectSession()
            }
        }
        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
        }

        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED)) {
            updateWidgetUI(player.isPlaying)
            if (player.isPlaying) {
                discordIntentionalDisconnect = false
                screenOffHandler.removeCallbacks(screenOffTimeout)
                screenOffHandler.removeCallbacks(pauseTimeout)
                startWidgetUpdates()
            } else {
                stopWidgetUpdates()
                if (isScreenOff) {
                    screenOffHandler.removeCallbacks(screenOffTimeout)
                    screenOffHandler.postDelayed(screenOffTimeout, 600_000)
                } else {
                    screenOffHandler.postDelayed(pauseTimeout, 60_000)
                }
            }
        }

        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_IS_PLAYING_CHANGED,
            )
        ) {
            syncDiscordState()
        }

        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED)) {
            scrobbleManager?.onPlayerStateChanged(player.isPlaying, player.currentMetadata, duration = player.duration)
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateNotification()
        if (shuffleModeEnabled) {
            if (player.mediaItemCount == 0) return

            val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
            val currentIndex = player.currentMediaItemIndex
            val totalCount = player.mediaItemCount

            applyShuffleOrder(currentIndex, totalCount, shufflePlaylistFirst)
        }

        if (dataStore.get(RememberShuffleAndRepeatKey, true)) {
            scope.launch {
                safeDataStoreEdit { settings ->
                    settings[ShuffleModeKey] = shuffleModeEnabled
                }
            }
        }

        if (cachedPersistentQueue) {
            saveQueueToDisk()
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        scope.launch {
            safeDataStoreEdit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }

        if (cachedPersistentQueue) {
            saveQueueToDisk()
        }
    }

    /**
     * Applies a new shuffle order to the player, maintaining the current item's position.
     * If `shufflePlaylistFirst` is true, it attempts to shuffle original items separately from added items.
     */
    private fun applyShuffleOrder(
        currentIndex: Int,
        totalCount: Int,
        shufflePlaylistFirst: Boolean,
    ) {
        if (totalCount == 0) return

        if (shufflePlaylistFirst && originalQueueSize > 0 && originalQueueSize < totalCount) {
            // Shuffle original items and added items separately
            val originalIndices = (0 until originalQueueSize).filter { it != currentIndex }.toMutableList()
            val addedIndices = (originalQueueSize until totalCount).filter { it != currentIndex }.toMutableList()

            originalIndices.shuffle()
            addedIndices.shuffle()

            val shuffledIndices = IntArray(totalCount)
            var pos = 0
            shuffledIndices[pos++] = currentIndex

            if (currentIndex < originalQueueSize) {
                originalIndices.forEach { shuffledIndices[pos++] = it }
                addedIndices.forEach { shuffledIndices[pos++] = it }
            } else {
                (0 until originalQueueSize).shuffled().forEach { shuffledIndices[pos++] = it }
                addedIndices.forEach { shuffledIndices[pos++] = it }
            }
            player.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
        } else {
            val shuffledIndices = IntArray(totalCount) { it }
            shuffledIndices.shuffle()
            // Ensure current item is first in the shuffle order
            val currentItemIndexInShuffled = shuffledIndices.indexOf(currentIndex)
            if (currentItemIndexInShuffled != -1) { // Should always be true if totalCount > 0
                val temp = shuffledIndices[0]
                shuffledIndices[0] = shuffledIndices[currentItemIndexInShuffled]
                shuffledIndices[currentItemIndexInShuffled] = temp
            }
            player.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        super.onPlaybackParametersChanged(playbackParameters)
        if (playbackParameters.speed != lastPlaybackSpeed) {
            Timber.tag("DiscordSvc").d("onPlaybackParametersChanged: speed changed %s -> %s", lastPlaybackSpeed, playbackParameters.speed)
            lastPlaybackSpeed = playbackParameters.speed
            DiscordRpcManager.notifySettingsChanged()
            scope.launch {
                delay(1000)
                if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
                    syncDiscordState()
                }
            }
        }
    }

    /**
     * Extracts the HTTP response code from an error's cause chain.
     * Returns null if no HTTP response code is found.
     */
    private fun getHttpResponseCode(error: PlaybackException): Int? {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode
            }
            cause = cause.cause
        }
        return null
    }

    /**
     * Checks if the error is caused by an expired/forbidden URL (HTTP 403).
     * This typically happens when a YouTube stream URL expires.
     */
    private fun isExpiredUrlError(error: PlaybackException): Boolean {
        val responseCode = getHttpResponseCode(error)
        return responseCode == 403
    }

    /**
     * Checks if the error is a Range Not Satisfiable error (HTTP 416).
     * This happens when cached data doesn't match the actual stream size.
     */
    private fun isRangeNotSatisfiableError(error: PlaybackException): Boolean {
        val responseCode = getHttpResponseCode(error)
        return responseCode == 416
    }

    /**
     * Checks if the error is a "page needs to be reloaded" error.
     * This is a YouTube-specific error that requires refreshing the stream.
     */
    private fun isPageReloadError(error: PlaybackException): Boolean {
        val errorMessage = error.message?.lowercase() ?: ""
        val causeMessage = error.cause?.message?.lowercase() ?: ""
        val innerCauseMessage =
            error.cause
                ?.cause
                ?.message
                ?.lowercase() ?: ""

        val reloadKeywords =
            listOf(
                "page needs to be reloaded",
                "pagina deve essere ricaricata",
                "la pagina deve essere ricaricata",
                "page must be reloaded",
                "reload",
                "ricaricata",
            )

        return reloadKeywords.any { keyword ->
            errorMessage.contains(keyword) ||
                causeMessage.contains(keyword) ||
                innerCauseMessage.contains(keyword)
        }
    }

    private fun isNetworkRelatedError(error: PlaybackException): Boolean {
        // Don't treat specific errors as network errors - they need special handling
        if (isExpiredUrlError(error) || isRangeNotSatisfiableError(error) || isPageReloadError(error)) {
            return false
        }
        return error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
            error.cause is java.net.ConnectException ||
            error.cause is java.net.UnknownHostException ||
            (error.cause as? PlaybackException)?.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    }

    /**
     * Checks if the error is caused by AudioTrack write or initialization failures.
     * These errors indicate the audio renderer is in a corrupted/invalid state.
     */
    private fun isAudioRendererError(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            (error.cause as? PlaybackException)?.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
            (error.cause as? PlaybackException)?.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED

    /**
     * Checks if the error is an IO_FILE_NOT_FOUND (ENOENT).
     *
     * In practice this surfaces when the player cache reports a chunk as cached
     * but the backing file has been evicted/removed (e.g. LRU eviction racing
     * with a buffer read, an external cache wipe, or partial corruption).
     * CacheDataSource then falls back to the upstream DefaultDataSource with a
     * URI that is just the bare mediaId (no scheme), which is interpreted as a
     * local file path and fails to open.
     */
    private fun isFileNotFoundError(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            (error.cause as? PlaybackException)?.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND

    private fun isRemotePlaybackError(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_REMOTE_ERROR

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)

        if (!playerInitialized.value) {
            Timber.tag(TAG).e(error, "Player error occurred but player not initialized")
            return
        }

        val mediaId = player.currentMediaItem?.mediaId
        Timber
            .tag(TAG)
            .w(error, "Player error occurred for $mediaId: errorCode=${error.errorCode}, message=${error.message}")
        reportException(error)

        if (mediaId != null && hasExceededRetryLimit(mediaId)) {
            Timber.tag(TAG).w("Song $mediaId has exceeded retry limit, skipping")
            markSongAsFailed(mediaId)
            handleFinalFailure()
            return
        }

        if (mediaId != null) {
            performAggressiveCacheClear(mediaId)
        }

        when {
            isAudioRendererError(error) -> {
                Timber.tag(TAG).d("AudioTrack error detected (${error.errorCode}), performing safe recovery")
                handleAudioRendererError(mediaId)
                return
            }

            isRangeNotSatisfiableError(error) -> {
                Timber.tag(TAG).d("Range Not Satisfiable (416) detected, performing strict recovery")
                handleRangeNotSatisfiableError(mediaId)
                return
            }

            isPageReloadError(error) -> {
                Timber.tag(TAG).d("Page reload error detected, performing strict recovery")
                handlePageReloadError(mediaId)
                return
            }

            isExpiredUrlError(error) -> {
                Timber.tag(TAG).d("Expired URL (403) detected, refreshing stream URL")
                handleExpiredUrlError(mediaId)
                return
            }

            isFileNotFoundError(error) -> {
                Timber.tag(TAG).d("Cache file missing (ENOENT) detected, refreshing stream")
                handleFileNotFoundError(mediaId)
                return
            }

            isRemotePlaybackError(error) -> {
                Timber.tag(TAG).d("Remote playback error detected (${error.errorCode}), refreshing stream URL")
                handleExpiredUrlError(mediaId)
                return
            }

            !isNetworkConnected.value -> {
                Timber.tag(TAG).d("No internet connection, waiting for connection")
                waitOnNetworkError()
                return
            }

            isNetworkRelatedError(error) -> {
                Timber.tag(TAG).d("Network-related error detected while connected, attempting recovery")
                handleGenericIOError(mediaId)
                return
            }
        }

        // For IO_UNSPECIFIED and IO_BAD_HTTP_STATUS, try recovery first
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        ) {
            Timber.tag(TAG).d("IO error detected (${error.errorCode}), attempting recovery")
            handleGenericIOError(mediaId)
            return
        }

        if (dataStore.get(AutoSkipNextOnErrorKey, false)) {
            Timber.tag(TAG).d("Auto-skipping to next track due to unrecoverable error")
            skipOnError()
        } else {
            Timber.tag(TAG).d("Stopping playback due to unrecoverable error")
            stopOnError()
        }
    }

    /**
     * Performs aggressive cache clearing for a media item.
     * Clears both player cache and download cache, plus URL cache.
     */
    private fun performAggressiveCacheClear(mediaId: String) {
        Timber.tag(TAG).d("Performing aggressive cache clear for $mediaId")

        songUrlCache.remove(mediaId)

        try {
            playerCache.removeResource(mediaId)
            Timber.tag(TAG).d("Cleared player cache for $mediaId")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to clear player cache for $mediaId")
        }

        try {
            YTPlayerUtils.forceRefreshForVideo(mediaId)
            Timber.tag(TAG).d("Cleared decryption caches for $mediaId")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to clear decryption caches for $mediaId")
        }
    }

    /**
     * Checks if a song has exceeded the retry limit.
     */
    private fun hasExceededRetryLimit(mediaId: String): Boolean {
        val currentRetries = currentMediaIdRetryCount[mediaId] ?: 0
        return currentRetries >= MAX_RETRY_PER_SONG
    }

    /**
     * Increments the retry count for a song.
     */
    private fun incrementRetryCount(mediaId: String) {
        val currentRetries = currentMediaIdRetryCount[mediaId] ?: 0
        currentMediaIdRetryCount[mediaId] = currentRetries + 1
        Timber.tag(TAG).d("Retry count for $mediaId: ${currentRetries + 1}/$MAX_RETRY_PER_SONG")
    }

    /**
     * Resets the retry count for a song (called on successful playback).
     */
    private fun resetRetryCount(mediaId: String) {
        currentMediaIdRetryCount.remove(mediaId)
        recentlyFailedSongs.remove(mediaId)
    }

    /**
     * Marks a song as failed to prevent further retry attempts.
     */
    private fun markSongAsFailed(mediaId: String) {
        recentlyFailedSongs.add(mediaId)
        currentMediaIdRetryCount.remove(mediaId)

        failedSongsClearJob?.cancel()
        failedSongsClearJob =
            scope.launch {
                delay(5 * 60 * 1000L) // 5 minutes
                recentlyFailedSongs.clear()
                Timber.tag(TAG).d("Cleared recently failed songs list")
            }
    }

    /**
     * Handles AudioTrack errors (write failed, init failed) with safe recovery.
     * These errors indicate the audio renderer is corrupted and needs careful reset.
     */
    private fun handleAudioRendererError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob =
            scope.launch {
                try {
                    player.pause()
                    Timber.tag(TAG).d("Paused playback due to AudioTrack error")

                    // Wait longer for audio renderer to settle before retry
                    // This prevents the renderer from continuing to fail in a loop
                    delay(RETRY_DELAY_MS * 3) // 3 seconds instead of 1 second

                    if (!playerInitialized.value) {
                        Timber.tag(TAG).w("Player no longer initialized, aborting AudioTrack recovery")
                        return@launch
                    }

                    val currentIndex = player.currentMediaItemIndex
                    if (currentIndex != C.INDEX_UNSET) {
                        // Seek to current position to force a clean audio renderer reinit
                        val currentPosition = player.currentPosition
                        player.seekTo(currentIndex, currentPosition)
                        player.prepare()

                        Timber.tag(TAG).d("Retrying playback for $mediaId after AudioTrack error")

                        if (wasPlayingBeforeAudioFocusLoss) {
                            delay(500) // Brief delay to allow renderer to be ready
                            if (hasAudioFocus && playerInitialized.value) {
                                if (castConnectionHandler?.isCasting?.value != true) {
                                    player.play()
                                }
                            }
                        }
                    } else {
                        Timber.tag(TAG).w("Invalid media item index during AudioTrack recovery")
                        handleFinalFailure()
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error during AudioTrack error recovery")
                    handleFinalFailure()
                }
            }
    }

    /**
     * Handles Range Not Satisfiable (416) errors with strict recovery.
     * This error occurs when cached data doesn't match the actual stream size.
     */
    private fun handleRangeNotSatisfiableError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob =
            scope.launch {
                performAggressiveCacheClear(mediaId)

                delay(RETRY_DELAY_MS)

                // Force re-prepare from position 0 to avoid range issues
                val currentIndex = player.currentMediaItemIndex
                player.seekTo(currentIndex, 0)
                player.prepare()

                Timber.tag(TAG).d("Retrying playback for $mediaId after 416 error (from position 0)")
            }
    }

    /**
     * Handles "page needs to be reloaded" errors with strict recovery.
     * This requires clearing decryption caches and getting fresh stream URLs.
     */
    private fun handlePageReloadError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob =
            scope.launch {
                Timber.tag(TAG).d("Handling page reload error for $mediaId")

                performAggressiveCacheClear(mediaId)

                // Additional delay for page reload errors as they may be rate-limited
                delay(RETRY_DELAY_MS * 2)

                val currentPosition = player.currentPosition
                val currentIndex = player.currentMediaItemIndex
                player.seekTo(currentIndex, currentPosition)
                player.prepare()

                Timber.tag(TAG).d("Retrying playback for $mediaId after page reload error")
            }
    }

    /**
     * Handles expired URL (403) errors by clearing caches and retrying.
     */
    private fun handleExpiredUrlError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        songUrlCache.remove(mediaId)
        // A 403/410 on GET means the (HEAD-unvalidated) WEB_REMIX stream URL was bad — mark it so the
        // re-resolution falls through to the fallback clients instead of retrying WEB_REMIX.
        YTPlayerUtils.markWebRemixFailed(mediaId)
        Timber.tag(TAG).d("Cleared cached URL for $mediaId, marked WEB_REMIX failed")

        try {
            YTPlayerUtils.forceRefreshForVideo(mediaId)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to clear decryption caches")
        }

        // A 403 can also mean the cipher produced a wrong-but-non-throwing signature from a
        // stale/wrong player config — invisible to the cipher's own exception-retry. Ask it to
        // re-fetch its config (rate-limited); if that corrects the table, the cipher rebuilds its
        // WebView on the next decipher, so we clear the WEB_REMIX failure set to let playback return
        // to WEB_REMIX — no app restart. Affects every cipher client (WEB_REMIX/WEB_CREATOR/TVHTML5/WEB).
        scope.launch {
            if (CipherDeobfuscator.onStreamRejected()) {
                Timber.tag(TAG).d("Player config changed after stream rejection — restoring WEB_REMIX")
                YTPlayerUtils.clearWebRemixFailures()
            }
        }

        retryJob?.cancel()
        retryJob =
            scope.launch {
                delay(RETRY_DELAY_MS)

                val currentPosition = player.currentPosition
                val currentIndex = player.currentMediaItemIndex
                player.seekTo(currentIndex, currentPosition)
                player.prepare()

                Timber.tag(TAG).d("Retrying playback for $mediaId after 403 error")
            }
    }

    /**
     * Handles IO_FILE_NOT_FOUND (ENOENT) by purging any cached state for the
     * media item and forcing the resolver to fetch a fresh stream URL.
     *
     * The aggressive cache clear at the top of [onPlayerError] already drops
     * the player cache entry and the cached stream URL, so re-preparing the
     * player here causes the resolver to take the "fetch fresh stream" path
     * instead of attempting another cache read for a file that no longer
     * exists on disk.
     */
    private fun handleFileNotFoundError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob =
            scope.launch {
                val currentPosition = player.currentPosition
                val currentIndex = player.currentMediaItemIndex
                if (currentIndex == C.INDEX_UNSET) {
                    Timber.tag(TAG).w("Invalid media item index during file-not-found recovery")
                    handleFinalFailure()
                    return@launch
                }
                player.seekTo(currentIndex, currentPosition)
                player.prepare()

                Timber.tag(TAG).d("Retrying playback for $mediaId after IO_FILE_NOT_FOUND")
            }
    }

    /**
     * Handles generic IO errors with recovery attempt.
     */
    private fun handleGenericIOError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob =
            scope.launch {
                performAggressiveCacheClear(mediaId)
                delay(RETRY_DELAY_MS)

                val currentPosition = player.currentPosition
                val currentIndex = player.currentMediaItemIndex
                if (currentIndex != C.INDEX_UNSET) {
                    player.seekTo(currentIndex, currentPosition)
                }
                player.prepare()

                Timber.tag(TAG).d("Retrying playback for $mediaId after generic IO error")
            }
    }

    /**
     * Handles final failure when all recovery attempts have been exhausted.
     */
    private fun handleFinalFailure() {
        val autoSkipOnError = dataStore.get(AutoSkipNextOnErrorKey, false)
        val autoplay = dataStore.get(AutoplayKey, true)
        val canAdvance = player.hasNextMediaItem()

        if (autoSkipOnError || (autoplay && canAdvance)) {
            Timber.tag(TAG).d("All recovery attempts exhausted, auto-skipping to next track")
            skipOnError()
        } else {
            Timber.tag(TAG).d("All recovery attempts exhausted, stopping playback")
            stopOnError()
        }
    }

    override fun onDeviceVolumeChanged(
        volume: Int,
        muted: Boolean,
    ) {
        super.onDeviceVolumeChanged(volume, muted)
        val pauseOnMute = dataStore.get(PauseOnMute, false)

        if ((volume == 0 || muted) && pauseOnMute) {
            if (player.isPlaying) {
                wasPlayingBeforeVolumeMute = true
                isPausedByVolumeMute = true
                player.pause()
            }
        } else if (volume > 0 && !muted && pauseOnMute) {
            if (wasPlayingBeforeVolumeMute && !player.isPlaying && castConnectionHandler?.isCasting?.value != true) {
                wasPlayingBeforeVolumeMute = false
                isPausedByVolumeMute = false
                player.play()
            }
        }
    }

    private fun createCacheDataSource(): CacheDataSource.Factory =
        CacheDataSource
            .Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                CacheDataSource
                    .Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        DefaultDataSource.Factory(
                            this,
                            OkHttpDataSource.Factory(
                                OkHttpClient
                                    .Builder()
                                    .proxy(YouTube.proxy)
                                    .proxyAuthenticator { _, response ->
                                        YouTube.proxyAuth?.let { auth ->
                                            response.request
                                                .newBuilder()
                                                .header("Proxy-Authorization", auth)
                                                .build()
                                        } ?: response.request
                                    }.build(),
                            ),
                        ),
                    ),
            ).setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

    private var isSilenceSkipping = false

    private fun handleLongSilenceDetected() {
        if (!instantSilenceSkipEnabled.value) return
        if (silenceSkipJob?.isActive == true) return

        silenceSkipJob =
            scope.launch {
                // Debounce so short fades or transitions do not trigger a jump.
                delay(200)
                performInstantSilenceSkip()
            }
    }

    private suspend fun performInstantSilenceSkip() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (duration <= INSTANT_SILENCE_SKIP_STEP_MS) return

        isSilenceSkipping = true
        try {
            var hops = 0
            val silenceProcessor = playerSilenceProcessors[player] ?: return
            while (coroutineContext.isActive && instantSilenceSkipEnabled.value && silenceProcessor.isCurrentlySilent()) {
                val current = player.currentPosition
                val target = (current + INSTANT_SILENCE_SKIP_STEP_MS).coerceAtMost(duration - 500)

                if (target <= current) break

                // Reset silence tracking before seeking to prevent immediate re-trigger
                silenceProcessor.resetTracking()
                player.seekTo(target)
                hops++

                if (hops >= 80 || target >= duration - 500) break

                delay(INSTANT_SILENCE_SKIP_SETTLE_MS)
            }
            if (hops > 0) {
                Timber.tag(TAG).d("Silence skip: jumped $hops times")
            }
        } finally {
            isSilenceSkipping = false
        }
    }

    private fun syncDiscordState() {
        if (!discordRpcEnabled) return

        val songId = player.currentMetadata?.id
        if (songId == null) {
            Timber.tag("DiscordSvc").d("syncDiscordState: no song, clearing presence")
            DiscordRpcManager.clear()
            return
        }

            if (!DiscordRpcManager.isReady()) {
            if (discordIntentionalDisconnect) {
                Timber.tag("DiscordSvc").d("syncDiscordState: not ready, skipping (intentional disconnect)")
                return
            }
            val token = DiscordRpcManager.getAccessToken()
            val now = System.currentTimeMillis()
            if (token != null && (now - lastDiscordReconnectAttemptAtMs) > 30_000L) {
                lastDiscordReconnectAttemptAtMs = now
                Timber.tag("DiscordSvc").i("syncDiscordState: not ready, attempting reconnect")
                scope.launch(Dispatchers.IO) {
                    if (!DiscordRpcManager.isInitialized()) {
                        DiscordRpcManager.init(this@MusicService)
                    }
                    DiscordRpcManager.reconnectWithToken(token)
                }
            }
            return
        }

        val isPlaying = player.isPlaying
        if (DiscordRpcManager.isShowingSong(songId, isPlaying)) {
            Timber.tag("DiscordSvc").d("syncDiscordState: dedup, already showing songId=%s isPlaying=%s", songId, isPlaying)
            return
        }

        scope.launch(Dispatchers.IO) {
            val (freshSongId, freshIsPlaying) = withContext(Dispatchers.Main.immediate) {
                player.currentMetadata?.id to player.isPlaying
            } ?: return@launch
            val song = database.song(freshSongId).first() ?: return@launch
            updateDiscordRPC(song, freshIsPlaying)
        }
    }

    private suspend fun updateDiscordRPC(song: Song, isPlaying: Boolean) {
        if (!DiscordRpcManager.isReady()) {
            Timber.tag("DiscordSvc").w("updateDiscordRPC: skipping — not ready")
            return
        }
        if (!discordRpcEnabled) {
            Timber.tag("DiscordSvc").w("updateDiscordRPC: skipping — RPC disabled")
            return
        }

        Timber.tag("DiscordSvc").i("updateDiscordRPC: song=%s, isPlaying=%s", song.song.title, isPlaying)

        // ExoPlayer must be accessed on the main thread
        val (currentPosition, speed) = withContext(Dispatchers.Main.immediate) {
            player.currentPosition to player.playbackParameters.speed
        }
        val adjustedTime = (currentPosition / speed).toLong()
        val now = System.currentTimeMillis()
        val startTime = if (isPlaying) now - adjustedTime else 0L
        val durationMs = song.song.duration.takeIf { it > 0 }?.times(1000L)
        val remainingMs = durationMs?.minus(currentPosition)?.coerceAtLeast(0L)
        val adjustedRemainingMs = remainingMs?.let { (it / speed).toLong() }
        val endTime = if (isPlaying && adjustedRemainingMs != null) now + adjustedRemainingMs else null

        val artistName = song.artists.joinToString { it.name }.ifEmpty { DiscordDefaults.UNKNOWN_ARTIST }
        val albumName = song.album?.title
        val songTitle = if (speed != 1.0f) {
            "${song.song.title} [${String.format("%.2fx", speed)}]"
        } else {
            song.song.title
        }
        val artistThumbnail = song.artists.firstOrNull()?.thumbnailUrl

        val advancedMode = dataStore.get(DiscordAdvancedModeKey, false)
        val activityType = dataStore.get(DiscordActivityTypeKey, DiscordDefaults.ACTIVITY_TYPE).toIntOrNull() ?: DiscordActivity.TYPE_LISTENING
        val activityName = dataStore.get(DiscordActivityNameKey, DiscordDefaults.ACTIVITY_NAME)
        val stateTemplate = dataStore.get(DiscordStateTemplateKey, DiscordDefaults.STATE_TEMPLATE)
        val detailsTemplate = dataStore.get(DiscordDetailsTemplateKey, DiscordDefaults.DETAILS_TEMPLATE)
        val btn1Enabled = dataStore.get(DiscordButton1EnabledKey, true)
        val btn1Label = dataStore.get(DiscordButton1LabelKey, DiscordDefaults.BUTTON1_LABEL)
        val btn1Url = dataStore.get(DiscordButton1UrlKey, DiscordDefaults.BUTTON1_URL_TEMPLATE)
        val btn2Enabled = dataStore.get(DiscordButton2EnabledKey, true)
        val btn2Label = dataStore.get(DiscordButton2LabelKey, DiscordDefaults.BUTTON2_LABEL)
        val btn2Url = dataStore.get(DiscordButton2UrlKey, DiscordDefaults.BUTTON2_URL)

        Timber.tag("DiscordSvc").d(
            "updateDiscordRPC: prefs — advancedMode=%s, activityType=%d, activityName=%s, stateTemplate=%s, detailsTemplate=%s",
            advancedMode, activityType, activityName, stateTemplate, detailsTemplate,
        )

        val activity = DiscordActivityBuilder.build(
            song = song,
            artistName = artistName,
            albumName = albumName,
            artistThumbnail = artistThumbnail,
            songTitle = songTitle,
            startTimestamp = startTime,
            endTimestamp = endTime,
            advancedMode = advancedMode,
            activityType = activityType,
            activityName = activityName,
            stateTemplate = stateTemplate,
            detailsTemplate = detailsTemplate,
            btn1Enabled = btn1Enabled,
            btn1Label = btn1Label,
            btn1Url = btn1Url,
            btn2Enabled = btn2Enabled,
            btn2Label = btn2Label,
            btn2Url = btn2Url,
        )

        Timber.tag("DiscordSvc").i("updateDiscordRPC: type=%d name=%s state=%s details=%s start=%d end=%d isPlaying=%s",
            activity.activityType, activity.name, activity.state, activity.details, startTime, endTime ?: 0L, isPlaying)

        val statusStr = dataStore.get(DiscordUserStatusKey, DiscordDefaults.USER_STATUS)
        val presenceStatus = when (statusStr) {
            DiscordDefaults.STATUS_IDLE -> if (advancedMode) PresenceStatus.Idle else PresenceStatus.Online
            DiscordDefaults.STATUS_DND -> if (advancedMode) PresenceStatus.Dnd else PresenceStatus.Online
            else -> PresenceStatus.Online
        }

        DiscordRpcManager.setActivity(
            activity,
            songId = song.song.id,
            isPlaying = isPlaying,
            status = presenceStatus,
        )

        val fetched = fetchArtistThumbnail(song)
        if (fetched != null && DiscordRpcManager.isReady() && discordRpcEnabled) {
            Timber.tag("DiscordSvc").i("updateDiscordRPC: updating with fetched thumbnail")
            val fetchedArtistName = fetched.artists.joinToString { it.name }.ifEmpty { DiscordDefaults.UNKNOWN_ARTIST }
            val fetchedAlbumName = fetched.album?.title
            val fetchedArtistThumbnail = fetched.artists.firstOrNull()?.thumbnailUrl

            val (freshPosition, freshSpeed, freshIsPlaying) = withContext(Dispatchers.Main.immediate) {
                Triple(player.currentPosition, player.playbackParameters.speed, player.isPlaying)
            }
            val freshAdjustedTime = (freshPosition / freshSpeed).toLong()
            val freshNow = System.currentTimeMillis()
            val freshStartTime = if (freshIsPlaying) freshNow - freshAdjustedTime else 0L
            val freshDurationMs = fetched.song.duration.takeIf { it > 0 }?.times(1000L)
            val freshRemainingMs = freshDurationMs?.minus(freshPosition)?.coerceAtLeast(0L)
            val freshAdjustedRemainingMs = freshRemainingMs?.let { (it / freshSpeed).toLong() }
            val freshEndTime = if (freshIsPlaying && freshAdjustedRemainingMs != null) freshNow + freshAdjustedRemainingMs else null

            val fetchedActivity = DiscordActivityBuilder.build(
                song = fetched,
                artistName = fetchedArtistName,
                albumName = fetchedAlbumName,
                artistThumbnail = fetchedArtistThumbnail,
                songTitle = songTitle,
                startTimestamp = freshStartTime,
                endTimestamp = freshEndTime,
                advancedMode = advancedMode,
                activityType = activityType,
                activityName = activityName,
                stateTemplate = stateTemplate,
                detailsTemplate = detailsTemplate,
                btn1Enabled = btn1Enabled,
                btn1Label = btn1Label,
                btn1Url = btn1Url,
                btn2Enabled = btn2Enabled,
                btn2Label = btn2Label,
                btn2Url = btn2Url,
            )

            DiscordRpcManager.setActivity(
                fetchedActivity,
                songId = song.song.id,
                isPlaying = freshIsPlaying,
                status = presenceStatus,
            )
        } else {
            Timber.tag("DiscordSvc").i("updateDiscordRPC: fetched=%s (no thumbnail update)", fetched != null)
        }
    }

    private suspend fun fetchArtistThumbnail(song: Song): Song? {
        val artist = song.artists.firstOrNull()
        if (artist == null) {
            Timber.tag("DiscordSvc").d("fetchArtistThumbnail: no artist, skipping")
            return null
        }
        if (artist.thumbnailUrl != null) {
            Timber.tag("DiscordSvc").d("fetchArtistThumbnail: artist already has thumbnail, skipping")
            return null
        }

        val browseId = when {
            artist.channelId != null && !artist.channelId.startsWith("LA")
                && !artist.channelId.startsWith("FEmusic_library_privately_owned") -> {
                artist.channelId
            }
            !artist.id.startsWith("LA")
                && !artist.id.startsWith("FEmusic_library_privately_owned") -> {
                artist.id
            }
            else -> {
                Timber.tag("DiscordSvc").d("fetchArtistThumbnail: no valid browseId for artist %s", artist.name)
                return null
            }
        }

        Timber.tag("DiscordSvc").d("fetchArtistThumbnail: fetching for artist=%s, browseId=%s", artist.name, browseId)

        return try {
            val artistPage = withContext(Dispatchers.IO) {
                YouTube.artist(browseId).getOrNull()
            }
            val thumbnail = artistPage?.artist?.thumbnail?.resize(1080, 1080)
            if (thumbnail != null) {
                Timber.tag("DiscordSvc").d("fetchArtistThumbnail: got thumbnail for %s", artist.name)
                withContext(Dispatchers.IO) {
                    database.update(artist.copy(thumbnailUrl = thumbnail))
                }
                database.getSongById(song.song.id)
            } else {
                Timber.tag("DiscordSvc").d("fetchArtistThumbnail: no thumbnail found for %s", artist.name)
                null
            }
        } catch (e: Exception) {
            Timber.tag("DiscordSvc").w(e, "fetchArtistThumbnail: failed for artist=%s", artist.name)
            null
        }
    }

    private fun createDataSourceFactory(): DataSource.Factory {
        return ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")

            val shouldBypassCache = bypassCacheForQualityChange.contains(mediaId)

            if (!shouldBypassCache) {
                val usePlayerCache = dataStore.get(EnableSongCacheKey, true)

                val contentLength =
                    runBlocking(Dispatchers.IO) {
                        database.song(mediaId).first()?.format?.contentLength
                    }
                val requiredLength =
                    when {
                        dataSpec.length >= 0 -> dataSpec.length
                        contentLength != null -> (contentLength - dataSpec.position).coerceAtLeast(1)
                        else -> CHUNK_LENGTH // contentLength unknown yet — fall back to old probe size
                    }

                if (downloadCache.isCached(mediaId, dataSpec.position, requiredLength)) {
                    recoverSongDeduped(mediaId)
                    return@Factory dataSpec
                }

                if (usePlayerCache && playerCache.isCached(mediaId, dataSpec.position, requiredLength)) {
                    recoverSongDeduped(mediaId)
                    return@Factory dataSpec
                }

                songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                    recoverSongDeduped(mediaId)
                    return@Factory dataSpec.withUri(it.first.toUri())
                }
            } else {
                Timber.tag(TAG).i("BYPASSING CACHE for $mediaId due to quality change")
            }

            Timber.tag(TAG).i("FETCHING STREAM: $mediaId | quality=$audioQuality")
            val playbackData =
                runBlocking(Dispatchers.IO) {
                    val song = database.songEntity(mediaId)
                    YTPlayerUtils.playerResponseForPlayback(
                        mediaId,
                        audioQuality = audioQuality,
                        connectivityManager = connectivityManager,
                        contentHints = ContentHints(
                            isExplicit = song?.explicit,
                            isUploaded = song?.isUploaded,
                        ),
                    )
                }.getOrElse { throwable ->
                    when (throwable) {
                        is PlaybackException -> {
                            throw throwable
                        }

                        is java.net.ConnectException, is java.net.UnknownHostException -> {
                            throw PlaybackException(
                                getString(R.string.error_no_internet),
                                throwable,
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            )
                        }

                        is java.net.SocketTimeoutException -> {
                            throw PlaybackException(
                                getString(R.string.error_timeout),
                                throwable,
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                            )
                        }

                        else -> {
                            throw PlaybackException(
                                getString(R.string.error_unknown),
                                throwable,
                                PlaybackException.ERROR_CODE_REMOTE_ERROR,
                            )
                        }
                    }
                }

            val nonNullPlayback =
                requireNotNull(playbackData) {
                    getString(R.string.error_unknown)
                }
            run {
                val format = nonNullPlayback.format
                val loudnessDb = nonNullPlayback.audioConfig?.loudnessDb
                val perceptualLoudnessDb = nonNullPlayback.audioConfig?.perceptualLoudnessDb

                Timber
                    .tag(TAG)
                    .d("Storing format for $mediaId with loudnessDb: $loudnessDb, perceptualLoudnessDb: $perceptualLoudnessDb")
                if (loudnessDb == null && perceptualLoudnessDb == null) {
                    Timber.tag(TAG).w("No loudness data available from YouTube for video: $mediaId")
                }

                database.query {
                    upsert(
                        FormatEntity(
                            id = mediaId,
                            itag = format.itag,
                            mimeType = format.mimeType.split(";")[0],
                            codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                            bitrate = format.bitrate,
                            sampleRate = format.audioSampleRate,
                            contentLength = format.contentLength!!,
                            loudnessDb = loudnessDb,
                            perceptualLoudnessDb = perceptualLoudnessDb,
                            playbackUrl = nonNullPlayback.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
                        ),
                    )
                }
                recoverSongDeduped(mediaId, nonNullPlayback)

                if (bypassCacheForQualityChange.remove(mediaId)) {
                    Timber.tag(TAG).d("Cleared bypass cache flag for $mediaId after fresh fetch")
                }

                val streamUrl = nonNullPlayback.streamUrl
                currentStreamClient.value = nonNullPlayback.streamClient

                songUrlCache[mediaId] =
                    streamUrl to System.currentTimeMillis() + (nonNullPlayback.streamExpiresInSeconds * 1000L)

                nonNullPlayback.playbackTracking?.videostatsPlaybackUrl?.baseUrl?.let {
                    playbackUrlCache[cacheKey(mediaId)] = it
                }

                return@Factory dataSpec.withUri(streamUrl.toUri()).subrange(dataSpec.uriPositionOffset, CHUNK_LENGTH)
            }
        }
    }

    private fun createMediaSourceFactory() =
        DefaultMediaSourceFactory(
            createDataSourceFactory(),
            ExtractorsFactory {
                arrayOf(MatroskaExtractor(), FragmentedMp4Extractor(), Mp4Extractor())
            },
        )

    private fun createRenderersFactory(
        normalizationProcessor: VolumeNormalizationAudioProcessor,
        eqProcessor: CustomEqualizerAudioProcessor,
        silenceProcessor: SilenceDetectorAudioProcessor,
        useAudioTrackPlaybackParams: Boolean,
    ) = object : DefaultRenderersFactory(this) {
        override fun buildAudioRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            audioSink: AudioSink,
            eventHandler: Handler,
            eventListener: AudioRendererEventListener,
            out: ArrayList<Renderer>,
        ) {
            super.buildAudioRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                audioSink,
                eventHandler,
                eventListener,
                out,
            )
            val coreRendererIndex = out.indexOfFirst { it is MediaCodecAudioRenderer }
            if (coreRendererIndex == -1) return
            out[coreRendererIndex] =
                object : MediaCodecAudioRenderer(
                    context,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    audioSink,
                ) {
                    override fun getCodecOperatingRateV23(
                        targetPlaybackSpeed: Float,
                        format: Format,
                        streamFormats: Array<Format>,
                    ): Float =
                        super.getCodecOperatingRateV23(
                            if (targetPlaybackSpeed in 0.95f..1.05f) 1f else targetPlaybackSpeed,
                            format,
                            streamFormats,
                        )
                }
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ) = DefaultAudioSink
            .Builder(this@MusicService)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(useAudioTrackPlaybackParams)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    arrayOf(
                        normalizationProcessor,
                        eqProcessor,
                        silenceProcessor,
                    ),
                    SilenceSkippingAudioProcessor(2_000_000, 20_000, 256),
                    SonicAudioProcessor(),
                ),
            ).build()
    }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats,
    ) {
        val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
        val historyDurationMs = dataStore[HistoryDuration]?.times(1000f) ?: 30000f

        if (playbackStats.totalPlayTimeMs >= historyDurationMs &&
            !dataStore.get(PauseListenHistoryKey, false)
        ) {
            database.query {
                incrementTotalPlayTime(mediaItem.mediaId, playbackStats.totalPlayTimeMs)
                try {
                    insert(
                        Event(
                            songId = mediaItem.mediaId,
                            timestamp = LocalDateTime.now(),
                            playTime = playbackStats.totalPlayTimeMs,
                        ),
                    )
                } catch (_: SQLException) {
                }
            }
        }

        if (playbackStats.totalPlayTimeMs >= historyDurationMs) {
            scope.launch(Dispatchers.IO) {
                val playbackUrl =
                    playbackUrlCache[cacheKey(mediaItem.mediaId)]
                        ?: YTPlayerUtils
                            .playerResponseForMetadata(mediaItem.mediaId, null)
                            .getOrNull()
                            ?.playbackTracking
                            ?.videostatsPlaybackUrl
                            ?.baseUrl
                if (playbackUrl == null) {
                    Timber.tag(TAG).w("No playback tracking URL available for $mediaItem.mediaId, skipping YouTube history registration")
                    return@launch
                }
                YouTube
                    .registerPlayback(null, playbackUrl)
                    .onFailure {
                        reportException(it)
                    }
            }
        }
    }

    private fun saveQueueToDisk() {
        if (player.mediaItemCount == 0) {
            Timber.tag(TAG).d("Skipping queue save - no media items")
            return
        }

        try {
            val persistQueue =
                currentQueue.toPersistQueue(
                    title = queueTitle,
                    items = player.mediaItems.mapNotNull { it.metadata },
                    mediaItemIndex = player.currentMediaItemIndex,
                    position = player.currentPosition,
                )

            val persistAutomix =
                PersistQueue(
                    title = "automix",
                    items = automixItems.value.mapNotNull { it.metadata },
                    mediaItemIndex = 0,
                    position = 0,
                )

            val persistPlayerState =
                PersistPlayerState(
                    playWhenReady = player.playWhenReady,
                    repeatMode = player.repeatMode,
                    shuffleModeEnabled = player.shuffleModeEnabled,
                    volume = playerVolume.value,
                    currentPosition = player.currentPosition,
                    currentMediaItemIndex = player.currentMediaItemIndex,
                    playbackState = player.playbackState,
                )

            runCatching {
                filesDir.resolve(PERSISTENT_QUEUE_FILE).outputStream().use { fos ->
                    ObjectOutputStream(fos).use { oos ->
                        oos.writeObject(persistQueue)
                    }
                }
                Timber.tag(TAG).d("Queue saved successfully")
            }.onFailure {
                Timber.tag(TAG).e(it, "Failed to save queue")
                reportException(it)
            }

            runCatching {
                filesDir.resolve(PERSISTENT_AUTOMIX_FILE).outputStream().use { fos ->
                    ObjectOutputStream(fos).use { oos ->
                        oos.writeObject(persistAutomix)
                    }
                }
                Timber.tag(TAG).d("Automix saved successfully")
            }.onFailure {
                Timber.tag(TAG).e(it, "Failed to save automix")
                reportException(it)
            }

            runCatching {
                filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE).outputStream().use { fos ->
                    ObjectOutputStream(fos).use { oos ->
                        oos.writeObject(persistPlayerState)
                    }
                }
                Timber.tag(TAG).d("Player state saved successfully")
            }.onFailure {
                Timber.tag(TAG).e(it, "Failed to save player state")
                reportException(it)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during queue save operation")
            reportException(e)
        }
    }

    /**
     * [Context.startForegroundService] requires [startForeground] to succeed quickly. If we cannot
     * enter the foreground state, stop immediately so the system does not ANR the app process.
     */
    private fun ensureStartedAsForegroundOrStop(): Boolean =
        startForegroundSafely(
            notification = createFallbackForegroundNotification(),
            deniedMessage = "Foreground service start not allowed; stopping service to avoid ANR",
            failureMessage = "Failed to enter foreground; stopping service to avoid ANR",
        )

    private fun ensureForegroundWithLatestNotificationOrStop(): Boolean =
        startForegroundSafely(
            notification = latestMediaNotification ?: createFallbackForegroundNotification(),
            deniedMessage = "Foreground promotion denied during notification update; stopping service",
            failureMessage = "Failed to promote service during notification update; stopping service",
            stopOnFailure = true,
        )

    private fun tryEnsureForegroundWithLatestNotification(): Boolean =
        startForegroundSafely(
            notification = latestMediaNotification ?: createFallbackForegroundNotification(),
            deniedMessage = "Foreground promotion denied during notification update",
            failureMessage = "Failed to promote service during notification update",
            stopOnFailure = false,
        )

    private fun ensureForegroundChannelExists() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.music_player),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun createFallbackForegroundNotification(): Notification {
        ensureForegroundChannelExists()
        val pending =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.music_player))
            .setContentText("")
            .setSmallIcon(R.drawable.small_icon)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundSafely(
        notification: Notification,
        deniedMessage: String,
        failureMessage: String,
        stopOnFailure: Boolean = true,
    ): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Timber.tag(TAG).w(e, deniedMessage)
            if (stopOnFailure) {
                stopSelf()
            }
            false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, failureMessage)
            reportException(e)
            if (stopOnFailure) {
                stopSelf()
            }
            false
        }

    override fun onDestroy() {
        isRunning = false

        if (!::player.isInitialized) {
            try {
                scope.cancel()
            } catch (_: Exception) {
            }
            super.onDestroy()
            shutdownDeferred.complete(Unit)
            return
        }

        val currentMetadata = player.currentMediaItem?.metadata
        if (currentMetadata?.isEpisode == true && player.currentPosition > 0) {
            runBlocking(Dispatchers.IO) {
                database.updatePlaybackPosition(currentMetadata.id, player.currentPosition)
            }
        }

        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
        }
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        castConnectionHandler?.release()
        if (dataStore.get(PersistentQueueKey, true)) {
            saveQueueToDisk()
        }
        screenOffHandler.removeCallbacks(screenOffTimeout)
        screenOffHandler.removeCallbacks(pauseTimeout)
        if (DiscordRpcManager.isReady()) {
            Timber.tag("DiscordSvc").i("onDestroy: disconnecting Discord RPC")
            DiscordRpcManager.disconnect()
        }
        DiscordRpcManager.destroy()
        connectivityObserver.unregister()
        abandonAudioFocus()
        closeAudioEffectSession()
        mediaLibrarySessionCallback.release()
        mediaSession?.release()
        player.removeListener(this)
        sleepTimer?.let { player.removeListener(it) }
        playerNormalizationProcessors.remove(player)
        playerSilenceProcessors.remove(player)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        player.release()
        scope.cancel()
        super.onDestroy()
        shutdownDeferred.complete(Unit)
    }

    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (dataStore.get(StopMusicOnTaskClearKey, false)) {
            if (!::player.isInitialized) {
                stopSelf()
                return
            }
            // Remote playback (Cast) is independent of the local ExoPlayer; ending the session
            // is required or audio keeps playing on the Cast device.
            runCatching {
                if (castConnectionHandler?.isCasting?.value == true) {
                    castConnectionHandler?.disconnect()
                }
                player.stop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                controllerFuture?.let { MediaController.releaseFuture(it) }
                controllerFuture = null
                // Media3: coordinates notification/foreground teardown and stopSelf; required when
                // playback was ongoing (default super.onTaskRemoved keeps the service alive).
                pauseAllPlayersAndStopSelf()
            }.onFailure { e ->
                Timber.tag(TAG).e(e, "Failed to stop playback on task clear")
                controllerFuture?.let { MediaController.releaseFuture(it) }
                controllerFuture = null
                runCatching { pauseAllPlayersAndStopSelf() }.onFailure { stopSelf() }
            }
            return
        }
        super.onTaskRemoved(rootIntent)
        // User removed the task while paused: drop foreground promotion so the process can idle.
        // Queue/state remain persisted; opening the app restores playback as usual.
        if (::player.isInitialized && !player.isPlaying) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        try {
            super.onUpdateNotification(session, startInForegroundRequired)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            handleForegroundServiceStartNotAllowed(e)
        } catch (e: IllegalStateException) {
            if (isForegroundServiceStartNotAllowedException(e)) {
                handleForegroundServiceStartNotAllowed(e)
            } else {
                throw e
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // On Android O+, every startForegroundService() call requires
        // Service.startForeground() to be called within a short timeout.
        // Some OEMs (e.g. MIUI) strictly enforce this even when the
        // service is already in the foreground, so always promote here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!ensureForegroundWithLatestNotificationOrStop()) {
                return START_NOT_STICKY
            }
        }

        when (intent?.action) {
            ACTION_ALARM_TRIGGER -> {
                handleAlarmTrigger(intent)
            }

            MusicWidgetReceiver.ACTION_PLAY_PAUSE -> {
                if (player.isPlaying) player.pause() else player.play()
                updateWidgetUI(player.isPlaying)
            }

            MusicWidgetReceiver.ACTION_LIKE -> {
                toggleLike()
            }

            MusicWidgetReceiver.ACTION_NEXT -> {
                player.seekToNext()
                updateWidgetUI(player.isPlaying)
            }

            MusicWidgetReceiver.ACTION_PREVIOUS -> {
                player.seekToPrevious()
                updateWidgetUI(player.isPlaying)
            }

            MusicWidgetReceiver.ACTION_UPDATE_WIDGET,
            PlaylistWidgetReceiver.ACTION_UPDATE_WIDGET -> {
                updateWidgetUI(player.isPlaying)
            }

            PlaylistWidgetReceiver.ACTION_PLAY_TARGET -> {
                handlePlaylistWidgetPlay(intent)
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }


    private fun handlePlaylistWidgetPlay(intent: Intent) {
        scope.launch {
            try {
                val queue = withContext(Dispatchers.IO) {
                    buildPlaylistWidgetQueue(intent)
                }
                if (queue == null) {
                    openPlaylistWidgetTarget(intent)
                    return@launch
                }
                playQueue(queue, playWhenReady = true)
                updateWidgetUI(true)
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to start playlist widget target")
                openPlaylistWidgetTarget(intent)
            }
        }
    }

    private fun openPlaylistWidgetTarget(source: Intent) {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_WIDGET_TARGET
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(
                MainActivity.EXTRA_WIDGET_TARGET_TYPE,
                source.getStringExtra(PlaylistWidgetReceiver.EXTRA_TARGET_TYPE),
            )
            putExtra(
                MainActivity.EXTRA_WIDGET_TARGET_ID,
                source.getStringExtra(PlaylistWidgetReceiver.EXTRA_TARGET_ID),
            )
        }
        try {
            startActivity(activityIntent)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to open playlist widget target")
        }
    }

    private suspend fun buildPlaylistWidgetQueue(intent: Intent): Queue? {
        val targetType = intent.getStringExtra(PlaylistWidgetReceiver.EXTRA_TARGET_TYPE) ?: return null
        val targetId = intent.getStringExtra(PlaylistWidgetReceiver.EXTRA_TARGET_ID).orEmpty()
        val targetTitle = intent.getStringExtra(PlaylistWidgetReceiver.EXTRA_TARGET_TITLE)

        return when (targetType) {
            PlaylistWidgetReceiver.TARGET_TYPE_LOCAL -> {
                if (targetId.isBlank()) return null
                val songs = database.playlistSongs(targetId).first()
                if (songs.isEmpty()) return null
                val playlistName = database.playlist(targetId).first()?.playlist?.name ?: targetTitle
                ListQueue(
                    title = playlistName,
                    items = songs.map { it.song.toMediaItem() },
                )
            }

            PlaylistWidgetReceiver.TARGET_TYPE_ONLINE -> {
                if (targetId.isBlank()) return null
                val cachedPlaylist = database.playlistByBrowseId(targetId).first()
                val cachedSongs = cachedPlaylist?.let { database.playlistSongs(it.playlist.id).first() }.orEmpty()
                if (cachedSongs.isNotEmpty()) {
                    ListQueue(
                        title = cachedPlaylist?.playlist?.name ?: targetTitle,
                        items = cachedSongs.map { it.song.toMediaItem() },
                    )
                } else {
                    YouTubePlaylistQueue(
                        playlistId = targetId,
                        playlistTitle = targetTitle,
                    )
                }
            }

            PlaylistWidgetReceiver.TARGET_TYPE_LIKED -> {
                val songs = database.likedSongsByCreateDateAsc().first()
                if (songs.isEmpty()) return null
                ListQueue(
                    title = getString(R.string.liked_songs),
                    items = songs.map { it.toMediaItem() },
                )
            }

            PlaylistWidgetReceiver.TARGET_TYPE_DOWNLOADED -> {
                val songs = database.downloadedSongsByCreateDateAsc().first()
                if (songs.isEmpty()) return null
                ListQueue(
                    title = getString(R.string.downloaded_songs),
                    items = songs.map { it.toMediaItem() },
                )
            }

            PlaylistWidgetReceiver.TARGET_TYPE_TOP -> {
                val limit = targetId.toIntOrNull() ?: 50
                val songs = database.mostPlayedSongs(LocalDateTime.of(1970, 1, 1, 0, 0), limit = limit).first()
                if (songs.isEmpty()) return null
                ListQueue(
                    title = getString(R.string.my_top),
                    items = songs.map { it.toMediaItem() },
                )
            }

            else -> null
        }
    }

    private fun handleAlarmTrigger(intent: Intent) {
        scope.launch(Dispatchers.IO) {
            try {
                MusicAlarmScheduler.scheduleFromPreferences(this@MusicService)
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to reschedule alarms after trigger")
            }
        }
        val playlistId = intent.getStringExtra(EXTRA_ALARM_PLAYLIST_ID).orEmpty()
        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID).orEmpty()
        if (playlistId.isBlank()) {
            if (alarmId.isNotBlank()) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val alarms = MusicAlarmStore.load(this@MusicService)
                        val updated =
                            alarms.map { alarm ->
                                if (alarm.id == alarmId) alarm.copy(enabled = false, nextTriggerAt = -1L) else alarm
                            }
                        MusicAlarmScheduler.scheduleAll(this@MusicService, updated)
                    } catch (t: Throwable) {
                        Timber.tag(TAG).e(t, "Failed to disable alarm with invalid playlist")
                    }
                }
            }
            return
        }
        val randomSong = intent.getBooleanExtra(EXTRA_ALARM_RANDOM_SONG, false)
        scope.launch {
            try {
                val playlistSongs =
                    withContext(Dispatchers.IO) {
                        database.playlistSongs(playlistId).first()
                    }
                if (playlistSongs.isEmpty()) {
                    if (alarmId.isNotBlank()) {
                        withContext(Dispatchers.IO) {
                            val alarms = MusicAlarmStore.load(this@MusicService)
                            val updated =
                                alarms.map { alarm ->
                                    if (alarm.id == alarmId) alarm.copy(enabled = false, nextTriggerAt = -1L) else alarm
                                }
                            MusicAlarmScheduler.scheduleAll(this@MusicService, updated)
                        }
                    }
                    return@launch
                }
                val items = playlistSongs.map { it.song.toMediaItem() }
                val playlistName =
                    withContext(Dispatchers.IO) {
                        database
                            .playlist(playlistId)
                            .first()
                            ?.playlist
                            ?.name
                    }
                withContext(Dispatchers.IO) {
                    MusicAlarmScheduler.scheduleFromPreferences(this@MusicService)
                }

                val alarmItems =
                    if (randomSong) {
                        val firstIndex = Random.nextInt(items.size)
                        buildList(items.size) {
                            add(items[firstIndex])
                            items.forEachIndexed { index, item ->
                                if (index != firstIndex) add(item)
                            }
                        }
                    } else {
                        items
                    }

                player.stop()
                player.clearMediaItems()
                playQueue(
                    ListQueue(
                        title = playlistName,
                        items = alarmItems,
                        startIndex = 0,
                        position = 0L,
                    ),
                    playWhenReady = true,
                )
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to start alarm playback")
            }
        }
    }

    private fun handleForegroundServiceStartNotAllowed(error: Throwable?) {
        if (error != null) {
            Timber.tag(TAG).w(error, "Foreground service start denied during notification update")
        } else {
            Timber.tag(TAG).w("Foreground service start denied by MediaSessionService listener")
        }

        if (tryEnsureForegroundWithLatestNotification()) {
            return
        }

        if (!::player.isInitialized) {
            stopSelf()
            return
        }

        if (player.isPlaying) {
            Timber.tag(TAG).w("Keeping playback alive after denied foreground restart request")
            return
        }

        runCatching {
            pauseAllPlayersAndStopSelf()
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed to stop service after foreground start denial")
            stopSelf()
        }
    }

    private fun isForegroundServiceStartNotAllowedException(error: IllegalStateException): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            error.javaClass.name == ForegroundServiceStartNotAllowedException::class.java.name

    /**
     * Updates all app widgets with current playback state
     */
    private var widgetUpdateInFlight = false
    private var pendingWidgetUpdate: Pair<Boolean, Boolean?>? = null

    private fun updateWidgetUI(
        isPlaying: Boolean,
        isLiked: Boolean? = currentSong.value?.song?.let { if (it.isEpisode) it.inLibrary != null else it.liked }
    ) {
        pendingWidgetUpdate = isPlaying to isLiked
        if (widgetUpdateInFlight) return
        widgetUpdateInFlight = true

        scope.launch {
            try {
                while (true) {
                    val (playing, isLikedRequested) = pendingWidgetUpdate ?: break
                    pendingWidgetUpdate = null

                    val songData = currentSong.value
                    val song = songData?.song
                    val songTitle = song?.title ?: getString(R.string.no_song_playing)
                    val artistName = songData?.artists?.joinToArtistString(getArtistSeparator(this@MusicService)) { it.name } ?: getString(R.string.tap_to_open)
                    val resolvedIsLiked = isLikedRequested == true

                    widgetManager.updateWidgets(
                        title = songTitle,
                        artist = artistName,
                        artworkUri = song?.thumbnailUrl,
                        isPlaying = playing,
                        isLiked = resolvedIsLiked,
                        duration = if (player.duration != C.TIME_UNSET) player.duration else 0,
                        currentPosition = player.currentPosition,
                    )
                }
            } catch (e: Exception) {
            } finally {
                widgetUpdateInFlight = false
            }
        }
    }

    private var widgetUpdateJob: Job? = null

    private fun startWidgetUpdates() {
        widgetUpdateJob?.cancel()
        widgetUpdateJob =
            scope.launch {
                while (isActive) {
                    if (player.isPlaying) {
                        updateWidgetUI(true)
                    }
                    delay(200)
                }
            }
    }

    private fun stopWidgetUpdates() {
        widgetUpdateJob?.cancel()
        widgetUpdateJob = null
    }

    private fun shareSong() {
        val songData = currentSong.value
        val songId = songData?.song?.id ?: return

        val shareIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=$songId")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        startActivity(
            Intent.createChooser(shareIntent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /**
     * Get the stream URL for a given media ID.
     * This is used for Google Cast to send the audio URL to Chromecast.
     */
    suspend fun getStreamUrl(mediaId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val song = database.songEntity(mediaId)
                val playbackData =
                    YTPlayerUtils
                        .playerResponseForPlayback(
                            videoId = mediaId,
                            audioQuality = audioQuality,
                            connectivityManager = connectivityManager,
                            contentHints = ContentHints(
                                isExplicit = song?.explicit,
                                isUploaded = song?.isUploaded,
                            ),
                        ).getOrNull()
                playbackData?.streamUrl
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to get stream URL for Cast")
                null
            }
        }

    /**
     * Initialize Google Cast support
     */
    private fun initializeCast() {
        if (dataStore.get(com.metrolist.music.constants.EnableGoogleCastKey, true)) {
            try {
                castConnectionHandler = CastConnectionHandler(this, scope, this)
                castConnectionHandler?.initialize()
                timber.log.Timber.d("Google Cast initialized")
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to initialize Google Cast")
            }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            scheduleCrossfade()
        }
    }

    private fun scheduleCrossfade() {
        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        if (!crossfadeEnabled || crossfadeDuration <= 0f || player.duration == C.TIME_UNSET || player.duration <= crossfadeDuration) return
        if (crossfadeGapless && isNextItemGapless()) return
        if (!player.hasNextMediaItem() && player.repeatMode != REPEAT_MODE_ONE) return

        val triggerTime = player.duration - crossfadeDuration.toLong()
        val delayMs = triggerTime - player.currentPosition
        if (delayMs <= 0) return

        val targetMediaId = player.currentMediaItem?.mediaId

        crossfadeTriggerJob =
            scope.launch {
                delay(delayMs)
                val timer = sleepTimer
                if (isActive && player.isPlaying && player.currentMediaItem?.mediaId == targetMediaId && (timer == null || !timer.pauseWhenSongEnd)) {
                    startCrossfade()
                }
            }
    }

    private fun isNextItemGapless(): Boolean {
        val current = player.currentMediaItem?.mediaMetadata ?: return false
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return false
        val next = player.getMediaItemAt(nextIndex).mediaMetadata
        return current.albumTitle != null && current.albumTitle == next.albumTitle
    }

    private fun startCrossfade() {
        if (isCrossfading) return

        playerNormalizationProcessors.values.forEach { it.enabled = false }

        // Preserve player state before creating the secondary player
        // Use runBlocking to ensure we get the correct state from DataStore
        val savedRepeatMode = runBlocking { dataStore.get(RepeatModeKey, REPEAT_MODE_OFF) }
        val savedShuffleEnabled = runBlocking { dataStore.get(ShuffleModeKey, false) }

        // For repeat-one, crossfade back into the same track
        val targetIndex =
            if (savedRepeatMode == REPEAT_MODE_ONE) {
                player.currentMediaItemIndex
            } else {
                player.nextMediaItemIndex
            }
        if (targetIndex == C.INDEX_UNSET) return

        secondaryPlayer = createExoPlayer()
        val secPlayer = secondaryPlayer!!
        secPlayer.addListener(secondaryPlayerListener)

        val itemCount = player.mediaItemCount
        val items = mutableListOf<MediaItem>()
        for (i in 0 until itemCount) {
            items.add(player.getMediaItemAt(i))
        }

        secPlayer.setMediaItems(items)
        secPlayer.seekTo(targetIndex, 0)
        secPlayer.volume = 0f

        secPlayer.repeatMode = savedRepeatMode
        secPlayer.shuffleModeEnabled = savedShuffleEnabled

        try {
            secPlayer.prepare()
            secPlayer.playWhenReady = true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to prepare secondary player for crossfade")
            playerNormalizationProcessors.remove(secPlayer)
            secPlayer.release()
            secondaryPlayer = null
            return
        }

        performCrossfadeSwap()

        if (savedShuffleEnabled) {
            val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
            applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
        }
    }

    private fun performCrossfadeSwap() {
        isCrossfading = true
        val nextPlayer = secondaryPlayer ?: return
        val currentPlayer = player

        fadingPlayer = currentPlayer
        player = nextPlayer
        _playerFlow.value = player
        secondaryPlayer = null

        fadingPlayer?.removeListener(this)
        sleepTimer?.let { timer -> fadingPlayer?.removeListener(timer) }

        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isCrossfading && fadingPlayer != null) {
                        if (isPlaying) {
                            fadingPlayer?.play()
                        } else {
                            fadingPlayer?.pause()
                        }
                    } else {
                        player.removeListener(this)
                    }
                }
            },
        )

        nextPlayer.removeListener(secondaryPlayerListener)
        nextPlayer.addListener(this)
        sleepTimer?.let { nextPlayer.addListener(it) }

        sleepTimer?.player = player

        try {
            mediaSession?.let { (it as MediaSession).player = player }
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Failed to swap player in MediaSession")
        }

        // secondaryPlayer was playing this item silently in the background without
        // `this` attached as a listener, so its real transition into this item never
        // reached onMediaItemTransition. Re-fire it manually now that the swap is done
        // so metadata recovery, cache marking, scrobbling, and normalization all run.
        onMediaItemTransition(player.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        val previousAudioSessionId = fadingPlayer?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET

        openAudioEffectSession()

        crossfadeJob =
            scope.launch {
                val duration = crossfadeDuration.toLong()
                val steps = 20
                val stepTime = duration / steps
                val startVolume =
                    try {
                        fadingPlayer?.volume ?: 1f
                    } catch (e: Exception) {
                        1f
                    }

                for (i in 0..steps) {
                    if (!isActive) break
                    while (!player.isPlaying && isActive) {
                        delay(100)
                    }

                    val progress = i / steps.toFloat()
                    val fadeIn = 1.0f - (1.0f - progress) * (1.0f - progress)
                    val fadeOut = (1.0f - progress) * (1.0f - progress)

                    try {
                        player.volume = startVolume * fadeIn
                        fadingPlayer?.volume = startVolume * fadeOut
                    } catch (e: Exception) {
                        break
                    }

                    delay(stepTime)
                }

                try {
                    fadingPlayer?.volume = 0f
                    player.volume = startVolume
                } catch (e: Exception) {
                }

                cleanupCrossfade(fadingPlayerSessionId = previousAudioSessionId)
            }
    }

    private fun cleanupCrossfade(fadingPlayerSessionId: Int = C.AUDIO_SESSION_ID_UNSET) {
        fadingPlayer?.let { playerNormalizationProcessors.remove(it) }
        fadingPlayer?.stop()
        fadingPlayer?.clearMediaItems()
        fadingPlayer?.release()
        fadingPlayer = null
        isCrossfading = false
        applyEffectiveVolume()
        sleepTimer?.notifySongTransition()

        applyCachedAudioNormalizationNow()

        if (fadingPlayerSessionId != C.AUDIO_SESSION_ID_UNSET && fadingPlayerSessionId > 0) {
            closeAudioEffectSession(sessionIdOverride = fadingPlayerSessionId, clearNormalizationCache = true)
        }
    }

    companion object {
        const val ACTION_ALARM_TRIGGER = "com.metrolist.music.action.ALARM_TRIGGER"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_ALARM_PLAYLIST_ID = "extra_alarm_playlist_id"
        const val EXTRA_ALARM_RANDOM_SONG = "extra_alarm_random_song"

        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val YOUTUBE_PLAYLIST = "youtube_playlist"
        const val SEARCH = "search"
        const val SHUFFLE_ACTION = "__shuffle__"

        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001
        const val CHUNK_LENGTH = 512 * 1024L
        const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
        const val PERSISTENT_AUTOMIX_FILE = "persistent_automix.data"
        const val PERSISTENT_PLAYER_STATE_FILE = "persistent_player_state.data"
        const val MAX_CONSECUTIVE_ERR = 5
        const val MAX_RETRY_COUNT = 10

        private const val MAX_GAIN_MB = 300 // Maximum gain in millibels (3 dB)
        private const val MIN_GAIN_MB = -1500 // Minimum gain in millibels (-15 dB)

        private const val TAG = "MusicService"

        @Volatile
        var isRunning = false
            private set
            
        @Volatile
        var shutdownDeferred = kotlinx.coroutines.CompletableDeferred<Unit>().apply { complete(Unit) }
    }
}
