package com.metrolist.shared.listentogether

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.IosPlaybackController
import com.metrolist.shared.playback.MusicPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal class IosListenTogetherPlayback(
    private val controller: IosPlaybackController,
    private val player: MusicPlayer,
    scope: CoroutineScope,
) : ListenTogetherPlayback {
    override val connected = MutableStateFlow(true)

    override val currentSong =
        combine(player.currentSong, player.currentPlaybackResource) { song, resource ->
            song?.takeIf { it.id == resource?.videoId }
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            player.currentSong.value?.takeIf { it.id == player.currentPlaybackResource.value?.videoId },
        )
    override val playing = player.isPlaying
    override val positionMs = player.currentPosition
    override val durationMs = player.duration
    override val queue = player.songQueue
    override val volume = player.volume
    override val readiness =
        combine(controller.state, player.readiness) { state, native ->
            when {
                state.error != null -> PlaybackReadiness.FAILED
                state.isLoading -> PlaybackReadiness.BUFFERING
                else -> native
            }
        }.stateIn(scope, SharingStarted.Eagerly, PlaybackReadiness.IDLE)

    override fun currentPositionMs(): Long = player.currentPositionMs()

    override fun currentQueueTitle(): String? = controller.state.value.queueTitle

    override fun currentQueueIndex(): Int = player.currentMediaItemIndex.value

    override fun playbackSpeed(): Float = player.playbackSpeed()

    override fun playbackInstanceId(): Long? = player.playbackInstance.value?.first

    override suspend fun playQueueFromRemote(
        songs: List<SongItem>,
        startIndex: Int,
        title: String?,
    ) = controller.playQueueFromListenTogether(songs, startIndex, title)

    override fun setQueueTitleFromRemote(title: String?) = controller.setQueueTitleFromListenTogether(title)

    override suspend fun replaceUpcomingQueueFromRemote(songs: List<SongItem>) = controller.replaceUpcomingFromListenTogether(songs)

    override suspend fun addToQueueFromRemote(song: SongItem) = controller.addToQueueFromListenTogether(song)

    override suspend fun skipToNextFromRemote() = controller.skipToNextFromListenTogether()

    override suspend fun skipToPreviousFromRemote() = controller.skipToPreviousFromListenTogether()

    override suspend fun setPlayingFromRemote(playing: Boolean) = controller.setPlayingFromListenTogether(playing)

    override suspend fun seekToFromRemote(positionMs: Long) = controller.seekFromListenTogether(positionMs)

    override suspend fun setPlaybackSpeedFromRemote(speed: Float) = player.setPlaybackSpeed(speed)

    override suspend fun setVolumeFromRemote(volume: Float) = player.setVolume((volume.coerceIn(0f, 1f) * 100f).toInt())
}
