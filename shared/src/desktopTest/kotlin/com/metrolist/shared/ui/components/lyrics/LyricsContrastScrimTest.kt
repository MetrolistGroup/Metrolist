package com.metrolist.shared.ui.components.lyrics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.metrolist.shared.settings.ThemePaletteStyle
import com.metrolist.shared.ui.theme.DefaultThemeColor
import com.metrolist.shared.ui.theme.themeColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LyricsContrastScrimTest {
    @Test
    fun lightArtworkMeetsContrastAcrossPalettesAndNeutralText() {
        val backgrounds =
            listOf(
                Color(0xFFADA8A5),
                Color.White.copy(alpha = 0.12f).compositeOver(Color.Black.copy(alpha = 0.3f).compositeOver(Color.White)),
                Color.Black.copy(alpha = 0.45f).compositeOver(Color.White),
                Color.White.copy(alpha = 13f / 255f).compositeOver(Color.Black.copy(alpha = 128f / 255f).compositeOver(Color.White)),
            )
        for (seed in listOf(DefaultThemeColor, Color(0xFFCEA47D), Color.Red, Color.Green, Color.Blue)) {
            for (palette in ThemePaletteStyle.entries) {
                for (dark in listOf(false, true)) {
                    val scheme = themeColorScheme(seed, dark, palette)
                    val accent = lyricsPaletteTextColor(scheme, lightText = true)
                    assertTrue(contrast(accent, Color.Black) >= 4.5f)
                    if (scheme.primaryFixed.luminance() > 0.5f) assertEquals(scheme.primaryFixed, accent)
                    assertEquals(scheme.onPrimaryFixedVariant, lyricsPaletteTextColor(scheme, lightText = false))
                    for (text in listOf(accent, Color.White)) {
                        val alpha = lyricsContrastScrimAlpha(text, backgrounds)
                        assertTrue(alpha in 0f..1f)
                        for (background in backgrounds) {
                            val treated = Color.Black.copy(alpha = alpha).compositeOver(background)
                            assertTrue(contrast(text, treated) >= 4.5f, "$seed $palette $dark $background")
                            val plus =
                                Color(
                                    (text.red + treated.red).coerceAtMost(1f),
                                    (text.green + treated.green).coerceAtMost(1f),
                                    (
                                        text.blue +
                                            treated.blue
                                    ).coerceAtMost(1f),
                                )
                            assertTrue(contrast(plus, treated) >= 4.5f)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun darkBackgroundsKeepTheirExistingTreatment() {
        val text = Color(0xFFFFDCC0)
        assertEquals(0f, lyricsContrastScrimAlpha(text, listOf(Color.Black, Color(0xFF282322))))
        assertEquals(0f, lyricsContrastScrimAlpha(Color.White, listOf(Color(0xFF444444))))
    }

    @Test
    fun scrimStopsAtTheContrastThreshold() {
        val text = Color(0xFFFFDCC0)
        val background = Color(0xFFADA8A5)
        val alpha = lyricsContrastScrimAlpha(text, listOf(background))
        assertTrue(alpha > 0f)
        val treated = Color.Black.copy(alpha = alpha).compositeOver(background)
        assertTrue(contrast(text, treated) in 4.5f..4.6f)
        assertTrue(contrast(text, Color.Black.copy(alpha = alpha - 1f / 255f).compositeOver(background)) < 4.5f)
        val inactive = text.copy(alpha = 0.35f).compositeOver(treated)
        assertTrue(contrast(inactive, treated) > 1.5f)
        assertTrue(contrast(inactive, treated) < contrast(text, treated))
    }

    @Test
    fun saturationMatchesTheRendererAndKeepsNeutralArtwork() {
        val gray = Color(0xFF555555)
        for (saturation in listOf(1f, 1.25f, 2.5f)) {
            val matrix = ColorMatrix().apply { setToSaturation(saturation) }
            assertEquals(gray, saturatedLyricsArtworkColor(gray, matrix))
        }
        val source = Color(0xFFBABB99)
        val saturated = saturatedLyricsArtworkColor(source, ColorMatrix().apply { setToSaturation(2.5f) })
        assertNotEquals(source, saturated)
        val background = Color.White.copy(alpha = 13f / 255f).compositeOver(Color.Black.copy(alpha = 128f / 255f).compositeOver(saturated))
        val text = Color(0xFFFFDCC0)
        val alpha = lyricsContrastScrimAlpha(text, listOf(background))
        assertTrue(contrast(text, Color.Black.copy(alpha = alpha).compositeOver(background)) >= 4.5f)
    }

    private fun contrast(
        text: Color,
        background: Color,
    ): Float = (text.luminance() + 0.05f) / (background.luminance() + 0.05f)
}
