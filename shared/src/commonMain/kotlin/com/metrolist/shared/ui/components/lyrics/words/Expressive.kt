/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components.lyrics.words

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.metrolist.shared.model.WordTimestamp
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.LYRICS_LINE_HORIZONTAL_PADDING
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.time.TimeSource

private fun String.containsRtl(): Boolean = isRtlText(this)

private fun String.containsJoinedIndic(): Boolean = any { it in '\u0900'..'\u0DFF' } || isJoiningScript(this)

internal fun String.toLyricsGraphemeClusters(): List<String> =
    buildList {
        var index = 0
        while (index < length) {
            val start = index++
            if (this@toLyricsGraphemeClusters[start].isHighSurrogate() && index < length &&
                this@toLyricsGraphemeClusters[index].isLowSurrogate()
            ) {
                index++
            }
            while (index < length &&
                this@toLyricsGraphemeClusters[index].category in
                setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK)
            ) {
                index++
            }
            add(substring(start, index))
        }
    }

internal fun experimentalWordWobble(
    word: WordTimestamp,
    position: Long,
): Float {
    val startMs = (word.startTime * 1000).toLong()
    val timeSinceStart = (position - startMs).toFloat()
    return if (timeSinceStart in 0f..750f) {
        if (timeSinceStart < 125f) {
            timeSinceStart / 125f
        } else {
            (1f - (timeSinceStart - 125f) / 625f).coerceAtLeast(0f)
        }
    } else {
        0f
    }
}

internal fun experimentalGlyphProgress(
    word: WordTimestamp,
    position: Long,
    glyphIndex: Int,
    glyphCount: Int,
): Float {
    val sMs = word.startTime * 1000.0
    val dur = (word.endTime * 1000.0 - sMs).coerceAtLeast(MIN_WORD_ANIMATION_DURATION_MS.toDouble())
    val wProg = (position.toDouble() - sMs) / dur
    val cInW = glyphIndex.toDouble()
    val wLen = glyphCount.coerceAtLeast(1).toDouble()
    return ((wProg - cInW / wLen) * wLen).coerceIn(0.0, 1.0).toFloat()
}

private fun DrawScope.drawMaskGradient(
    mask: androidx.compose.ui.graphics.ImageBitmap,
    maskScale: Float,
    padPx: Float,
    brush: Brush,
    layerPaint: Paint,
) {
    val inv = 1f / maskScale
    val localW = mask.width * inv
    val localH = mask.height * inv
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(-padPx, -padPx, localW - padPx, localH - padPx), layerPaint)
    withTransform({
        scale(inv, inv, Offset.Zero)
        translate(-padPx * maskScale, -padPx * maskScale)
    }) {
        drawImage(
            image = mask,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(mask.width, mask.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(mask.width, mask.height),
            filterQuality = FilterQuality.Low,
        )
    }
    drawRect(
        brush = brush,
        topLeft = Offset(-padPx, -padPx),
        size = Size(localW, localH),
        blendMode = BlendMode.SrcIn,
    )
    canvas.restore()
}

private class ExpressiveClusterStatics(
    val visible: BooleanArray,
    val lineIdx: IntArray,
    val left: FloatArray,
    val top: FloatArray,
    val width: FloatArray,
    val height: FloatArray,
)

private class ExpressiveHyphenGroups(
    val hasGroup: BooleanArray,
    val pos: IntArray,
    val isLast: BooleanArray,
    val groupStartMs: LongArray,
    val groupEndMs: LongArray,
)

