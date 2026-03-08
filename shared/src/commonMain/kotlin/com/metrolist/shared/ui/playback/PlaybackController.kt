package com.metrolist.shared.ui.playback

import androidx.compose.runtime.staticCompositionLocalOf
import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.model.RadioQueueChip
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.playback.QueueRemoval
import com.metrolist.shared.playback.SleepTimerState
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class PlaybackError {
    STREAM_UNAVAILABLE,
}

data class PlaybackUiState(
    val currentSong: SongItem? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<SongItem> = emptyList(),
    val currentQueueIndex: Int = -1,
    val isShuffled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isLoading: Boolean = false,
    val isReconnecting: Boolean = false,
    val error: PlaybackError? = null,
    val queueTitle: String? = null,
    val queueChips: List<RadioQueueChip> = emptyList(),
    val isQueueLoading: Boolean = false,
    val suggestedTracks: List<SongItem> = emptyList(),
    val sleepTimerState: SleepTimerState = SleepTimerState(),
    val isVideoModeActive: Boolean = false,
    val isVideoAvailable: Boolean = false,
    val isVideoResolving: Boolean = false,
    val castState: CastState = CastState(),
    val castDevices: List<CastDevice> = emptyList(),
    val bufferedPositionMs: Long = 0L,
    val isAudioProcessingAvailable: Boolean = true,
    val playbackSpeed: Float = 1f,
    val playbackResource: PlaybackResource? = null,
    val playbackPitch: Float = 1f,
    val isPitchProcessingAvailable: Boolean = true,
) {
    val currentPlaybackResource: PlaybackResource?
        get() = playbackResource?.takeIf { it.videoId == currentSong?.id }

    fun canSkipNext(allowSuggestions: Boolean = true): Boolean =
        currentQueueIndex in queue.indices &&
            (currentQueueIndex < queue.lastIndex || repeatMode == RepeatMode.ALL || (allowSuggestions && suggestedTracks.isNotEmpty()))

    val canSkipPrevious: Boolean
        get() =
            currentQueueIndex in queue.indices &&
                (currentQueueIndex > 0 || positionMs > 3000L || repeatMode == RepeatMode.ALL)
}

interface PlaybackController {
    val state: StateFlow<PlaybackUiState>
    val listState: Flow<PlaybackListState> get() = state.listStates()
    val canSkipNext: Boolean get() = state.value.canSkipNext()
    val canSkipPrevious: Boolean get() = state.value.canSkipPrevious
    val supportsVideoPlayback: Boolean get() = true
    val supportsCasting: Boolean get() = true

    fun currentPositionMs(): Long = state.value.positionMs

    fun play(
        song: SongItem,
        queue: List<SongItem> = listOf(song),
        songsOnly: Boolean = false,
    )

    fun playQueue(
        songs: List<SongItem>,
        startIndex: Int = 0,
        queueTitle: String? = null,
    ) {
        val song = songs.getOrNull(startIndex) ?: return
        play(song, songs)
    }

    fun togglePlayPause()

    fun seekTo(positionMs: Long)

    fun seekTo(
        positionMs: Long,
        source: PlaybackSeekSource,
    ) {
        source.record(currentPositionMs(), positionMs, state.value.currentSong?.id)
        seekTo(positionMs)
    }

    fun skipToNext()

    fun skipToPrevious()

    fun setPlaybackSpeed(speed: Float)

    fun setPlaybackPitch(pitch: Float) = Unit

    fun startRadio(song: SongItem)

    fun playNext(songs: List<SongItem>)

    fun addToQueue(songs: List<SongItem>)

    fun playQueueIndex(index: Int) {
        val snapshot = state.value
        snapshot.queue.getOrNull(index)?.let { play(it, snapshot.queue) }
    }

    fun playSuggestedTrack(song: SongItem) {
        play(song, state.value.queue + song)
    }

    fun removeQueueItem(index: Int) = Unit

    fun removeQueueItems(indices: Set<Int>): QueueRemoval? = null

    fun restoreQueueItems(removals: List<QueueRemoval>) = Unit

    fun moveQueueItem(
        from: Int,
        to: Int,
    ) = Unit

    fun reorderQueue(order: List<Int>): Boolean = false

    fun selectQueueChip(chipId: String) = Unit

    fun toggleShuffle() = Unit

    fun toggleRepeat() = Unit

    fun startSleepTimer(
        durationMs: Long,
        fadeOut: Boolean,
        finishCurrentSong: Boolean = false,
    ) = Unit

    fun stopSleepTimerAfterCurrentSong(fadeOut: Boolean) = Unit

    fun cancelSleepTimer() = Unit

    fun toggleVideoMode() = Unit

    fun setVideoMode(wantVideo: Boolean) = Unit

    fun startCastDiscovery() = Unit

    fun stopCastDiscovery() = Unit

    fun castTo(device: CastDevice) = Unit

    fun disconnectCast() = Unit
}

internal fun preferredPlaybackDurationMs(
    metadataSeconds: Long?,
    nativeDurationMs: Long,
): Long {
    metadataSeconds?.takeIf { it > 0L }?.let { return it * 1_000L }
    val milliseconds = nativeDurationMs.coerceAtLeast(0L)
    val remainder = milliseconds % 1_000L
    return if (remainder == 0L) milliseconds else milliseconds + minOf(1_000L - remainder, Long.MAX_VALUE - milliseconds)
}

internal fun isQualifiedLocalPlay(
    playTimeMs: Long,
    thresholdSeconds: Float,
    historyPaused: Boolean,
): Boolean = !historyPaused && playTimeMs >= (thresholdSeconds.coerceAtLeast(0f) * 1_000f).toLong()

val LocalPlaybackController = staticCompositionLocalOf<PlaybackController> { error("No PlaybackController provided") }
