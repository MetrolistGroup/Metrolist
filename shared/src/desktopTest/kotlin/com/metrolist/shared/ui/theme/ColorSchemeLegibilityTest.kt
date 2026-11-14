package com.metrolist.shared.ui.theme

import androidx.compose.ui.graphics.Color
import com.metrolist.shared.settings.ThemePaletteStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ColorSchemeLegibilityTest {
    private val seeds =
        listOf(
            DefaultThemeColor,
            ArtworkFallbackSeedColor,
            Color(0xFFF8FEFF),
            Color(0xFF050505),
            Color(0xFF808080),
            Color(0xFFFFFF00),
            Color(0xFFFF0000),
        )

    @Test
    fun everyGeneratedPaletteAndPureBlackStayLegible() {
        for (style in ThemePaletteStyle.entries) {
            for (seed in seeds) {
                for (contrast in listOf(-1.0, 0.0, 0.5, 1.0)) {
                    for (isDark in listOf(false, true)) {
                        val scheme = themeColorScheme(seed, isDark, style, contrast)
                        assertTrue(scheme.isLegible(isDark), "$style $seed contrast=$contrast dark=$isDark")
                        if (isDark) assertTrue(scheme.pureBlack().isLegible(true), "pure black $style $seed contrast=$contrast")
                    }
                }
                assertEquals(
                    themeColorScheme(seed, false, style, 0.0).onSurfaceVariant,
                    themeColorScheme(seed, false, style, -1.0).onSurfaceVariant,
                )
            }
        }
    }

    @Test
    fun contentPaletteRetainsMoreOfTheSeedChroma() {
        val seed = Color(0xFFFF0000)
        for (dark in listOf(false, true)) {
            val content = themeColorScheme(seed, dark, ThemePaletteStyle.CONTENT)
            val tonalSpot = themeColorScheme(seed, dark, ThemePaletteStyle.TONAL_SPOT)
            assertNotEquals(tonalSpot.primary, content.primary)
            assertTrue(content.isLegible(dark))
        }
    }

    @Test
    fun rejectsRolesFromTheWrongModeOrWithoutContrast() {
        val light = themeColorScheme(Color(0xFFF8FEFF), false, ThemePaletteStyle.TONAL_SPOT)
        val dark = themeColorScheme(Color(0xFFF8FEFF), true, ThemePaletteStyle.TONAL_SPOT)
        assertFalse(light.copy(primary = dark.primary, onPrimary = dark.onPrimary).isLegible(false))
        assertFalse(dark.isLegible(false))
        assertFalse(light.isLegible(true))
        assertFalse(light.copy(onSurface = light.surface).isLegible(false))
        assertFalse(light.copy(surface = Color.Transparent).isLegible(false))
    }

    @Test
    fun illegibleSchemesUseTheFallback() {
        val light = themeColorScheme(DefaultThemeColor, false, ThemePaletteStyle.TONAL_SPOT)
        val dark = themeColorScheme(DefaultThemeColor, true, ThemePaletteStyle.TONAL_SPOT)
        assertSame(light, light.orIfIllegible(false) { dark })
        assertSame(light, dark.orIfIllegible(false) { light })
    }

    @Test
    fun translucentSavedSeedIsMadeOpaque() {
        val scheme = themeColorScheme(Color(0x00FF0000), false, ThemePaletteStyle.TONAL_SPOT)
        assertTrue(scheme.isLegible(false))
        assertEquals(themeColorScheme(Color(0xFFFF0000), false, ThemePaletteStyle.TONAL_SPOT).primary, scheme.primary)
    }
}
