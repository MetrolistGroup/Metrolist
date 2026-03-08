package com.metrolist.android.listentogether

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.metrolist.android.R
import com.metrolist.android.deviceconnect.AndroidDeviceConnect
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.android.playback.roomNeedsPlaybackService
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.unknown_artist
import com.metrolist.shared.listentogether.ListenTogetherLogLevel
import com.metrolist.shared.listentogether.ListenTogetherLogger
import com.metrolist.shared.listentogether.ListenTogetherManager
import com.metrolist.shared.listentogether.ListenTogetherPlayback
import com.metrolist.shared.listentogether.ListenTogetherPresentationText
import com.metrolist.shared.listentogether.ListenTogetherRoomRole
import com.metrolist.shared.listentogether.ListenTogetherText
import com.metrolist.shared.listentogether.PlaybackReadiness
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds

object AndroidListenTogetherManager {
    private const val TAG = "AndroidListenTogether"
    private const val MAX_FRAME_SIZE_BYTES = 256L * 1024
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playback = AndroidListenTogetherPlayback(scope)

    @Volatile
    private var sharedManager: ListenTogetherManager? = null

    private val _isGuestPlaybackLocked = MutableStateFlow(false)
    val isGuestPlaybackLockedFlow: StateFlow<Boolean> = _isGuestPlaybackLocked.asStateFlow()

    val isGuestPlaybackLocked: Boolean
        get() = _isGuestPlaybackLocked.value

    val currentManager: ListenTogetherManager? get() = sharedManager

    private val _playbackRequired = MutableStateFlow(false)
    val playbackRequired: StateFlow<Boolean> = _playbackRequired.asStateFlow()

    private var managerObserverJob: Job? = null
    private var notifications: ListenTogetherNotifications? = null

    fun setScreenVisible(visible: Boolean) {
        notifications?.screenVisible?.value = visible
    }

    fun respondToNotification(intent: Intent) {
        notifications?.respond(intent)
    }

    private fun bindManager(manager: ListenTogetherManager) {
        managerObserverJob?.cancel()
        _isGuestPlaybackLocked.value = manager.isGuestPlaybackLocked
        managerObserverJob =
            scope.launch {
                combine(
                    manager.role,
                    manager.isInRoom,
                    manager.connectionState,
                    manager.roomRequestPending,
                ) { role, inRoom, connection, pending ->
                    (role == ListenTogetherRoomRole.GUEST && inRoom) to roomNeedsPlaybackService(inRoom, pending, connection)
                }.collect { (locked, required) ->
                    _isGuestPlaybackLocked.value = locked
                    _playbackRequired.value = required
                }
            }
    }

    fun suggestTrack(song: SongItem) {
        sharedManager?.suggestTrack(song)
    }

    suspend fun leaveRoomAndWait(): Boolean {
        sharedManager?.leaveRoomAndWait() ?: return false
        return withTimeoutOrNull(20_000L.milliseconds) {
            isGuestPlaybackLockedFlow.first { !it }
            true
        } == true
    }

    fun initialize(
        context: Context,
        playerConnection: PlayerConnection,
    ): ListenTogetherManager {
        val applicationContext = context.applicationContext
        playerConnection.ensureConnected()
        val replacedPlayer = playback.replace(playerConnection)
        val manager =
            sharedManager ?: synchronized(this) {
                sharedManager ?: createManager(applicationContext).also {
                    sharedManager = it
                    bindManager(it)
                    notifications = ListenTogetherNotifications(applicationContext, it, scope)
                }
            }
        AndroidDeviceConnect.manager.remote.transferBlocked = { manager.hasActiveOrPendingRoom }
        if (
            replacedPlayer &&
            manager.role.value == ListenTogetherRoomRole.GUEST &&
            manager.isInRoom.value
        ) {
            scope.launch {
                playerConnection.ensureConnected()
                val connected =
                    withTimeoutOrNull(5_000L.milliseconds) {
                        while (!playerConnection.isConnected.value && playback.currentPlayer === playerConnection) {
                            delay(
                                50L.milliseconds,
                            )
                        }
                        true
                    } == true
                if (connected && playback.currentPlayer === playerConnection) manager.requestSync()
            }
        }
        return manager
    }

