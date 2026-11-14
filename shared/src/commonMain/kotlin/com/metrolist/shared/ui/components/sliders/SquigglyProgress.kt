/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Adapted from Metrolist's SquigglySlider, ported from mpvEx:
 * https://github.com/marlboro-advance/mpvEx
 */

package com.metrolist.shared.ui.components.sliders

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max

private const val WAVELENGTH_PER_STROKE = 10f
private const val AMPLITUDE_PER_STROKE = 0.75f
private const val PHASE_SPEED_PER_STROKE = 4f
private val ThumbWidth = 4.dp
private val ThumbHeight = 16.dp
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)

@Composable
internal fun SquigglyProgress(
    progress: () -> Float,
    bufferedFraction: Float,
    color: Color,
    isPlaying: Boolean,
    isDragging: Boolean,
    animate: Boolean,
    thickness: Dp,
    showThumb: Boolean,
) {
    val flatten = !isPlaying || isDragging
    val heightFraction by animateFloatAsState(
        targetValue = if (flatten) 0f else 1f,
        animationSpec =
            if (!animate) {
                snap()
            } else if (flatten) {
                tween(durationMillis = 550, easing = StandardDecelerate)
            } else {
                tween(durationMillis = 800, delayMillis = 60, easing = EmphasizedDecelerate)
            },
        label = "heightFraction",
    )
    val path = remember { Path() }
    val visiblePath = remember { Path() }
    val pathMeasure = remember { PathMeasure() }
    val transitionEnabled = !showThumb
    val phaseFraction by rememberWavePhase(
        isPlaying && animate && !isDragging,
        cycleMillis = (WAVELENGTH_PER_STROKE / PHASE_SPEED_PER_STROKE * 1_000f).toLong(),
    )
    Canvas(Modifier.fillMaxWidth().height(48.dp)) {
        val strokeWidth = thickness.toPx()
        val wavelength = strokeWidth * WAVELENGTH_PER_STROKE
        val lineAmplitude = minOf(strokeWidth * AMPLITUDE_PER_STROKE, (size.height - strokeWidth).coerceAtLeast(0f) / 2f)
        val inset = minOf(strokeWidth / 2f, size.width / 2f)
        val trackWidth = (size.width - inset * 2f).coerceAtLeast(0f)
        val transitionLength = 1.5f * wavelength
        val played = progress().coerceIn(0f, 1f)
        val playedX = inset + trackWidth * played
        val bufferedX = inset + trackWidth * (bufferedFraction.takeIf { it.isFinite() } ?: played).coerceIn(played, 1f)
        val waveStart = -phaseFraction * wavelength - wavelength / 2f
        val waveEnd = if (transitionEnabled) size.width - inset else playedX
        val inactiveColor = color.copy(alpha = color.alpha * (77f / 255f))
        val clipHeight = lineAmplitude + strokeWidth

        fun amplitudeAt(x: Float): Float {
            val coefficient = if (transitionEnabled) ((playedX + transitionLength / 2f - x) / transitionLength).coerceIn(0f, 1f) else 1f
            return heightFraction * lineAmplitude * coefficient
        }

        path.rewind()
        path.moveTo(waveStart, center.y)
        var x = waveStart
        var sign = 1f
        var currentAmplitude = amplitudeAt(x)
        while (x < waveEnd) {
            sign = -sign
            val nextX = x + wavelength / 2f
            val midX = x + wavelength / 4f
            val nextAmplitude = sign * amplitudeAt(nextX)
            path.cubicTo(midX, center.y + currentAmplitude, midX, center.y + nextAmplitude, nextX, center.y + nextAmplitude)
            currentAmplitude = nextAmplitude
            x = nextX
        }

        // Inset the measured start so its round cap stays inside the track at every phase and thickness.
        pathMeasure.setPath(path, false)
        val length = pathMeasure.length
        visiblePath.rewind()
        val startDistance = distanceAtX(inset, length) { pathMeasure.getPosition(it).x }
        pathMeasure.getSegment(startDistance, length, visiblePath, true)

        scale(scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f, scaleY = 1f) {
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

            fun drawWave(
                start: Float,
                end: Float,
                segmentColor: Color,
            ) {
                if (end <= start) return
                val left = if (start <= inset) 0f else start
                clipRect(left = left, top = center.y - clipHeight, right = end, bottom = center.y + clipHeight) {
                    drawPath(visiblePath, segmentColor, style = stroke)
                }
            }

            fun drawFlat(
                start: Float,
                end: Float,
                segmentColor: Color,
            ) {
                if (end > start) drawLine(segmentColor, Offset(start, center.y), Offset(end, center.y), strokeWidth, StrokeCap.Round)
            }
            drawWave(inset, playedX, color)
            if (transitionEnabled) {
                drawWave(playedX, size.width, inactiveColor)
                if (bufferedX > playedX) drawWave(playedX, bufferedX, color.copy(alpha = color.alpha * 0.38f))
            } else {
                drawFlat(playedX, size.width - inset, inactiveColor)
                if (bufferedX > playedX) drawFlat(playedX, bufferedX, color.copy(alpha = color.alpha * 0.38f))
            }
            if (showThumb) {
                val thumbWidth = max(ThumbWidth.toPx(), strokeWidth)
                val thumbHeight = max(ThumbHeight.toPx(), 2f * (lineAmplitude + strokeWidth))
                drawRoundRect(
                    color = color,
                    topLeft = Offset(playedX - thumbWidth / 2f, center.y - thumbHeight / 2f),
                    size = Size(thumbWidth, thumbHeight),
                    cornerRadius = CornerRadius(thumbWidth / 2f),
                )
            }
        }
    }
}
