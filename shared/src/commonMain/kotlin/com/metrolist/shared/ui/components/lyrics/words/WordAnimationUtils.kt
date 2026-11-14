/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components.lyrics.words

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.metrolist.shared.model.WordTimestamp
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.round

internal data class RomanizedWordCluster(
    val wordIndex: Int,
    val startIndex: Int,
    val endIndex: Int,
    val lineIdx: Int,
    val subLayout: TextLayoutResult,
    val exactAdvance: Float =
        if (subLayout.layoutInput.text.isNotEmpty()) {
            val adv = subLayout.getHorizontalPosition(subLayout.layoutInput.text.length, true)
            if (adv > 0f) adv else subLayout.size.width.toFloat()
        } else {
            0f
        },
    val romanWidth: Float = maxOf(subLayout.size.width.toFloat(), exactAdvance),
    val visibleWidth: Float =
        if (subLayout.layoutInput.text.isNotEmpty()) {
            val trimmedLen =
                subLayout.layoutInput.text.text
                    .trimEnd { it.isWhitespace() || it == '\u00A0' }
                    .length
            val adv = subLayout.getHorizontalPosition(trimmedLen, true)
            if (adv > 0f) adv else subLayout.size.width.toFloat()
        } else {
            0f
        },
)

@Composable
internal fun rememberFormattedMainText(
    mainText: String,
    words: List<WordTimestamp>,
    romanizedWords: List<WordTimestamp>?,
    romanizedText: String?,
    showSyllableRomanization: Boolean,
    lyricStyle: TextStyle,
    textMeasurer: TextMeasurer,
    widthPx: Int,
): String {
    val density = LocalDensity.current
    val fontSizePx = remember(lyricStyle) { with(density) { lyricStyle.fontSize.toPx() } }

    return remember(
        mainText,
        words,
        romanizedWords,
        romanizedText,
        showSyllableRomanization,
        lyricStyle,
        textMeasurer,
        widthPx,
        fontSizePx,
    ) {
        if (!showSyllableRomanization || widthPx <= 0 || mainText.isBlank()) {
            return@remember mainText
        }

        buildFormattedMainText(
            mainText = mainText,
            words = words,
            romanizedWords = romanizedWords,
            romanizedText = romanizedText,
            lyricStyle = lyricStyle,
            textMeasurer = textMeasurer,
            widthPx = widthPx,
            fontSizePx = fontSizePx,
        )
    }
}

fun buildFormattedMainText(
    mainText: String,
    words: List<WordTimestamp>,
    romanizedWords: List<WordTimestamp>?,
    romanizedText: String?,
    lyricStyle: TextStyle,
    textMeasurer: TextMeasurer,
    widthPx: Int,
    fontSizePx: Float,
): String {
    if (mainText.isEmpty() || widthPx <= 0) return mainText

    val alignedSyllables = alignSyllablesForFormatting(words, mainText)
    if (alignedSyllables.isEmpty()) return mainText

    val romStyle =
        lyricStyle.copy(
            fontSize = (lyricStyle.fontSize.value * 0.7f).sp,
            fontWeight = lyricStyle.fontWeight ?: FontWeight.SemiBold,
            letterSpacing = 0.sp,
            textAlign = TextAlign.Start,
        )

    val unconstrainedResult =
        textMeasurer.measure(
            text = AnnotatedString(mainText),
            style = lyricStyle,
            constraints = Constraints(),
        )

    val romanClusters =
        buildRomanizedWordClusters(
            alignedSyllables = alignedSyllables,
            romanizedWords = romanizedWords,
            romanizedText = romanizedText,
            mainText = mainText,
            textLayoutResult = unconstrainedResult,
            textMeasurer = textMeasurer,
            romStyle = romStyle,
        )

    if (romanClusters.isEmpty()) return mainText

    val clusterMap = romanClusters.associateBy { it.wordIndex }
    val isRtl = isRtlText(mainText)

    val sylMainWidths = FloatArray(alignedSyllables.size)
    val sylRomWidths = FloatArray(alignedSyllables.size)
    val sylSlotWidths = FloatArray(alignedSyllables.size)
    for ((sylIdx, syl) in alignedSyllables.withIndex()) {
        val cluster = clusterMap[sylIdx]
        val startX = unconstrainedResult.getHorizontalPosition(syl.startOffset.coerceIn(0, mainText.length), true)
        val endX = unconstrainedResult.getHorizontalPosition(syl.endOffset.coerceIn(0, mainText.length), true)
        val mainWidth = maxOf(1f, abs(endX - startX))
        val romWidth = cluster?.romanWidth ?: 0f
        sylMainWidths[sylIdx] = mainWidth
        sylRomWidths[sylIdx] = romWidth
        sylSlotWidths[sylIdx] = maxOf(mainWidth, romWidth)
    }

    val breakIndices = mutableListOf<Int>()
    val spaceReplaced = mutableSetOf<Int>()

    var currentLineSlotSum = 0f
    var currentLineMainSum = 0f
    var currentLineRomSum = 0f
    var syllablesOnCurrentLine = 0
    var currentLineStartOffset = 0

    for ((sylIdx, syl) in alignedSyllables.withIndex()) {
        val mainW = sylMainWidths[sylIdx]
        val romW = sylRomWidths[sylIdx]
        val slotW = sylSlotWidths[sylIdx]
        val prevSyl = alignedSyllables.getOrNull(sylIdx - 1)

        if (prevSyl != null) {
            val gapText =
                mainText.substring(
                    prevSyl.endOffset.coerceIn(0, mainText.length),
                    syl.startOffset.coerceIn(0, mainText.length),
                )
            if (gapText.contains('\n')) {
                currentLineSlotSum = 0f
                currentLineMainSum = 0f
                currentLineRomSum = 0f
                syllablesOnCurrentLine = 0
                currentLineStartOffset = syl.startOffset.coerceIn(0, mainText.length)
            } else {
                val gapStart =
                    unconstrainedResult.getHorizontalPosition(prevSyl.endOffset.coerceIn(0, mainText.length), true)
                val gapEnd =
                    unconstrainedResult.getHorizontalPosition(syl.startOffset.coerceIn(0, mainText.length), true)
                val gapW = abs(gapEnd - gapStart)
                currentLineSlotSum += gapW
                currentLineMainSum += gapW
            }
        }

        val breakThreshold = widthPx - fontSizePx * 0.1f
        val projectedWidth =
            if (isRtl) {
                maxOf(currentLineMainSum + mainW, currentLineRomSum + romW)
            } else {
                currentLineSlotSum + slotW
            }
        if (syllablesOnCurrentLine > 0 && projectedWidth > breakThreshold) {
            val rawBreakAt = syl.startOffset.coerceIn(0, mainText.length)
            val breakAt = findSafeBreakOffset(mainText, rawBreakAt, currentLineStartOffset)
            if (breakAt != null && breakAt > 0) {
                breakIndices.add(breakAt)
                if (mainText.getOrNull(breakAt - 1) == ' ') {
                    spaceReplaced.add(breakAt - 1)
                }
                currentLineStartOffset = breakAt
                currentLineSlotSum = slotW
                currentLineMainSum = mainW
                currentLineRomSum = romW
                syllablesOnCurrentLine = 1
            } else {
                currentLineSlotSum += slotW
                currentLineMainSum += mainW
                currentLineRomSum += romW
                syllablesOnCurrentLine++
            }
        } else {
            currentLineSlotSum += slotW
            currentLineMainSum += mainW
            currentLineRomSum += romW
            syllablesOnCurrentLine++
        }
    }

    if (breakIndices.isEmpty()) return mainText

    val sb = StringBuilder()
    var prev = 0
    for (bIdx in breakIndices) {
        val safeBIdx = bIdx.coerceIn(0, mainText.length)
        val safePrev = prev.coerceIn(0, mainText.length)
        if (safeBIdx > safePrev) {
            val spaceIdx = safeBIdx - 1
            if (spaceReplaced.contains(spaceIdx) && spaceIdx in 0 until mainText.length && mainText[spaceIdx] == ' ') {
                sb.append(mainText.substring(safePrev, spaceIdx))
                sb.append('\n')
                prev = safeBIdx
            } else {
                sb.append(mainText.substring(safePrev, safeBIdx))
                sb.append('\n')
                prev = safeBIdx
            }
        }
    }
    val finalPrev = prev.coerceIn(0, mainText.length)
    if (finalPrev < mainText.length) {
        sb.append(mainText.substring(finalPrev))
    }

    return sb.toString()
}

