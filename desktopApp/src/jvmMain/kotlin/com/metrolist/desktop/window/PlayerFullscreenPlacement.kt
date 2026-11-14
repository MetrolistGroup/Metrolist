package com.metrolist.desktop.window

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState

internal data class PlayerFullscreenTransition(
    val windowState: WindowState,
    val playerFullscreen: Boolean,
)

internal class PlayerFullscreenPlacement {
    private var previous: WindowState? = null

    fun toggle(
        from: WindowState,
        playerFullscreen: Boolean,
        fallback: WindowPlacement,
        nativeFullscreen: Boolean = from.placement == WindowPlacement.Fullscreen,
    ): PlayerFullscreenTransition {
        val normalPlacement = fallback.takeUnless { it == WindowPlacement.Fullscreen } ?: WindowPlacement.Floating
        if (playerFullscreen || nativeFullscreen) {
            val restore =
                if (nativeFullscreen) {
                    previous ?: WindowState(placement = normalPlacement, size = from.size, position = from.position)
                } else {
                    from
                }
            cancel()
            return PlayerFullscreenTransition(restore, playerFullscreen = false)
        }
        if (from.placement == WindowPlacement.Fullscreen) {
            return PlayerFullscreenTransition(
                WindowState(
                    placement = if (from.placement == WindowPlacement.Fullscreen) normalPlacement else from.placement,
                    size = from.size,
                    position = from.position,
                ),
                playerFullscreen = false,
            )
        }
        previous = WindowState(placement = from.placement, size = from.size, position = from.position)
        return PlayerFullscreenTransition(
            WindowState(placement = WindowPlacement.Fullscreen, size = from.size, position = from.position),
            playerFullscreen = true,
        )
    }

    fun cancel() {
        previous = null
    }
}
