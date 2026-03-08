/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.listentogether

import com.metrolist.desktop.deviceconnect.DesktopDeviceConnect
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.listentogether.ListenTogetherPlayback
import com.metrolist.shared.listentogether.PlaybackReadiness
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DesktopListenTogetherPlayback : ListenTogetherPlayback {
    override val connected: StateFlow<Boolean> = PlayerState.playbackConnected
    override val currentSong: StateFlow<SongItem?> = PlayerState.player.currentSong
    override val playing: StateFlow<Boolean> = PlayerState.desiredPlayback
    override val positionMs: StateFlow<Long> = PlayerState.player.currentPosition
    override val durationMs: StateFlow<Long> = PlayerState.player.duration
    override val volume: StateFlow<Float> = PlayerState.volumeState
    override val queue: StateFlow<List<SongItem>> = PlayerState.queue.items
    override val readiness = MutableStateFlow(PlaybackReadiness.IDLE)

    init {
        AppState.scope.launch {
            currentSong.collectLatest { song ->
                if (song == null) readiness.value = PlaybackReadiness.IDLE
            }
        }
        AppState.scope.launch {
            PlayerState.player.loadFailure.collectLatest { failure ->
                if (failure != null) readiness.value = PlaybackReadiness.FAILED
            }
        }
    }

    override fun currentPositionMs(): Long = PlayerState.player.currentPlaybackPositionMs()

    override fun playbackSpeed(): Float = PlayerState.player.playbackSpeed()

    override fun playbackInstanceId(): Long? = PlayerState.player.currentPlaybackInstanceId.takeIf { it != 0L }

    override fun currentQueueTitle(): String? = PlayerState.currentQueueTitle()

    override fun currentQueueIndex(): Int = PlayerState.queue.currentIndex.value

    override suspend fun playQueueFromRemote(
        songs: List<SongItem>,
        startIndex: Int,
        title: String?,
    ) {
        val song = songs.getOrNull(startIndex) ?: return
        readiness.value = PlaybackReadiness.BUFFERING
        readiness.value =
            if (PlayerState.playTrackInternalSuspend(song, songs, startIndex = startIndex, startPaused = true)) {
                PlaybackReadiness.READY
            } else {
                PlaybackReadiness.FAILED
            }
        if (readiness.value == PlaybackReadiness.READY) {
            PlayerState.setQueueTitleFromSync(title)
            DesktopDeviceConnect.manager.remote.localStateChanged(trackChanged = true)
        }
    }

    override fun setQueueTitleFromRemote(title: String?) = PlayerState.setQueueTitleFromSync(title)

    override suspend fun replaceUpcomingQueueFromRemote(songs: List<SongItem>) {
        PlayerState.replaceUpcomingQueueFromListenTogether(songs)
        DesktopDeviceConnect.manager.remote.localStateChanged()
    }

    override suspend fun addToQueueFromRemote(song: SongItem) {
        PlayerState.addToQueueFromListenTogether(song)
        DesktopDeviceConnect.manager.remote.localStateChanged()
    }

    override suspend fun skipToNextFromRemote() {
        PlayerState.skipNextFromListenTogether()
    }

    override suspend fun skipToPreviousFromRemote() {
        PlayerState.skipPreviousFromListenTogether()
    }

    override suspend fun setPlayingFromRemote(playing: Boolean) {
        PlayerState.setPlayingFromSync(playing)
        DesktopDeviceConnect.manager.remote.localStateChanged()
    }

    override suspend fun seekToFromRemote(positionMs: Long) {
        PlayerState.seekToPositionInternal(positionMs.coerceAtLeast(0L))
        DesktopDeviceConnect.manager.remote.localStateChanged()
    }

    override suspend fun setPlaybackSpeedFromRemote(speed: Float) {
        PlayerState.applyPlaybackSpeed(speed)
    }

    override suspend fun setVolumeFromRemote(volume: Float) {
        PlayerState.setVolumeLevel(volume.coerceIn(0f, 1f))
    }
}
