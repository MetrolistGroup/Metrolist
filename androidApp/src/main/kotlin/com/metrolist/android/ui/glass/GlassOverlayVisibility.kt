package com.metrolist.android.ui.glass

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.platform.LocalView

/** Paint reports belong to the overlay, never to its transparent full-window wrapper. */
internal data class GlassOpaqueRegion(val overlay: Any, val bounds: Rect, val alpha: Float)

@Stable
internal class GlassOverlayVisibility {
    val regions = mutableStateMapOf<Any, GlassOpaqueRegion>()
    val viewports = mutableStateMapOf<Any, Rect>()
    val primaryViewport: Rect get() = viewports.values.maxByOrNull { it.width * it.height } ?: Rect.Zero

    fun removeOverlay(id: Any) { regions.entries.removeAll { it.value.overlay == id } }
    fun covers(viewport: Rect, overlays: List<Any>): Boolean = opaqueRegionsCover(viewport,
        regions.values.filter { it.overlay in overlays && it.alpha >= 1f }.map { it.bounds })
}

/** Union actual opaque bands; a transparent hole or a lowered top edge keeps the page live. */
internal fun opaqueRegionsCover(viewport: Rect, regions: List<Rect>): Boolean {
    if (viewport.isEmpty || !viewport.left.isFinite() || !viewport.top.isFinite() ||
        !viewport.right.isFinite() || !viewport.bottom.isFinite()) return false
    val clipped = regions.map { it.intersect(viewport) }.filter { !it.isEmpty }
    val edges = (listOf(viewport.top, viewport.bottom) + clipped.flatMap { listOf(it.top, it.bottom) }).distinct().sorted()
    return edges.zipWithNext().all { (top, bottom) ->
        val middle = (top + bottom) / 2f
        var right = viewport.left
        for (rect in clipped.filter { it.top <= middle && it.bottom >= middle }.sortedBy { it.left }) {
            if (rect.left > right) break
            right = maxOf(right, rect.right)
        }
        right >= viewport.right
    }
}

internal fun GlassMenuHostState.overlaysAbove(owner: Any? = null): List<Any> {
    val ordered = dialogs.entries.map { it.id } + listOfNotNull(entry?.id) + nativeOverlays.toList()
    return if (owner == null) ordered else ordered.drop(ordered.indexOf(owner) + 1)
}

internal fun GlassMenuHostState.visuallyCovered(owner: Any? = null, viewport: Rect = visibility.primaryViewport): Boolean =
    visibility.covers(viewport, overlaysAbove(owner))

internal class GlassOverlayPaintScope(val host: GlassMenuHostState, val id: Any, val alpha: () -> Float = { 1f })
internal val LocalGlassOverlayPaintScope = staticCompositionLocalOf<GlassOverlayPaintScope?> { null }

/** Window-relative coordinates need their window origin before comparing native and activity paint. */
internal fun LayoutCoordinates.glassBoundsOnScreen(view: View): Rect {
    val screen = IntArray(2)
    val window = IntArray(2)
    view.getLocationOnScreen(screen)
    view.getLocationInWindow(window)
    return boundsInWindow().translate(Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat()))
}

/** Call on the node that paints this alpha, inside the panel's translated/clipped content. */
@Composable
internal fun Modifier.glassOverlayPaint(alpha: Float): Modifier {
    val scope = LocalGlassOverlayPaintScope.current ?: return this
    val view = LocalView.current
    val token = remember { Any() }
    val bounds = remember { arrayOf(Rect.Zero) }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val actualAlpha = rememberUpdatedState(alpha * scope.alpha())
    fun report(rect: Rect) {
        scope.host.visibility.regions[token] = GlassOpaqueRegion(scope.id, rect, actualAlpha.value)
    }
    fun refresh() {
        coordinates[0]?.takeIf { it.isAttached }?.let { positioned ->
            bounds[0] = positioned.glassBoundsOnScreen(view)
            report(bounds[0])
        }
    }
    SideEffect { report(bounds[0]) }
    DisposableEffect(view, scope.host, scope.id, token) {
        // Layer-only transforms can bypass Compose's post-layout callbacks. Observe the
        // window's existing draws; never request a draw or keep an idle frame clock alive.
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener { refresh(); true }
        observer.addOnPreDrawListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            else view.viewTreeObserver.removeOnPreDrawListener(listener)
            scope.host.visibility.regions.remove(token)
        }
    }
    return onGloballyPositioned {
        coordinates[0] = it
        bounds[0] = it.glassBoundsOnScreen(view)
        report(bounds[0])
    }.onLayoutRectChanged(throttleMillis = 0, debounceMillis = 0) {
        // Use clipped floating-point coordinates rather than the rounded callback AABB.
        refresh()
    }
}
