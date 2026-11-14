package com.metrolist.android.widget

internal fun turntableWidgetPadding(
    width: Int,
    height: Int,
): Pair<Float, Float> {
    val side = minOf(width, height)
    return 4 + (width - side) / 2f to 4 + (height - side) / 2f
}
