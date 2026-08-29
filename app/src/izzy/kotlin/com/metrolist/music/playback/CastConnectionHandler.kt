package com.metrolist.music.playback

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stub CastConnectionHandler for Izzy builds.
 * Cast functionality is not available without Google Play Services.
 */
class CastConnectionHandler(
    context: Context,
    scope: CoroutineScope,
    musicService: MusicService
) {
    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    private val _castDeviceName = MutableStateFlow<String?>(null)
    val castDeviceName: StateFlow<String?> = _castDeviceName

    private val _castPosition = MutableStateFlow(0L)
    val castPosition: StateFlow<Long> = _castPosition

    private val _castDuration = MutableStateFlow(0L)
    val castDuration: StateFlow<Long> = _castDuration

    private val _castIsPlaying = MutableStateFlow(false)
    val castIsPlaying: StateFlow<Boolean> = _castIsPlaying

    private val _castIsBuffering = MutableStateFlow(false)
    val castIsBuffering: StateFlow<Boolean> = _castIsBuffering

    private val _castVolume = MutableStateFlow(1.0f)
    val castVolume: StateFlow<Float> = _castVolume

    var isSyncingFromCast: Boolean = false
        private set

    fun initialize(): Boolean = false
    fun disconnect() {}
    fun loadCurrentMedia() {}
    fun loadMedia(metadata: com.metrolist.music.models.MediaMetadata) {}
    fun play() {}
    fun pause() {}
    fun seekTo(position: Long) {}
    fun setVolume(volume: Float) {}
    fun skipToNext() {}
    fun skipToPrevious() {}
    fun navigateToMediaIfInQueue(mediaId: String): Boolean = false
    fun release() {}
}
