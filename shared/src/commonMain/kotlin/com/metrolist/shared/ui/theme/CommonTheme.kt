/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:Suppress("FunctionName")

package com.metrolist.shared.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.ThemePaletteStyle
import com.metrolist.shared.ui.haptics.MetrolistHapticsProvider
import com.metrolist.shared.ui.settings.IosAppearanceProvider
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.artworkColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val DefaultThemeColor = Color(0xFF6750A4)

// Official material-color-utilities Score fallback (Google Blue), used when the artwork has no usable colour.
val ArtworkFallbackSeedColor = Color(0xFF4285F4)

// Official material-color-utilities quantizer sample size (QuantizerCelebi.quantize(pixels, 128)).
private const val ARTWORK_QUANTIZER_MAX_COLORS = 128

fun parseThemeSeedColor(value: Long): Color {
    if (value == 0L) return DefaultThemeColor
    val ulong = value.toULong()
    return try {
        val color =
            if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || ulong <= 0xFFFFFFFFUL) {
                Color(color = 0xFF000000L or (value and 0xFFFFFFL))
            } else {
                val c = Color(value = ulong)
                if (c.red.isNaN() || c.green.isNaN() || c.blue.isNaN()) {
                    Color(color = 0xFF000000L or (value and 0xFFFFFFL))
                } else {
                    c
                }
            }
        if (color.red.isNaN() || color.green.isNaN() || color.blue.isNaN()) {
            DefaultThemeColor
        } else {
            color
        }
    } catch (_: Exception) {
        DefaultThemeColor
    }
}