fun measureSyllableRomanizationWidth(
    mainText: String,
    words: List<WordTimestamp>,
    romanizedWords: List<WordTimestamp>?,
    romanizedText: String?,
    lyricStyle: TextStyle,
    textMeasurer: TextMeasurer,
): Float {
    if (mainText.isEmpty()) return 0f
    val alignedSyllables = alignSyllablesForFormatting(words, mainText)
    if (alignedSyllables.isEmpty()) return 0f

    val romStyle =
        lyricStyle.copy(
            fontSize = (lyricStyle.fontSize.value * 0.7f).sp,
            fontWeight = lyricStyle.fontWeight ?: FontWeight.SemiBold,
            letterSpacing = 0.sp,
            textAlign = TextAlign.Start,
        )

    val unconstrainedResult =
        textMeasurer.measure(
            text = AnnotatedString(mainText),
            style = lyricStyle,
            constraints = Constraints(),
        )

    val romanClusters =
        buildRomanizedWordClusters(
            alignedSyllables = alignedSyllables,
            romanizedWords = romanizedWords,
            romanizedText = romanizedText,
            mainText = mainText,
            textLayoutResult = unconstrainedResult,
            textMeasurer = textMeasurer,
            romStyle = romStyle,
        )

    if (romanClusters.isEmpty()) return unconstrainedResult.size.width.toFloat()

    val clusterMap = romanClusters.associateBy { it.wordIndex }
    var totalWidth = 0f
    for ((sylIdx, syl) in alignedSyllables.withIndex()) {
        val cluster = clusterMap[sylIdx]
        val prevSyl = alignedSyllables.getOrNull(sylIdx - 1)
        if (prevSyl != null) {
            val gapStart = unconstrainedResult.getHorizontalPosition(prevSyl.endOffset.coerceIn(0, mainText.length), true)
            val gapEnd = unconstrainedResult.getHorizontalPosition(syl.startOffset.coerceIn(0, mainText.length), true)
            totalWidth += abs(gapEnd - gapStart)
        }
        val startX = unconstrainedResult.getHorizontalPosition(syl.startOffset.coerceIn(0, mainText.length), true)
        val endX = unconstrainedResult.getHorizontalPosition(syl.endOffset.coerceIn(0, mainText.length), true)
        val mainWidth = maxOf(1f, abs(endX - startX))
        val romWidth = cluster?.romanWidth ?: 0f
        totalWidth += maxOf(mainWidth, romWidth)
    }
    return totalWidth
}

internal fun alignSyllablesForFormatting(
    words: List<WordTimestamp>,
    mainText: String,
): List<AlignedSyllable> {
    val strippedToOriginal = ArrayList<Int>(mainText.length + 1)
    val strippedBuilder = StringBuilder(mainText.length)
    for (i in mainText.indices) {
        val c = mainText[i]
        if (!c.isBidiOrInvisible()) {
            strippedBuilder.append(c)
            strippedToOriginal.add(i)
        }
    }
    strippedToOriginal.add(mainText.length)
    val strippedMain = strippedBuilder.toString()

    var searchIdx = 0
    val totalSylLen = words.sumOf { it.text.stripBidiControls().length }.coerceAtLeast(1)
    return words.mapIndexed { _, syl ->
        val sylText = syl.text.stripBidiControls()
        val found = if (sylText.isEmpty()) -1 else strippedMain.indexOf(sylText, searchIdx, ignoreCase = true)
        val si: Int
        val ei: Int
        if (found != -1) {
            si = strippedToOriginal[found]
            ei = strippedToOriginal[(found + sylText.length).coerceAtMost(strippedToOriginal.size - 1)]
            searchIdx = found + sylText.length
        } else {
            val fallbackStripped = searchIdx.coerceAtMost(strippedMain.length)
            val propLen = maxOf(1, ((sylText.length.toDouble() / totalSylLen) * strippedMain.length).toInt())
            val endStripped = (fallbackStripped + propLen).coerceAtMost(strippedMain.length)
            si = strippedToOriginal[fallbackStripped.coerceAtMost(strippedToOriginal.size - 1)]
            ei = strippedToOriginal[endStripped.coerceAtMost(strippedToOriginal.size - 1)]
            searchIdx = endStripped
        }
        AlignedSyllable(syl, si, ei)
    }
}

private fun isCJKChar(c: Char): Boolean {
    val cp = c.code
    return cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x3040..0x309F || cp in 0x30A0..0x30FF || cp in 0xAC00..0xD7AF
}

private fun isProhibitedLineStart(c: Char): Boolean =
    when (c) {
        '、', '。', '！', '？', ')', ']', '}', '”', '’', '…', '—',
        ' ', ' ', '』', '」', '≫', ' ', ' ', '＞', '›', '〕', '〛',
        ',', '.', '!', '?', ':', ';',
        -> true

        else -> false
    }

private fun findSafeBreakOffset(
    mainText: String,
    rawBreakAt: Int,
    lineStartOffset: Int,
): Int? {
    if (rawBreakAt <= lineStartOffset || rawBreakAt > mainText.length) return null

    val before = mainText.getOrNull(rawBreakAt - 1)
    val at = mainText.getOrNull(rawBreakAt)

    val atCjkBoundary =
        (before == null || before == ' ' || isCJKChar(before)) &&
            (at == null || at == ' ' || isCJKChar(at) || before?.let { isCJKChar(it) } == true)

    var candidate = rawBreakAt
    if (!atCjkBoundary) {
        var i = rawBreakAt
        while (i > lineStartOffset) {
            val c = mainText[i - 1]
            val next = mainText.getOrNull(i)
            if (c == ' ' || isCJKChar(c) || (next != null && isCJKChar(next))) {
                candidate = i
                break
            }
            i--
        }
        if (i <= lineStartOffset) return null
    }

    while (candidate > lineStartOffset && mainText.getOrNull(candidate)?.let { isProhibitedLineStart(it) } == true) {
        candidate--
        while (candidate > lineStartOffset &&
            !(mainText[candidate - 1] == ' ' || isCJKChar(mainText[candidate - 1]))
        ) {
            candidate--
        }
    }

    return if (candidate > lineStartOffset) candidate else null
}

@Composable
internal fun rememberRomanClusters(
    showSyllableRomanization: Boolean,
    alignedSyllables: List<AlignedSyllable>,
    romanizedWords: List<WordTimestamp>?,
    romanizedText: String?,
    mainText: String,
    textLayoutResult: TextLayoutResult,
    textMeasurer: TextMeasurer,
    lyricStyle: TextStyle,
    expressiveAccent: Color = Color.Unspecified,
): List<RomanizedWordCluster> {
    val romStyle =
        remember(lyricStyle, expressiveAccent) {
            lyricStyle.copy(
                fontSize = (lyricStyle.fontSize.value * 0.7f).sp,
                fontWeight = lyricStyle.fontWeight ?: FontWeight.SemiBold,
                letterSpacing = 0.sp,
                textAlign = TextAlign.Start,
                color = if (expressiveAccent != Color.Unspecified) expressiveAccent else lyricStyle.color,
            )
        }
    return remember(
        alignedSyllables,
        romanizedWords,
        romanizedText,
        mainText,
        textLayoutResult,
        textMeasurer,
        romStyle,
        showSyllableRomanization,
    ) {
        if (showSyllableRomanization) {
            buildRomanizedWordClusters(
                alignedSyllables = alignedSyllables,
                romanizedWords = romanizedWords,
                romanizedText = romanizedText,
                mainText = mainText,
                textLayoutResult = textLayoutResult,
                textMeasurer = textMeasurer,
                romStyle = romStyle,
            )
        } else {
            emptyList()
        }
    }
}

internal class WordLineMetrics(
    textLayoutResult: TextLayoutResult,
) {
    val lineCount: Int = textLayoutResult.lineCount
    val lineLefts: FloatArray = FloatArray(lineCount) { i -> textLayoutResult.getLineLeft(i) }
    val lineRights: FloatArray = FloatArray(lineCount) { i -> textLayoutResult.getLineRight(i) }
    val lineWidths: FloatArray = FloatArray(lineCount) { i -> maxOf(1f, lineRights[i] - lineLefts[i]) }
    val lineStarts: FloatArray =
        FloatArray(lineCount).also { starts ->
            var cum = 0f
            for (i in 0 until lineCount) {
                starts[i] = cum
                cum += lineWidths[i]
            }
        }
    val totalWidth: Float = lineWidths.sum()
    val lineIsRtl: BooleanArray =
        BooleanArray(lineCount) { i ->
            textLayoutResult.getParagraphDirection(i) == ResolvedTextDirection.Rtl
        }
    val lineBaselines: FloatArray = FloatArray(lineCount) { i -> textLayoutResult.getLineBaseline(i) }
    val lineBottoms: FloatArray = FloatArray(lineCount) { i -> textLayoutResult.getLineBottom(i) }
}

internal class CharPositionMaps(
    val charXMap: FloatArray,
    val romanStartShift: FloatArray,
)

