package com.metrolist.desktop.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.ComposeWindow
import com.metrolist.shared.ui.components.LocalVisualWorkActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Frame
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener

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
    window: ComposeWindow,
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
