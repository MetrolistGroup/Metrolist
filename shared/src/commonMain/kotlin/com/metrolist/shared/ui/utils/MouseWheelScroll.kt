package com.metrolist.shared.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

data class MouseScrollFactors(
    val horizontal: Float,
    val vertical: Float,
)

val LocalMouseScrollFactors = staticCompositionLocalOf<MouseScrollFactors?> { null }

fun desktopMouseScrollFactors(density: Density): MouseScrollFactors {
    val pixelsPerTick = with(density) { 48.dp.toPx() }
    return MouseScrollFactors(horizontal = pixelsPerTick, vertical = pixelsPerTick)
}

internal fun mouseWheelDelta(
    delta: Offset,
    horizontal: Boolean,
    verticalToHorizontal: Boolean,
    rtl: Boolean,
    factors: MouseScrollFactors,
): Float =
    if (horizontal) {
        val axis =
            if (delta.x != 0f) {
                delta.x
            } else if (verticalToHorizontal) {
                delta.y
            } else {
                0f
            }
        axis * factors.horizontal * if (rtl) -1f else 1f
    } else {
        -delta.y * factors.vertical
    }

@Composable
internal fun Modifier.mouseWheelScroll(
    horizontal: Boolean,
    onScroll: (Float) -> Float,
): Modifier {
    val factors = LocalMouseScrollFactors.current ?: return this
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return pointerInput(horizontal, factors, rtl, onScroll) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(if (horizontal) PointerEventPass.Initial else PointerEventPass.Main)
                if (event.type != PointerEventType.Scroll || event.changes.any { it.isConsumed }) continue
                val delta = event.changes.fold(Offset.Zero) { sum, change -> sum + change.scrollDelta }
                val amount = mouseWheelDelta(delta, horizontal, event.keyboardModifiers.isShiftPressed, rtl, factors)
                if (amount != 0f && onScroll(amount) != 0f) event.changes.forEach { it.consume() }
            }
        }
    }
}
