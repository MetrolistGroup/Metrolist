/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components.lyrics

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.metrolist.shared.ui.theme.getGradientColors

internal enum class LyricsRenderLayer { COMPLETE, BACKGROUND, FOREGROUND }

internal enum class LyricsCardTemplate { CLASSIC, EDITORIAL, SPOTLIGHT }

internal enum class LyricsImageBackground { SOLID, BLUR, GRADIENT }

internal data class LyricsImageStyle(
    val template: LyricsCardTemplate = LyricsCardTemplate.CLASSIC,
    val background: LyricsImageBackground = LyricsImageBackground.SOLID,
    val backgroundColor: Color = Color(0xFF242424),
    val textColor: Color = Color.White,
    val secondaryColor: Color = Color.White.copy(alpha = 0.7f),
    val alignment: TextAlign = TextAlign.Center,
    val textScale: Float = 1f,
    val contentScale: Float = 1f,
)

private fun DrawScope.drawCover(
    image: ImageBitmap,
    topLeft: Offset,
    side: Float,
) {
    val crop = minOf(image.width, image.height)
    drawImage(
        image,
        srcOffset = IntOffset((image.width - crop) / 2, (image.height - crop) / 2),
        srcSize = IntSize(crop, crop),
        dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
        dstSize = IntSize(side.toInt(), side.toInt()),
        filterQuality = FilterQuality.High,
    )
}

internal fun prepareLyricsArtwork(image: ImageBitmap): Pair<List<Color>, ImageBitmap> {
    val sample = ImageBitmap(64, 64)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(sample), Size(64f, 64f)) {
        drawRect(Color.Black)
        drawCover(image, Offset.Zero, 64f)
    }
    val pixels = IntArray(64 * 64)
    sample.readPixels(pixels)
    val palette = lyricsArtworkPalette(pixels)
    val blurred = blurLyricsArtwork(pixels, 64, 64, 6)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(sample), Size(64f, 64f)) {
        blurred.forEachIndexed { index, pixel ->
            drawRect(Color(pixel), Offset((index % 64).toFloat(), (index / 64).toFloat()), Size(1f, 1f))
        }
    }
    return palette to sample
}

