package com.metrolist.desktop.window

import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentEvent
import java.awt.event.WindowEvent

internal fun playbackWindowVisibility() =
    callbackFlow {
        if (GraphicsEnvironment.isHeadless()) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }

        fun update() {
            trySend(
                Window.getWindows().filterIsInstance<ComposeWindow>().any {
                    it.isShowing && it.extendedState and Frame.ICONIFIED == 0 && it.findSkiaLayer()?.isShowing != false
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
