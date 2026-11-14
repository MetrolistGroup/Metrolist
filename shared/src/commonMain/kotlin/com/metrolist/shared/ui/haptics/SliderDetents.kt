package com.metrolist.shared.ui.haptics

import kotlin.math.roundToInt

internal class SliderDetents(
    private val range: ClosedFloatingPointRange<Float>,
    private val steps: Int,
    initialValue: Float,
) {
    private var previous = detent(initialValue)

    fun moved(value: Float): Boolean {
        val current = detent(value)
        val changed = current != null && previous != null && current != previous
        previous = current
        return changed
    }

    fun reset(value: Float) {
        previous = detent(value)
    }

    private fun detent(value: Float): Int? {
        val length = range.endInclusive - range.start
        if (steps <= 0 || length <= 0f || !length.isFinite() || !value.isFinite()) return null
        return ((value.coerceIn(range) - range.start) / length * (steps + 1)).roundToInt()
    }
}
