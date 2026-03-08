/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Some UI stuff inside of this file are using YouLyPlus code under MIT code
 */

package com.metrolist.shared.ui.components.lyrics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.WordTimestamp
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.words.FixedMotionDurationScale
import com.metrolist.shared.ui.components.lyrics.words.LyricsAnimationStyle
import com.metrolist.shared.ui.components.lyrics.words.WordLevelLyrics
import com.metrolist.shared.ui.components.lyrics.words.isJoiningScript
import com.metrolist.shared.ui.components.lyrics.words.isRtlText
import com.metrolist.shared.ui.components.lyrics.words.isSameLyricsText
import com.metrolist.shared.ui.components.lyrics.words.measureSyllableRomanizationWidth
import com.metrolist.shared.ui.components.lyrics.words.rememberFormattedMainText
import com.metrolist.shared.ui.components.lyrics.words.romanizedSyllableText
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

internal val LYRICS_LINE_HORIZONTAL_PADDING = 12.dp
private const val MULTI_SINGER_PADDING_RATIO = 0.10f

private fun TextStyle.forDisplayedText(text: String): TextStyle =
    copy(
        fontFeatureSettings = "liga 0",
        letterSpacing = if (isJoiningScript(text)) 0.sp else letterSpacing,
        textDirection = if (isRtlText(text)) TextDirection.ContentOrRtl else TextDirection.ContentOrLtr,
    )

@Composable
private fun AlphaSafeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val needsLayer = color.alpha > 0f && color.alpha < 1f && remember(text) { isJoiningScript(text) }
    if (needsLayer) {
        val layerAlpha = color.alpha
        Text(
            text = text,
            style = style.copy(color = color.copy(alpha = 1f)),
            textAlign = textAlign,
            maxLines = maxLines,
            softWrap = softWrap,
            overflow = overflow,
            modifier =
                modifier.graphicsLayer {
                    alpha = layerAlpha
                    compositingStrategy = CompositingStrategy.Offscreen
                },
        )
    } else {
        Text(
            text = text,
            style = style.copy(color = color),
            textAlign = textAlign,
            maxLines = maxLines,
            softWrap = softWrap,
            overflow = overflow,
            modifier = modifier,
        )
    }
}

@Composable
private fun AnimatedAlphaSafeText(
    text: String,
    style: TextStyle,
    textColor: Color,
    alpha: () -> Float,
    lineColor: ColorProducer,
    modifier: Modifier = Modifier,
) {
    if (remember(text) { isJoiningScript(text) }) {
        val opaqueStyle = remember(style, textColor) { style.copy(color = textColor) }
        Text(
            text = text,
            style = opaqueStyle,
            modifier =
                modifier.graphicsLayer {
                    val a = alpha().coerceIn(0f, 1f)
                    this.alpha = a
                    compositingStrategy = if (a < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                },
        )
    } else {
        Text(
            text = text,
            style = style,
            color = lineColor,
            modifier = modifier,
        )
    }
}

@Composable
private fun LyricsBgReveal(
    visible: Boolean,
    bgBeforeMain: Boolean,
    alignment: TextAlign = TextAlign.Start,
    onBgMeasured: (measuredH: Int, reportedH: Int) -> Unit = { _, _ -> },
    onLayoutShift: (Float) -> Unit = {},
    mainTranslationY: () -> Float = { 0f },
    durationMs: () -> Long = { 400L },
    zeta: () -> Float = { SPRING_ZETA_DEFAULT },
    isStaging: () -> Boolean = { false },
    staggerDelayMs: () -> Int = { 0 },
    content: @Composable () -> Unit,
) {
    val lastLayoutH = remember { intArrayOf(if (visible) -1 else 0) }
    val measuredHeight = remember { intArrayOf(0) }
    val alpha = remember { Animatable(if (visible) 1f else 0f) }
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) {
        while (isStaging()) {
            withFrameNanos { }
        }
        val waitMs = staggerDelayMs().coerceAtLeast(0)
        val baseDuration = durationMs().coerceAtLeast(1L)
        val spring = lyPlusSpringFor(baseDuration, zeta())
        val inScaleMs = spring.totalMs.roundToInt().coerceAtLeast(1)
        val inAlphaMs = (baseDuration.toFloat() * 0.95f).roundToInt().coerceIn(300, 450)
        val outMs = (baseDuration.toFloat() * 0.8f).roundToInt().coerceIn(260, 360)

        withContext(FixedMotionDurationScale) {
            if (visible) {
                val scaleEasing = Easing { f -> spring.value(f * spring.totalMs) }
                launch {
                    alpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = inAlphaMs, delayMillis = waitMs, easing = LinearOutSlowInEasing),
                    )
                }
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = inScaleMs, delayMillis = waitMs, easing = scaleEasing),
                )
            } else {
                launch {
                    alpha.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = outMs, delayMillis = waitMs, easing = FastOutSlowInEasing),
                    )
                }
                progress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = outMs, delayMillis = waitMs, easing = FastOutSlowInEasing),
                )
            }
        }
    }
    val currentVisible by rememberUpdatedState(visible)
    val hidden by remember {
        derivedStateOf {
            !currentVisible &&
                alpha.value <= 0f &&
                progress.value <= 0f &&
                (!bgBeforeMain || abs(mainTranslationY()) < 0.5f)
        }
    }
    val pivotX =
        when (alignment) {
            TextAlign.Center, TextAlign.Justify -> 0.5f
            TextAlign.Right, TextAlign.End -> 1f
            else -> 0f
        }
    val pivotY = if (bgBeforeMain) 1f else 0f
    Box(
        modifier =
            Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    measuredHeight[0] = placeable.height
                    val reportedH = if (visible) placeable.height else 0
                    onBgMeasured(placeable.height, reportedH)
                    if (lastLayoutH[0] >= 0 && lastLayoutH[0] != reportedH) {
                        onLayoutShift((reportedH - lastLayoutH[0]).toFloat())
                    }
                    lastLayoutH[0] = reportedH
                    layout(placeable.width, reportedH) {
                        if (!hidden) {
                            placeable.place(0, 0)
                        }
                    }
                }.graphicsLayer {
                    this.alpha = alpha.value.coerceIn(0f, 1f)
                    val scale = (0.8f + 0.2f * progress.value).coerceAtLeast(0f)
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(pivotX, pivotY)
                    if (bgBeforeMain) {
                        val mainShift = mainTranslationY()
                        translationY = if (visible) mainShift else mainShift - measuredHeight[0]
                    } else {
                        translationY = 0f
                    }
                    clip = false
                },
    ) {
        content()
    }
}

