package com.metrolist.shared.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.metrolist.shared.utils.isDesktop
import kotlin.math.roundToInt

@Composable
fun LazyListScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    var isDragging by remember(state) { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (isDesktop || state.isScrollInProgress || isDragging) 1f else 0f,
        animationSpec = tween(durationMillis = if (isDesktop) 0 else 400),
        label = "Scrollbar visibility",
    )
    val minThumbLength = with(LocalDensity.current) { 32.dp.toPx() }
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)

    Canvas(
        modifier =
            modifier
                .width(16.dp)
                .fillMaxHeight()
                .graphicsLayer { this.alpha = alpha }
                .then(
                    if (alpha > 0f) {
                        Modifier.pointerInput(state) {
                            var dragTargetOffset = 0f
                            detectDragGestures(
                                onDragStart = {
                                    dragTargetOffset = state.scrollIndicatorState?.scrollOffset?.toFloat() ?: 0f
                                    isDragging = true
                                },
                                onDragEnd = { isDragging = false },
                                onDragCancel = { isDragging = false },
                            ) { change, dragAmount ->
                                change.consume()
                                scrollbarMetrics(state, size.height.toFloat(), minThumbLength)?.let { metrics ->
                                    val scrollTrackLength = metrics.trackLength - metrics.thumbLength
                                    if (scrollTrackLength > 0f) {
                                        dragTargetOffset =
                                            scrollbarDragTarget(
                                                dragTargetOffset,
                                                dragAmount.y,
                                                metrics.maxScroll,
                                                scrollTrackLength,
                                            )
                                        val (index, offset) =
                                            scrollbarScrollPosition(
                                                dragTargetOffset,
                                                metrics.maxScroll,
                                                state.layoutInfo.totalItemsCount,
                                                metrics.contentSize,
                                            )
                                        state.requestScrollToItem(index, offset)
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
    ) {
        scrollbarMetrics(state, size.height, minThumbLength)?.let { metrics ->
            val barWidth = 4.dp.toPx()
            val barX = (size.width - barWidth) / 2f
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(barX, 0f),
                size = Size(barWidth, size.height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(barX, metrics.thumbOffset),
                size = Size(barWidth, metrics.thumbLength),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

internal fun scrollbarDragTarget(
    currentOffset: Float,
    dragAmount: Float,
    maxScroll: Float,
    scrollTrackLength: Float,
): Float {
    if (maxScroll <= 0f || scrollTrackLength <= 0f) return 0f
    return (currentOffset + dragAmount * maxScroll / scrollTrackLength).coerceIn(0f, maxScroll)
}

internal fun scrollbarScrollPosition(
    scrollOffset: Float,
    maxScroll: Float,
    totalItemsCount: Int,
    contentSize: Int,
): Pair<Int, Int> {
    if (totalItemsCount <= 0 || contentSize <= 0 || maxScroll <= 0f) return 0 to 0
    if (scrollOffset >= maxScroll) return totalItemsCount - 1 to 0
    val averageItemSize = contentSize.toFloat() / totalItemsCount
    val itemPosition = scrollOffset.coerceAtLeast(0f) / averageItemSize
    val index = itemPosition.toInt().coerceAtMost(totalItemsCount - 1)
    return index to ((itemPosition - index) * averageItemSize).roundToInt()
}

internal data class ScrollbarMetrics(
    val thumbOffset: Float,
    val thumbLength: Float,
    val trackLength: Float,
    val maxScroll: Float,
    val contentSize: Int,
)

internal fun scrollbarMetrics(
    state: ScrollableState,
    trackLength: Float,
    minThumbLength: Float,
): ScrollbarMetrics? {
    val indicator = state.scrollIndicatorState ?: return null
    return scrollbarMetrics(indicator.contentSize, indicator.viewportSize, indicator.scrollOffset, trackLength, minThumbLength)
}

internal fun scrollbarMetrics(
    contentSize: Int,
    viewportSize: Int,
    scrollOffset: Int,
    trackLength: Float,
    minThumbLength: Float,
): ScrollbarMetrics? {
    if (contentSize <= 0 || viewportSize <= 0) return null
    if (contentSize <= viewportSize || trackLength <= 0f) return null

    val maxScroll = (contentSize - viewportSize).toFloat()
    val thumbLength =
        (trackLength * viewportSize / contentSize)
            .coerceIn(minThumbLength.coerceAtMost(trackLength), trackLength)
    val scrollTrackLength = trackLength - thumbLength

    return ScrollbarMetrics(
        thumbOffset = scrollOffset.coerceIn(0, maxScroll.toInt()) / maxScroll * scrollTrackLength,
        thumbLength = thumbLength,
        trackLength = trackLength,
        maxScroll = maxScroll,
        contentSize = contentSize,
    )
}
