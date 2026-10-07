/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import com.materialkolor.score.Score

val DefaultThemeColor = Color(0xFF9D72FF)

@Composable
fun MetrolistTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    themeColor: Color = DefaultThemeColor,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val useSystemDynamicColor = false

    val baseColorScheme = if (useSystemDynamicColor) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        rememberDynamicColorScheme(
            seedColor = themeColor,
            isDark = darkTheme,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            style = PaletteStyle.TonalSpot,
        )
    }

    val colorScheme = remember(baseColorScheme, pureBlack, darkTheme) {
        val branded =
            if (themeColor == DefaultThemeColor) {
                if (darkTheme) {
                    baseColorScheme.copy(
                        primary = SevenXTunePalette.Violet,
                        onPrimary = Color(0xFF220A4A),
                        primaryContainer = Color(0xFF442078),
                        onPrimaryContainer = Color(0xFFEBDDFF),
                        secondary = SevenXTunePalette.ElectricBlue,
                        onSecondary = Color(0xFF002F3D),
                        secondaryContainer = Color(0xFF104C60),
                        onSecondaryContainer = Color(0xFFC6F1FF),
                        tertiary = SevenXTunePalette.PulsePink,
                        onTertiary = Color(0xFF4A0E35),
                        tertiaryContainer = Color(0xFF68204F),
                        onTertiaryContainer = Color(0xFFFFD9EE),
                    )
                } else {
                    baseColorScheme.copy(
                        primary = Color(0xFF6D37C9),
                        onPrimary = Color.White,
                        primaryContainer = Color(0xFFE9DDFF),
                        onPrimaryContainer = Color(0xFF250050),
                        secondary = Color(0xFF006984),
                        onSecondary = Color.White,
                        secondaryContainer = Color(0xFFB9ECFF),
                        onSecondaryContainer = Color(0xFF001F29),
                        tertiary = Color(0xFF9E2A6E),
                        onTertiary = Color.White,
                        tertiaryContainer = Color(0xFFFFD8EB),
                        onTertiaryContainer = Color(0xFF3C0826),
                    )
                }
            } else {
                baseColorScheme
            }

        branded
            .midnightPulseSurfaces(darkTheme)
            .let { if (darkTheme && pureBlack) it.pureBlack(true) else it }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = SevenXTuneShapes,
        content = content,
    )
}

private fun ColorScheme.midnightPulseSurfaces(darkTheme: Boolean): ColorScheme =
    if (!darkTheme) {
        this
    } else {
        copy(
            background = SevenXTunePalette.Midnight,
            onBackground = SevenXTunePalette.TextPrimary,
            surface = SevenXTunePalette.Surface,
            onSurface = SevenXTunePalette.TextPrimary,
            surfaceVariant = SevenXTunePalette.SurfaceRaised,
            onSurfaceVariant = SevenXTunePalette.TextSecondary,
            inverseSurface = SevenXTunePalette.TextPrimary,
            inverseOnSurface = SevenXTunePalette.Midnight,
            surfaceTint = SevenXTunePalette.Violet,
        )
    }

fun Bitmap.extractThemeColor(): Color = Color(
    Palette.from(this)
        .maximumColorCount(8)
        .generate()
        .rankedColors(1, DefaultThemeColor.toArgb())
        .first()
)

internal fun Palette.rankedColors(
    desiredColorCount: Int,
    fallbackColor: Int,
): List<Int> = Score.score(
    swatches.associate { it.rgb to it.population },
    desiredColorCount,
    fallbackColor,
    true,
)

fun ColorScheme.pureBlack(apply: Boolean) =
    if (apply) copy(
        surface = Color.Black,
        background = Color.Black
    ) else this

val ColorSaver = object : Saver<Color, Int> {
    override fun restore(value: Int): Color = Color(value)
    override fun SaverScope.save(value: Color): Int = value.toArgb()
}
