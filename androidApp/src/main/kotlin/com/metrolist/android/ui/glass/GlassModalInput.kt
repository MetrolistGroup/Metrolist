package com.metrolist.android.ui.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Velocity

/** Portal content shares the activity tree, but must not drive its surrounding scaffold. */
fun NestedScrollConnection.excludingGlassOverlays(host: GlassMenuHostState): NestedScrollConnection {
    val delegate = this
    return object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (host.hasOverlays) Offset.Zero else delegate.onPreScroll(available, source)
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (host.hasOverlays) Offset.Zero else delegate.onPostScroll(consumed, available, source)
        override suspend fun onPreFling(available: Velocity): Velocity =
            if (host.hasOverlays) Velocity.Zero else delegate.onPreFling(available)
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
            if (host.hasOverlays) Velocity.Zero else delegate.onPostFling(consumed, available)
    }
}

/** The outside-dismiss target owns drags too; it never wraps a modal's scrolling content. */
internal fun Modifier.glassScrimInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Main).changes.forEach { change ->
                if (change.positionChanged()) change.consume()
            }
        }
    }
}

internal fun Modifier.blockGlassCoveredInput(blocked: Boolean): Modifier = pointerInput(blocked) {
    if (blocked) awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}
