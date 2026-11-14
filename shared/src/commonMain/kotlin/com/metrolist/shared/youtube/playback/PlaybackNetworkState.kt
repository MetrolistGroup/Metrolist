package com.metrolist.shared.youtube.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackNetworkState(
    val scope: String,
    val connected: Boolean = true,
    val isCellular: Boolean = false,
) {
    init {
        require(scope.length <= 128) { "Network scope is too long" }
        require(scope.none { it.isWhitespace() }) { "Network scope must be normalized" }
    }
}

object PlaybackNetworkStateStore {
    private val _state = MutableStateFlow(PlaybackNetworkState(scope = "", connected = true))
    val state: StateFlow<PlaybackNetworkState> = _state.asStateFlow()

    val current: PlaybackNetworkState
        get() = _state.value

    fun publish(state: PlaybackNetworkState) {
        _state.value = state
    }
}
