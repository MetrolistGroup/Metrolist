package com.metrolist.shared.playback

import androidx.compose.runtime.staticCompositionLocalOf
import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.model.RadioQueueChip
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.MusicVideoQuality
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.flow.StateFlow

interface PlayerSession {
    val isConnected: StateFlow<Boolean>
    val currentSong: StateFlow<SongItem?>
    val isPlaying: StateFlow<Boolean>
    val wantsPlaybackState: StateFlow<Boolean> get() = isPlaying
    val isReconnecting: StateFlow<Boolean>
    val isPlaybackLoading: StateFlow<Boolean>
    val volume: StateFlow<Float>
    val playbackSpeedFlow: StateFlow<Float>
    val currentPosition: StateFlow<Long>
    val bufferedPosition: StateFlow<Long>
    val duration: StateFlow<Long>
    val progress: StateFlow<Float>
    val bufferedProgress: StateFlow<Float>
    val shuffleModeEnabled: StateFlow<Boolean>

    /** Media3 repeat mode constants: 0 = off, 1 = one, 2 = all. */
    val repeatMode: StateFlow<Int>
    val queueSongs: StateFlow<List<SongItem>>
    val queueState: StateFlow<MusicQueueState>
    val currentMediaItemIndex: StateFlow<Int>
    val isQueueLoading: StateFlow<Boolean>
    val queueTitle: StateFlow<String?>
    val queueChips: StateFlow<List<RadioQueueChip>>
    val isVideoModeActive: StateFlow<Boolean>
    val isVideoModeResolving: StateFlow<Boolean>
    val isVideoViewerVisible: StateFlow<Boolean>
    val currentPlaybackResource: StateFlow<PlaybackResource?>
    val sleepTimerState: StateFlow<SleepTimerState>
    val castDevices: StateFlow<List<CastDevice>>
    val castState: StateFlow<CastState>

    fun currentPositionMs(): Long

    fun togglePlayPause()

    fun seekTo(position: Long)

    fun seekTo(
        position: Long,
        source: PlaybackSeekSource,
    ) {
        source.record(currentPositionMs(), position, currentSong.value?.id)
        seekTo(position)
    }

    fun setVolume(volume: Float)

    fun setPlaybackSpeed(speed: Float)

    fun skipToNext()

    fun skipToPrevious()

    fun skipToQueueEntry(entryKey: Long)

    fun cycleRepeatMode()

    fun toggleShuffle()

    fun startRadio(song: SongItem)

    fun selectQueueChip(chipId: String)

    fun addQueueEntry(entryKey: Long)

    fun addToQueue(
        song: SongItem,
        playNext: Boolean = false,
    )

    fun playNextQueueEntry(entryKey: Long)

    fun queueSong(entryKey: Long): SongItem?

    fun queueSongs(entryKeys: Set<Long>): List<SongItem>

    fun removeQueueEntry(entryKey: Long): QueueRemoval?

    fun removeQueueEntries(entryKeys: Set<Long>): QueueRemoval?

    fun pendingQueueRemovals(): List<QueueRemoval>

    fun discardQueueUndo(removals: List<QueueRemoval>)

    fun restoreQueueItems(removals: List<QueueRemoval>)

    fun moveQueueEntry(
        fromEntryKey: Long,
        toEntryKey: Long,
        updatedItem: SongItem? = null,
    ): Boolean

    fun reorderQueueEntries(entryKeys: List<Long>): Boolean

    fun onSongDisliked(songId: String)

    fun toggleDislike(song: SongItem): Boolean {
        val disliked = SongLikeState.toggleDislike(song)
        if (disliked) onSongDisliked(song.id)
        return disliked
    }

    fun blockArtist(artistId: String)

    fun toggleVideoMode()

    fun setVideoMode(wantVideo: Boolean)

    fun changeMusicVideoResolution(quality: MusicVideoQuality)

    fun startSleepTimer(
        durationMs: Long,
        fadeOut: Boolean,
        finishCurrentSong: Boolean = false,
    )

    fun stopAfterCurrentSong(fadeOut: Boolean)

    fun cancelSleepTimer()

    fun startCastDiscovery()

    fun stopCastDiscovery()

    fun castTo(device: CastDevice)

    fun disconnectCast()
}

val LocalPlayerSession = staticCompositionLocalOf<PlayerSession?> { null }
