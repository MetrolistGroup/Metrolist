package com.metrolist.desktop.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal fun fullscreenPlayerScale(
    availableWidth: Dp,
    availableHeight: Dp,
): Float = minOf((availableWidth - 48.dp) / 650.dp, availableHeight / 1030.dp).coerceAtLeast(0f)

internal fun fullscreenArtworkSize(
    availableWidth: Int,
    availableHeight: Int,
    controlsHeight: Int,
    aspectRatio: Float,
    preferredHeight: Int,
): IntSize {
    val height = minOf(preferredHeight, (availableHeight - controlsHeight).coerceAtLeast(0))
    val width = minOf(availableWidth, (height * aspectRatio).roundToInt())
    return IntSize(width, (width / aspectRatio).roundToInt().coerceAtMost(height))
}
