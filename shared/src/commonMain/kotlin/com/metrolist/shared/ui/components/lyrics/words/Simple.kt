/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components.lyrics.words

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import com.metrolist.shared.model.WordTimestamp
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.LYRICS_LINE_HORIZONTAL_PADDING

@Composable
internal fun SimpleWordAnimation(
    mainText: String,
    words: List<WordTimestamp>,
    isActiveLine: Boolean,
    positionProvider: () -> Long,
    lyricStyle: TextStyle,
    lineColor: () -> Color,
    expressiveAccent: Color,
    focusedAlpha: () -> Float,
    alignment: TextAlign,
    textMeasurer: TextMeasurer,
    isPlaying: Boolean = true,
    romanizedWords: List<WordTimestamp>? = null,
    romanizedText: String? = null,
    showSyllableRomanization: Boolean = false,
) {
    val currentPositionProvider by rememberUpdatedState(positionProvider)
    val density = LocalDensity.current
    val fontSizePx = remember(lyricStyle) { with(density) { lyricStyle.fontSize.toPx() } }
    val gradientWidth = fontSizePx * 1.25f
    val extraPaddingPx = minOf(fontSizePx * 0.3f, with(density) { LYRICS_LINE_HORIZONTAL_PADDING.toPx() })

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth
        if (widthPx <= 0) return@BoxWithConstraints

        val formattedMainText =
            rememberFormattedMainText(
                mainText = mainText,
                words = words,
                romanizedWords = romanizedWords,
                romanizedText = romanizedText,
                showSyllableRomanization = showSyllableRomanization,
                lyricStyle = lyricStyle,
                textMeasurer = textMeasurer,
                widthPx = widthPx,
            )

        val textLayoutResult =
            remember(formattedMainText, lyricStyle, widthPx) {
                textMeasurer.measure(
                    text = AnnotatedString(formattedMainText),
                    style = lyricStyle,
                    softWrap = true,
                    constraints = Constraints(minWidth = widthPx, maxWidth = widthPx),
                )
            }

        val metrics = remember(textLayoutResult) { WordLineMetrics(textLayoutResult) }
        val lineCount = metrics.lineCount
        val lineLefts = metrics.lineLefts
        val lineRights = metrics.lineRights
        val lineStarts = metrics.lineStarts
        val lineWidths = metrics.lineWidths
        val totalWidth = metrics.totalWidth
        val lineIsRtl = metrics.lineIsRtl
        val lineBaselines = metrics.lineBaselines
        val lineBottoms = metrics.lineBottoms

        val alignedSyllables =
            remember(words, formattedMainText) { alignSyllablesForFormatting(words, formattedMainText) }

        val romanClusters =
            rememberRomanClusters(
                showSyllableRomanization = showSyllableRomanization,
                alignedSyllables = alignedSyllables,
                romanizedWords = romanizedWords,
                romanizedText = romanizedText,
                mainText = formattedMainText,
                textLayoutResult = textLayoutResult,
                textMeasurer = textMeasurer,
                lyricStyle = lyricStyle,
                expressiveAccent = expressiveAccent,
            )

        val hasSyllableRomanization =
            showSyllableRomanization && romanClusters.isNotEmpty()

        val lineExtraHeight =
            remember(hasSyllableRomanization, fontSizePx) {
                if (hasSyllableRomanization) (fontSizePx * 1.05f) else 0f
            }

        val reconstructedWords =
            remember(alignedSyllables, formattedMainText) {
                reconstructWords(alignedSyllables, formattedMainText)
            }

        val charPositionMaps =
            rememberCharXMap(
                mainText = formattedMainText,
                textLayoutResult = textLayoutResult,
                lineCount = lineCount,
                lineIsRtl = lineIsRtl,
                lineStarts = lineStarts,
                alignedSyllables = alignedSyllables,
                romanClusters = romanClusters,
                alignment = alignment,
            )
        val charXMap = charPositionMaps.charXMap
        val romanStartShift = charPositionMaps.romanStartShift

        fun globalX(offset: Int): Float {
            if (offset < 0) return 0f
            if (offset >= charXMap.size) return totalWidth
            return charXMap[offset]
        }

        val sylBounds =
            remember(alignedSyllables, charXMap, textLayoutResult, formattedMainText) {
                buildSyllableBounds(alignedSyllables, charXMap, textLayoutResult, formattedMainText)
            }

        val clusters =
            remember(formattedMainText, textLayoutResult, reconstructedWords, alignedSyllables, lyricStyle) {
                val metaList = buildInitialClusterMetas(formattedMainText, textLayoutResult, lineIsRtl)

                for ((wordIdx, word) in reconstructedWords.withIndex()) {
                    val wordText = word.syllables.joinToString("") { it.syllable.text }
                    if (isJoiningScript(wordText)) {
                        for (m in metasInRange(metaList, word.startIndex, word.endIndex)) {
                            if (!formattedMainText[m.start].isWhitespace()) {
                                m.isJoiningScript = true
                                m.wordIndex = wordIdx
                            }
                        }
                    }
                }

                buildVisualClusters(
                    metaList = metaList,
                    alignedSyllables = alignedSyllables,
                    formattedMainText = formattedMainText,
                    lineBaselines = lineBaselines,
                    lyricStyle = lyricStyle,
                    expressiveAccent = expressiveAccent,
                    textMeasurer = textMeasurer,
                )
            }

        val animState = remember(mainText) { LyricAnimationState() }

        val activeSylIdxState = rememberActiveSyllableIdxState(alignedSyllables) { currentPositionProvider() }
        val activeInfoState =
            rememberActiveSylInfoState(activeSylIdxState, alignedSyllables, textLayoutResult, formattedMainText)
        val activeSylProgressState =
            rememberActiveSylProgressState(activeSylIdxState, alignedSyllables) { currentPositionProvider() }

        val lineEndX = if (alignedSyllables.isEmpty()) 0f else globalX(alignedSyllables.last().endOffset)
        val lastSylIdx = alignedSyllables.lastIndex
        val lineStartMs =
            alignedSyllables.firstOrNull()?.let { (it.syllable.startTime * 1000).toLong() } ?: Long.MAX_VALUE
        val reachedLineEnd by remember(lineStartMs) {
            derivedStateOf(structuralEqualityPolicy()) {
                val currentPosition = currentPositionProvider()
                currentPosition > 0L && currentPosition >= lineStartMs
            }
        }

        val curGX by rememberWiperPosition(
            animState = animState,
            isActiveLine = isActiveLine,
            alignedSyllables = alignedSyllables,
            sylBounds = sylBounds,
            activeSylIdxState = activeSylIdxState,
            currentPositionProvider = { currentPositionProvider() },
            lineEndX = lineEndX,
            reachedLineEnd = reachedLineEnd,
            activeInfoState = activeInfoState,
            activeSylProgressState = activeSylProgressState,
        )

        val litColorCache = remember { ColorAlphaCache() }
        val unlitColorCache = remember { ColorAlphaCache() }
        val pastBrushCache = remember { SolidColorCache() }
        val unlitBrushCache = remember { SolidColorCache() }
        val lineBrushCache = remember { SolidColorCache() }
        val statics =
            remember(
                clusters,
                romanStartShift,
                lineExtraHeight,
                lineIsRtl,
                lineLefts,
                lineRights,
                lineStarts,
                lineWidths,
            ) {
                buildClusterStatics(
                    clusters = clusters,
                    romanStartShift = romanStartShift,
                    lineExtraHeight = lineExtraHeight,
                    lineIsRtl = lineIsRtl,
                    lineLefts = lineLefts,
                    lineRights = lineRights,
                    lineStarts = lineStarts,
                    lineWidths = lineWidths,
                )
            }
        val wipeScratch = remember { WipeResult() }
        val romanLines = remember(romanClusters, lineCount) { groupRomanClustersByLine(romanClusters, lineCount) }
        val romanStatics =
            remember(
                romanLines,
                charXMap,
                formattedMainText,
                romanStartShift,
                lineBaselines,
                lineBottoms,
                lineLefts,
                lineRights,
                lineStarts,
                lineIsRtl,
                alignment,
                widthPx,
                lineCount,
                fontSizePx,
                lineExtraHeight,
                totalWidth,
                extraPaddingPx,
            ) {
                buildRomanStatics(
                    romanLines = romanLines,
                    charXMap = charXMap,
                    mainText = formattedMainText,
                    romanStartShift = romanStartShift,
                    lineBaselines = lineBaselines,
                    lineBottoms = lineBottoms,
                    lineLefts = lineLefts,
                    lineRights = lineRights,
                    lineStarts = lineStarts,
                    lineIsRtl = lineIsRtl,
                    alignment = alignment,
                    canvasWidthPx = widthPx.toFloat(),
                    lineCount = lineCount,
                    fontSizePx = fontSizePx,
                    lineExtraHeight = lineExtraHeight,
                    totalWidth = totalWidth,
                    extraPaddingPx = extraPaddingPx,
                )
            }
        val finishing = { !isActiveLine && reachedLineEnd && lastSylIdx >= 0 && curGX in 1f..(lineEndX - 0.5f) }

        Canvas(
            modifier =
                Modifier
                    .lyricsWordCanvasLayout(
                        mainText = mainText,
                        textLayoutResult = textLayoutResult,
                        lineCount = lineCount,
                        lineExtraHeight = lineExtraHeight,
                        hasSyllableRomanization = hasSyllableRomanization,
                        fontSizePx = fontSizePx,
                        extraPaddingPx = extraPaddingPx,
                    ).graphicsLayer {
                        clip = false
                    },
        ) {
            val isFinishing = finishing()
            val showWipe = isActiveLine || isFinishing
            val activeInfo = if (isActiveLine) activeInfoState.value else null
            val wipeSylIdx = if (isFinishing) -2 else (activeInfo?.index ?: -1)
            val wipeLineIdx =
                if (isFinishing) {
                    finishingLineIndex(curGX, lineStarts, lineWidths, lineCount, animState.finishStartLineIdx)
                } else {
                    activeInfo?.lineIdx ?: -1
                }
            val isActiveSylLastOfLine = if (isFinishing) true else (activeInfo?.isLastOfLine ?: false)
            val activeSylProgress =
                if (isFinishing) {
                    finishingSylProgress(curGX, wipeLineIdx, lineStarts, lineWidths, animState)
                } else if (isActiveLine) {
                    activeSylProgressState.value
                } else {
                    0f
                }
            val litColor = litColorCache.get(expressiveAccent, 1f)
            val unlitColor = unlitColorCache.get(expressiveAccent, focusedAlpha())
            val clearShadow = LyricsClearShadow
            val lineBrush = if (showWipe) null else lineBrushCache.get(lineColor())

            val pastBrush = pastBrushCache.get(litColor)
            val unlitBrush = unlitBrushCache.get(unlitColor)
            val n = clusters.size

            for (idx in 0 until n) {
                val vc = clusters[idx]
                val topLeft = Offset(statics.baseX[idx] + extraPaddingPx, statics.baseY[idx] + extraPaddingPx)

                if (lineBrush != null) {
                    drawClusterText(
                        vc = vc,
                        topLeft = topLeft,
                        brush = lineBrush,
                        shadow = clearShadow,
                    )
                } else {
                    drawWipeText(
                        vc = vc,
                        topLeft = topLeft,
                        isRtlLine = statics.isRtlLine[idx],
                        lineStart = statics.lineStart[idx],
                        lineWidth = statics.lineWidth[idx],
                        localStart = statics.localStart[idx],
                        localEnd = statics.localEnd[idx],
                        activeSylIdx = wipeSylIdx,
                        activeLineIdx = wipeLineIdx,
                        curGX = curGX,
                        isActiveSylLastOfLine = isActiveSylLastOfLine,
                        activeSylProgress = activeSylProgress,
                        gradientWidth = gradientWidth,
                        pastBrush = pastBrush,
                        unlitBrush = unlitBrush,
                        litColor = litColor,
                        unlitColor = unlitColor,
                        wipe = wipeScratch,
                        shadow = clearShadow,
                    )
                }
            }

            if (romanLines.isNotEmpty()) {
                drawRomanizedWordClusters(
                    romanStatics = romanStatics,
                    isActiveLine = showWipe,
                    activeSylIdx = wipeSylIdx,
                    activeLineIdx = wipeLineIdx,
                    curGX = curGX,
                    isActiveSylLastOfLine = isActiveSylLastOfLine,
                    activeSylProgress = activeSylProgress,
                    gradientWidth = gradientWidth,
                    pastBrush = pastBrush,
                    unlitBrush = unlitBrush,
                    litColor = litColor,
                    unlitColor = unlitColor,
                    lineColor = if (showWipe) Color.Unspecified else lineColor(),
                    curSimpleState = 0f,
                    expressiveAccent = expressiveAccent,
                )
            }
        }
    }
}
