/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components.lyrics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.components.WavyCircularProgressIndicator
import com.metrolist.shared.ui.components.lyrics.words.FixedMotionDurationScale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

private val GapEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private const val GAP_SHOW_DELAY_MS = 300L
private const val GAP_FADE_MS = 200
private const val GAP_HIDE_FADE_DELAY_MS = 500L
private const val GAP_ENDED_MS = 800
private const val GAP_CLOSE_MS = 850L

internal val IntervalIndicatorHeight = 72.dp

@Composable
internal fun IntervalIndicator(
    gapStartMs: Long,
    gapEndMs: Long,
    currentPositionMs: () -> Long,
    visible: Boolean,
    surfaceVisible: Boolean = true,
    isPlaying: Boolean = true,
    animateClose: Boolean = true,
    holdOpen: Boolean = false,
    delayedClose: Boolean = true,
    onLayoutShift: (Float) -> Unit = {},
    color: Color,
    modifier: Modifier = Modifier,
) {
    val animAlpha = remember { Animatable(0f) }
    val animScale = remember { Animatable(if (visible) 1f else 0f) }
    var open by remember { mutableStateOf(visible) }

    LaunchedEffect(visible, animateClose) {
        withContext(FixedMotionDurationScale) {
            if (visible) {
                open = true
                animScale.snapTo(1f)
                delay(GAP_SHOW_DELAY_MS.milliseconds)
                animAlpha.animateTo(1f, tween(GAP_FADE_MS, easing = GapEase))
            } else if (animateClose) {
                coroutineScope {
                    if (animAlpha.value > 0f) {
                        launch {
                            delay(GAP_HIDE_FADE_DELAY_MS.milliseconds)
                            animAlpha.animateTo(0f, tween(GAP_FADE_MS, easing = GapEase))
                        }
                    }
                    launch {
                        animScale.snapTo(1f)
                        animScale.animateTo(
                            0f,
                            keyframes {
                                durationMillis = GAP_ENDED_MS
                                1f at 0 using GapEase
                                1.2f at (GAP_ENDED_MS * 35 / 100) using GapEase
                                0f at GAP_ENDED_MS
                            },
                        )
                    }
                }
                delay((GAP_CLOSE_MS - GAP_ENDED_MS).milliseconds)
                open = false
            } else {
                animAlpha.snapTo(0f)
                animScale.snapTo(0f)
                open = false
            }
        }
    }

    LaunchedEffect(delayedClose) {
        if (!delayedClose && !visible) open = false
    }
    val expanded = visible || holdOpen || (delayedClose && open)

    val hasVisibleRing by remember { derivedStateOf { animAlpha.value > 0f } }
    val lastLayoutH = remember { intArrayOf(-1) }
    Box(
        modifier =
            modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val layoutH = if (expanded) placeable.height else 0
                    if (lastLayoutH[0] >= 0 && lastLayoutH[0] != layoutH) {
                        onLayoutShift((layoutH - lastLayoutH[0]).toFloat())
                    }
                    lastLayoutH[0] = layoutH
                    layout(placeable.width, layoutH) { placeable.place(0, 0) }
                }.height(IntervalIndicatorHeight)
                .padding(bottom = 16.dp)
                .graphicsLayer {
                    this.alpha = animAlpha.value
                    this.clip = false
                },
        contentAlignment = Alignment.Center,
    ) {
        if (surfaceVisible && hasVisibleRing) {
            WavyCircularProgressIndicator(
                progress = {
                    if (gapEndMs > gapStartMs) {
                        ((currentPositionMs() - gapStartMs).toFloat() / (gapEndMs - gapStartMs).toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                },
                animate = isPlaying && visible,
                modifier =
                    Modifier
                        .size(36.dp)
                        .graphicsLayer {
                            this.alpha = animAlpha.value
                            scaleX = animScale.value
                            scaleY = animScale.value
                        },
                color = color,
                trackColor = color.copy(alpha = 0.4f),
            )
        }
    }
}