@Composable
internal fun rememberCharXMap(
    mainText: String,
    textLayoutResult: TextLayoutResult,
    lineCount: Int,
    lineIsRtl: BooleanArray,
    lineStarts: FloatArray,
    alignedSyllables: List<AlignedSyllable>,
    romanClusters: List<RomanizedWordCluster>,
    alignment: TextAlign,
): CharPositionMaps =
    remember(
        mainText,
        textLayoutResult,
        lineCount,
        lineIsRtl.contentHashCode(),
        lineStarts.contentHashCode(),
        alignedSyllables,
        romanClusters,
        alignment,
    ) {
        val charXMap =
            buildCharXMap(
                mainText = mainText,
                textLayoutResult = textLayoutResult,
                lineIsRtl = lineIsRtl,
                lineStarts = lineStarts,
            )
        val romanStartShift =
            buildRomanStartShift(
                mainText = mainText,
                charXMap = charXMap,
                textLayoutResult = textLayoutResult,
                lineCount = lineCount,
                alignedSyllables = alignedSyllables,
                romanClusters = romanClusters,
                alignment = alignment,
            )
        CharPositionMaps(charXMap, romanStartShift)
    }

internal fun buildCharXMap(
    mainText: String,
    textLayoutResult: TextLayoutResult,
    lineIsRtl: BooleanArray,
    lineStarts: FloatArray,
): FloatArray =
    FloatArray(mainText.length + 1) { i ->
        val maxIdx = maxOf(0, mainText.length - 1)
        if (mainText.isNotEmpty() && (i == mainText.length || mainText[i] == '\n')) {
            val prevIdx = maxOf(0, i - 1).coerceAtMost(maxIdx)
            val li = textLayoutResult.getLineForOffset(prevIdx)
            val lineW = maxOf(1f, textLayoutResult.getLineRight(li) - textLayoutResult.getLineLeft(li))
            lineStarts[li] + lineW
        } else {
            val idx = minOf(i, maxIdx)
            val li = textLayoutResult.getLineForOffset(idx)
            val h = textLayoutResult.getHorizontalPosition(minOf(i, mainText.length), true)
            val local = if (lineIsRtl[li]) textLayoutResult.getLineRight(li) - h else h - textLayoutResult.getLineLeft(li)
            lineStarts[li] + local
        }
    }

internal fun buildRomanStartShift(
    mainText: String,
    charXMap: FloatArray,
    textLayoutResult: TextLayoutResult,
    lineCount: Int,
    alignedSyllables: List<AlignedSyllable>,
    romanClusters: List<RomanizedWordCluster>,
    alignment: TextAlign,
): FloatArray {
    val shiftByWordIndex = FloatArray(romanClusters.size + alignedSyllables.size)
    if (romanClusters.isEmpty() || isRtlText(mainText)) return shiftByWordIndex

    val lineTotalExtra = FloatArray(lineCount)
    val clusterMap = romanClusters.associateBy { it.wordIndex }
    var runningShift = 0f
    var currentLine = -1

    for ((sylIdx, syl) in alignedSyllables.withIndex()) {
        val li = textLayoutResult.getLineForOffset(syl.startOffset.coerceIn(0, maxOf(0, mainText.length - 1)))
        if (li != currentLine) {
            currentLine = li
            runningShift = 0f
        }

        if (sylIdx < shiftByWordIndex.size) {
            shiftByWordIndex[sylIdx] = runningShift
        }

        val cluster = clusterMap[sylIdx] ?: continue
        val sylEndLine = textLayoutResult.getLineForOffset(minOf(syl.endOffset, maxOf(0, mainText.length - 1)))
        val lineEndOff = textLayoutResult.getLineEnd(li, visibleEnd = true)

        val startX = charXMap[syl.startOffset.coerceIn(0, charXMap.size - 1)]
        val endX =
            if (li == sylEndLine) {
                charXMap[syl.endOffset.coerceIn(0, charXMap.size - 1)]
            } else {
                charXMap[lineEndOff.coerceIn(0, charXMap.size - 1)]
            }
        val mainWidth = maxOf(1f, endX - startX)
        val romWidth = cluster.romanWidth

        if (romWidth > mainWidth) {
            val extra = romWidth - mainWidth
            runningShift += extra
            lineTotalExtra[li] += extra
        }
    }

    if (alignment == TextAlign.Center || alignment == TextAlign.End || alignment == TextAlign.Right) {
        for (idx in shiftByWordIndex.indices) {
            val syl = alignedSyllables.getOrNull(idx) ?: continue
            val li = textLayoutResult.getLineForOffset(syl.startOffset.coerceIn(0, maxOf(0, mainText.length - 1)))
            val totalExtra = lineTotalExtra.getOrElse(li) { 0f }
            val correction = if (alignment == TextAlign.Center) totalExtra / 2f else totalExtra
            shiftByWordIndex[idx] -= correction
        }
    }

    return shiftByWordIndex
}

internal fun calculateGapShift(
    syllableIndex: Int,
    romanStartShift: FloatArray,
): Float {
    if (syllableIndex < 0 || syllableIndex >= romanStartShift.size) return 0f
    return romanStartShift[syllableIndex]
}

internal fun buildRomanizedWordClusters(
    alignedSyllables: List<AlignedSyllable>,
    romanizedWords: List<WordTimestamp>?,
    romanizedText: String?,
    mainText: String,
    textLayoutResult: TextLayoutResult,
    textMeasurer: TextMeasurer,
    romStyle: TextStyle,
): List<RomanizedWordCluster> {
    if (alignedSyllables.isEmpty() || romanizedWords.isNullOrEmpty()) return emptyList()

    if (romanizedText != null && isSameLyricsText(romanizedText, mainText)) return emptyList()

    val rWords = romanizedWords.filter { !it.isBackground }
    if (rWords.any { isRtlText(it.text) || isJoiningScript(it.text) }) return emptyList()

    val sylRomanTexts: List<String> =
        alignedSyllables.mapIndexed { i, syl ->
            val rw = rWords.getOrNull(i)
            if (rw != null) romanizedSyllableText(rw, syl.syllable.hasTrailingSpace) else ""
        }

    val assembledRoman = sylRomanTexts.joinToString("")
    if (assembledRoman.isBlank() || isSameLyricsText(assembledRoman, mainText)) {
        return emptyList()
    }

    val result = mutableListOf<RomanizedWordCluster>()
    alignedSyllables.forEachIndexed { idx, syl ->
        val text = sylRomanTexts.getOrNull(idx) ?: return@forEachIndexed
        if (text.isNotBlank()) {
            val li = textLayoutResult.getLineForOffset(syl.startOffset.coerceIn(0, maxOf(0, mainText.length - 1)))
            val measuredText =
                if (text.endsWith(' ')) {
                    text.dropLast(1) + '\u00A0'
                } else {
                    text
                }
            val subLayout =
                textMeasurer.measure(
                    text = AnnotatedString(measuredText),
                    style = romStyle,
                    constraints = Constraints(),
                    skipCache = false,
                )
            result.add(
                RomanizedWordCluster(
                    wordIndex = idx,
                    startIndex = syl.startOffset,
                    endIndex = syl.endOffset,
                    lineIdx = li,
                    subLayout = subLayout,
                ),
            )
        }
    }
    return result
}

internal fun romanizedSyllableText(
    word: WordTimestamp,
    hasMainTrailingSpace: Boolean = false,
): String {
    val raw = word.text.stripBidiControls().replace('\u00A0', ' ')
    val trimmedEnd = raw.trimEnd(' ')
    val hadTrailingSpace = raw.length > trimmedEnd.length
    return trimmedEnd + if (word.hasTrailingSpace || hasMainTrailingSpace || hadTrailingSpace) " " else ""
}

internal fun String.normalizeForComparison(): String {
    val sb = StringBuilder(length)
    var lastWasSpace = true
    for (i in indices) {
        val c = this[i]
        if (c.isBidiOrInvisible()) continue
        if (c.isWhitespace() || c == '\u00A0') {
            if (!lastWasSpace) {
                sb.append(' ')
                lastWasSpace = true
            }
        } else {
            sb.append(c.lowercaseChar())
            lastWasSpace = false
        }
    }
    if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
        sb.setLength(sb.length - 1)
    }
    return sb.toString()
}

internal fun isSameLyricsText(
    a: String?,
    b: String?,
): Boolean {
    if (a == null || b == null) return false
    return a.normalizeForComparison() == b.normalizeForComparison()
}

internal fun groupRomanClustersByLine(
    romanClusters: List<RomanizedWordCluster>,
    lineCount: Int,
): Array<List<RomanizedWordCluster>> {
    if (romanClusters.isEmpty()) return emptyArray()
    val sorted = romanClusters.sortedBy { it.wordIndex }
    return Array(lineCount) { li -> sorted.filter { it.lineIdx == li } }
}

