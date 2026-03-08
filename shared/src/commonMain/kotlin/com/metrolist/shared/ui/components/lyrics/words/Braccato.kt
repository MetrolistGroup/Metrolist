/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Word animation are replicated and ported from Braccato (https://github.com/better-lyrics/braccato, MIT).
 * behavior may not 1:1, but atleast it work
 */

package com.metrolist.shared.ui.components.lyrics.words

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
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
import androidx.compose.ui.unit.dp
import com.metrolist.shared.model.WordTimestamp
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.LYRICS_LINE_HORIZONTAL_PADDING
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.time.TimeSource

private val BraccatoEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private val BraccatoEaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val BraccatoEaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

private val GlowFillBrush = SolidColor(Color.White.copy(alpha = 0f))

private const val SWIPE_LEAD_RATIO = 0.1f
private const val SWIPE_DURATION_RATIO = 1.6f
private const val SWIPE_START_FROM = -0.2f
private const val SWIPE_END_FROM = -0.1f
private const val SWIPE_START_TO = 1.4f
private const val SWIPE_END_TO = 1.5f
private const val SWIPE_EDGE = 0.1f

private const val GLOW_RADIUS_REM = 0.8f
private const val GLOW_DURATION_RATIO = 1.2f
private const val GLOW_MIN_MS = 1200f
private const val GLOW_COLOR_ALPHA = 0.5f

private const val FADE_IN_MS = 330f
private const val FADE_OUT_MS = 500f

private const val WOBBLE_MS = 1000f
private const val WOBBLE_PEAK_OFFSET = 0.125f
private const val WOBBLE_SETTLE_OFFSET = 0.75f
private const val WOBBLE_TRANSLATE_EM = 0.05f
private const val WOBBLE_SCALE_X = 1.025f

private const val WOBBLE_MODE = 1
private const val WOBBLE_LIFT_PEAK_EM = -0.005f
private const val WOBBLE_LIFT_SETTLE_EM = -0.028f
private const val WOBBLE_LIFT_TO_EM = -0.0375f

// Skia/Android turn a text Shadow's blurRadius into a Gaussian sigma differently from CSS (sigma = radius / 2).
private const val GLOW_BLUR_SCALE = 0.85f

private const val FINISH_MS = 180f
private const val MAX_EXIT_MS = 15_000L

private const val WAVE_MS = 900f
private const val WAVE_CREST = 0.4f
private const val WAVE_PEAK_EM = -0.05f
private const val WAVE_SETTLE_EM = -0.02f
private const val WAVE_EMPHASIS_SCALE = 1.11f

private const val LONG_WORD_MS = 1500f
private const val LONG_GROUP_CHARS = 20

private const val KIND_NONE = 0
private const val KIND_LETTER = 1
private const val KIND_WORD = 2

private fun isMixedDirection(text: String): Boolean = isRtlText(text) && text.any { it.isLetterOrDigit() && !isRtlText(it.toString()) }

private class BraccatoStatics(
    n: Int,
) {
    val kind = IntArray(n)
    val startMs = FloatArray(n)
    val durMs = FloatArray(n)
    val letterIdx = IntArray(n)
    val letterCount = IntArray(n) { 1 }
    val swipeDur = FloatArray(n)
    val lead = FloatArray(n)
    val begin = FloatArray(n)
    val finish = FloatArray(n)
    val waveDelay = FloatArray(n)
    val waveDur = FloatArray(n) { WAVE_MS }
    val emphasise = BooleanArray(n)
    val longWord = BooleanArray(n)
    var lineStartMs = Long.MAX_VALUE
    var lineEndMs = 0L
    var fxEndMs = 0L
    val lineSynced = BooleanArray(n)
    val glowDur = FloatArray(n)
    val groupStartMs = FloatArray(n)
    val groupWobble = BooleanArray(n)
    val groupCx = FloatArray(n)
}

private fun buildBraccatoStatics(
    clusters: List<VisualCluster>,
    alignedSyllables: List<AlignedSyllable>,
    reconstructedWords: List<ReconstructedWord>,
    statics: ClusterStatics,
    formattedMainText: String,
): BraccatoStatics {
    val n = clusters.size
    val out = BraccatoStatics(n)

    val sylIdxByStart = HashMap<Int, Int>()
    alignedSyllables.forEachIndexed { i, s -> sylIdxByStart[s.startOffset] = i }
    val sylGroup = IntArray(alignedSyllables.size) { -1 }
    reconstructedWords.forEachIndexed { gi, w ->
        for (s in w.syllables) sylIdxByStart[s.startOffset]?.let { sylGroup[it] = gi }
    }
    val groupLong =
        BooleanArray(reconstructedWords.size) { gi ->
            val w = reconstructedWords[gi]
            val s = w.startIndex.coerceIn(0, formattedMainText.length)
            val e = w.endIndex.coerceIn(s, formattedMainText.length)
            formattedMainText.substring(s, e).trim().length > LONG_GROUP_CHARS
        }

    val countBySyl = IntArray(alignedSyllables.size)
    for (i in 0 until n) {
        val vc = clusters[i]
        val s = vc.syllableIndex
        val text = formattedMainText.substring(vc.start.coerceIn(0, formattedMainText.length), vc.end.coerceIn(0, formattedMainText.length))
        if (s < 0 || s >= alignedSyllables.size || text.isBlank()) {
            out.kind[i] = KIND_NONE
            continue
        }
        if (vc.isHeavy && vc.isJoiningScript) {
            out.kind[i] = KIND_WORD
        } else {
            out.kind[i] = KIND_LETTER
            countBySyl[s]++
        }
    }

    val runningBySyl = IntArray(alignedSyllables.size)
    for (i in 0 until n) {
        val kind = out.kind[i]
        if (kind == KIND_NONE) continue
        val vc = clusters[i]
        val first = alignedSyllables[vc.syllableIndex].syllable
        val lastIdx = if (vc.lastSyllableIndex in vc.syllableIndex..alignedSyllables.lastIndex) vc.lastSyllableIndex else vc.syllableIndex
        val last = alignedSyllables[lastIdx].syllable
        val startMs = round(first.startTime * 1000.0).toFloat()
        val endMs = if (kind == KIND_WORD) round(last.endTime * 1000.0).toFloat() else round(first.endTime * 1000.0).toFloat()
        val rawDur = endMs - startMs
        val timedDur = max(0f, rawDur)
        val animationDur =
            if (timedDur > 0f) {
                lyricsWordAnimationDurationMs(timedDur.toLong()).toFloat()
            } else {
                timedDur
            }
        out.startMs[i] = startMs
        out.durMs[i] = timedDur
        out.lineSynced[i] = rawDur == 0f
        out.longWord[i] = timedDur > LONG_WORD_MS
        out.emphasise[i] = kind == KIND_LETTER && out.longWord[i]
        out.lineStartMs = min(out.lineStartMs, startMs.toLong())
        out.lineEndMs = max(out.lineEndMs, max(startMs, endMs).toLong())
        out.swipeDur[i] = animationDur * SWIPE_DURATION_RATIO
        out.lead[i] = animationDur * SWIPE_LEAD_RATIO
        out.glowDur[i] = if (out.lineSynced[i]) GLOW_MIN_MS else max(timedDur * GLOW_DURATION_RATIO, GLOW_MIN_MS)

        val letters = if (kind == KIND_LETTER) countBySyl[vc.syllableIndex].coerceAtLeast(1) else 1
        val index = if (kind == KIND_LETTER) runningBySyl[vc.syllableIndex]++ else 0
        out.letterCount[i] = letters
        out.letterIdx[i] = index

        val sd = out.swipeDur[i]
        if (sd > 0f) {
            val timeWhereEnd = { v: Float -> sd * (v - SWIPE_END_FROM) / (SWIPE_END_TO - SWIPE_END_FROM) }
            val timeWhereStart = { v: Float -> sd * (v - SWIPE_START_FROM) / (SWIPE_START_TO - SWIPE_START_FROM) }
            out.begin[i] = timeWhereEnd(index.toFloat() / letters).coerceIn(0f, sd)
            out.finish[i] = timeWhereStart((index + 1f) / letters).coerceIn(0f, sd)
        }

        if (kind == KIND_LETTER) {
            if (sd > 0f) {
                val windowDur = max(out.finish[i] - out.begin[i], 1f)
                val touched = out.begin[i] - out.lead[i]
                val lit = touched + windowDur
                val rise = WAVE_MS * WAVE_CREST
                if (lit - rise > touched) {
                    out.waveDelay[i] = touched
                    out.waveDur[i] = (lit - touched) / WAVE_CREST
                } else {
                    out.waveDelay[i] = lit - rise
                    out.waveDur[i] = WAVE_MS
                }
            } else {
                out.waveDelay[i] = timedDur * index / letters
                out.waveDur[i] = WAVE_MS
            }
        }

        val g = sylGroup[vc.syllableIndex]
        if (g >= 0) {
            out.groupStartMs[i] = reconstructedWords[g].wordStartTimeMs.toFloat()
            out.groupWobble[i] = !groupLong[g]
        } else {
            out.groupStartMs[i] = startMs
            out.groupWobble[i] = false
        }

        var fxEnd = if (out.longWord[i]) startMs + out.glowDur[i] else startMs
        if (kind == KIND_LETTER) fxEnd = max(fxEnd, startMs + out.waveDelay[i] + out.waveDur[i])
        if (out.groupWobble[i]) fxEnd = max(fxEnd, out.groupStartMs[i] + WOBBLE_MS)
        out.fxEndMs = max(out.fxEndMs, fxEnd.toLong())
    }

    val extent = HashMap<Long, FloatArray>()

    fun keyOf(
        g: Int,
        line: Int,
    ) = g.toLong() * 4096L + line
    for (i in 0 until n) {
        if (out.kind[i] == KIND_NONE) continue
        val g = sylGroup[clusters[i].syllableIndex]
        if (g < 0) continue
        val e = extent.getOrPut(keyOf(g, clusters[i].lineIdx)) { floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE) }
        e[0] = min(e[0], statics.baseX[i])
        e[1] = max(e[1], statics.baseX[i] + clusters[i].width)
    }
    for (i in 0 until n) {
        if (out.kind[i] == KIND_NONE) continue
        val g = sylGroup[clusters[i].syllableIndex]
        val e = if (g >= 0) extent[keyOf(g, clusters[i].lineIdx)] else null
        out.groupCx[i] = if (e != null) (e[0] + e[1]) / 2f else statics.baseX[i] + clusters[i].width / 2f
    }
    return out
}

