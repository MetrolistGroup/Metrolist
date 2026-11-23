package com.metrolist.desktop.state.playback

import androidx.compose.runtime.snapshotFlow
import com.metrolist.desktop.deviceconnect.DesktopDeviceConnect
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.playlistOccurrenceIdentity
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
    override val playbackVolume = PlayerState.volumeState
    override val playbackCommandGeneration: Long get() = PlayerState.explicitPlaybackGeneration
    override val playbackCommands = PlayerState.playbackCommands
    private val wrappedSharedPlayback =
        ListenTogetherState.listenTogetherManager.let { manager ->
            combine(manager.isInRoom, manager.roomRequestPending, manager.persistedRoomSession) { active, pending, persisted ->
                active || pending || persisted
            }
        }
    override val wrappedSoundtrackAllowed =
        combine(
            PlayerState.castManager.state,
            wrappedSharedPlayback,
            DesktopDeviceConnect.manager.remote.state,
            snapshotFlow { PlayerState.sleepTimerEnabled || PlayerState.sleepTimerStopAfterCurrentSong },
        ) { cast, shared, remote, timer -> !cast.isConnected && !shared && remote.sessionId == null && !timer }

    override val listState =
        combine(PlayerState.player.currentSong, PlayerState.player.isPlaying) { song, playing ->
            PlaybackListState(
                song?.id,
                playing,
                originalQueueItemId = song?.originalQueueItemId,
                currentSongOccurrenceKey = song?.playlistOccurrenceIdentity(),
            )
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

    override val explicitPlaybackGeneration: Long
        get() = PlayerState.explicitPlaybackGeneration

    override val playbackReplacementGeneration: Long
        get() = PlayerState.playbackReplacementGeneration

    override fun reserveExplicitPlaybackGeneration(): Long = PlayerState.reserveExplicitPlaybackGeneration()

    override fun reserveQueueAdditionGeneration(): Long = PlayerState.reserveQueueAdditionGeneration()

    override fun play(
        song: SongItem,
        queue: List<SongItem>,
        songsOnly: Boolean,
    ) = PlayerState.playTrack(song, queue.ifEmpty { listOf(song) }, songsOnly = songsOnly)

    override fun playAtGeneration(
        expectedGeneration: Long,
        song: SongItem,
        queue: List<SongItem>,
        songsOnly: Boolean,
    ) {
        if (explicitPlaybackGeneration != expectedGeneration) return
        val replacementGeneration = playbackReplacementGeneration
        PlayerState.playTrack(
            song,
            queue.ifEmpty { listOf(song) },
            songsOnly = songsOnly,
            advanceGeneration = false,
            expectedReplacementGeneration = replacementGeneration,
        )
    }

    override fun playQueueAtGeneration(
        expectedGeneration: Long,
        songs: List<SongItem>,
        startIndex: Int,
        queueTitle: String?,
    ) {
        val song = songs.getOrNull(startIndex) ?: return
        playAtGeneration(expectedGeneration, song, songs)
    }

    override fun shuffleQueue(
        songs: List<SongItem>,
        queueTitle: String?,
        songsOnly: Boolean,
    ) = PlayerState.shuffleQueue(songs, songsOnly)

    override fun shuffleQueueAtGeneration(
        expectedGeneration: Long,
        songs: List<SongItem>,
        queueTitle: String?,
        songsOnly: Boolean,
    ) {
        if (explicitPlaybackGeneration != expectedGeneration) return
        val replacementGeneration = playbackReplacementGeneration
        PlayerState.shuffleQueue(
            songs,
            songsOnly,
            advanceGeneration = false,
            expectedReplacementGeneration = replacementGeneration,
        )
    }

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

    override fun playNextAtGeneration(
        expectedGeneration: Long,
        songs: List<SongItem>,
    ) {
        if (playbackReplacementGeneration != expectedGeneration) return
        PlayerState.playNext(songs, advanceGeneration = false, expectedReplacementGeneration = expectedGeneration)
    }

    override fun addToQueue(songs: List<SongItem>) = PlayerState.addToQueue(songs)

    override fun addToQueueAtGeneration(
        expectedGeneration: Long,
        songs: List<SongItem>,
    ) {
        if (playbackReplacementGeneration != expectedGeneration) return
        PlayerState.addToQueue(songs, advanceGeneration = false, expectedReplacementGeneration = expectedGeneration)
    }

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
