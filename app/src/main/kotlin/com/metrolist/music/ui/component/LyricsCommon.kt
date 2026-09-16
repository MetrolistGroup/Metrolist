/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.metrolist.music.lyrics.LyricsEntry

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.ui.screens.settings.LyricsPosition
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

sealed class LyricsListItem {
    data class Line(val index: Int, val entry: LyricsEntry) : LyricsListItem()
    data class Indicator(
        val afterLineIndex: Int,
        val gapMs: Long,
        val gapStartMs: Long,
        val gapEndMs: Long,
        val nextAgent: String?
    ) : LyricsListItem()
}

// Padding values shared by LyricsLine layout and the eager height table so the two can
// never drift apart. Must stay identical to the paddings in LyricsLine.
internal val LYRICS_LINE_TOP_PADDING = 12.dp
internal val LYRICS_LINE_BOTTOM_PADDING = 12.dp
internal val LYRICS_BG_TOP_PADDING = 0.dp
internal val LYRICS_BG_BOTTOM_PADDING = 2.dp
internal val LYRICS_SUB_TOP_PADDING = 2.dp
internal val LYRICS_TRANS_TOP_PADDING = 4.dp
internal const val LYRICS_SUB_FONT_SIZE_SP = 18f
internal const val LYRICS_TRANS_FONT_SIZE_SP = 16f
// Main lyric text size/spacing. Passed to LyricsLine and the height table alike.
internal const val LYRICS_TEXT_SIZE_SP = 36f
internal const val LYRICS_LINE_SPACING = 1.3f

/** Which text is drawn big. Mirrors the selection logic in LyricsLine. */
internal fun effectiveLyricMainText(
    entry: LyricsEntry,
    romanized: String?,
    romanizeAsMain: Boolean,
): String? {
    val raw: String? = if (romanizeAsMain && romanized != null) romanized else entry.text
    return if (entry.isBackground) raw?.removePrefix("(")?.removeSuffix(")") else raw
}

/** Which text is drawn small underneath. Mirrors the selection logic in LyricsLine. */
internal fun effectiveLyricSubText(
    entry: LyricsEntry,
    romanized: String?,
    romanizeAsMain: Boolean,
): String? {
    val raw: String? = if (romanizeAsMain && romanized != null) entry.text else romanized
    return if (entry.isBackground) raw?.removePrefix("(")?.removeSuffix(")") else raw
}

/** Main lyric text style. Single source used by both LyricsLine and the height table. */
internal fun lyricMainTextStyle(
    isBackground: Boolean,
    textSizeSp: Float,
    lineSpacing: Float,
    fontFamily: FontFamily?,
    textAlign: TextAlign = TextAlign.Center,
): TextStyle = TextStyle(
    fontSize = if (isBackground) (textSizeSp * 0.7f).sp else textSizeSp.sp,
    fontWeight = FontWeight.Bold,
    fontStyle = if (isBackground) FontStyle.Italic else FontStyle.Normal,
    lineHeight = if (isBackground) (textSizeSp * 0.7f * lineSpacing).sp else (textSizeSp * lineSpacing).sp,
    letterSpacing = (-0.5).sp,
    textAlign = textAlign,
    fontFamily = fontFamily,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both
    )
)

/** Small sub-line style (romanized / translation). Height only depends on size. */
internal fun lyricSubTextStyle(
    fontSizeSp: Float,
    fontFamily: FontFamily?,
): TextStyle = TextStyle(
    fontSize = fontSizeSp.sp,
    fontWeight = FontWeight.Normal,
    fontFamily = fontFamily,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both
    )
)

/**
 * Eagerly measures every item's height with the real text engine (same styles, same width
 * as LyricsLine) so scroll positions are exact from the first frame instead of starting
 * from fallback estimates. Cheap: a few hundred short-text layouts, once per song/layout.
 * Later arrivals (translations, romanizations) still patch heights via onSizeChanged.
 */
