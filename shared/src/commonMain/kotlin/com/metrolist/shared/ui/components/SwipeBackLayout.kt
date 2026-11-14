package com.metrolist.shared.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.navigation.blockInput
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SwipeBackLayout(
    enabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    edgeWidth: Dp = 32.dp,
    previousContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier = modifier.fillMaxSize()) {
            content()
        }
        return
    }

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val edgeWidthPx = with(density) { edgeWidth.toPx() }

    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val animatable = remember { Animatable(0f) }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(enabled, isRtl, edgeWidthPx) {
                    val velocityTracker = VelocityTracker()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (animatable.isRunning) return@awaitEachGesture
                        val width = size.width.toFloat()
                        if (width <= 0f) return@awaitEachGesture

                        val touchX = down.position.x
                        val isValidStart =
                            if (!isRtl) {
                                touchX <= edgeWidthPx
                            } else {
                                touchX >= width - edgeWidthPx
                            }
                        if (!isValidStart) return@awaitEachGesture

                        velocityTracker.resetTracking()
                        velocityTracker.addPointerInputChange(down)

                        var dragStarted = false
                        var currentOffset = 0f

                        val drag =
                            awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                                val validDirection = if (!isRtl) overSlop > 0f else overSlop < 0f
                                if (validDirection) {
                                    change.consume()
                                    velocityTracker.addPointerInputChange(change)
                                    dragStarted = true
                                    currentOffset = overSlop
                                }
                            }

                        if (dragStarted && drag != null) {
                            isDragging = true
                            dragOffset = currentOffset

                            val completed =
                                horizontalDrag(drag.id) { change ->
                                    velocityTracker.addPointerInputChange(change)
                                    val delta = change.positionChange().x
                                    val rawOffset = currentOffset + delta
                                    currentOffset =
                                        if (!isRtl) {
                                            rawOffset.coerceIn(0f, width)
                                        } else {
                                            rawOffset.coerceIn(-width, 0f)
                                        }
                                    dragOffset = currentOffset
                                    change.consume()
                                }

                            val velocityX = velocityTracker.calculateVelocity().x
                            val progress =
                                if (!isRtl) {
                                    currentOffset / width
                                } else {
                                    -currentOffset / width
                                }

                            val shouldDismiss =
                                if (!isRtl) {
                                    progress > 0.35f || velocityX > 800f
                                } else {
                                    progress > 0.35f || velocityX < -800f
                                }

                            scope.launch {
                                if (shouldDismiss && completed) {
                                    val target = if (!isRtl) width else -width
                                    animatable.snapTo(dragOffset)
                                    animatable.animateTo(
                                        targetValue = target,
                                        animationSpec =
                                            spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow,
                                            ),
                                    ) {
                                        dragOffset = value
                                    }
                                    onBack()
                                    dragOffset = 0f
                                    isDragging = false
                                } else {
                                    animatable.snapTo(dragOffset)
                                    animatable.animateTo(
                                        targetValue = 0f,
                                        animationSpec =
                                            spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow,
                                            ),
                                    ) {
                                        dragOffset = value
                                    }
                                    dragOffset = 0f
                                    isDragging = false
                                }
                            }
                        }
                    }
                },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val isSwiping by remember { derivedStateOf { isDragging || dragOffset != 0f } }
        val progress = { if (widthPx > 0f) (abs(dragOffset) / widthPx).coerceIn(0f, 1f) else 0f }

        if (isSwiping && previousContent != null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .blockInput(true)
                        .offset { IntOffset(((if (isRtl) 1f else -1f) * widthPx * 0.25f * (1f - progress())).roundToInt(), 0) },
            ) {
                previousContent()
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .drawBehind { drawRect(Color.Black.copy(alpha = 0.25f * (1f - progress()))) },
                )
            }
        }

        if (isSwiping) {
            val shadowWidth = 16.dp
            val shadowWidthPx = with(density) { shadowWidth.toPx() }
            if (!isRtl) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .width(shadowWidth)
                            .offset { IntOffset((dragOffset - shadowWidthPx).roundToInt(), 0) }
                            .drawBehind {
                                drawRect(
                                    Brush.horizontalGradient(
                                        colors =
                                            listOf(
                                                Color.Transparent,
                                                Color.Black.copy(alpha = 0.22f * (1f - progress() * 0.5f)),
                                            ),
                                    ),
                                )
                            },
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .width(shadowWidth)
                            .offset { IntOffset((widthPx + dragOffset).roundToInt(), 0) }
                            .drawBehind {
                                drawRect(
                                    Brush.horizontalGradient(
                                        colors =
                                            listOf(
                                                Color.Black.copy(alpha = 0.22f * (1f - progress() * 0.5f)),
                                                Color.Transparent,
                                            ),
                                    ),
                                )
                            },
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .blockInput(animatable.isRunning)
                    .offset { IntOffset(dragOffset.roundToInt(), 0) }
                    .background(MaterialTheme.colorScheme.surface),
        ) {
            content()
        }
    }
}
