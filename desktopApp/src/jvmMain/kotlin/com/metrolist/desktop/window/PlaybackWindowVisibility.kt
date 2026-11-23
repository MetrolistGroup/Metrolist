package com.metrolist.desktop.window

import androidx.compose.ui.awt.ComposeWindow
import com.metrolist.desktop.utils.WaylandScale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentEvent
import java.awt.event.WindowEvent
import java.util.concurrent.ConcurrentHashMap

private val visibleTaoWindows = ConcurrentHashMap.newKeySet<Long>()
private val visibleTaoWindowCount = MutableStateFlow(0)

internal fun setTaoWindowVisible(
    handle: Long,
    visible: Boolean,
) {
    if (visible) {
        visibleTaoWindows.add(handle)
    } else {
        visibleTaoWindows.remove(handle)
    }
    visibleTaoWindowCount.value = visibleTaoWindows.size
}

internal fun playbackWindowVisibility() =
    if (WaylandScale.isNativeWaylandActive()) {
        visibleTaoWindowCount.map { it > 0 }.distinctUntilChanged()
    } else {
        callbackFlow {
            if (GraphicsEnvironment.isHeadless()) {
                trySend(false)
                awaitClose { }
                return@callbackFlow
            }

            fun update() {
                trySend(
                    Window.getWindows().filterIsInstance<ComposeWindow>().any { window ->
                        window.isShowing &&
                            window.extendedState and Frame.ICONIFIED == 0 &&
                            window.findSkiaLayer()?.isShowing != false
                    },
                )
            }
            val toolkit = Toolkit.getDefaultToolkit()
            val listener =
                AWTEventListener { event ->
                    when (event.id) {
                        ComponentEvent.COMPONENT_SHOWN, ComponentEvent.COMPONENT_HIDDEN,
                        WindowEvent.WINDOW_OPENED, WindowEvent.WINDOW_CLOSED, WindowEvent.WINDOW_STATE_CHANGED,
                        -> update()
                    }
                }
            toolkit.addAWTEventListener(
                listener,
                AWTEvent.COMPONENT_EVENT_MASK or AWTEvent.WINDOW_EVENT_MASK or AWTEvent.WINDOW_STATE_EVENT_MASK,
            )
            EventQueue.invokeLater(::update)
            awaitClose { toolkit.removeAWTEventListener(listener) }
        }.conflate().distinctUntilChanged()
    }
