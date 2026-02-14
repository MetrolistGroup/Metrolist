package com.metrolist.shared.player

import kotlinx.coroutines.flow.StateFlow

enum class PlayerState {
    IDLE,
    BUFFERING,
    READY,
    ENDED
}

enum class PlaybackState {
    PLAYING,
    PAUSED,
    STOPPED
}

data class MediaItem(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val duration: Long,
    val artworkUrl: String?,
    val streamUrl: String
)

interface MusicPlayer {
    val currentItem: StateFlow<MediaItem?>
    val playerState: StateFlow<PlayerState>
    val playbackState: StateFlow<PlaybackState>
    val position: StateFlow<Long>
    val duration: StateFlow<Long>

    fun play()
    fun pause()
    fun stop()
    fun seekTo(position: Long)
    fun setMediaItem(item: MediaItem)
    fun setMediaItems(items: List<MediaItem>, startIndex: Int = 0)
    fun next()
    fun previous()
    fun setRepeatMode(repeatMode: RepeatMode)
    fun setShuffleMode(enabled: Boolean)
    fun release()
}

enum class RepeatMode {
    OFF,
    ONE,
    ALL
}

expect class MusicPlayerImpl() : MusicPlayer
