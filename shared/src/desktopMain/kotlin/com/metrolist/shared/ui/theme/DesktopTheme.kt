/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import java.util.Locale
import kotlin.math.roundToInt

@Composable
actual fun localeAwareSystemFontFamily(languageTag: String): FontFamily =
    remember(languageTag) {
        resolveLocaleAwareSystemFontFamily(languageTag)
    }

private fun resolveLocaleAwareSystemFontFamily(languageTag: String): FontFamily {
    val locale = Locale.forLanguageTag(languageTag)
    val sample = locale.getDisplayName(locale)
    val manager = FontMgr.default
    val base = manager.matchFamilyStyle(null, FontStyle.NORMAL) ?: return FontFamily.Default
    val missingCodePoint = sample.codePoints().toArray().firstOrNull { base.getUTF32Glyph(it).toInt() == 0 }
    if (missingCodePoint == null) {
        base.close()
        return FontFamily.Default
    }

    val matched =
        manager.matchFamilyStyleCharacter(
            base.familyName,
            FontStyle.NORMAL,
            arrayOf(languageTag),
            missingCodePoint,
        )
    base.close()
    return matched?.let { FontFamily(Typeface(it)) } ?: FontFamily.Default
}

actual class ThemeColorExtractor {
    actual fun extractSeedColor(image: Any): Color =
        withImage(image) { skiaImage ->
            Bitmap().use { bitmap ->
                val imageInfo = ImageInfo(skiaImage.width, skiaImage.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
                bitmap.allocPixels(imageInfo)
                bitmap.erase(0)
                Canvas(bitmap).use { it.drawImage(skiaImage, 0f, 0f) }

                val straightAlphaInfo = ImageInfo(skiaImage.width, skiaImage.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
                val pixels = bitmap.readPixels(straightAlphaInfo, bitmap.rowBytes, 0, 0) ?: return@withImage DefaultThemeColor
                selectArtworkSeedColor(skiaImage.width, skiaImage.height) { x, y ->
                    val offset = y * bitmap.rowBytes + x * 4
                    ((pixels[offset + 3].toInt() and 0xFF) shl 24) or
                        ((pixels[offset].toInt() and 0xFF) shl 16) or
                        ((pixels[offset + 1].toInt() and 0xFF) shl 8) or
                        (pixels[offset + 2].toInt() and 0xFF)
                }
            }
        }

    actual fun extractGradientColors(
        image: Any,
        fallback: Color,
    ): List<Color> =
        withImage(image) { skiaImage ->
            Bitmap().use { bitmap ->
                val imageInfo = ImageInfo(skiaImage.width, skiaImage.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
                bitmap.allocPixels(imageInfo)
                Canvas(bitmap).use { it.drawImage(skiaImage, 0f, 0f) }

                val pixels = bitmap.readPixels(imageInfo, bitmap.rowBytes, 0, 0) ?: return@withImage getGradientColors(fallback)

                val colorCounts = mutableMapOf<Int, Int>()
                val step = (skiaImage.width * skiaImage.height / 1000).coerceAtLeast(1)

                for (i in 0 until (skiaImage.width * skiaImage.height) step step) {
                    val offset = i * 4
                    if (offset + 3 >= pixels.size) break
                    val r = pixels[offset].toInt() and 0xFF
                    val g = pixels[offset + 1].toInt() and 0xFF
                    val b = pixels[offset + 2].toInt() and 0xFF
                    val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

                    val hsv = Color(argb).toHsv()
                    if (hsv[2] !in 0.1f..0.9f || hsv[1] < 0.05f) continue

                    colorCounts[argb] = colorCounts.getOrDefault(argb, 0) + 1
                }

                val topColors =
                    colorCounts.entries
                        .sortedByDescending { it.value * (Color(it.key).toHsv()[1] + 0.1f) }
                        .take(3)
                        .map { Color(it.key) }

                when (topColors.size) {
                    0 -> {
                        getGradientColors(fallback)
                    }

                    1 -> {
                        getGradientColors(topColors[0])
                    }

                    2 -> {
                        val c1 = topColors[0]
                        val c2 = topColors[1]
                        val hsv1 = c1.toHsv()
                        val c3 = Color.hsv((hsv1[0] + 30f) % 360f, hsv1[1].coerceIn(0.3f, 0.5f), 0.1f)
                        listOf(c1, c2, c3, Color.Black)
                    }

                    else -> {
                        val c1 = topColors[0]
                        val c2 = topColors[1]
                        val c3 = topColors[2]
                        listOf(c1, c2, c3, Color.Black)
                    }
                }
            }
        }

    actual fun isArtworkBright(image: Any): Boolean =
        withImage(image) { skiaImage ->
            Bitmap().use { bitmap ->
                val imageInfo = ImageInfo(skiaImage.width, skiaImage.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
                bitmap.allocPixels(imageInfo)
                Canvas(bitmap).use { it.drawImage(skiaImage, 0f, 0f) }
                val pixels = bitmap.readPixels(imageInfo, bitmap.rowBytes, 0, 0) ?: return@withImage false
                val step = (skiaImage.width * skiaImage.height / 1000).coerceAtLeast(1)
                var total = 0.0
                var count = 0
                for (i in 0 until (skiaImage.width * skiaImage.height) step step) {
                    val off = i * 4
                    if (off + 2 >= pixels.size) break
                    val r = (pixels[off].toInt() and 0xFF) / 255f
                    val g = (pixels[off + 1].toInt() and 0xFF) / 255f
                    val b = (pixels[off + 2].toInt() and 0xFF) / 255f
                    total += 0.299 * r + 0.587 * g + 0.114 * b
                    count++
                }
                count > 0 && (total / count) > 0.4
            }
        }

    private fun <T> withImage(
        image: Any,
        block: (Image) -> T,
    ): T {
        val ownedImage =
            when (image) {
                is Image -> null
                is Bitmap -> Image.makeFromBitmap(image)
                else -> throw IllegalArgumentException("Unsupported image type: ${image::class.simpleName}")
            }
        val skiaImage = ownedImage ?: (image as Image)
        ownedImage.use {
            val scale = (128f / maxOf(skiaImage.width, skiaImage.height)).coerceAtMost(1f)
            if (scale == 1f) return block(skiaImage)
            val width = (skiaImage.width * scale).roundToInt().coerceAtLeast(1)
            val height = (skiaImage.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap().use { sampled ->
                sampled.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
                sampled.erase(0)
                Canvas(sampled).use { canvas ->
                    canvas.drawImageRect(skiaImage, Rect.makeWH(width.toFloat(), height.toFloat()))
                }
                sampled.setImmutable()
                Image.makeFromBitmap(sampled).use { return block(it) }
            }
        }
    }
}

@Composable
internal actual fun rememberReducedMotion(): Boolean = false
