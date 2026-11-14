/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.WindowScope
import com.metrolist.desktop.utils.WaylandScale
import com.metrolist.shared.settings.DensityScale
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.OsUtil
import kotlinx.coroutines.delay
import java.awt.Frame
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.beans.PropertyChangeListener
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun WindowScope.DesktopDensityProvider(content: @Composable () -> Unit) {
    val userScale = DensityScale.fromValue(Settings.shared.densityScaleFactor).value
    val systemDensity = LocalDensity.current
    if (!OsUtil.isLinux || !WaylandScale.isWayland() || Settings.shared.disableWaylandScaleFix) {
        CompositionLocalProvider(LocalDensity provides Density(systemDensity.density * userScale, systemDensity.fontScale)) {
            content()
        }
        return
    }

    val window = window
    var scale by remember { mutableStateOf(WaylandScale.getWindowScale(window.x, window.y, window.width, window.height)) }

    var visible by remember { mutableStateOf(window.isVisible && (window !is Frame || window.extendedState and Frame.ICONIFIED == 0)) }

    DisposableEffect(window) {
        fun refreshVisibility() {
            visible = window.isVisible && (window !is Frame || window.extendedState and Frame.ICONIFIED == 0)
            if (visible) WaylandScale.requestMonitorRefresh()
        }
        val listener =
            object : ComponentAdapter() {
                override fun componentShown(e: ComponentEvent) = refreshVisibility()

                override fun componentHidden(e: ComponentEvent) = refreshVisibility()

                override fun componentMoved(e: ComponentEvent) {
                    WaylandScale.requestMonitorRefresh()
                    val newScale = WaylandScale.getWindowScale(window.x, window.y, window.width, window.height)
                    if (newScale != scale) {
                        scale = newScale
                    }
                }
            }

        val focusListener =
            object : WindowAdapter() {
                override fun windowStateChanged(e: WindowEvent) = refreshVisibility()

                override fun windowGainedFocus(e: WindowEvent) {
                    WaylandScale.requestMonitorRefresh()
                }
            }
        val screenListener = PropertyChangeListener { WaylandScale.requestMonitorRefresh() }
        window.addWindowStateListener(focusListener)
        window.addWindowFocusListener(focusListener)
        window.addPropertyChangeListener("graphicsConfiguration", screenListener)
        window.addComponentListener(listener)
        onDispose {
            window.removeComponentListener(listener)
            window.removeWindowStateListener(focusListener)
            window.removeWindowFocusListener(focusListener)
            window.removePropertyChangeListener("graphicsConfiguration", screenListener)
        }
    }

    // Compositors sometimes swallow move events during active drag, so poll the in-memory monitor cache.
    LaunchedEffect(window, visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            val newScale = WaylandScale.getWindowScale(window.x, window.y, window.width, window.height)
            if (newScale != scale) {
                scale = newScale
            }
            delay(200.milliseconds)
        }
    }

    val density = Density((scale ?: systemDensity.density) * userScale, systemDensity.fontScale)
    CompositionLocalProvider(LocalDensity provides density) {
        content()
    }
}