    private fun createManager(context: Context): ListenTogetherManager {
        if (Settings.shared.listenTogetherUsername.isBlank()) {
            Settings.shared.listenTogetherUsername =
                Settings.shared.accountName.ifBlank { Settings.shared.accountChannelHandle }.ifBlank {
                    Settings.shared.accountEmail.substringBefore('@').ifBlank {
                        context.getString(R.string.listen_together_android_user)
                    }
                }
        }
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
        return ListenTogetherManager(
            callerScope = scope,
            httpClient = createHttpClient(),
            playback = playback,
            resolver =
                androidListenTogetherTrackResolver(
                    metadata = { GlobalYouTubeRepository.instance.getSongMetadata(it) },
                    fallbackArtist = { getString(Res.string.unknown_artist) },
                ),
            monotonicClockMs = SystemClock::elapsedRealtime,
            wallClockMs = System::currentTimeMillis,
            timestampFormatter = { formatter.format(Instant.ofEpochMilli(it)) },
            presentationText = ListenTogetherPresentationText { context.listenTogetherText(it) },
            logger =
                ListenTogetherLogger { level, message ->
                    when (level) {
                        ListenTogetherLogLevel.ERROR -> {
                            Timber.tag(TAG).e(message)
                        }

                        ListenTogetherLogLevel.WARNING -> {
                            Timber.tag(TAG).w(message)
                        }

                        ListenTogetherLogLevel.INFO -> {
                            Timber.tag(TAG).i(message)
                        }

                        ListenTogetherLogLevel.DEBUG -> {
                            Timber.tag(TAG).d(message)
                        }
                    }
                },
        )
    }

    private fun createHttpClient() =
        HttpClient(CIO) {
            install(HttpFailureLog)
            install(WebSockets) {
                maxFrameSize = MAX_FRAME_SIZE_BYTES
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 30_000L
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = 60_000L
            }
        }
}

private class AndroidListenTogetherPlayback(
    private val scope: CoroutineScope,
) : ListenTogetherPlayback {
    private val collectionJobs = mutableListOf<Job>()

    var currentPlayer: PlayerConnection? = null
        private set

    override val connected = MutableStateFlow(false)
    override val currentSong = MutableStateFlow<SongItem?>(null)
    override val playing = MutableStateFlow(false)
    override val positionMs = MutableStateFlow(0L)
    override val durationMs = MutableStateFlow(0L)
    override val volume = MutableStateFlow(1f)
    override val queue = MutableStateFlow<List<SongItem>>(emptyList())
    override val readiness = MutableStateFlow(PlaybackReadiness.IDLE)

    fun replace(player: PlayerConnection): Boolean {
        if (currentPlayer === player) return false
        val replaced = currentPlayer != null
        currentPlayer = player
        collectionJobs.forEach(Job::cancel)
        collectionJobs.clear()
        connected.value = player.isConnected.value
        currentSong.value = player.currentSong.value
        playing.value = player.isPlaying.value
        positionMs.value = player.currentPosition.value
        durationMs.value = player.duration.value
        volume.value = player.volume.value
        queue.value = player.queueSongs.value
        updateReadiness(player)
        collectionJobs += scope.launch { player.isConnected.collect { connected.value = it } }
        collectionJobs +=
            scope.launch {
                player.currentSong.collect {
                    currentSong.value = it
                    updateReadiness(player)
                }
            }
        collectionJobs += scope.launch { player.isPlaying.collect { playing.value = it } }
        collectionJobs += scope.launch { player.duration.collect { durationMs.value = it } }
        collectionJobs += scope.launch { player.volume.collect { volume.value = it } }
        collectionJobs +=
            scope.launch {
                player.playbackState.collect { updateReadiness(player) }
            }
        collectionJobs +=
            scope.launch {
                player.loadError.collect { updateReadiness(player) }
            }
        collectionJobs += scope.launch { player.queueSongs.collect { queue.value = it } }
        return replaced
    }

    private fun updateReadiness(player: PlayerConnection) {
        readiness.value =
            when {
                player.loadError.value != null -> PlaybackReadiness.FAILED
                else -> player.playbackState.value.toReadiness()
            }
    }

    override fun currentPositionMs(): Long = currentPlayer?.currentPositionMs() ?: positionMs.value

    override fun currentQueueTitle(): String? = currentPlayer?.queueTitle?.value

    override fun currentQueueIndex(): Int? = currentPlayer?.currentQueueIndex()

    override fun currentQueueSnapshot(): MusicQueueSnapshot = currentPlayer?.currentQueueSnapshot() ?: super.currentQueueSnapshot()

    override fun playbackSpeed(): Float = currentPlayer?.playbackSpeed() ?: 1f

    override fun playbackInstanceId(): Long? = currentPlayer?.playbackInstanceId()

    override suspend fun playQueueFromRemote(
        songs: List<SongItem>,
        startIndex: Int,
        title: String?,
    ) {
        val player = currentPlayer ?: return
        readiness.value = PlaybackReadiness.BUFFERING
        val loaded = player.playQueueFromRemote(songs, startIndex, title)
        if (!loaded) {
            readiness.value = PlaybackReadiness.FAILED
        } else {
            if (!player.castState.value.isConnected) readiness.value = PlaybackReadiness.READY
            AndroidDeviceConnect.manager.remote.localStateChanged(trackChanged = true)
        }
    }

    override fun setQueueTitleFromRemote(title: String?) {
        currentPlayer?.setQueueTitleFromRemote(title)
    }

    override suspend fun replaceUpcomingQueueFromRemote(songs: List<SongItem>) {
        currentPlayer?.replaceUpcomingQueueFromRemote(songs)
        AndroidDeviceConnect.manager.remote.localStateChanged(trackChanged = false)
    }

    override suspend fun addToQueueFromRemote(song: SongItem) {
        currentPlayer?.addToQueueFromRemote(song)
        AndroidDeviceConnect.manager.remote.localStateChanged(trackChanged = false)
    }

    override suspend fun skipToNextFromRemote() {
        currentPlayer?.skipToNextFromRemote()
        AndroidDeviceConnect.manager.remote.localStateChanged(trackChanged = true)
    }

    override suspend fun skipToPreviousFromRemote() {
        currentPlayer?.skipToPreviousFromRemote()
        AndroidDeviceConnect.manager.remote.localStateChanged(trackChanged = true)
    }

    override suspend fun setPlayingFromRemote(playing: Boolean) {
        currentPlayer?.setPlayingFromRemote(playing)
        AndroidDeviceConnect.manager.remote.localStateChanged(trackChanged = false)
    }

    override suspend fun seekToFromRemote(positionMs: Long) {
        currentPlayer?.seekToFromRemote(positionMs)
        AndroidDeviceConnect.manager.remote.localStateChanged(trackChanged = false)
    }

    override suspend fun setPlaybackSpeedFromRemote(speed: Float) {
        currentPlayer?.setPlaybackSpeedFromRemote(speed)
        AndroidDeviceConnect.manager.remote.localStateChanged(trackChanged = false)
    }

    override suspend fun setVolumeFromRemote(volume: Float) {
        currentPlayer?.setVolumeFromRemote(volume)
    }
}

