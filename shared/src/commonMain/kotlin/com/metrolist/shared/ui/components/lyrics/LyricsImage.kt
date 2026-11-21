/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components.lyrics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import com.materialkolor.quantize.QuantizerCelebi
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.ui.theme.toHsv
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val LYRICS_SHARE_LINE_LIMIT = 5

internal fun toggleLyricsShareLine(
    selected: Set<Int>,
    index: Int,
): Set<Int> =
    when {
        index in selected -> selected - index
        selected.size < LYRICS_SHARE_LINE_LIMIT -> selected + index
        else -> selected
    }

internal fun selectedLyricsText(
    lyrics: List<LyricsEntry>,
    selected: Set<Int>,
): String =
    selected
        .sorted()
        .mapNotNull { index ->
            lyrics.getOrNull(index)?.let { entry ->
                val mainText = entry.text.takeIf { !entry.isBackground && it.isNotBlank() }
                val backgroundText =
                    listOfNotNull(
                        entry.text
                            .takeIf { entry.isBackground && it.isNotBlank() }
                            ?.trim()
                            ?.removeSurrounding("(", ")"),
                        entry.backgroundText
                            ?.takeIf(String::isNotBlank)
                            ?.trim()
                            ?.removeSurrounding("(", ")"),
                        entry.words?.backgroundText(),
                    ).distinctBy(::normalizeBackgroundText)
                        .joinToString(" ")
                        .takeIf(String::isNotBlank)
                listOfNotNull(mainText, backgroundText?.let { "($it)" }).joinToString(" ").takeIf(String::isNotBlank)
            }
        }.joinToString("\n")

internal fun lyricsArtworkPalette(pixels: IntArray): List<Color> =
    QuantizerCelebi
        .quantize(pixels.filter { it ushr 24 >= 128 }.toIntArray(), 16)
        .entries
        .sortedByDescending { it.value }
        .map { Color(it.key) }
        .filter { it.toHsv()[1] > 0.2f }
        .take(5)

internal fun blurLyricsArtwork(
    pixels: IntArray,
    width: Int,
    height: Int,
    radius: Int,
): IntArray {
    require(width > 0 && height > 0 && pixels.size == width * height && radius >= 0)
    var source = pixels.copyOf()
    for (horizontal in listOf(true, false)) {
        val output = IntArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var red = 0
                var green = 0
                var blue = 0
                for (offset in -radius..radius) {
                    val sx = if (horizontal) (x + offset).coerceIn(0, width - 1) else x
                    val sy = if (horizontal) y else (y + offset).coerceIn(0, height - 1)
                    val color = source[sy * width + sx]
                    red += color ushr 16 and 255
                    green += color ushr 8 and 255
                    blue += color and 255
                }
                val count = radius * 2 + 1
                output[y * width + x] = (255 shl 24) or ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
            }
        }
        source = output
    }
    return source
}

internal data class LyricsImageLayout(
    val contentScale: Float,
    val story: Boolean = false,
) {
    val canvasHeight = if (story) 340f * 16f / 9f else 340f
    val padding = (if (story) 28f else 24f) * contentScale
    val artworkSize = (if (story) 64f else 56f) * contentScale
    val artworkX = padding + (if (story) 18f else 16f) * contentScale
    val cardTop = if (story) 30f else 18f
    val artworkTop =
        if (story) {
            maxOf(48f * contentScale, cardTop + 8f * contentScale)
        } else {
            maxOf(30f * contentScale, cardTop + 8f * contentScale)
        }
    val titleTop = if (story) 58f * contentScale else artworkTop + 6f * contentScale
    val artistTop = if (story) 84f * contentScale else titleTop + 22f * contentScale
    val lyricsTop = if (story) 138f * contentScale else artworkTop + artworkSize + 14f * contentScale
    val footerHeight = 34f * contentScale
    val footerTop =
        if (story) {
            544f
        } else {
            minOf(canvasHeight - 72f * contentScale, canvasHeight - cardTop - footerHeight)
        }
    val lyricsWidth = 340f - 2f * padding
    val lyricsHeight = footerTop - 10f * contentScale - lyricsTop
}

internal fun lyricsImageLayout(
    contentScale: Float,
    story: Boolean = false,
): LyricsImageLayout = LyricsImageLayout(contentScale.coerceIn(0.6f, 1.35f), story)

internal data class LyricsTextFit(
    val fontSize: Float,
    val drawScale: Float,
)

internal fun fitLyricsImageText(
    height: Float,
    textScale: Float,
    maxFontSize: Float = 50f,
    measureHeight: (Float) -> Float,
): LyricsTextFit {
    fun fit(maximum: Float): Float {
        var low = 0.25f
        var high = maximum.coerceAtLeast(low)
        repeat(16) {
            val mid = (low + high) / 2f
            if (measureHeight(mid) <= height) low = mid else high = mid
        }
        return low
    }
    val automatic = fit(maxFontSize)
    val requested = automatic * textScale.coerceIn(0.5f, 2f)
    val fontSize = if (requested <= automatic) requested else fit(requested)
    return LyricsTextFit(fontSize, (height / measureHeight(fontSize).coerceAtLeast(1f)).coerceAtMost(1f))
}

@Serializable
private data class StoredLyricsImageStyle(
    val background: String,
    val backgroundColor: Int,
    val textColor: Int,
    val secondaryColor: Int,
    val textScale: Float,
    val contentScale: Float,
)

internal fun LyricsImageStyle.encodeForStorage(): String =
    Json.encodeToString(
        StoredLyricsImageStyle(
            background.name,
            backgroundColor.toArgb(),
            textColor.toArgb(),
            secondaryColor.toArgb(),
            textScale,
            contentScale,
        ),
    )

internal fun decodeLyricsImageStyle(
    stored: String,
    alignment: TextAlign,
): LyricsImageStyle =
    runCatching {
        val style = Json.decodeFromString<StoredLyricsImageStyle>(stored)
        LyricsImageStyle(
            background = LyricsImageBackground.valueOf(style.background),
            backgroundColor = Color(style.backgroundColor),
            textColor = Color(style.textColor),
            secondaryColor = Color(style.secondaryColor),
            alignment = alignment,
            textScale = style.textScale.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f,
            contentScale = style.contentScale.takeIf { it.isFinite() }?.coerceIn(0.6f, 1.35f) ?: 1f,
        )
    }.getOrDefault(LyricsImageStyle(alignment = alignment))
