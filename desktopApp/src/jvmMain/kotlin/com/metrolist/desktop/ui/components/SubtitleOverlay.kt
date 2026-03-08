package com.metrolist.desktop.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.ActiveLyricsLineContent
import kotlin.math.roundToInt

@Composable
fun SubtitleOverlay(
    lyricsPositionState: State<Long>,
    offset: IntOffset,
    onOffsetChange: (IntOffset) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 120.dp,
) {
    val latestOffset by rememberUpdatedState(offset)
    val latestOnOffsetChange by rememberUpdatedState(onOffsetChange)
    val lyrics = LyricsState.currentLyricsEntries
    if (!LyricsUtils.hasPlaybackTiming(lyrics)) return

    val currentLineIndex by remember(lyrics, lyricsPositionState) {
        derivedStateOf(structuralEqualityPolicy()) { LyricsUtils.findCurrentLineIndex(lyrics, lyricsPositionState.value) }
    }
    val currentLine = lyrics.getOrNull(currentLineIndex)
    val nextLine = lyrics.getOrNull(currentLineIndex + 1)

    val lineEndTime =
        if (currentLine != null) {
            LyricsUtils.getLineEndTime(currentLine, nextLine, LyricsUtils.FINAL_DYNAMIC_LINE_DURATION_MS)
        } else {
            0L
        }

    val isVisible by remember(lyrics, currentLineIndex, currentLine, nextLine, lineEndTime, lyricsPositionState) {
        derivedStateOf(structuralEqualityPolicy()) {
            val lyricsPosition = lyricsPositionState.value
            val inSignificantGap =
                currentLine != null &&
                    nextLine != null &&
                    lyricsPosition > lineEndTime &&
                    (nextLine.time - lineEndTime) > 5000L

            val afterLastLine =
                currentLine != null &&
                    nextLine == null &&
                    lyricsPosition > lineEndTime + 2000L

            val isBeforeFirstLine =
                currentLineIndex == -1 &&
                    (lyrics.firstOrNull()?.time ?: 0L) > 5000L &&
                    lyricsPosition < (lyrics.firstOrNull()?.time ?: 0L)

            currentLine != null && !inSignificantGap && !afterLastLine && !isBeforeFirstLine
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(bottom = bottomPadding.coerceAtLeast(0.dp), start = 32.dp, end = 32.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier =
                Modifier
                    .offset { offset }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            latestOnOffsetChange(
                                IntOffset(
                                    (latestOffset.x + dragAmount.x).roundToInt(),
                                    (latestOffset.y + dragAmount.y).roundToInt(),
                                ),
                            )
                        }
                    },
        ) {
            Surface(
                color = lerp(MaterialTheme.colorScheme.secondaryContainer, Color.Black, 0.25f).copy(alpha = 0.88f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.wrapContentSize(Alignment.Center).widthIn(min = 160.dp, max = 800.dp),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = currentLine to lineEndTime,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(200)) + slideInVertically(initialOffsetY = { it / 4 }))
                                .togetherWith(fadeOut(animationSpec = tween(200)))
                        },
                        label = "subtitleContentSwitch",
                    ) { (line, displayedLineEndTime) ->
                        if (line != null) {
                            val style =
                                MaterialTheme.typography.titleMedium.copy(
                                    fontSize = Settings.shared.subtitleTextSize.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = LyricsUtils.textAlignment(Settings.shared.activeLyricsTextPosition),
                                )
                            val isActive by remember(line, displayedLineEndTime, lyricsPositionState) {
                                derivedStateOf(structuralEqualityPolicy()) { lyricsPositionState.value in line.time..displayedLineEndTime }
                            }
                            ActiveLyricsLineContent(
                                entry = line,
                                isActive = isActive,
                                positionProvider = { lyricsPositionState.value },
                                lineEndTimeMs = displayedLineEndTime,
                                isPlaying = PlayerState.isPlaying,
                                textColor = Color.White,
                                lyricStyle = style,
                                textMeasurer = rememberTextMeasurer(),
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
