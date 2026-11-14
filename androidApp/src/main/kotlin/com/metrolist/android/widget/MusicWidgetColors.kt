package com.metrolist.android.widget

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.metrolist.shared.settings.ThemePaletteStyle
import com.metrolist.shared.ui.theme.themeColorScheme

internal fun musicWidgetColorScheme(
    artworkSeed: Color?,
    isDark: Boolean,
    systemScheme: () -> ColorScheme,
): ColorScheme = artworkSeed?.let { themeColorScheme(it, isDark, ThemePaletteStyle.TONAL_SPOT) } ?: systemScheme()