@Composable
internal fun ExpressiveWordAnimation(
    mainText: String,
    words: List<WordTimestamp>,
    isActiveLine: Boolean,
    positionProvider: () -> Long,
    adjustedEndTimeMs: Long?,
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
    prewarmMasks: Boolean = isActiveLine,
    useFixedTone: Boolean = true,
) {
    val useOgRtlEngine = remember(mainText) { mainText.containsRtl() || mainText.containsJoinedIndic() }
    if (useOgRtlEngine) {
        OgRtlExpressiveWordAnimation(
            mainText = mainText,
            words = words,
            isActiveLine = isActiveLine,
            positionProvider = positionProvider,
            adjustedEndTimeMs = adjustedEndTimeMs,
            lyricStyle = lyricStyle,
            lineColor = lineColor,
            expressiveAccent = expressiveAccent,
            focusedAlpha = focusedAlpha,
            alignment = alignment,
            textMeasurer = textMeasurer,
            isPlaying = isPlaying,
            romanizedWords = romanizedWords,
            romanizedText = romanizedText,
            showSyllableRomanization = showSyllableRomanization,
        )
        return
    }

    val latestPosition by rememberUpdatedState(positionProvider)
    val density = LocalDensity.current
    val fontSizePx = remember(lyricStyle, density) { with(density) { lyricStyle.fontSize.toPx() } }
    val gradientWidth = fontSizePx * 0.4f
    val extraPaddingPx = minOf(fontSizePx * 0.4f, with(density) { LYRICS_LINE_HORIZONTAL_PADDING.toPx() })

    var frameWallTimeMs by remember { mutableLongStateOf(0L) }

    val animationVisibility = rememberLyricsAnimationVisibility()
    LaunchedEffect(isActiveLine, isPlaying, animationVisibility) {
        if (isActiveLine) animateLyricsFrames(animationVisibility) { frameWallTimeMs = it }
    }

    val (effectiveWords, effectiveToOriginalIdx) =
        remember(words) {
            words
                .flatMapIndexed { originalIdx, word ->
                    val shouldSplit =
                        word.text.contains('-') && word.text.length > 1 &&
                            (!word.hasTrailingSpace || words.size == 1)
                    if (shouldSplit) {
                        val segments = mutableListOf<String>()
                        var start = 0
                        for (i in 0 until word.text.length) {
                            if (word.text[i] == '-') {
                                segments.add(word.text.substring(start, i + 1))
                                start = i + 1
                            }
                        }
                        if (start < word.text.length) {
                            segments.add(word.text.substring(start))
                        }

                        if (segments.size > 1) {
                            val totalDuration = word.endTime - word.startTime
                            val segmentDuration = totalDuration / segments.size
                            segments.mapIndexed { index, segmentText ->
                                WordTimestamp(
                                    text = segmentText,
                                    startTime = word.startTime + index * segmentDuration,
                                    endTime = word.startTime + (index + 1) * segmentDuration,
                                    hasTrailingSpace = if (index == segments.size - 1) word.hasTrailingSpace else false,
                                ) to originalIdx
                            }
                        } else {
                            listOf(word to originalIdx)
                        }
                    } else {
                        listOf(word to originalIdx)
                    }
                }.let { data -> data.map { it.first } to data.map { it.second }.toIntArray() }
        }

    val hyphenGroups =
        remember(effectiveWords) {
            val count = effectiveWords.size
            val hasGroup = BooleanArray(count)
            val posArr = IntArray(count)
            val isLastArr = BooleanArray(count)
            val startArr = LongArray(count)
            val endArr = LongArray(count)
            var currentGroup = mutableListOf<Int>()
            effectiveWords.forEachIndexed { wordIdx, word ->
                currentGroup.add(wordIdx)
                if (!word.text.endsWith("-")) {
                    if (currentGroup.size > 1) {
                        val groupSize = currentGroup.size
                        val groupStartMs = (effectiveWords[currentGroup.first()].startTime * 1000).toLong()
                        val groupEndMs = (word.endTime * 1000).toLong()
                        currentGroup.forEachIndexed { pos, idx ->
                            hasGroup[idx] = true
                            posArr[idx] = pos
                            isLastArr[idx] = pos == groupSize - 1
                            startArr[idx] = groupStartMs
                            endArr[idx] = groupEndMs
                        }
                    }
                    currentGroup = mutableListOf()
                }
            }
            ExpressiveHyphenGroups(hasGroup, posArr, isLastArr, startArr, endArr)
        }

    val effWordStartMs =
        remember(effectiveWords) {
            LongArray(effectiveWords.size) { (effectiveWords[it].startTime * 1000).toLong() }
        }
    val effWordEndMs =
        remember(effectiveWords) {
            LongArray(effectiveWords.size) { (effectiveWords[it].endTime * 1000).toLong() }
        }
    val effWordImpactBase =
        remember(effectiveWords) {
            FloatArray(effectiveWords.size) { wIdx ->
                val word = effectiveWords[wIdx]
                val dur = ((word.endTime - word.startTime) * 1000.0).toFloat()
                val wordLenText = word.text.length.coerceAtLeast(1)
                val impactRatio = dur / wordLenText
                (
                    ((impactRatio - 100f) / 250f).coerceIn(0f, 1f) * 0.6f +
                        ((dur - 300f) / 1500f).coerceIn(0f, 1f) * 0.4f
                ).coerceIn(0f, 1f)
            }
        }

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

        val hasSyllableRomanization = showSyllableRomanization && romanClusters.isNotEmpty()
        val lineExtraHeight =
            remember(hasSyllableRomanization, fontSizePx) {
                if (hasSyllableRomanization) (fontSizePx * 1.05f) else 0f
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

        val graphemeClusters = remember(formattedMainText) { formattedMainText.toLyricsGraphemeClusters() }
        val clusterCount = graphemeClusters.size
        val clusterCharOffsets =
            remember(formattedMainText, graphemeClusters) {
                IntArray(clusterCount).also { offsets ->
                    var charOffset = 0
                    graphemeClusters.forEachIndexed { i, cluster ->
                        offsets[i] = charOffset
                        charOffset += cluster.length
                    }
                }
            }

        val charToWordData =
            remember(formattedMainText, effectiveWords, graphemeClusters, clusterCharOffsets) {
                val wordIdxMap = IntArray(clusterCount) { -1 }
                val charInWordMap = IntArray(clusterCount)
                val wordLenMap = IntArray(clusterCount) { 1 }
                var currentPos = 0
                var clCursor = 0
                effectiveWords.forEachIndexed { wordIdx, word ->
                    val rawWordText =
                        word.text
                            .stripBidiControls()
                            .replace('\u00A0', ' ')
                            .trimEnd(' ')
                    if (rawWordText.isEmpty()) return@forEachIndexed
                    var indexInMain = formattedMainText.indexOf(rawWordText, currentPos, ignoreCase = true)
                    if (indexInMain == -1 && currentPos < formattedMainText.length) {
                        val trimmed = rawWordText.trim()
                        if (trimmed.isNotEmpty()) {
                            indexInMain = formattedMainText.indexOf(trimmed, currentPos, ignoreCase = true)
                        }
                    }
                    if (indexInMain != -1) {
                        val matchedLen =
                            if (indexInMain + rawWordText.length <= formattedMainText.length &&
                                formattedMainText.regionMatches(indexInMain, rawWordText, 0, rawWordText.length, ignoreCase = true)
                            ) {
                                rawWordText.length
                            } else {
                                rawWordText.trim().length
                            }
                        val wordEndInMain = (indexInMain + matchedLen).coerceAtMost(formattedMainText.length)
                        while (clCursor < clusterCount && clusterCharOffsets[clCursor] < indexInMain) {
                            clCursor++
                        }
                        val wordClusterIndices = mutableListOf<Int>()
                        while (clCursor < clusterCount && clusterCharOffsets[clCursor] < wordEndInMain) {
                            wordClusterIndices.add(clCursor)
                            clCursor++
                        }
                        val wordClusterLen = wordClusterIndices.size.coerceAtLeast(1)
                        wordClusterIndices.forEachIndexed { posInWord, clIdx ->
                            wordIdxMap[clIdx] = wordIdx
                            charInWordMap[clIdx] = posInWord
                            wordLenMap[clIdx] = wordClusterLen
                        }
                        if (clCursor < clusterCount && clusterCharOffsets[clCursor] == wordEndInMain &&
                            wordEndInMain < formattedMainText.length &&
                            (formattedMainText[wordEndInMain] == ' ' || formattedMainText[wordEndInMain] == '\n')
                        ) {
                            val spaceClIdx = clCursor
                            wordIdxMap[spaceClIdx] = wordIdx
                            charInWordMap[spaceClIdx] = wordClusterLen
                            wordLenMap[spaceClIdx] = wordClusterLen + 1
                            clCursor++
                        }
                        currentPos = wordEndInMain
                    }
                }
                Triple(wordIdxMap, charInWordMap, wordLenMap)
            }

        val letterStyle =
            remember(lyricStyle) {
                lyricStyle.copy(textAlign = TextAlign.Start)
            }
        val letterLayouts =
            remember(graphemeClusters, letterStyle, textMeasurer) {
                graphemeClusters.map { cluster ->
                    if (cluster == "\n") {
                        textMeasurer.measure("", letterStyle)
                    } else {
                        textMeasurer.measure(cluster, letterStyle)
                    }
                }
            }

        val clusterStatics =
            remember(
                textLayoutResult,
                letterLayouts,
                lineBaselines,
                graphemeClusters,
                clusterCharOffsets,
                charToWordData,
                effectiveToOriginalIdx,
                romanStartShift,
                lineExtraHeight,
            ) {
                val visible = BooleanArray(clusterCount)
                val lineIdxArr = IntArray(clusterCount)
                val leftArr = FloatArray(clusterCount)
                val topArr = FloatArray(clusterCount)
                val widthArr = FloatArray(clusterCount)
                val heightArr = FloatArray(clusterCount)
                val visibleLimit =
                    if (textLayoutResult.lineCount > 0) {
                        textLayoutResult.getLineEnd(textLayoutResult.lineCount - 1, visibleEnd = true)
                    } else {
                        0
                    }
                val (wordIdxMap, _, _) = charToWordData
                for (i in 0 until clusterCount) {
                    val cluster = graphemeClusters[i]
                    val charOffset = clusterCharOffsets[i]
                    if (cluster == "\n" || charOffset >= visibleLimit) {
                        visible[i] = false
                        continue
                    }
                    visible[i] = true
                    val li = textLayoutResult.getLineForOffset(charOffset)
                    lineIdxArr[i] = li
                    val bounds = textLayoutResult.getBoundingBox(charOffset)
                    val wIdx = wordIdxMap[i]
                    val origIdx = if (wIdx != -1) effectiveToOriginalIdx[wIdx] else -1
                    val gapShift = calculateGapShift(origIdx, romanStartShift)
                    leftArr[i] = bounds.left + gapShift
                    topArr[i] = lineBaselines[li] - letterLayouts[i].getLineBaseline(0) + li * lineExtraHeight
                    widthArr[i] = bounds.width
                    heightArr[i] = bounds.height
                }
                ExpressiveClusterStatics(visible, lineIdxArr, leftArr, topArr, widthArr, heightArr)
            }

        // Android combines text shadows with paint shaders, causing ghost glyphs; draw a separate Alpha8 mask.
        val glowBlurPx = remember(fontSizePx, extraPaddingPx) { minOf(fontSizePx * 0.1f, extraPaddingPx * 0.5f).coerceAtLeast(1f) }
        val glowMaskCache = remember(letterLayouts, extraPaddingPx) { ClusterMaskCache(clusterCount, extraPaddingPx) }
        val glowCapable =
            remember(clusterStatics, charToWordData, effWordImpactBase, graphemeClusters) {
                val (wordIdxMapForGlow, _, _) = charToWordData
                BooleanArray(clusterCount) { i ->
                    val w = wordIdxMapForGlow[i]
                    clusterStatics.visible[i] && w != -1 && effWordImpactBase[w] > 0.01f && graphemeClusters[i].isNotBlank()
                }
            }
        val glowTintCache = remember { TintFilterCache() }
        LaunchedEffect(glowMaskCache, glowCapable, clusterStatics, prewarmMasks) {
            if (!prewarmMasks) return@LaunchedEffect
            var mark = TimeSource.Monotonic.markNow()
            for (i in 0 until clusterCount) {
                if (!clusterStatics.visible[i] || graphemeClusters[i].isBlank()) continue
                glowMaskCache.obtain(i, letterLayouts[i], density)
                if (mark.elapsedNow() >= PREWARM_FRAME_BUDGET) {
                    withFrameNanos { }
                    mark = TimeSource.Monotonic.markNow()
                }
                if (glowCapable[i] && !glowMaskCache.hasGlow(i)) {
                    glowMaskCache.obtainGlow(i, letterLayouts[i], glowBlurPx, density)
                    if (mark.elapsedNow() >= PREWARM_FRAME_BUDGET) {
                        withFrameNanos { }
                        mark = TimeSource.Monotonic.markNow()
                    }
                }
            }
        }

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

        val sylBounds =
            remember(alignedSyllables, charXMap, textLayoutResult, formattedMainText) {
                buildSyllableBounds(alignedSyllables, charXMap, textLayoutResult, formattedMainText)
            }

        val wordSungFactors = remember(effectiveWords.size) { FloatArray(effectiveWords.size) }
        val wordIsSung = remember(effectiveWords.size) { BooleanArray(effectiveWords.size) }
        val wordIsActive = remember(effectiveWords.size) { BooleanArray(effectiveWords.size) }
        val wordBaseAlphas = remember(effectiveWords.size) { FloatArray(effectiveWords.size) }
        val wordBaseBrushes = remember(effectiveWords.size) { arrayOfNulls<SolidColor>(effectiveWords.size) }
        val wordGlowAlphas = remember(effectiveWords.size) { FloatArray(effectiveWords.size) }
        val wordWobbles = remember(words.size) { FloatArray(words.size) }
        val lineCurrentPushes = remember(lineCount) { FloatArray(lineCount) }
        val lineTotalPushes = remember(lineCount) { FloatArray(lineCount) }
        val clusterScaleX = remember(clusterCount) { FloatArray(clusterCount) { 1f } }
        val clusterScaleY = remember(clusterCount) { FloatArray(clusterCount) { 1f } }
        val clusterCharLp = remember(clusterCount) { FloatArray(clusterCount) }

        val litColorCache = remember { ColorAlphaCache() }
        val unlitColorCache = remember { ColorAlphaCache() }
        val pastBrushCache = remember { SolidColorCache() }
        val unlitBrushCache = remember { SolidColorCache() }

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
                        compositingStrategy =
                            if (isActiveLine) {
                                CompositingStrategy.Offscreen
                            } else {
                                CompositingStrategy.Auto
                            }
                    },
        ) {
            if (formattedMainText.isEmpty()) return@Canvas

            val padOffset = Offset(extraPaddingPx, extraPaddingPx)
            val unfocusedAlphaVal = focusedAlpha()
            val litColor = litColorCache.get(expressiveAccent, 1f)
            val unlitColor = unlitColorCache.get(expressiveAccent, unfocusedAlphaVal)
            val pastBrush = pastBrushCache.get(litColor)
            val unlitBrush = unlitBrushCache.get(unlitColor)

            if (!isActiveLine) {
                val inactiveLineColor = lineColor()
                if (!hasSyllableRomanization) {
                    drawText(
                        textLayoutResult = textLayoutResult,
                        topLeft = padOffset,
                        color = inactiveLineColor,
                        shadow = LyricsClearShadow,
                    )
                } else {
                    val inactiveBrush = unlitBrushCache.get(inactiveLineColor)
                    for (i in 0 until clusterCount) {
                        if (!clusterStatics.visible[i]) continue
                        drawText(
                            textLayoutResult = letterLayouts[i],
                            topLeft = Offset(clusterStatics.left[i] + extraPaddingPx, clusterStatics.top[i] + extraPaddingPx),
                            brush = inactiveBrush,
                            shadow = LyricsClearShadow,
                        )
                    }
                    if (romanLines.isNotEmpty()) {
                        drawRomanizedWordClusters(
                            romanStatics = romanStatics,
                            isActiveLine = false,
                            activeSylIdx = -1,
                            activeLineIdx = -1,
                            curGX = 0f,
                            isActiveSylLastOfLine = false,
                            activeSylProgress = 0f,
                            gradientWidth = gradientWidth,
                            pastBrush = pastBrush,
                            unlitBrush = unlitBrush,
                            litColor = litColor,
                            unlitColor = unlitColor,
                            lineColor = inactiveLineColor,
                            curSimpleState = 0f,
                            expressiveAccent = expressiveAccent,
                        )
                    }
                }
                return@Canvas
            }

            val smoothPosition = latestPosition()
            val wallTimeMs = frameWallTimeMs

            val effectiveCount = effectiveWords.size
            for (wIdx in 0 until effectiveCount) {
                val wStartMs = effWordStartMs[wIdx]
                val wEndMs = effWordEndMs[wIdx]
                val animationEndMs = wStartMs + lyricsWordAnimationDurationMs(wEndMs - wStartMs)
                val isSung = smoothPosition > animationEndMs
                val isAct = smoothPosition in wStartMs..animationEndMs
                val factor =
                    if (isSung) {
                        1f
                    } else if (isAct) {
                        lyricsWordAnimationProgress(smoothPosition, wStartMs, animationEndMs)
                    } else {
                        0f
                    }
                wordSungFactors[wIdx] = factor
                wordIsSung[wIdx] = isSung
                wordIsActive[wIdx] = isAct

                val baseAlpha = if (isSung) 1f else (unfocusedAlphaVal + (1f - unfocusedAlphaVal) * factor)
                wordBaseAlphas[wIdx] = baseAlpha
                wordBaseBrushes[wIdx] =
                    when {
                        isSung || baseAlpha >= 0.999f -> pastBrush
                        factor <= 0f -> unlitBrush
                        else -> SolidColor(expressiveAccent.copy(alpha = baseAlpha))
                    }

                if (!isSung && factor > 0.001f) {
                    val fadeFactor = (factor * 5f).coerceIn(0f, 1f) * ((1f - factor) * 8f).coerceIn(0f, 1f)
                    val impactFactor = effWordImpactBase[wIdx] * fadeFactor
                    wordGlowAlphas[wIdx] = if (impactFactor > 0.01f) (impactFactor * 0.6f).coerceIn(0f, 0.6f) else 0f
                } else {
                    wordGlowAlphas[wIdx] = 0f
                }
            }

            val (wordIdxMap, charInWordMap, wordLenMap) = charToWordData
            for (wordIdx in words.indices) {
                wordWobbles[wordIdx] = experimentalWordWobble(words[wordIdx], smoothPosition)
            }

            lineCurrentPushes.fill(0f)
            lineTotalPushes.fill(0f)

            for (i in 0 until clusterCount) {
                if (!clusterStatics.visible[i]) continue
                val lineIdx = clusterStatics.lineIdx[i]
                val wordIdx = wordIdxMap[i]
                val originalWordIdx = if (wordIdx != -1) effectiveToOriginalIdx[wordIdx] else -1

                val sungFactor = if (wordIdx != -1) wordSungFactors[wordIdx] else 0f
                val isWordSung = if (wordIdx != -1) wordIsSung[wordIdx] else false
                val wobble = if (originalWordIdx != -1) wordWobbles[originalWordIdx] else 0f

                var crescendoDeltaXPre = 0f
                var crescendoDeltaX = 0f
                var crescendoDeltaY = 0f
                if (wordIdx != -1 && hyphenGroups.hasGroup[wordIdx]) {
                    val p = sungFactor
                    val timeSinceEnd = (smoothPosition - hyphenGroups.groupEndMs[wordIdx]).toFloat()
                    val exitDuration = 600f
                    val pOut = (timeSinceEnd / exitDuration).coerceIn(0f, 1f)
                    val peakScale = 0.06f
                    val baseScalePerSegment = 0.012f
                    val gPos = hyphenGroups.pos[wordIdx]
                    if (pOut > 0f) {
                        val baseAtEnd = gPos * baseScalePerSegment
                        val totalAtEnd = baseAtEnd + peakScale
                        crescendoDeltaXPre = totalAtEnd * exp(-2.5f * pOut) * cos(10.0f * pOut * PI.toFloat()) * (1f - pOut)
                        val springOut = totalAtEnd * exp(-3.5f * pOut) * cos(5.0f * pOut * PI.toFloat()) * (1f - pOut)
                        crescendoDeltaX = springOut
                        crescendoDeltaY = springOut
                    } else if (hyphenGroups.isLast[wordIdx]) {
                        val base = gPos * baseScalePerSegment
                        val springPartPre = peakScale * (1f - exp(-2.5f * p) * cos(10.0f * p * PI.toFloat()) * (1f - p))
                        crescendoDeltaXPre = base + springPartPre
                        val springPart = peakScale * (1f - exp(-3.5f * p) * cos(5.0f * p * PI.toFloat()) * (1f - p))
                        crescendoDeltaX = base + springPart
                        crescendoDeltaY = base + springPart
                    } else {
                        val boost = if (p > 0f) 0.02f * (1f - p) else 0f
                        val base = (gPos * baseScalePerSegment) + boost
                        crescendoDeltaXPre = base
                        crescendoDeltaX = base
                        crescendoDeltaY = base
                    }
                }

                val charLp =
                    if (wordIdx != -1) {
                        experimentalGlyphProgress(
                            effectiveWords[wordIdx],
                            smoothPosition,
                            charInWordMap[i],
                            wordLenMap[i],
                        )
                    } else {
                        0f
                    }
                clusterCharLp[i] = charLp

                val nudgeScale =
                    if (wordIdx != -1 && !isWordSung && sungFactor > 0f) {
                        0.038f * sin(charLp * PI.toFloat()) * exp(-3f * charLp)
                    } else {
                        0f
                    }

                val preScaleX = 1f + (wobble * 0.025f) + crescendoDeltaXPre + (nudgeScale * 0.3f)
                val scaleX = 1f + (wobble * 0.025f) + crescendoDeltaX + (nudgeScale * 0.3f)
                val scaleY = 1f + (wobble * 0.015f) + crescendoDeltaY + nudgeScale
                clusterScaleX[i] = scaleX
                clusterScaleY[i] = scaleY
                lineTotalPushes[lineIdx] += clusterStatics.width[i] * (preScaleX - 1f)
            }

            for (i in 0 until clusterCount) {
                if (!clusterStatics.visible[i]) continue
                val lineIdx = clusterStatics.lineIdx[i]
                val cLeft = clusterStatics.left[i]
                val cTop = clusterStatics.top[i]
                val cWidth = clusterStatics.width[i]
                val cHeight = clusterStatics.height[i]
                val wordIdx = wordIdxMap[i]

                val alignShift =
                    when (alignment) {
                        TextAlign.Center -> -lineTotalPushes[lineIdx] / 2f
                        TextAlign.Right, TextAlign.End -> -lineTotalPushes[lineIdx]
                        else -> 0f
                    }

                val isWordSung = if (wordIdx != -1) wordIsSung[wordIdx] else false
                val charLp = clusterCharLp[i]
                val charScaleX = clusterScaleX[i]
                val charScaleY = clusterScaleY[i]

                var waveOffset = 0f
                if (wordIdx != -1 && hyphenGroups.hasGroup[wordIdx]) {
                    val gStart = hyphenGroups.groupStartMs[wordIdx]
                    val gEnd = hyphenGroups.groupEndMs[wordIdx]
                    val timeInGroup = (smoothPosition - gStart).toFloat()
                    val timeToGroupEnd = (gEnd - smoothPosition).toFloat()
                    val waveFade = (timeInGroup / 200f).coerceIn(0f, 1f) * (timeToGroupEnd / 200f).coerceIn(0f, 1f)
                    if (waveFade > 0.01f) {
                        val waveSpeed = 0.006f
                        val waveHeight = 3.24f
                        val phaseOffset = i * 0.4f
                        waveOffset = sin(wallTimeMs * waveSpeed + phaseOffset) * waveHeight * waveFade
                    }
                }

                val drawX = alignShift + lineCurrentPushes[lineIdx] + cLeft + extraPaddingPx
                val drawY = cTop + waveOffset + extraPaddingPx

                withTransform({
                    translate(left = drawX, top = drawY)
                    if (wordIdx != -1 && (charScaleX != 1f || charScaleY != 1f)) {
                        scale(
                            charScaleX,
                            charScaleY,
                            pivot = Offset(cWidth / 2f, cHeight),
                        )
                    }
                }) {
                    val glowAlpha = if (wordIdx != -1) wordGlowAlphas[wordIdx] else 0f
                    if (glowAlpha > 0.01f) {
                        val glowMask = glowMaskCache.peekGlow(i)
                        if (glowMask != null) {
                            drawMaskTinted(
                                mask = glowMask,
                                maskScale = glowMaskCache.glowScaleOf(i),
                                padPx = glowMaskCache.padPx,
                                filter = glowTintCache.get(expressiveAccent),
                                alpha = glowAlpha,
                            )
                        }
                    }
                    val glyphBrush: Brush =
                        when {
                            wordIdx == -1 -> {
                                unlitBrush
                            }

                            isWordSung || charLp > 0.99f -> {
                                pastBrush
                            }

                            charLp <= 0f -> {
                                wordBaseBrushes[wordIdx] ?: unlitBrush
                            }

                            else -> {
                                val fXL = cWidth * charLp
                                val eW = (cWidth * 0.45f).coerceAtLeast(1f)
                                val sWL = (fXL - eW).coerceAtLeast(0f)
                                val baseColor = expressiveAccent.copy(alpha = wordBaseAlphas[wordIdx])
                                val edgeProgress = ((fXL - sWL) / eW).coerceIn(0f, 1f)
                                val edgeColor = lerp(litColor, baseColor, edgeProgress)
                                Brush.linearGradient(
                                    0.0f to litColor,
                                    0.98f to edgeColor,
                                    1.0f to baseColor,
                                    start = Offset(sWL, 0f),
                                    end = Offset((fXL + 0.5f).coerceAtLeast(sWL + 0.5f), 0f),
                                )
                            }
                        }
                    val mask = glowMaskCache.peek(i)
                    if (mask != null) {
                        val ms = glowMaskCache.scaleOf(i)
                        val pad = glowMaskCache.padPx
                        when {
                            glyphBrush === pastBrush -> {
                                drawMaskTinted(mask, ms, pad, glowMaskCache.pastTint.get(litColor))
                            }

                            glyphBrush === unlitBrush -> {
                                drawMaskTinted(mask, ms, pad, glowMaskCache.unlitTint.get(unlitColor))
                            }

                            glyphBrush is SolidColor -> {
                                drawMaskTinted(mask, ms, pad, ColorFilter.tint(glyphBrush.value))
                            }

                            else -> {
                                drawMaskGradient(mask, ms, pad, glyphBrush, glowMaskCache.layerPaint)
                            }
                        }
                    } else {
                        drawText(
                            textLayoutResult = letterLayouts[i],
                            brush = glyphBrush,
                            shadow = LyricsClearShadow,
                        )
                    }
                }

                lineCurrentPushes[lineIdx] += cWidth * (charScaleX - 1f)
            }

            if (romanLines.isNotEmpty()) {
                var activeSylIdx = -1
                var curGX = 0f
                var activeSylProgress = 0f
                for (sIdx in alignedSyllables.indices) {
                    val s = alignedSyllables[sIdx].syllable
                    val ss = (s.startTime * 1000).toLong()
                    val se = (s.endTime * 1000).toLong()
                    if (smoothPosition >= ss) {
                        activeSylIdx = sIdx
                        val dur = (se - ss).coerceAtLeast(1L)
                        activeSylProgress = ((smoothPosition - ss).coerceIn(0L, dur)).toFloat() / dur.toFloat()
                        val sgStart = sylBounds.startX[sIdx]
                        val sgEnd = sylBounds.endX[sIdx]
                        curGX = sgStart + (sgEnd - sgStart) * activeSylProgress
                    } else {
                        break
                    }
                }
                val activeLineIdx =
                    if (activeSylIdx != -1) {
                        textLayoutResult.getLineForOffset(
                            alignedSyllables[activeSylIdx].startOffset.coerceIn(
                                0,
                                maxOf(0, formattedMainText.length - 1),
                            ),
                        )
                    } else {
                        -1
                    }
                val isLastOfLine =
                    if (activeSylIdx != -1 && activeLineIdx != -1) {
                        if (activeSylIdx == alignedSyllables.lastIndex) {
                            true
                        } else {
                            val nextLineIdx =
                                textLayoutResult.getLineForOffset(
                                    alignedSyllables[activeSylIdx + 1].startOffset.coerceIn(
                                        0,
                                        maxOf(0, formattedMainText.length - 1),
                                    ),
                                )
                            nextLineIdx > activeLineIdx
                        }
                    } else {
                        false
                    }
                drawRomanizedWordClusters(
                    romanStatics = romanStatics,
                    isActiveLine = true,
                    activeSylIdx = activeSylIdx,
                    activeLineIdx = activeLineIdx,
                    curGX = curGX,
                    isActiveSylLastOfLine = isLastOfLine,
                    activeSylProgress = activeSylProgress,
                    gradientWidth = gradientWidth,
                    pastBrush = pastBrush,
                    unlitBrush = unlitBrush,
                    litColor = litColor,
                    unlitColor = unlitColor,
                    lineColor = Color.Unspecified,
                    curSimpleState = 1f,
                    expressiveAccent = expressiveAccent,
                )
            }
        }
    }
}

