package com.metrolist.android.ui

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import androidx.core.view.ViewConfigurationCompat
import com.metrolist.shared.ui.utils.MouseScrollFactors

@Composable
internal fun rememberMouseScrollFactors(): MouseScrollFactors {
    val context = LocalContext.current
    return remember(context) {
        val configuration = ViewConfiguration.get(context)
        MouseScrollFactors(
            ViewConfigurationCompat.getScaledHorizontalScrollFactor(configuration, context),
            ViewConfigurationCompat.getScaledVerticalScrollFactor(configuration, context),
        )
    }
}

internal fun hasMousePointer(sources: Int): Boolean =
    sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE &&
        sources and InputDevice.SOURCE_TOUCHPAD != InputDevice.SOURCE_TOUCHPAD

@Composable
internal fun rememberHasMouseDevice(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { requireNotNull(context.getSystemService<InputManager>()) }

    fun hasMouse() =
        manager.inputDeviceIds.any { id ->
            manager.getInputDevice(id)?.sources?.let(::hasMousePointer) == true
        }
    var present by remember(manager) { mutableStateOf(hasMouse()) }
    DisposableEffect(manager) {
        val listener =
            object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) {
                    present = hasMouse()
                }

                override fun onInputDeviceRemoved(deviceId: Int) {
                    present = hasMouse()
                }

                override fun onInputDeviceChanged(deviceId: Int) {
                    present = hasMouse()
                }
            }
        manager.registerInputDeviceListener(listener, null)
        present = hasMouse()
        onDispose { manager.unregisterInputDeviceListener(listener) }
    }
    return present
}