internal fun precomputeLyricHeights(
    items: List<LyricsListItem>,
    measurer: TextMeasurer,
    density: Density,
    contentWidthPx: Int,
    mainFontFamily: FontFamily?,
    textSizeSp: Float,
    lineSpacing: Float,
    romanizeAsMain: Boolean,
    showRomanizedSub: Boolean,
    indicatorHeightPx: Float,
    itemGapPx: Float,
    visibleBgLineIndices: Set<Int> = emptySet(),
    visibleIndicatorListIndex: Int? = null,
): Map<Int, Int> {
    if (items.isEmpty() || contentWidthPx <= 0) return emptyMap()
    val constraints = Constraints(maxWidth = contentWidthPx)
    val mainStyle = lyricMainTextStyle(false, textSizeSp, lineSpacing, mainFontFamily)
    val bgStyle = lyricMainTextStyle(true, textSizeSp, lineSpacing, mainFontFamily)
    val romanizedStyle = lyricSubTextStyle(LYRICS_SUB_FONT_SIZE_SP, mainFontFamily)
    val translationStyle = lyricSubTextStyle(LYRICS_TRANS_FONT_SIZE_SP, mainFontFamily)
    val result = HashMap<Int, Int>(items.size)
    with(density) {
        val topPad = LYRICS_LINE_TOP_PADDING.roundToPx()
        val bottomPad = LYRICS_LINE_BOTTOM_PADDING.roundToPx()
        val bgTopPad = LYRICS_BG_TOP_PADDING.roundToPx()
        val bgBottomPad = LYRICS_BG_BOTTOM_PADDING.roundToPx()
        val subTopPad = LYRICS_SUB_TOP_PADDING.roundToPx()
        val transTopPad = LYRICS_TRANS_TOP_PADDING.roundToPx()
        items.forEachIndexed { i, item ->
            when (item) {
                // Seed the collapsed height unless visible right now; the live report
                // grows it when shown. Hidden is the common case and stays exact.
                is LyricsListItem.Indicator ->
                    result[i] = if (i == visibleIndicatorListIndex) indicatorHeightPx.roundToInt() else 0
                is LyricsListItem.Line -> {
                    val entry = item.entry
                    if (entry.isBackground && item.index !in visibleBgLineIndices) {
                        result[i] = bgTopPad + bgBottomPad
                    } else {
                        val romanized = entry.romanizedTextFlow.value
                        val main = effectiveLyricMainText(entry, romanized, romanizeAsMain) ?: ""
                        var h = measurer.measure(text = main, style = if (entry.isBackground) bgStyle else mainStyle, constraints = constraints).size.height
                        h += if (entry.isBackground) bgTopPad else topPad
                        h += if (entry.isBackground) bgBottomPad else bottomPad
                        if (showRomanizedSub) {
                            val sub = effectiveLyricSubText(entry, romanized, romanizeAsMain)
                            if (sub != null) {
                                h += measurer.measure(text = sub, style = romanizedStyle, constraints = constraints).size.height + subTopPad
                            }
                        }
                        entry.translatedTextFlow.value?.let { trans ->
                            h += measurer.measure(text = trans, style = translationStyle, constraints = constraints).size.height + transTopPad
                        }
                        result[i] = h
                    }
                }
            }
        }
    }
    return result
}

/** Horizontal content width after the side paddings LyricsLine applies. */
internal fun lyricContentWidthPx(
    maxWidthPx: Int,
    density: Density,
    lyricsTextPosition: LyricsPosition,
): Int = with(density) {
    val sidePad = if (lyricsTextPosition == LyricsPosition.CENTER) 24.dp else 11.dp
    (maxWidthPx - 2 * sidePad.roundToPx()).coerceAtLeast(0)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun IntervalIndicator(
    gapStartMs: Long,
    gapEndMs: Long,
    currentPositionProvider: () -> Long,
    visible: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val alpha = remember { Animatable(0f) }
    val rowHeightPx = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        if (visible) {
            rowHeightPx.animateTo(1f, tween(200))
            alpha.animateTo(1f, tween(200))
        } else {
            alpha.animateTo(0f, tween(200))
            rowHeightPx.animateTo(0f, tween(200))
        }
    }

    val targetHeightDp = 72.dp

    var currentProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(visible, gapStartMs, gapEndMs) {
        if (visible && gapEndMs > gapStartMs) {
            while (isActive) {
                withFrameMillis {
                    val pos = currentPositionProvider()
                    currentProgress = ((pos - gapStartMs).toFloat() / (gapEndMs - gapStartMs).toFloat()).coerceIn(0f, 1f)
                }
            }
        } else {
            currentProgress = 0f
        }
    }

    Box(
        modifier = modifier
            .height(targetHeightDp * rowHeightPx.value)
            .padding(top = 16.dp * rowHeightPx.value)
            .graphicsLayer {
                this.alpha = alpha.value
                this.clip = true
            },
        contentAlignment = Alignment.Center
    ) {
        CircularWavyProgressIndicator(
            progress = { currentProgress },
            modifier = Modifier
                .size(36.dp)
                .alpha(alpha.value),
            color = color,
            trackColor = color.copy(alpha = 0.2f),
        )
    }
}
