/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cd_playback_position
import com.metrolist.shared.generated.resources.live
import com.metrolist.shared.generated.resources.playback_position_time
import com.metrolist.shared.playback.PendingSeekPosition
import com.metrolist.shared.playback.interpolatePlaybackPosition
import com.metrolist.shared.ui.components.LiveBadge
import com.metrolist.shared.ui.components.sliders.PlaybackProgressSlider
import com.metrolist.shared.ui.components.sliders.collectPlaybackDisplayState
import com.metrolist.shared.ui.components.sliders.progressAtPixel
import com.metrolist.shared.ui.components.sliders.rememberPendingSeekPosition
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.formatDuration
import com.metrolist.shared.viewmodels.PlayerViewModel
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerProgressSlider(
    viewModel: PlayerViewModel,
    duration: Long,
    isPlaying: Boolean,
    textBackgroundColor: Color,
    canSeek: Boolean,
    isCasting: Boolean,
    songId: String? = null,
    showTimers: Boolean = true,
    progressColor: Color? = null,
    timerTextStyle: TextStyle = MaterialTheme.typography.labelMedium,
    isLive: Boolean = false,
) {
    val active = LocalPlayerContentActive.current
    val positionActive = LocalPlayerPositionActive.current
    var trackWidthPx by remember { mutableIntStateOf(0) }
    val progressWidthPx = trackWidthPx
    val currentPosition by key(songId) { viewModel.currentPosition.collectWhilePlayerActive(positionActive) }
    val playbackSpeed by viewModel.playbackSpeed.collectWhilePlayerActive(positionActive)
    val interpolationSpeed = if (isCasting) 1f else playbackSpeed
    val isPlaybackLoading by viewModel.isPlaybackLoading.collectWhilePlayerActive(positionActive)
    val isProgressAnimating = active && isPlaying && !isPlaybackLoading && !isLive
    val latestPosition by rememberUpdatedState(currentPosition)
    val latestSpeed by rememberUpdatedState(interpolationSpeed)
    val latestDuration by rememberUpdatedState(duration)
    val displayedPosition by
        produceState(
            currentPosition,
            songId,
            duration,
            interpolationSpeed,
            isProgressAnimating,
            currentPosition.takeUnless { isProgressAnimating },
        ) {
            value = currentPosition
            if (isProgressAnimating) {
                var sampledPosition = currentPosition
                var sampleFrameNanos = 0L
                while (true) {
                    withFrameNanos { frameNanos ->
                        val newSample = latestPosition
                        if (newSample != sampledPosition || sampleFrameNanos == 0L) {
                            sampledPosition = newSample
                            sampleFrameNanos = frameNanos
                        }
                        value =
                            interpolatePlaybackPosition(
                                sampledPositionMs = sampledPosition,
                                elapsedRealtimeMs = (frameNanos - sampleFrameNanos) / 1_000_000L,
                                playbackSpeed = latestSpeed,
                                durationMs = latestDuration,
                            )
                    }
                }
            }
        }
    // Read the rendered clock when the slider requests it, including the frame that acknowledges a seek.
    val progress = {
        if (isLive) {
            1f
        } else if (duration > 0L) {
            progressAtPixel(displayedPosition.toFloat() / duration, progressWidthPx)
        } else {
            0f
        }
    }
    val currentSecond by key(songId) {
        viewModel.currentPosition.collectPlaybackDisplayState(positionActive) { it.coerceAtLeast(0L) / 1_000L }
    }
    val bufferedProgress by viewModel.bufferedProgress.collectPlaybackDisplayState(positionActive) {
        progressAtPixel(it, progressWidthPx)
    }
    var sliderValue by remember(songId, duration, isLive) { mutableFloatStateOf(0f) }
    var isDraggingSlider by remember(songId, duration, isLive) { mutableStateOf(false) }
    var pendingSeek by rememberPendingSeekPosition(
        songId = songId,
        durationMs = duration,
        enabled = canSeek && !isLive && duration > 0L,
        position = { currentPosition },
        renderedPosition = { displayedPosition },
    )
    val hapticManager = rememberMetrolistHaptics()
    val displayedSecond =
        when {
            isDraggingSlider -> (sliderValue.toDouble() * duration).toLong() / 1_000L
            pendingSeek != null -> pendingSeek!!.targetMs / 1_000L
            else -> currentSecond
        }
    val elapsedText = remember(displayedSecond) { formatDuration(displayedSecond * 1_000L) }
    val durationText = remember(duration) { formatDuration(duration) }
    val positionLabel = stringResource(Res.string.cd_playback_position)
    val liveLabel = stringResource(Res.string.live)
    val timeDescription =
        if (isLive) {
            stringResource(Res.string.playback_position_time, elapsedText, liveLabel)
        } else {
            stringResource(Res.string.playback_position_time, elapsedText, durationText)
        }

    key(songId, isLive) {
        PlaybackProgressSlider(
            modifier =
                Modifier
                    .onSizeChanged { trackWidthPx = it.width }
                    .semantics {
                        contentDescription = positionLabel
                        stateDescription = timeDescription
                    },
            // Only seed in composition; subsequent samples update SliderState through the provider.
            value = if (isLive) 1f else remember { progress() },
            valueProvider = {
                if (isLive) {
                    1f
                } else if (isDraggingSlider) {
                    sliderValue
                } else {
                    pendingSeek?.let { it.targetMs.toFloat() / duration } ?: progress()
                }
            },
            onValueChange = { newValue ->
                if (canSeek && !isLive && duration > 0L) {
                    pendingSeek = null
                    isDraggingSlider = true
                    sliderValue = newValue
                }
            },
            onValueChangeFinished = {
                if (isDraggingSlider && canSeek && !isLive && duration > 0L) {
                    Logger.d("Haptics", "Trigger PlayerScreen slider release value=$sliderValue")
                    hapticManager.perform(HapticEvent.CONFIRM)
                    val seek =
                        PendingSeekPosition(
                            targetMs = (sliderValue.coerceIn(0f, 1f).toDouble() * duration).toLong(),
                            previousPositionMs = viewModel.currentPosition.value,
                        )
                    if (viewModel.seekTo(sliderValue, duration, songId)) pendingSeek = seek
                }
                isDraggingSlider = false
            },
            enabled = canSeek && !isLive && duration > 0L,
            showThumb = true,
            isPlaying = isPlaying && !isPlaybackLoading,
            bufferedFraction = if (isLive) 1f else bufferedProgress,
            animate = active,
            colors =
                if (isLive) {
                    val activeColor = progressColor ?: MaterialTheme.colorScheme.primary
                    val inactiveColor = activeColor.copy(alpha = 0.24f)
                    SliderDefaults.colors(
                        thumbColor = activeColor,
                        activeTrackColor = activeColor,
                        inactiveTrackColor = inactiveColor,
                        disabledThumbColor = activeColor,
                        disabledActiveTrackColor = activeColor,
                        disabledInactiveTrackColor = inactiveColor,
                    )
                } else {
                    val activeColor = progressColor ?: MaterialTheme.colorScheme.primary
                    val inactive = activeColor.copy(alpha = activeColor.alpha * (77f / 255f))
                    SliderDefaults.colors(
                        thumbColor = activeColor,
                        activeTrackColor = activeColor,
                        inactiveTrackColor = inactive,
                        disabledThumbColor = activeColor,
                        disabledActiveTrackColor = activeColor,
                        disabledInactiveTrackColor = inactive,
                    )
                },
        )
    }

    if (showTimers) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isLive) {
                LiveBadge()
                Text(
                    text = elapsedText,
                    style = timerTextStyle,
                    color = textBackgroundColor.copy(alpha = 0.75f),
                )
            } else {
                Text(
                    text = elapsedText,
                    style = timerTextStyle,
                    color = textBackgroundColor.copy(alpha = 0.75f),
                )
                Text(
                    text = durationText,
                    style = timerTextStyle,
                    color = textBackgroundColor.copy(alpha = 0.75f),
                )
            }
        }
    }
}
