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
): PlayerLayoutMode =
    if (width > height && width >= 600.dp && height >= 320.dp) {
        PlayerLayoutMode.LANDSCAPE
    } else {
        PlayerLayoutMode.PORTRAIT
    }
