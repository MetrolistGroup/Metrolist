package com.metrolist.android.ui.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned

/** Observes final positions without taking click, long-click, or scroll ownership. */
internal fun Modifier.trackGlassHighlightPointers(tracker: GlassHighlightPointerTracker): Modifier =
    onGloballyPositioned { tracker.coordinates = it }.pointerInput(tracker) {
        try {
            awaitPointerEventScope {
                while (true) tracker.observe(awaitPointerEvent(PointerEventPass.Final))
            }
        } finally {
            tracker.cancelPointerInput()
        }
    }

/** Routes provider-local positions only to indications with an active native press. */
internal class GlassHighlightPointerTracker {
    var coordinates: LayoutCoordinates? = null
    private val activeNodes = mutableSetOf<GlassHighlightNode>()
    private val pointerByNode = mutableMapOf<GlassHighlightNode, PointerId>()
    private var pressedPointers: Map<PointerId, Offset> = emptyMap()

    fun add(node: GlassHighlightNode) {
        activeNodes.add(node)
        bindPointer(node)
    }

    fun remove(node: GlassHighlightNode) {
        activeNodes.remove(node)
        pointerByNode.remove(node)
    }

    fun observe(event: PointerEvent) {
        pressedPointers = event.changes.filter { it.pressed }.associate { it.id to it.position }
        val coordinates = coordinates ?: return
        // A native long click consumes movement while its press remains active.
        activeNodes.forEach { node ->
            bindPointer(node)
            pressedPointers[pointerByNode[node]]?.let { node.movePointer(it, coordinates) }
        }
    }

    private fun bindPointer(node: GlassHighlightNode) {
        if (pointerByNode.containsKey(node)) return
        val coordinates = coordinates ?: return
        val origin = node.pressPositionIn(coordinates) ?: return
        val pointer = pressedPointers.entries.filter { it.key !in pointerByNode.values }
            .minByOrNull { (it.value - origin).getDistanceSquared() } ?: return
        pointerByNode[node] = pointer.key
    }

    fun cancelPointerInput() {
        pointerByNode.clear()
        pressedPointers = emptyMap()
    }
}
