/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Some UI stuff inside of this file are using YouLyPlus code under MIT code
 */

package com.metrolist.shared.ui.components.lyrics.words

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
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
import com.metrolist.shared.model.WordTimestamp
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.LYRICS_LINE_HORIZONTAL_PADDING
import com.metrolist.shared.ui.components.lyrics.nextLyricsDeadlineIndex
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

private val TransparentWhite = Color.White.copy(alpha = 0f)
private val TransparentWhiteBrush = SolidColor(TransparentWhite)

private val ZeroGrowState: State<Float> =
    object : State<Float> {
        override val value: Float get() = 0f
    }

@Composable
internal fun AppleWordAnimation(
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
    val currentPositionProvider by rememberUpdatedState(positionProvider)
    val density = LocalDensity.current
    val fontSizePx = remember(lyricStyle) { with(density) { lyricStyle.fontSize.toPx() } }
    val gradientWidth = fontSizePx * 1f
    val elevationAmount = fontSizePx * 0.05f
    val extraPaddingPx = minOf(fontSizePx * 0.4f, with(density) { LYRICS_LINE_HORIZONTAL_PADDING.toPx() })

    val emphasisLiftEm = 0.05f
    val emphasisRiseEm = 0.035f
    val emphasisShiftEm = 0.05f
    val emphasisScaleGain = 0.1f

    fun emphasisSpring(
        tSec: Float,
        responseSec: Float,
    ): Float {
        if (tSec <= 0f) return 0f
        val i = 2f * PI.toFloat() * tSec / maxOf(0.001f, responseSec)
        return 1f - (1f + i) * exp(-i)
    }

    fun VisualCluster.emphasisResponseSec(): Float = minOf(3f, maxOf(0.001f, (wordEndMs - wordStartMs) / 1000f))

    fun VisualCluster.emphasisSpanMs(): Float = maxOf(1f, charTranslateYPeak + emphasisResponseSec() * 2000f)

    class EmphasisSample {
        var lift = 0f
        var pulse = 0f
        var glow = 0f
    }

    fun emphasisGlowOpacity(fraction: Float): Float =
        when {
            fraction <= 0f || fraction >= 1f -> 0f
            fraction < 0.15f -> 0.8f * fraction / 0.15f
            fraction < 0.30f -> 0.8f + 0.2f * (fraction - 0.15f) / 0.15f
            fraction < 0.60f -> 1f - 0.7f * (fraction - 0.30f) / 0.30f
            else -> 0.3f * (1f - fraction) / 0.40f
        }

    fun VisualCluster.emphasisFrame(
        frame: Int,
        out: EmphasisSample,
    ) {
        if (frame >= 60) {
            out.lift = 1f
            out.pulse = 0f
            return
        }
        val response = emphasisResponseSec()
        val hold = charTranslateYPeak / 1000f
        val t = emphasisSpanMs() / 1000f * frame / 60f
        val sp = emphasisSpring(t, response)
        out.lift = sp
        out.pulse = sp * (1f - emphasisSpring(t - hold, response))
    }

    val emphasisTableFrames = 61

    fun buildEmphasisTable(vc: VisualCluster): FloatArray {
        val tmp = EmphasisSample()
        val table = FloatArray(emphasisTableFrames * 2)
        for (f in 0 until emphasisTableFrames) {
            vc.emphasisFrame(f, tmp)
            table[f * 2] = tmp.lift
            table[f * 2 + 1] = tmp.pulse
        }
        return table
    }

    fun sampleEmphasisCached(
        table: FloatArray,
        fraction: Float,
        out: EmphasisSample,
    ) {
        out.glow = emphasisGlowOpacity(fraction)
        if (fraction <= 0f) {
            out.lift = 0f
            out.pulse = 0f
            return
        }
        if (fraction >= 1f) {
            out.lift = 1f
            out.pulse = 0f
            return
        }
        val pos = fraction * 60f
        val i0 = pos.toInt().coerceIn(0, 59)
        val f = pos - i0
        val a = i0 * 2
        val b = a + 2
        out.lift = table[a] + (table[b] - table[a]) * f
        out.pulse = table[a + 1] + (table[b + 1] - table[a + 1]) * f
    }

    val emphasisTrailingPunct = "!?.,;:~…‥。、！？"

    fun isGraphemeAttached(ch: Char): Boolean =
        ch.category == CharCategory.NON_SPACING_MARK ||
            ch.category == CharCategory.COMBINING_SPACING_MARK ||
            ch.category == CharCategory.ENCLOSING_MARK ||
            ch.code in 0x0300..0x036F || ch.code in 0x3099..0x309A ||
            ch.code == 0x200D || ch.code == 0xFE0F || ch.code == 0xFE0E

    fun isEmphasisAttached(ch: Char): Boolean = emphasisTrailingPunct.indexOf(ch) >= 0 || isGraphemeAttached(ch)

    fun isNeedSplitChar(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF ||
            cp in 0x3400..0x4DBF ||
            cp in 0x20000..0x2A6DF ||
            cp in 0x2A700..0x2EBEF ||
            cp in 0x30000..0x323AF ||
            cp in 0xF900..0xFAFF ||
            cp in 0x2F800..0x2FA1F ||
            cp in 0x3005..0x3007 ||
            cp in 0x3021..0x3029 ||
            cp in 0x3038..0x303B ||
            cp in 0x3041..0x3096 ||
            cp in 0x309D..0x309F ||
            cp in 0x30A1..0x30FA ||
            cp in 0x30FD..0x30FF ||
            cp in 0x31F0..0x31FF ||
            cp in 0x32D0..0x32FE ||
            cp in 0x3300..0x3357 ||
            cp in 0xFF66..0xFF9F ||
            cp in 0x1B000..0x1B122 ||
            cp == 0x1B132 ||
            cp in 0x1B150..0x1B152 ||
            cp in 0x1B164..0x1B167 ||
            cp == 0x1F200

    fun isPunctOrSymbol(ch: Char): Boolean =
        when (ch.category) {
            CharCategory.CONNECTOR_PUNCTUATION,
            CharCategory.DASH_PUNCTUATION,
            CharCategory.START_PUNCTUATION,
            CharCategory.END_PUNCTUATION,
            CharCategory.INITIAL_QUOTE_PUNCTUATION,
            CharCategory.FINAL_QUOTE_PUNCTUATION,
            CharCategory.OTHER_PUNCTUATION,
            CharCategory.MATH_SYMBOL,
            CharCategory.CURRENCY_SYMBOL,
            CharCategory.MODIFIER_SYMBOL,
            CharCategory.OTHER_SYMBOL,
            -> true

            else -> false
        }

    fun isNeedSplitOnly(text: String): Boolean {
        if (text.isEmpty()) return false
        var hasNeedSplit = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = text[i]
            if (isNeedSplitChar(cp)) {
                hasNeedSplit = true
            } else if (cp != 0x30FC && !ch.isWhitespace() && !isPunctOrSymbol(ch) && !isGraphemeAttached(ch)) {
                return false
            }
            i += if (cp > 0xFFFF) 2 else 1
        }
        return hasNeedSplit
    }

    fun countGraphemes(text: String): Int {
        if (text.isEmpty()) return 0
        var count = 0
        var prevZwj = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = text[i]
            val attached = count > 0 && (prevZwj || isGraphemeAttached(ch))
            if (!attached) count++
            prevZwj = cp == 0x200D
            i += if (cp > 0xFFFF) 2 else 1
        }
        return count
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
                isBackground,
            ) {
                val metaList = buildInitialClusterMetas(formattedMainText, textLayoutResult, lineIsRtl)

                for ((wordIdx, word) in reconstructedWords.withIndex()) {
                    if (word.wordLen <= 0) continue

                    val wordText = word.syllables.joinToString("") { it.syllable.text }
                    val isJoining = isJoiningScript(wordText)
                    val trimmedWordText = wordText.trim()
                    val wordDurationMs = (word.wordEndTimeMs - word.wordStartTimeMs).toFloat()
                    val gateDurationMs =
                        word.syllables.sumOf { ((it.syllable.endTime - it.syllable.startTime) * 1000).toLong() }
                    val isGroupGrowable =
                        !isBackground &&
                            !isRtlText(wordText) &&
                            !isNeedSplitOnly(wordText) &&
                            trimmedWordText.isNotEmpty() &&
                            countGraphemes(trimmedWordText) <= 7 &&
                            gateDurationMs >= 1000L

                    val charGroups = mutableListOf<MutableList<ClusterMeta>>()
                    if (isGroupGrowable) {
                        for (m in metasInRange(metaList, word.startIndex, word.endIndex)) {
                            val ch = formattedMainText[m.start]
                            if (ch.isWhitespace()) continue
                            if (charGroups.isNotEmpty() && isEmphasisAttached(ch)) {
                                charGroups.last().add(m)
                            } else {
                                charGroups.add(mutableListOf(m))
                            }
                        }
                    }
                    val numChars = charGroups.size

                    if (isGroupGrowable && numChars > 0) {
                        val e = maxOf(0.001f, wordDurationMs / 1000f)
                        val delaySec = minOf(e / numChars * 0.4f, 0.4f)
                        val holdMs = 2f * e / numChars * 1000f
                        val amplitude = (e - 1f).coerceIn(0f, 1f)
                        val glowMax = 0.45f * ((e - 1f) / 0.5f).coerceIn(0f, 1f)
                        val half = maxOf(1f, (numChars - 1) / 2f)

                        charGroups.forEachIndexed { charIdx, group ->
                            for (m in group) {
                                m.isHeavy = true
                                m.isJoiningScript = isJoining
                                m.wordIndex = wordIdx
                                m.wordStartMs = word.wordStartTimeMs
                                m.wordEndMs = word.wordEndTimeMs
                                m.intensity = amplitude
                                m.charShadowIntensity = glowMax
                                m.charDelayMs = ((charIdx + 1) * delaySec * 1000f).toLong()
                                m.charTranslateYPeak = holdMs
                                m.normX = (charIdx - (numChars - 1) / 2f) / half
                            }
                        }
                    } else if (isJoining) {
                        for (m in metasInRange(metaList, word.startIndex, word.endIndex)) {
                            if (!formattedMainText[m.start].isWhitespace()) {
                                m.isJoiningScript = true
                                m.wordIndex = wordIdx
                                m.wordStartMs = word.wordStartTimeMs
                                m.wordEndMs = word.wordEndTimeMs
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

        val lineStartTime = remember(words) { words.firstOrNull()?.let { (it.startTime * 1000).toLong() } ?: 0L }
        val lineEndTime = remember(words) { words.lastOrNull()?.let { (it.endTime * 1000).toLong() } ?: 0L }
        val effectiveEndTimeMs = adjustedEndTimeMs ?: lineEndTime

        val animState = remember(mainText) { LyricAnimationState() }

        val activeSylIdxState = rememberActiveSyllableIdxState(alignedSyllables) { currentPositionProvider() }
        val activeInfoState =
            rememberActiveSylInfoState(activeSylIdxState, alignedSyllables, textLayoutResult, formattedMainText)
        val activeSylProgressState =
            rememberActiveSylProgressState(activeSylIdxState, alignedSyllables) { currentPositionProvider() }

        val playbackSpeed = Settings.shared.playbackSpeed.coerceAtLeast(0.1f)
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

        val heavyElevationIndices = remember(clusters) { clusters.indices.filter { clusters[it].isHeavy } }
        val syllableElevationIndices = remember(alignedSyllables) { alignedSyllables.indices.toList() }

        heavyElevationIndices.forEach { clusterIdx ->
            val releaseAnim =
                remember(clusterIdx) { animState.elevationFor(packElevationKey(true, clusterIdx.toLong())) }
            LaunchedEffect(isActiveLine) {
                val hc = clusters[clusterIdx]
                withContext(FixedMotionDurationScale) {
                    if (isActiveLine) {
                        releaseAnim.snapTo(0f)
                    } else {
                        val pos = currentPositionProvider()
                        var wordRemaining = 0f
                        for (c in clusters) {
                            if (c.isHeavy && c.wordStartMs == hc.wordStartMs) {
                                wordRemaining =
                                    maxOf(wordRemaining, c.emphasisSpanMs() - (pos - c.wordStartMs - c.charDelayMs))
                            }
                        }
                        val ownRemaining = hc.emphasisSpanMs() - (pos - hc.wordStartMs - hc.charDelayMs)
                        if (pos >= hc.wordStartMs) {
                            if (wordRemaining > 0f && ownRemaining > 0f) {
                                releaseAnim.animateTo(
                                    1f,
                                    tween(
                                        durationMillis =
                                            (minOf(500f, ownRemaining) / playbackSpeed)
                                                .toInt()
                                                .coerceAtLeast(1),
                                        easing = Easing { t -> t * t * (3f - 2f * t) },
                                    ),
                                )
                            } else {
                                releaseAnim.animateTo(
                                    1f,
                                    tween(
                                        durationMillis = 1000,
                                        delayMillis = (wordRemaining.coerceAtLeast(0f) / playbackSpeed).toInt(),
                                        easing = FastOutSlowInEasing,
                                    ),
                                )
                            }
                        } else {
                            releaseAnim.snapTo(1f)
                        }
                    }
                }
            }
        }

        syllableElevationIndices.forEach { sIdx ->
            val startMs =
                (
                    alignedSyllables
                        .getOrNull(sIdx)
                        ?.syllable
                        ?.startTime
                        ?.times(1000)
                )?.toLong() ?: 0L
            val targetElev by remember(sIdx, isActiveLine, startMs, elevationAmount) {
                derivedStateOf(structuralEqualityPolicy()) {
                    if (isActiveLine &&
                        currentPositionProvider() >= startMs
                    ) {
                        -elevationAmount
                    } else {
                        0f
                    }
                }
            }
            val elevAnim = remember(sIdx) { animState.elevationFor(packElevationKey(false, sIdx.toLong())) }
            LaunchedEffect(targetElev) {
                if (elevAnim.targetValue != targetElev || elevAnim.isRunning) {
                    withContext(FixedMotionDurationScale) {
                        elevAnim.animateTo(targetElev, tween(durationMillis = 1000, easing = FastOutSlowInEasing))
                    }
                }
            }
        }

        val seekGeneration = remember { mutableStateOf(0) }
        val hasHeavyClusters = remember(clusters) { clusters.any { it.isHeavy } }
        val startedWordTimeState =
            remember(clusters) {
                val starts = clusters.map { it.wordStartMs }.distinct().sorted()
                derivedStateOf {
                    starts.getOrNull(nextLyricsDeadlineIndex(starts, currentPositionProvider()) - 1) ?: Long.MIN_VALUE
                }
            }
        LaunchedEffect(isActiveLine, hasHeavyClusters) {
            if (!isActiveLine || !hasHeavyClusters) return@LaunchedEffect
            var last = -1L
            snapshotFlow { currentPositionProvider() }.collect { pos ->
                if (last >= 0L && abs(pos - last) > 250L) seekGeneration.value += 1
                last = pos
            }
        }

        val clusterGrowStates: List<State<Float>> =
            clusters.mapIndexed { idx, vc ->
                if (!vc.isHeavy) return@mapIndexed ZeroGrowState
                val spanMs = vc.emphasisSpanMs()
                val charDelayMs = vc.charDelayMs
                val latched = remember(vc) { BooleanArray(1) }
                val wordShouldRun by remember(vc, isActiveLine, lineEndTime) {
                    derivedStateOf(structuralEqualityPolicy()) {
                        val pos = currentPositionProvider()
                        val next =
                            if (pos < vc.wordStartMs) {
                                false
                            } else if (isActiveLine || (pos <= lineEndTime && curGX > 0f)) {
                                true
                            } else {
                                latched[0]
                            }
                        latched[0] = next
                        next
                    }
                }
                val growAnim = animState.growAnimFor(idx)

                LaunchedEffect(wordShouldRun, seekGeneration.value, isPlaying, playbackSpeed) {
                    if (!wordShouldRun) {
                        growAnim.snapTo(0f)
                        return@LaunchedEffect
                    }
                    val currentPosition = currentPositionProvider()
                    withContext(FixedMotionDurationScale) {
                        val elapsedSinceOwnStart = currentPosition - vc.wordStartMs - charDelayMs
                        if (elapsedSinceOwnStart < 0L) {
                            growAnim.snapTo(0f)
                            if (isPlaying) {
                                growAnim.animateTo(
                                    1f,
                                    tween(
                                        durationMillis = (spanMs / playbackSpeed).toInt(),
                                        delayMillis = ((-elapsedSinceOwnStart) / playbackSpeed).toInt(),
                                        easing = LinearEasing,
                                    ),
                                )
                            }
                        } else {
                            val progress = (elapsedSinceOwnStart / spanMs).coerceIn(0f, 1f)
                            growAnim.snapTo(progress)
                            val remainingMs = (((1f - progress) * spanMs) / playbackSpeed).toInt()
                            if (isPlaying && remainingMs > 0) {
                                growAnim.animateTo(1f, tween(durationMillis = remainingMs, easing = LinearEasing))
                            }
                        }
                    }
                }
                growAnim.asState()
            }

        val curColorState by animState.colorState.asState()
        val curSimpleState by animState.simpleState.asState()

        val clusterScales = remember(clusters) { FloatArray(clusters.size) { 1f } }
        val clusterGlowAlphas = remember(clusters) { FloatArray(clusters.size) }
        val clusterIsGlowing = remember(clusters) { BooleanArray(clusters.size) }
        val clusterDrawX = remember(clusters) { FloatArray(clusters.size) }
        val clusterDrawY = remember(clusters) { FloatArray(clusters.size) }
        val clusterLastDrawY = remember(clusters) { FloatArray(clusters.size) { Float.NaN } }
        val clusterIsYMoving = remember(clusters) { BooleanArray(clusters.size) }
        val glowPossible = remember(clusters) { clusters.any { it.isHeavy } }
        val heavyClusterIdx = remember(clusters) { clusters.indices.filter { clusters[it].isHeavy }.toIntArray() }
        val anyHeavyGrowing = {
            var growing = false
            for (i in heavyClusterIdx) {
                val g = clusterGrowStates[i].value
                if (g > 0f && g < 1f) {
                    growing = true
                    break
                }
            }
            growing
        }
        val isArabicOrRtl = remember(mainText) { isRtlText(mainText) || isJoiningScript(mainText) }

        val clusterElevAnims =
            remember(clusters) {
                Array(clusters.size) { idx ->
                    val vc = clusters[idx]
                    val key =
                        packElevationKey(
                            vc.isHeavy,
                            if (vc.isHeavy) idx.toLong() else vc.syllableIndex.coerceAtLeast(0).toLong(),
                        )
                    animState.elevationFor(key)
                }
            }

        val maskCacheStore =
            remember(clusters, extraPaddingPx) {
                ClusterMaskCache(clusters.size, extraPaddingPx)
            }
        LaunchedEffect(maskCacheStore, prewarmMasks) {
            if (!prewarmMasks) return@LaunchedEffect
            maskCacheStore.prewarmSliced(
                count = clusters.size,
                density = density,
                layoutOf = { clusters[it].subLayout },
                glowBlurOf = {
                    val vc = clusters[it]
                    if (vc.isHeavy && !(isArabicOrRtl || vc.isRtl || vc.isJoiningScript)) fontSizePx * 0.1f else 0f
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
        val sample = remember { EmphasisSample() }
        val litColorCache = remember { ColorAlphaCache() }
        val unlitColorCache = remember { ColorAlphaCache() }
        val pastBrushCache = remember { SolidColorCache() }
        val unlitBrushCache = remember { SolidColorCache() }
        val glowShadowTable = remember(fontSizePx) { GlowShadowTable(fontSizePx * 0.1f) }
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
        val clusterMotionOff =
            remember(clusters, isArabicOrRtl) {
                BooleanArray(clusters.size) { isArabicOrRtl || clusters[it].isRtl || clusters[it].isJoiningScript }
            }
        val emphasisTables =
            remember(clusters) {
                Array(clusters.size) { idx -> if (clusters[idx].isHeavy) buildEmphasisTable(clusters[idx]) else null }
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
                        val heavyGrowing = anyHeavyGrowing()
                        compositingStrategy =
                            if (glowPossible && heavyGrowing) {
                                CompositingStrategy.Offscreen
                            } else {
                                CompositingStrategy.Auto
                            }
                    },
        ) {
            val isFinishing = finishing()
            val showActiveWipe = isActiveLine || isFinishing
            val heavyGrowing = anyHeavyGrowing()
            val startedWordTime = if (showActiveWipe || heavyGrowing) startedWordTimeState.value else Long.MIN_VALUE
            val activeInfo = if (isActiveLine) activeInfoState.value else null
            val wipeSylIdx = if (isFinishing) -2 else (activeInfo?.index ?: -1)
            val wipeLineIdx =
                if (isFinishing) {
                    finishingLineIndex(curGX, lineStarts, lineWidths, lineCount, animState.finishStartLineIdx)
                } else {
                    activeInfo?.lineIdx ?: -1
                }
            val isActiveSylLastOfLine = if (isFinishing) true else (activeInfo?.isLastOfLine ?: false)
            val maskCache = if (showActiveWipe || heavyGrowing) maskCacheStore else null
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

            val n = clusters.size
            var anyGlow = false

            for (idx in 0 until n) {
                val vc = clusters[idx]
                val elevValue = clusterElevAnims[idx].value

                var scale = 1.0f
                var shift = 0f
                var glowAlpha = 0f
                var lift = 0f
                var rise = 0f

                val disableGlowAndGrow = clusterMotionOff[idx]
                val growFraction = if (startedWordTime >= vc.wordStartMs) clusterGrowStates[idx].value else 0f
                var isEmphasised = false

                if (vc.isHeavy) {
                    sampleEmphasisCached(emphasisTables[idx]!!, growFraction, sample)
                    isEmphasised = growFraction > 0f && growFraction < 1f

                    lift = -emphasisLiftEm * fontSizePx * sample.lift * (1f - elevValue)

                    if (!disableGlowAndGrow) {
                        val pulse = sample.pulse * vc.intensity
                        rise = -emphasisRiseEm * fontSizePx * pulse
                        scale = 1f + emphasisScaleGain * pulse
                        shift = vc.normX * emphasisShiftEm * fontSizePx * pulse
                        glowAlpha = (vc.charShadowIntensity * sample.glow).coerceIn(0f, 1f)
                    }
                }

                val drawX = statics.baseX[idx] + shift
                val drawY = statics.baseY[idx] + (if (vc.isHeavy) lift + rise else elevValue)
                val isGlowing = !disableGlowAndGrow && vc.isHeavy && isEmphasised

                clusterScales[idx] = scale
                clusterGlowAlphas[idx] = glowAlpha
                clusterIsGlowing[idx] = isGlowing
                clusterDrawX[idx] = drawX
                clusterDrawY[idx] = drawY
                val lastY = clusterLastDrawY[idx]
                val isYMoving =
                    (!lastY.isNaN() && abs(drawY - lastY) > 0.001f) || clusterElevAnims[idx].isRunning
                clusterIsYMoving[idx] = isYMoving
                clusterLastDrawY[idx] = drawY
                if (isGlowing) anyGlow = true
            }

            if (anyGlow) {
                for (idx in 0 until n) {
                    if (clusterIsGlowing[idx]) {
                        val glowAlpha = clusterGlowAlphas[idx]
                        if (glowAlpha <= 0f) continue
                        val vc = clusters[idx]
                        val scale = clusterScales[idx]
                        val drawX = clusterDrawX[idx]
                        val drawY = clusterDrawY[idx]
                        if (maskCache != null) {
                            val glowMask = maskCache.peekGlow(idx) ?: continue
                            withTransform({
                                translate(drawX + extraPaddingPx, drawY + extraPaddingPx)
                                if (scale != 1.0f) {
                                    scale(
                                        scale,
                                        scale,
                                        pivot = Offset(statics.pivotX[idx], statics.pivotY[idx]),
                                    )
                                }
                            }) {
                                drawMaskTinted(
                                    mask = glowMask,
                                    maskScale = maskCache.glowScaleOf(idx),
                                    padPx = maskCache.padPx,
                                    filter = maskCache.glowFilter,
                                    alpha = glowAlpha,
                                    blendMode = BlendMode.Lighten,
                                )
                            }
                        } else {
                            val shadow = glowShadowTable.get(glowAlpha)
                            val tLeft = Offset(drawX + extraPaddingPx, drawY + extraPaddingPx)
                            withTransform({
                                if (scale != 1.0f) {
                                    scale(
                                        scale,
                                        scale,
                                        pivot = Offset(tLeft.x + statics.pivotX[idx], tLeft.y + statics.pivotY[idx]),
                                    )
                                }
                            }) {
                                drawText(
                                    textLayoutResult = vc.subLayout,
                                    topLeft = tLeft,
                                    brush = TransparentWhiteBrush,
                                    shadow = shadow,
                                    blendMode = BlendMode.Lighten,
                                )
                            }
                        }
                    }
                }
            }

            val pastBrush = pastBrushCache.get(pastColor)
            val unlitBrush = unlitBrushCache.get(unlitColor)
            val pastFilter = maskCache?.pastTint?.get(pastColor)
            val unlitFilter = maskCache?.unlitTint?.get(unlitColor)
            val inactiveFilter =
                if (maskCache != null && inactiveBrush != null) maskCache.inactiveTint.get(inactiveBrush.value) else null

            for (idx in 0 until n) {
                val vc = clusters[idx]
                val cdScale = clusterScales[idx]
                val cdDrawX = clusterDrawX[idx]
                val cdDrawY = clusterDrawY[idx]
                val isRtlLine = statics.isRtlLine[idx]
                val lgs = statics.lineStart[idx]
                val lw = statics.lineWidth[idx]
                val topLeft = Offset(cdDrawX + extraPaddingPx, cdDrawY + extraPaddingPx)

                val isScaling = cdScale != 1.0f
                val isElevating = clusterIsYMoving[idx]
                val isAnimating = (showActiveWipe || heavyGrowing) && (isScaling || isElevating)
                val mask = if (isAnimating) maskCache?.peek(idx) else null
                if (mask == null || maskCache == null) {
                    if (isScaling) {
                        withTransform({
                            translate(topLeft.x, topLeft.y)
                            scale(
                                cdScale,
                                cdScale,
                                pivot = Offset(statics.pivotX[idx], statics.pivotY[idx]),
                            )
                        }) {
                            if (inactiveBrush != null) {
                                drawClusterText(
                                    vc = vc,
                                    topLeft = Offset.Zero,
                                    brush = inactiveBrush,
                                    shadow = LyricsClearShadow,
                                )
                            } else {
                                drawWipeText(
                                    vc = vc,
                                    topLeft = Offset.Zero,
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
                                    unlitBrush = unlitBrush,
                                    litColor = litColor,
                                    unlitColor = unlitColor,
                                    wipe = wipeScratch,
                                    shadow = LyricsClearShadow,
                                )
                            }
                        }
                    } else {
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
                                unlitBrush = unlitBrush,
                                litColor = litColor,
                                unlitColor = unlitColor,
                                wipe = wipeScratch,
                                shadow = LyricsClearShadow,
                            )
                        }
                    }
                } else {
                    val maskScale = maskCache.scaleOf(idx)
                    withTransform({
                        translate(topLeft.x, topLeft.y)
                        if (isScaling) {
                            scale(
                                cdScale,
                                cdScale,
                                pivot = Offset(statics.pivotX[idx], statics.pivotY[idx]),
                            )
                        }
                    }) {
                        if (inactiveBrush != null) {
                            drawMaskTinted(mask, maskScale, maskCache.padPx, inactiveFilter!!)
                        } else {
                            computeWipe(
                                vc = vc,
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
                                out = wipeScratch,
                            )
                            when (wipeScratch.mode) {
                                WIPE_PAST -> {
                                    drawMaskTinted(mask, maskScale, maskCache.padPx, pastFilter!!)
                                }

                                WIPE_UNLIT -> {
                                    drawMaskTinted(mask, maskScale, maskCache.padPx, unlitFilter!!)
                                }

                                else -> {
                                    drawMaskBand(
                                        mask,
                                        maskScale,
                                        maskCache.padPx,
                                        wipeScratch,
                                        maskCache.bandBrush(
                                            litColor,
                                            unlitColor,
                                            (wipeScratch.bandEnd - wipeScratch.bandStart) * maskScale,
                                        ),
                                        maskCache.layerPaint,
                                    )
                                }
                            }
                        }
                    }
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
