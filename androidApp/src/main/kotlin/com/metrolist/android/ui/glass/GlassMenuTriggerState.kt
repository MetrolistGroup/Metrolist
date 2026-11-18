package com.metrolist.android.ui.glass

import kotlin.math.roundToInt
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset

/** Registers an existing native control as the single visual source of its dropdown. */
@Composable
fun GlassMenuTrigger(
    state: GlassMenuTriggerState,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val latestContent = rememberUpdatedState(content)
    val locals = currentCompositionLocalContext
    val source: @Composable () -> Unit = remember(locals) {
        { CompositionLocalProvider(locals) { latestContent.value() } }
    }
    SideEffect {
        state.content = source
        state.shape = shape
    }
    Box(modifier.glassMenuTrigger(state)) { content() }
}

/** Transfers one button's rendering to its menu without changing the original layout or hit target. */
@Stable
class GlassMenuTriggerState {
    var bounds by mutableStateOf(Rect.Zero)
        internal set
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
        internal set
    var shape by mutableStateOf<Shape?>(null)
        internal set
    /** Horizontal menu attachment: 0 starts at the source, .5 centers, 1 aligns its end. */
    var menuAnchorFraction by mutableStateOf(1f)
        internal set
    var owner by mutableStateOf<Any?>(null)
        private set
    var menuBounds by mutableStateOf<Rect?>(null)
        private set
    val isTransferred: Boolean get() = owner != null && menuBounds != null

    internal fun claim(id: Any) {
        if (owner != id) menuBounds = null
        owner = id
    }

    internal fun updateMenuBounds(id: Any, bounds: Rect) {
        if (owner == id) menuBounds = bounds
    }

    internal fun release(id: Any) {
        if (owner == id) {
            owner = null
            menuBounds = null
        }
    }
}

internal val LocalGlassMenuTrigger = staticCompositionLocalOf<GlassMenuTriggerState?> { null }

/** Keeps the measured gap from a growing owner pane, bounded by the root's leading edge. */
internal fun glassMenuNeighborOffset(neighbor: Rect, trigger: Rect, menu: Rect?): Int {
    if (menu == null || neighbor == Rect.Zero || menu.bottom <= neighbor.top || menu.top >= neighbor.bottom) return 0
    val gap = (trigger.left - neighbor.right).coerceAtLeast(0f)
    return (menu.left - gap - neighbor.right).coerceIn(-neighbor.left.coerceAtLeast(0f), 0f).roundToInt()
}

/** Layout displacement moves the button's rendering, semantics and hit target together. */
@Composable
internal fun Modifier.glassMenuNeighbor(trigger: GlassMenuTriggerState): Modifier {
    var restingBounds by remember(trigger) { mutableStateOf(Rect.Zero) }
    fun offset() = glassMenuNeighborOffset(restingBounds, trigger.bounds, trigger.menuBounds)
    return absoluteOffset { IntOffset(offset(), 0) }.onGloballyPositioned { coordinates ->
        val position = coordinates.positionInRoot()
        val left = position.x - offset()
        restingBounds = Rect(left, position.y, left + coordinates.size.width, position.y + coordinates.size.height)
    }
}

/** A source keeps its space while the overlay owns all visible pixels and accessibility. */
internal fun Modifier.glassMenuTrigger(state: GlassMenuTriggerState?): Modifier = if (state == null) this else {
    onGloballyPositioned { coordinates ->
        val origin = coordinates.positionInRoot()
        state.bounds = Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
    }.graphicsLayer { alpha = if (state.isTransferred) 0f else 1f }
        .then(if (state.isTransferred) Modifier.clearAndSetSemantics { } else Modifier)
}
