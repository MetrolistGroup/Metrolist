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
    story: Boolean = false,
    storyBackgroundColor: Color = style.backgroundColor,
    storyCardColor: Color = style.backgroundColor,
    storyTextColor: Color = style.textColor,
    storySecondaryColor: Color = style.secondaryColor,
    shareUrl: String? = null,
): ImageBitmap {
    val bitmap = ImageBitmap(sizePx, if (story) sizePx * 16 / 9 else sizePx)
    val scale = sizePx / 340f
    val density = Density(scale, fontScale = 1f)
    val layout = lyricsImageLayout(style.contentScale, story)
    val contentScale = layout.contentScale
    val padding = layout.padding * scale
    val width = layout.lyricsWidth * scale
    val lyricsTop = layout.lyricsTop * scale
    val lyricsHeight = layout.lyricsHeight * scale
    val backgroundColor = if (story) storyBackgroundColor else style.backgroundColor
    val cardColor = if (story) storyCardColor else style.backgroundColor
    val textColor = if (story) storyTextColor else style.textColor
    val secondaryColor = if (story) storySecondaryColor else style.secondaryColor
    val lyricsInsetX = if (story) 18f * contentScale * scale else 0f
    val lyricsInsetY = if (story) 12f * contentScale * scale else 0f
    val textWidth = width - 2 * lyricsInsetX
    val textHeight = lyricsHeight - 2 * lyricsInsetY
    val textLeft = padding + lyricsInsetX
    val textStyle =
        TextStyle(
            color = textColor,
            fontWeight = FontWeight.Bold,
            textAlign = style.alignment,
        )

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
            constraints = Constraints(minWidth = textWidth.toInt(), maxWidth = textWidth.toInt()),
            density = density,
            layoutDirection = layoutDirection,
        )
    val fit =
        fitLyricsImageText(textHeight, style.textScale, maxFontSize = if (story) 34f else 50f) {
            measureLyrics(it).size.height.toFloat()
        }
    val lyricsLayout = measureLyrics(fit.fontSize)
    val renderedLyricsHeight = lyricsLayout.size.height * fit.drawScale
    val footerTop =
        if (story) {
            minOf(layout.footerTop, layout.lyricsTop + 12f * contentScale + renderedLyricsHeight / scale + 18f * contentScale)
        } else {
            layout.footerTop
        }
    val cardBottom = if (story) footerTop + layout.footerHeight else 0f
    val cardHeight = if (story) cardBottom - layout.cardTop else 0f
    val verticalOffset =
        if (story) {
            maxOf(0f, ((layout.canvasHeight - cardHeight) / 2f) - layout.cardTop)
        } else {
            0f
        }
    val verticalOffsetPx = verticalOffset * scale
    val textTop =
        lyricsTop + lyricsInsetY + verticalOffsetPx +
            if (story) 0f else (textHeight - renderedLyricsHeight) / 2f
    val textClipHeight = if (story) renderedLyricsHeight else textHeight
    CanvasDrawScope().draw(density, layoutDirection, Canvas(bitmap), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
        val path =
            Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(if (story) 0f else 20 * scale)))
            }
        clipPath(path) {
            drawRect(backgroundColor)
            if (!story) {
                when (style.background) {
                    LyricsImageBackground.SOLID -> {}

                    LyricsImageBackground.BLUR -> {
                        blurredArtwork?.let {
                            drawCover(it, Offset.Zero, size.width)
                            drawRect(Color.Black.copy(alpha = 0.3f))
                        }
                    }

                    LyricsImageBackground.GRADIENT -> {
                        val colors = palette.firstOrNull()?.let(::getGradientColors) ?: listOf(style.backgroundColor, style.backgroundColor)
                        drawRect(Brush.linearGradient(colors))
                    }
                }
            }
            if (story) {
                val cardTop = (layout.cardTop + verticalOffset) * scale
                val cardHeightPx = cardHeight * scale
                drawRoundRect(
                    cardColor,
                    topLeft = Offset(padding, cardTop),
                    size = Size(width, cardHeightPx),
                    cornerRadius = CornerRadius(28f * scale),
                )
            }
            artwork?.let {
                val topLeft = Offset(layout.artworkX * scale, (layout.artworkTop + verticalOffset) * scale)
                val artworkSide = layout.artworkSize * scale
                if (story) {
                    val artworkClip =
                        Path().apply {
                            addRoundRect(
                                RoundRect(
                                    topLeft.x,
                                    topLeft.y,
                                    topLeft.x + artworkSide,
                                    topLeft.y + artworkSide,
                                    CornerRadius(18 * scale),
                                ),
                            )
                        }
                    clipPath(artworkClip) { drawCover(it, topLeft, artworkSide) }
                } else {
                    drawCover(it, topLeft, artworkSide)
                }
            }

            fun label(
                text: String,
                x: Float,
                y: Float,
                fontSize: Float,
                color: Color,
                maxWidth: Float,
                shrinkToFit: Boolean = false,
            ) {
                fun measure(fontSize: Float) =
                    textMeasurer.measure(
                        text,
                        style =
                            TextStyle(
                                color = color,
                                fontSize = fontSize.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Start,
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        constraints =
                            Constraints(
                                minWidth = 0,
                                maxWidth = (maxWidth * scale).toInt(),
                            ),
                        density = density,
                        layoutDirection = layoutDirection,
                    )
                var resolvedFontSize = fontSize
                var textLayout = measure(resolvedFontSize)
                while (shrinkToFit && (textLayout.didOverflowWidth || (textLayout.lineCount > 0 && textLayout.isLineEllipsized(0))) &&
                    resolvedFontSize > 4f
                ) {
                    resolvedFontSize = (resolvedFontSize - 0.25f).coerceAtLeast(4f)
                    textLayout = measure(resolvedFontSize)
                }
                drawText(textLayout, topLeft = Offset(x * scale, y * scale))
            }
            val headerX =
                if (story && artwork != null) {
                    layout.artworkX + layout.artworkSize + 14f * contentScale
                } else if (story) {
                    layout.padding + 18f * contentScale
                } else if (artwork == null) {
                    layout.padding
                } else {
                    layout.padding + 80f * contentScale
                }
            val headerWidth =
                if (story && artwork != null) {
                    layout.lyricsWidth - (headerX - layout.padding) - 18f * contentScale
                } else if (story) {
                    layout.lyricsWidth - 36f * contentScale
                } else {
                    340f - layout.padding - headerX
                }
            label(
                title,
                headerX,
                layout.titleTop + verticalOffset,
                (if (story) 20f else 18f) * contentScale,
                textColor,
                headerWidth,
            )
            label(
                artist,
                headerX,
                layout.artistTop + verticalOffset,
                (if (story) 16f else 14f) * contentScale,
                secondaryColor,
                headerWidth,
            )
            val textX =
                textLeft +
                    when (style.alignment) {
                        TextAlign.Left -> 0f
                        TextAlign.Right -> textWidth * (1f - fit.drawScale)
                        else -> textWidth * (1f - fit.drawScale) / 2f
                    }
            clipRect(textLeft, textTop, textLeft + textWidth, textTop + textClipHeight) {
                translate(textX, textTop) {
                    scale(fit.drawScale, pivot = Offset.Zero) { drawText(lyricsLayout) }
                }
            }
            val footerScale = scale * contentScale
            drawCircle(
                secondaryColor,
                radius = (if (story) 10 else 22) * footerScale,
                center =
                    Offset(
                        padding + (if (story) 28f else 22f) * footerScale,
                        (footerTop + verticalOffset) * scale + (if (story) 14 else 22) * footerScale,
                    ),
            )
            translate(
                padding + (if (story) 18f else 3f) * footerScale,
                (footerTop + verticalOffset) * scale + (if (story) 4 else 3) * footerScale,
            ) {
                with(logo) {
                    draw(
                        Size((if (story) 20 else 38) * footerScale, (if (story) 20 else 38) * footerScale),
                        colorFilter = ColorFilter.tint(if (story) cardColor else style.backgroundColor),
                    )
                }
            }
            label(
                appName,
                layout.padding + (if (story) 44f else 52f) * contentScale,
                footerTop + verticalOffset + (if (story) 8f else 13f) * contentScale,
                (if (story) 12f else 14f) * contentScale,
                secondaryColor,
                layout.lyricsWidth - (if (story) 62f else 52f) * contentScale,
            )
            if (story && shareUrl != null) {
                label(
                    shareUrl,
                    layout.padding + 44f * contentScale,
                    footerTop + verticalOffset + 20f * contentScale,
                    7f * contentScale,
                    secondaryColor,
                    layout.lyricsWidth - 62f * contentScale,
                    shrinkToFit = true,
                )
            }
        }
    }
    return bitmap
}
