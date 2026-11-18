package com.metrolist.android.ui.glass

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed

/** Keeps a drag that starts on an enabled Glass button away from ancestor scrollables. */
internal fun Modifier.retainGlassButtonGesture(enabled: Boolean = true): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var dragging = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val primary = event.changes.firstOrNull { it.id == down.id }
                if (primary != null && (primary.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    dragging = true
                }
                // Leave down/up and sub-slop motion to clickable/combinedClickable. Once
                // dragging, consume at Initial before either scroll axis can claim it.
                if (dragging) event.changes.forEach { change ->
                    if (change.positionChangedIgnoreConsumed()) change.consume()
                }
            } while (event.changes.any { it.pressed })
        }
    }
