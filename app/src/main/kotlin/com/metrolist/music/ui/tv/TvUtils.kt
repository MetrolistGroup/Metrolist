/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color

/** Returns true when the app is running on an Android TV device. */
fun Context.isAndroidTv(): Boolean {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

/**
 * Spotify TV Design Tokens & Color Constants
 */
object TvColors {
    val SpotifyGreen = Color(0xFF1DB954)
    val SpotifyGreenBright = Color(0xFF1ED760)
    val SpotifyDarkGreen = Color(0xFF169C46)

    // Backgrounds
    val BackgroundDark = Color(0xFF121212)
    val BackgroundPureBlack = Color(0xFF000000)
    val CardBackground = Color(0xFF181818)
    val CardBackgroundElevated = Color(0xFF242424)
    val FocusHighlight = Color(0xFFFFFFFF)

    // Translucent overlays
    val OverlayLight = Color.White.copy(alpha = 0.08f)
    val OverlayMedium = Color.White.copy(alpha = 0.15f)
    val OverlayFocused = Color.White.copy(alpha = 0.25f)

    // Text colors
    val TextPrimary = Color.White
    val TextSecondary = Color(0xFFB3B3B3)
    val TextTertiary = Color(0xFF727272)
    val TextMuted = Color.White.copy(alpha = 0.5f)
}
