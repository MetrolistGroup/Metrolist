package com.metrolist.desktop.state.playback

import androidx.compose.runtime.snapshotFlow
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.playback.PlaybackListState
import com.metrolist.shared.ui.playback.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

class DesktopPlaybackController(
    scope: CoroutineScope,
) : PlaybackController {
    override val listState =
        combine(PlayerState.player.currentSong, PlayerState.player.isPlaying) { song, playing ->
            PlaybackListState(song?.id, playing)
        }.distinctUntilChanged()

    private val baseState =
        combine(
            PlayerState.player.currentSong,
            PlayerState.player.isPlaying,
            PlayerState.player.duration,
            PlayerState.queue.items,
        ) { song, isPlaying, duration, queue ->
            PlaybackUiState(currentSong = song, isPlaying = isPlaying, durationMs = duration, queue = queue)
        }

    override val state: StateFlow<PlaybackUiState> =
        combine(
            baseState,
            PlayerState.queue.currentIndex,
            PlayerState.castManager.state,
            PlayerState.castManager.devices,
            snapshotFlow { Triple(UiState.showVideoViewer, PlayerState.isVideoAvailable, PlayerState.isVideoResolving) },
        ) { state, index, castState, castDevices, videoState ->
            state.copy(
                currentQueueIndex = index,
                castState = castState,
                castDevices = castDevices,
                isVideoModeActive = videoState.first,
                isVideoAvailable = videoState.second,
                isVideoResolving = videoState.third,
            )
        }.combine(snapshotFlow { PlayerState.isPlaybackLoading }) { state, isLoading ->
            state.copy(isLoading = isLoading)
        }.combine(snapshotFlow { PlayerState.isReconnecting }) { state, reconnecting ->
            state.copy(isReconnecting = reconnecting)
        }.combine(PlayerState.player.currentPosition) { state, position ->
            state.copy(positionMs = position)
        }.stateIn(scope, SharingStarted.Eagerly, PlaybackUiState())

    override fun play(
        song: SongItem,
        queue: List<SongItem>,
        songsOnly: Boolean,
    ) = PlayerState.playTrack(song, queue.ifEmpty { listOf(song) }, songsOnly = songsOnly)

    override fun currentPositionMs(): Long = PlayerState.currentMediaPositionMs()

    override fun togglePlayPause() = PlayerState.togglePlay()

    override fun seekTo(positionMs: Long) = PlayerState.seekToPosition(positionMs)

    override fun skipToNext() {
        PlayerState.skipNext()
    }

    override fun skipToPrevious() = PlayerState.skipPrevious()

    override fun setPlaybackSpeed(speed: Float) = PlayerState.setPlaybackSpeed(speed)

    override fun setPlaybackPitch(pitch: Float) = PlayerState.setPlaybackPitch(pitch)

    override fun startRadio(song: SongItem) = PlayerState.startRadio(song)

    override fun playNext(songs: List<SongItem>) = PlayerState.playNext(songs)

    override fun addToQueue(songs: List<SongItem>) = PlayerState.addToQueue(songs)

    override fun reorderQueue(order: List<Int>) = PlayerState.reorderQueue(order)

    override fun toggleVideoMode() = PlayerState.requestVideoMode()

    override fun setVideoMode(wantVideo: Boolean) {
        if (wantVideo) {
            if (!UiState.showVideoViewer) {
                PlayerState.requestVideoMode()
            }
        } else {
            PlayerState.closeVideoViewer()
        }
    }

    override fun startCastDiscovery() = PlayerState.startCastDiscovery()

    override fun stopCastDiscovery() = PlayerState.stopCastDiscovery()

    override fun castTo(device: CastDevice) = PlayerState.castTo(device)

    override fun disconnectCast() = PlayerState.disconnectCast()
}
