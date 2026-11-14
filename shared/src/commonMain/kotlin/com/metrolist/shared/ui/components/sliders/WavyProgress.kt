/*
 * Copyright (C) 2024 The Android Open Source Project
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
 * Determinate drawing adapted from Material 3's LinearWavyProgressIndicator.
 */

package com.metrolist.shared.ui.components.sliders

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.metrolist.shared.ui.components.LocalVisualWorkActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val WaveFrameInterval = 1.seconds / 30

internal val MaterialWavyStrokeWidth = 4.dp

@Composable
fun rememberWavePhase(
    running: Boolean,
    cycleMillis: Long,
): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    val visibility = LocalVisualWorkActive.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(running, cycleMillis, visibility, lifecycle) {
        if (!running) return@LaunchedEffect
        // Visible unfocused desktop windows stay STARTED.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            animateWavePhase(visibility, cycleMillis) { delta ->
                phase.floatValue = (phase.floatValue + delta) % 1f
            }
        }
    }
    return phase
}

internal suspend fun animateWavePhase(
    visibility: Flow<Boolean>,
    cycleMillis: Long,
    timeSource: TimeSource = TimeSource.Monotonic,
    onAdvance: (Float) -> Unit,
) {
    visibility.collectLatest { shown ->
        if (!shown) return@collectLatest
        val origin = timeSource.markNow()
        var previous = Duration.ZERO
        var next = WaveFrameInterval
        while (currentCoroutineContext().isActive) {
            // A phase write requests its own draw; awaiting a frame first also renders the old phase.
            delay(next - origin.elapsedNow())
            val elapsed = origin.elapsedNow()
            onAdvance(((elapsed - previous).inWholeNanoseconds.toDouble() / 1_000_000 / cycleMillis).toFloat())
            previous = elapsed
            next = WaveFrameInterval * (elapsed.inWholeNanoseconds / WaveFrameInterval.inWholeNanoseconds + 1).toDouble()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WavyProgress(
    progress: () -> Float,
    amplitude: Float,
    color: Color,
    trackColor: Color,
    stroke: Stroke,
    gapSize: Dp,
    moving: Boolean,
    thumbRadius: Dp = 0.dp,
    bufferedFraction: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val strokeDp = with(LocalDensity.current) { stroke.width.toDp() }
    val scale = strokeDp / MaterialWavyStrokeWidth
    val wavelength = WavyProgressIndicatorDefaults.LinearDeterminateWavelength * scale
    val containerHeight = WavyProgressIndicatorDefaults.LinearContainerHeight * scale
    val phase by rememberWavePhase(moving, cycleMillis = 1_000L)
    val fullPath = remember { Path() }
    val progressPath = remember { Path() }
    val pathMeasure = remember { PathMeasure() }
    Spacer(
        modifier.fillMaxWidth().height(containerHeight).drawWithCache {
            val width = size.width
            val centerY = size.height / 2f
            val wavelengthPx = wavelength.toPx()
            val waveAmplitude = waveAmplitude(size.height, stroke.width, thumbRadius.toPx()) * amplitude.coerceIn(0f, 1f)
            fullPath.rewind()
            fullPath.moveTo(0f, centerY)
            if (waveAmplitude == 0f) {
                fullPath.lineTo(width, centerY)
            } else {
                val halfWavelength = wavelengthPx / 2f
                var anchorX = halfWavelength
                var controlY = waveAmplitude
                while (anchorX <= width + wavelengthPx * 2) {
                    fullPath.quadraticTo(anchorX - halfWavelength / 2f, centerY + controlY, anchorX, centerY)
                    anchorX += halfWavelength
                    controlY = -controlY
                }
            }
            pathMeasure.setPath(fullPath, false)

            onDrawBehind {
                val width = size.width
                val centerY = size.height / 2f
                val wavelengthPx = wavelength.toPx()
                val capWidth = stroke.width / 2f
                val played = progress().coerceIn(0f, 1f)
                val head = played * width
                val headX = head.coerceIn(capWidth, width - capWidth)
                val activeVisible = head >= capWidth
                val gap = if (activeVisible) min(head - capWidth, gapSize.toPx()) else 0f

                scale(scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f, scaleY = 1f) {
                    if (played > 0f) {
                        val shift = if (waveAmplitude > 0f) phase * wavelengthPx else 0f
                        progressPath.rewind()
                        pathMeasure.getSegment(
                            distanceAtX(capWidth + shift, pathMeasure.length) { pathMeasure.getPosition(it).x },
                            distanceAtX(headX + shift, pathMeasure.length) { pathMeasure.getPosition(it).x },
                            progressPath,
                            true,
                        )
                        translate(left = -shift) { drawPath(progressPath, color, style = stroke) }
                    }

                    val trackStart = max(capWidth, headX + if (activeVisible) gap + capWidth * 2 else gap)
                    if (width - capWidth > trackStart) {
                        drawLine(trackColor, Offset(trackStart, centerY), Offset(width - capWidth, centerY), stroke.width, StrokeCap.Round)
                        val bufferedX = if (bufferedFraction.isFinite()) bufferedFraction.coerceIn(played, 1f) * width else head
                        if (bufferedX > head) {
                            clipRect(left = head, right = bufferedX) {
                                drawLine(
                                    color.copy(alpha = color.alpha * 0.38f),
                                    Offset(trackStart, centerY),
                                    Offset(width - capWidth, centerY),
                                    stroke.width,
                                    StrokeCap.Round,
                                )
                            }
                        }
                    }

                    var stopSize = min(stroke.width, WavyProgressIndicatorDefaults.LinearTrackStopIndicatorSize.toPx())
                    var stopX = width - stopSize - if (stopSize == stroke.width) 0f else stroke.width / 4f
                    val progressX = head + capWidth
                    if (stopX <= progressX) {
                        stopSize = max(0f, stopSize - (progressX - stopX))
                        stopX = progressX
                    }
                    if (stopSize > 0f) drawCircle(color, stopSize / 2f, Offset(stopX + stopSize / 2f, centerY))
                }
            }
        },
    )
}
