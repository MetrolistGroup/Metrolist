package com.metrolist.desktop.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.AwtWindow
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import com.metrolist.desktop.utils.WaylandScale
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.WindowConstants
import kotlin.math.roundToInt

@Stable
interface DesktopFrameWindowScope : WindowScope {
    override val window: JFrame
}

@Stable
interface DesktopDialogWindowScope : WindowScope {
    override val window: JDialog
}

internal fun DesktopFrameWindowScope.asComposeFrameWindowScope(): FrameWindowScope? =
    (window as? ComposeWindow)?.let { composeWindow ->
        object : FrameWindowScope {
            override val window: ComposeWindow = composeWindow
        }
    }

internal fun applyFramePlacement(
    frame: JFrame,
    placement: WindowPlacement,
) {
    val device = frame.graphicsConfiguration?.device
    when (placement) {
        WindowPlacement.Fullscreen -> {
            if (device != null && device.fullScreenWindow !== frame) {
                device.fullScreenWindow = frame
            }
        }

        WindowPlacement.Maximized -> {
            if (device != null && device.fullScreenWindow === frame) {
                device.fullScreenWindow = null
            }
            if ((frame.extendedState and Frame.MAXIMIZED_BOTH) != Frame.MAXIMIZED_BOTH) {
                frame.extendedState = frame.extendedState or Frame.MAXIMIZED_BOTH
            }
        }

        WindowPlacement.Floating -> {
            if (device != null && device.fullScreenWindow === frame) {
                device.fullScreenWindow = null
            }
            if ((frame.extendedState and Frame.MAXIMIZED_BOTH) != 0) {
                frame.extendedState = frame.extendedState and Frame.MAXIMIZED_BOTH.inv()
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "Untitled",
    icon: Painter? = null,
    undecorated: Boolean = false,
    transparent: Boolean = false,
    resizable: Boolean = true,
    alwaysOnTop: Boolean = false,
    content: @Composable DesktopFrameWindowScope.() -> Unit,
) {
    if (!WaylandScale.isNativeWaylandActive()) {
        Window(
            onCloseRequest = onCloseRequest,
            state = state,
            visible = visible,
            title = title,
            icon = icon,
            undecorated = undecorated,
            transparent = transparent,
            resizable = resizable,
            alwaysOnTop = alwaysOnTop,
        ) {
            val scope =
                remember(window) {
                    object : DesktopFrameWindowScope {
                        override val window: JFrame = this@Window.window
                    }
                }
            scope.content()
        }
        return
    }

    // Skiko's SkiaLayer requires X11 JAWT surfaces; under JBR WLToolkit, SwingGraphics renders via Swing Graphics2D.
    val currentOnCloseRequest by rememberUpdatedState(onCloseRequest)
    val currentContent by rememberUpdatedState(content)
    val compositionLocalContext = currentCompositionLocalContext
    val compositionLocalContextState = remember { mutableStateOf(compositionLocalContext) }
    compositionLocalContextState.value = compositionLocalContext

    AwtWindow(
        visible = visible,
        create = {
            val panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
            JFrame().apply {
                defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                isUndecorated = undecorated
                isResizable = resizable
                isAlwaysOnTop = alwaysOnTop
                this.title = title
                val initialWidth =
                    state.size.width.value
                        .roundToInt()
                        .coerceAtLeast(1)
                val initialHeight =
                    state.size.height.value
                        .roundToInt()
                        .coerceAtLeast(1)
                size = Dimension(initialWidth, initialHeight)
                when (val pos = state.position) {
                    is WindowPosition.Absolute -> setLocation(pos.x.value.roundToInt(), pos.y.value.roundToInt())
                    else -> setLocationRelativeTo(null)
                }
                addWindowListener(
                    object : WindowAdapter() {
                        override fun windowClosing(e: WindowEvent) {
                            currentOnCloseRequest()
                        }

                        override fun windowIconified(e: WindowEvent) {
                            state.isMinimized = true
                        }

                        override fun windowDeiconified(e: WindowEvent) {
                            state.isMinimized = false
                        }
                    },
                )
                addWindowStateListener {
                    state.isMinimized = (extendedState and Frame.ICONIFIED) != 0
                    val isFullscreen = graphicsConfiguration?.device?.fullScreenWindow === this
                    state.placement =
                        when {
                            isFullscreen -> WindowPlacement.Fullscreen
                            (extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH -> WindowPlacement.Maximized
                            else -> WindowPlacement.Floating
                        }
                }
                addComponentListener(
                    object : ComponentAdapter() {
                        override fun componentResized(e: ComponentEvent) {
                            if (state.placement == WindowPlacement.Floating) {
                                state.size = DpSize(width.dp, height.dp)
                            }
                        }

                        override fun componentMoved(e: ComponentEvent) {
                            if (state.placement == WindowPlacement.Floating) {
                                state.position = WindowPosition(x.dp, y.dp)
                            }
                        }
                    },
                )
                contentPane.layout = BorderLayout()
                contentPane.add(panel, BorderLayout.CENTER)
                val scope =
                    object : DesktopFrameWindowScope {
                        override val window: JFrame = this@apply
                    }
                panel.setContent {
                    CompositionLocalProvider(compositionLocalContextState.value) {
                        scope.currentContent()
                    }
                }
                if (state.placement != WindowPlacement.Floating) {
                    applyFramePlacement(this, state.placement)
                }
            }
        },
        dispose = { frame ->
            frame.contentPane.components
                .filterIsInstance<ComposePanel>()
                .forEach(ComposePanel::dispose)
            frame.contentPane.removeAll()
            frame.dispose()
        },
        update = { frame ->
            if (frame.title != title) frame.title = title
            if (frame.isResizable != resizable) frame.isResizable = resizable
            if (frame.isAlwaysOnTop != alwaysOnTop) frame.isAlwaysOnTop = alwaysOnTop
            val currentlyMinimized = (frame.extendedState and Frame.ICONIFIED) != 0
            if (state.isMinimized != currentlyMinimized) {
                frame.extendedState =
                    if (state.isMinimized) {
                        frame.extendedState or Frame.ICONIFIED
                    } else {
                        frame.extendedState and Frame.ICONIFIED.inv()
                    }
            }
            applyFramePlacement(frame, state.placement)
            if (state.placement == WindowPlacement.Floating) {
                val targetWidth =
                    state.size.width.value
                        .roundToInt()
                        .coerceAtLeast(1)
                val targetHeight =
                    state.size.height.value
                        .roundToInt()
                        .coerceAtLeast(1)
                if (frame.width != targetWidth || frame.height != targetHeight) {
                    frame.size = Dimension(targetWidth, targetHeight)
                }
            }
        },
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopDialogWindow(
    onCloseRequest: () -> Unit,
    state: DialogState = rememberDialogState(),
    visible: Boolean = true,
    title: String = "Untitled",
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable DesktopDialogWindowScope.() -> Unit,
) {
    if (!WaylandScale.isNativeWaylandActive()) {
        DialogWindow(
            onCloseRequest = onCloseRequest,
            state = state,
            visible = visible,
            title = title,
            onPreviewKeyEvent = onPreviewKeyEvent,
        ) {
            val scope =
                remember(window) {
                    object : DesktopDialogWindowScope {
                        override val window: JDialog = this@DialogWindow.window
                    }
                }
            scope.content()
        }
        return
    }

    val currentOnCloseRequest by rememberUpdatedState(onCloseRequest)
    val currentOnPreviewKeyEvent by rememberUpdatedState(onPreviewKeyEvent)
    val currentContent by rememberUpdatedState(content)
    val compositionLocalContext = currentCompositionLocalContext
    val compositionLocalContextState = remember { mutableStateOf(compositionLocalContext) }
    compositionLocalContextState.value = compositionLocalContext
    val owner = remember { KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow }

    AwtWindow(
        visible = visible,
        create = {
            val panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
            JDialog(owner, title).apply {
                defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                val initialWidth =
                    state.size.width.value
                        .roundToInt()
                        .coerceAtLeast(1)
                val initialHeight =
                    state.size.height.value
                        .roundToInt()
                        .coerceAtLeast(1)
                size = Dimension(initialWidth, initialHeight)
                setLocationRelativeTo(owner)
                addWindowListener(
                    object : WindowAdapter() {
                        override fun windowClosing(e: WindowEvent) {
                            currentOnCloseRequest()
                        }
                    },
                )
                contentPane.layout = BorderLayout()
                contentPane.add(panel, BorderLayout.CENTER)
                val scope =
                    object : DesktopDialogWindowScope {
                        override val window: JDialog = this@apply
                    }
                panel.setContent {
                    CompositionLocalProvider(compositionLocalContextState.value) {
                        Box(Modifier.fillMaxSize().onPreviewKeyEvent { currentOnPreviewKeyEvent(it) }) {
                            scope.currentContent()
                        }
                    }
                }
            }
        },
        dispose = { dialog ->
            dialog.contentPane.components
                .filterIsInstance<ComposePanel>()
                .forEach(ComposePanel::dispose)
            dialog.contentPane.removeAll()
            dialog.dispose()
        },
        update = { dialog ->
            if (dialog.title != title) dialog.title = title
        },
    )
}
