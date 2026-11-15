package com.metrolist.desktop.window

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState

internal fun setWindowPlacement(
    state: WindowState,
    placement: WindowPlacement,
    setNativePlacement: (WindowPlacement) -> Unit,
) {
    // Compose's maximized placement does not clear native fullscreen.
    if (state.placement == WindowPlacement.Fullscreen && placement == WindowPlacement.Maximized) {
        setNativePlacement(WindowPlacement.Floating)
    }
    setNativePlacement(placement)
    state.placement = placement
}
