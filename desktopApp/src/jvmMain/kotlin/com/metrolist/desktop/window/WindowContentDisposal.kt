package com.metrolist.desktop.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import javax.swing.RootPaneContainer

@Composable
internal fun ClearWindowContentOnDispose(window: RootPaneContainer) {
    DisposableEffect(window) {
        onDispose {
            // Compose leaves its disposed panel attached, so queued Swing validation can still resize it.
            window.contentPane.removeAll()
        }
    }
}
