package com.metrolist.android.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.Player
import com.metrolist.android.R
import com.metrolist.android.deviceconnect.AndroidDeviceConnect
import com.metrolist.android.deviceconnect.toDeviceConnectRepeatMode
import com.metrolist.android.listentogether.AndroidListenTogetherManager
import com.metrolist.android.models.decodePersistPlayerState
import com.metrolist.shared.api.PlaylistPage
import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.deviceconnect.DcPlaybackAction
import com.metrolist.shared.deviceconnect.DcPlaybackCommand
import com.metrolist.shared.deviceconnect.DcSyncError
import com.metrolist.shared.deviceconnect.DeviceConnectManager
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.error_unknown
import com.metrolist.shared.generated.resources.playlist_unavailable
import com.metrolist.shared.model.RadioQueueChip
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isLive
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.playback.MusicQueueState
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.playback.PlayerSession
import com.metrolist.shared.playback.QUEUE_SOURCE_ADDED_MANUALLY
import com.metrolist.shared.playback.QueueRemoval
import com.metrolist.shared.playback.SleepTimerState
import com.metrolist.shared.playback.isRecommendationGeneratedQueueSource
import com.metrolist.shared.playback.isRecommendedQueueSource
import com.metrolist.shared.playback.shouldPlayAfterSongChange
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.MusicVideoQuality
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.utils.normalizePlaybackTempo
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.getString
import timber.log.Timber

val LocalPlayerConnection = staticCompositionLocalOf<PlayerConnection?> { null }

data class ListenTogetherPlaybackRequest(
    val song: SongItem,
    val queue: List<SongItem> = emptyList(),
    val startIndex: Int = 0,
    val queueTitle: String? = null,
    val shuffleModeEnabled: Boolean? = null,
)

internal fun canSkipToNext(
    isServiceConnected: Boolean,
    isDeviceConnectActive: Boolean,
    queue: MusicQueueSnapshot,
    repeatMode: Int,
    isPlaybackLocked: Boolean,
    hasPlaylistContinuation: Boolean = false,
): Boolean =
    (isServiceConnected || isDeviceConnectActive) && !isPlaybackLocked &&
        queue.currentIndex in queue.displayedItems.indices &&
        (queue.currentIndex < queue.displayedItems.lastIndex || repeatMode == Player.REPEAT_MODE_ALL || hasPlaylistContinuation)

internal fun canSkipToPrevious(
    isServiceConnected: Boolean,
    isDeviceConnectActive: Boolean,
    queue: MusicQueueSnapshot,
    repeatMode: Int,
    isPlaybackLocked: Boolean,
    isLive: Boolean,
): Boolean {
    if ((!isServiceConnected && !isDeviceConnectActive) || isPlaybackLocked) return false
    if (isLive) {
        return queue.currentIndex in queue.displayedItems.indices &&
            (queue.currentIndex > 0 || (repeatMode == Player.REPEAT_MODE_ALL && queue.displayedItems.size > 1))
    }
    return true
}

internal fun preparePlayerDismissal(
    currentRequestId: () -> Long?,
    stop: () -> Unit,
): () -> Unit {
    val expectedRequestId = currentRequestId()
    return {
        if (expectedRequestId != null && expectedRequestId == currentRequestId()) stop()
    }
}

internal fun queueSelectionTargetIndex(
    index: Int,
    currentIndex: Int,
    isSuggested: Boolean,
): Int = if (isSuggested && index > currentIndex) currentIndex + 1 else index

internal fun queueEntryIndex(
    state: MusicQueueState,
    entryKey: Long,
): Int? = state.entries.indexOfFirst { it.key == entryKey }.takeIf { it >= 0 }

internal fun queueEntryIndices(
    state: MusicQueueState,
    entryKeys: Set<Long>,
): Set<Int>? {
    val indices =
        buildSet {
            state.entries.forEachIndexed { index, entry ->
                if (entry.key in entryKeys) add(index)
            }
        }
    return indices.takeIf { it.size == entryKeys.size }
}

internal data class QueueUndo(
    val removals: List<QueueRemoval>,
    val before: MusicQueueSnapshot,
    val after: MusicQueueSnapshot,
    val sessionId: String?,
    val queueRevision: Long?,
) {
    fun isCurrent(
        queue: MusicQueueSnapshot,
        sessionId: String?,
        queueRevision: Long?,
    ): Boolean =
        this.sessionId == sessionId && after.currentIndex == queue.currentIndex &&
            if (sessionId == null) after == queue else this.queueRevision == queueRevision

    fun originalRemovalIndices(): Set<Int> {
        val remaining = before.displayedItems.indices.toMutableList()
        return buildSet {
            removals.forEach { removal ->
                removal.items.sortedByDescending { it.index }.forEach { add(remaining.removeAt(it.index)) }
            }
        }
    }

    fun append(
        removal: QueueRemoval,
        before: MusicQueueSnapshot,
        after: MusicQueueSnapshot,
        sessionId: String?,
        currentRevision: Long?,
        resultingRevision: Long? = currentRevision,
    ): QueueUndo =
        if (isCurrent(before, sessionId, currentRevision)) {
            copy(removals = removals + removal, after = after, queueRevision = resultingRevision)
        } else {
            QueueUndo(listOf(removal), before, after, sessionId, resultingRevision)
        }
}

