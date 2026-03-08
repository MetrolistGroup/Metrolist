package com.metrolist.android.playback

import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.metrolist.shared.deviceconnect.DcLocalPlaybackState
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.playback.MusicQueueState
import com.metrolist.shared.playback.PlaybackFailure
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.playback.QueuePlaybackRequest
import com.metrolist.shared.playback.QueueRemoval
import com.metrolist.shared.playback.SeekableMediaSource
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class AndroidPlaybackRecoveryRequest(
    val transitionId: Long,
    val failure: PlaybackFailure,
    val freshAudioSession: Boolean = false,
)

interface AndroidPlaybackPlayer {
    val currentSong: StateFlow<SongItem?>
    val isPlaying: StateFlow<Boolean>
    val wantsPlayback: Boolean
    val wantsPlaybackState: StateFlow<Boolean>
    val volume: StateFlow<Float>
    val playbackSpeed: StateFlow<Float>
    val playbackPitch: StateFlow<Float>
    val currentPosition: StateFlow<Long>
    val nextPlaybackPositionMs: Long
    val nextPlaybackDurationMs: Long
    val isNextPlaybackRendering: Boolean
    val bufferedPosition: StateFlow<Long>
    val duration: StateFlow<Long>
    val currentPlaybackResource: StateFlow<PlaybackResource?>
    val playbackStateFlow: StateFlow<Int>
    val playbackTransitionPending: StateFlow<Boolean>
    val songQueue: StateFlow<List<SongItem>>
    val queueSnapshots: StateFlow<MusicQueueSnapshot>
    val queueState: StateFlow<MusicQueueState>
    val currentMediaItemIndex: StateFlow<Int>
    val queuePlaybackRequests: SharedFlow<QueuePlaybackRequest>
    val latestQueuePlaybackRequestId: Long
    val onEOFPlaybackInstance: SharedFlow<Long>
    val streamFailure: SharedFlow<AndroidPlaybackRecoveryRequest>
    val shuffleEnabled: StateFlow<Boolean>
    val isShuffleEnabled: Boolean get() = shuffleEnabled.value
    val playbackInstanceId: StateFlow<Long>
    val currentPlaybackInstanceId: Long
    val loadError: StateFlow<String?>
    var videoEnabled: Boolean
    var videoFrameListener: ((Any) -> Unit)?

    fun play(
        song: SongItem,
        url: String,
        startPlaying: Boolean = true,
    )

    fun play(
        song: SongItem,
        resource: PlaybackResource,
        startPlaying: Boolean = true,
    )

    fun setControllerPlayback(playback: DcLocalPlaybackState?)

    fun pause()

    fun resume()

    fun stop()

    fun seekTo(position: Long)

    fun seekTo(
        position: Long,
        source: PlaybackSeekSource,
    ) {
        seekTo(position)
    }

    fun setVolume(level: Float)

    fun setSleepTimerGain(gain: Float)

    fun applyAudioPreferences()

    fun setTransitionVolume(level: Float)

    fun setNextVolume(level: Float)

    fun setCrossfadeVolumes(
        outgoingLevel: Float,
        incomingLevel: Float,
    ) {
        setTransitionVolume(outgoingLevel)
        setNextVolume(incomingLevel)
    }

    fun startNextPlayback(): Boolean

    fun holdNextPlaybackAtStart()

    fun resumeNextPlayback()

    fun isPreloadedPlaybackActive(token: PlaybackPreloadToken): Boolean

    fun setAudioOutputEnabled(enabled: Boolean)

    fun setExternalReportingEnabled(enabled: Boolean)

    fun playbackSpeed(): Float

    fun setPlaybackSpeed(speed: Float)

    fun setPlaybackPitch(pitch: Float)

    fun setTempoPitch(
        speed: Float,
        pitch: Float,
    ) {
        setPlaybackSpeed(speed)
        setPlaybackPitch(pitch)
    }

    fun setQueue(
        songs: List<SongItem>,
        startIndex: Int = 0,
        startPlaying: Boolean = false,
        startPositionMs: Long = 0L,
        preparePlayback: Boolean = startPlaying,
        recommendationsSongsOnly: Boolean = false,
    )

    fun insertQueueItems(
        index: Int,
        songs: List<SongItem>,
    )

    fun appendPlaylistSongs(songs: List<SongItem>)

    fun queueSnapshot(): MusicQueueSnapshot

