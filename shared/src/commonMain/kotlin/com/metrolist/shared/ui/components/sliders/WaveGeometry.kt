package com.metrolist.shared.ui.components.sliders

internal inline fun distanceAtX(
    x: Float,
    length: Float,
    positionX: (Float) -> Float,
): Float {
    var before = 0f
    var after = length
    repeat(24) {
        val middle = (before + after) / 2f
        if (positionX(middle) < x) before = middle else after = middle
    }
    return after
}

internal fun waveAmplitude(
    containerHeight: Float,
    strokeWidth: Float,
    thumbRadius: Float,
): Float {
    val available = (containerHeight - strokeWidth).coerceAtLeast(0f)
    return if (thumbRadius > 0f) minOf(available, (2f * thumbRadius - strokeWidth).coerceAtLeast(0f)) else available
}