@Composable
private fun OgRtlExpressiveWordAnimation(
    mainText: String,
    words: List<WordTimestamp>,
    isActiveLine: Boolean,
    positionProvider: () -> Long,
    adjustedEndTimeMs: Long?,
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
    val gradientWidth = fontSizePx * 0.4f
    val extraPaddingPx = minOf(fontSizePx * 0.4f, with(density) { LYRICS_LINE_HORIZONTAL_PADDING.toPx() })

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
            return charXMap[offset.coerceIn(0, charXMap.size - 1)]
        }

        val sylBounds =
            remember(alignedSyllables, charXMap, textLayoutResult, formattedMainText) {
                buildSyllableBounds(alignedSyllables, charXMap, textLayoutResult, formattedMainText)
            }

        val clusters =
            remember(
                formattedMainText,
                textLayoutResult,
                reconstructedWords,
                alignedSyllables,
                lyricStyle,
            ) {
                val metaList = buildInitialClusterMetas(formattedMainText, textLayoutResult, lineIsRtl)

                for ((wordIdx, word) in reconstructedWords.withIndex()) {
                    val wordText = word.syllables.joinToString("") { it.syllable.text }
                    val isWordJoining = isJoiningScript(wordText)
                    for (syl in word.syllables) {
                        val sylText = syl.syllable.text
                        if (sylText.isEmpty()) continue

                        val isJoining = isWordJoining || isJoiningScript(sylText)
                        val sylStartMs = (syl.syllable.startTime * 1000).toLong()
                        val sylEndMs = (syl.syllable.endTime * 1000).toLong()

                        for (m in metasInRange(metaList, syl.startOffset, syl.endOffset)) {
                            val isWhitespace = formattedMainText[m.start].isWhitespace()
                            val joiningMeta = !isWhitespace && isJoining
                            m.isHeavy = true
                            m.isJoiningScript = joiningMeta
                            m.wordIndex = if (!isWhitespace) wordIdx else -1
                            m.wordStartMs = if (joiningMeta) word.wordStartTimeMs else sylStartMs
                            m.wordEndMs = if (joiningMeta) word.wordEndTimeMs else sylEndMs
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

        val lineStartTime = remember(words) { words.firstOrNull()?.let { (it.startTime * 1000).toLong() } ?: 0L }
        val lineEndTime = remember(words) { words.lastOrNull()?.let { (it.endTime * 1000).toLong() } ?: 0L }
        val effectiveEndTimeMs = adjustedEndTimeMs ?: lineEndTime

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

        val finishing = { !isActiveLine && reachedLineEnd && lastSylIdx >= 0 && curGX in 1f..(lineEndX - 0.5f) }

        LaunchLineColorAnimations(
            animState = animState,
            isActiveLine = isActiveLine,
            lineStartTime = lineStartTime,
            effectiveEndTimeMs = effectiveEndTimeMs,
            currentPositionProvider = { currentPositionProvider() },
            isFinishingProvider = finishing,
        )

        LaunchedEffect(Unit) { animState.isFirstRun = false }
        val curColorState by animState.colorState.asState()
        val curSimpleState by animState.simpleState.asState()

        val litColorCache = remember { ColorAlphaCache() }
        val unlitColorCache = remember { ColorAlphaCache() }
        val pastBrushCache = remember { SolidColorCache() }
        val unlitBrushCache = remember { SolidColorCache() }
        val activeUnlitBrushCache = remember { SolidColorCache() }
        val activeUnlitColorCache = remember { ColorAlphaCache() }
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
                    computePivotY = {
                        it.subLayout.size.height
                            .toFloat()
                    },
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
            val showActiveWipe = isActiveLine || isFinishing
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
            val pastColor = if (curColorState <= 0f) litColor else lerp(litColor, unlitColor, curColorState)
            val lineColorValue = if (showActiveWipe) Color.Unspecified else lineColor()
            val inactiveBrush =
                if (!showActiveWipe) {
                    SolidColor(lerp(lineColorValue, expressiveAccent, curSimpleState.coerceIn(0f, 1f)))
                } else {
                    null
                }

            val pastBrush = pastBrushCache.get(pastColor)
            val unlitBrush = unlitBrushCache.get(unlitColor)
            val startAlpha = focusedAlpha()
            val fadeAlpha = (startAlpha + (1f - startAlpha) * activeSylProgress.coerceIn(0f, 1f)).coerceIn(startAlpha, 1f)
            val activeUnlitColor =
                if (isActiveLine && wipeSylIdx != -1) {
                    activeUnlitColorCache.get(expressiveAccent, fadeAlpha)
                } else {
                    unlitColor
                }
            val activeUnlitBrush = activeUnlitBrushCache.get(activeUnlitColor)

            val n = clusters.size
            for (idx in 0 until n) {
                val vc = clusters[idx]
                val isRtlLine = statics.isRtlLine[idx]
                val lgs = statics.lineStart[idx]
                val lw = statics.lineWidth[idx]
                val topLeft = Offset(statics.baseX[idx] + extraPaddingPx, statics.baseY[idx] + extraPaddingPx)

                val lastSylIdxOfCluster = if (vc.lastSyllableIndex != -1) vc.lastSyllableIndex else vc.syllableIndex
                val isPastSylCluster =
                    (wipeLineIdx != -1 && vc.lineIdx < wipeLineIdx) ||
                        (isActiveLine && wipeSylIdx != -1 && lastSylIdxOfCluster != -1 && lastSylIdxOfCluster < wipeSylIdx)
                val isActiveSylCluster = isActiveLine && wipeSylIdx != -1 && lastSylIdxOfCluster == wipeSylIdx
                val clusterUnlitBrush =
                    when {
                        isPastSylCluster -> pastBrush
                        isActiveSylCluster -> activeUnlitBrush
                        else -> unlitBrush
                    }
                val clusterUnlitColor =
                    when {
                        isPastSylCluster -> pastColor
                        isActiveSylCluster -> activeUnlitColor
                        else -> unlitColor
                    }

                if (inactiveBrush != null) {
                    drawClusterText(
                        vc = vc,
                        topLeft = topLeft,
                        brush = inactiveBrush,
                        shadow = LyricsClearShadow,
                    )
                } else {
                    drawWipeText(
                        vc = vc,
                        topLeft = topLeft,
                        isRtlLine = isRtlLine,
                        lineStart = lgs,
                        lineWidth = lw,
                        localStart = statics.localStart[idx],
                        localEnd = statics.localEnd[idx],
                        activeSylIdx = wipeSylIdx,
                        activeLineIdx = wipeLineIdx,
                        curGX = curGX,
                        isActiveSylLastOfLine = isActiveSylLastOfLine,
                        activeSylProgress = activeSylProgress,
                        gradientWidth = gradientWidth,
                        pastBrush = pastBrush,
                        unlitBrush = clusterUnlitBrush,
                        litColor = litColor,
                        unlitColor = clusterUnlitColor,
                        wipe = wipeScratch,
                        shadow = LyricsClearShadow,
                    )
                }
            }

            if (romanLines.isNotEmpty()) {
                drawRomanizedWordClusters(
                    romanStatics = romanStatics,
                    isActiveLine = showActiveWipe,
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
                    lineColor = lineColorValue,
                    curSimpleState = curSimpleState,
                    expressiveAccent = expressiveAccent,
                )
            }
        }
    }
}
