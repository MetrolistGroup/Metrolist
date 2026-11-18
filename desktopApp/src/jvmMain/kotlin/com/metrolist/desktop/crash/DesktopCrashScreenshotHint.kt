package com.metrolist.desktop.crash

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.crash_screenshot_hint
import com.metrolist.shared.ui.crash.CrashScreenshotDialog
import com.metrolist.shared.ui.crash.CrashScreenshotHintText
import com.metrolist.shared.ui.crash.isCrashScreenshotKey
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import java.awt.BorderLayout
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JLabel
import javax.swing.RootPaneContainer

@OptIn(ExperimentalComposeUiApi::class)
internal fun installCrashScreenshotHint(
    window: Window,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    val container = (window as RootPaneContainer).contentPane
    val hint = JLabel(runBlocking { getString(Res.string.crash_screenshot_hint) })
    container.add(hint, BorderLayout.NORTH)
    val visible = mutableStateOf(false)
    var panel: ComposePanel? = null
    val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    val dispatcher =
        KeyEventDispatcher { event ->
            if (isCrashScreenshotKey(event.id, event.keyCode, window.isFocused)) {
                // Keep recovery independent of Compose until a screenshot needs the shared dialog.
                if (panel == null) {
                    val content = ComposePanel()
                    content.preferredSize = hint.preferredSize
                    content.setContent {
                        MaterialTheme {
                            CrashScreenshotHintText()
                            CrashScreenshotDialog(visible.value, { visible.value = false }, onCopy, onShare)
                        }
                    }
                    container.remove(hint)
                    container.add(content, BorderLayout.NORTH)
                    container.revalidate()
                    panel = content
                }
                visible.value = true
            }
            false
        }
    manager.addKeyEventDispatcher(dispatcher)
    window.addWindowListener(
        object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent) {
                manager.removeKeyEventDispatcher(dispatcher)
                panel?.dispose()
            }
        },
    )
}
