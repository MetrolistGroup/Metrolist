package com.metrolist.android.playback

import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.playback.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

class AndroidPlaybackController(
    private val connection: PlayerConnection,
    scope: CoroutineScope,
) : PlaybackController {
    private val baseState =
        combine(
            connection.currentSong,
            connection.isPlaying,
            connection.currentPosition,
            connection.duration,
            connection.queueSongs,
        ) { song, isPlaying, position, duration, queue ->
            PlaybackUiState(song, isPlaying, position, duration, queue)
        }

    private val playbackState =
        combine(
            baseState,
            connection.currentMediaItemIndex,
            connection.sleepTimerState,
            connection.isVideoModeActive,
            connection.isVideoModeResolving,
        ) { state, index, sleepState, videoActive, videoResolving ->
            state.copy(
                currentQueueIndex = index,
                sleepTimerState = sleepState,
                isVideoModeActive = videoActive,
                isVideoResolving = videoResolving,
            )
        }

    override val state: StateFlow<PlaybackUiState> =
        combine(playbackState, connection.castState, connection.castDevices, connection.isPlaybackLoading) {
            state,
            castState,
            castDevices,
            isLoading,
            ->
            state.copy(castState = castState, castDevices = castDevices, isLoading = isLoading)
        }.combine(connection.isReconnecting) { state, reconnecting ->
            state.copy(isReconnecting = reconnecting)
        }.flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.WhileSubscribed(0), PlaybackUiState())

    override fun play(
        song: SongItem,
        queue: List<SongItem>,
        songsOnly: Boolean,
    ) {
        val songs = queue.ifEmpty { listOf(song) }
        connection.playQueue(songs, songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0), songsOnly = songsOnly)
    }

    override fun currentPositionMs(): Long = connection.currentPositionMs()

    override fun togglePlayPause() = connection.togglePlayPause()

    override fun seekTo(positionMs: Long) = connection.seekTo(positionMs)

    override fun seekTo(
        positionMs: Long,
        source: PlaybackSeekSource,
    ) = connection.seekTo(positionMs, source)

    override fun skipToNext() = connection.skipToNext()

    override fun skipToPrevious() = connection.skipToPrevious()

    override fun setPlaybackSpeed(speed: Float) = connection.setPlaybackSpeed(speed)

    override fun setPlaybackPitch(pitch: Float) = connection.setPlaybackPitch(pitch)

    override fun startRadio(song: SongItem) = connection.startRadio(song)

    override fun playNext(songs: List<SongItem>) {
        connection.addToQueue(songs, playNext = true)
    }

    override fun addToQueue(songs: List<SongItem>) {
        connection.addToQueue(songs)
    }

    override fun startSleepTimer(
        durationMs: Long,
        fadeOut: Boolean,
        finishCurrentSong: Boolean,
    ) {
        connection.startSleepTimer(durationMs, fadeOut, finishCurrentSong)
    }

    override fun stopSleepTimerAfterCurrentSong(fadeOut: Boolean) {
        connection.stopAfterCurrentSong(fadeOut)
    }

    override fun cancelSleepTimer() {
        connection.cancelSleepTimer()
    }

    override fun toggleVideoMode() {
        connection.toggleVideoMode()
    }

    override fun setVideoMode(wantVideo: Boolean) {
        connection.setVideoMode(wantVideo)
    }

    override fun startCastDiscovery() {
        connection.startCastDiscovery()
    }

    override fun stopCastDiscovery() {
        connection.stopCastDiscovery()
    }

    override fun castTo(device: CastDevice) {
        connection.castTo(device)
    }

    override fun disconnectCast() {
        connection.disconnectCast()
    }
}
