package com.metrolist.android.playback

import android.net.Uri
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.runtime.snapshotFlow
import androidx.core.net.toUri
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.HeartRating
import androidx.media3.common.IllegalSeekPositionException
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Rating
import androidx.media3.common.ThumbRating
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.metrolist.android.deviceconnect.toDeviceConnectRepeatMode
import com.metrolist.android.listentogether.AndroidListenTogetherManager
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.deviceconnect.DcPlaybackAction
import com.metrolist.shared.deviceconnect.DcPlaybackCommand
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueEntry
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.playback.asManuallyAddedQueueItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.utils.canonicalArtworkUrl
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArraySet

private data class QueuePeriodUid(
    val entryKey: Long,
    val songId: String,
)

private fun SongItem.toMediaItem(
    queueEntryKey: Long? = null,
    artworkUri: (String?) -> Uri? = { it?.let(::canonicalArtworkUrl)?.toUri() },
): MediaItem =
    MediaItem
        .Builder()
        .setMediaId(queueEntryKey?.takeIf { it >= 0 }?.let { AutomotiveMediaIds.queueSong(it, id) } ?: id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setArtist(artists.joinToString { it.displayName })
                .setAlbumTitle(album?.name)
                .setArtworkUri(artworkUri(thumbnail))
                .build(),
        ).build()

internal fun setMediaSessionRating(
    song: SongItem?,
    rating: Rating,
): Int {
    if (song == null) return SessionError.ERROR_BAD_VALUE
    val liked =
        when (rating) {
            is ThumbRating -> rating.isRated && rating.isThumbsUp
            is HeartRating -> rating.isRated && rating.isHeart
            else -> return SessionError.ERROR_NOT_SUPPORTED
        }
    if (liked) SongLikeState.addLike(song) else SongLikeState.removeLike(song.id)
    return SessionResult.RESULT_SUCCESS
}

internal class MediaSessionItemCache(
    private val artworkUri: (String?) -> Uri? = { it?.let(::canonicalArtworkUrl)?.toUri() },
) {
    private var song: SongItem? = null
    private var entryKey: Long? = null
    private var revision = -1L
    private var aliases: String? = null
    private var item: MediaItem? = null

    fun get(
        song: SongItem?,
        entryKey: Long? = null,
    ): MediaItem? {
        val revision = SongMetadataState.revision.value
        val aliases = Settings.shared.artistNameAliasesJson
        if (this.song !== song || this.entryKey != entryKey || this.revision != revision ||
            this.aliases != aliases
        ) {
            this.song = song
            this.entryKey = entryKey
            this.revision = revision
            this.aliases = aliases
            item = song?.let(SongMetadataState::apply)?.toMediaItem(entryKey, artworkUri)
        }
        return item
    }
}

internal fun mediaSessionTimeline(
    entries: List<MusicQueueEntry>,
    currentIndex: Int,
    currentDurationMs: Long,
    isSeekable: Boolean = true,
    artworkUri: (String?) -> Uri? = { it?.let(::canonicalArtworkUrl)?.toUri() },
): Timeline =
    object : Timeline() {
        private val mediaItems = entries.map { entry -> lazy { entry.song.toMediaItem(entry.key, artworkUri) } }

        override fun getWindowCount(): Int = entries.size

        override fun getWindow(
            windowIndex: Int,
            window: Window,
            defaultPositionProjectionUs: Long,
        ): Window {
            val entry = entries[windowIndex]
            val song = entry.song
            val durationMs =
                if (windowIndex == currentIndex && currentDurationMs > 0) {
                    currentDurationMs
                } else {
                    song.duration?.times(1000L) ?: C.TIME_UNSET
                }
            return window.set(
                QueuePeriodUid(entry.key, song.id),
                mediaItems[windowIndex].value,
                null,
                C.TIME_UNSET,
                C.TIME_UNSET,
                C.TIME_UNSET,
                isSeekable,
                false,
                null,
                0,
                Util.msToUs(durationMs),
                windowIndex,
                windowIndex,
                0,
            )
        }

        override fun getPeriodCount(): Int = entries.size

        override fun getPeriod(
            periodIndex: Int,
            period: Period,
            setIdentifiers: Boolean,
        ): Period {
            val entry = entries[periodIndex]
            val song = entry.song
            val durationMs =
                if (periodIndex == currentIndex && currentDurationMs > 0) {
                    currentDurationMs
                } else {
                    song.duration?.times(1000L) ?: C.TIME_UNSET
                }
            val uid = QueuePeriodUid(entry.key, song.id)
            period.id = uid.takeIf { setIdentifiers }
            period.uid = uid.takeIf { setIdentifiers }
            period.windowIndex = periodIndex
            period.durationUs = Util.msToUs(durationMs)
            period.positionInWindowUs = 0
            period.adPlaybackState = AdPlaybackState.NONE
            period.isPlaceholder = false
            return period
        }

        override fun getIndexOfPeriod(uid: Any): Int {
            val queued = uid as? QueuePeriodUid ?: return C.INDEX_UNSET
            return entries.indexOfFirst { it.key == queued.entryKey && it.song.id == queued.songId }
        }

        override fun getUidOfPeriod(periodIndex: Int): Any = entries[periodIndex].let { QueuePeriodUid(it.key, it.song.id) }
    }

internal class MediaSessionTimelineCache(
    private val artworkUri: (String?) -> Uri? = { it?.let(::canonicalArtworkUrl)?.toUri() },
) {
    private data class Key(
        val entries: List<MusicQueueEntry>,
        val currentIndex: Int,
        val currentDurationMs: Long,
        val isSeekable: Boolean,
        val artistAliases: String,
        val networkConnected: Boolean,
    )

    private var key: Key? = null
    private var timeline: Timeline = Timeline.EMPTY
    private var sourceEntries: List<MusicQueueEntry>? = null
    private var fallbackSong: SongItem? = null
    private var metadataRevision = -1L
    private var resolvedEntries: List<MusicQueueEntry> = emptyList()

    fun get(
        entries: List<MusicQueueEntry>,
        currentIndex: Int,
        currentDurationMs: Long,
        isSeekable: Boolean,
        artistAliases: String = "",
        metadataRevision: Long = SongMetadataState.revision.value,
        fallbackSong: SongItem? = null,
    ): Timeline {
        val currentSong = fallbackSong.takeIf { entries.isEmpty() }
        if (sourceEntries !== entries || this.metadataRevision != metadataRevision || this.fallbackSong !== currentSong) {
            resolvedEntries =
                entries
                    .ifEmpty { currentSong?.let { listOf(MusicQueueEntry(-1, it)) }.orEmpty() }
                    .map { it.copy(song = SongMetadataState.apply(it.song)) }
            sourceEntries = entries
            this.metadataRevision = metadataRevision
            this.fallbackSong = currentSong
        }
        val nextKey =
            Key(resolvedEntries, currentIndex, currentDurationMs, isSeekable, artistAliases, PlaybackNetworkStateStore.current.connected)
        if (nextKey != key) {
            timeline = mediaSessionTimeline(resolvedEntries, currentIndex, currentDurationMs, isSeekable, artworkUri)
            key = nextKey
        }
        return timeline
    }
}

