package com.metrolist.desktop.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.metrolist.desktop.state.app.UiState
import com.metrolist.shared.settings.Settings

@Composable
internal fun LinuxWindowFrame(
    onCloseRequest: () -> Unit,
    darkTheme: Boolean,
    state: WindowState,
    content: @Composable (onMaximized: () -> Unit) -> Unit,
) {
    Box {
        fun onMaximized() {
            state.placement =
                if (state.placement == WindowPlacement.Maximized || state.placement == WindowPlacement.Fullscreen) {
                    WindowPlacement.Floating
                } else {
                    WindowPlacement.Maximized
                }
            UiState.isMaximized =
                state.placement == WindowPlacement.Maximized || state.placement == WindowPlacement.Fullscreen
        }
        content { onMaximized() }
        if (state.placement != WindowPlacement.Fullscreen && !Settings.shared.enableSystemWindowFrame) {
            Row(modifier = Modifier.fillMaxWidth().height(32.dp)) {
                Box(Modifier.weight(1f))
                CaptionButton(
                    onClick = {
                        state.isMinimized = true
                    },
                    icon = CaptionButtonIcon.Minimize,
                    isActive = true,
                    isCloseButton = false,
                    darkTheme = darkTheme,
                )
                CaptionButton(
                    onClick = { onMaximized() },
                    icon =
                        if (state.placement == WindowPlacement.Maximized || state.placement == WindowPlacement.Fullscreen) {
                            CaptionButtonIcon.Restore
                        } else {
                            CaptionButtonIcon.Maximize
                        },
                    isActive = true,
                    isCloseButton = false,
                    darkTheme = darkTheme,
                )
                CaptionButton(
                    icon = CaptionButtonIcon.Close,
                    onClick = onCloseRequest,
                    isActive = true,
                    isCloseButton = true,
                    darkTheme = darkTheme,
                )
            }
        }
    }
}
