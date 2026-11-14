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

internal enum class LyricsImageBackground { SOLID, BLUR, GRADIENT }

internal data class LyricsImageStyle(
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
): ImageBitmap {
    val bitmap = ImageBitmap(sizePx, sizePx)
    val scale = sizePx / 340f
    val density = Density(scale, fontScale = 1f)
    val layout = lyricsImageLayout(style.contentScale)
    val contentScale = layout.contentScale
    val padding = layout.padding * scale
    val width = layout.lyricsWidth * scale
    val lyricsTop = layout.lyricsTop * scale
    val lyricsHeight = layout.lyricsHeight * scale
    val textStyle = TextStyle(color = style.textColor, fontWeight = FontWeight.Bold, textAlign = style.alignment)

    fun lyricsText(size: Float) =
        buildAnnotatedString {
            lyrics.lines().forEachIndexed { index, line ->
                if (index > 0) {
                    withStyle(ParagraphStyle(lineHeight = (size * 0.5f).sp)) {
                        withStyle(SpanStyle(fontSize = (size * 0.5f).sp)) { append('\u200B') }
                    }
                }
                withStyle(ParagraphStyle(lineHeight = (size * 1.2f).sp)) { append(line) }
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
            drawRect(style.backgroundColor)
            when (style.background) {
                LyricsImageBackground.SOLID -> {}

                LyricsImageBackground.BLUR -> {
                    if (blurredArtwork != null) {
                        drawCover(blurredArtwork, Offset.Zero, size.width)
                        drawRect(Color.Black.copy(alpha = 0.3f))
                    }
                }

                LyricsImageBackground.GRADIENT -> {
                    val colors = palette.firstOrNull()?.let(::getGradientColors) ?: listOf(style.backgroundColor, style.backgroundColor)
                    drawRect(Brush.linearGradient(colors))
                }
            }
            artwork?.let { drawCover(it, Offset(padding, padding), layout.artworkSize * scale) }

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
                        constraints = Constraints(maxWidth = (maxWidth * scale).toInt()),
                        density = density,
                        layoutDirection = layoutDirection,
                    )
                drawText(layout, topLeft = Offset(x * scale, y * scale))
            }
            val headerX = layout.padding + if (artwork == null) 0f else 80f * contentScale
            val headerWidth = 340f - layout.padding - headerX
            label(title, headerX, 34f * contentScale, 20f * contentScale, style.textColor, headerWidth)
            label(artist, headerX, 63f * contentScale, 16f * contentScale, style.secondaryColor, headerWidth)
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
                radius = 22 * footerScale,
                center = Offset(padding + 22 * footerScale, layout.footerTop * scale + 22 * footerScale),
            )
            translate(padding + 3 * footerScale, layout.footerTop * scale + 3 * footerScale) {
                with(logo) { draw(Size(38 * footerScale, 38 * footerScale), colorFilter = ColorFilter.tint(style.backgroundColor)) }
            }
            label(
                appName,
                layout.padding + 52f * contentScale,
                layout.footerTop + 13f * contentScale,
                14f * contentScale,
                style.secondaryColor,
                layout.lyricsWidth - 52f * contentScale,
            )
        }
    }
    return bitmap
}
