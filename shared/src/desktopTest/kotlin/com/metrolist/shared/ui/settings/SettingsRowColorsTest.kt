package com.metrolist.shared.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.metrolist.shared.settings.ThemePaletteStyle
import com.metrolist.shared.ui.theme.DefaultThemeColor
import com.metrolist.shared.ui.theme.pureBlack
import com.metrolist.shared.ui.theme.themeColorScheme
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SettingsRowColorsTest {
    @Test
    fun rowsStayDistinctAndReadableAcrossThemesAndPalettes() {
        for (seed in listOf(DefaultThemeColor, Color.Red, Color.Green, Color.Blue)) {
            for (palette in ThemePaletteStyle.entries) {
                val dark = themeColorScheme(seed, true, palette)
                for (scheme in listOf(themeColorScheme(seed, false, palette), dark, dark.pureBlack())) {
                    val normal = scheme.settingsRowContainerColor()
                    val pressed = scheme.settingsRowContainerColor(pressed = true)
                    assertNotEquals(normal, pressed, "$seed $palette")
                    for (fill in listOf(normal, pressed)) {
                        assertTrue(contrast(fill, scheme.background) > 1.05f, "$seed $palette $fill")
                        assertTrue(contrast(fill, scheme.onSurface) >= 4.5f, "$seed $palette $fill")
                        assertTrue(contrast(fill, scheme.onSurfaceVariant) >= 4.5f, "$seed $palette $fill")
                        assertTrue(fill.alpha == 1f)
                    }
                }
            }
        }
    }

    private fun contrast(
        first: Color,
        second: Color,
    ): Float {
        val firstLuminance = first.luminance()
        val secondLuminance = second.luminance()
        return (maxOf(firstLuminance, secondLuminance) + 0.05f) / (minOf(firstLuminance, secondLuminance) + 0.05f)
    }
}
