/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.scale
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.quantize.QuantizerCelebi
import com.metrolist.shared.ui.theme.toHsv
import com.metrolist.shared.utils.artworkColorSourceUrl
import com.metrolist.shared.utils.artworkColors
import com.metrolist.shared.viewmodels.PlayerArtworkColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

object PlayerColorExtractor {
    private val paletteMutex = Mutex()
    private val palettes = linkedMapOf<String, Map<Int, Int>>()

    suspend fun extractColors(
        context: Context,
        imageLoader: ImageLoader,
        artworkUrl: String,
        fallbackColor: Color,
    ): PlayerArtworkColors? {
        val key = artworkColorSourceUrl(artworkUrl)
        val palette =
            paletteMutex.withLock {
                palettes.remove(key)?.also { palettes[key] = it } ?: run {
                    val request =
                        ImageRequest
                            .Builder(context)
                            .artworkColors(artworkUrl)
                            .allowHardware(false)
                            .build()
                    val result = imageLoader.execute(request) as? SuccessResult ?: return@withLock null
                    quantize(result.image.toBitmap()).also { palette ->
                        palettes[key] = palette
                        while (palettes.size > 32) palettes.remove(palettes.keys.first())
                    }
                }
            } ?: return null
        return colorsFromPalette(palette, fallbackColor)
    }

    private suspend fun quantize(bitmap: Bitmap): Map<Int, Int> =
        withContext(Dispatchers.Default) {
            val scale = sqrt(10_000.0 / (bitmap.width.toDouble() * bitmap.height)).coerceAtMost(1.0)
            val source =
                if (scale < 1.0) {
                    bitmap.scale(ceil(bitmap.width * scale).toInt(), ceil(bitmap.height * scale).toInt(), false)
                } else {
                    bitmap
                }
            try {
                val pixels = IntArray(source.width * source.height)
                source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
                quantizePixels(pixels)
            } finally {
                if (source !== bitmap) source.recycle()
            }
        }

    internal fun extractColors(
        pixels: IntArray,
        fallbackColor: Color,
    ): PlayerArtworkColors = colorsFromPalette(quantizePixels(pixels), fallbackColor)

    private fun quantizePixels(pixels: IntArray): Map<Int, Int> {
        val accepted = IntArray(pixels.size)
        var count = 0
        for (pixel in pixels) {
            if (pixel ushr 24 >= 128 && isAllowedColor(pixel)) accepted[count++] = pixel or 0xFF000000.toInt()
        }
        val opaque = if (count == accepted.size) accepted else accepted.copyOf(count)
        val colors =
            if (opaque.isEmpty()) {
                emptyMap()
            } else {
                QuantizerCelebi.quantize(opaque, 8).filterKeys(::isAllowedColor)
            }
        return colors
    }

    private fun colorsFromPalette(
        colors: Map<Int, Int>,
        fallbackColor: Color,
    ): PlayerArtworkColors {
        val dominant = colors.maxByOrNull { it.value }?.key?.let(::Color) ?: fallbackColor
        val best = colors.maxByOrNull { (argb, population) -> calculateColorWeight(argb, population) }?.key?.let(::Color)
        val hsv = best?.toHsv()
        val vibrant = hsv != null && hsv[1] > 0.25f && hsv[2] > 0.2f && hsv[2] < 0.9f
        val primary = enhanceColorVividness(if (vibrant) best else dominant, if (vibrant) 1.3f else 1.1f)
        return PlayerArtworkColors(
            dominant = dominant,
            gradient =
                listOf(
                    primary,
                    primary.copy(red = primary.red * 0.6f, green = primary.green * 0.6f, blue = primary.blue * 0.6f),
                    Color.Black,
                ),
        )
    }

    internal fun isAllowedColor(argb: Int): Boolean {
        val red = ((argb shr 16) and 0xFF) / 255f
        val green = ((argb shr 8) and 0xFF) / 255f
        val blue = (argb and 0xFF) / 255f
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val delta = max - min
        val valueSaturation = if (max == 0f) 0f else delta / max
        val lightness = max * (1f - valueSaturation / 2f)
        if (lightness <= 0.05f || lightness >= 0.95f) return false
        val hue =
            when {
                delta == 0f -> 0f
                max == red -> ((green - blue) / delta).let { if (it < 0f) it + 6f else it } * 60f
                max == green -> ((blue - red) / delta + 2f) * 60f
                else -> ((red - green) / delta + 4f) * 60f
            }
        val saturation = max * valueSaturation / (1f - abs(2f * lightness - 1f))
        return (hue + 360f) % 360f !in 10f..37f || saturation > 0.82f
    }

    private fun calculateColorWeight(
        argb: Int,
        population: Int,
    ): Float {
        val hsv = Color(argb).toHsv()
        val vibrancyBonus = if (hsv[1] > 0.3f && hsv[2] > 0.3f) 1.5f else 1f
        return population * vibrancyBonus * (hsv[1] + hsv[2])
    }

    private fun enhanceColorVividness(
        color: Color,
        saturationFactor: Float,
    ): Color {
        val hsv = color.toHsv()
        return Color.hsv(hsv[0], (hsv[1] * saturationFactor).coerceAtMost(1f), (hsv[2] * 0.9f).coerceIn(0.4f, 0.85f))
    }
}
