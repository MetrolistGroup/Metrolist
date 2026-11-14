package com.metrolist.android.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class MiniPlayerInsets(
    val bottomOffset: Dp,
    val contentBottomInset: Dp,
)

internal fun calculateMiniPlayerInsets(
    systemBottomInset: Dp,
    navigationBottomInset: Dp,
    navigationConsumesSpace: Boolean,
    playerHeight: Dp,
    playerBottomSpacing: Dp,
): MiniPlayerInsets {
    val bottomOffset = maxOf(systemBottomInset, navigationBottomInset) + playerBottomSpacing
    val consumedInset = if (navigationConsumesSpace) navigationBottomInset else 0.dp
    return MiniPlayerInsets(
        bottomOffset = bottomOffset,
        contentBottomInset = (playerHeight + bottomOffset - consumedInset).coerceAtLeast(0.dp),
    )
}