fun resolveThemeSeedColor(
    materialYouEnabled: Boolean,
    songArtworkColorsEnabled: Boolean,
    selectedThemeColor: Long,
    artworkSeedColor: Color?,
): Color =
    when {
        !materialYouEnabled -> parseThemeSeedColor(selectedThemeColor)
        songArtworkColorsEnabled && artworkSeedColor != null -> artworkSeedColor
        else -> DefaultThemeColor
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MetrolistTheme(
    seedColor: Color = DefaultThemeColor,
    darkTheme: Boolean = false,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val baseColorScheme = rememberDynamicColorScheme(seedColor, darkTheme)

    val colorScheme =
        remember(baseColorScheme, pureBlack, darkTheme) {
            if (darkTheme && pureBlack) {
                baseColorScheme.pureBlack()
            } else {
                baseColorScheme
            }
        }

    val motionScheme = remember { MotionScheme.expressive() }
    val animatedColorScheme = animateColorScheme(colorScheme, motionScheme)

    IosAppearanceProvider {
        MaterialExpressiveTheme(
            colorScheme = animatedColorScheme,
            typography = MetrolistTypography(),
            shapes = MetrolistShapes,
            motionScheme = motionScheme,
            content = { MetrolistHapticsProvider(content) },
        )
    }
}

@Composable
fun rememberDynamicColorScheme(
    seedColor: Color,
    isDark: Boolean,
): ColorScheme {
    val paletteStyle = Settings.shared.themePaletteStyle
    return remember(seedColor, isDark, paletteStyle) {
        themeColorScheme(seedColor, isDark, paletteStyle)
    }
}

fun themeColorScheme(
    seedColor: Color,
    isDark: Boolean,
    paletteStyle: ThemePaletteStyle,
    contrastLevel: Double = 0.0,
): ColorScheme {
    val seed = if (seedColor.isOpaqueAndFinite()) seedColor else seedColor.copy(alpha = 1f).takeIf { it.isOpaqueAndFinite() }
    val contrast = contrastLevel.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
    val scheme = generatedColorScheme(seed ?: DefaultThemeColor, isDark, paletteStyle, contrast)
    if (seed == DefaultThemeColor || scheme.isLegible(isDark)) return scheme
    Logger.w("Theme", "Generated $paletteStyle palette is illegible; using the default seed")
    return generatedColorScheme(DefaultThemeColor, isDark, paletteStyle, contrast)
}

private fun generatedColorScheme(
    seedColor: Color,
    isDark: Boolean,
    paletteStyle: ThemePaletteStyle,
    contrastLevel: Double,
): ColorScheme =
    dynamicColorScheme(
        seedColor = seedColor,
        isDark = isDark,
        style =
            when (paletteStyle) {
                ThemePaletteStyle.TONAL_SPOT -> PaletteStyle.TonalSpot
                ThemePaletteStyle.VIBRANT -> PaletteStyle.Vibrant
                ThemePaletteStyle.EXPRESSIVE -> PaletteStyle.Expressive
                ThemePaletteStyle.RAINBOW -> PaletteStyle.Rainbow
                ThemePaletteStyle.FRUIT_SALAD -> PaletteStyle.FruitSalad
                ThemePaletteStyle.MONOCHROME -> PaletteStyle.Monochrome
                ThemePaletteStyle.NEUTRAL -> PaletteStyle.Neutral
                ThemePaletteStyle.CONTENT -> PaletteStyle.Content
            },
        contrastLevel = contrastLevel,
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
    )

fun ColorScheme.pureBlack() =
    copy(
        surface = Color.Black,
        background = Color.Black,
        surfaceDim = Color.Black,
        surfaceBright = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainer = Color.Black,
        surfaceContainerHigh = Color.Black,
        surfaceContainerHighest = Color.Black,
        surfaceVariant = Color.Black,
    )

expect class ThemeColorExtractor() {
    fun extractSeedColor(image: Any): Color

    fun extractGradientColors(
        image: Any,
        fallback: Color = DefaultThemeColor,
    ): List<Color>

    fun isArtworkBright(image: Any): Boolean
}

fun Color.toHsv(): FloatArray {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, maxOf(g, b))
    val min = minOf(r, minOf(g, b))
    val d = max - min
    val h =
        when (max) {
            min -> 0f
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
    val s = if (max == 0f) 0f else d / max

    val normalizedH = (h * 60f + 360f) % 360f
    return floatArrayOf(normalizedH, s, max)
}

fun selectArtworkSeedColor(
    width: Int,
    height: Int,
    pixelAt: (x: Int, y: Int) -> Int,
): Color {
    if (width <= 0 || height <= 0) return DefaultThemeColor
    val pixels =
        IntArray(64 * 64) { index ->
            pixelAt((index % 64) * width / 64, (index / 64) * height / 64)
        }.filter { it ushr 24 >= 128 }
    if (pixels.isEmpty()) return ArtworkFallbackSeedColor
    val opaque = pixels.map { it or 0xFF000000.toInt() }.toIntArray()
    val colors = QuantizerCelebi.quantize(opaque, ARTWORK_QUANTIZER_MAX_COLORS)
    return Color(Score.score(colors, fallbackColorArgb = ArtworkFallbackSeedColor.toArgb()).first())
}

internal fun isArtworkBright(argbPixels: List<Int>): Boolean {
    if (argbPixels.isEmpty()) return false
    val opaque = argbPixels.filter { it ushr 24 and 0xFF >= 128 }
    if (opaque.isEmpty()) return false
    return opaque
        .map { pixel ->
            val red = pixel ushr 16 and 0xFF
            val green = pixel ushr 8 and 0xFF
            val blue = pixel and 0xFF
            (red * 299 + green * 587 + blue * 114) / 1_000
        }.average() >= 160.0
}

fun getGradientColors(accentColor: Color): List<Color> {
    val hsv = accentColor.toHsv()
    val c1 = Color.hsv(hsv[0], hsv[1].coerceAtLeast(0.7f), 0.4f)
    val c2 = Color.hsv((hsv[0] + 30f) % 360f, hsv[1].coerceIn(0.4f, 0.6f), 0.2f)
    val c3 = Color.hsv((hsv[0] - 30f + 360f) % 360f, hsv[1].coerceIn(0.3f, 0.5f), 0.1f)
    return listOf(c1, c2, c3, Color.Black)
}

@Composable
fun rememberArtworkSeedColor(url: String?): Color? {
    val context = LocalPlatformContext.current
    var color by remember(url) { mutableStateOf<Color?>(null) }
    LaunchedEffect(url) {
        color =
            url?.let {
                runCatching {
                    val request =
                        ImageRequest
                            .Builder(context)
                            .artworkColors(it)
                            .build()
                    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
                    val bitmap = (result?.image as? BitmapImage)?.bitmap
                    withContext(Dispatchers.Default) { bitmap?.let(ThemeColorExtractor()::extractSeedColor) }
                }.getOrNull()
            }
    }
    return color
}