    fun restoreQueue(
        snapshot: MusicQueueSnapshot,
        currentTrackChanged: Boolean = false,
    )

    val playbackState: Int

    val isReady: Boolean

    fun addToQueue(
        song: SongItem,
        playNext: Boolean = false,
    )

    fun addToQueue(
        songs: List<SongItem>,
        playNext: Boolean = false,
    )

    fun removeQueueItem(index: Int)

    fun removeQueueItems(indices: Set<Int>): QueueRemoval?

    fun restoreQueueItems(removal: QueueRemoval)

    fun moveQueueItem(
        fromIndex: Int,
        toIndex: Int,
        updatedItem: SongItem? = null,
    )

    fun reorderQueue(order: List<Int>)

    fun updateQueueItems(updates: Map<Int, SongItem>)

    fun replaceUpcomingQueue(songs: List<SongItem>)

    var onPreloadedPlaybackCommitted: ((SongItem, PlaybackResource) -> Unit)?
    var consumePreloadedNext: (() -> Boolean)?
    var startPreloadedNextAtEof: ((Long) -> Unit)?

    fun skipToNext(resumePlayback: Boolean = wantsPlayback)

    fun skipToPrevious(resumePlayback: Boolean = wantsPlayback)

    fun skipToQueueItem(
        index: Int,
        positionMs: Long = 0L,
        resumePlayback: Boolean = true,
    )

    fun toggleShuffle(preserveHistory: Boolean = true): Boolean

    fun restoreShuffleMode(enabled: Boolean)

    fun setPreferredDevice(device: Any?)

    fun getAudioSessionId(): Int

    fun getRealtimePositionMs(): Long

    fun setVideoSurface(surface: Any?)

    fun clearVideoSurface(surface: Any? = null)

    fun getVideoSize(): VideoSize

    fun addPlayerListener(listener: Player.Listener)

    fun removePlayerListener(listener: Player.Listener)

    fun release()

    fun retryPendingQueuePlayback(
        positionMs: Long = getRealtimePositionMs(),
        resumePlayback: Boolean = true,
    ): Boolean

    fun setPendingPlaybackIntent(
        requestId: Long,
        resumePlayback: Boolean,
    )

    fun finishPlaybackTransition(requestId: Long)

    fun cancelPendingQueuePlayback()

    fun finishPlaybackRecovery(transitionId: Long)

    fun finishInterruptedPlayback(
        playbackInstanceId: Long,
        positionMs: Long,
    )

    suspend fun prepareAndPlay(
        song: SongItem,
        resource: PlaybackResource,
        positionMs: Long = 0L,
        resumePlayback: Boolean = true,
        shouldCommit: () -> Boolean = { true },
        onPrepareFailure: (Throwable?) -> Unit = {},
        recoveryPlaybackInstanceId: Long? = null,
        replaceCurrentQueueItem: Boolean = false,
        positionMsProvider: (() -> Long)? = null,
        playWhenReadyProvider: (() -> Boolean)? = null,
        replaceInForeground: Boolean = false,
        freshAudioSession: Boolean = false,
        stageQueueReplacement: Boolean = false,
        seekSource: PlaybackSeekSource? = null,
    ): Boolean

    suspend fun prepareNextTrack(
        resource: PlaybackResource,
        expectedCurrentPlaybackInstanceId: Long = currentPlaybackInstanceId,
        expectedCurrentVideoId: String? = currentPlaybackResource.value?.videoId,
        startPaused: Boolean = false,
        queueSongId: String = resource.videoId,
        positionMs: Long = 0L,
        onFailure: (Throwable) -> Unit = {},
    ): PlaybackPreloadToken?

    fun swapToPreloadedQueueNext(
        token: PlaybackPreloadToken,
        resource: PlaybackResource,
        song: SongItem? = null,
        transitionGain: Float = 1f,
    ): Boolean

    fun cancelNext()

    fun invalidateActiveStreamForNetworkChange()

    suspend fun refreshPlaybackResource(wantVideo: Boolean): Boolean

    fun createSabrVideoSource(resource: PlaybackResource): SeekableMediaSource?

    data class PlaybackPreloadToken(
        val generation: Long,
        val playerInstanceId: Long,
        val videoId: String,
        val queueSongId: String = videoId,
    )
}

internal fun isPreloadedQueueTargetChanged(
    token: AndroidPlaybackPlayer.PlaybackPreloadToken?,
    queueSongId: String?,
): Boolean = token != null && token.queueSongId != queueSongId
