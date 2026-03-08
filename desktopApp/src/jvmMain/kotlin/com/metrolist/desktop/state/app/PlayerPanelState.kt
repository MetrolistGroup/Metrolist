package com.metrolist.desktop.state.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class PlayerPanelState {
    private var queueVisible by mutableStateOf(false)
    private var lyricsVisible by mutableStateOf(false)
    private var lyricsOpenedLast = false

    var canShowBoth = false
        set(value) {
            field = value
            if (!value && queueVisible && lyricsVisible) {
                if (lyricsOpenedLast) queueVisible = false else lyricsVisible = false
            }
        }

    var showQueue: Boolean
        get() = queueVisible
        set(value) {
            queueVisible = value
            if (value) {
                lyricsOpenedLast = false
                if (!canShowBoth) lyricsVisible = false
            }
        }

    var showLyrics: Boolean
        get() = lyricsVisible
        set(value) {
            lyricsVisible = value
            if (value) {
                lyricsOpenedLast = true
                if (!canShowBoth) queueVisible = false
            }
        }
}
