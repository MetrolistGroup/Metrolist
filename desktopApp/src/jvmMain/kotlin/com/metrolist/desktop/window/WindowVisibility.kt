package com.metrolist.desktop.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.window.WindowState
import com.metrolist.shared.ui.components.LocalVisualWorkActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Frame
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener
import javax.swing.JFrame

internal class WindowVisibilityState {
    private var shown = false
    private var minimized = false
    private var workspaceVisible = true
    private val _visible = MutableStateFlow(false)
    val visible = _visible.asStateFlow()

    fun updateWindow(
        shown: Boolean,
        minimized: Boolean,
    ) {
        this.shown = shown
        this.minimized = minimized
        publish()
    }

    fun updateWorkspace(visible: Boolean) {
        workspaceVisible = visible
        publish()
    }

    private fun publish() {
        _visible.value = shown && !minimized && workspaceVisible
    }
}

@Composable
internal fun WindowVisibility(
    scope: DesktopFrameWindowScope,
    state: WindowState,
    windowShown: Boolean = true,
    content: @Composable () -> Unit,
) {
    val frame = scope.awtFrame
    if (frame != null) {
        WindowVisibility(frame, content)
        return
    }
    val taoWindow = scope.taoWindow
    val visibility = remember(taoWindow) { WindowVisibilityState() }
    val currentWindowShown by rememberUpdatedState(windowShown)
    LaunchedEffect(taoWindow, windowShown, state.isMinimized) {
        visibility.updateWindow(shown = windowShown, minimized = taoWindow?.isMinimized ?: state.isMinimized)
    }
    DisposableEffect(taoWindow, visibility) {
        // Only register multi-cast listeners; onShown/onWillHide are single-slot hooks owned by Tao's EGL host.
        taoWindow?.onMinimizedChanged { minimized -> visibility.updateWindow(shown = currentWindowShown, minimized = minimized) }
        onDispose {
            visibility.updateWindow(shown = false, minimized = false)
            if (taoWindow != null) setTaoWindowVisible(taoWindow.handle, false)
        }
    }
    LaunchedEffect(taoWindow, visibility) {
        if (taoWindow == null) return@LaunchedEffect
        try {
            visibility.visible.collect { visible -> setTaoWindowVisible(taoWindow.handle, visible) }
        } finally {
            setTaoWindowVisible(taoWindow.handle, false)
        }
    }
    CompositionLocalProvider(LocalVisualWorkActive provides visibility.visible, content = content)
}

@Composable
internal fun WindowVisibility(
    window: JFrame,
    content: @Composable () -> Unit,
) {
    ClearWindowContentOnDispose(window)
    val visibility = remember(window) { WindowVisibilityState() }
    val updateWorkspace = remember(visibility) { visibility::updateWorkspace }
    ObserveHyprlandRenderVisibility(window, updateWorkspace)
    DisposableEffect(window, visibility) {
        val renderTimeGuard = window.findSkiaLayer()?.let(::guardRenderTime)

        fun updateWindow() {
            visibility.updateWindow(window.isVisible, window.extendedState and Frame.ICONIFIED != 0)
        }
        val listener =
            object : ComponentAdapter() {
                override fun componentShown(event: ComponentEvent) = updateWindow()

                override fun componentHidden(event: ComponentEvent) = updateWindow()
            }
        val stateListener = WindowStateListener { updateWindow() }
        window.addComponentListener(listener)
        window.addWindowStateListener(stateListener)
        updateWindow()
        onDispose {
            renderTimeGuard?.close()
            visibility.updateWindow(shown = false, minimized = false)
            window.removeComponentListener(listener)
            window.removeWindowStateListener(stateListener)
        }
    }
    CompositionLocalProvider(LocalVisualWorkActive provides visibility.visible, content = content)
}
