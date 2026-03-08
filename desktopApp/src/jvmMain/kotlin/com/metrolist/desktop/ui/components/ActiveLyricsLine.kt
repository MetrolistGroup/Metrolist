/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.ActiveLyricsLineContent
import com.metrolist.shared.ui.components.lyrics.rememberCaptionLyricsPositionState

@Composable
fun ActiveLyricsLine(modifier: Modifier = Modifier) {
    val showLyricsOnPlayer = Settings.shared.showLyricsOnPlayer || PlayerState.isSubtitleActive
    val lyricsDrawerOpen = UiState.showLyricsDrawer
    val currentTrack = PlayerState.currentTrack
    LaunchedEffect(
        showLyricsOnPlayer,
        PlayerState.isSubtitleActive,
        currentTrack?.id,
        Settings.shared.lyricsProviderOrder,
        Settings.shared.lyricsEnabledProviders,
    ) {
        if (showLyricsOnPlayer && currentTrack != null) {
            LyricsState.fetchLyrics(currentTrack)
        }
    }

    val lyrics = LyricsState.currentLyricsEntries
    if (!showLyricsOnPlayer || !LyricsUtils.hasPlaybackTiming(lyrics)) return

    val castState by PlayerState.castManager.state.collectAsState()
    val isPlaying = PlayerState.isPlaying
    val timingOffsetMs = LyricsState.currentLyricsOffsetMs

    val lyricsPositionState =
        rememberCaptionLyricsPositionState(
            lyrics = lyrics,
            currentPositionFlow = PlayerState.playbackPosition,
            positionReader = PlayerState::currentMediaPositionMs,
            playbackSpeed =
                PlayerState.player.playbackSpeed
                    .collectAsState()
                    .value,
            isPlaying = isPlaying,
            songId = PlayerState.currentTrack?.id,
            timingOffsetMs = timingOffsetMs,
            isCasting = castState.isConnected,
            enabled = !UiState.videoFullscreenObscuresPlayer,
        )
    val currentLineIndex by remember(lyrics, lyricsPositionState) {
        derivedStateOf(structuralEqualityPolicy()) { LyricsUtils.findCurrentLineIndex(lyrics, lyricsPositionState.value) }
    }

    val currentLine = lyrics.getOrNull(currentLineIndex)
    val nextLine = lyrics.getOrNull(currentLineIndex + 1)

    val lineEndTime =
        currentLine?.let {
            LyricsUtils.getLineEndTime(it, nextLine)
        } ?: 0L

    val isLineVisible by remember(lyrics, currentLineIndex, currentLine, nextLine, lineEndTime, lyricsPositionState, lyricsDrawerOpen) {
        derivedStateOf(structuralEqualityPolicy()) {
            val lyricsPosition = lyricsPositionState.value
            val inSignificantGap =
                currentLine != null && nextLine != null &&
                    lyricsPosition > lineEndTime && (nextLine.time - lineEndTime) > 5000L

            val afterLastLine =
                currentLine != null && nextLine == null &&
                    lyricsPosition > lineEndTime + 2000L

            val isBeforeFirstLine =
                currentLineIndex == -1 && (lyrics.firstOrNull()?.time ?: 0L) > 5000L && lyricsPosition < (lyrics.firstOrNull()?.time ?: 0L)

            !lyricsDrawerOpen && currentLine != null && !inSignificantGap && !afterLastLine && !isBeforeFirstLine
        }
    }

    AnimatedVisibility(
        visible = isLineVisible,
        enter =
            expandVertically(
                animationSpec =
                    tween(
                        durationMillis = 400,
                        easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f),
                    ),
            ) +
                fadeIn(
                    animationSpec =
                        tween(
                            durationMillis = 350,
                            easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f),
                        ),
                ),
        exit =
            shrinkVertically(
                animationSpec =
                    tween(
                        durationMillis = 300,
                        easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f),
                    ),
            ) +
                fadeOut(
                    animationSpec =
                        tween(
                            durationMillis = 200,
                            easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f),
                        ),
                ),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val style =
                MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    textAlign = LyricsUtils.textAlignment(Settings.shared.activeLyricsTextPosition),
                )
            val reservedLineHeight = with(LocalDensity.current) { style.lineHeight.toDp() * 2 }
            Surface(
                color = Color.Black.copy(alpha = 0.3f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = currentLine to lineEndTime,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400)))
                                .using(null)
                        },
                        contentAlignment = Alignment.Center,
                        label = "activeLyricLineSwitch",
                        modifier = Modifier.sizeIn(minHeight = reservedLineHeight).wrapContentHeight(Alignment.CenterVertically),
                    ) { (line, displayedLineEndTime) ->
                        if (line != null) {
                            val isActive by remember(line, displayedLineEndTime, lyricsPositionState) {
                                derivedStateOf(structuralEqualityPolicy()) { lyricsPositionState.value in line.time..displayedLineEndTime }
                            }
                            ActiveLyricsLineContent(
                                entry = line,
                                isActive = isActive,
                                positionProvider = { lyricsPositionState.value },
                                lineEndTimeMs = displayedLineEndTime,
                                isPlaying = isPlaying,
                                textColor = Color.White,
                                lyricStyle = style,
                                textMeasurer = rememberTextMeasurer(),
                                maxLines = 2,
                                alignment = LyricsUtils.textAlignment(Settings.shared.activeLyricsTextPosition),
                                fillWidth = false,
                            )
                        }
                    }
                }
            }
        }
    }
}