internal class RomanLineStatics(
    val clusters: List<RomanizedWordCluster>,
    val drawX: FloatArray,
    val mainStart: FloatArray,
    val mainEnd: FloatArray,
    val localStart: FloatArray,
    val localEnd: FloatArray,
    val romWidth: FloatArray,
    val firstMainStart: Float,
    val lastMainEnd: Float,
    val lastRomEnd: Float,
    val y: Float,
    val hasWordIndex: BooleanArray,
)

internal class RomanStatics(
    val lines: Array<RomanLineStatics?>,
    val extraPaddingPx: Float,
)

internal fun buildRomanStatics(
    romanLines: Array<List<RomanizedWordCluster>>,
    charXMap: FloatArray,
    mainText: String,
    romanStartShift: FloatArray,
    lineBaselines: FloatArray,
    lineBottoms: FloatArray,
    lineLefts: FloatArray,
    lineRights: FloatArray,
    lineStarts: FloatArray,
    lineIsRtl: BooleanArray,
    alignment: TextAlign,
    canvasWidthPx: Float,
    lineCount: Int,
    fontSizePx: Float,
    lineExtraHeight: Float,
    totalWidth: Float,
    extraPaddingPx: Float,
): RomanStatics {
    fun getX(offset: Int): Float {
        if (offset < 0) return 0f
        if (offset >= charXMap.size) return totalWidth
        return charXMap[offset]
    }

    fun mainX(index: Int): Float = charXMap.getOrElse(index.coerceIn(0, maxOf(0, charXMap.size - 1))) { 0f }

    fun getShift(wordIndex: Int): Float = romanStartShift.getOrElse(wordIndex) { 0f }

    val count = minOf(lineCount, romanLines.size)
    val lines =
        Array<RomanLineStatics?>(count) { li ->
            val cs = romanLines[li]
            if (cs.isEmpty()) return@Array null
            val lineLeft = lineLefts.getOrElse(li) { 0f }
            val lineRight = lineRights.getOrElse(li) { canvasWidthPx }
            val lineStart = lineStarts.getOrElse(li) { 0f }
            val isRtlLine = lineIsRtl.getOrElse(li) { false }
            val n = cs.size
            val drawX = FloatArray(n)
            val mainStart = FloatArray(n)
            val mainEnd = FloatArray(n)
            val romWidth = FloatArray(n) { cs[it].visibleWidth }
            var maxWordIdx = 0
            if (isRtlLine) {
                var totalRomW = 0f
                for (i in 0 until n) {
                    val c = cs[i]
                    val w = if (i == n - 1 && c.visibleWidth > 0f) c.visibleWidth else c.exactAdvance
                    totalRomW += w
                }
                val rightGap = canvasWidthPx - lineRight
                val isVisuallyRightAligned =
                    when (alignment) {
                        TextAlign.Right -> true
                        TextAlign.Left -> false
                        TextAlign.Start -> true
                        TextAlign.End -> false
                        else -> lineLeft > rightGap + 1f
                    }
                val rawStartX =
                    when {
                        alignment == TextAlign.Center -> (lineLeft + lineRight - totalRomW) / 2f
                        isVisuallyRightAligned -> lineRight - totalRomW
                        else -> lineLeft
                    }
                var curX =
                    if (canvasWidthPx > totalRomW) {
                        rawStartX.coerceIn(0f, canvasWidthPx - totalRomW)
                    } else {
                        rawStartX.coerceAtLeast(0f)
                    }
                for (i in 0 until n) {
                    val c = cs[i]
                    drawX[i] = curX
                    curX += c.exactAdvance
                }
            } else {
                for (i in 0 until n) {
                    val c = cs[i]
                    drawX[i] = getX(c.startIndex) - lineStart + lineLeft + getShift(c.wordIndex)
                }
            }
            val localStart = FloatArray(n)
            val localEnd = FloatArray(n)
            var cumRom = 0f
            for (i in 0 until n) {
                val c = cs[i]
                var start = c.startIndex.coerceIn(0, mainText.length)
                var end = c.endIndex.coerceIn(start, mainText.length)
                while (start < end && mainText[start].isWhitespace()) start++
                while (end > start && mainText[end - 1].isWhitespace()) end--
                val s0 = mainX(start)
                val s1 = mainX(end)
                mainStart[i] = minOf(s0, s1)
                mainEnd[i] = maxOf(s0, s1)
                if (c.wordIndex > maxWordIdx) maxWordIdx = c.wordIndex

                localStart[i] = cumRom
                localEnd[i] = cumRom + romWidth[i]
                cumRom += maxOf(romWidth[i], c.exactAdvance)
            }
            val hasWordIndex = BooleanArray(maxWordIdx + 1)
            for (c in cs) if (c.wordIndex >= 0) hasWordIndex[c.wordIndex] = true
            val baseLineBottom = lineBottoms.getOrNull(li) ?: (lineBaselines[li] + fontSizePx * 0.25f)
            RomanLineStatics(
                clusters = cs,
                drawX = drawX,
                mainStart = mainStart,
                mainEnd = mainEnd,
                localStart = localStart,
                localEnd = localEnd,
                romWidth = romWidth,
                firstMainStart = mainStart[0],
                lastMainEnd = mainEnd[n - 1],
                lastRomEnd = localEnd[n - 1],
                y = baseLineBottom + li * lineExtraHeight + fontSizePx * 0.08f,
                hasWordIndex = hasWordIndex,
            )
        }
    return RomanStatics(lines, extraPaddingPx)
}

internal fun RomanLineStatics.romanizedWipePosition(mainX: Float): Float {
    if (mainX <= firstMainStart) return mainX - firstMainStart
    if (mainX >= lastMainEnd) return lastRomEnd + (mainX - lastMainEnd)
    for (i in mainStart.indices) {
        val start = mainStart[i]
        val end = mainEnd[i]
        if (mainX in start..end) {
            val progress = (mainX - start) / maxOf(1f, end - start)
            return localStart[i] + progress * romWidth[i]
        }
        if (i < mainStart.lastIndex) {
            val nextStart = mainStart[i + 1]
            if (mainX in end..nextStart) {
                val progress = (mainX - end) / maxOf(1f, nextStart - end)
                return localEnd[i] + progress * (localStart[i + 1] - localEnd[i])
            }
        }
    }
    return mainX - firstMainStart
}

internal fun DrawScope.drawRomanizedWordClusters(
    romanStatics: RomanStatics,
    isActiveLine: Boolean,
    activeSylIdx: Int,
    activeLineIdx: Int,
    curGX: Float,
    isActiveSylLastOfLine: Boolean,
    activeSylProgress: Float,
    gradientWidth: Float,
    pastBrush: Brush,
    unlitBrush: Brush,
    litColor: Color,
    unlitColor: Color,
    lineColor: Color = Color.Unspecified,
    curSimpleState: Float = 0f,
    expressiveAccent: Color = litColor,
) {
    val lines = romanStatics.lines
    if (lines.isEmpty()) return

    val pad = romanStatics.extraPaddingPx
    val halfGradientWidth = gradientWidth / 2f
    val clearShadow = LyricsClearShadow
    val inactiveBrush =
        if (!isActiveLine) SolidColor(lerp(lineColor, expressiveAccent, curSimpleState.coerceIn(0f, 1f))) else null

    for (li in lines.indices) {
        val line = lines[li] ?: continue
        val cs = line.clusters
        val n = cs.size
        val topY = line.y + pad

        if (inactiveBrush != null) {
            for (i in 0 until n) {
                drawText(
                    textLayoutResult = cs[i].subLayout,
                    topLeft = Offset(line.drawX[i] + pad, topY),
                    brush = inactiveBrush,
                    shadow = clearShadow,
                )
            }
            continue
        }

        val isLineInPast = activeLineIdx != -1 && li < activeLineIdx
        val isLineInFuture = activeLineIdx != -1 && li > activeLineIdx

        val curRomGX: Float =
            when {
                isLineInPast -> {
                    Float.MAX_VALUE
                }

                isLineInFuture || activeSylIdx == -1 -> {
                    -gradientWidth * 2f
                }

                else -> {
                    line.romanizedWipePosition(curGX)
                }
            }

        val activeWordInLine =
            activeSylIdx >= 0 && activeSylIdx < line.hasWordIndex.size && line.hasWordIndex[activeSylIdx]
        val brushShift =
            if ((li == activeLineIdx || activeWordInLine) && isActiveSylLastOfLine) {
                activeSylProgress * halfGradientWidth
            } else {
                0f
            }
        val localX = curRomGX + brushShift

        for (i in 0 until n) {
            val topLeft = Offset(line.drawX[i] + pad, topY)
            val localStart = line.localStart[i]
            val localEnd = line.localEnd[i]

            if (localX >= localEnd + halfGradientWidth) {
                drawText(textLayoutResult = cs[i].subLayout, topLeft = topLeft, brush = pastBrush, shadow = clearShadow)
            } else if (localX <= localStart - halfGradientWidth) {
                drawText(
                    textLayoutResult = cs[i].subLayout,
                    topLeft = topLeft,
                    brush = unlitBrush,
                    shadow = clearShadow,
                )
            } else {
                val relWiperX = localX - localStart
                val bandStartRel: Float
                val bandEndRel: Float
                if (localX < halfGradientWidth) {
                    val growProgress = (localX / halfGradientWidth).coerceIn(0f, 1f)
                    val growWidth = maxOf(0.1f, gradientWidth * growProgress)
                    bandStartRel = -localStart
                    bandEndRel = growWidth - localStart
                } else {
                    bandStartRel = relWiperX - halfGradientWidth
                    bandEndRel = relWiperX + halfGradientWidth
                }
                val brush =
                    Brush.linearGradient(
                        0.0f to litColor,
                        0.1f to litColor,
                        0.9f to unlitColor,
                        1.0f to unlitColor,
                        start = Offset(bandStartRel, 0f),
                        end = Offset(bandEndRel, 0f),
                    )
                drawText(textLayoutResult = cs[i].subLayout, topLeft = topLeft, brush = brush, shadow = clearShadow)
            }
        }
    }
}

