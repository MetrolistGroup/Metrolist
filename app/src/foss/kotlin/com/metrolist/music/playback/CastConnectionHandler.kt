package com.metrolist.music.playback

import android.content.Context
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Suppress("UNUSED_PARAMETER")
class CastConnectionHandler(
    context: Context,
    scope: CoroutineScope,
    musicService: MusicService,
) {
    val isCasting: StateFlow<Boolean> = MutableStateFlow(false)
    val castDeviceName: StateFlow<String?> = MutableStateFlow(null)
    val castPosition: StateFlow<Long> = MutableStateFlow(0L)
    val castDuration: StateFlow<Long> = MutableStateFlow(0L)
    val castIsPlaying: StateFlow<Boolean> = MutableStateFlow(false)
    val castIsBuffering: StateFlow<Boolean> = MutableStateFlow(false)
    val castVolume: StateFlow<Float> = MutableStateFlow(1f)
    val isSyncingFromCast = false

    fun initialize() = false

    fun disconnect() = Unit

    fun loadCurrentMedia() = Unit

    fun loadMedia(metadata: MediaMetadata) = Unit

    fun play() = Unit

    fun pause() = Unit

    fun seekTo(position: Long) = Unit

    fun setVolume(volume: Float) = Unit

    fun skipToNext() = Unit

    fun skipToPrevious() = Unit

    fun navigateToMediaIfInQueue(mediaId: String) = false

    fun release() = Unit
}
