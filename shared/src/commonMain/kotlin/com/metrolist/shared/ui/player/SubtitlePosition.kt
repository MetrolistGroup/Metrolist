package com.metrolist.shared.ui.player

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

internal fun clampSubtitleOffset(
    offset: IntOffset,
    viewport: IntSize,
    subtitle: IntSize,
): IntOffset {
    val horizontalSpace = (viewport.width - subtitle.width).coerceAtLeast(0)
    val verticalSpace = (viewport.height - subtitle.height).coerceAtLeast(0)
    return IntOffset(
        offset.x.coerceIn(-horizontalSpace / 2, horizontalSpace - horizontalSpace / 2),
        offset.y.coerceIn(-verticalSpace, 0),
    )
}