internal object FixedMotionDurationScale : MotionDurationScale {
    override val scaleFactor: Float get() = 1f
}

internal val LyricsClearShadow = Shadow(color = Color.Transparent, offset = Offset.Zero, blurRadius = 0f)

private val joiningLayerPaint by lazy { Paint() }

private const val OPAQUE_EPS = 0.999f

private fun joiningLayerBounds(
    vc: VisualCluster,
    topLeft: Offset,
): Rect {
    val pad = maxOf(8f, vc.subLayout.size.height * 0.5f)
    return Rect(
        left = topLeft.x - pad,
        top = topLeft.y - pad,
        right = topLeft.x + vc.subLayout.size.width + pad,
        bottom = topLeft.y + vc.subLayout.size.height + pad,
    )
}

private fun needsOverlapSafeDraw(
    vc: VisualCluster,
    color: Color?,
): Boolean = vc.isJoiningScript && color != null && color.alpha > 0f && color.alpha < OPAQUE_EPS

internal fun DrawScope.drawClusterText(
    vc: VisualCluster,
    topLeft: Offset,
    brush: Brush,
    shadow: Shadow = LyricsClearShadow,
) {
    val color = (brush as? SolidColor)?.value
    if (!needsOverlapSafeDraw(vc, color)) {
        drawText(textLayoutResult = vc.subLayout, topLeft = topLeft, brush = brush, shadow = shadow)
        return
    }
    val c = color!!
    val bounds = joiningLayerBounds(vc, topLeft)
    drawIntoCanvas { canvas ->
        joiningLayerPaint.alpha = c.alpha
        joiningLayerPaint.blendMode = BlendMode.SrcOver
        canvas.saveLayer(bounds, joiningLayerPaint)
        drawText(
            textLayoutResult = vc.subLayout,
            topLeft = topLeft,
            color = c.copy(alpha = 1f),
            shadow = shadow,
        )
        canvas.restore()
    }
}

internal fun DrawScope.drawClusterBandText(
    vc: VisualCluster,
    topLeft: Offset,
    bandBrush: Brush,
    unlitColor: Color,
    shadow: Shadow = LyricsClearShadow,
) {
    if (!vc.isJoiningScript || unlitColor.alpha >= OPAQUE_EPS) {
        drawText(textLayoutResult = vc.subLayout, topLeft = topLeft, brush = bandBrush, shadow = shadow)
        return
    }
    val bounds = joiningLayerBounds(vc, topLeft)
    drawIntoCanvas { canvas ->
        joiningLayerPaint.alpha = 1f
        joiningLayerPaint.blendMode = BlendMode.SrcOver
        canvas.saveLayer(bounds, joiningLayerPaint)
        drawText(
            textLayoutResult = vc.subLayout,
            topLeft = topLeft,
            color = Color.Black,
            shadow = shadow,
        )
        translate(topLeft.x, topLeft.y) {
            drawRect(
                brush = bandBrush,
                topLeft = Offset(bounds.left - topLeft.x, bounds.top - topLeft.y),
                size = Size(bounds.width, bounds.height),
                blendMode = BlendMode.SrcIn,
            )
        }
        canvas.restore()
    }
}

internal fun DrawScope.drawWipeText(
    vc: VisualCluster,
    topLeft: Offset,
    isRtlLine: Boolean,
    lineStart: Float,
    lineWidth: Float,
    localStart: Float,
    localEnd: Float,
    activeSylIdx: Int,
    activeLineIdx: Int,
    curGX: Float,
    isActiveSylLastOfLine: Boolean,
    activeSylProgress: Float,
    gradientWidth: Float,
    pastBrush: Brush,
    unlitBrush: Brush,
    litColor: Color,
    unlitColor: Color,
    wipe: WipeResult,
    shadow: Shadow = LyricsClearShadow,
) {
    computeWipe(
        vc = vc,
        isRtlLine = isRtlLine,
        lineStart = lineStart,
        lineWidth = lineWidth,
        localStart = localStart,
        localEnd = localEnd,
        activeSylIdx = activeSylIdx,
        activeLineIdx = activeLineIdx,
        curGX = curGX,
        isActiveSylLastOfLine = isActiveSylLastOfLine,
        activeSylProgress = activeSylProgress,
        gradientWidth = gradientWidth,
        out = wipe,
    )
    when (wipe.mode) {
        WIPE_PAST -> {
            drawClusterText(vc = vc, topLeft = topLeft, brush = pastBrush, shadow = shadow)
        }

        WIPE_UNLIT -> {
            drawClusterText(vc = vc, topLeft = topLeft, brush = unlitBrush, shadow = shadow)
        }

        else -> {
            val brush =
                Brush.linearGradient(
                    0.0f to litColor,
                    0.1f to litColor,
                    0.9f to unlitColor,
                    1.0f to unlitColor,
                    start = Offset(wipe.bandStart, 0f),
                    end = Offset(wipe.bandEnd, 0f),
                )
            if (vc.isJoiningScript) {
                drawClusterBandText(
                    vc = vc,
                    topLeft = topLeft,
                    bandBrush = brush,
                    unlitColor = unlitColor,
                    shadow = shadow,
                )
            } else {
                drawText(
                    textLayoutResult = vc.subLayout,
                    topLeft = topLeft,
                    brush = brush,
                    shadow = shadow,
                )
            }
        }
    }
}

internal class LyricAnimationState {
    val globalX = Animatable(0f)
    val colorState = Animatable(0f)
    val simpleState = Animatable(0f)
    val elevations = mutableMapOf<Long, Animatable<Float, AnimationVector1D>>()
    val growAnimations = mutableMapOf<Int, Animatable<Float, AnimationVector1D>>()
    var isFirstRun = true
    var finishStartX = 0f
    var finishStartLineIdx = 0
    var finishStartShiftProgress = 0f

    fun elevationFor(key: Long): Animatable<Float, AnimationVector1D> = elevations.getOrPut(key) { Animatable(0f) }

    fun growAnimFor(index: Int): Animatable<Float, AnimationVector1D> = growAnimations.getOrPut(index) { Animatable(0f) }
}

internal fun finishingLineIndex(
    curGX: Float,
    lineStarts: FloatArray,
    lineWidths: FloatArray,
    lineCount: Int,
    minLineIdx: Int = 0,
): Int {
    val startLi = minLineIdx.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
    for (li in startLi until lineCount - 1) {
        if (curGX < lineStarts[li] + lineWidths[li]) return li
    }
    return (lineCount - 1).coerceAtLeast(0)
}

internal fun finishingSylProgress(
    curGX: Float,
    wipeLineIdx: Int,
    lineStarts: FloatArray,
    lineWidths: FloatArray,
    animState: LyricAnimationState,
): Float {
    val rowStart = lineStarts.getOrElse(wipeLineIdx) { 0f }
    val rowEnd = rowStart + maxOf(1f, lineWidths.getOrElse(wipeLineIdx) { 1f })
    val startX = if (wipeLineIdx == animState.finishStartLineIdx) maxOf(rowStart, animState.finishStartX) else rowStart
    val initProg = if (wipeLineIdx == animState.finishStartLineIdx) animState.finishStartShiftProgress else 0f
    val t = ((curGX - startX) / maxOf(1f, rowEnd - startX)).coerceIn(0f, 1f)
    return initProg + (1f - initProg) * t
}

internal fun packElevationKey(
    isHeavy: Boolean,
    value: Long,
): Long = (value shl 1) or (if (isHeavy) 1L else 0L)

