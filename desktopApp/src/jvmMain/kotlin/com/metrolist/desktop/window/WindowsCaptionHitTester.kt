package com.metrolist.desktop.window

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTCAPTION
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTCLIENT
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTCLOSE
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTMAXBUTTON
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTMINBUTTON

internal class WindowsCaptionHitTester {
    var captionBar by mutableStateOf(Rect.Zero)
    var maximizeButton by mutableStateOf(Rect.Zero)
    var minimizeButton by mutableStateOf(Rect.Zero)
    var closeButton by mutableStateOf(Rect.Zero)
    var backButton by mutableStateOf(Rect.Zero)

    fun hitTest(
        x: Float,
        y: Float,
        playerExpanded: Boolean,
        contentHitTest: (Float, Float) -> Boolean,
    ): Int =
        when {
            maximizeButton.contains(x, y) -> HTMAXBUTTON
            minimizeButton.contains(x, y) -> HTMINBUTTON
            closeButton.contains(x, y) -> HTCLOSE
            playerExpanded && backButton.contains(x, y) -> HTCLIENT
            playerExpanded && captionBar.contains(x, y) -> HTCAPTION
            captionBar.contains(x, y) && !contentHitTest(x, y) -> HTCAPTION
            else -> HTCLIENT
        }
}

private fun Rect.contains(
    x: Float,
    y: Float,
): Boolean = x in left..<right && y >= top && y < bottom
