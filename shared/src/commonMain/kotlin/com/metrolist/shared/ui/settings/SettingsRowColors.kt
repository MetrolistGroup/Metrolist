package com.metrolist.shared.ui.settings

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

fun ColorScheme.settingsRowContainerColor(pressed: Boolean = false): Color {
    val container = if (pressed) surfaceContainerHighest else surfaceContainerHigh
    // Pure black collapses container tokens; tonal elevation retains the theme's surface tint.
    return if (container == background) surfaceColorAtElevation(if (pressed) 12.dp else 6.dp) else container
}