internal class VisualCluster(
    val start: Int,
    val end: Int,
    var lineIdx: Int = 0,
    var x: Float = 0f,
    var y: Float = 0f,
    var width: Float = 0f,
    var isHeavy: Boolean = false,
    var isRtl: Boolean = false,
    var isJoiningScript: Boolean = false,
    var wordStartMs: Long = 0L,
    var wordEndMs: Long = 0L,
    var intensity: Float = 0f,
    var normX: Float = 0f,
    val subLayout: TextLayoutResult,
    var charShadowIntensity: Float = 0f,
    var charTranslateYPeak: Float = 0f,
    var charDelayMs: Long = 0L,
    var syllableIndex: Int = -1,
    var lastSyllableIndex: Int = syllableIndex,
)

internal class ClusterMeta(
    val start: Int,
    val end: Int,
    val lineIdx: Int,
    val x: Float,
    val y: Float,
    val width: Float,
) {
    var isHeavy: Boolean = false
    var isRtl: Boolean = false
    var isJoiningScript: Boolean = false
    var wordIndex: Int = -1
    var wordStartMs: Long = 0L
    var wordEndMs: Long = 0L
    var intensity: Float = 0f
    var normX: Float = 0f
    var charDelayMs: Long = 0L
    var charShadowIntensity: Float = 0f
    var charTranslateYPeak: Float = 0f
}

internal data class ActiveSylInfo(
    val index: Int,
    val lineIdx: Int,
    val isLastOfLine: Boolean,
)

internal data class AlignedSyllable(
    val syllable: WordTimestamp,
    val startOffset: Int,
    val endOffset: Int,
)

internal class SyllableBounds(
    val startX: FloatArray,
    val endX: FloatArray,
)

internal fun buildSyllableBounds(
    alignedSyllables: List<AlignedSyllable>,
    charXMap: FloatArray,
    textLayoutResult: TextLayoutResult,
    formattedMainText: String,
): SyllableBounds {
    val n = alignedSyllables.size
    val startX = FloatArray(n)
    val endX = FloatArray(n)
    val maxLen = maxOf(0, formattedMainText.length - 1)

    fun gx(offset: Int): Float {
        if (offset < 0) return 0f
        return charXMap[offset.coerceIn(0, charXMap.size - 1)]
    }
    for (i in 0 until n) {
        val current = alignedSyllables[i]
        val currentLine = textLayoutResult.getLineForOffset(current.startOffset.coerceIn(0, maxLen))
        val startOff =
            if (i > 0) {
                val prev = alignedSyllables[i - 1]
                val prevLine = textLayoutResult.getLineForOffset(prev.endOffset.coerceIn(0, maxLen))
                if (prevLine == currentLine) prev.endOffset else current.startOffset
            } else {
                current.startOffset
            }
        val sX = gx(startOff)
        val eX = gx(current.endOffset)
        startX[i] = minOf(sX, eX)
        endX[i] = maxOf(sX, eX)
    }
    return SyllableBounds(startX, endX)
}

internal class ReconstructedWord(
    val startIndex: Int,
    val endIndex: Int,
    val wordStartTimeMs: Long,
    val wordEndTimeMs: Long,
    val wordLen: Int,
    val syllables: List<AlignedSyllable>,
)

internal fun reconstructWords(
    alignedSyllables: List<AlignedSyllable>,
    formattedMainText: String,
): List<ReconstructedWord> {
    val result = mutableListOf<ReconstructedWord>()
    var idx = 0
    while (idx < alignedSyllables.size) {
        val group = mutableListOf<AlignedSyllable>()
        group.add(alignedSyllables[idx])
        idx++
        while (idx < alignedSyllables.size) {
            val prev = group.last()
            val next = alignedSyllables[idx]
            val gapStart = prev.endOffset
            val gapEnd = next.startOffset
            val hasSpaceBetween =
                prev.syllable.hasTrailingSpace ||
                    prev.syllable.text
                        .lastOrNull()
                        ?.isWhitespace() == true ||
                    next.syllable.text
                        .firstOrNull()
                        ?.isWhitespace() == true ||
                    (gapEnd > gapStart && formattedMainText.substring(gapStart, gapEnd).any { it.isWhitespace() })
            if (hasSpaceBetween) break
            group.add(next)
            idx++
        }
        if (group.isNotEmpty()) {
            val wordLen = group.sumOf { it.syllable.text.length }
            result.add(
                ReconstructedWord(
                    startIndex = group.first().startOffset,
                    endIndex = group.last().endOffset,
                    wordStartTimeMs = round(group.first().syllable.startTime * 1000).toLong(),
                    wordEndTimeMs = round(group.last().syllable.endTime * 1000).toLong(),
                    wordLen = wordLen,
                    syllables = group,
                ),
            )
        }
    }
    return result
}

internal fun buildInitialClusterMetas(
    formattedMainText: String,
    textLayoutResult: TextLayoutResult,
    lineIsRtl: BooleanArray,
): MutableList<ClusterMeta> {
    val metaList = mutableListOf<ClusterMeta>()
    val maxIdx = maxOf(0, formattedMainText.length - 1)
    var i = 0
    while (i < formattedMainText.length) {
        val start = i
        val end =
            if (i + 1 < formattedMainText.length && formattedMainText[i].isHighSurrogate() &&
                formattedMainText[i + 1].isLowSurrogate()
            ) {
                i + 2
            } else {
                i + 1
            }
        i = end

        if (formattedMainText[start] == '\n') continue

        val safeStart = start.coerceIn(0, maxIdx)
        val safeEnd = minOf(end, maxIdx)
        val li = textLayoutResult.getLineForOffset(safeStart)
        val eli = textLayoutResult.getLineForOffset(safeEnd)
        val hStart = textLayoutResult.getHorizontalPosition(safeStart, true)
        val hEnd =
            if (li == eli) {
                textLayoutResult.getHorizontalPosition(end, true)
            } else {
                textLayoutResult.getLineRight(li)
            }
        val x = minOf(hStart, hEnd)
        val w = abs(hEnd - hStart)
        val y = textLayoutResult.getLineTop(li)

        metaList.add(
            ClusterMeta(start = start, end = end, lineIdx = li, x = x, y = y, width = w).apply {
                isRtl = lineIsRtl[li]
            },
        )
    }
    return metaList
}

internal fun metasInRange(
    metaList: List<ClusterMeta>,
    startIdx: Int,
    endIdx: Int,
): List<ClusterMeta> {
    fun metaLowerBound(pos: Int): Int {
        var lo = 0
        var hi = metaList.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (metaList[mid].end <= pos) lo = mid + 1 else hi = mid
        }
        return lo
    }
    return metaList.subList(metaLowerBound(startIdx), metaLowerBound(endIdx))
}

