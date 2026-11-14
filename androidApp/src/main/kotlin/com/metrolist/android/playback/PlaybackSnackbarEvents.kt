package com.metrolist.android.playback

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object PlaybackSnackbarEvents {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    fun emit(message: String) {
        _messages.tryEmit(message)
    }
}