internal class TimelineDurationPublicationTracker {
    private var songId: String? = null
    private var playbackRequestId = -1L
    private var published = false

    fun shouldPublish(
        song: SongItem?,
        playbackRequestId: Long,
        durationMs: Long,
    ): Boolean {
        if (song?.id != songId || playbackRequestId != this.playbackRequestId) {
            songId = song?.id
            this.playbackRequestId = playbackRequestId
            published = false
        }
        val metadataDurationMs = song?.duration?.times(1000L)
        if (published || durationMs <= 0L || durationMs == metadataDurationMs) return false
        published = true
        return true
    }
}

internal class CastTimelineDurationPublicationTracker {
    private var isConnected: Boolean? = null
    private var mediaGeneration: Long? = null
    private var songId: String? = null
    private var publishedDurationMs: Long? = null

    fun shouldPublish(
        song: SongItem?,
        state: CastState,
    ): Boolean {
        val connectionChanged = isConnected?.let { it != state.isConnected } == true
        if (connectionChanged) {
            mediaGeneration = null
            songId = null
            publishedDurationMs = null
        }
        isConnected = state.isConnected
        if (!state.isConnected || song == null) return connectionChanged
        if (state.mediaGeneration != mediaGeneration) {
            mediaGeneration = state.mediaGeneration
            songId = song.id
            publishedDurationMs = null
        }
        if (song.id != songId || state.durationMs <= 0L || state.durationMs == publishedDurationMs) {
            return connectionChanged
        }
        publishedDurationMs = state.durationMs
        return true
    }
}

private data class RemoteSessionSnapshot(
    val isConnected: Boolean,
    val isPlaying: Boolean,
    val volume: Float,
)

internal class RemoteVolumeTracker(
    initialVolume: Float?,
) {
    private var lastAudibleVolume = initialVolume?.takeIf { it > 0f } ?: 1f

    fun observe(volume: Float) {
        if (volume > 0f) lastAudibleVolume = volume
    }

    fun volumeForMutedState(muted: Boolean): Float = if (muted) 0f else lastAudibleVolume.coerceAtLeast(0.01f)
}