internal fun buildVisualClusters(
    metaList: List<ClusterMeta>,
    alignedSyllables: List<AlignedSyllable>,
    formattedMainText: String,
    lineBaselines: FloatArray,
    lyricStyle: TextStyle,
    expressiveAccent: Color,
    textMeasurer: TextMeasurer,
): List<VisualCluster> {
    fun measureRun(text: String): TextLayoutResult =
        textMeasurer.measure(
            text = text,
            style =
                lyricStyle.copy(
                    textAlign = TextAlign.Start,
                    color = expressiveAccent,
                    letterSpacing = lyricStyle.letterSpacing,
                ),
            constraints = Constraints(),
            skipCache = false,
        )

    var sylPointer = 0

    fun sylIndexFor(m: ClusterMeta): Int {
        while (sylPointer < alignedSyllables.size && alignedSyllables[sylPointer].endOffset <= m.start) {
            sylPointer++
        }
        val s = alignedSyllables.getOrNull(sylPointer) ?: return -1
        return if (m.start >= s.startOffset && m.end <= s.endOffset) sylPointer else -1
    }

    val list = mutableListOf<VisualCluster>()
    var idx = 0
    while (idx < metaList.size) {
        val m = metaList[idx]
        if (m.isHeavy && !m.isJoiningScript) {
            val subLayout =
                measureRun(
                    formattedMainText.substring(
                        m.start.coerceIn(0, formattedMainText.length),
                        m.end.coerceIn(0, formattedMainText.length),
                    ),
                )
            val clusterY = lineBaselines[m.lineIdx] - subLayout.getLineBaseline(0)
            list.add(
                VisualCluster(
                    start = m.start,
                    end = m.end,
                    lineIdx = m.lineIdx,
                    x = m.x,
                    y = clusterY,
                    width = m.width,
                    isHeavy = true,
                    isRtl = m.isRtl,
                    wordStartMs = m.wordStartMs,
                    wordEndMs = m.wordEndMs,
                    intensity = m.intensity,
                    normX = m.normX,
                    subLayout = subLayout,
                    charShadowIntensity = m.charShadowIntensity,
                    charTranslateYPeak = m.charTranslateYPeak,
                    charDelayMs = m.charDelayMs,
                    syllableIndex = sylIndexFor(m),
                ),
            )
            idx++
        } else if (m.isHeavy && m.isJoiningScript) {
            val firstSylIdx = sylIndexFor(m)
            var j = idx
            while (
                j + 1 < metaList.size && metaList[j + 1].isHeavy && metaList[j + 1].isJoiningScript &&
                metaList[j + 1].lineIdx == m.lineIdx &&
                if (m.wordIndex != -1) {
                    metaList[j + 1].wordIndex == m.wordIndex
                } else {
                    metaList[j + 1].wordStartMs == m.wordStartMs &&
                        metaList[j + 1].wordEndMs == m.wordEndMs
                }
            ) {
                j++
            }
            val runMetas = metaList.subList(idx, j + 1)
            val runLeft = runMetas.minOf { it.x }
            val runRight = runMetas.maxOf { it.x + it.width }
            val last = metaList[j]
            val lastSylIdxForRun =
                if (j > idx) sylIndexFor(last).let { if (it != -1) it else firstSylIdx } else firstSylIdx
            val subLayout =
                measureRun(
                    formattedMainText.substring(
                        m.start.coerceIn(0, formattedMainText.length),
                        last.end.coerceIn(0, formattedMainText.length),
                    ),
                )
            val clusterY = lineBaselines[m.lineIdx] - subLayout.getLineBaseline(0)
            list.add(
                VisualCluster(
                    start = m.start,
                    end = last.end,
                    lineIdx = m.lineIdx,
                    x = runLeft,
                    y = clusterY,
                    width = runRight - runLeft,
                    isHeavy = true,
                    isRtl = m.isRtl,
                    isJoiningScript = true,
                    wordStartMs = m.wordStartMs,
                    wordEndMs = m.wordEndMs,
                    intensity = m.intensity,
                    normX = 0f,
                    subLayout = subLayout,
                    charShadowIntensity = m.charShadowIntensity,
                    charTranslateYPeak = m.charTranslateYPeak,
                    charDelayMs = 0L,
                    syllableIndex = firstSylIdx,
                    lastSyllableIndex = lastSylIdxForRun,
                ),
            )
            idx = j + 1
        } else {
            val runSylIdx = sylIndexFor(m)
            var j = idx
            val isJoiningWordRun = m.isJoiningScript && m.wordIndex != -1
            if (isJoiningWordRun) {
                while (
                    j + 1 < metaList.size && !metaList[j + 1].isHeavy &&
                    metaList[j + 1].lineIdx == m.lineIdx &&
                    metaList[j + 1].isJoiningScript &&
                    metaList[j + 1].wordIndex == m.wordIndex
                ) {
                    j++
                }
            } else {
                while (
                    j + 1 < metaList.size && !metaList[j + 1].isHeavy &&
                    metaList[j + 1].lineIdx == m.lineIdx &&
                    !metaList[j + 1].isJoiningScript &&
                    sylIndexFor(metaList[j + 1]) == runSylIdx
                ) {
                    j++
                }
            }
            val runMetas = metaList.subList(idx, j + 1)
            val runLeft = runMetas.minOf { it.x }
            val runRight = runMetas.maxOf { it.x + it.width }
            val last = metaList[j]
            val lastSylIdxForRun =
                if (isJoiningWordRun && j > idx) {
                    sylIndexFor(last).let { if (it != -1) it else runSylIdx }
                } else {
                    runSylIdx
                }
            val subLayout =
                measureRun(
                    formattedMainText.substring(
                        m.start.coerceIn(0, formattedMainText.length),
                        last.end.coerceIn(0, formattedMainText.length),
                    ),
                )
            val clusterY = lineBaselines[m.lineIdx] - subLayout.getLineBaseline(0)
            list.add(
                VisualCluster(
                    start = m.start,
                    end = last.end,
                    lineIdx = m.lineIdx,
                    x = runLeft,
                    y = clusterY,
                    width = runRight - runLeft,
                    isHeavy = false,
                    isRtl = m.isRtl,
                    isJoiningScript = m.isJoiningScript,
                    subLayout = subLayout,
                    syllableIndex = runSylIdx,
                    lastSyllableIndex = lastSylIdxForRun,
                ),
            )
            idx = j + 1
        }
    }
    return list
}