internal fun renderLyricsImage(
    lyrics: String,
    title: String,
    artist: String,
    appName: String,
    artwork: ImageBitmap?,
    blurredArtwork: ImageBitmap?,
    palette: List<Color>,
    style: LyricsImageStyle,
    textMeasurer: TextMeasurer,
    logo: Painter,
    layoutDirection: LayoutDirection,
    sizePx: Int = 2160,
    activeLine: Int? = null,
    activeWordRange: IntRange? = null,
    layer: LyricsRenderLayer = LyricsRenderLayer.COMPLETE,
): ImageBitmap {
    val bitmap = ImageBitmap(sizePx, sizePx)
    val scale = sizePx / 340f
    val density = Density(scale, fontScale = 1f)
    val layout = lyricsImageLayout(style.contentScale, style.template)
    val contentScale = layout.contentScale
    val padding = layout.padding * scale
    val width = layout.lyricsWidth * scale
    val lyricsTop = layout.lyricsTop * scale
    val lyricsHeight = layout.lyricsHeight * scale
    val editorial = style.template == LyricsCardTemplate.EDITORIAL
    val textStyle = TextStyle(
        color = style.textColor,
        fontFamily = if (editorial) FontFamily.Serif else FontFamily.Default,
        fontWeight = if (editorial) FontWeight.Medium else FontWeight.Bold,
        textAlign = style.alignment,
    )

    fun lyricsText(size: Float) =
        buildAnnotatedString {
            var sourceOffset = 0
            lyrics.lines().forEachIndexed { index, line ->
                if (index > 0) {
                    withStyle(ParagraphStyle(lineHeight = (size * 0.5f).sp)) {
                        withStyle(SpanStyle(fontSize = (size * 0.5f).sp)) { append('\u200B') }
                    }
                }
                withStyle(ParagraphStyle(lineHeight = (size * 1.2f).sp)) {
                    val lineColor = if (activeLine == null || activeLine == index) style.textColor else style.textColor.copy(alpha = style.textColor.alpha * 0.35f)
                    val start = activeWordRange?.first?.minus(sourceOffset)?.coerceIn(0, line.length)
                    val end = activeWordRange?.last?.plus(1)?.minus(sourceOffset)?.coerceIn(0, line.length)
                    if (start != null && end != null && end > start) {
                        val inactive = lineColor.copy(alpha = lineColor.alpha * 0.45f)
                        withStyle(SpanStyle(color = inactive)) { append(line.substring(0, start)) }
                        withStyle(SpanStyle(color = style.textColor, background = style.textColor.copy(alpha = 0.18f))) {
                            append(line.substring(start, end))
                        }
                        withStyle(SpanStyle(color = inactive)) { append(line.substring(end)) }
                    } else {
                        withStyle(SpanStyle(color = lineColor)) { append(line) }
                    }
                }
                sourceOffset += line.length + 1
            }
        }

    fun measureLyrics(size: Float) =
        textMeasurer.measure(
            lyricsText(size),
            style = textStyle.copy(fontSize = size.sp, lineHeight = (size * 1.2f).sp),
            constraints = Constraints(minWidth = width.toInt(), maxWidth = width.toInt()),
            density = density,
            layoutDirection = layoutDirection,
        )
    val fit = fitLyricsImageText(lyricsHeight, style.textScale) { measureLyrics(it).size.height.toFloat() }
    val lyricsLayout = measureLyrics(fit.fontSize)
    CanvasDrawScope().draw(density, layoutDirection, Canvas(bitmap), Size(sizePx.toFloat(), sizePx.toFloat())) {
        val path = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(20 * scale))) }
        clipPath(path) {
            if (layer != LyricsRenderLayer.FOREGROUND) {
                drawRect(style.backgroundColor)
                when (style.background) {
                    LyricsImageBackground.SOLID -> Unit
                    LyricsImageBackground.BLUR -> blurredArtwork?.let {
                        drawCover(it, Offset.Zero, size.width)
                        drawRect(Color.Black.copy(alpha = 0.3f))
                    }
                    LyricsImageBackground.GRADIENT -> {
                        val colors = palette.firstOrNull()?.let(::getGradientColors) ?: listOf(style.backgroundColor, style.backgroundColor)
                        drawRect(Brush.linearGradient(colors))
                    }
                }
                if (style.template == LyricsCardTemplate.SPOTLIGHT) {
                    artwork?.let { drawCover(it, Offset.Zero, size.width) }
                }
            }
            if (layer == LyricsRenderLayer.BACKGROUND) return@clipPath
            // Keep contrast on the foreground so moving backgrounds never wash out the lyrics.
            if (style.template == LyricsCardTemplate.SPOTLIGHT) {
                drawRect(Brush.verticalGradient(listOf(
                    Color.Black.copy(alpha = 0.72f),
                    Color.Black.copy(alpha = 0.65f),
                    Color.Black.copy(alpha = 0.9f),
                )))
            }
            if (editorial) {
                drawLine(style.secondaryColor.copy(alpha = 0.4f), Offset(padding, 66f * scale), Offset(size.width - padding, 66f * scale), scale)
                artwork?.let { drawCover(it, Offset(size.width - padding - 28f * scale, layout.footerTop * scale), 28f * scale) }
            } else {
                artwork?.let { drawCover(it, Offset(padding, padding), layout.artworkSize * scale) }
            }

            fun label(
                text: String,
                x: Float,
                y: Float,
                fontSize: Float,
                color: Color,
                maxWidth: Float,
            ) {
                val layout =
                    textMeasurer.measure(
                        text,
                        style = TextStyle(color = color, fontSize = fontSize.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        constraints = Constraints(maxWidth = (maxWidth * scale).toInt().coerceAtLeast(1)),
                        density = density,
                        layoutDirection = layoutDirection,
                    )
                drawText(layout, topLeft = Offset(x * scale, y * scale))
            }
            val headerX = layout.padding + if (artwork == null || editorial) 0f else 80f * contentScale
            val headerWidth = 340f - layout.padding - headerX
            label(title, headerX, if (editorial) 25f else 34f * contentScale, if (editorial) 15f else 20f * contentScale, style.textColor, headerWidth)
            label(artist, headerX, if (editorial) 46f else 63f * contentScale, if (editorial) 11f else 16f * contentScale, style.secondaryColor, headerWidth)
            val textX =
                padding +
                    when (style.alignment) {
                        TextAlign.Left -> 0f
                        TextAlign.Right -> width * (1f - fit.drawScale)
                        else -> width * (1f - fit.drawScale) / 2f
                    }
            clipRect(padding, lyricsTop, padding + width, lyricsTop + lyricsHeight) {
                translate(textX, lyricsTop + (lyricsHeight - lyricsLayout.size.height * fit.drawScale) / 2f) {
                    scale(fit.drawScale, pivot = Offset.Zero) { drawText(lyricsLayout) }
                }
            }
            val footerScale = scale * contentScale
            drawCircle(
                style.secondaryColor,
                radius = 12 * footerScale,
                center = Offset(padding + 12 * footerScale, layout.footerTop * scale + 12 * footerScale),
            )
            translate(padding + 3 * footerScale, layout.footerTop * scale + 3 * footerScale) {
                with(logo) { draw(Size(18 * footerScale, 18 * footerScale), colorFilter = ColorFilter.tint(style.backgroundColor)) }
            }
            label(
                appName,
                layout.padding + 32f * contentScale,
                layout.footerTop + 5f * contentScale,
                11f * contentScale,
                style.secondaryColor,
                layout.lyricsWidth - 32f * contentScale - if (editorial && artwork != null) 36f else 0f,
            )
        }
    }
    return bitmap
}
