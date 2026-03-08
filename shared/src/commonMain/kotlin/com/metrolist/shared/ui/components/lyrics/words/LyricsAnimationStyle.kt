/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components.lyrics.words

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.metrolist.shared.model.WordTimestamp
import com.metrolist.shared.settings.Settings

enum class LyricsAnimationStyle(
    val id: String,
    val label: String,
) {
    APPLE("Apple", "Apple"),
    BRACCATO("Braccato", "Braccato"),
    EXPRESSIVE("Expressive", "Expressive"),
    SIMPLE("Simple", "Simple"),
    NONE("None", "None"),
    ;

    companion object {
        fun fromString(value: String?): LyricsAnimationStyle {
            if (value == null) return APPLE
            return when (value.uppercase()) {
                "APPLE" -> APPLE
                "BRACCATO" -> BRACCATO
                "EXPRESSIVE", "GLOW", "SLIDE" -> EXPRESSIVE
                "SIMPLE", "FADE", "KARAOKE" -> SIMPLE
                "NONE" -> NONE
                else -> APPLE
            }
        }
    }
}

@Composable
fun WordLevelLyrics(
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
    animationStyle: LyricsAnimationStyle = LyricsAnimationStyle.fromString(Settings.shared.lyricsAnimationStyle),
    romanizedWords: List<WordTimestamp>? = null,
    romanizedText: String? = null,
    showSyllableRomanization: Boolean =
        Settings.shared.lyricsRomanizationEnabled &&
            Settings.shared.lyricsSyllableRomanizationEnabled,
    prewarmMasks: Boolean = isActiveLine,
    useFixedTone: Boolean = true,
) {
    when (animationStyle) {
        LyricsAnimationStyle.APPLE -> {
            AppleWordAnimation(
                mainText = mainText,
                words = words,
                isActiveLine = isActiveLine,
                positionProvider = positionProvider,
                adjustedEndTimeMs = adjustedEndTimeMs,
                lyricStyle = lyricStyle,
                lineColor = lineColor,
                expressiveAccent = expressiveAccent,
                isBackground = isBackground,
                focusedAlpha = focusedAlpha,
                alignment = alignment,
                textMeasurer = textMeasurer,
                isPlaying = isPlaying,
                romanizedWords = romanizedWords,
                romanizedText = romanizedText,
                showSyllableRomanization = showSyllableRomanization,
                prewarmMasks = prewarmMasks,
            )
        }

        LyricsAnimationStyle.BRACCATO -> {
            BraccatoWordAnimation(
                mainText = mainText,
                words = words,
                isActiveLine = isActiveLine,
                positionProvider = positionProvider,
                adjustedEndTimeMs = adjustedEndTimeMs,
                lyricStyle = lyricStyle,
                lineColor = lineColor,
                expressiveAccent = expressiveAccent,
                isBackground = isBackground,
                focusedAlpha = focusedAlpha,
                alignment = alignment,
                textMeasurer = textMeasurer,
                isPlaying = isPlaying,
                romanizedWords = romanizedWords,
                romanizedText = romanizedText,
                showSyllableRomanization = showSyllableRomanization,
                prewarmMasks = prewarmMasks,
            )
        }

        LyricsAnimationStyle.EXPRESSIVE -> {
            ExpressiveWordAnimation(
                useFixedTone = useFixedTone,
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
                prewarmMasks = prewarmMasks,
            )
        }

        LyricsAnimationStyle.SIMPLE -> {
            SimpleWordAnimation(
                mainText = mainText,
                words = words,
                isActiveLine = isActiveLine,
                positionProvider = positionProvider,
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
        }

        LyricsAnimationStyle.NONE -> {}
    }
}
