package com.metrolist.shared.ui.utils

import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

fun desktopScrollEvent(
    event: MouseEvent,
    toolkitClassName: String,
): MouseEvent? {
    if (toolkitClassName != "sun.awt.X11.XToolkit" || event.button !in 4..5 || event.isConsumed) return event
    return when (event.id) {
        MouseEvent.MOUSE_PRESSED -> {
            MouseWheelEvent(
                event.component,
                MouseEvent.MOUSE_WHEEL,
                event.`when`,
                (event.modifiersEx and InputEvent.getMaskForButton(event.button).inv()) or InputEvent.SHIFT_DOWN_MASK,
                event.x,
                event.y,
                event.xOnScreen,
                event.yOnScreen,
                0,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                3,
                if (event.button == 4) -1 else 1,
            )
        }

        MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED -> {
            null
        }

        else -> {
            event
        }
    }
}
