/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.theme

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

@Composable
actual fun localeAwareSystemFontFamily(languageTag: String): FontFamily = FontFamily.Default

@Suppress("SameReturnValue")
actual class ThemeColorExtractor {
    actual fun extractSeedColor(image: Any): Color {
        val bitmap = image as? Bitmap ?: return DefaultThemeColor
        val source =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return DefaultThemeColor
            } else {
                bitmap
            }
        try {
            val pixels = IntArray(source.width * source.height)
            source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
            return selectArtworkSeedColor(source.width, source.height) { x, y -> pixels[y * source.width + x] }
        } finally {
            if (source !== bitmap) source.recycle()
        }
    }

    actual fun extractGradientColors(
        image: Any,
        fallback: Color,
    ): List<Color> = getGradientColors(fallback)

    actual fun isArtworkBright(image: Any): Boolean = false
}

@Composable
internal actual fun rememberReducedMotion(): Boolean = false
