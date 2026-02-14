package com.metrolist.shared.player

import android.content.Context
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class MusicPlayerImpl(context: Context) : MusicPlayer {
    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    private val _currentItem = MutableStateFlow<MediaItem?>(null)
    override val currentItem: StateFlow<MediaItem?> = _currentItem.asStateFlow()

    private val _playerState = MutableStateFlow(PlayerState.IDLE)
    override val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState.STOPPED)
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _position = MutableStateFlow(0L)
    override val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _playerState.value = when (playbackState) {
                    Player.STATE_IDLE -> PlayerState.IDLE
                    Player.STATE_BUFFERING -> PlayerState.BUFFERING
                    Player.STATE_READY -> PlayerState.READY
                    Player.STATE_ENDED -> PlayerState.ENDED
                    else -> PlayerState.IDLE
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playbackState.value = if (isPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED
                _duration.value = exoPlayer.duration
            }
        })
    }

    override fun play() {
        exoPlayer.play()
        _playbackState.value = PlaybackState.PLAYING
    }

    override fun pause() {
        exoPlayer.pause()
        _playbackState.value = PlaybackState.PAUSED
    }

    override fun stop() {
        exoPlayer.stop()
        _playbackState.value = PlaybackState.STOPPED
    }

    override fun seekTo(position: Long) {
        exoPlayer.seekTo(position)
        _position.value = position
    }

    override fun setMediaItem(item: MediaItem) {
        val exoMediaItem = ExoMediaItem.Builder()
            .setUri(item.streamUrl)
            .setMediaId(item.id)
            .build()

        exoPlayer.setMediaItem(exoMediaItem)
        exoPlayer.prepare()
        _currentItem.value = item
    }

    override fun setMediaItems(items: List<MediaItem>, startIndex: Int) {
        val exoItems = items.map { item ->
            ExoMediaItem.Builder()
                .setUri(item.streamUrl)
                .setMediaId(item.id)
                .build()
        }

        exoPlayer.setMediaItems(exoItems, startIndex, 0)
        exoPlayer.prepare()
        _currentItem.value = items.getOrNull(startIndex)
    }

    override fun next() {
        exoPlayer.seekToNext()
    }

    override fun previous() {
        exoPlayer.seekToPrevious()
    }

    override fun setRepeatMode(repeatMode: RepeatMode) {
        exoPlayer.repeatMode = when (repeatMode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
    }

    override fun setShuffleMode(enabled: Boolean) {
        exoPlayer.shuffleModeEnabled = enabled
    }

    override fun release() {
        exoPlayer.release()
    }
}