private fun braccatoEdge(
    b: BraccatoStatics,
    i: Int,
    elapsedMs: Float,
): Float {
    val sd = b.swipeDur[i]
    if (sd <= 0f) return (b.letterCount[i] + 2) / 2f
    val tt = (elapsedMs + b.lead[i]).coerceIn(b.begin[i], b.finish[i])
    val start = SWIPE_START_FROM + (SWIPE_START_TO - SWIPE_START_FROM) * tt / sd
    return start * b.letterCount[i] - b.letterIdx[i]
}

private fun braccatoCoverage(
    e0: Float,
    f: Float,
): Float {
    var area = e0.coerceIn(0f, 1f)
    val rampStart = max(e0, 0f)
    val rampEnd = min(e0 + f, 1f)
    if (rampEnd > rampStart && f > 0f) {
        val a0 = 1f - (rampStart - e0) / f
        val a1 = 1f - (rampEnd - e0) / f
        area += (rampEnd - rampStart) * (a0 + a1) / 2f
    }
    return area.coerceIn(0f, 1f)
}

internal fun braccatoExitOpacity(elapsedMs: Float): Float = 1f - BraccatoEase.transform((elapsedMs / FADE_OUT_MS).coerceIn(0f, 1f))

private class BraccatoRuntime {
    var hadActive = false
    var lastPos = 0L
    var exitPos = -1L
    var exitWall = -1L
}

