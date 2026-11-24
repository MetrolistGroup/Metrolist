package com.metrolist.shared.playback

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.jetbrains.compose.resources.StringResource

class PlaybackSnackbarResourceEvent internal constructor(
    val resource: StringResource,
)

object PlaybackSnackbarEvents {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val _resourceMessages = MutableSharedFlow<PlaybackSnackbarResourceEvent>(extraBufferCapacity = 8)
    val resourceMessages = _resourceMessages.asSharedFlow()

    fun emit(message: String) {
        _messages.tryEmit(message)
    }

    fun emit(resource: StringResource) {
        _resourceMessages.tryEmit(PlaybackSnackbarResourceEvent(resource))
    }
}
