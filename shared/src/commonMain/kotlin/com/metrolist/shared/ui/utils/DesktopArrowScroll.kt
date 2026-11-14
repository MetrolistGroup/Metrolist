package com.metrolist.shared.ui.utils

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.metrolist.shared.utils.isDesktop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun Modifier.desktopArrowScroll(
    state: ScrollableState,
    horizontal: Boolean = false,
    enabled: Boolean = true,
): Modifier {
    if (!isDesktop) return this
    val scrollEnabled by rememberUpdatedState(enabled)
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 48.dp.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return this
        .pointerInput(focusRequester) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (scrollEnabled && event.type == PointerEventType.Press && event.buttons.isPrimaryPressed) {
                        focusRequester.requestFocus()
                    }
                }
            }
        }.onKeyEvent { event ->
            scrollEnabled && handleDesktopArrowScroll(event, state, horizontal, rtl, step, scope)
        }.focusRequester(focusRequester)
        .focusable()
}

internal fun handleDesktopArrowScroll(
    event: KeyEvent,
    state: ScrollableState,
    horizontal: Boolean,
    rtl: Boolean,
    step: Float,
    scope: CoroutineScope,
): Boolean {
    val delta = desktopArrowScrollDelta(event, horizontal, rtl, step)
    if (delta == 0f) return false
    scope.launch { state.scroll(MutatePriority.UserInput) { scrollBy(delta) } }
    return true
}

private fun desktopArrowScrollDelta(
    event: KeyEvent,
    horizontal: Boolean,
    rtl: Boolean,
    step: Float,
): Float {
    if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed || event.isShiftPressed || event.isAltPressed) {
        return 0f
    }
    return if (horizontal) {
        val direction = if (rtl) -1 else 1
        when (event.key) {
            Key.DirectionRight -> step * direction
            Key.DirectionLeft -> -step * direction
            else -> 0f
        }
    } else {
        when (event.key) {
            Key.DirectionDown -> step
            Key.DirectionUp -> -step
            else -> 0f
        }
    }
}
