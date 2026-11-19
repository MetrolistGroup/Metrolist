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
): String = selected.sorted().mapNotNull { lyrics.getOrNull(it)?.text?.takeIf(String::isNotBlank) }.joinToString("\n")

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
    val template: LyricsCardTemplate = LyricsCardTemplate.CLASSIC,
) {
    val padding = 28f * contentScale
    val artworkSize = 64f * contentScale
    val lyricsTop = if (template == LyricsCardTemplate.EDITORIAL) 82f else 110f * contentScale
    val footerTop = 340f - 52f * contentScale
    val lyricsWidth = 340f - 2f * padding
    val lyricsHeight = footerTop - 12f * contentScale - lyricsTop
}

internal fun lyricsImageLayout(
    contentScale: Float,
    template: LyricsCardTemplate = LyricsCardTemplate.CLASSIC,
): LyricsImageLayout = LyricsImageLayout(contentScale.coerceIn(0.6f, 1.35f), template)

internal fun LyricsImageStyle.withTemplate(template: LyricsCardTemplate): LyricsImageStyle {
    if (this.template == template) return this
    return when (template) {
        LyricsCardTemplate.CLASSIC -> LyricsImageStyle()
        LyricsCardTemplate.EDITORIAL -> LyricsImageStyle(
            template = template,
            backgroundColor = Color(0xFFF3EFE5),
            textColor = Color(0xFF25231F),
            secondaryColor = Color(0xFF686155),
            alignment = TextAlign.Left,
        )
        LyricsCardTemplate.SPOTLIGHT -> LyricsImageStyle(
            template = template,
            backgroundColor = Color(0xFF101217),
            textColor = Color.White,
            secondaryColor = Color(0xFFE3E6EC),
            alignment = TextAlign.Left,
        )
    }
}

internal data class LyricsTextFit(
    val fontSize: Float,
    val drawScale: Float,
)

internal fun fitLyricsImageText(
    height: Float,
    textScale: Float,
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
    val automatic = fit(50f)
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
    val template: String = LyricsCardTemplate.CLASSIC.name,
    val alignment: String? = null,
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
            template.name,
            when (alignment) {
                TextAlign.Left -> "LEFT"
                TextAlign.Right -> "RIGHT"
                else -> "CENTER"
            },
        ),
    )

internal fun decodeLyricsImageStyle(
    stored: String,
    alignment: TextAlign,
): LyricsImageStyle =
    runCatching {
        val style = Json { ignoreUnknownKeys = true }.decodeFromString<StoredLyricsImageStyle>(stored)
        LyricsImageStyle(
            template = runCatching { LyricsCardTemplate.valueOf(style.template) }.getOrDefault(LyricsCardTemplate.CLASSIC),
            background = runCatching { LyricsImageBackground.valueOf(style.background) }.getOrDefault(LyricsImageBackground.SOLID),
            backgroundColor = Color(style.backgroundColor),
            textColor = Color(style.textColor),
            secondaryColor = Color(style.secondaryColor),
            alignment = when (style.alignment) {
                "LEFT" -> TextAlign.Left
                "RIGHT" -> TextAlign.Right
                "CENTER" -> TextAlign.Center
                else -> alignment
            },
            textScale = style.textScale.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f,
            contentScale = style.contentScale.takeIf { it.isFinite() }?.coerceIn(0.6f, 1.35f) ?: 1f,
        )
    }.getOrDefault(LyricsImageStyle(alignment = alignment))
