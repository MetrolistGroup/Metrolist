package com.metrolist.shared.ui.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

class PlaybackKeyFocus {
    private val focusedControls = mutableSetOf<Any>()
    private val textInputs = mutableSetOf<Any>()
    val textInputActive: Boolean get() = textInputs.isNotEmpty()
    var keyboardNavigation = false

    val retainsFocus: Boolean get() = textInputActive || focusedControls.isNotEmpty()

    fun setFocused(
        control: Any,
        focused: Boolean,
    ) {
        if (focused) focusedControls.add(control) else focusedControls.remove(control)
    }

    fun setTextInputActive(
        session: Any,
        active: Boolean,
    ) {
        if (active) textInputs.add(session) else textInputs.remove(session)
    }

    fun allowsMediaKeys(backgroundFocused: Boolean): Boolean = !retainsFocus && (backgroundFocused || !keyboardNavigation)
}

val LocalPlaybackKeyFocus = staticCompositionLocalOf<PlaybackKeyFocus?> { null }

@Composable
fun Modifier.retainPlaybackKeyFocus(): Modifier {
    val focus = LocalPlaybackKeyFocus.current ?: return this
    val control = remember { Any() }
    DisposableEffect(focus, control) {
        onDispose { focus.setFocused(control, false) }
    }
    return onFocusChanged { focus.setFocused(control, it.hasFocus) }
}
