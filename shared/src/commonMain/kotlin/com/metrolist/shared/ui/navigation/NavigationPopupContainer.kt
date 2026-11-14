package com.metrolist.shared.ui.navigation

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputEventHandler
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize

internal data class NavigationPopupOrigin(
    val source: NavigationPopupSource,
    val windowBounds: Rect,
    val screenBounds: Rect,
    val shape: Shape,
)

internal class NavigationPopupSource {
    var coordinates: LayoutCoordinates? = null
}

internal fun Modifier.resetPopupSource(state: NavigationContainerState?): Modifier =
    onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown) {
            state?.pendingPopup = null
            state?.pendingPageOrigin = null
            state?.pendingAlbumArtworkSource = null
        }
        false
    }.pointerInput(state, block = PopupSourceResetHandler(state))

class PopupSourceResetHandler(
    private val state: NavigationContainerState?,
) : PointerInputEventHandler {
    override suspend fun PointerInputScope.invoke() {
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) {
                    state?.pendingPopup = null
                    state?.pendingPageOrigin = null
                    state?.pendingAlbumArtworkSource = null
                }
            }
        }
    }
}

@Composable
fun Modifier.navigationPopupSource(shape: Shape = CircleShape): Modifier {
    val state = LocalNavigationContainerState.current ?: return this
    val source = remember { NavigationPopupSource() }

    fun offer() {
        val coordinates = source.coordinates?.takeIf { it.isAttached } ?: return
        val size = coordinates.size.toSize()
        val end = Offset(size.width, size.height)
        val window = Rect(coordinates.localToWindow(Offset.Zero), coordinates.localToWindow(end))
        val screen = Rect(coordinates.localToScreen(Offset.Zero), coordinates.localToScreen(end))
        if (window.isUsable() && screen.isUsable()) state.pendingPopup = NavigationPopupOrigin(source, window, screen, shape)
    }
    return onGloballyPositioned { source.coordinates = it }
        .onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.Spacebar || it.key == Key.DirectionCenter)) offer()
            false
        }.pointerInput(source, state, shape) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) offer()
                }
            }
        }
}

internal fun Rect.isUsable(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && width.isFinite() && height.isFinite() && width > 0f &&
        height > 0f
