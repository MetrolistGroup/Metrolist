package com.metrolist.desktop.state.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class PlayerPresentationState {
    var isExpanded by mutableStateOf(false)
        private set
    var isFullscreen by mutableStateOf(false)
    var showVideoFullscreen by mutableStateOf(false)

    fun enterExpanded() {
        isExpanded = true
    }

    fun exitExpanded() {
        isExpanded = false
    }

    fun toggleFullscreenVideo() {
        enterExpanded()
        showVideoFullscreen = !showVideoFullscreen
    }

    fun closePlayer() {
        showVideoFullscreen = false
        exitExpanded()
    }
}
