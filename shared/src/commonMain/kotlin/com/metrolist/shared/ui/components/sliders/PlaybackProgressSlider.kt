/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components.sliders

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.SliderStyle
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.playback.retainPlaybackKeyFocus

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaybackProgressSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showThumb: Boolean = true,
    style: SliderStyle = Settings.shared.sliderStyle,
    colors: SliderColors = SliderDefaults.colors(),
    bufferedFraction: Float = 0f,
    animate: Boolean = true,
    trackThicknessDp: Float = Settings.shared.sliderTrackThicknessDp,
    valueProvider: () -> Float = { value },
) {
    val interactionSource = remember { MutableInteractionSource() }

    val latestValueProvider by rememberUpdatedState(valueProvider)
    val sliderState =
        rememberSliderState(
            value =
                value
                    .takeIf {
                        it.isFinite()
                    }?.coerceIn(0f, 1f) ?: 0f,
        )
    LaunchedEffect(sliderState) {
        snapshotFlow { latestValueProvider() }.collect { position ->
            sliderState.value = position.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        }
    }
    LaunchedEffect(value) {
        sliderState.value = value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    }

    val focused by interactionSource.collectIsFocusedAsState()
    val isTv = LocalIsTv.current
    val focusColor = MaterialTheme.colorScheme.primary
    if (style == SliderStyle.DEFAULT) {
        if (!showThumb) {
            Slider(
                state = sliderState,
                modifier =
                    modifier
                        .retainPlaybackKeyFocus()
                        .fillMaxWidth()
                        .then(if (isTv && focused) Modifier.border(3.dp, focusColor, RoundedCornerShape(8.dp)) else Modifier),
                enabled = enabled,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = colors,
                interactionSource = interactionSource,
                thumb = { Spacer(Modifier.size(0.dp)) },
            )
        } else {
            Slider(
                state = sliderState,
                modifier =
                    modifier
                        .retainPlaybackKeyFocus()
                        .fillMaxWidth()
                        .then(if (isTv && focused) Modifier.border(3.dp, focusColor, RoundedCornerShape(8.dp)) else Modifier),
                enabled = enabled,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = colors,
                interactionSource = interactionSource,
            )
        }
        return
    }

    val trackThickness = trackThicknessDp.coerceIn(2f, 10f).dp
    val dragging by interactionSource.collectIsDraggedAsState()
    val activeColor = if (enabled) colors.activeTrackColor else colors.disabledActiveTrackColor
    val inactiveColor = activeColor.copy(alpha = activeColor.alpha * (77f / 255f))
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val motionEnabled = animate && motionScale > 0f

    Slider(
        state = sliderState,
        modifier =
            modifier
                .retainPlaybackKeyFocus()
                .fillMaxWidth()
                .height(48.dp)
                .then(if (focused && !isTv) Modifier.border(1.dp, activeColor) else Modifier)
                .drawWithContent {
                    drawContent()
                    if (isTv && focused) {
                        val fraction = if (layoutDirection == LayoutDirection.Rtl) 1f - sliderState.value else sliderState.value
                        drawCircle(
                            color = focusColor,
                            radius = 12.dp.toPx(),
                            center = Offset(size.width * fraction, center.y),
                            style = Stroke(3.dp.toPx()),
                        )
                    }
                },
        enabled = enabled,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interactionSource,
        thumb = { Spacer(Modifier.size(0.dp)) },
        track = { state ->
            when (style) {
                SliderStyle.SQUIGGLY -> {
                    SquigglyProgress(
                        progress = { state.value },
                        bufferedFraction = bufferedFraction,
                        color = activeColor,
                        isPlaying = isPlaying,
                        isDragging = dragging,
                        animate = motionEnabled,
                        thickness = trackThickness,
                        showThumb = showThumb,
                    )
                }

                SliderStyle.WAVY, SliderStyle.LINEAR_WAVY -> {
                    // Ported from Metrolist's WavySlider: spring amplitude and 8dp round thumb.
                    val amplitude by animateFloatAsState(
                        targetValue = if (isPlaying) 1f else 0f,
                        animationSpec = if (animate) ProgressIndicatorDefaults.ProgressAnimationSpec else snap(),
                        label = "amplitude",
                    )
                    val stroke = with(LocalDensity.current) { Stroke(width = trackThickness.toPx(), cap = StrokeCap.Round) }
                    val thumbRadius = maxOf(8.dp, trackThickness)
                    val waveHeight =
                        WavyProgressIndicatorDefaults.LinearContainerHeight *
                            (trackThickness / MaterialWavyStrokeWidth)
                    Box(
                        Modifier.fillMaxWidth().height(maxOf(waveHeight, thumbRadius * 2)),
                        contentAlignment = Alignment.Center,
                    ) {
                        WavyProgress(
                            progress = { state.value },
                            bufferedFraction = bufferedFraction,
                            amplitude = amplitude,
                            color = activeColor,
                            trackColor = inactiveColor,
                            stroke = stroke,
                            gapSize = WavyProgressIndicatorDefaults.LinearIndicatorTrackGapSize + if (showThumb) thumbRadius else 0.dp,
                            moving = motionEnabled && isPlaying && !dragging,
                            thumbRadius = if (showThumb) thumbRadius else 0.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (showThumb) {
                            Canvas(Modifier.fillMaxWidth().height(thumbRadius * 2)) {
                                val fraction = if (layoutDirection == LayoutDirection.Rtl) 1f - state.value else state.value
                                drawCircle(
                                    color = if (enabled) colors.thumbColor else colors.disabledThumbColor,
                                    radius = thumbRadius.toPx(),
                                    center = Offset(size.width * fraction, center.y),
                                )
                            }
                        }
                    }
                }

                else -> {
                    Canvas(Modifier.fillMaxWidth().height(trackThickness)) {
                        val playedX = size.width * state.value
                        val bufferedX = size.width * (bufferedFraction.takeIf { it.isFinite() } ?: state.value).coerceIn(state.value, 1f)
                        val strokeWidth = trackThickness.toPx()
                        scale(scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f, scaleY = 1f) {
                            drawLine(inactiveColor, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth, StrokeCap.Round)
                            if (bufferedX > playedX) {
                                drawLine(
                                    activeColor.copy(alpha = activeColor.alpha * 0.38f),
                                    Offset(0f, center.y),
                                    Offset(bufferedX, center.y),
                                    strokeWidth,
                                    StrokeCap.Round,
                                )
                            }
                            drawLine(activeColor, Offset(0f, center.y), Offset(playedX, center.y), strokeWidth, StrokeCap.Round)
                        }
                    }
                }
            }
        },
    )
}
