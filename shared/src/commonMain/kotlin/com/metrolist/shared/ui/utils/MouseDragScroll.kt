package com.metrolist.shared.ui.utils

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.sign

internal class MouseDragSlop(
    private val horizontal: Boolean,
    private val touchSlop: Float,
) {
    private var accumulated = Offset.Zero
    private var dragging = false
    var rejected = false
        private set

    fun delta(movement: Offset): Float? {
        if (rejected) return null
        if (dragging) return if (horizontal) movement.x else movement.y
        accumulated += movement
        val x = accumulated.x.absoluteValue
        val y = accumulated.y.absoluteValue
        if (maxOf(x, y) <= touchSlop) return null
        if (horizontal != (x > y)) {
            rejected = true
            return null
        }
        dragging = true
        val distance = if (horizontal) accumulated.x else accumulated.y
        return distance - distance.sign * touchSlop
    }
}

internal fun Velocity.mouseDragScrollVelocity(
    horizontal: Boolean,
    reverseDirection: Boolean,
): Float {
    val velocity = if (horizontal) x else y
    return if (reverseDirection) velocity else -velocity
}

internal fun Modifier.mouseDragScroll(
    state: ScrollableState,
    horizontal: Boolean,
    flingBehavior: FlingBehavior,
    reverseDirection: Boolean = false,
): Modifier =
    pointerInput(state, horizontal, reverseDirection, flingBehavior) {
        coroutineScope {
            val pointerScope: CoroutineScope = this
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.type != PointerType.Mouse || !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture

                val slop = MouseDragSlop(horizontal, viewConfiguration.touchSlop)
                val velocityTracker = VelocityTracker()
                velocityTracker.addPosition(down.uptimeMillis, down.position)
                var deltas: Channel<Float>? = null
                var completed = false
                var releaseVelocity = 0f
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) {
                            releaseVelocity = velocityTracker.calculateVelocity().mouseDragScrollVelocity(horizontal, reverseDirection)
                            completed = true
                            break
                        }
                        val delta = slop.delta(change.positionChange())
                        if (slop.rejected) break
                        if (delta != null) {
                            if (deltas == null) {
                                val scrollDelta = if (reverseDirection) delta else -delta
                                if ((scrollDelta > 0f && !state.canScrollForward) ||
                                    (scrollDelta < 0f && !state.canScrollBackward)
                                ) {
                                    break
                                }
                                val channel = Channel<Float>(Channel.UNLIMITED)
                                deltas = channel
                                pointerScope.launch {
                                    state.scroll(MutatePriority.UserInput) {
                                        for (amount in channel) {
                                            scrollBy(if (reverseDirection) amount else -amount)
                                        }
                                        if (completed) {
                                            with(flingBehavior) { performFling(releaseVelocity) }
                                        }
                                    }
                                }
                            }
                            change.consume()
                            deltas.trySend(delta)
                        } else {
                            awaitPointerEvent(PointerEventPass.Final)
                            if (change.isConsumed) break
                        }
                    }
                } finally {
                    deltas?.close()
                }
            }
        }
    }
