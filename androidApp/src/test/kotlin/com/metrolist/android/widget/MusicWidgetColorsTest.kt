package com.metrolist.android.widget

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.metrolist.shared.settings.ThemePaletteStyle
import com.metrolist.shared.ui.theme.themeColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicWidgetColorsTest {
    @Test
    fun artworkTakesPrecedenceOverWallpaper() {
        for (dark in listOf(false, true)) {
            val expected = themeColorScheme(Color.Red, dark, ThemePaletteStyle.TONAL_SPOT)
            val actual = musicWidgetColorScheme(Color.Red, dark) { error("Artwork must take precedence") }
            assertEquals(expected.primary, actual.primary)
            assertEquals(expected.secondary, actual.secondary)
            assertEquals(expected.surface, actual.surface)
        }
    }

    @Test
    fun missingArtworkUsesTheCurrentSystemScheme() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            assertEquals(scheme, musicWidgetColorScheme(null, scheme.surface.luminance() < 0.5f) { scheme })
        }
    }

    @Test
    fun controlAndForegroundRolesHaveContrastInBothModes() {
        val seeds = listOf(Color.Red, Color.Blue, Color.Green, Color.White, Color.Black, Color.Gray, null)
        for (dark in listOf(false, true)) {
            for (seed in seeds) {
                val scheme = musicWidgetColorScheme(seed, dark) { themeColorScheme(Color.Blue, dark, ThemePaletteStyle.TONAL_SPOT) }
                val controls =
                    listOf(
                        scheme.primary to scheme.onPrimary,
                        scheme.secondary to scheme.onSecondary,
                        scheme.tertiary to scheme.onTertiary,
                    )
                for ((background, foreground) in controls) {
                    assertTrue("Button against surface for $seed, dark=$dark", contrast(background, scheme.surface) >= 3f)
                    assertTrue("Icon and label against button for $seed, dark=$dark", contrast(foreground, background) >= 4.5f)
                }
            }
        }
    }

    private fun contrast(
        first: Color,
        second: Color,
    ): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
}