class PlayerConnection(
    private val context: Context,
    private val scope: CoroutineScope,
) : PlayerSession {
    internal fun playFromSearch(request: VoiceSearchRequest) {
        if (deferUntilConnected { playFromSearch(request) }) return
        cancelPlaylistRequest()
        musicService?.playFromSearch(request)
    }

    private var musicService: MusicService? = null

    private var queueUndo: QueueUndo? = null
    private var playlistRequestGeneration = 0L
    private var playlistRequestJob: Job? = null

    private fun cancelPlaylistRequest() {
        playlistRequestGeneration++
        playlistRequestJob?.cancel()
        playlistRequestJob = null
    }

    private val readinessListener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlaybackState(playbackState)
            }

            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                updatePlaybackState(player.playbackState)
            }
        }

    private fun updatePlaybackState(state: Int) {
        _playbackState.value =
            when (state) {
                Player.STATE_READY -> PlaybackState.READY
                Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                Player.STATE_ENDED -> PlaybackState.ENDED
                else -> PlaybackState.IDLE
            }
    }

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _currentSong = MutableStateFlow<SongItem?>(null)
    override val currentSong: StateFlow<SongItem?> = _currentSong.asStateFlow()
    private val listenTogetherPlaybackRequestsState =
        MutableSharedFlow<ListenTogetherPlaybackRequest>(extraBufferCapacity = 1)
    val listenTogetherPlaybackRequests: SharedFlow<ListenTogetherPlaybackRequest> = listenTogetherPlaybackRequestsState

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val wantsPlaybackMutableState = MutableStateFlow(false)
    override val wantsPlaybackState: StateFlow<Boolean> = wantsPlaybackMutableState.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _playbackSpeedFlow = MutableStateFlow(1f)
    override val playbackSpeedFlow: StateFlow<Float> = _playbackSpeedFlow.asStateFlow()
    private val _playbackPitchFlow = MutableStateFlow(1f)
    val playbackPitchFlow: StateFlow<Float> = _playbackPitchFlow.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    override val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _bufferedPosition = MutableStateFlow(0L)
    override val bufferedPosition: StateFlow<Long> = _bufferedPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _shuffleModeEnabled = MutableStateFlow(false)
    override val shuffleModeEnabled: StateFlow<Boolean> = _shuffleModeEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(0)
    override val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    override val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _bufferedProgress = MutableStateFlow(0f)
    override val bufferedProgress: StateFlow<Float> = _bufferedProgress.asStateFlow()

    private val _queueSongs = MutableStateFlow<List<SongItem>>(emptyList())
    override val queueSongs: StateFlow<List<SongItem>> = _queueSongs.asStateFlow()
    private val _queueSnapshot = MutableStateFlow(MusicQueueSnapshot())
    val queueSnapshot: StateFlow<MusicQueueSnapshot> = _queueSnapshot.asStateFlow()
    private val _queueState = MutableStateFlow(MusicQueueState())
    override val queueState: StateFlow<MusicQueueState> = _queueState.asStateFlow()

    private val _currentMediaItemIndex = MutableStateFlow(-1)
    override val currentMediaItemIndex: StateFlow<Int> = _currentMediaItemIndex.asStateFlow()

    private val _isQueueLoading = MutableStateFlow(false)
    override val isQueueLoading: StateFlow<Boolean> = _isQueueLoading.asStateFlow()

    private val _isReconnecting = MutableStateFlow(false)
    override val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()
    private val _isPlaybackLoading = MutableStateFlow(false)
    override val isPlaybackLoading: StateFlow<Boolean> = _isPlaybackLoading.asStateFlow()

    private val _queueTitle = MutableStateFlow<String?>(null)
    override val queueTitle: StateFlow<String?> = _queueTitle.asStateFlow()

    private val _queueChips = MutableStateFlow<List<RadioQueueChip>>(emptyList())
    override val queueChips: StateFlow<List<RadioQueueChip>> = _queueChips.asStateFlow()

    private val _isVideoModeActive = MutableStateFlow(false)
    override val isVideoModeActive: StateFlow<Boolean> = _isVideoModeActive.asStateFlow()

    private val _isVideoModeResolving = MutableStateFlow(false)
    override val isVideoModeResolving: StateFlow<Boolean> = _isVideoModeResolving.asStateFlow()

    private val _currentPlaybackResource = MutableStateFlow<PlaybackResource?>(null)
    override val currentPlaybackResource: StateFlow<PlaybackResource?> = _currentPlaybackResource.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val crossfadeStateValue = MutableStateFlow<AndroidCrossfadeState?>(null)
    internal val crossfadeState: StateFlow<AndroidCrossfadeState?> = crossfadeStateValue.asStateFlow()

    private val _isVideoViewerVisible = MutableStateFlow(Settings.shared.videoViewerVisible)
    override val isVideoViewerVisible: StateFlow<Boolean> = _isVideoViewerVisible.asStateFlow()

    private val _sleepTimerState = MutableStateFlow(SleepTimerState())
    override val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    private val _castDevices = MutableStateFlow<List<CastDevice>>(emptyList())
    override val castDevices: StateFlow<List<CastDevice>> = _castDevices.asStateFlow()

    private val _castState = MutableStateFlow(CastState())
    override val castState: StateFlow<CastState> = _castState.asStateFlow()

    val videoPlayer: Player?
        get() = musicService?.media3Player

    val audioSessionId: Int
        get() = musicService?.player?.getAudioSessionId() ?: 0

    val canSkipPrevious: StateFlow<Boolean> =
        combine(
            combine(
                _isConnected,
                AndroidDeviceConnect.manager.remote.state,
                _queueSnapshot,
            ) { isConnected, deviceConnectState, queue ->
                Triple(isConnected, deviceConnectState, queue)
            },
            _repeatMode,
            AndroidListenTogetherManager.isGuestPlaybackLockedFlow,
            _currentSong,
        ) { (isConnected, deviceConnectState, queue), repeatMode, isPlaybackLocked, song ->
            canSkipToPrevious(
                isConnected || queue.displayedItems.isNotEmpty(),
                deviceConnectState.sessionId != null,
                queue,
                repeatMode,
                isPlaybackLocked,
                song?.isLive == true || _currentPlaybackResource.value?.isLive == true,
            )
        }.stateIn(scope, SharingStarted.Eagerly, true)
    val canSkipNext: StateFlow<Boolean> =
        combine(
            _isConnected,
            AndroidDeviceConnect.manager.remote.state,
            _queueSnapshot,
            _repeatMode,
            AndroidListenTogetherManager.isGuestPlaybackLockedFlow,
        ) { isConnected, deviceConnectState, queue, repeatMode, isPlaybackLocked ->
            canSkipToNext(
                isConnected || queue.displayedItems.isNotEmpty(),
                deviceConnectState.sessionId != null,
                queue,
                repeatMode,
                isPlaybackLocked,
            )
        }.stateIn(scope, SharingStarted.Eagerly, false)

    enum class PlaybackState {
        IDLE,
        BUFFERING,
        READY,
        ENDED,
    }

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val pendingCommands = PendingPlaybackCommands()
    private var bindingRequested = false
    private var connectionJob: Job? = null
    private var previewJob: Job? = null
    private var observationScope: CoroutineScope? = null

    private fun deferUntilConnected(command: () -> Unit): Boolean {
        if (!pendingCommands.defer(command)) return false
        ensureConnected()
        return true
    }

    suspend fun awaitConnected() {
        ensureConnected()
        withTimeout(5_000L) { isConnected.first { it } }
    }

    fun ensureConnected() {
        if (bindingRequested) return
        bindingRequested = true
        previewJob?.cancel()
        try {
            val intent = Intent(context, MusicService::class.java).putExtra("internal_bind", true)
            if (!context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) bindingFailed()
        } catch (error: RuntimeException) {
            Timber.w(error, "Could not bind playback service")
            bindingFailed()
        }
    }

    private fun bindingFailed() {
        bindingRequested = false
        pendingCommands.clear()
        scope.launch { _loadError.value = getString(Res.string.error_unknown) }
    }

    private fun disconnect() {
        connectionJob?.cancel()
        observationScope?.cancel()
        observationScope = null
        musicService?.player?.removePlayerListener(readinessListener)
        musicService = null
        pendingCommands.disconnected()
        crossfadeStateValue.value = null
        updatePlaybackState(Player.STATE_IDLE)
        _isConnected.value = false
        _isPlaying.value = false
        wantsPlaybackMutableState.value = false
    }

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                val binder = service as MusicService.MusicBinder
                val s = binder.getService()
                connectionJob?.cancel()
                connectionJob =
                    scope.launch {
                        s.awaitPlaybackRestore()
                        observationScope?.cancel()
                        musicService?.player?.removePlayerListener(readinessListener)
                        musicService = s
                        observationScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
                        observeService(s)
                        _isConnected.value = true
                        pendingCommands.connected()
                    }
            }

            override fun onServiceDisconnected(name: ComponentName?) = disconnect()

            override fun onBindingDied(name: ComponentName?) {
                disconnect()
                context.unbindService(this)
                bindingRequested = false
                ensureConnected()
            }

            override fun onNullBinding(name: ComponentName?) {
                context.unbindService(this)
                bindingFailed()
            }
        }

    init {
        scope.launch {
            AndroidDeviceConnect.manager.state.collect { state ->
                if (state.peers.any { it.state == DeviceConnectManager.PeerState.CONNECTED }) ensureConnected()
            }
        }
        scope.launch {
            MusicService.running.dropWhile { !it }.collect { running ->
                if (running) {
                    ensureConnected()
                } else if (bindingRequested) {
                    context.unbindService(serviceConnection)
                    bindingRequested = false
                    disconnect()
                }
            }
        }
        previewJob =
            scope.launch {
                if (bindingRequested || !Settings.shared.persistentQueue) return@launch
                val restored =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val raw = Settings.shared.persistentPlaybackState
                            if (raw.isBlank()) return@runCatching null
                            decodePersistPlayerState(raw, Settings.shared.persistentPlaybackPosition)
                                .restoredPlaybackState(Settings.shared.rememberShuffleAndRepeat, BlockedContent.snapshot()::isBlocked)
                        }.onFailure { Timber.w(it, "Could not restore playback preview") }.getOrNull()
                    } ?: return@launch
                if (bindingRequested || musicService != null) return@launch
                val (state, snapshot) = restored
                _queueSnapshot.value = snapshot
                _queueSongs.value = snapshot.displayedItems
                _currentMediaItemIndex.value = snapshot.currentIndex
                val song = snapshot.displayedItems.getOrNull(snapshot.currentIndex)
                _queueTitle.value = state.queueTitle
                _currentPosition.value = state.currentPosition
                _duration.value = (song?.duration ?: 0) * 1000L
                _shuffleModeEnabled.value = snapshot.isShuffled
                _repeatMode.value = if (Settings.shared.rememberShuffleAndRepeat) state.repeatMode else Player.REPEAT_MODE_OFF
                _volume.value = state.volume
                Settings.shared.pendingContinuePlayback = Settings.shared.promptContinuePlayback && song != null
                updateProgress()
                _currentSong.value = song
            }
    }

    private fun observeService(service: MusicService) {
        val scope = checkNotNull(observationScope)
        val player = service.player
        updatePlaybackState(player.playbackState)
        player.addPlayerListener(readinessListener)
        wantsPlaybackMutableState.value = player.wantsPlayback
        _shuffleModeEnabled.value = player.isShuffleEnabled
        _repeatMode.value = service.repeatMode.value
        scope.launch {
            player.isPlaying.collect {
                if (!_castState.value.isConnected) _isPlaying.value = it
            }
        }
        scope.launch {
            player.wantsPlaybackState.collect { wantsPlaybackMutableState.value = it }
        }
        scope.launch {
            player.volume.collect {
                if (!_castState.value.isConnected) _volume.value = it
            }
        }
        scope.launch {
            player.playbackSpeed.collect { _playbackSpeedFlow.value = it }
        }
        scope.launch {
            player.playbackPitch.collect { _playbackPitchFlow.value = it }
        }
        scope.launch {
            combine(player.currentSong, player.currentPosition, player.duration, player.bufferedPosition) { _, _, _, _ ->
                if (!_castState.value.isConnected) {
                    _currentSong.value = player.currentSong.value
                    _currentPosition.value = player.currentPosition.value
                    _duration.value = player.duration.value
                    _bufferedPosition.value = player.bufferedPosition.value
                    updateProgress()
                } else {
                    _currentSong.value = player.currentSong.value
                }
            }.collect {}
        }
        scope.launch {
            service.castManager.devices.collect { _castDevices.value = it }
        }
        scope.launch {
            service.castManager.state.collect { state ->
                _castState.value = state
                if (state.isConnected) {
                    _isPlaying.value = state.isPlaying
                    _volume.value = state.volume
                    _currentPosition.value = state.positionMs
                    _duration.value = state.durationMs
                    updateProgress()
                }
            }
        }
        scope.launch {
            player.queueState.collect { _queueState.value = it }
        }
        scope.launch {
            player.queueSnapshots.collect {
                _queueSnapshot.value = it
                _queueSongs.value = it.displayedItems
                _currentMediaItemIndex.value = it.currentIndex
                _shuffleModeEnabled.value = it.isShuffled
            }
        }
        scope.launch {
            service.repeatMode.collect { _repeatMode.value = it }
        }
        scope.launch {
            service.shuffleModeEnabled.collect { _shuffleModeEnabled.value = it }
        }
        scope.launch {
            player.playbackStateFlow.collect {
                _playbackState.value =
                    when (it) {
                        Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                        Player.STATE_READY -> PlaybackState.READY
                        Player.STATE_ENDED -> PlaybackState.ENDED
                        else -> PlaybackState.IDLE
                    }
            }
        }
        scope.launch {
            service.isReconnecting.collect { _isReconnecting.value = it }
        }
        scope.launch {
            service.isQueueLoading.collect { _isQueueLoading.value = it }
        }
        scope.launch {
            combine(service.isPlaybackLoading, _playbackState, wantsPlaybackMutableState) { resolving, state, wantsPlayback ->
                resolving || (wantsPlayback && state == PlaybackState.BUFFERING)
            }.collect { _isPlaybackLoading.value = it }
        }
        scope.launch {
            service.queueTitle.collect { _queueTitle.value = it }
        }
        scope.launch {
            service.queueChips.collect { _queueChips.value = it }
        }
        scope.launch {
            player.currentPlaybackResource.collect {
                _currentPlaybackResource.value = it
                updateProgress()
            }
        }
        scope.launch {
            player.loadError.collect { _loadError.value = it }
        }
        scope.launch {
            service.crossfadeState.collect { transition ->
                crossfadeStateValue.value = transition
            }
        }
        scope.launch {
            service.isVideoModeActive.collect {
                _isVideoModeActive.value = it
                updateProgress()
            }
        }
        scope.launch {
            service.isVideoModeResolving.collect { _isVideoModeResolving.value = it }
        }
        scope.launch {
            service.isVideoViewerVisible.collect {
                _isVideoViewerVisible.value = it
                updateProgress()
            }
        }
        scope.launch {
            service.sleepTimerController.state.collect { _sleepTimerState.value = it }
        }
    }

    private fun updateProgress() {
        val isLive = _currentSong.value?.isLive == true || _currentPlaybackResource.value?.isLive == true
        if (isLive) {
            _progress.value = 1f
            _bufferedProgress.value = 1f
            return
        }
        val dur = _duration.value
        if (dur > 0) {
            _progress.value = (_currentPosition.value.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            val inVideoMode = _isVideoModeActive.value || _isVideoViewerVisible.value
            _bufferedProgress.value =
                if (inVideoMode) {
                    (_bufferedPosition.value.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
        } else {
            _progress.value = 0f
            _bufferedProgress.value = 0f
        }
    }

    fun playSong(
        song: SongItem,
        url: String,
        startPositionMs: Long = 0L,
        startVideo: Boolean = false,
    ) {
        if (song.isUnavailable) return
        cancelPlaylistRequest()
        if (AndroidListenTogetherManager.isGuestPlaybackLocked) {
            listenTogetherPlaybackRequestsState.tryEmit(ListenTogetherPlaybackRequest(song = song))
            return
        }
        if (AndroidDeviceConnect.intercept(
                DcPlaybackCommand(
                    DcPlaybackAction.REPLACE_QUEUE,
                    songs = listOf(song),
                    index = 0,
                    finite = true,
                    positionMs = startPositionMs,
                ),
            )
        ) {
            return
        }
        playSongInternal(song, url, startPositionMs, startVideo)
    }

    private fun localSongChangeStartsPlayback(): Boolean =
        _castState.value.isConnected ||
            shouldPlayAfterSongChange(
                keepPaused = Settings.shared.keepPausedOnSongChange,
                hasCurrentSong = _currentSong.value != null,
                playbackRequested = musicService?.player?.wantsPlayback == true,
            )

    private fun playSongInternal(
        song: SongItem,
        url: String,
        startPositionMs: Long = 0L,
        startVideo: Boolean = false,
    ) {
        if (deferUntilConnected { playSongInternal(song, url, startPositionMs, startVideo) }) return
        if (url.isBlank()) {
            musicService?.playQueue(
                listOf(song),
                finite = true,
                startPositionMs = startPositionMs,
                startPlaying = localSongChangeStartsPlayback(),
                startVideo = startVideo,
            )
        } else {
            musicService?.player?.play(song, url, startPlaying = localSongChangeStartsPlayback())
        }
    }

    fun playNext(song: SongItem) {
        addToQueue(song, playNext = true)
    }

    fun playNext(
        index: Int,
        expectedQueue: MusicQueueSnapshot = _queueSnapshot.value,
    ) {
        val player = musicService?.player ?: return
        if (player.queueSnapshot() != expectedQueue) return
        val entryKey =
            player.queueState.value.entries
                .getOrNull(index)
                ?.key ?: return
        playNextQueueEntry(entryKey)
    }

    override fun playNextQueueEntry(entryKey: Long) {
        if (deferUntilConnected { playNextQueueEntry(entryKey) }) return
        val player = musicService?.player ?: return
        val state = player.queueState.value
        val index = queueEntryIndex(state, entryKey) ?: return
        val currentIndex = state.currentIndex.takeIf { it >= 0 } ?: return
        if (index == currentIndex) return
        val targetIndex = if (index < currentIndex) currentIndex else (currentIndex + 1).coerceAtMost(state.entries.lastIndex)
        val song = state.entries[index].song
        val updatedSong =
            song
                .copy(queueSourceTitle = QUEUE_SOURCE_ADDED_MANUALLY)
                .takeIf { song.queueSourceTitle.isRecommendedQueueSource() && index > currentIndex }
        if (index == targetIndex && updatedSong == null) return
        moveQueueEntry(entryKey, state.entries[targetIndex].key, updatedSong)
    }

    fun playPlaylist(
        playlistId: String,
        title: String,
        shuffle: Boolean = false,
    ) = playPagedCollection(title, shuffle) {
        GlobalYouTubeRepository.instance.run {
            val page = getPlaylistPlaybackPage(playlistId)
            if (shuffle) completePlaylistPage(page, applyContentFilters = false) else page
        }
    }

    fun playAlbum(
        albumId: String,
        title: String,
        shuffle: Boolean = false,
    ) = playPagedCollection(title, shuffle) { GlobalYouTubeRepository.instance.getAlbumPlaybackPage(albumId) }

    private fun playPagedCollection(
        title: String,
        shuffle: Boolean,
        load: suspend () -> PlaylistPage?,
    ) {
        cancelPlaylistRequest()
        val generation = playlistRequestGeneration
        playlistRequestJob =
            scope.launch {
                try {
                    val page =
                        withContext(Dispatchers.IO) {
                            var loaded = load()
                            while (loaded != null && loaded.hasMore && BlockedContent.filterPlayableSongs(loaded.songs).isEmpty()) {
                                loaded = GlobalYouTubeRepository.instance.continuePlaylistPage(loaded, applyContentFilters = false)
                            }
                            loaded
                        }
                    if (generation != playlistRequestGeneration) return@launch
                    playlistRequestJob = null
                    val songs = page?.songs?.let(BlockedContent::filterPlayableSongs).orEmpty()
                    if (songs.isEmpty()) {
                        Toast.makeText(context, getString(Res.string.playlist_unavailable), Toast.LENGTH_SHORT).show()
                    } else if (shuffle) {
                        shuffleQueue(songs, title, finite = true, playlistPage = page)
                    } else {
                        playQueue(songs, queueTitle = title, finite = true, playlistPage = page, startVideo = false)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (generation ==
                        playlistRequestGeneration
                    ) {
                        Toast.makeText(context, getString(Res.string.error_unknown), Toast.LENGTH_SHORT).show()
                    }
                }
            }
    }

    fun shuffleQueue(
        songs: List<SongItem>,
        queueTitle: String? = null,
        finite: Boolean = false,
        playlistPage: PlaylistPage? = null,
        songsOnly: Boolean = false,
    ) {
        val playableSongs = BlockedContent.filterPlayableSongs(songs)
        if (playableSongs.isEmpty()) return
        playQueue(
            playableSongs,
            playableSongs.indices.random(),
            queueTitle,
            finite,
            shuffleModeEnabled = true,
            playlistPage = playlistPage,
            songsOnly = songsOnly,
            startVideo = false,
        )
    }

    fun playQueue(
        songs: List<SongItem>,
        startIndex: Int = 0,
        queueTitle: String? = null,
        finite: Boolean = false,
        shuffleModeEnabled: Boolean? = null,
        playlistPage: PlaylistPage? = null,
        songsOnly: Boolean = false,
        startVideo: Boolean = false,
    ) {
        if (songs.any { it.isUnavailable }) {
            val queue = MusicQueue().apply { setQueue(songs, startIndex) }
            if (queue.items.value.isNotEmpty()) {
                playQueue(
                    queue.items.value,
                    queue.currentIndex.value,
                    queueTitle,
                    finite,
                    shuffleModeEnabled,
                    playlistPage,
                    songsOnly,
                    startVideo,
                )
            }
            return
        }
        if (deferUntilConnected {
                playQueue(songs, startIndex, queueTitle, finite, shuffleModeEnabled, playlistPage, songsOnly, startVideo)
            }
        ) {
            return
        }
        cancelPlaylistRequest()
        val generation = playlistRequestGeneration
        // Synced queue snapshots have no continuation cursor in the wire protocol.
        if (playlistPage?.hasMore == true &&
            (AndroidListenTogetherManager.isGuestPlaybackLocked || AndroidDeviceConnect.manager.remote.willStartRemotePlayback)
        ) {
            playlistRequestJob =
                scope.launch {
                    try {
                        val complete =
                            withContext(Dispatchers.IO) {
                                GlobalYouTubeRepository.instance.completePlaylistPage(
                                    playlistPage,
                                    applyContentFilters = false,
                                )
                            }
                        if (generation == playlistRequestGeneration) {
                            playlistRequestJob = null
                            val additions = BlockedContent.filterPlayableSongs(complete.songs.drop(playlistPage.songs.size))
                            playQueue(
                                songs + additions,
                                startIndex,
                                queueTitle,
                                finite,
                                shuffleModeEnabled,
                                songsOnly = songsOnly,
                                startVideo = startVideo,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (generation ==
                            playlistRequestGeneration
                        ) {
                            Toast.makeText(context, getString(Res.string.error_unknown), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            return
        }
        if (AndroidListenTogetherManager.isGuestPlaybackLocked) {
            val selectedSong = songs.getOrElse(startIndex) { songs.firstOrNull() } ?: return
            listenTogetherPlaybackRequestsState.tryEmit(
                ListenTogetherPlaybackRequest(
                    song = selectedSong,
                    queue = songs,
                    startIndex = startIndex,
                    queueTitle = queueTitle,
                    shuffleModeEnabled = shuffleModeEnabled,
                ),
            )
            return
        }
        musicService?.playQueue(
            songs,
            startIndex,
            queueTitle = queueTitle,
            finite = finite,
            shuffleModeEnabled = shuffleModeEnabled,
            playlistPage = playlistPage,
            songsOnly = songsOnly,
            startPlaying = localSongChangeStartsPlayback(),
            startVideo = startVideo,
        )
    }

    private val widgetPlaybackMutex = Mutex()

    suspend fun togglePlayPauseFromWidget() {
        if (!_isConnected.value) {
            togglePlayPause()
            return
        }
        widgetPlaybackMutex.withLock {
            val service = musicService ?: return@withLock
            val requestId = service.player.latestQueuePlaybackRequestId
            val commandGeneration = service.playbackCommandGeneration
            val needsPromotion =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                    !AndroidListenTogetherManager.isGuestPlaybackLocked &&
                    !_castState.value.isConnected &&
                    !AndroidDeviceConnect.manager.remote.willStartRemotePlayback &&
                    !service.player.wantsPlayback &&
                    service.player.currentPlaybackResource.value != null &&
                    service.player.playbackStateFlow.value != Player.STATE_IDLE
            runWidgetPlaybackCommand(
                needsPromotion = needsPromotion,
                promote = service::promoteWidgetPlayback,
                isCurrent = {
                    musicService === service && service.player.latestQueuePlaybackRequestId == requestId &&
                        service.playbackCommandGeneration == commandGeneration &&
                        (
                            !needsPromotion || (
                                !service.player.wantsPlayback && !_castState.value.isConnected &&
                                    !AndroidDeviceConnect.manager.remote.willStartRemotePlayback
                            )
                        )
                },
                command = ::togglePlayPause,
            )
        }
    }

    override fun togglePlayPause() {
        if (deferUntilConnected { togglePlayPause() }) return
        if (AndroidListenTogetherManager.isGuestPlaybackLocked) return
        val action =
            if (_castState.value.takeIf { it.isConnected }?.isPlaying
                ?: (musicService?.player?.wantsPlayback == true)
            ) {
                DcPlaybackAction.PAUSE
            } else {
                DcPlaybackAction.PLAY
            }
        if (AndroidDeviceConnect.intercept(DcPlaybackCommand(action), expectedQueue = _queueSnapshot.value)) return
        val service = musicService ?: return
        if (_castState.value.isConnected) {
            service.toggleCastPlayback()
        } else if (service.player.wantsPlayback) {
            service.player.pause()
        } else if (service.player.retryPendingQueuePlayback()) {
            return
        } else {
            service.player.resume()
        }
    }

    override fun seekTo(position: Long) = seekTo(position, PlaybackSeekSource.Other)

    override fun seekTo(
        position: Long,
        source: PlaybackSeekSource,
    ) {
        if (_currentSong.value?.isLive == true || _currentPlaybackResource.value?.isLive == true) return
        if (AndroidListenTogetherManager.isGuestPlaybackLocked) return
        if (AndroidDeviceConnect.intercept(DcPlaybackCommand(DcPlaybackAction.SEEK, positionMs = position))) return
        seekToInternal(position, publishToRoom = true, source = source)
    }

    private fun seekToInternal(
        position: Long,
        publishToRoom: Boolean = false,
        source: PlaybackSeekSource = PlaybackSeekSource.Other,
    ) {
        if (deferUntilConnected { seekToInternal(position, publishToRoom, source) }) return
        val service = musicService ?: return
        if (_castState.value.isConnected) {
            service.seekCastTo(position)
        } else {
            service.updatePendingRestorePosition(position)
            service.seekTo(position, source)
        }
        if (publishToRoom) AndroidListenTogetherManager.currentManager?.sendSeek(position)
    }

    override fun setVolume(volume: Float) {
        if (AndroidDeviceConnect.intercept(DcPlaybackCommand(DcPlaybackAction.VOLUME, volume = volume))) return
        if (deferUntilConnected { setVolume(volume) }) return
        val service = musicService ?: return
        if (_castState.value.isConnected) {
            service.setCastVolume(volume)
        } else {
            service.player.setVolume(volume)
            Settings.shared.volume = volume.coerceIn(0f, 1f)
        }
    }

    override fun startCastDiscovery() {
        if (deferUntilConnected { startCastDiscovery() }) return
        musicService?.startCastDiscovery()
    }

    override fun stopCastDiscovery() {
        if (bindingRequested && deferUntilConnected { stopCastDiscovery() }) return
        musicService?.stopCastDiscovery()
    }

    override fun castTo(device: CastDevice) {
        if (deferUntilConnected { castTo(device) }) return
        musicService?.castTo(device)
    }

    override fun disconnectCast() {
        if (bindingRequested && deferUntilConnected { disconnectCast() }) return
        musicService?.disconnectCast()
    }

    override fun skipToNext() = skipToNext(expectedSongId = null)

    private fun skipToNext(expectedSongId: String?) {
        if (AndroidListenTogetherManager.isGuestPlaybackLocked) return
        if (AndroidDeviceConnect.intercept(DcPlaybackCommand(DcPlaybackAction.NEXT))) return
        skipToNextInternal(local = true, expectedSongId = expectedSongId)
    }

    private fun skipToNextInternal(
        local: Boolean = false,
        expectedSongId: String? = null,
    ) {
        if (deferUntilConnected { skipToNextInternal(local, expectedSongId) }) return
        val player = musicService?.player ?: return
        if (expectedSongId != null && player.currentSong.value?.id != expectedSongId) return
        val resumePlayback = if (local && !_castState.value.isConnected) localSongChangeStartsPlayback() else player.wantsPlayback
        if (musicService?.extendPlaylistBeforeSkip(resumePlayback) == true) return
        val mode = _repeatMode.value
        val index = player.currentMediaItemIndex.value
        val lastIndex = player.songQueue.value.lastIndex
        if (mode == Player.REPEAT_MODE_ALL && index == lastIndex && lastIndex >= 0) {
            player.skipToQueueItem(0, resumePlayback = resumePlayback)
        } else {
            player.skipToNext(resumePlayback)
        }
    }

    override fun skipToPrevious() {
        if (AndroidListenTogetherManager.isGuestPlaybackLocked) return
        if (AndroidDeviceConnect.intercept(DcPlaybackCommand(DcPlaybackAction.PREVIOUS))) return
        skipToPreviousInternal(local = true)
    }

    private fun skipToPreviousInternal(local: Boolean = false) {
        if (deferUntilConnected { skipToPreviousInternal(local) }) return
        val player = musicService?.player ?: return
        player.skipToPrevious(if (local && !_castState.value.isConnected) localSongChangeStartsPlayback() else player.wantsPlayback)
    }

    fun skipToQueueItem(index: Int) {
        skipToQueueItem(index, _queueSnapshot.value)
    }

    fun skipToQueueItem(
        index: Int,
        expectedQueue: MusicQueueSnapshot = _queueSnapshot.value,
        toIndex: Int = -1,
    ) {
        if (deferUntilConnected { skipToQueueItem(index, expectedQueue, toIndex) }) return
        if (AndroidListenTogetherManager.isGuestPlaybackLocked) return
        val player = musicService?.player ?: return
        if (player.queueSnapshot() != expectedQueue) return
        if (AndroidDeviceConnect.intercept(
                DcPlaybackCommand(DcPlaybackAction.SELECT, index = index, toIndex = toIndex),
                expectedQueue = expectedQueue,
            )
        ) {
            return
        }
        if (toIndex >= 0 && toIndex != index) player.moveQueueItem(index, toIndex)
        player.skipToQueueItem(if (toIndex >= 0) toIndex else index, resumePlayback = localSongChangeStartsPlayback())
    }

    override fun skipToQueueEntry(entryKey: Long) {
        if (deferUntilConnected { skipToQueueEntry(entryKey) }) return
        if (AndroidListenTogetherManager.isGuestPlaybackLocked) return
        val player = musicService?.player ?: return
        val state = player.queueState.value
        val index = queueEntryIndex(state, entryKey) ?: return
        val targetIndex =
            queueSelectionTargetIndex(
                index = index,
                currentIndex = state.currentIndex,
                isSuggested =
                    state.entries[index]
                        .song.queueSourceTitle
                        .isRecommendedQueueSource(),
            )
        val expectedQueue = player.queueSnapshot()
        if (AndroidDeviceConnect.intercept(
                DcPlaybackCommand(DcPlaybackAction.SELECT, index = index, toIndex = targetIndex),
                expectedQueue = expectedQueue,
            )
        ) {
            return
        }
        if (targetIndex != index) player.moveQueueItem(index, targetIndex)
        player.skipToQueueItem(targetIndex, resumePlayback = localSongChangeStartsPlayback())
    }

    fun setPlaybackPitch(pitch: Float) {
        val normalizedPitch = normalizePlaybackTempo(pitch) ?: return
        if (AndroidDeviceConnect.manager.remote.intercept(DcPlaybackCommand(DcPlaybackAction.PITCH, pitch = normalizedPitch))) return
        Settings.shared.playbackPitch = normalizedPitch
        musicService?.player?.setPlaybackPitch(normalizedPitch)
    }

    override fun setPlaybackSpeed(speed: Float) {
        val normalizedSpeed = normalizePlaybackTempo(speed) ?: return
        if (AndroidDeviceConnect.intercept(DcPlaybackCommand(DcPlaybackAction.SPEED, speed = normalizedSpeed))) return
        Settings.shared.playbackSpeed = normalizedSpeed
        musicService?.player?.setPlaybackSpeed(normalizedSpeed)
    }

    fun setRepeatMode(mode: Int) {
        if (deferUntilConnected { setRepeatMode(mode) }) return
        if (AndroidDeviceConnect.intercept(
                DcPlaybackCommand(DcPlaybackAction.REPEAT, repeatMode = mode.toDeviceConnectRepeatMode()),
            )
        ) {
            return
        }
        musicService?.media3Player?.repeatMode = mode
        _repeatMode.value = mode
    }

    override fun cycleRepeatMode() {
        val nextMode = (_repeatMode.value + 1) % 3
        setRepeatMode(nextMode)
    }

    override fun toggleShuffle() {
        if (deferUntilConnected { toggleShuffle() }) return
        if (AndroidDeviceConnect.intercept(
                DcPlaybackCommand(
                    DcPlaybackAction.SHUFFLE,
                    enabled = !_shuffleModeEnabled.value,
                ),
            )
        ) {
            return
        }
        val service = musicService
        _shuffleModeEnabled.value =
            if (service != null) {
                val enabled = !service.player.isShuffleEnabled
                service.media3Player?.shuffleModeEnabled = enabled
                enabled
            } else {
                !_shuffleModeEnabled.value
            }
    }

    override fun startSleepTimer(
        durationMs: Long,
        fadeOut: Boolean,
        finishCurrentSong: Boolean,
    ) {
        if (deferUntilConnected { startSleepTimer(durationMs, fadeOut, finishCurrentSong) }) return
        musicService?.sleepTimerController?.start(durationMs, fadeOut, finishCurrentSong)
    }

    override fun stopAfterCurrentSong(fadeOut: Boolean) {
        if (deferUntilConnected { stopAfterCurrentSong(fadeOut) }) return
        musicService?.sleepTimerController?.stopAfterCurrentSong(fadeOut)
    }

    override fun cancelSleepTimer() {
        if (bindingRequested && deferUntilConnected { cancelSleepTimer() }) return
        musicService?.sleepTimerController?.cancel()
    }

    override fun startRadio(song: SongItem) {
        if (deferUntilConnected { startRadio(song) }) return
        if (song.isUnavailable) return
        cancelPlaylistRequest()
        if (AndroidListenTogetherManager.isGuestPlaybackLocked) {
            listenTogetherPlaybackRequestsState.tryEmit(ListenTogetherPlaybackRequest(song = song))
            return
        }
        musicService?.playMix(song)
    }

    override fun selectQueueChip(chipId: String) {
        if (deferUntilConnected { selectQueueChip(chipId) }) return
        musicService?.selectQueueChip(chipId)
    }

    fun addToQueue(index: Int) {
        val entryKey =
            _queueState.value.entries
                .getOrNull(index)
                ?.key ?: return
        addQueueEntry(entryKey)
    }

    override fun addQueueEntry(entryKey: Long) {
        if (deferUntilConnected { addQueueEntry(entryKey) }) return
        if (AndroidListenTogetherManager.isGuestPlaybackLocked) return
        val player = musicService?.player ?: return
        val state = player.queueState.value
        val index = queueEntryIndex(state, entryKey) ?: return
        val song = state.entries[index].song
        val isSuggested = song.queueSourceTitle.isRecommendedQueueSource() && index > state.currentIndex
        if (isSuggested) {
            val firstSuggestedIndex =
                state.entries.indices.firstOrNull {
                    it > state.currentIndex &&
                        state.entries[it]
                            .song.queueSourceTitle
                            .isRecommendedQueueSource()
                } ?: state.entries.size
            val targetIndex = firstSuggestedIndex.coerceAtMost(state.entries.lastIndex)
            moveQueueEntry(
                entryKey,
                state.entries[targetIndex].key,
                song.copy(queueSourceTitle = QUEUE_SOURCE_ADDED_MANUALLY),
            )
        } else {
            addToQueue(song)
        }
    }

    override fun addToQueue(
        song: SongItem,
        playNext: Boolean,
    ) = addToQueue(listOf(song), playNext)

    fun addToQueue(
        songs: List<SongItem>,
        playNext: Boolean = false,
    ) {
        if (AndroidListenTogetherManager.isGuestPlaybackLocked || songs.isEmpty()) return
        if (deferUntilConnected { addToQueue(songs, playNext) }) return
        val preparedSongs =
            BlockedContent.filterPlayableSongs(songs).map { song ->
                if (song.queueSourceTitle.isRecommendationGeneratedQueueSource()) {
                    song.copy(queueSourceTitle = QUEUE_SOURCE_ADDED_MANUALLY)
                } else {
                    song
                }
            }
        val action = if (playNext) DcPlaybackAction.PLAY_NEXT else DcPlaybackAction.ADD
        if (AndroidDeviceConnect.intercept(DcPlaybackCommand(action, songs = preparedSongs))) return
        val service = musicService
        if (service == null) return
        if (service.player.currentSong.value == null) {
            service.addToQueue(preparedSongs, playNext)
        } else {
            val state = service.player.queueState.value
            val recommendationIds =
                state.entries
                    .drop(state.currentIndex + 1)
                    .filter { it.song.queueSourceTitle.isRecommendedQueueSource() }
                    .mapTo(mutableSetOf()) { it.song.id }
            if (preparedSongs.none { it.id in recommendationIds }) {
                service.addToQueue(preparedSongs, playNext)
                return
            }
            (if (playNext) preparedSongs.asReversed() else preparedSongs).forEach { song ->
                val state = service.player.queueState.value
                val existingEntry =
                    state.entries
                        .withIndex()
                        .firstOrNull { (index, entry) ->
                            index > state.currentIndex && entry.song.id == song.id &&
                                entry.song.queueSourceTitle.isRecommendedQueueSource()
                        }?.value
                if (existingEntry != null) {
                    if (playNext) playNextQueueEntry(existingEntry.key) else addQueueEntry(existingEntry.key)
                } else {
                    service.addToQueue(listOf(song), playNext)
                }
            }
        }
    }

    private fun addToLocalQueue(
        song: SongItem,
        playNext: Boolean,
    ) {
        if (deferUntilConnected { addToLocalQueue(song, playNext) }) return
        val service = musicService
        if (service != null) {
            service.addToQueue(listOf(song), playNext)
        }
    }

    override fun removeQueueEntry(entryKey: Long): QueueRemoval? = removeQueueEntries(setOf(entryKey))

    override fun removeQueueEntries(entryKeys: Set<Long>): QueueRemoval? {
        val player = musicService?.player ?: return null
        val indices = queueEntryIndices(player.queueState.value, entryKeys) ?: return null
        return removeQueueItems(indices, player.queueSnapshot())
    }

    fun removeQueueItems(
        indices: Set<Int>,
        expectedQueue: MusicQueueSnapshot = _queueSnapshot.value,
    ): QueueRemoval? {
        val player = musicService?.player ?: return null
        val sync = AndroidDeviceConnect.manager.remote
        if (AndroidListenTogetherManager.isGuestPlaybackLocked || player.queueSnapshot() != expectedQueue) return null
        val previewQueue = MusicQueue().apply { restore(expectedQueue) }
        val removal = previewQueue.removeAt(indices) ?: return null
        val validIndices = removal.items.mapTo(mutableSetOf()) { it.index }
        val after = previewQueue.snapshot()
        if (AndroidDeviceConnect.intercept(
                DcPlaybackCommand(DcPlaybackAction.REMOVE, indices = validIndices),
                expectedQueue = expectedQueue,
            )
        ) {
            queueUndo =
                (
                    queueUndo ?: QueueUndo(
                        emptyList(),
                        expectedQueue,
                        expectedQueue,
                        sync.state.value.sessionId,
                        sync.queueRevision,
                    )
                ).append(
                    removal,
                    expectedQueue,
                    after,
                    sync.state.value.sessionId,
                    sync.queueRevision,
                    sync.queueRevision?.plus(1),
                )
            return removal
        }
        val localRemoval = player.removeQueueItems(validIndices) ?: return null
        queueUndo =
            (queueUndo ?: QueueUndo(emptyList(), expectedQueue, expectedQueue, null, null))
                .append(localRemoval, expectedQueue, player.queueSnapshot(), null, null)
        return localRemoval
    }

    override fun pendingQueueRemovals(): List<QueueRemoval> = queueUndo?.removals.orEmpty()

    override fun discardQueueUndo(removals: List<QueueRemoval>) {
        if (queueUndo?.removals == removals) queueUndo = null
    }

    override fun restoreQueueItems(removals: List<QueueRemoval>) {
        val player = musicService?.player ?: return
        val sync = AndroidDeviceConnect.manager.remote
        val undo = queueUndo
        queueUndo = null
        if (undo == null || undo.removals != removals ||
            !undo.isCurrent(player.queueSnapshot(), sync.state.value.sessionId, sync.queueRevision)
        ) {
            sync.reportError(DcSyncError.STALE_QUEUE)
            return
        }
        if (sync.intercept(
                DcPlaybackCommand(
                    DcPlaybackAction.RESTORE_QUEUE,
                    queue = undo.before,
                    indices = undo.originalRemovalIndices(),
                ),
                expectedQueueRevision = undo.queueRevision,
            )
        ) {
            return
        }
        if (AndroidListenTogetherManager.isGuestPlaybackLocked) return
        removals.asReversed().forEach(player::restoreQueueItems)
    }

    fun moveQueueItem(
        from: Int,
        to: Int,
        expectedQueue: MusicQueueSnapshot = _queueSnapshot.value,
        updatedItem: SongItem? = null,
    ) {
        if (AndroidListenTogetherManager.isGuestPlaybackLocked) return
        val player = musicService?.player ?: return
        if (player.queueSnapshot() != expectedQueue) return
        if (AndroidDeviceConnect.intercept(
                DcPlaybackCommand(
                    DcPlaybackAction.MOVE,
                    songs = listOfNotNull(updatedItem),
                    index = from,
                    toIndex = to,
                ),
                expectedQueue = expectedQueue,
            )
        ) {
            return
        }
        player.moveQueueItem(from, to, updatedItem)
    }

    override fun moveQueueEntry(
        fromEntryKey: Long,
        toEntryKey: Long,
        updatedItem: SongItem?,
    ): Boolean {
        if (AndroidListenTogetherManager.isGuestPlaybackLocked) return false
        val player = musicService?.player ?: return false
        val state = player.queueState.value
        val from = queueEntryIndex(state, fromEntryKey) ?: return false
        val to = queueEntryIndex(state, toEntryKey) ?: return false
        val expectedQueue = player.queueSnapshot()
        if (AndroidDeviceConnect.intercept(
                DcPlaybackCommand(
                    DcPlaybackAction.MOVE,
                    songs = listOfNotNull(updatedItem),
                    index = from,
                    toIndex = to,
                ),
                expectedQueue = expectedQueue,
            )
        ) {
            return true
        }
        player.moveQueueItem(from, to, updatedItem)
        return true
    }

    override fun reorderQueueEntries(entryKeys: List<Long>): Boolean {
        if (AndroidListenTogetherManager.isGuestPlaybackLocked) return false
        val player = musicService?.player ?: return false
        val entries = player.queueState.value.entries
        if (entryKeys.size != entries.size || entryKeys.toSet() != entries.map { it.key }.toSet()) return false
        val indices = entries.withIndex().associate { it.value.key to it.index }
        val order = entryKeys.map(indices::getValue)
        val expectedQueue = player.queueSnapshot()
        if (!AndroidDeviceConnect.intercept(
                DcPlaybackCommand(DcPlaybackAction.REORDER, order = order),
                expectedQueue = expectedQueue,
            )
        ) {
            player.reorderQueue(order)
        }
        return true
    }

    override fun onSongDisliked(songId: String) {
        if (currentSong.value?.id == songId) skipToNext(expectedSongId = songId)
    }

    override fun blockArtist(artistId: String) {
    }

    fun getCurrentPlaybackResource(): PlaybackResource? = _currentPlaybackResource.value

    override fun changeMusicVideoResolution(quality: MusicVideoQuality) {
        if (deferUntilConnected { changeMusicVideoResolution(quality) }) return
        musicService?.changeMusicVideoResolution(quality)
    }

    override fun toggleVideoMode() {
        if (deferUntilConnected { toggleVideoMode() }) return
        musicService?.toggleVideoMode()
    }

    override fun setVideoMode(wantVideo: Boolean) {
        if (deferUntilConnected { setVideoMode(wantVideo) }) return
        musicService?.setVideoMode(wantVideo)
    }

    fun stop() {
        cancelPlaylistRequest()
        if (AndroidDeviceConnect.intercept(DcPlaybackCommand(DcPlaybackAction.CLEAR))) return
        if (musicService == null && !bindingRequested) {
            previewJob?.cancel()
            _currentSong.value = null
            _queueSongs.value = emptyList()
            _queueSnapshot.value = MusicQueueSnapshot()
            _currentMediaItemIndex.value = -1
            _currentPosition.value = 0L
            _duration.value = 0L
            Settings.shared.pendingContinuePlayback = false
            Settings.shared.persistentPlaybackState = ""
            Settings.shared.persistentPlaybackPosition = ""
            updateProgress()
            return
        }
        if (deferUntilConnected { stop() }) return
        musicService?.player?.stop()
    }

    fun prepareDismissal(): () -> Unit {
        if (musicService == null && !bindingRequested) {
            val expected = _queueSnapshot.value
            return { if (musicService == null && !bindingRequested && _queueSnapshot.value == expected) stop() }
        }
        val player = musicService?.player
        return preparePlayerDismissal(
            currentRequestId = { player?.takeIf { it === musicService?.player }?.latestQueuePlaybackRequestId },
            stop = { stop() },
        )
    }

    fun applyAudioPreferences() {
        musicService?.player?.applyAudioPreferences()
    }

    override fun currentPositionMs(): Long = musicService?.player?.getRealtimePositionMs() ?: _currentPosition.value

    fun currentQueueIndex(): Int? = _currentMediaItemIndex.value.takeIf { it >= 0 }

    fun currentQueueSnapshot(): MusicQueueSnapshot = musicService?.player?.queueSnapshot() ?: _queueSnapshot.value

    override fun queueSong(entryKey: Long): SongItem? {
        val state = _queueState.value
        return queueEntryIndex(state, entryKey)?.let { state.entries[it].song }
    }

    override fun queueSongs(entryKeys: Set<Long>): List<SongItem> {
        val state = _queueState.value
        val indices = queueEntryIndices(state, entryKeys) ?: return emptyList()
        return indices.sorted().map { state.entries[it].song }
    }

    fun playbackSpeed(): Float = _playbackSpeedFlow.value

    fun playbackInstanceId(): Long? = musicService?.player?.currentPlaybackInstanceId?.takeIf { it > 0L }

    suspend fun playQueueFromRemote(
        songs: List<SongItem>,
        startIndex: Int,
        title: String?,
    ): Boolean {
        awaitConnected()
        cancelPlaylistRequest()
        return musicService?.playQueueFromRemote(songs, startIndex, title) == true
    }

    fun setQueueTitleFromRemote(title: String?) {
        if (deferUntilConnected { setQueueTitleFromRemote(title) }) return
        musicService?.setQueueTitleFromRemote(title)
    }

    fun replaceUpcomingQueueFromRemote(songs: List<SongItem>) {
        if (deferUntilConnected { replaceUpcomingQueueFromRemote(songs) }) return
        musicService?.player?.let { player ->
            if (_currentSong.value == null) {
                player.setQueue(songs, startIndex = -1)
            } else {
                player.replaceUpcomingQueue(songs)
            }
        }
    }

    fun addToQueueFromRemote(song: SongItem) {
        addToLocalQueue(song, playNext = false)
    }

    fun skipToNextFromRemote() {
        skipToNextInternal()
    }

    fun skipToPreviousFromRemote() {
        skipToPreviousInternal()
    }

    fun setPlayingFromRemote(playing: Boolean) {
        if (deferUntilConnected { setPlayingFromRemote(playing) }) return
        if (playing) musicService?.player?.resume() else musicService?.player?.pause()
    }

    fun seekToFromRemote(positionMs: Long) {
        seekToInternal(positionMs, source = PlaybackSeekSource("listen_together"))
    }

    fun setPlaybackSpeedFromRemote(speed: Float) {
        if (deferUntilConnected { setPlaybackSpeedFromRemote(speed) }) return
        val normalizedSpeed = normalizePlaybackTempo(speed) ?: return
        musicService?.player?.setPlaybackSpeed(normalizedSpeed)
    }

    fun setVolumeFromRemote(volume: Float) {
        if (deferUntilConnected { setVolumeFromRemote(volume) }) return
        musicService?.player?.setVolume(volume)
    }
}
