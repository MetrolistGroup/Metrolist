package com.metrolist.desktop.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import com.metrolist.desktop.utils.WaylandScale
import dev.nucleusframework.window.tao.DecoratedDialog
import dev.nucleusframework.window.tao.DecoratedWindow
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.taoApplication
import kotlinx.coroutines.Dispatchers
import java.awt.EventQueue
import javax.swing.JDialog
import javax.swing.JFrame
import kotlin.coroutines.EmptyCoroutineContext
import androidx.compose.ui.window.ApplicationScope as AwtApplicationScope
import dev.nucleusframework.window.tao.ApplicationScope as TaoApplicationScope
import java.awt.Window as AwtWindow

@Stable
interface DesktopApplicationScope {
    val awtApplicationScope: AwtApplicationScope?
    val taoApplicationScope: TaoApplicationScope?

    fun exitApplication()
}

internal val LocalTaoApplicationScope = staticCompositionLocalOf<TaoApplicationScope?> { null }

@Stable
interface DesktopWindowScope {
    val awtWindow: AwtWindow?
    val taoWindow: TaoWindow?

    fun requestFocus()
}

@Stable
interface DesktopFrameWindowScope : DesktopWindowScope {
    val awtFrame: JFrame?
}

@Stable
interface DesktopDialogWindowScope : DesktopWindowScope {
    val awtDialog: JDialog?
}

internal fun DesktopFrameWindowScope.asComposeFrameWindowScope(): FrameWindowScope? =
    (awtFrame as? ComposeWindow)?.let { composeWindow ->
        object : FrameWindowScope {
            override val window: ComposeWindow = composeWindow
        }
    }

internal fun DesktopWindowScope.asComposeWindowScope(): WindowScope? =
    awtWindow?.let { awt ->
        object : WindowScope {
            override val window: AwtWindow = awt
        }
    }

internal fun runOnDesktopUiThread(action: () -> Unit) {
    if (WaylandScale.isNativeWaylandActive()) {
        val main = Dispatchers.Main.immediate
        if (!main.isDispatchNeeded(EmptyCoroutineContext)) {
            action()
        } else {
            Dispatchers.Main.dispatch(EmptyCoroutineContext, Runnable(action))
        }
    } else {
        EventQueue.invokeLater(action)
    }
}

internal fun desktopApplication(content: @Composable DesktopApplicationScope.() -> Unit) {
    if (WaylandScale.isNativeWaylandActive()) {
        taoApplication {
            val taoScope = this
            val scope =
                remember(taoScope) {
                    object : DesktopApplicationScope {
                        override val awtApplicationScope: AwtApplicationScope? = null
                        override val taoApplicationScope: TaoApplicationScope = taoScope

                        override fun exitApplication() = taoScope.exitApplication()
                    }
                }
            CompositionLocalProvider(LocalTaoApplicationScope provides taoScope) {
                scope.content()
            }
        }
        return
    }

    application {
        val awtScope = this
        val scope =
            remember(awtScope) {
                object : DesktopApplicationScope {
                    override val awtApplicationScope: AwtApplicationScope = awtScope
                    override val taoApplicationScope: TaoApplicationScope? = null

                    override fun exitApplication() = awtScope.exitApplication()
                }
            }
        scope.content()
    }
}

@Composable
internal fun DesktopWindow(
    onCloseRequest: () -> Unit,
    title: String,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    icon: Painter? = null,
    undecorated: Boolean = false,
    transparent: Boolean = false,
    resizable: Boolean = true,
    alwaysOnTop: Boolean = false,
    minimumSize: DpSize = DpSize.Unspecified,
    content: @Composable DesktopFrameWindowScope.() -> Unit,
) {
    val taoAppScope = LocalTaoApplicationScope.current
    if (taoAppScope != null) {
        val compositionLocalContext = currentCompositionLocalContext
        taoAppScope.DecoratedWindow(
            onCloseRequest = onCloseRequest,
            state = state,
            title = title,
            icon = icon,
            minimumSize = minimumSize,
            visible = visible,
            alwaysOnTop = alwaysOnTop,
            resizable = resizable,
            undecorated = undecorated,
            transparent = transparent,
            compositionLocalContext = compositionLocalContext,
            systemDecorations = !undecorated,
        ) {
            val tao = window
            val scope =
                remember(tao) {
                    object : DesktopFrameWindowScope {
                        override val awtWindow: AwtWindow? = null
                        override val awtFrame: JFrame? = null
                        override val taoWindow: TaoWindow = tao

                        override fun requestFocus() = tao.focus()
                    }
                }
            scope.content()
        }
        return
    }

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
        val frame = window
        val scope =
            remember(frame) {
                object : DesktopFrameWindowScope {
                    override val awtWindow: AwtWindow = frame
                    override val awtFrame: JFrame = frame
                    override val taoWindow: TaoWindow? = null

                    override fun requestFocus() {
                        frame.requestFocus()
                    }
                }
            }
        scope.content()
    }
}

@Composable
internal fun DesktopDialogWindow(
    onCloseRequest: () -> Unit,
    title: String,
    state: DialogState = rememberDialogState(),
    visible: Boolean = true,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable DesktopDialogWindowScope.() -> Unit,
) {
    val taoAppScope = LocalTaoApplicationScope.current
    if (taoAppScope != null) {
        val compositionLocalContext = currentCompositionLocalContext
        taoAppScope.DecoratedDialog(
            onCloseRequest = onCloseRequest,
            state = state,
            visible = visible,
            title = title,
            resizable = false,
            onPreviewKeyEvent = onPreviewKeyEvent,
            compositionLocalContext = compositionLocalContext,
            systemDecorations = true,
        ) {
            val tao = window
            val scope =
                remember(tao) {
                    object : DesktopDialogWindowScope {
                        override val awtWindow: AwtWindow? = null
                        override val awtDialog: JDialog? = null
                        override val taoWindow: TaoWindow = tao

                        override fun requestFocus() = tao.focus()
                    }
                }
            scope.content()
        }
        return
    }

    DialogWindow(
        onCloseRequest = onCloseRequest,
        state = state,
        visible = visible,
        title = title,
        onPreviewKeyEvent = onPreviewKeyEvent,
    ) {
        val dialog = window
        val scope =
            remember(dialog) {
                object : DesktopDialogWindowScope {
                    override val awtWindow: AwtWindow = dialog
                    override val awtDialog: JDialog = dialog
                    override val taoWindow: TaoWindow? = null

                    override fun requestFocus() {
                        dialog.requestFocus()
                    }
                }
            }
        scope.content()
    }
}