@Composable
internal fun BraccatoWordAnimation(
    mainText: String,
    words: List<WordTimestamp>,
    isActiveLine: Boolean,
    positionProvider: () -> Long,
    adjustedEndTimeMs: Long?,
    lyricStyle: TextStyle,
    lineColor: () -> Color,
    expressiveAccent: Color,
    isBackground: Boolean,
    focusedAlpha: () -> Float,
    alignment: TextAlign,
    textMeasurer: TextMeasurer,
    isPlaying: Boolean = true,
    romanizedWords: List<WordTimestamp>? = null,
    romanizedText: String? = null,
    showSyllableRomanization: Boolean = false,
    prewarmMasks: Boolean = isActiveLine,
) {
    val latestPosition by rememberUpdatedState(positionProvider)
    val density = LocalDensity.current
    val fontSizePx = remember(lyricStyle, density) { with(density) { lyricStyle.fontSize.toPx() } }
    val gradientWidth = fontSizePx * 0.4f
    val extraPaddingPx = minOf(fontSizePx * 0.4f, with(density) { LYRICS_LINE_HORIZONTAL_PADDING.toPx() })
    val glowRadiusPx = remember(density) { with(density) { (GLOW_RADIUS_REM * 16f).dp.toPx() } }

    val runtime = remember { BraccatoRuntime() }
    var frameWallTimeMs by remember { mutableLongStateOf(0L) }

    val animationVisibility = rememberLyricsAnimationVisibility()
    LaunchedEffect(isActiveLine, isPlaying, animationVisibility) {
        if (!isActiveLine) {
            if (runtime.hadActive) {
                val mark = TimeSource.Monotonic.markNow()
                animateLyricsFrames(
                    animationVisibility,
                    shouldAnimate = { runtime.hadActive && mark.elapsedNow().inWholeMilliseconds < MAX_EXIT_MS },
                ) { frameWallTimeMs = it }
            }
            return@LaunchedEffect
        }
        animateLyricsFrames(animationVisibility) { frameWallTimeMs = it }
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

        val sylBounds =
            remember(alignedSyllables, charXMap, textLayoutResult, formattedMainText) {
                buildSyllableBounds(alignedSyllables, charXMap, textLayoutResult, formattedMainText)
            }

        val clusters =
            remember(formattedMainText, textLayoutResult, reconstructedWords, alignedSyllables, lyricStyle) {
                val metaList = buildInitialClusterMetas(formattedMainText, textLayoutResult, lineIsRtl)
                for ((wordIdx, word) in reconstructedWords.withIndex()) {
                    if (word.wordLen <= 0) continue
                    val wordText = word.syllables.joinToString("") { it.syllable.text }
                    val wordLevel = isJoiningScript(wordText) || isMixedDirection(wordText)
                    for (m in metasInRange(metaList, word.startIndex, word.endIndex)) {
                        if (formattedMainText[m.start].isWhitespace()) continue
                        m.isHeavy = true
                        m.isJoiningScript = wordLevel
                        m.wordIndex = wordIdx
                        m.wordStartMs = word.wordStartTimeMs
                        m.wordEndMs = word.wordEndTimeMs
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
                fontSizePx,
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
                    computePivotY = { it.subLayout.size.height * 0.8f + fontSizePx * 0.3f },
                )
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

        val bst =
            remember(clusters, alignedSyllables, reconstructedWords, statics, formattedMainText) {
                buildBraccatoStatics(clusters, alignedSyllables, reconstructedWords, statics, formattedMainText)
            }
        val n = clusters.size

        val baseCache = remember(clusters, extraPaddingPx) { ClusterMaskCache(n, extraPaddingPx) }
        LaunchedEffect(baseCache, bst, prewarmMasks) {
            if (!prewarmMasks) return@LaunchedEffect
            var mark = TimeSource.Monotonic.markNow()

            suspend fun yieldIfOverBudget() {
                if (mark.elapsedNow() >= PREWARM_FRAME_BUDGET) {
                    withFrameNanos { }
                    mark = TimeSource.Monotonic.markNow()
                }
            }
            for (i in 0 until n) {
                if (bst.kind[i] == KIND_NONE) continue
                baseCache.obtain(i, clusters[i].subLayout, density)
                yieldIfOverBudget()
            }
        }

        val litColorCache = remember { ColorAlphaCache() }
        val unlitColorCache = remember { ColorAlphaCache() }
        val glowColorCache = remember { ColorAlphaCache() }
        val glowLayerPaint = remember { Paint() }
        val pastBrushCache = remember { SolidColorCache() }
        val romanPastBrushCache = remember { SolidColorCache() }
        val unlitBrushCache = remember { SolidColorCache() }
        val inactiveBrushCache = remember { SolidColorCache() }

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
                    ).graphicsLayer { clip = false },
        ) {
            val wallMs = frameWallTimeMs
            val litColor = litColorCache.get(expressiveAccent, 1f)
            val litTransparent = litColor.copy(alpha = 0f)
            val unlitColor = unlitColorCache.get(expressiveAccent, focusedAlpha())
            val pastBrush = pastBrushCache.get(litColor)
            val unlitBrush = unlitBrushCache.get(unlitColor)

            var mode = 0
            var pos = 0L
            var fxPos = 0L
            var fadeOpacity = 1f
            if (isActiveLine) {
                runtime.hadActive = true
                runtime.exitPos = -1L
                runtime.exitWall = -1L
                pos = latestPosition()
                runtime.lastPos = pos
                fxPos = pos
                mode = 1
            } else if (runtime.hadActive) {
                if (runtime.exitPos < 0L) {
                    runtime.exitPos = runtime.lastPos
                    runtime.exitWall = wallMs
                }
                val speed = Settings.shared.playbackSpeed.coerceAtLeast(0.1f)
                val exitPos = runtime.exitPos
                val started = exitPos > bst.lineStartMs
                val needsFinish = started && exitPos < bst.lineEndMs
                val finishMs = if (needsFinish) FINISH_MS else 0f
                val fxRemainingMs = if (started) max(0f, (bst.fxEndMs - exitPos) / speed) else 0f
                val holdMs = max(finishMs, fxRemainingMs)
                val elapsed = (wallMs - runtime.exitWall).toFloat()
                fadeOpacity = braccatoExitOpacity(elapsed)
                if (elapsed >= max(holdMs, FADE_OUT_MS)) {
                    runtime.hadActive = false
                } else if (elapsed < holdMs) {
                    mode = 1
                    fxPos = exitPos + (elapsed * speed).toLong()
                    pos =
                        if (elapsed < finishMs) {
                            val t = LinearOutSlowInEasing.transform(elapsed / finishMs)
                            exitPos + ((bst.lineEndMs - exitPos) * t).toLong()
                        } else {
                            max(exitPos, bst.lineEndMs)
                        }
                } else {
                    mode = 2
                    pos = if (needsFinish) bst.lineEndMs else exitPos
                }
            }

            val baseColor =
                if (mode == 0) {
                    lineColor().let { it.copy(alpha = min(it.alpha, focusedAlpha())) }
                } else {
                    unlitColor
                }
            val baseBrush = if (mode == 0) inactiveBrushCache.get(baseColor) else unlitBrush
            val baseTint = baseCache.inactiveTint.get(baseColor)
            val padOffset = Offset(extraPaddingPx, extraPaddingPx)

            var glowLayerOpen = false
            var glowLayerSyl = -1
            for (pass in 0..2) {
                if (pass > 0 && mode == 0) continue

                for (i in 0 until n) {
                    val kind = bst.kind[i]
                    if (kind == KIND_NONE) continue
                    val vc = clusters[i]
                    val w = vc.width
                    val h =
                        vc.subLayout.size.height
                            .toFloat()
                    val rtl = statics.isRtlLine[i]
                    val letters = bst.letterCount[i]
                    val edgeLen = SWIPE_EDGE * letters

                    var litOpacity = 0f
                    var e0 = 0f
                    var glowStrength = 0f
                    var glowR = 0f
                    var waveTy = 0f
                    var waveScale = 1f
                    var wobbleTx = 0f
                    var wobbleSx = 1f
                    var wobbleTy = 0f

                    if (mode == 1) {
                        val e = pos - bst.startMs[i]
                        litOpacity =
                            when {
                                e < 0f -> 0f
                                bst.lineSynced[i] && isActiveLine -> BraccatoEase.transform((e / FADE_IN_MS).coerceIn(0f, 1f))
                                else -> 1f
                            } * fadeOpacity
                        e0 = if (bst.lineSynced[i]) (letters + 2) / 2f else braccatoEdge(bst, i, e)

                        val ef = fxPos - bst.startMs[i]
                        if (bst.longWord[i] && ef >= 0f && ef < bst.glowDur[i]) {
                            val gp = BraccatoEase.transform(ef / bst.glowDur[i])
                            glowR = glowRadiusPx * (1f - gp)
                            glowStrength = 1f
                        }

                        if (kind == KIND_LETTER) {
                            val local = ef - bst.waveDelay[i]
                            if (local > 0f) {
                                val p = local / bst.waveDur[i]
                                val peakScale = if (bst.emphasise[i]) WAVE_EMPHASIS_SCALE else 1f
                                if (p >= 1f) {
                                    waveTy = WAVE_SETTLE_EM * fontSizePx
                                } else if (p < WAVE_CREST) {
                                    val v = BraccatoEaseInOut.transform(p / WAVE_CREST)
                                    waveTy = WAVE_PEAK_EM * fontSizePx * v
                                    waveScale = 1f + (peakScale - 1f) * v
                                } else {
                                    val v = BraccatoEaseOut.transform((p - WAVE_CREST) / (1f - WAVE_CREST))
                                    waveTy = (WAVE_PEAK_EM + (WAVE_SETTLE_EM - WAVE_PEAK_EM) * v) * fontSizePx
                                    waveScale = peakScale + (1f - peakScale) * v
                                }
                            }
                        }

                        if (bst.groupWobble[i] && WOBBLE_MODE != 2) {
                            val eg = fxPos - bst.groupStartMs[i]
                            if (eg > 0f) {
                                val p = BraccatoEase.transform((eg / WOBBLE_MS).coerceIn(0f, 1f))
                                if (WOBBLE_MODE == 0) {
                                    val em =
                                        if (p < WOBBLE_PEAK_OFFSET) {
                                            WOBBLE_LIFT_PEAK_EM * (p / WOBBLE_PEAK_OFFSET)
                                        } else if (p < WOBBLE_SETTLE_OFFSET) {
                                            val v =
                                                BraccatoEaseInOut.transform(
                                                    (p - WOBBLE_PEAK_OFFSET) / (WOBBLE_SETTLE_OFFSET - WOBBLE_PEAK_OFFSET),
                                                )
                                            WOBBLE_LIFT_PEAK_EM + (WOBBLE_LIFT_SETTLE_EM - WOBBLE_LIFT_PEAK_EM) * v
                                        } else {
                                            val u = (p - WOBBLE_SETTLE_OFFSET) / (1f - WOBBLE_SETTLE_OFFSET)
                                            WOBBLE_LIFT_SETTLE_EM + (WOBBLE_LIFT_TO_EM - WOBBLE_LIFT_SETTLE_EM) * u
                                        }
                                    wobbleTy = em * fontSizePx
                                } else if (eg < WOBBLE_MS) {
                                    if (p < WOBBLE_PEAK_OFFSET) {
                                        val u = p / WOBBLE_PEAK_OFFSET
                                        wobbleTx = WOBBLE_TRANSLATE_EM * fontSizePx * u
                                        wobbleSx = 1f + (WOBBLE_SCALE_X - 1f) * u
                                    } else if (p < WOBBLE_SETTLE_OFFSET) {
                                        val v =
                                            BraccatoEaseInOut.transform(
                                                (p - WOBBLE_PEAK_OFFSET) / (WOBBLE_SETTLE_OFFSET - WOBBLE_PEAK_OFFSET),
                                            )
                                        wobbleTx = WOBBLE_TRANSLATE_EM * fontSizePx * (1f - v)
                                        wobbleSx = WOBBLE_SCALE_X + (1f - WOBBLE_SCALE_X) * v
                                    }
                                }
                            }
                        }
                    } else if (mode == 2 && pos >= bst.startMs[i]) {
                        litOpacity = fadeOpacity
                        e0 = (letters + 2) / 2f
                    }

                    if (pass > 0 && litOpacity <= 0.001f) continue
                    val cov = if (pass == 1) braccatoCoverage(e0, edgeLen) else 0f
                    if (pass == 1) {
                        val eligible = glowStrength > 0f && glowR > 0.05f && cov > 0f
                        if (glowLayerOpen && (!eligible || vc.syllableIndex != glowLayerSyl)) {
                            drawContext.canvas.restore()
                            glowLayerOpen = false
                        }
                        if (!eligible) continue
                        if (!glowLayerOpen) {
                            glowLayerPaint.alpha = (GLOW_COLOR_ALPHA * litOpacity).coerceIn(0f, 1f)
                            drawContext.canvas.saveLayer(
                                Rect(
                                    -glowRadiusPx * 3f,
                                    -glowRadiusPx * 3f,
                                    size.width + glowRadiusPx * 3f,
                                    size.height + glowRadiusPx * 3f,
                                ),
                                glowLayerPaint,
                            )
                            glowLayerOpen = true
                            glowLayerSyl = vc.syllableIndex
                        }
                    }
                    if (pass == 2 && e0 + edgeLen <= 0f) continue

                    val posX = statics.baseX[i] + extraPaddingPx
                    val posY = statics.baseY[i] + extraPaddingPx

                    withTransform({
                        if (wobbleTy != 0f) translate(left = 0f, top = wobbleTy)
                        if (wobbleSx != 1f || wobbleTx != 0f) {
                            translate(left = wobbleTx, top = 0f)
                            scale(wobbleSx, 1f, Offset(bst.groupCx[i] + extraPaddingPx, 0f))
                        }
                        translate(left = posX, top = posY)
                        if (waveTy != 0f || waveScale != 1f) {
                            translate(left = 0f, top = waveTy)
                            if (waveScale != 1f) scale(waveScale, waveScale, Offset(w / 2f, h / 2f))
                        }
                    }) {
                        if (pass == 0) {
                            val baseMask = baseCache.peek(i)
                            if (baseMask != null) {
                                drawMaskTinted(baseMask, baseCache.scaleOf(i), baseCache.padPx, baseTint)
                            } else {
                                drawText(textLayoutResult = vc.subLayout, brush = baseBrush, shadow = LyricsClearShadow)
                            }
                        } else if (pass == 1) {
                            drawText(
                                textLayoutResult = vc.subLayout,
                                brush = GlowFillBrush,
                                shadow =
                                    Shadow(
                                        color = glowColorCache.get(expressiveAccent, cov),
                                        offset = Offset.Zero,
                                        blurRadius = glowR * GLOW_BLUR_SCALE,
                                    ),
                            )
                        } else {
                            val x0 = e0 * w
                            val x1 = (e0 + edgeLen) * w
                            val brush =
                                if (rtl) {
                                    Brush.horizontalGradient(listOf(litColor, litTransparent), startX = w - x0, endX = w - x1)
                                } else {
                                    Brush.horizontalGradient(listOf(litColor, litTransparent), startX = x0, endX = max(x1, x0 + 0.01f))
                                }
                            val fullyLit = e0 >= 1f
                            val litMask = baseCache.peek(i)
                            if (litMask != null) {
                                if (fullyLit && litOpacity >= 0.999f) {
                                    drawMaskTinted(litMask, baseCache.scaleOf(i), baseCache.padPx, baseCache.pastTint.get(litColor))
                                } else if (fullyLit) {
                                    drawMaskTinted(
                                        litMask,
                                        baseCache.scaleOf(i),
                                        baseCache.padPx,
                                        baseCache.pastTint.get(litColor),
                                        alpha = litOpacity,
                                    )
                                } else {
                                    drawMaskLit(litMask, baseCache.scaleOf(i), baseCache.padPx, brush, baseCache.layerPaint, litOpacity)
                                }
                            } else {
                                drawText(
                                    textLayoutResult = vc.subLayout,
                                    brush = if (fullyLit) pastBrush else brush,
                                    alpha = litOpacity,
                                    shadow = LyricsClearShadow,
                                )
                            }
                        }
                    }
                }
                if (pass == 1 && glowLayerOpen) {
                    drawContext.canvas.restore()
                    glowLayerOpen = false
                }
            }

            if (romanLines.isNotEmpty()) {
                if (mode == 0) {
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
                        lineColor = lineColor(),
                        curSimpleState = 0f,
                        expressiveAccent = expressiveAccent,
                    )
                } else {
                    var activeSylIdx = -1
                    var curGX = 0f
                    var activeSylProgress = 0f
                    for (sIdx in alignedSyllables.indices) {
                        val s = alignedSyllables[sIdx].syllable
                        val ss = (s.startTime * 1000).toLong()
                        val se = (s.endTime * 1000).toLong()
                        if (pos >= ss) {
                            activeSylIdx = sIdx
                            val dur = (se - ss).coerceAtLeast(1L)
                            activeSylProgress = ((pos - ss).coerceIn(0L, dur)).toFloat() / dur.toFloat()
                            val sgStart = sylBounds.startX[sIdx]
                            val sgEnd = sylBounds.endX[sIdx]
                            curGX = sgStart + (sgEnd - sgStart) * activeSylProgress
                        } else {
                            break
                        }
                    }
                    val maxOffset = maxOf(0, formattedMainText.length - 1)
                    val activeLineIdx =
                        if (activeSylIdx != -1) {
                            textLayoutResult.getLineForOffset(alignedSyllables[activeSylIdx].startOffset.coerceIn(0, maxOffset))
                        } else {
                            -1
                        }
                    val isLastOfLine =
                        if (activeSylIdx != -1 && activeLineIdx != -1) {
                            if (activeSylIdx == alignedSyllables.lastIndex) {
                                true
                            } else {
                                textLayoutResult.getLineForOffset(
                                    alignedSyllables[activeSylIdx + 1].startOffset.coerceIn(0, maxOffset),
                                ) > activeLineIdx
                            }
                        } else {
                            false
                        }
                    val romanLitColor = lerp(unlitColor, litColor, fadeOpacity)
                    drawRomanizedWordClusters(
                        romanStatics = romanStatics,
                        isActiveLine = true,
                        activeSylIdx = activeSylIdx,
                        activeLineIdx = activeLineIdx,
                        curGX = curGX,
                        isActiveSylLastOfLine = isLastOfLine,
                        activeSylProgress = activeSylProgress,
                        gradientWidth = gradientWidth,
                        pastBrush = romanPastBrushCache.get(romanLitColor),
                        unlitBrush = unlitBrush,
                        litColor = romanLitColor,
                        unlitColor = unlitColor,
                        lineColor = Color.Unspecified,
                        curSimpleState = 1f,
                        expressiveAccent = expressiveAccent,
                    )
                }
            }
            @Suppress("UNUSED_EXPRESSION")
            padOffset
        }
    }
}

private fun DrawScope.drawMaskLit(
    mask: ImageBitmap,
    maskScale: Float,
    padPx: Float,
    brush: Brush,
    layerPaint: Paint,
    alpha: Float,
) {
    val inv = 1f / maskScale
    val localW = mask.width * inv
    val localH = mask.height * inv
    val canvas = drawContext.canvas
    layerPaint.alpha = alpha
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
    layerPaint.alpha = 1f
}
