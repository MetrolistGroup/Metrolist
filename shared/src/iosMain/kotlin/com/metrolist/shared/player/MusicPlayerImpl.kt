package com.metrolist.shared.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFoundation.*
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSURL
import platform.darwin.NSObject

actual class MusicPlayerImpl : MusicPlayer {
    private val avPlayer: AVPlayer = AVPlayer()
    private val playerItems = mutableListOf<MediaItem>()
    private var currentIndex = 0

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

    private var repeatMode = RepeatMode.OFF
    private var shuffleEnabled = false

    override fun play() {
        avPlayer.play()
        _playbackState.value = PlaybackState.PLAYING
    }

    override fun pause() {
        avPlayer.pause()
        _playbackState.value = PlaybackState.PAUSED
    }

    override fun stop() {
        avPlayer.pause()
        avPlayer.replaceCurrentItemWithPlayerItem(null)
        _playbackState.value = PlaybackState.STOPPED
    }

    override fun seekTo(position: Long) {
        val time = CMTimeMake(position, 1000)
        avPlayer.seekToTime(time)
        _position.value = position
    }

    override fun setMediaItem(item: MediaItem) {
        val url = NSURL.URLWithString(item.streamUrl) ?: return
        val playerItem = AVPlayerItem(uRL = url)

        avPlayer.replaceCurrentItemWithPlayerItem(playerItem)
        _currentItem.value = item
        _playerState.value = PlayerState.READY

        // Get duration
        val duration = playerItem.duration
        if (duration.timescale > 0) {
            _duration.value = (duration.value / duration.timescale) * 1000
        }
    }

    override fun setMediaItems(items: List<MediaItem>, startIndex: Int) {
        playerItems.clear()
        playerItems.addAll(items)
        currentIndex = startIndex

        if (items.isNotEmpty()) {
            setMediaItem(items[startIndex])
        }
    }

    override fun next() {
        if (currentIndex < playerItems.size - 1) {
            currentIndex++
            setMediaItem(playerItems[currentIndex])
        } else if (repeatMode == RepeatMode.ALL) {
            currentIndex = 0
            setMediaItem(playerItems[0])
        }
    }

    override fun previous() {
        if (currentIndex > 0) {
            currentIndex--
            setMediaItem(playerItems[currentIndex])
        } else if (repeatMode == RepeatMode.ALL) {
            currentIndex = playerItems.size - 1
            setMediaItem(playerItems[currentIndex])
        }
    }

    override fun setRepeatMode(repeatMode: RepeatMode) {
        this.repeatMode = repeatMode
    }

    override fun setShuffleMode(enabled: Boolean) {
        this.shuffleEnabled = enabled
        if (enabled) {
            playerItems.shuffle()
        }
    }

    override fun release() {
        avPlayer.pause()
        avPlayer.replaceCurrentItemWithPlayerItem(null)
    }
}
