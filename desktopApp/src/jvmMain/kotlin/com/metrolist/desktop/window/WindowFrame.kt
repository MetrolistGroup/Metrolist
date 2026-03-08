package com.metrolist.desktop.window

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.window.jna.structure.isWindows10OrLater
import com.metrolist.shared.utils.OsUtil

@Composable
fun FrameWindowScope.WindowFrame(
    onCloseRequest: () -> Unit,
    darkTheme: Boolean,
    state: WindowState,
    captionBarHeight: Dp = 32.dp,
    lastNormalPlacement: WindowPlacement,
    content: @Composable (
        windowInset: WindowInsets,
        captionBarInset: WindowInsets,
        onMaximized: () -> Unit,
        toggleFullscreen: () -> Unit,
    ) -> Unit,
) {
    val playerFullscreenPlacement = remember { PlayerFullscreenPlacement() }

    if (!OsUtil.isWindows || !isWindows10OrLater()) {
        LaunchedEffect(state.placement) {
            if (PlayerState.isFullscreen && state.placement != WindowPlacement.Fullscreen) {
                PlayerState.isFullscreen = false
                playerFullscreenPlacement.cancel()
            }
        }
    }

    fun toggleFullscreen() {
        val transition =
            playerFullscreenPlacement.toggle(
                from = state,
                playerFullscreen = PlayerState.isFullscreen,
                fallback = lastNormalPlacement,
            )
        val nextState = transition.windowState
        // Window fullscreen is independent of the expanded player.
        PlayerState.isFullscreen = transition.playerFullscreen
        state.size = nextState.size
        state.position = nextState.position
        state.placement = nextState.placement
    }

    when {
        OsUtil.isWindows && isWindows10OrLater() -> {
            WindowsWindowFrame(
                onCloseRequest = onCloseRequest,
                content = content,
                state = state,
                captionBarHeight = captionBarHeight,
                darkTheme = darkTheme,
                lastNormalPlacement = lastNormalPlacement,
            )
        }

        OsUtil.isMac -> {
            content(WindowInsets(0), WindowInsets(0), { }, { toggleFullscreen() })
        }

        else -> {
            LinuxWindowFrame(
                onCloseRequest = onCloseRequest,
                darkTheme = darkTheme,
                state = state,
            ) { onMaximized ->
                content(WindowInsets(0), WindowInsets(0), onMaximized, { toggleFullscreen() })
            }
        }
    }
}