@Composable
internal fun rememberActiveSyllableIdxState(
    alignedSyllables: List<AlignedSyllable>,
    currentPositionProvider: () -> Long,
): State<Int> =
    remember(alignedSyllables) {
        derivedStateOf(structuralEqualityPolicy()) {
            val pos = currentPositionProvider()
            var lo = 0
            var hi = alignedSyllables.size - 1
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val ss = (alignedSyllables[mid].syllable.startTime * 1000).toLong()
                if (ss <= pos) {
                    ans = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            ans
        }
    }

@Composable
internal fun rememberActiveSylInfoState(
    activeSylIdxState: State<Int>,
    alignedSyllables: List<AlignedSyllable>,
    textLayoutResult: TextLayoutResult,
    formattedMainText: String,
): State<ActiveSylInfo> =
    remember(activeSylIdxState, alignedSyllables, textLayoutResult, formattedMainText) {
        derivedStateOf(structuralEqualityPolicy()) {
            val idx = activeSylIdxState.value
            val s = alignedSyllables.getOrNull(idx)
            val lineIdx =
                if (s != null) {
                    textLayoutResult.getLineForOffset(
                        s.startOffset.coerceIn(
                            0,
                            maxOf(0, formattedMainText.length - 1),
                        ),
                    )
                } else {
                    -1
                }
            val lastOfLine =
                if (idx != -1 && idx < alignedSyllables.size && lineIdx != -1) {
                    if (idx == alignedSyllables.lastIndex) {
                        true
                    } else {
                        val currentSyl = alignedSyllables[idx]
                        val nextSyl = alignedSyllables[idx + 1]
                        val nextLineIdx =
                            textLayoutResult.getLineForOffset(
                                nextSyl.startOffset.coerceIn(0, maxOf(0, formattedMainText.length - 1)),
                            )
                        val sOffset =
                            minOf(currentSyl.startOffset, nextSyl.startOffset).coerceIn(
                                0,
                                formattedMainText.length,
                            )
                        val eOffset =
                            maxOf(currentSyl.endOffset, nextSyl.startOffset).coerceIn(
                                0,
                                formattedMainText.length,
                            )
                        nextLineIdx > lineIdx || formattedMainText.substring(sOffset, eOffset).contains('\n')
                    }
                } else {
                    false
                }
            ActiveSylInfo(idx, lineIdx, lastOfLine)
        }
    }

@Composable
internal fun rememberActiveSylProgressState(
    activeSylIdxState: State<Int>,
    alignedSyllables: List<AlignedSyllable>,
    currentPositionProvider: () -> Long,
): State<Float> =
    remember(alignedSyllables) {
        derivedStateOf(structuralEqualityPolicy()) {
            val currentPosition = currentPositionProvider()
            val activeS = alignedSyllables.getOrNull(activeSylIdxState.value)
            if (activeS != null) {
                val ssMs = (activeS.syllable.startTime * 1000).toLong()
                val seMs = (activeS.syllable.endTime * 1000).toLong()
                val dur = maxOf(1L, seMs - ssMs)
                ((currentPosition - ssMs).coerceIn(0L, dur)).toFloat() / dur.toFloat()
            } else {
                0f
            }
        }
    }

internal fun syllableWipePosition(
    positionMs: Long,
    startMs: Long,
    endMs: Long,
    startX: Float,
    endX: Float,
): Float {
    val durationMs = maxOf(1L, endMs - startMs)
    val progress = (positionMs - startMs).coerceIn(0L, durationMs).toFloat() / durationMs
    return startX + (endX - startX) * progress
}

@Composable
internal fun rememberWiperPosition(
    animState: LyricAnimationState,
    isActiveLine: Boolean,
    alignedSyllables: List<AlignedSyllable>,
    sylBounds: SyllableBounds,
    activeSylIdxState: State<Int>,
    currentPositionProvider: () -> Long,
    lineEndX: Float,
    reachedLineEnd: Boolean,
    activeInfoState: State<ActiveSylInfo>? = null,
    activeSylProgressState: State<Float>? = null,
): State<Float> {
    val lastActiveX = remember(alignedSyllables, sylBounds) { floatArrayOf(0f) }
    val activeGen = remember(alignedSyllables, sylBounds) { IntArray(1) }
    var syncedGen by remember(alignedSyllables, sylBounds) { mutableIntStateOf(0) }
    val position =
        remember(
            isActiveLine,
            alignedSyllables,
            sylBounds,
            activeSylIdxState,
            reachedLineEnd,
            lineEndX,
            activeInfoState,
            activeSylProgressState,
        ) {
            derivedStateOf {
                if (isActiveLine) {
                    val index = activeSylIdxState.value
                    val syllable = alignedSyllables.getOrNull(index)?.syllable
                    val x =
                        if (syllable == null) {
                            0f
                        } else {
                            syllableWipePosition(
                                currentPositionProvider(),
                                (syllable.startTime * 1000).toLong(),
                                (syllable.endTime * 1000).toLong(),
                                sylBounds.startX[index],
                                sylBounds.endX[index],
                            )
                        }
                    lastActiveX[0] = x
                    val info = activeInfoState?.value
                    val prog = activeSylProgressState?.value ?: 0f
                    animState.finishStartX = x
                    animState.finishStartLineIdx = info?.lineIdx?.coerceAtLeast(0) ?: 0
                    animState.finishStartShiftProgress = if (info?.isLastOfLine == true) prog else 0f
                    x
                } else {
                    val animX = animState.globalX.value
                    val isSynced = syncedGen == activeGen[0]
                    if (!isSynced) {
                        val x = lastActiveX[0]
                        if (!animState.isFirstRun && reachedLineEnd && x in 1f..(lineEndX - 0.5f)) {
                            x
                        } else {
                            0f
                        }
                    } else {
                        animX
                    }
                }
            }
        }
    LaunchedEffect(isActiveLine, reachedLineEnd, lineEndX) {
        if (isActiveLine) {
            activeGen[0] += 1
            animState.isFirstRun = false
            return@LaunchedEffect
        }
        if (animState.isFirstRun) {
            syncedGen = activeGen[0]
            return@LaunchedEffect
        }
        val x = lastActiveX[0]
        if (reachedLineEnd && x in 1f..(lineEndX - 0.5f)) {
            animState.globalX.snapTo(x)
            syncedGen = activeGen[0]
            withContext(FixedMotionDurationScale) {
                animState.globalX.animateTo(lineEndX, tween(durationMillis = 250, easing = LinearEasing))
            }
        } else {
            animState.globalX.snapTo(0f)
            syncedGen = activeGen[0]
        }
    }
    return position
}

@Composable
internal fun LaunchLineColorAnimations(
    animState: LyricAnimationState,
    isActiveLine: Boolean,
    lineStartTime: Long,
    effectiveEndTimeMs: Long,
    currentPositionProvider: () -> Long,
    isFinishingProvider: () -> Boolean = { false },
) {
    val targetCS by remember(isActiveLine, effectiveEndTimeMs) {
        derivedStateOf(structuralEqualityPolicy()) {
            if (!isActiveLine && !isFinishingProvider() && currentPositionProvider() > effectiveEndTimeMs) 1f else 0f
        }
    }
    val targetSS by remember(isActiveLine, lineStartTime, effectiveEndTimeMs) {
        derivedStateOf(structuralEqualityPolicy()) {
            if ((isActiveLine && currentPositionProvider() in lineStartTime..effectiveEndTimeMs) || isFinishingProvider()) 1f else 0f
        }
    }

    LaunchedEffect(targetCS) {
        if (animState.isFirstRun) {
            animState.colorState.snapTo(targetCS)
        } else {
            withContext(FixedMotionDurationScale) {
                animState.colorState.animateTo(
                    targetCS,
                    spring(stiffness = 400f, dampingRatio = Spring.DampingRatioNoBouncy),
                )
            }
        }
    }

    LaunchedEffect(targetSS) {
        if (animState.isFirstRun) {
            animState.simpleState.snapTo(targetSS)
            animState.isFirstRun = false
        } else {
            withContext(FixedMotionDurationScale) {
                animState.simpleState.animateTo(
                    targetSS,
                    spring(stiffness = 200f, dampingRatio = Spring.DampingRatioNoBouncy),
                )
            }
        }
    }
}

internal fun Modifier.lyricsWordCanvasLayout(
    mainText: String,
    textLayoutResult: TextLayoutResult,
    lineCount: Int,
    lineExtraHeight: Float,
    hasSyllableRomanization: Boolean,
    fontSizePx: Float,
    extraPaddingPx: Float,
): Modifier =
    this
        .fillMaxWidth()
        .semantics { text = AnnotatedString(mainText) }
        .layout { measurable, constraints ->
            val extraPx = extraPaddingPx.toInt()
            val totalExtraPx = (lineCount * lineExtraHeight).toInt()
            val bottomMargin = if (hasSyllableRomanization) (fontSizePx * 0.25f).toInt() else 0
            val originalHeight = textLayoutResult.size.height + totalExtraPx + bottomMargin
            val newConstraints =
                if (constraints.hasBoundedWidth) {
                    constraints.copy(
                        minWidth = (constraints.minWidth + extraPx * 2).coerceAtLeast(0),
                        maxWidth = constraints.maxWidth + extraPx * 2,
                        minHeight = originalHeight + extraPx * 2,
                        maxHeight = originalHeight + extraPx * 2,
                    )
                } else {
                    constraints.copy(
                        minHeight = originalHeight + extraPx * 2,
                        maxHeight = originalHeight + extraPx * 2,
                    )
                }
            val placeable = measurable.measure(newConstraints)
            val originalWidth =
                if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width - extraPx * 2
            layout(originalWidth, originalHeight) {
                placeable.placeRelative(-extraPx, -extraPx)
            }
        }

internal fun Char.Companion.toCodePoint(
    high: Char,
    low: Char,
): Int = ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000

internal fun String.codePointAt(index: Int): Int {
    if (index >= length) return 0
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) {
            return Char.toCodePoint(high, low)
        }
    }
    return high.code
}

internal fun isArabic(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (cp in 0x0600..0x06FF || cp in 0x0750..0x077F || cp in 0x08A0..0x08FF || cp in 0xFB50..0xFDFF || cp in 0xFE70..0xFEFF) {
            return true
        }
        i += if (cp > 0xFFFF) 2 else 1
    }
    return false
}

internal fun isJoiningScript(text: String): Boolean =
    isArabic(text) ||
        text.any {
            it.category == CharCategory.NON_SPACING_MARK ||
                it.category == CharCategory.COMBINING_SPACING_MARK ||
                it.category == CharCategory.ENCLOSING_MARK ||
                it == '\u200D' || it == '\u200C'
        }

internal fun Char.isBidiOrInvisible(): Boolean =
    when (this) {
        '\u200E', '\u200F', '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069', '\uFEFF',
        -> true

        else -> false
    }

internal fun String.stripBidiControls(): String = filterNot { it.isBidiOrInvisible() }

fun isRtlText(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (cp in 0x0590..0x08FF || cp in 0xFB1D..0xFDFF || cp in 0xFE70..0xFEFF) {
            return true
        }
        i += if (cp > 0xFFFF) 2 else 1
    }
    return false
}

internal fun Char.isDash(): Boolean =
    when (this) {
        '-', '—', '–', '−', '―', '‐', '‑', '~', '〜', '〰', '\uFF0D' -> true
        else -> false
    }

internal class ClusterStatics(
    val baseX: FloatArray,
    val baseY: FloatArray,
    val isRtlLine: BooleanArray,
    val lineStart: FloatArray,
    val lineWidth: FloatArray,
    val localStart: FloatArray,
    val localEnd: FloatArray,
    val pivotX: FloatArray,
    val pivotY: FloatArray,
)

internal fun buildClusterStatics(
    clusters: List<VisualCluster>,
    romanStartShift: FloatArray,
    lineExtraHeight: Float,
    lineIsRtl: BooleanArray,
    lineLefts: FloatArray,
    lineRights: FloatArray,
    lineStarts: FloatArray,
    lineWidths: FloatArray,
    computePivotY: ((vc: VisualCluster) -> Float)? = null,
): ClusterStatics {
    val n = clusters.size
    val baseX = FloatArray(n)
    val baseY = FloatArray(n)
    val rtl = BooleanArray(n)
    val ls = FloatArray(n)
    val lw = FloatArray(n)
    val localStart = FloatArray(n)
    val localEnd = FloatArray(n)
    val pivotX = FloatArray(n)
    val pivotY = FloatArray(n)
    for (i in 0 until n) {
        val vc = clusters[i]
        baseX[i] = vc.x + calculateGapShift(vc.syllableIndex, romanStartShift)
        baseY[i] = vc.y + vc.lineIdx * lineExtraHeight
        val isRtl = lineIsRtl[vc.lineIdx]
        rtl[i] = isRtl
        val lineL = lineLefts[vc.lineIdx]
        val lineR = lineRights[vc.lineIdx]
        ls[i] = lineStarts[vc.lineIdx]
        lw[i] = lineWidths[vc.lineIdx]
        val lStart = if (isRtl) lineR - (vc.x + vc.width) else vc.x - lineL
        localStart[i] = lStart
        localEnd[i] = lStart + vc.width
        pivotX[i] = vc.width / 2f
        val subHeight =
            vc.subLayout.size.height
                .toFloat()
        pivotY[i] = computePivotY?.invoke(vc) ?: subHeight
    }
    return ClusterStatics(baseX, baseY, rtl, ls, lw, localStart, localEnd, pivotX, pivotY)
}
