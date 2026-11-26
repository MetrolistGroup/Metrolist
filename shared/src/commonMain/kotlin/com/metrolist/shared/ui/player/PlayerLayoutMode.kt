package com.metrolist.shared.ui.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class PlayerLayoutMode {
    PORTRAIT,
    LANDSCAPE,
}

internal fun playerLayoutMode(
    width: Dp,
    height: Dp,
    useTwoPanesOnLargePortrait: Boolean = false,
    showingLyrics: Boolean = false,
): PlayerLayoutMode =
    if (width >= 600.dp && height >= 320.dp && (width > height || (useTwoPanesOnLargePortrait && !showingLyrics))) {
        PlayerLayoutMode.LANDSCAPE
    } else {
        PlayerLayoutMode.PORTRAIT
    }
