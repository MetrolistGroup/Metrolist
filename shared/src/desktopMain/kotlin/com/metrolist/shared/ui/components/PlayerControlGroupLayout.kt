package com.metrolist.shared.ui.components

internal fun playerControlLastVisibleIndex(
    availableWidth: Float,
    itemCount: Int,
    itemWidth: Float,
    spacing: Float,
    overflowWidth: Float,
): Int {
    if (availableWidth <= 0) return itemCount - 1
    val desiredWidth = itemCount * itemWidth + (itemCount - 1).coerceAtLeast(0) * spacing
    if (desiredWidth <= availableWidth) return itemCount - 1

    var count = 0
    var remaining = availableWidth - overflowWidth - spacing
    while (count < itemCount && itemWidth <= remaining) {
        remaining -= itemWidth + spacing
        count++
    }
    return count - 1
}
