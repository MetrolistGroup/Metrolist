package com.metrolist.android.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.metrolist.android.ui.player.LocalPlayerTransportGeometry
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.android.ui.player.PlayerInformationVerticalPadding

/** Shared edge reserve for lyrics and visualizer utilities, independent of the material mode. */
internal fun overlayControlsBottomInset(showThumbnail: Boolean, circularThumbnail: Boolean): Dp = when {
    !showThumbnail -> 0.dp
    circularThumbnail -> 16.dp
    else -> 0.dp
}

/** Reserve the same visible six-dp pane margin for native Lyrics and Visualizer seats. */
internal fun overlayCornerInset(seatSize: Dp): Dp = 6.dp + (40.dp - seatSize) / 2

/** Keeps centered page controls independent of the trailing settings pane. */
@Composable
internal fun GlassOverlayControls(
    showThumbnail: Boolean,
    circularThumbnail: Boolean,
    modifier: Modifier = Modifier,
    cornerSeatSize: Dp = 44.dp,
    centerContent: @Composable RowScope.() -> Unit = {},
    trailingContent: @Composable RowScope.() -> Unit,
) {
    Layout(
        modifier = modifier.fillMaxWidth().padding(start = overlayCornerInset(cornerSeatSize), end = overlayCornerInset(cornerSeatSize),
            bottom = overlayControlsBottomInset(showThumbnail, circularThumbnail)).height(50.dp),
        content = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically, content = centerContent)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically, content = trailingContent)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val trailing = measurables[1].measure(loose)
        val gap = 8.dp.roundToPx()
        val available = (constraints.maxWidth - trailing.width - gap).coerceAtLeast(0)
        val center = measurables[0].measure(loose.copy(maxWidth = available))
        val rightLimit = (available - center.width).coerceAtLeast(0)
        val centerX = ((constraints.maxWidth - center.width) / 2).coerceIn(0, rightLimit)
        layout(constraints.maxWidth, constraints.maxHeight) {
            center.placeRelative(centerX, (constraints.maxHeight - center.height) / 2)
            trailing.placeRelative(constraints.maxWidth - trailing.width,
                (constraints.maxHeight - trailing.height) / 2)
        }
    }
}

/** Native canvases retain their bounds; Glass leaves one plate inset plus a visible gap. */
internal fun overlayCanvasBottomReserve(settings: GlassSettings): Dp =
    if (settings.enabled) PlayerInformationVerticalPadding * 2 else 0.dp

/** Reduce the complete visible overlay inside its stable host, including background and utilities. */
@Composable
internal fun Modifier.glassOverlayCanvasViewport(settings: GlassSettings, showThumbnail: Boolean = false): Modifier {
    // Embedded scenes already occupy the painted artwork, inside its padding and crop.
    if (!settings.enabled || showThumbnail) return this
    val geometry = LocalPlayerTransportGeometry.current
    val density = LocalDensity.current
    var sourceBottom by remember(geometry) { mutableStateOf<Float?>(null) }
    val measuredReserve = geometry?.informationSurfaceTop?.let { top ->
        sourceBottom?.let { bottom -> with(density) { (bottom - top).toDp() } + PlayerInformationVerticalPadding }
    }
    val reserve = maxOf(0.dp, measuredReserve ?: overlayCanvasBottomReserve(settings))
    return onGloballyPositioned { sourceBottom = geometry?.bounds(it)?.bottom }
        .padding(bottom = reserve).clipToBounds()
}

/** A coverless overlay participates in host measurement even when it is the only child. */
internal fun Modifier.glassOverlayHostViewport(settings: GlassSettings): Modifier =
    if (settings.enabled) fillMaxSize() else this

/** Starts an entrance even when a caller mounts an already requested overlay. */
@Composable
internal fun rememberOverlayTransition(active: Boolean): MutableTransitionState<Boolean> =
    remember { MutableTransitionState(false) }.apply { targetState = active }

internal val MutableTransitionState<Boolean>.hasOverlayContent: Boolean
    get() = currentState || targetState || !isIdle

private fun Modifier.overlayInput(active: Boolean): Modifier = if (active) this else
    onPreviewKeyEvent { true }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
        .clearAndSetSemantics { }

/** Owns fade lifetime; content hosted by an ancestor reuses that fade without doubling opacity. */
@Composable
internal fun OverlayFade(
    active: Boolean,
    modifier: Modifier = Modifier,
    durationMillis: Int? = null,
    state: MutableTransitionState<Boolean>? = null,
    hosted: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (hosted) {
        content()
    } else {
        AnimatedVisibility(
            visibleState = state ?: rememberOverlayTransition(active),
            modifier = modifier.overlayInput(active),
            enter = if (durationMillis == null) fadeIn() else fadeIn(tween(durationMillis)),
            exit = if (durationMillis == null) fadeOut() else fadeOut(tween(durationMillis)),
        ) { content() }
    }
}

/** Inactive overlays install no full-size layout; closing overlays retain their real fade lifetime. */
@Composable
internal fun GlassOverlayHost(
    active: Boolean,
    settings: GlassSettings,
    modifier: Modifier = Modifier,
    fadeDurationMillis: Int? = null,
    transitionState: MutableTransitionState<Boolean> = rememberOverlayTransition(active),
    content: @Composable BoxScope.() -> Unit,
) {
    OverlayFade(active, durationMillis = fadeDurationMillis, state = transitionState) {
        Box(modifier.glassOverlayHostViewport(settings), content = content)
    }
}

/** Coverless canvases are rectangular; only overlays drawn on artwork inherit its crop. */
internal fun overlayViewportShape(showThumbnail: Boolean, thumbnailShape: Shape): Shape =
    if (showThumbnail) thumbnailShape else RectangleShape
