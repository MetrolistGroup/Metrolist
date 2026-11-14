package com.metrolist.shared.ui.navigation

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced

@Composable
fun Modifier.navigationPageSource(albumArtworkSource: String? = null): Modifier {
    val state = LocalNavigationContainerState.current ?: return this
    val overlay = LocalNavOverlayCoordinates.current ?: return this
    val source = remember { NavigationPageSource() }

    fun offer() {
        val viewport = overlay.contentCoordinates?.takeIf { it.isAttached } ?: overlay.coordinates?.takeIf { it.isAttached } ?: return
        val control = source.coordinates?.takeIf { it.isAttached } ?: return
        val bounds = viewport.localBoundingBoxOf(control)
        if (bounds.isEmpty || viewport.size.width == 0 || viewport.size.height == 0) return
        state.pendingPageOrigin =
            TransformOrigin(
                (bounds.center.x / viewport.size.width).coerceIn(0f, 1f),
                (bounds.center.y / viewport.size.height).coerceIn(0f, 1f),
            )
        state.pendingAlbumArtworkSource = albumArtworkSource
    }
    return onPlaced { source.coordinates = it }
        .onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown) offer()
            false
        }.pointerInput(source, state, albumArtworkSource) {
            awaitPointerEventScope {
                while (true) {
                    if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) offer()
                }
            }
        }
}

private class NavigationPageSource {
    var coordinates: LayoutCoordinates? = null
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.expressiveNavigationElement(
    key: String,
    shape: Shape,
    overlayClip: SharedTransitionScope.OverlayClip? = null,
): Modifier {
    val scope = LocalNavSharedTransitionScope.current ?: return this
    val visibility = LocalNavVisibilityScope.current ?: return this
    val state = LocalNavigationContainerState.current
    if (state?.elementPattern != NavigationPattern.Expressive || !rememberNavigationMotion().enabled) return this
    val spatial = MotionScheme.expressive().defaultSpatialSpec<Rect>()
    return with(scope) {
        sharedElement(
            rememberSharedContentState(key),
            animatedVisibilityScope = visibility,
            boundsTransform = BoundsTransform { _, _ -> spatial },
            clipInOverlayDuringTransition = overlayClip ?: OverlayClip(shape),
        )
    }
}