private fun PlayerConnection.PlaybackState.toReadiness(): PlaybackReadiness =
    when (this) {
        PlayerConnection.PlaybackState.IDLE,
        PlayerConnection.PlaybackState.ENDED,
        -> PlaybackReadiness.IDLE

        PlayerConnection.PlaybackState.BUFFERING -> PlaybackReadiness.BUFFERING

        PlayerConnection.PlaybackState.READY -> PlaybackReadiness.READY
    }

internal fun Context.listenTogetherText(key: ListenTogetherText): String =
    getString(
        when (key) {
            ListenTogetherText.CONNECTION_FAILED -> R.string.scrobble_connection_failed
            ListenTogetherText.ROOM_CREATED -> R.string.listen_together_log_room_created
            ListenTogetherText.JOINED_ROOM -> R.string.listen_together_log_joined_room
            ListenTogetherText.JOIN_REJECTED -> R.string.listen_together_log_join_rejected
            ListenTogetherText.KICKED -> R.string.listen_together_log_kicked_from_room
            ListenTogetherText.SERVER_ERROR -> R.string.listen_together_log_server_error
            ListenTogetherText.BLOCKED_BY_HOST -> R.string.listen_together_blocked_by_host
            ListenTogetherText.BUFFER_WAIT -> R.string.listen_together_log_waiting_for_buffers
            ListenTogetherText.HOST_CHANGED -> R.string.listen_together_log_host_changed
            ListenTogetherText.USER_JOINED -> R.string.listen_together_log_user_joined
            ListenTogetherText.USER_LEFT -> R.string.listen_together_log_user_left
            ListenTogetherText.RECONNECTED -> R.string.listen_together_log_reconnected_to_room
            ListenTogetherText.SUGGESTION_SUBMITTED -> R.string.listen_together_suggestion_submitted
            ListenTogetherText.SUGGESTION_APPROVED -> R.string.listen_together_suggestion_approved
            ListenTogetherText.SUGGESTION_REJECTED -> R.string.listen_together_suggestion_rejected
            ListenTogetherText.QUEUE_TOO_LARGE -> R.string.listen_together_queue_too_large
            ListenTogetherText.TRACK_LOAD_FAILED -> R.string.listen_together_track_load_failed
            ListenTogetherText.BECAME_HOST -> R.string.listen_together_became_host
            ListenTogetherText.SUGGESTION_RECEIVED -> R.string.listen_together_log_suggestion_received
        },
    )
