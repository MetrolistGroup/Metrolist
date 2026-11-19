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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val sample = ImageBitmap(128, 128)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(sample), Size(128f, 128f)) {
        drawRect(Color.Black)
        drawCover(image, Offset.Zero, 128f)
    }
    val pixels = IntArray(128 * 128)
    sample.readPixels(pixels)
    val palette = lyricsArtworkPalette(pixels)
    val blurred = blurLyricsArtwork(pixels, 128, 128, 12)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(sample), Size(128f, 128f)) {
        blurred.forEachIndexed { index, pixel ->
            drawRect(Color(pixel), Offset((index % 128).toFloat(), (index / 128).toFloat()), Size(1f, 1f))
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
    val lyricsInsetX = if (story) 12f * contentScale * scale else 0f
    val lyricsInsetY = if (story) 10f * contentScale * scale else 0f
    val textWidth = width - 2 * lyricsInsetX
    val textHeight = lyricsHeight - 2 * lyricsInsetY
    val textLeft = padding + lyricsInsetX
    val textTop = lyricsTop + lyricsInsetY
    val textStyle =
        TextStyle(
            color = style.textColor,
            fontWeight = FontWeight.Bold,
            textAlign = style.alignment,
            shadow = if (story) Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f * scale), 4f * scale) else null,
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
    val fit = fitLyricsImageText(textHeight, style.textScale) { measureLyrics(it).size.height.toFloat() }
    val lyricsLayout = measureLyrics(fit.fontSize)
    CanvasDrawScope().draw(density, layoutDirection, Canvas(bitmap), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
        val path =
            Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(if (story) 0f else 20 * scale)))
            }
        clipPath(path) {
            drawRect(style.backgroundColor)
            when (style.background) {
                LyricsImageBackground.SOLID -> {}

                LyricsImageBackground.BLUR -> {
                    if (blurredArtwork != null) {
                        if (story) {
                            drawImage(
                                blurredArtwork,
                                srcOffset = IntOffset((blurredArtwork.width - blurredArtwork.height * 9 / 16) / 2, 0),
                                srcSize = IntSize(blurredArtwork.height * 9 / 16, blurredArtwork.height),
                                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                                filterQuality = FilterQuality.Medium,
                            )
                        } else {
                            drawCover(blurredArtwork, Offset.Zero, size.width)
                        }
                        if (story) {
                            drawRect(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Black.copy(alpha = 0.2f),
                                        Color.Black.copy(alpha = 0.48f),
                                        Color.Black.copy(alpha = 0.64f),
                                        Color.Black.copy(alpha = 0.24f),
                                    ),
                                ),
                            )
                        } else {
                            drawRect(Color.Black.copy(alpha = 0.3f))
                        }
                    }
                }

                LyricsImageBackground.GRADIENT -> {
                    val colors = palette.firstOrNull()?.let(::getGradientColors) ?: listOf(style.backgroundColor, style.backgroundColor)
                    drawRect(Brush.linearGradient(colors))
                    if (story) drawRect(Color.Black.copy(alpha = 0.16f))
                }
            }
            artwork?.let {
                val topLeft = Offset(layout.artworkX * scale, layout.artworkTop * scale)
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
                centered: Boolean = false,
            ) {
                val layout =
                    textMeasurer.measure(
                        text,
                        style =
                            TextStyle(
                                color = color,
                                fontSize = fontSize.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        constraints =
                            Constraints(
                                minWidth = if (centered) (maxWidth * scale).toInt() else 0,
                                maxWidth = (maxWidth * scale).toInt(),
                            ),
                        density = density,
                        layoutDirection = layoutDirection,
                    )
                drawText(layout, topLeft = Offset(x * scale, y * scale))
            }
            val headerX = if (story || artwork == null) layout.padding else layout.padding + 80f * contentScale
            val headerWidth = if (story) layout.lyricsWidth else 340f - layout.padding - headerX
            label(title, headerX, layout.titleTop, 20f * contentScale, style.textColor, headerWidth, centered = story)
            label(artist, headerX, layout.artistTop, 16f * contentScale, style.secondaryColor, headerWidth, centered = story)
            if (story) {
                drawRoundRect(
                    Color.Black.copy(alpha = 0.22f),
                    topLeft = Offset(padding, lyricsTop),
                    size = Size(width, lyricsHeight),
                    cornerRadius = CornerRadius(22f * scale),
                )
                drawRoundRect(
                    Color.White.copy(alpha = 0.18f),
                    topLeft = Offset(padding, lyricsTop),
                    size = Size(width, lyricsHeight),
                    cornerRadius = CornerRadius(22f * scale),
                    style = Stroke(width = scale),
                )
            }
            val textX =
                textLeft +
                    when (style.alignment) {
                        TextAlign.Left -> 0f
                        TextAlign.Right -> textWidth * (1f - fit.drawScale)
                        else -> textWidth * (1f - fit.drawScale) / 2f
                    }
            clipRect(textLeft, textTop, textLeft + textWidth, textTop + textHeight) {
                translate(textX, textTop + (textHeight - lyricsLayout.size.height * fit.drawScale) / 2f) {
                    scale(fit.drawScale, pivot = Offset.Zero) { drawText(lyricsLayout) }
                }
            }
            val footerScale = scale * contentScale
            drawCircle(
                style.secondaryColor,
                radius = 14 * footerScale,
                center = Offset(padding + 14 * footerScale, layout.footerTop * scale + 14 * footerScale),
            )
            translate(padding + 2 * footerScale, layout.footerTop * scale + 2 * footerScale) {
                with(logo) { draw(Size(24 * footerScale, 24 * footerScale), colorFilter = ColorFilter.tint(style.backgroundColor)) }
            }
            label(
                appName,
                layout.padding + 36f * contentScale,
                layout.footerTop + 8f * contentScale,
                12f * contentScale,
                style.secondaryColor,
                layout.lyricsWidth - 36f * contentScale,
            )
        }
    }
    return bitmap
}
