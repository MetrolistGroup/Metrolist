package com.metrolist.shared.ui.crash

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalWindowInfo
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

@Composable
internal actual fun ObserveCrashScreenshots(onScreenshot: () -> Unit) {
    val window = LocalWindowInfo.current
    val currentCallback = rememberUpdatedState(onScreenshot)
    DisposableEffect(window) {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher =
            KeyEventDispatcher { event ->
                if (isCrashScreenshotKey(event.id, event.keyCode, window.isWindowFocused)) currentCallback.value()
                false
            }
        manager.addKeyEventDispatcher(dispatcher)
        onDispose { manager.removeKeyEventDispatcher(dispatcher) }
    }
}

fun isCrashScreenshotKey(
    eventId: Int,
    keyCode: Int,
    focused: Boolean,
): Boolean = focused && eventId == KeyEvent.KEY_RELEASED && keyCode == KeyEvent.VK_PRINTSCREEN
