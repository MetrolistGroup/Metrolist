/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components.lyrics

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
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

internal fun renderLyricsImage(
    lyrics: String,
    title: String,
    artist: String,
    appName: String,
    artwork: ImageBitmap?,
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
    val lyricsInsetX = (if (story) 18f else 16f) * contentScale * scale
    val lyricsInsetY = 12f * contentScale * scale
    val textWidth = width - 2 * lyricsInsetX
    val textHeight = lyricsHeight - 2 * lyricsInsetY
    val textLeft = padding + lyricsInsetX
    val textStyle =
        TextStyle(
            color = storyTextColor,
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
        fitLyricsImageText(textHeight, style.textScale, maxFontSize = if (story) 34f else 30f) {
            measureLyrics(it).size.height.toFloat()
        }
    val lyricsLayout = measureLyrics(fit.fontSize)
    val renderedLyricsHeight = lyricsLayout.size.height * fit.drawScale
    val footerTop =
        minOf(layout.footerTop, layout.lyricsTop + 12f * contentScale + renderedLyricsHeight / scale + 18f * contentScale)
    val cardBottom = footerTop + layout.footerHeight
    val cardHeight = cardBottom - layout.cardTop
    val canvasHeight = layout.canvasHeight
    val verticalOffset = maxOf(0f, ((canvasHeight - cardHeight) / 2f) - layout.cardTop)
    val verticalOffsetPx = verticalOffset * scale
    val textTop = lyricsTop + lyricsInsetY + verticalOffsetPx
    val textClipHeight = renderedLyricsHeight
    CanvasDrawScope().draw(density, layoutDirection, Canvas(bitmap), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
        val path =
            Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(0f)))
            }
        clipPath(path) {
            drawRect(storyBackgroundColor)
            val cardTop = (layout.cardTop + verticalOffset) * scale
            val cardHeightPx = cardHeight * scale
            drawRoundRect(
                storyCardColor,
                topLeft = Offset(padding, cardTop),
                size = Size(width, cardHeightPx),
                cornerRadius = CornerRadius(28f * scale),
            )
            artwork?.let {
                val topLeft = Offset(layout.artworkX * scale, (layout.artworkTop + verticalOffset) * scale)
                val artworkSide = layout.artworkSize * scale
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
                if (artwork != null) {
                    layout.artworkX + layout.artworkSize + 14f * contentScale
                } else {
                    layout.padding + 18f * contentScale
                }
            val headerWidth =
                if (artwork != null) {
                    layout.lyricsWidth - (headerX - layout.padding) - 18f * contentScale
                } else {
                    layout.lyricsWidth - 36f * contentScale
                }
            label(
                title,
                headerX,
                layout.titleTop + verticalOffset,
                (if (story) 20f else 18f) * contentScale,
                storyTextColor,
                headerWidth,
            )
            label(
                artist,
                headerX,
                layout.artistTop + verticalOffset,
                (if (story) 16f else 14f) * contentScale,
                storySecondaryColor,
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
                storySecondaryColor,
                radius = 10 * footerScale,
                center =
                    Offset(
                        padding + 28f * footerScale,
                        (footerTop + verticalOffset) * scale + 14 * footerScale,
                    ),
            )
            translate(
                padding + 18f * footerScale,
                (footerTop + verticalOffset) * scale + 4 * footerScale,
            ) {
                with(logo) {
                    draw(
                        Size(20 * footerScale, 20 * footerScale),
                        colorFilter = ColorFilter.tint(storyCardColor),
                    )
                }
            }
            label(
                appName,
                layout.padding + 44f * contentScale,
                footerTop + verticalOffset + 13f * contentScale,
                12f * contentScale,
                storySecondaryColor,
                layout.lyricsWidth - 62f * contentScale,
            )
        }
    }
    return bitmap
}