@UnstableApi
@Suppress("OVERRIDE_DEPRECATION")
class MediaSessionPlayer(
    private val musicPlayer: AndroidPlaybackPlayer,
    private val repeatMode: MutableStateFlow<Int>,
    private val shuffleModeEnabled: MutableStateFlow<Boolean> = MutableStateFlow(musicPlayer.isShuffleEnabled),
    private val isPlaybackLockedProvider: () -> Boolean = { AndroidListenTogetherManager.isGuestPlaybackLocked },
    private val isPlaybackLockedFlow: StateFlow<Boolean> = AndroidListenTogetherManager.isGuestPlaybackLockedFlow,
    private val remotePlaybackState: StateFlow<CastState>? = null,
    private val onRemotePlaybackChange: (Boolean) -> Unit = {},
    private val onRemoteStop: () -> Unit = {},
    private val onRemoteSeek: (Long) -> Unit = {},
    private val onRemoteVolumeChange: (Float) -> Unit = {},
    private val onQueueChanged: (replacesQueue: Boolean) -> Unit = {},
    private val artworkUri: (String?) -> Uri? = { it?.let(::canonicalArtworkUrl)?.toUri() },
    private val mediaItemResolver: (MediaItem) -> SongItem? = { null },
    currentArtworkUri: (String?) -> Uri? = artworkUri,
    private val automotivePlaybackRequestGate: AutomotivePlaybackRequestGate? = null,
    private val isAutomotiveSongBlocked: (SongItem) -> Boolean = { it.isUnavailable || BlockedContent.isBlocked(it) },
    private val isDeviceConnectFollowing: () -> Boolean = { false },
    private val deviceConnectInterceptor: (DcPlaybackCommand, MusicQueueSnapshot?) -> Boolean = { _, _ -> false },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val awaitLikes: suspend () -> Unit = {},
    private val extendPlaylistBeforeSkip: () -> Boolean = { false },
) : Player {
    companion object {
        const val TAG = "MediaSessionPlayer"
    }

    private val listeners = CopyOnWriteArraySet<Player.Listener>()
    private var publishedPlaybackState: Triple<Boolean, Int, Boolean>? = null
    private val remoteVolumeTracker = RemoteVolumeTracker(remotePlaybackState?.value?.volume)
    private val timelineCache = MediaSessionTimelineCache(artworkUri)
    private var publishedTimeline: Timeline? = null
    private val currentItemCache = MediaSessionItemCache(artworkUri)
    private val metadataItemCache = MediaSessionItemCache(currentArtworkUri)
    private val timelineDurationPublicationTracker = TimelineDurationPublicationTracker()
    private val castTimelineDurationPublicationTracker = CastTimelineDurationPublicationTracker()

    private fun interceptDeviceConnect(
        command: DcPlaybackCommand,
        expectedQueue: MusicQueueSnapshot? = null,
    ): Boolean = deviceConnectInterceptor(command, expectedQueue)

    private var terminalError: PlaybackException? = null

    internal fun setTerminalError(error: PlaybackException?) {
        if (terminalError === error) return
        terminalError = error
        listeners.forEach { listener ->
            listener.onPlayerErrorChanged(error)
            if (error != null) listener.onPlayerError(error)
            listener.onPlaybackStateChanged(playbackState)
            listener.onIsPlayingChanged(isPlaying)
            listener.onEvents(
                this,
                Player.Events(
                    FlagSet
                        .Builder()
                        .add(Player.EVENT_PLAYER_ERROR)
                        .add(Player.EVENT_PLAYBACK_STATE_CHANGED)
                        .add(Player.EVENT_IS_PLAYING_CHANGED)
                        .build(),
                ),
            )
        }
    }

    private val musicPlayerListener =
        object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                listeners.forEach { it.onVideoSizeChanged(videoSize) }
            }

            override fun onRenderedFirstFrame() {
                listeners.forEach { it.onRenderedFirstFrame() }
            }

            override fun onSurfaceSizeChanged(
                width: Int,
                height: Int,
            ) {
                listeners.forEach { it.onSurfaceSizeChanged(width, height) }
            }
        }

    init {
        musicPlayer.addPlayerListener(musicPlayerListener)
        scope.launch {
            combine(musicPlayer.playbackSpeed, musicPlayer.playbackPitch, ::PlaybackParameters)
                .distinctUntilChanged()
                .collect { parameters ->
                    listeners.forEach {
                        it.onPlaybackParametersChanged(parameters)
                        it.onEvents(
                            this@MediaSessionPlayer,
                            Player.Events(FlagSet.Builder().add(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED).build()),
                        )
                    }
                }
        }
        scope.launch {
            repeatMode.collectLatest { mode -> listeners.forEach { it.onRepeatModeChanged(mode) } }
        }
        scope.launch {
            shuffleModeEnabled.collectLatest { enabled -> listeners.forEach { it.onShuffleModeEnabledChanged(enabled) } }
        }
        scope.launch {
            combine(
                musicPlayer.isPlaying,
                musicPlayer.wantsPlaybackState,
                musicPlayer.playbackStateFlow,
                musicPlayer.playbackTransitionPending,
            ) { _, _, _, _ -> Triple(isPlaying, playbackState, playWhenReady) }
                .distinctUntilChanged()
                .collect { state ->
                    val previous = publishedPlaybackState
                    publishedPlaybackState = state
                    listeners.forEach {
                        if (previous?.first != state.first) it.onIsPlayingChanged(state.first)
                        if (previous?.second != state.second) it.onPlaybackStateChanged(state.second)
                        if (previous?.third !=
                            state.third
                        ) {
                            it.onPlayWhenReadyChanged(state.third, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                        }
                    }
                }
        }
        scope.launch {
            musicPlayer.currentSong.collectLatest {
                listeners.forEach {
                    it.onMediaItemTransition(getCurrentMediaItem(), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                    it.onMediaMetadataChanged(getMediaMetadata())
                }
                publishTimeline(Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
            }
        }
        scope.launch {
            merge(
                SongMetadataState.revision.map { Unit },
                PlaybackNetworkStateStore.state
                    .map { it.connected }
                    .distinctUntilChanged()
                    .map { Unit },
                snapshotFlow { Settings.shared.artistNameAliasesJson }.map { Unit },
            ).collectLatest {
                if (musicPlayer.currentSong.value != null) {
                    listeners.forEach { it.onMediaMetadataChanged(getMediaMetadata()) }
                    publishTimeline(Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
                }
            }
        }
        scope.launch {
            awaitLikes()
            combine(musicPlayer.currentSong, SongLikeState.likedSongsFlow) { song, songs ->
                songs.any { it.id == song?.id }
            }.distinctUntilChanged()
                .collect { listeners.forEach { it.onMediaMetadataChanged(getMediaMetadata()) } }
        }
        scope.launch {
            musicPlayer.queueState.collectLatest {
                publishTimeline(Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            }
        }
        scope.launch {
            combine(musicPlayer.currentSong, musicPlayer.duration) { song, durationMs ->
                timelineDurationPublicationTracker.shouldPublish(
                    song,
                    musicPlayer.latestQueuePlaybackRequestId,
                    durationMs,
                )
            }.collectLatest { shouldPublish ->
                if (shouldPublish) publishTimeline(Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
            }
        }
        scope.launch {
            isPlaybackLockedFlow.collectLatest { isLocked ->
                if (isLocked) musicPlayer.cancelNext()
                listeners.forEach { it.onAvailableCommandsChanged(getAvailableCommands()) }
                publishTimeline(Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
            }
        }
        remotePlaybackState?.let { state ->
            scope.launch {
                state
                    .combine(musicPlayer.currentSong) { remoteState, song ->
                        castTimelineDurationPublicationTracker.shouldPublish(song, remoteState)
                    }.collectLatest { shouldPublish ->
                        if (shouldPublish) publishTimeline(Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
                    }
            }
            scope.launch {
                state
                    .map { RemoteSessionSnapshot(it.isConnected, it.isPlaying, it.volume) }
                    .distinctUntilChanged()
                    .collectLatest { remoteState ->
                        remoteVolumeTracker.observe(remoteState.volume)
                        listeners.forEach { listener ->
                            listener.onDeviceInfoChanged(getDeviceInfo())
                            listener.onDeviceVolumeChanged(getDeviceVolume(), isDeviceMuted)
                            listener.onAvailableCommandsChanged(getAvailableCommands())
                            listener.onIsPlayingChanged(isPlaying)
                            listener.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                            listener.onPlaybackStateChanged(playbackState)
                        }
                    }
            }
        }
    }

    private fun publishTimeline(reason: Int) {
        val timeline = getCurrentTimeline()
        // Media3 republishes the Auto queue for every timeline event, including identical metadata revisions.
        if (timeline == publishedTimeline) return
        publishedTimeline = timeline
        listeners.forEach { it.onTimelineChanged(timeline, reason) }
    }

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
    }

    override fun getApplicationLooper(): Looper = Looper.getMainLooper()

    override fun prepare() { }

    override fun play() {
        if (isPlaybackLockedProvider()) return
        if (interceptDeviceConnect(DcPlaybackCommand(DcPlaybackAction.PLAY), expectedQueue = musicPlayer.queueSnapshot())) return
        setTerminalError(null)
        if (remotePlaybackState?.value?.isConnected == true) {
            onRemotePlaybackChange(true)
        } else if (!musicPlayer.retryPendingQueuePlayback()) {
            musicPlayer.resume()
        }
    }

    override fun pause() {
        if (isPlaybackLockedProvider()) return
        if (interceptDeviceConnect(DcPlaybackCommand(DcPlaybackAction.PAUSE))) return
        if (remotePlaybackState?.value?.isConnected == true) onRemotePlaybackChange(false) else musicPlayer.pause()
    }

    override fun stop() {
        if (isPlaybackLockedProvider()) return
        if (interceptDeviceConnect(DcPlaybackCommand(DcPlaybackAction.CLEAR))) return
        if (remotePlaybackState?.value?.isConnected == true) onRemoteStop() else musicPlayer.stop()
    }

    override fun release() {
        musicPlayer.removePlayerListener(musicPlayerListener)
        listeners.clear()
        musicPlayer.release()
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) = seekTo(mediaItemIndex, positionMs, PlaybackSeekSource("media_session", mapOf("command" to "seek_to")))

    private fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
        source: PlaybackSeekSource,
    ) {
        if (isPlaybackLockedProvider()) return
        if (mediaItemIndex !in 0 until mediaItemCount) return
        automotivePlaybackRequestGate?.cancelPendingRequests()
        val position = positionMs.coerceAtLeast(0L)
        val action = if (mediaItemIndex == getCurrentMediaItemIndex()) DcPlaybackAction.SEEK else DcPlaybackAction.SELECT
        if (interceptDeviceConnect(
                DcPlaybackCommand(action, index = mediaItemIndex, positionMs = position),
                expectedQueue = musicPlayer.queueSnapshot(),
            )
        ) {
            return
        }
        if (action == DcPlaybackAction.SELECT) {
            if (remotePlaybackState?.value?.isConnected == true) return
            if (mediaItemIndex in musicPlayer.songQueue.value.indices) {
                musicPlayer.skipToQueueItem(mediaItemIndex, position)
            }
        } else if (remotePlaybackState?.value?.isConnected == true) {
            onRemoteSeek(position)
        } else {
            musicPlayer.seekTo(position, source)
        }
    }

    override fun getPlaybackState(): Int =
        when {
            terminalError != null -> {
                Player.STATE_IDLE
            }

            remotePlaybackState?.value?.isConnected == true -> {
                Player.STATE_READY
            }

            musicPlayer.playbackState == Player.STATE_IDLE && musicPlayer.playbackTransitionPending.value -> {
                if (musicPlayer.wantsPlayback) Player.STATE_BUFFERING else Player.STATE_READY
            }

            else -> {
                musicPlayer.playbackState
            }
        }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    override fun getPlayWhenReady(): Boolean = remotePlaybackState?.value?.takeIf { it.isConnected }?.isPlaying ?: musicPlayer.wantsPlayback

    override fun setRepeatMode(repeatMode: Int) {
        if (isPlaybackLockedProvider()) return
        if (interceptDeviceConnect(
                DcPlaybackCommand(DcPlaybackAction.REPEAT, repeatMode = repeatMode.toDeviceConnectRepeatMode()),
            )
        ) {
            return
        }
        if (this.repeatMode.value == repeatMode) return
        this.repeatMode.value = repeatMode
    }

    override fun getRepeatMode(): Int = repeatMode.value

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        if (isPlaybackLockedProvider()) return
        if (interceptDeviceConnect(DcPlaybackCommand(DcPlaybackAction.SHUFFLE, enabled = shuffleModeEnabled))) return
        if (musicPlayer.isShuffleEnabled == shuffleModeEnabled) return
        musicPlayer.toggleShuffle()
        this.shuffleModeEnabled.value = shuffleModeEnabled
    }

    override fun getShuffleModeEnabled(): Boolean = musicPlayer.isShuffleEnabled

    override fun isLoading(): Boolean = false

    override fun getDuration(): Long = remotePlaybackState?.value?.takeIf { it.isConnected }?.durationMs ?: musicPlayer.duration.value

    override fun getCurrentPosition(): Long =
        remotePlaybackState?.value?.takeIf { it.isConnected }?.positionMs ?: musicPlayer.getRealtimePositionMs()

    override fun getBufferedPosition(): Long =
        remotePlaybackState?.value?.takeIf { it.isConnected }?.positionMs ?: musicPlayer.bufferedPosition.value

    override fun getBufferedPercentage(): Int {
        val dur = duration
        return if (dur > 0L) {
            ((bufferedPosition.toFloat() / dur.toFloat()) * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }
    }

    override fun getTotalBufferedDuration(): Long = (bufferedPosition - currentPosition).coerceAtLeast(0L)

    override fun getPlaybackParameters(): PlaybackParameters =
        PlaybackParameters(musicPlayer.playbackSpeed(), musicPlayer.playbackPitch.value)

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        if (!isPlaybackLockedProvider() &&
            interceptDeviceConnect(DcPlaybackCommand(DcPlaybackAction.SPEED, speed = playbackParameters.speed))
        ) {
            interceptDeviceConnect(DcPlaybackCommand(DcPlaybackAction.PITCH, pitch = playbackParameters.pitch))
            return
        }
        musicPlayer.setTempoPitch(playbackParameters.speed, playbackParameters.pitch)
    }

    override fun getCurrentTimeline(): Timeline {
        val queueState = musicPlayer.queueState.value
        val currentIndex = queueState.currentIndex.takeIf { it in queueState.entries.indices } ?: 0
        return timelineCache.get(
            entries = queueState.entries,
            currentIndex = currentIndex,
            currentDurationMs = duration,
            isSeekable = !isPlaybackLockedProvider(),
            artistAliases = Settings.shared.artistNameAliasesJson,
            fallbackSong = musicPlayer.currentSong.value,
        )
    }

    override fun getCurrentMediaItemIndex(): Int =
        musicPlayer.queueState.value.let { it.currentIndex.takeIf(it.entries.indices::contains) } ?: 0

    override fun getMediaItemCount(): Int =
        musicPlayer.queueState.value.entries.size.coerceAtLeast(
            if (musicPlayer.currentSong.value ==
                null
            ) {
                0
            } else {
                1
            },
        )

    override fun getMediaItemAt(index: Int): MediaItem =
        musicPlayer.queueState.value.entries
            .getOrNull(index)
            ?.let { SongMetadataState.apply(it.song).toMediaItem(it.key, artworkUri) }
            ?: musicPlayer.currentSong.value
                ?.takeIf { index == 0 }
                ?.let(SongMetadataState::apply)
                ?.toMediaItem(artworkUri = artworkUri)
            ?: MediaItem.EMPTY

    override fun getCurrentMediaItem(): MediaItem? {
        val entry =
            musicPlayer.queueState.value.entries
                .getOrNull(getCurrentMediaItemIndex())
        return currentItemCache.get(entry?.song ?: musicPlayer.currentSong.value, entry?.key)
    }

    override fun getMediaMetadata(): MediaMetadata =
        metadataItemCache
            .get(musicPlayer.currentSong.value)
            ?.mediaMetadata
            ?.buildUpon()
            ?.setUserRating(
                ThumbRating(
                    musicPlayer.currentSong.value
                        ?.id
                        ?.let(SongLikeState::isLiked) == true,
                ),
            )?.build() ?: MediaMetadata.EMPTY

    override fun getPlaylistMetadata(): MediaMetadata = MediaMetadata.EMPTY

    override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) { }

    override fun getTrackSelectionParameters(): TrackSelectionParameters = TrackSelectionParameters.DEFAULT

    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) { }

    override fun getCurrentTracks(): Tracks = Tracks.EMPTY

    override fun getCurrentCues(): CueGroup = CueGroup.EMPTY_TIME_ZERO

    override fun getDeviceInfo(): DeviceInfo =
        if (remotePlaybackState?.value?.isConnected == true) {
            DeviceInfo
                .Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                .setMinVolume(0)
                .setMaxVolume(100)
                .build()
        } else {
            DeviceInfo.UNKNOWN
        }

    override fun getDeviceVolume(): Int = ((remotePlaybackState?.value?.volume ?: 0f) * 100).toInt().coerceIn(0, 100)

    override fun isDeviceMuted(): Boolean = remotePlaybackState?.value?.isConnected == true && getDeviceVolume() == 0

    override fun setDeviceVolume(
        volume: Int,
        flags: Int,
    ) {
        if (remotePlaybackState?.value?.isConnected != true) return
        val normalized = volume.coerceIn(0, 100) / 100f
        remoteVolumeTracker.observe(normalized)
        onRemoteVolumeChange(normalized)
    }

    override fun setDeviceMuted(
        muted: Boolean,
        flags: Int,
    ) {
        setDeviceVolume((remoteVolumeTracker.volumeForMutedState(muted) * 100).toInt(), flags)
    }

    override fun getAudioAttributes(): AudioAttributes = AudioAttributes.DEFAULT

    override fun getVideoSize(): VideoSize = musicPlayer.getVideoSize()

    override fun setVideoSurface(surface: Surface?) {
        musicPlayer.setVideoSurface(surface)
    }

    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        musicPlayer.setVideoSurface(surfaceHolder)
    }

    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        musicPlayer.clearVideoSurface(surfaceHolder)
    }

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
        musicPlayer.setVideoSurface(surfaceView)
    }

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
        musicPlayer.clearVideoSurface(surfaceView)
    }

    override fun setVideoTextureView(textureView: TextureView?) {
        musicPlayer.setVideoSurface(textureView)
    }

    override fun clearVideoTextureView(textureView: TextureView?) {
        musicPlayer.clearVideoSurface(textureView)
    }

    override fun clearVideoSurface() {
        musicPlayer.clearVideoSurface(null)
    }

    override fun clearVideoSurface(surface: Surface?) {
        musicPlayer.clearVideoSurface(surface)
    }

    override fun getCurrentPeriodIndex(): Int = getCurrentMediaItemIndex()

    override fun getNextMediaItemIndex(): Int {
        val index = getCurrentMediaItemIndex()
        val lastIndex = musicPlayer.songQueue.value.lastIndex
        return when {
            index < lastIndex -> index + 1
            repeatMode.value == Player.REPEAT_MODE_ALL && lastIndex >= 0 -> 0
            else -> C.INDEX_UNSET
        }
    }

    override fun getPreviousMediaItemIndex(): Int {
        val index = getCurrentMediaItemIndex()
        val lastIndex = musicPlayer.songQueue.value.lastIndex
        return when {
            index > 0 -> index - 1
            repeatMode.value == Player.REPEAT_MODE_ALL && lastIndex >= 0 -> lastIndex
            else -> C.INDEX_UNSET
        }
    }

    override fun hasNextMediaItem(): Boolean = getNextMediaItemIndex() != C.INDEX_UNSET

    override fun hasPreviousMediaItem(): Boolean = getPreviousMediaItemIndex() != C.INDEX_UNSET

    override fun seekToNext() {
        if (isPlaybackLockedProvider()) return
        if (interceptDeviceConnect(DcPlaybackCommand(DcPlaybackAction.NEXT))) return
        if (extendPlaylistBeforeSkip()) return
        val mode = repeatMode.value
        val index = musicPlayer.currentMediaItemIndex.value
        val lastIndex = musicPlayer.songQueue.value.lastIndex
        if (mode == Player.REPEAT_MODE_ALL && index == lastIndex && lastIndex >= 0) {
            musicPlayer.skipToQueueItem(0, resumePlayback = musicPlayer.wantsPlayback)
        } else {
            musicPlayer.skipToNext()
        }
    }

    override fun seekToPrevious() {
        if (isPlaybackLockedProvider()) return
        if (interceptDeviceConnect(DcPlaybackCommand(DcPlaybackAction.PREVIOUS))) return
        val remoteState = remotePlaybackState?.value?.takeIf { it.isConnected }
        if (remoteState == null) {
            musicPlayer.skipToPrevious()
        } else if (shouldRestartCurrentItem(remoteState.positionMs, musicPlayer.currentMediaItemIndex.value)) {
            onRemoteSeek(0L)
        } else {
            seekToPreviousMediaItem()
        }
    }

    override fun seekToNextMediaItem() {
        if (isPlaybackLockedProvider()) return
        if (interceptDeviceConnect(DcPlaybackCommand(DcPlaybackAction.NEXT))) return
        if (extendPlaylistBeforeSkip()) return
        getNextMediaItemIndex().takeUnless { it == C.INDEX_UNSET }?.let {
            musicPlayer.skipToQueueItem(it, resumePlayback = musicPlayer.wantsPlayback)
        }
    }

    override fun seekToPreviousMediaItem() {
        if (isPlaybackLockedProvider()) return
        if (interceptDeviceConnect(DcPlaybackCommand(DcPlaybackAction.PREVIOUS))) return
        getPreviousMediaItemIndex().takeUnless { it == C.INDEX_UNSET }?.let {
            musicPlayer.skipToQueueItem(it, resumePlayback = musicPlayer.wantsPlayback)
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (isPlaybackLockedProvider()) return
        if (interceptDeviceConnect(DcPlaybackCommand(DcPlaybackAction.SPEED, speed = speed))) return
        musicPlayer.setPlaybackSpeed(speed)
    }

    override fun setVolume(volume: Float) {
        musicPlayer.setVolume(volume)
    }

    override fun getVolume(): Float = musicPlayer.volume.value

    override fun getAvailableCommands(): Player.Commands {
        val builder =
            Player.Commands
                .Builder()
                .addAll(
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_SET_VIDEO_SURFACE,
                    Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                    Player.COMMAND_GET_VOLUME,
                    Player.COMMAND_SET_VOLUME,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_DEVICE_VOLUME,
                )
        if (remotePlaybackState?.value?.isConnected == true) {
            builder.addAll(
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            )
        }
        if (!isPlaybackLockedProvider()) {
            if (remotePlaybackState?.value?.isConnected != true) builder.add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            builder.addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_PREPARE,
            )
        }
        return builder.build()
    }

    override fun isCommandAvailable(command: Int): Boolean = getAvailableCommands().contains(command)

    override fun isCurrentMediaItemDynamic(): Boolean = false

    override fun isCurrentMediaItemLive(): Boolean = false

    override fun isCurrentMediaItemSeekable(): Boolean = !isPlaybackLockedProvider() && musicPlayer.currentSong.value != null

    override fun isPlayingAd(): Boolean = false

    override fun isCurrentWindowSeekable(): Boolean = !isPlaybackLockedProvider() && musicPlayer.currentSong.value != null

    override fun getCurrentAdGroupIndex(): Int = -1

    override fun getCurrentAdIndexInAdGroup(): Int = -1

    override fun getContentDuration(): Long = getDuration()

    override fun getContentPosition(): Long = getCurrentPosition()

    override fun getContentBufferedPosition(): Long = getBufferedPosition()

    override fun setDeviceVolume(volume: Int) {
        setDeviceVolume(volume, 0)
    }

    override fun setDeviceMuted(muted: Boolean) {
        setDeviceMuted(muted, 0)
    }

    override fun seekToDefaultPosition() {
        if (isPlaybackLockedProvider()) return
        seekTo(getCurrentMediaItemIndex(), 0, PlaybackSeekSource("media_session", mapOf("command" to "seek_to_default")))
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (isPlaybackLockedProvider()) return
        seekTo(mediaItemIndex, 0, PlaybackSeekSource("media_session", mapOf("command" to "seek_to_default")))
    }

    override fun seekTo(positionMs: Long) {
        if (isPlaybackLockedProvider()) return
        seekTo(getCurrentMediaItemIndex(), positionMs)
    }

    private fun resolveMediaItem(mediaItem: MediaItem): SongItem? {
        val entries = musicPlayer.queueState.value.entries
        AutomotiveMediaIds.queueSong(mediaItem.mediaId)?.let { queued ->
            return entries.firstOrNull { it.key == queued.entryKey && it.song.id == queued.songId }?.song
        }
        val songId =
            AutomotiveMediaIds.songId(mediaItem.mediaId)
                ?: mediaItem.mediaId.takeUnless { it.startsWith("auto:") }
        return songId?.let { id -> entries.firstOrNull { it.song.id == id }?.song } ?: mediaItemResolver(mediaItem)
    }

    private fun resolveMediaItems(mediaItems: List<MediaItem>): List<SongItem>? {
        val songs = mediaItems.mapNotNull(::resolveMediaItem)
        if (songs.size == mediaItems.size) return songs
        Timber.tag(TAG).w("Ignoring media-item mutation with unresolved items")
        return null
    }

    private fun applyQueueSelection(
        mediaItems: List<MediaItem>,
        positionMs: Long,
        selectForAutomotivePlayback: Boolean,
    ): Boolean {
        val mediaId = mediaItems.singleOrNull()?.mediaId ?: return false
        val queueState = musicPlayer.queueState.value
        val selectedIndex = automotiveQueueIndex(mediaId, queueState.entries) ?: return false
        if (selectForAutomotivePlayback || selectedIndex != queueState.currentIndex) {
            seekTo(selectedIndex, positionMs)
            return true
        }
        if (musicPlayer.currentPosition.value != positionMs) seekTo(positionMs)
        musicPlayer.resume()
        return true
    }

    private fun reorderExistingQueue(mediaItems: List<MediaItem>): Boolean {
        val requested = mediaItems.map { AutomotiveMediaIds.queueSong(it.mediaId) ?: return false }
        val entries = musicPlayer.queueState.value.entries
        if (requested.size != entries.size || requested.map { it.entryKey }.toSet() != entries.map { it.key }.toSet()) return false
        val entriesByKey = entries.associateBy(MusicQueueEntry::key)
        if (requested.any { entriesByKey[it.entryKey]?.song?.id != it.songId }) return false
        // shortcut: Moves use indexes; use keyed batches if large Auto queues make reordering slow.
        requested.forEachIndexed { targetIndex, item ->
            val currentIndex =
                musicPlayer.queueState.value.entries
                    .indexOfFirst { it.key == item.entryKey }
            if (currentIndex != targetIndex) musicPlayer.moveQueueItem(currentIndex, targetIndex)
        }
        return true
    }

    private fun setResolvedMediaItems(
        mediaItems: List<MediaItem>,
        songs: List<SongItem>,
        startIndex: Int,
        startPositionMs: Long,
        automotiveSelection: AutomotiveMediaItemsApplication.Apply?,
    ) {
        val playableIndices =
            if (automotiveSelection == null) songs.indices.toList() else songs.indices.filterNot { isAutomotiveSongBlocked(songs[it]) }
        val playableStartIndex = playableIndices.indexOf(startIndex)
        if (automotiveSelection != null && playableStartIndex < 0) return
        val playableMediaItems = if (automotiveSelection == null) mediaItems else playableIndices.map(mediaItems::get)
        val playableSongs = if (automotiveSelection == null) songs else playableIndices.map(songs::get)
        if (playableSongs.isEmpty()) {
            clearMediaItems()
            return
        }
        val appliedStartIndex =
            if (automotiveSelection?.shuffleModeEnabled == true) {
                playableSongs.indices.random()
            } else if (automotiveSelection == null) {
                startIndex
            } else {
                playableStartIndex
            }
        if (applyQueueSelection(playableMediaItems, startPositionMs, automotiveSelection != null)) return
        val stateBeforeReorder = musicPlayer.queueState.value
        if (reorderExistingQueue(playableMediaItems)) {
            if (musicPlayer.queueState.value != stateBeforeReorder) onQueueChanged(false)
            if (musicPlayer.currentMediaItemIndex.value != appliedStartIndex) {
                musicPlayer.skipToQueueItem(appliedStartIndex, startPositionMs)
            } else {
                if (musicPlayer.currentPosition.value != startPositionMs) seekTo(startPositionMs)
                musicPlayer.resume()
            }
            return
        }
        val replaceQueue = {
            val previousState = musicPlayer.queueState.value
            musicPlayer.setQueue(
                songs = playableSongs,
                startIndex = appliedStartIndex,
                startPlaying = true,
                startPositionMs = startPositionMs,
                preparePlayback = true,
            )
            if (automotiveSelection?.replacesQueue != true && musicPlayer.queueState.value != previousState) {
                onQueueChanged(true)
            }
        }
        if (automotiveSelection?.replacesQueue == true) {
            automotivePlaybackRequestGate?.replaceQueue(automotiveSelection, playableSongs, appliedStartIndex, replaceQueue)
        } else {
            replaceQueue()
        }
    }

    override fun addMediaItem(mediaItem: MediaItem) {
        addMediaItems(mutableListOf(mediaItem))
    }

    override fun addMediaItem(
        index: Int,
        mediaItem: MediaItem,
    ) {
        addMediaItems(index, mutableListOf(mediaItem))
    }

    override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
        addMediaItems(musicPlayer.songQueue.value.size, mediaItems)
    }

    override fun addMediaItems(
        index: Int,
        mediaItems: MutableList<MediaItem>,
    ) {
        if (isPlaybackLockedProvider()) return
        resolveMediaItems(mediaItems)?.filterNot(isAutomotiveSongBlocked)?.takeIf { it.isNotEmpty() }?.let { songs ->
            mutateQueueItems(replacesQueue = musicPlayer.currentSong.value == null) { insertResolvedMediaItems(index, songs) }
        }
    }

    private fun insertResolvedMediaItems(
        index: Int,
        songs: List<SongItem>,
        startPlaying: Boolean = true,
    ): Boolean {
        if (musicPlayer.currentSong.value != null && musicPlayer.songQueue.value.isNotEmpty()) {
            musicPlayer.insertQueueItems(index, songs)
            return false
        }
        musicPlayer.setQueue(songs.map(SongItem::asManuallyAddedQueueItem), startPlaying = startPlaying, preparePlayback = true)
        return true
    }

    private inline fun mutateQueueItems(
        replacesQueue: Boolean = false,
        mutation: () -> Unit,
    ) {
        val previousState = musicPlayer.queueState.value
        mutation()
        val currentState = musicPlayer.queueState.value
        if (currentState == previousState) return
        onQueueChanged(replacesQueue || previousState.entries.isEmpty())
        if (previousState.entries.isNotEmpty() && currentState.entries.isEmpty() && remotePlaybackState?.value?.isConnected == true) {
            onRemoteStop()
        }
    }

    override fun moveMediaItem(
        currentIndex: Int,
        newIndex: Int,
    ) {
        if (isPlaybackLockedProvider()) return
        if (interceptDeviceConnect(
                DcPlaybackCommand(DcPlaybackAction.MOVE, index = currentIndex, toIndex = newIndex),
                expectedQueue = musicPlayer.queueSnapshot(),
            )
        ) {
            return
        }
        mutateQueueItems { musicPlayer.moveQueueItem(currentIndex, newIndex) }
    }

    override fun moveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int,
    ) {
        if (isPlaybackLockedProvider() || isDeviceConnectFollowing()) return
        val itemCount = musicPlayer.songQueue.value.size
        val movedCount = toIndex - fromIndex
        if (fromIndex !in 0..itemCount || toIndex !in fromIndex..itemCount || newIndex !in 0..(itemCount - movedCount)) return
        mutateQueueItems {
            if (newIndex < fromIndex) {
                repeat(movedCount) { offset -> musicPlayer.moveQueueItem(fromIndex + offset, newIndex + offset) }
            } else if (newIndex > fromIndex) {
                for (offset in movedCount - 1 downTo 0) {
                    musicPlayer.moveQueueItem(fromIndex + offset, newIndex + offset)
                }
            }
        }
    }

    override fun removeMediaItem(index: Int) {
        if (isPlaybackLockedProvider()) return
        if (interceptDeviceConnect(
                DcPlaybackCommand(DcPlaybackAction.REMOVE, index = index),
                expectedQueue = musicPlayer.queueSnapshot(),
            )
        ) {
            return
        }
        mutateQueueItems { musicPlayer.removeQueueItem(index) }
    }

    override fun removeMediaItems(
        fromIndex: Int,
        toIndex: Int,
    ) {
        if (isPlaybackLockedProvider() || isDeviceConnectFollowing()) return
        mutateQueueItems { musicPlayer.removeQueueItems((fromIndex until toIndex).toSet()) }
    }

    override fun clearMediaItems() {
        if (isPlaybackLockedProvider()) return
        if (interceptDeviceConnect(DcPlaybackCommand(DcPlaybackAction.CLEAR))) return
        mutateQueueItems {
            musicPlayer.removeQueueItems(
                musicPlayer.songQueue.value.indices
                    .toSet(),
            )
        }
        shuffleModeEnabled.value = false
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        setMediaItems(mutableListOf(mediaItem))
    }

    override fun setMediaItem(
        mediaItem: MediaItem,
        startPositionMs: Long,
    ) {
        setMediaItems(mutableListOf(mediaItem), 0, startPositionMs)
    }

    override fun setMediaItem(
        mediaItem: MediaItem,
        resetPosition: Boolean,
    ) {
        setMediaItems(mutableListOf(mediaItem), resetPosition)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        setMediaItems(mediaItems, true)
    }

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        resetPosition: Boolean,
    ) {
        if (isPlaybackLockedProvider()) return
        val currentIndex = musicPlayer.currentMediaItemIndex.value
        val retainPosition = !resetPosition && currentIndex in mediaItems.indices
        val startIndex = currentIndex.takeIf { retainPosition } ?: 0
        val startPositionMs = getCurrentPosition().coerceAtLeast(0L).takeIf { retainPosition } ?: 0L
        val automotiveApplication = automotivePlaybackRequestGate?.consume(mediaItems, startIndex, startPositionMs)
        if (automotiveApplication == AutomotiveMediaItemsApplication.Reject) return
        val automotiveSelection = automotiveApplication as? AutomotiveMediaItemsApplication.Apply
        val resolvedMediaItems = automotiveSelection?.mediaItems ?: mediaItems
        val songs = resolveMediaItems(resolvedMediaItems) ?: return
        setResolvedMediaItems(resolvedMediaItems, songs, startIndex, startPositionMs, automotiveSelection)
    }

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) {
        if (isPlaybackLockedProvider()) return
        if (startIndex == C.INDEX_UNSET) {
            setMediaItems(mediaItems, false)
            return
        }
        val positionMs = startPositionMs.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        val automotiveApplication = automotivePlaybackRequestGate?.consume(mediaItems, startIndex, positionMs)
        if (automotiveApplication == AutomotiveMediaItemsApplication.Reject) return
        val automotiveSelection = automotiveApplication as? AutomotiveMediaItemsApplication.Apply
        val resolvedMediaItems = automotiveSelection?.mediaItems ?: mediaItems
        val songs = resolveMediaItems(resolvedMediaItems) ?: return
        if (songs.isNotEmpty() && startIndex !in songs.indices) {
            throw IllegalSeekPositionException(Timeline.EMPTY, startIndex, startPositionMs)
        }
        setResolvedMediaItems(resolvedMediaItems, songs, startIndex, positionMs, automotiveSelection)
    }

    override fun replaceMediaItem(
        index: Int,
        mediaItem: MediaItem,
    ) {
        replaceMediaItems(index, index + 1, listOf(mediaItem))
    }

    override fun replaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>,
    ) {
        if (isPlaybackLockedProvider() || isDeviceConnectFollowing()) return
        val replacements = resolveMediaItems(mediaItems) ?: return
        val itemCount = musicPlayer.songQueue.value.size
        if (fromIndex !in 0..itemCount || toIndex !in fromIndex..itemCount) return
        val replacesCurrent = musicPlayer.currentMediaItemIndex.value in fromIndex until toIndex
        val wantsPlayback = musicPlayer.wantsPlayback
        mutateQueueItems(replacesQueue = fromIndex == 0 && toIndex == itemCount) {
            musicPlayer.removeQueueItems((fromIndex until toIndex).toSet())
            val replacedEmptyQueue = replacements.isNotEmpty() && insertResolvedMediaItems(fromIndex, replacements, wantsPlayback)
            if (replacesCurrent && replacements.isNotEmpty() && !replacedEmptyQueue) {
                musicPlayer.skipToQueueItem(fromIndex.coerceAtMost(musicPlayer.songQueue.value.lastIndex))
            }
        }
    }

    override fun canAdvertiseSession(): Boolean = true

    override fun getPlaybackSuppressionReason(): Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE

    override fun isPlaying(): Boolean =
        terminalError == null && (remotePlaybackState?.value?.takeIf { it.isConnected }?.isPlaying ?: musicPlayer.isPlaying.value)

    override fun getPlayerError(): PlaybackException? = terminalError

    override fun getSeekBackIncrement(): Long = Settings.shared.seekDurationSeconds * 1000L

    override fun seekBack() {
        seekTo(
            getCurrentMediaItemIndex(),
            maxOf(0, getCurrentPosition() - getSeekBackIncrement()),
            PlaybackSeekSource("media_session", mapOf("command" to "seek_back")),
        )
    }

    override fun getSeekForwardIncrement(): Long = Settings.shared.seekDurationSeconds * 1000L

    override fun seekForward() {
        val position = getCurrentPosition() + getSeekForwardIncrement()
        seekTo(
            getCurrentMediaItemIndex(),
            getDuration().takeIf { it > 0L }?.let { minOf(it, position) } ?: position,
            PlaybackSeekSource("media_session", mapOf("command" to "seek_forward")),
        )
    }

    override fun getMaxSeekToPreviousPosition(): Long = 3000L

    override fun getCurrentManifest(): Any? = null

    override fun getCurrentWindowIndex(): Int = getCurrentMediaItemIndex()

    override fun getNextWindowIndex(): Int = getNextMediaItemIndex()

    override fun getPreviousWindowIndex(): Int = getPreviousMediaItemIndex()

    override fun isCurrentWindowDynamic(): Boolean = false

    override fun isCurrentWindowLive(): Boolean = false

    override fun getCurrentLiveOffset(): Long = 0L

    override fun mute() {
        volume = 0f
    }

    override fun unmute() {
        volume = 1f
    }

    override fun getSurfaceSize(): androidx.media3.common.util.Size = androidx.media3.common.util.Size.UNKNOWN

    override fun increaseDeviceVolume() {
        setDeviceVolume(getDeviceVolume() + 5, 0)
    }

    override fun increaseDeviceVolume(flags: Int) {
        setDeviceVolume(getDeviceVolume() + 5, flags)
    }

    override fun decreaseDeviceVolume() {
        setDeviceVolume(getDeviceVolume() - 5, 0)
    }

    override fun decreaseDeviceVolume(flags: Int) {
        setDeviceVolume(getDeviceVolume() - 5, flags)
    }

    override fun setAudioAttributes(
        audioAttributes: AudioAttributes,
        handleAudioFocus: Boolean,
    ) { }
}