internal fun LyricsEntry.hasAnimatedWordTiming(includeRomanization: Boolean): Boolean =
    words.orEmpty().any { !it.isBackground && !it.isLineTimed && it.endTime > it.startTime } ||
        (includeRomanization && romanizedWords.orEmpty().any { !it.isBackground && !it.isLineTimed && it.endTime > it.startTime })

internal fun LyricsEntry.hasTimedRomanization(): Boolean =
    words?.any { !it.isBackground && !it.isLineTimed } == true &&
        romanizedWords?.any { !it.isBackground && !it.isLineTimed } == true

@Composable
fun ActiveLyricsLineContent(
    entry: LyricsEntry,
    isActive: Boolean,
    positionProvider: () -> Long,
    lineEndTimeMs: Long = Long.MAX_VALUE,
    isPlaying: Boolean = true,
    textColor: Color,
    lyricStyle: TextStyle,
    textMeasurer: TextMeasurer,
    alignment: TextAlign = LyricsUtils.textAlignment(Settings.shared.lyricsTextPosition),
    maxLines: Int = Int.MAX_VALUE,
    fillWidth: Boolean = true,
    singleLine: Boolean = false,
    useFixedTone: Boolean = true,
) {
    val currentPositionProvider by rememberUpdatedState(positionProvider)
    val romanizationEnabled = Settings.shared.lyricsRomanizationEnabled
    val romanizeAsMainSetting = Settings.shared.lyricsRomanizeAsMain
    val animationStyleSetting = Settings.shared.lyricsAnimationStyle
    val showTranslations = Settings.shared.lyricsShowTranslations
    val syllableRomanizationSetting = Settings.shared.lyricsSyllableRomanizationEnabled
    val prep =
        remember(
            entry,
            lyricStyle,
            romanizationEnabled,
            romanizeAsMainSetting,
            animationStyleSetting,
            showTranslations,
            syllableRomanizationSetting,
        ) {
            ActiveLinePrep.build(
                entry = entry,
                lyricStyle = lyricStyle,
                romanizationEnabled = romanizationEnabled,
                romanizeAsMainSetting = romanizeAsMainSetting,
                animationStyleSetting = animationStyleSetting,
                showTranslations = showTranslations,
                syllableRomanizationSetting = syllableRomanizationSetting,
            )
        }
    val mainWords = prep.mainWords
    val secondaryWords = prep.secondaryWords
    val animationStyle = prep.animationStyle
    val isWordAnimationEnabled = prep.isWordAnimationEnabled
    val color = if (isActive) textColor else textColor.copy(alpha = 0.35f)
    val secondaryText = prep.secondaryText
    val translation = prep.translation
    val displayText = prep.displayText
    val mainStyle = prep.mainStyle
    val secondaryStyle = prep.secondaryStyle
    val showSyllableRomanization = prep.showSyllableRomanization
    val displayedSecondary = prep.displayedSecondary
    val density = LocalDensity.current
    val reservedHeight =
        remember(prep, density, singleLine) {
            with(density) {
                if (singleLine) {
                    prep.mainStyle.fontSize.toDp() * 1.2f
                } else {
                    prep.mainStyle.fontSize.toDp() * 1.2f +
                        prep.secondaryStyle.fontSize.toDp() * 1.2f * prep.secondaryLineCount +
                        4.dp * prep.secondaryLineCount
                }
            }
        }
    val contentWidth =
        if (fillWidth) {
            800.dp
        } else {
            val syllableWidthPx =
                if (showSyllableRomanization && mainWords != null) {
                    measureSyllableRomanizationWidth(
                        mainText = displayText,
                        words = mainWords,
                        romanizedWords = secondaryWords,
                        romanizedText = secondaryText,
                        lyricStyle = mainStyle,
                        textMeasurer = textMeasurer,
                    )
                } else {
                    0f
                }
            val formattedText =
                if (showSyllableRomanization && mainWords != null) {
                    rememberFormattedMainText(
                        mainText = displayText,
                        words = mainWords,
                        romanizedWords = secondaryWords,
                        romanizedText = secondaryText,
                        showSyllableRomanization = true,
                        lyricStyle = mainStyle,
                        textMeasurer = textMeasurer,
                        widthPx = 4000,
                    )
                } else {
                    displayText
                }
            remember(
                formattedText,
                displayedSecondary,
                translation,
                secondaryText,
                showSyllableRomanization,
                syllableWidthPx,
                mainStyle,
                secondaryStyle,
                textMeasurer,
                density,
            ) {
                with(density) {
                    val textMaxWidth =
                        listOfNotNull(
                            formattedText to mainStyle,
                            displayedSecondary?.let { it to secondaryStyle.forDisplayedText(it) },
                            translation?.let { it to secondaryStyle.forDisplayedText(it) },
                            secondaryText
                                ?.takeIf { showSyllableRomanization }
                                ?.let { it to secondaryStyle.forDisplayedText(it) },
                        ).maxOf { (text, style) -> textMeasurer.measure(text, style).size.width }
                    maxOf(textMaxWidth, syllableWidthPx.toInt()).toDp() + 16.dp
                }
            }
        }

    Column(
        modifier =
            Modifier
                .widthIn(max = 800.dp)
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(contentWidth.coerceAtMost(800.dp)))
                .heightIn(min = reservedHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!singleLine && isWordAnimationEnabled && mainWords != null) {
            WordLevelLyrics(
                mainText = displayText,
                words = mainWords,
                isActiveLine = isActive,
                positionProvider = { currentPositionProvider() },
                adjustedEndTimeMs = lineEndTimeMs.takeIf { it != Long.MAX_VALUE },
                lyricStyle = mainStyle,
                lineColor = { color },
                expressiveAccent = textColor,
                isBackground = false,
                focusedAlpha = { 0.35f },
                alignment = alignment,
                textMeasurer = textMeasurer,
                isPlaying = isPlaying,
                animationStyle = animationStyle,
                useFixedTone = useFixedTone,
                romanizedWords = secondaryWords,
                romanizedText = secondaryText,
                showSyllableRomanization = showSyllableRomanization,
            )
        } else {
            if (singleLine) {
                val scrollState = rememberScrollState()
                val measuredText =
                    remember(displayText, mainStyle, textMeasurer) {
                        textMeasurer.measure(displayText, mainStyle)
                    }
                val activeWordStart by remember(mainWords) {
                    derivedStateOf(structuralEqualityPolicy()) {
                        val currentPosition = currentPositionProvider()
                        mainWords
                            ?.filterNot { it.isBackground }
                            ?.takeWhile { currentPosition >= (it.endTime * 1000).toLong() }
                            ?.sumOf { it.text.length + if (it.hasTrailingSpace) 1 else 0 }
                            ?: 0
                    }
                }
                LaunchedEffect(activeWordStart, measuredText, scrollState.maxValue) {
                    if (activeWordStart > 0 && activeWordStart < displayText.length) {
                        val wordX = measuredText.getBoundingBox(activeWordStart).left.toInt()
                        scrollState.animateScrollTo(wordX.coerceIn(0, scrollState.maxValue))
                    }
                }
                val isJoining = remember(displayText) { isJoiningScript(displayText) }
                if (isJoining) {
                    AlphaSafeText(
                        text = displayText,
                        style = mainStyle,
                        color = color,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.horizontalScroll(scrollState, reverseScrolling = false),
                    )
                } else {
                    Text(
                        text = displayText,
                        style = mainStyle.copy(color = color),
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.horizontalScroll(scrollState, reverseScrolling = false),
                    )
                }
            } else {
                val isJoining = remember(displayText) { isJoiningScript(displayText) }
                if (isJoining) {
                    AlphaSafeText(
                        text = displayText,
                        style = mainStyle,
                        color = color,
                        textAlign = alignment,
                        maxLines = maxLines,
                        softWrap = true,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = displayText,
                        style = mainStyle.copy(color = color),
                        textAlign = alignment,
                        maxLines = maxLines,
                        softWrap = true,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (!singleLine && displayedSecondary != null) {
            val displayedSecondaryStyle = prep.displayedSecondaryStyle ?: secondaryStyle
            val secondaryColor = color.copy(alpha = color.alpha * 0.8f)
            Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                if (isWordAnimationEnabled && secondaryWords != null) {
                    WordLevelLyrics(
                        mainText = displayedSecondary,
                        words = secondaryWords,
                        isActiveLine = isActive,
                        positionProvider = { currentPositionProvider() },
                        adjustedEndTimeMs = lineEndTimeMs.takeIf { it != Long.MAX_VALUE },
                        lyricStyle = displayedSecondaryStyle,
                        lineColor = { secondaryColor },
                        expressiveAccent = textColor,
                        isBackground = false,
                        focusedAlpha = { 0.35f },
                        alignment = alignment,
                        textMeasurer = textMeasurer,
                        isPlaying = isPlaying,
                        animationStyle = animationStyle,
                        useFixedTone = useFixedTone,
                        showSyllableRomanization = false,
                    )
                } else if (remember(displayedSecondary) { isJoiningScript(displayedSecondary) }) {
                    AlphaSafeText(
                        text = displayedSecondary,
                        style = displayedSecondaryStyle,
                        color = secondaryColor,
                        textAlign = alignment,
                        maxLines = maxLines,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = displayedSecondary,
                        style = displayedSecondaryStyle.copy(color = secondaryColor),
                        textAlign = alignment,
                        maxLines = maxLines,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (!singleLine) {
            translation?.let { translation ->
                val transStyle = prep.translationStyle ?: secondaryStyle
                val transColor = textColor.copy(alpha = if (isActive) 0.7f else 0.3f)
                if (remember(translation) { isJoiningScript(translation) }) {
                    AlphaSafeText(
                        text = translation,
                        style = transStyle,
                        color = transColor,
                        textAlign = alignment,
                        maxLines = maxLines,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                } else {
                    Text(
                        text = translation,
                        style = transStyle.copy(color = transColor),
                        textAlign = alignment,
                        maxLines = maxLines,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private class ActiveLinePrep(
    val mainWords: List<WordTimestamp>?,
    val secondaryWords: List<WordTimestamp>?,
    val animationStyle: LyricsAnimationStyle,
    val isWordAnimationEnabled: Boolean,
    val secondaryText: String?,
    val translation: String?,
    val displayText: String,
    val mainStyle: TextStyle,
    val secondaryStyle: TextStyle,
    val showSyllableRomanization: Boolean,
    val displayedSecondary: String?,
    val displayedSecondaryStyle: TextStyle?,
    val translationStyle: TextStyle?,
) {
    val secondaryLineCount: Int = (if (displayedSecondary != null) 1 else 0) + (if (translation != null) 1 else 0)

    companion object {
        fun build(
            entry: LyricsEntry,
            lyricStyle: TextStyle,
            romanizationEnabled: Boolean,
            romanizeAsMainSetting: Boolean,
            animationStyleSetting: String?,
            showTranslations: Boolean,
            syllableRomanizationSetting: Boolean,
        ): ActiveLinePrep {
            val originalWords =
                entry.words
                    ?.filter { !it.isBackground && !it.isLineTimed && it.endTime > it.startTime }
                    ?.takeIf { it.isNotEmpty() }
            val origMainText =
                originalWords?.joinToString("") { romanizedSyllableText(it) }?.trim()?.takeIf { it.isNotEmpty() }
                    ?: entry.text
            val timedRomanizedWords =
                entry.romanizedWords
                    ?.filter { !it.isBackground && !it.isLineTimed }
                    ?.takeIf { it.isNotEmpty() }
            val rawRomanized =
                if (timedRomanizedWords != null) {
                    timedRomanizedWords.joinToString("") { romanizedSyllableText(it) }.trim()
                } else {
                    entry.romanizedText?.replace('\u00A0', ' ')?.trim()
                }
            val romanized =
                rawRomanized?.takeIf {
                    romanizationEnabled &&
                        it.isNotBlank() &&
                        !isSameLyricsText(it, entry.text) &&
                        !isSameLyricsText(it, origMainText)
                }
            val romanizeAsMain = romanizeAsMainSetting && romanized != null
            val mainText = if (romanizeAsMain) romanized else entry.text
            val mainWords = if (romanizeAsMain) timedRomanizedWords else originalWords
            val secondaryWords = if (romanizeAsMain) originalWords else timedRomanizedWords
            val animationStyle = LyricsAnimationStyle.fromString(animationStyleSetting)
            val isWordAnimationEnabled =
                animationStyle != LyricsAnimationStyle.NONE &&
                    mainWords?.any { !it.isLineTimed && it.endTime > it.startTime } == true
            val secondaryText = if (romanizeAsMain) entry.text else romanized
            val translation =
                entry.translatedText?.replace('\u00A0', ' ')?.trim()?.takeIf {
                    showTranslations &&
                        it.isNotBlank() &&
                        !isSameLyricsText(it, entry.text) &&
                        !isSameLyricsText(it, origMainText) &&
                        !isSameLyricsText(it, romanized)
                }
            val displayText = mainText.orEmpty()
            val mainStyle = lyricStyle.forDisplayedText(displayText)
            val secondaryStyle = lyricStyle.copy(fontSize = lyricStyle.fontSize * 0.64f)
            val showSyllableRomanization =
                isWordAnimationEnabled &&
                    romanized != null &&
                    entry.hasTimedRomanization() &&
                    syllableRomanizationSetting
            val displayedSecondary = secondaryText?.takeUnless { showSyllableRomanization }
            return ActiveLinePrep(
                mainWords = mainWords,
                secondaryWords = secondaryWords,
                animationStyle = animationStyle,
                isWordAnimationEnabled = isWordAnimationEnabled,
                secondaryText = secondaryText,
                translation = translation,
                displayText = displayText,
                mainStyle = mainStyle,
                secondaryStyle = secondaryStyle,
                showSyllableRomanization = showSyllableRomanization,
                displayedSecondary = displayedSecondary,
                displayedSecondaryStyle = displayedSecondary?.let { secondaryStyle.forDisplayedText(it) },
                translationStyle = translation?.let { secondaryStyle.forDisplayedText(it) },
            )
        }
    }
}

internal data class LineLook(
    val alpha: Float,
    val translationAlpha: Float,
    val focusedAlpha: Float,
    val visibility: Float,
    val scale: Float,
)

private const val LINE_VISIBILITY_MS = 250

@Stable
internal class LineLookState(
    initial: LineLook,
) {
    private class Segment(
        val from: LineLook,
        val to: LineLook,
        val alphaMs: Int,
        val scaleMs: Int,
        val clock: Animatable<Float, AnimationVector1D>,
    )

    private var segment by mutableStateOf(Segment(initial, initial, 1, 1, Animatable(0f)))

    private fun fraction(
        clockMs: Float,
        durationMs: Int,
    ): Float = FastOutSlowInEasing.transform((clockMs / durationMs.coerceAtLeast(1)).coerceIn(0f, 1f))

    private fun mix(
        a: Float,
        b: Float,
        t: Float,
    ): Float = a + (b - a) * t

    val alpha: Float
        get() = segment.let { mix(it.from.alpha, it.to.alpha, fraction(it.clock.value, it.alphaMs)) }
    val translationAlpha: Float
        get() =
            segment.let {
                mix(
                    it.from.translationAlpha,
                    it.to.translationAlpha,
                    fraction(it.clock.value, it.alphaMs),
                )
            }
    val focusedAlpha: Float
        get() = segment.let { mix(it.from.focusedAlpha, it.to.focusedAlpha, fraction(it.clock.value, it.alphaMs)) }
    val visibility: Float
        get() = segment.let { mix(it.from.visibility, it.to.visibility, fraction(it.clock.value, LINE_VISIBILITY_MS)) }
    val scale: Float
        get() = segment.let { mix(it.from.scale, it.to.scale, fraction(it.clock.value, it.scaleMs)) }

    private fun current(): LineLook = LineLook(alpha, translationAlpha, focusedAlpha, visibility, scale)

    suspend fun animateTo(
        target: LineLook,
        alphaMs: Int,
        scaleMs: Int,
    ) {
        if (segment.to == target) return
        val clock = Animatable(0f)
        segment = Segment(current(), target, alphaMs, scaleMs, clock)
        val total = max(max(alphaMs, scaleMs), LINE_VISIBILITY_MS)
        withContext(FixedMotionDurationScale) {
            clock.animateTo(total.toFloat(), tween(total, easing = LinearEasing))
        }
    }
}

@Composable
internal fun InlineLyricsLine(
    entry: LyricsEntry,
    isActive: Boolean,
    signedDistance: Int = 0,
    distance: Int,
    staggerDelayMs: Long = 0L,
    currentPosition: () -> Long,
    adjustedEndTimeMs: Long?,
    isAutoScrollEnabled: Boolean,
    isLrcSynced: Boolean,
    onSeek: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    alignment: TextAlign,
    hasMultipleSingers: Boolean = false,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    textSizeMultiplier: Float = 1.0f,
    textColor: Color = Color.White,
    textMeasurer: TextMeasurer,
    isInteractive: Boolean = true,
    isPlaying: Boolean = true,
    flipShifter: LyricsScrollShifter? = null,
    flipIndex: Int = 0,
    isPlusLighter: Boolean = false,
    translationShimmer: State<Float>? = null,
    backgroundTranslationShimmer: State<Float>? = null,
) {
    val animStyle =
        remember(Settings.shared.lyricsAnimationStyle) {
            LyricsAnimationStyle.fromString(Settings.shared.lyricsAnimationStyle)
        }
    val isRomanizationEnabled = Settings.shared.lyricsRomanizationEnabled
    val hasTimedRoman = remember(entry) { entry.hasTimedRomanization() }
    val origWords =
        remember(entry.words) {
            entry.words
                ?.filter { !it.isBackground && !it.isLineTimed && it.endTime > it.startTime }
                ?.takeIf { it.isNotEmpty() }
        }
    val origMainText =
        remember(entry.text, entry.words) {
            entry.words
                ?.filter { !it.isBackground }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString("") { romanizedSyllableText(it) }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: entry.text
        }
    val rawRomanMainWords =
        remember(entry.romanizedWords) {
            entry.romanizedWords
                ?.filter { !it.isBackground && !it.isLineTimed && it.endTime > it.startTime }
                ?.takeIf { it.isNotEmpty() }
        }
    val cleanedRomanText =
        remember(entry.romanizedText, entry.romanizedWords) {
            val allRomanMain = entry.romanizedWords?.filter { !it.isBackground }?.takeIf { it.isNotEmpty() }
            if (allRomanMain != null) {
                allRomanMain.joinToString("") { romanizedSyllableText(it) }.trim()
            } else {
                entry.romanizedText?.replace('\u00A0', ' ')?.trim()
            }
        }
    val roman =
        remember(cleanedRomanText, entry.text, origMainText, isRomanizationEnabled) {
            if (isRomanizationEnabled) {
                cleanedRomanText?.takeIf {
                    it.isNotBlank() &&
                        !isSameLyricsText(it, entry.text) &&
                        !isSameLyricsText(it, origMainText)
                }
            } else {
                null
            }
        }
    val romanizeAsMain = isRomanizationEnabled && Settings.shared.lyricsRomanizeAsMain && roman != null
    val mainText = if (romanizeAsMain) roman else entry.text
    val allWords = if (romanizeAsMain) entry.romanizedWords else entry.words
    val mainWords =
        remember(allWords) {
            allWords
                ?.filter { !it.isBackground && !it.isLineTimed && it.endTime > it.startTime }
                ?.takeIf { it.isNotEmpty() }
        }
    val bgWords =
        remember(allWords) {
            allWords
                ?.filter { it.isBackground && !it.isLineTimed && it.endTime > it.startTime }
                ?.map { it.copy(text = it.text.replace("(", "").replace(")", "")) }
                ?.takeIf { it.isNotEmpty() }
        }
    val rawBgWordsForText =
        remember(allWords) {
            allWords
                ?.filter { it.isBackground }
                ?.map { it.copy(text = it.text.replace("(", "").replace(")", "")) }
                ?.takeIf { it.isNotEmpty() }
        }
    val hasBg = rawBgWordsForText != null || !entry.backgroundText.isNullOrBlank()
    val hasRealWords = !mainWords.isNullOrEmpty() || !bgWords.isNullOrEmpty()
    val isWordAnimEnabled = isLrcSynced && animStyle != LyricsAnimationStyle.NONE && hasRealWords

    val colorScheme = MaterialTheme.colorScheme
    val lyricsTextSize = Settings.shared.lyricsTextSize * textSizeMultiplier
    val lyricsLineSpacing = Settings.shared.lyricsLineSpacing

    // Word-by-word highlighting needs lower line opacity for readability.
    val unlitAlpha = if (isPlusLighter) 0.2f else 0.35f
    val targetFocusedAlpha = unlitAlpha
    val targetAlpha = if (isActive) 1f else unlitAlpha
    val targetTranslationAlpha = if (isActive) 0.5f else unlitAlpha

    val staggerDurationMs = (750 + staggerDelayMs).toInt()
    val scaleStaggerDurationMs = (800 + staggerDelayMs * 2).toInt()
    val targetScale =
        if (isActive || !isAutoScrollEnabled || isSelectionMode) {
            1.0f
        } else if (isLrcSynced) {
            when (distance) {
                0, 1 -> 0.97f
                2, 3 -> 0.93f
                4 -> 0.90f
                else -> 0.85f
            }
        } else {
            0.92f
        }
    val lineLookTarget =
        LineLook(
            alpha = targetAlpha,
            translationAlpha = targetTranslationAlpha,
            focusedAlpha = targetFocusedAlpha,
            visibility = if (isActive || isSelectionMode) 1f else 0f,
            scale = targetScale,
        )
    val lineLook = remember(entry) { LineLookState(lineLookTarget) }
    LaunchedEffect(lineLook, lineLookTarget) {
        lineLook.animateTo(lineLookTarget, staggerDurationMs, scaleStaggerDurationMs)
    }
    val lineColor = remember(textColor, lineLook) { ColorProducer { textColor.copy(alpha = lineLook.alpha) } }
    val isWordSynced = isWordAnimEnabled && !mainWords.isNullOrEmpty()
    val isActiveNow by rememberUpdatedState(isActive)
    val lineColorProvider: () -> Color =
        remember(textColor, lineLook, isWordSynced) {
            {
                val a = if (isWordSynced && isActiveNow) lineLook.focusedAlpha else lineLook.alpha
                textColor.copy(alpha = a)
            }
        }
    val textColorProvider: () -> Color = remember(textColor) { { textColor } }
    val focusedAlphaProvider: () -> Float = remember(lineLook) { { lineLook.focusedAlpha } }
    val translationColor =
        remember(textColor, lineLook) { ColorProducer { textColor.copy(alpha = lineLook.translationAlpha) } }

    val displayedMainText = mainText.orEmpty()
    val isRtlContent =
        remember(displayedMainText, entry.text) {
            isRtlText(displayedMainText) || isRtlText(entry.text)
        }
    val hasJoiningScript = remember(displayedMainText) { isJoiningScript(displayedMainText) }

    val layoutDirection = LocalLayoutDirection.current
    val isVisuallyRightAligned =
        when (alignment) {
            TextAlign.Right -> true
            TextAlign.Left -> false
            TextAlign.Center, TextAlign.Justify -> false
            TextAlign.End -> if (isRtlContent) false else layoutDirection != LayoutDirection.Rtl
            else -> if (isRtlContent) true else layoutDirection == LayoutDirection.Rtl
        }

    val visualAlignment =
        when {
            alignment == TextAlign.Center || alignment == TextAlign.Justify -> TextAlign.Center
            isVisuallyRightAligned -> TextAlign.Right
            else -> TextAlign.Left
        }

    val bodyFontFamily = MaterialTheme.typography.bodyLarge.fontFamily
    val mainStyle =
        remember(lyricsTextSize, lyricsLineSpacing, hasJoiningScript, visualAlignment, isRtlContent, bodyFontFamily) {
            TextStyle(
                fontSize = lyricsTextSize.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = (lyricsTextSize * lyricsLineSpacing).sp,
                letterSpacing = if (hasJoiningScript) 0.sp else (-0.5).sp,
                textAlign = visualAlignment,
                textDirection = if (isRtlContent) TextDirection.ContentOrRtl else TextDirection.ContentOrLtr,
                fontFamily = bodyFontFamily,
                fontFeatureSettings = "liga 0",
                textMotion = TextMotion.Animated,
                lineHeightStyle =
                    LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both,
                    ),
            )
        }

    val bgStyle =
        remember(mainStyle, lyricsTextSize, lyricsLineSpacing) {
            mainStyle.copy(
                fontSize = (lyricsTextSize * 0.62f).sp,
                lineHeight = (lyricsTextSize * 0.62f * lyricsLineSpacing).sp,
            )
        }

    val romanBgStyle =
        remember(bgStyle, lyricsTextSize, lyricsLineSpacing) {
            bgStyle.copy(
                fontSize = (lyricsTextSize * 0.48f).sp,
                lineHeight = (lyricsTextSize * 0.48f * lyricsLineSpacing).sp,
            )
        }

    val bgText =
        remember(rawBgWordsForText, entry.backgroundText) {
            rawBgWordsForText?.joinToString("") { romanizedSyllableText(it) }?.trim() ?: entry.backgroundText.orEmpty()
        }

    val bgDisplayStyle = remember(bgStyle, bgText) { bgStyle.forDisplayedText(bgText) }

    val hAlign =
        when (visualAlignment) {
            TextAlign.Center -> Alignment.CenterHorizontally
            TextAlign.Right -> if (layoutDirection == LayoutDirection.Rtl) Alignment.Start else Alignment.End
            else -> if (layoutDirection == LayoutDirection.Rtl) Alignment.End else Alignment.Start
        }

    val pivotX =
        when (visualAlignment) {
            TextAlign.Center -> 0.5f
            TextAlign.Right -> 1f
            else -> 0f
        }

    val innerFlipEnabled = !isSelectionMode
    val innerFlip = if (flipShifter != null) remember(flipShifter, flipIndex) { LyricsItemFlip() } else null

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (isSelected) {
                        Modifier
                            .background(
                                color = colorScheme.primary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(12.dp),
                            ).clip(RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    },
                ).then(
                    if (isInteractive) {
                        Modifier
                            .heightIn(min = 48.dp)
                            .combinedClickable(role = Role.Button, onClick = onSeek, onLongClick = onLongClick)
                            .semantics {
                                if (isSelectionMode) selected = isSelected
                            }
                    } else {
                        Modifier
                    },
                ).padding(horizontal = LYRICS_LINE_HORIZONTAL_PADDING, vertical = 12.dp),
        contentAlignment =
            when (visualAlignment) {
                TextAlign.Center -> Alignment.Center
                TextAlign.Right -> if (layoutDirection == LayoutDirection.Rtl) Alignment.CenterStart else Alignment.CenterEnd
                else -> if (layoutDirection == LayoutDirection.Rtl) Alignment.CenterEnd else Alignment.CenterStart
            },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val s = lineLook.scale
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(pivotX, 0.5f)
                    }.then(
                        if (hasMultipleSingers) {
                            Modifier.layout { measurable, constraints ->
                                val totalW = constraints.maxWidth
                                val sidePx = (totalW * MULTI_SINGER_PADDING_RATIO).roundToInt()
                                val centerPx = sidePx / 2
                                val (leftPx, rightPx) =
                                    when (visualAlignment) {
                                        TextAlign.Left -> 0 to sidePx
                                        TextAlign.Right -> sidePx to 0
                                        TextAlign.Center -> centerPx to centerPx
                                        else -> 0 to 0
                                    }
                                val horizInset = leftPx + rightPx
                                val childMaxW = (totalW - horizInset).coerceAtLeast(0)
                                val childMinW = (constraints.minWidth - horizInset).coerceIn(0, childMaxW)
                                val placeable =
                                    measurable.measure(constraints.copy(minWidth = childMinW, maxWidth = childMaxW))
                                layout(totalW, placeable.height) {
                                    placeable.place(leftPx, 0)
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
            horizontalAlignment = hAlign,
        ) {
            val bgMargin = 16.dp
            val bgBeforeMain =
                remember(allWords, rawBgWordsForText) {
                    val mainStart = allWords?.firstOrNull { !it.isBackground }?.startTime
                    val bgStart = rawBgWordsForText?.firstOrNull()?.startTime
                    mainStart != null && bgStart != null && bgStart < mainStart
                }

            val romanizeAsMainSetting = isRomanizationEnabled && Settings.shared.lyricsRomanizeAsMain
            val showTranslations = Settings.shared.lyricsShowTranslations
            val translated =
                remember(entry.translatedText, entry.text, origMainText, roman, showTranslations) {
                    entry.translatedText?.replace('\u00A0', ' ')?.trim()?.takeIf {
                        showTranslations &&
                            it.isNotBlank() &&
                            !isSameLyricsText(it, entry.text) &&
                            !isSameLyricsText(it, origMainText) &&
                            !isSameLyricsText(it, roman)
                    }
                }

            val romanMainWords = remember(roman, rawRomanMainWords) { if (roman != null) rawRomanMainWords else null }
            val romanBgWords =
                remember(roman, entry.romanizedWords) {
                    entry.romanizedWords
                        ?.filter { it.isBackground && !it.isLineTimed && it.endTime > it.startTime }
                        ?.map { it.copy(text = it.text.replace("(", "").replace(")", "")) }
                        ?.takeIf { it.isNotEmpty() }
                }
            val rawRomanBgWordsForText =
                remember(roman, entry.romanizedWords) {
                    entry.romanizedWords
                        ?.filter { it.isBackground }
                        ?.map { it.copy(text = it.text.replace("(", "").replace(")", "")) }
                        ?.takeIf { it.isNotEmpty() }
                }
            val romanMainText = roman.orEmpty()
            val romanBgText =
                remember(rawRomanBgWordsForText, bgText) {
                    rawRomanBgWordsForText
                        ?.joinToString("") { romanizedSyllableText(it) }
                        ?.trim()
                        ?.takeIf { !isSameLyricsText(it, bgText) }
                        ?: ""
                }
            val backgroundTranslated =
                remember(entry.backgroundTranslatedText, bgText, romanBgText, showTranslations) {
                    entry.backgroundTranslatedText?.replace('\u00A0', ' ')?.trim()?.takeIf {
                        showTranslations && it.isNotBlank() &&
                            !isSameLyricsText(it, bgText) && !isSameLyricsText(it, romanBgText)
                    }
                }
            val backgroundTranslationStyle =
                remember(romanBgStyle, backgroundTranslated) {
                    backgroundTranslated?.let { romanBgStyle.forDisplayedText(it) }
                }
            val origDisplayStyle = remember(bgStyle, entry.text) { bgStyle.forDisplayedText(entry.text) }
            val romanMainStyle = remember(bgStyle, romanMainText) { bgStyle.forDisplayedText(romanMainText) }
            val romanBgDisplayStyle = remember(romanBgStyle, romanBgText) { romanBgStyle.forDisplayedText(romanBgText) }
            val translatedStyle = remember(bgStyle, translated) { translated?.let { bgStyle.forDisplayedText(it) } }

            val isSyllableRomanizationActive =
                isWordAnimEnabled &&
                    !mainWords.isNullOrEmpty() &&
                    roman != null &&
                    hasTimedRoman &&
                    Settings.shared.lyricsSyllableRomanizationEnabled

            val showRomanBg = !romanizeAsMainSetting && roman != null && romanBgText.isNotBlank()

            val mainContent: @Composable () -> Unit = {
                Box(Modifier.lyricsTranslationShimmer(translationShimmer, colorScheme.primary)) {
                    if (isWordAnimEnabled && !mainWords.isNullOrEmpty()) {
                        WordLevelLyrics(
                            mainText = mainText,
                            words = mainWords,
                            isActiveLine = isActive,
                            positionProvider = currentPosition,
                            adjustedEndTimeMs = adjustedEndTimeMs,
                            lyricStyle = mainStyle,
                            lineColor = lineColorProvider,
                            expressiveAccent = textColor,
                            isBackground = false,
                            focusedAlpha = focusedAlphaProvider,
                            alignment = visualAlignment,
                            textMeasurer = textMeasurer,
                            isPlaying = isPlaying,
                            animationStyle = animStyle,
                            romanizedWords = if (romanizeAsMain) origWords else romanMainWords,
                            romanizedText = if (romanizeAsMain) entry.text else roman,
                            showSyllableRomanization = isSyllableRomanizationActive,
                            prewarmMasks = isActive || signedDistance in -1..1,
                        )
                    } else if (hasJoiningScript) {
                        AnimatedAlphaSafeText(
                            text = mainText,
                            style = mainStyle,
                            textColor = textColor,
                            alpha = { lineLook.alpha },
                            lineColor = lineColor,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = mainText,
                            style = mainStyle,
                            color = lineColor,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            val bgContent: @Composable () -> Unit = {
                if ((hasBg && bgText.isNotBlank()) || showRomanBg) {
                    LyricsBgReveal(
                        visible = isActive || isSelectionMode,
                        bgBeforeMain = bgBeforeMain,
                        alignment = visualAlignment,
                        onBgMeasured = { measuredH, reportedH ->
                            flipShifter?.updateBgHeight(flipIndex, measuredH, reportedH)
                        },
                        onLayoutShift = { flipShifter?.recordLayoutShift(flipIndex, it) },
                        mainTranslationY = {
                            if (innerFlip != null && innerFlipEnabled && flipShifter != null) {
                                flipShifter.translationFor(innerFlip)
                            } else {
                                0f
                            }
                        },
                        durationMs = { flipShifter?.scrollDurationMs ?: 400L },
                        zeta = { flipShifter?.scrollZeta ?: SPRING_ZETA_DEFAULT },
                        isStaging = { flipShifter?.isStaging == true },
                        staggerDelayMs = { flipShifter?.delayMsFor(flipIndex) ?: 0 },
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .padding(
                                        top = if (bgBeforeMain) 0.dp else bgMargin,
                                        bottom = if (bgBeforeMain) bgMargin else 0.dp,
                                    ).graphicsLayer {
                                        alpha = (0.8f * lineLook.visibility).coerceAtLeast(0.65f)
                                        clip = false
                                    },
                        ) {
                            if (hasBg && bgText.isNotBlank()) {
                                Box(Modifier.lyricsTranslationShimmer(backgroundTranslationShimmer, colorScheme.primary)) {
                                    if (isWordAnimEnabled && !bgWords.isNullOrEmpty()) {
                                        WordLevelLyrics(
                                            mainText = bgText,
                                            words = bgWords,
                                            isActiveLine = isActive,
                                            positionProvider = currentPosition,
                                            adjustedEndTimeMs = adjustedEndTimeMs,
                                            lyricStyle = bgDisplayStyle,
                                            lineColor = lineColorProvider,
                                            expressiveAccent = textColor,
                                            isBackground = true,
                                            focusedAlpha = focusedAlphaProvider,
                                            alignment = visualAlignment,
                                            textMeasurer = textMeasurer,
                                            isPlaying = isPlaying,
                                            animationStyle = animStyle,
                                        )
                                    } else {
                                        Text(
                                            text = bgText,
                                            style = bgDisplayStyle,
                                            color = lineColor,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                            if (showRomanBg) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(top = if (hasBg && bgText.isNotBlank()) 4.dp else 0.dp),
                                ) {
                                    if (isWordAnimEnabled && !romanBgWords.isNullOrEmpty()) {
                                        WordLevelLyrics(
                                            mainText = romanBgText,
                                            words = romanBgWords,
                                            isActiveLine = isActive,
                                            positionProvider = currentPosition,
                                            adjustedEndTimeMs = adjustedEndTimeMs,
                                            lyricStyle = romanBgDisplayStyle,
                                            lineColor = lineColorProvider,
                                            expressiveAccent = textColor,
                                            isBackground = true,
                                            focusedAlpha = focusedAlphaProvider,
                                            alignment = visualAlignment,
                                            textMeasurer = textMeasurer,
                                            animationStyle = animStyle,
                                            isPlaying = isPlaying,
                                        )
                                    } else {
                                        Text(
                                            text = romanBgText,
                                            style = romanBgDisplayStyle,
                                            color = lineColor,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                            if (backgroundTranslated != null) {
                                Text(
                                    text = backgroundTranslated,
                                    style = backgroundTranslationStyle ?: romanBgStyle,
                                    color = translationColor,
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
            val romanMainContent: @Composable () -> Unit = {
                if (!isSyllableRomanizationActive) {
                    if (romanizeAsMainSetting && roman != null) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                        ) {
                            if (isWordAnimEnabled && !origWords.isNullOrEmpty()) {
                                WordLevelLyrics(
                                    mainText = entry.text,
                                    words = origWords,
                                    isActiveLine = isActive,
                                    positionProvider = currentPosition,
                                    adjustedEndTimeMs = adjustedEndTimeMs,
                                    lyricStyle = origDisplayStyle,
                                    lineColor = lineColorProvider,
                                    expressiveAccent = textColor,
                                    isBackground = false,
                                    focusedAlpha = focusedAlphaProvider,
                                    alignment = visualAlignment,
                                    textMeasurer = textMeasurer,
                                    animationStyle = animStyle,
                                    isPlaying = isPlaying,
                                )
                            } else if (remember(entry.text) { isJoiningScript(entry.text) }) {
                                AnimatedAlphaSafeText(
                                    text = entry.text,
                                    style = origDisplayStyle,
                                    textColor = textColor,
                                    alpha = { lineLook.alpha },
                                    lineColor = lineColor,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Text(
                                    text = entry.text,
                                    style = origDisplayStyle,
                                    color = lineColor,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else if (roman != null && romanMainText.isNotBlank()) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                        ) {
                            if (isWordAnimEnabled && !romanMainWords.isNullOrEmpty()) {
                                WordLevelLyrics(
                                    mainText = romanMainText,
                                    words = romanMainWords,
                                    isActiveLine = isActive,
                                    positionProvider = currentPosition,
                                    adjustedEndTimeMs = adjustedEndTimeMs,
                                    lyricStyle = romanMainStyle,
                                    lineColor = lineColorProvider,
                                    expressiveAccent = textColor,
                                    isBackground = false,
                                    focusedAlpha = focusedAlphaProvider,
                                    alignment = visualAlignment,
                                    textMeasurer = textMeasurer,
                                    animationStyle = animStyle,
                                    isPlaying = isPlaying,
                                )
                            } else {
                                Text(
                                    text = romanMainText,
                                    style = romanMainStyle,
                                    color = lineColor,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            val mainFlipModifier =
                if (flipShifter != null && innerFlip != null) {
                    Modifier
                        .onPlaced {
                            flipShifter.onPlaced(innerFlip, flipIndex, it.positionInParent().y, innerFlipEnabled)
                        }.graphicsLayer {
                            translationY = if (innerFlipEnabled) flipShifter.translationFor(innerFlip) else 0f
                        }
                } else {
                    Modifier
                }
            val mainTranslationContent: @Composable () -> Unit = {
                if (translated != null) {
                    Text(
                        text = translated,
                        style = translatedStyle ?: bgStyle,
                        color = translationColor,
                        textAlign = visualAlignment,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                    )
                }
            }

            if (bgBeforeMain) {
                bgContent()
                Column(horizontalAlignment = hAlign, modifier = mainFlipModifier) {
                    mainContent()
                    romanMainContent()
                    mainTranslationContent()
                }
            } else {
                Column(horizontalAlignment = hAlign, modifier = mainFlipModifier) {
                    mainContent()
                    romanMainContent()
                    mainTranslationContent()
                }
                bgContent()
            }
        }
    }
}
