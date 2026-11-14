@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.utils

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.carousel.CarouselItemDrawInfo
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import kotlin.math.ceil
import kotlin.math.floor

internal fun Modifier.carouselCaption(info: CarouselItemDrawInfo?): Modifier =
    if (info == null) {
        this
    } else {
        layout { measurable, constraints ->
            val caption = carouselCaptionLayout(info.maskRect, constraints.maxWidth)
            val placeable = measurable.measure(constraints.copy(minWidth = caption.width, maxWidth = caption.width))
            layout(constraints.maxWidth, placeable.height) {
                // Mask coordinates are physical coordinates, including in RTL.
                placeable.placeWithLayer(caption.left, 0) { alpha = caption.alpha }
            }
        }
    }

internal data class CarouselCaptionLayout(
    val left: Int,
    val width: Int,
    val alpha: Float,
)

internal fun carouselCaptionLayout(
    mask: Rect,
    itemWidth: Int,
): CarouselCaptionLayout {
    val left = ceil(mask.left).toInt().coerceIn(0, itemWidth)
    val right = floor(mask.right).toInt().coerceIn(left, itemWidth)
    val visibleWidth = right - left
    val minimumWidth = itemWidth / 2
    val alpha = if (itemWidth > 0) ((visibleWidth.toFloat() / itemWidth - 0.5f) * 2f).coerceIn(0f, 1f) else 0f
    return CarouselCaptionLayout(left, maxOf(visibleWidth, minimumWidth), alpha)
}
