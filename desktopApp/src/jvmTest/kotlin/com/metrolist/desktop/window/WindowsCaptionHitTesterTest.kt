package com.metrolist.desktop.window

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import com.metrolist.desktop.ui.components.DesktopTitleBarHeight
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTCAPTION
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTCLIENT
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTCLOSE
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTMAXBUTTON
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTMINBUTTON
import com.metrolist.shared.ui.navigation.PopupSourceResetHandler
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsCaptionHitTesterTest {
    @Test
    fun popupSourceObserverDoesNotClaimBlankCaption() {
        val observer = SuspendingPointerInputModifierNode(PopupSourceResetHandler(null))
        val control = SuspendingPointerInputModifierNode { }
        val frame = frame()

        assertEquals(HTCAPTION, frame.hitTest(200f, 16f, false) { _, _ -> hasInteractivePointerInput(listOf(observer)) })
        for (nodes in listOf(listOf(observer, control), listOf(control, observer))) {
            assertEquals(HTCLIENT, frame.hitTest(200f, 16f, false) { _, _ -> hasInteractivePointerInput(nodes) })
        }
    }

    @Test
    fun fullTitleBarRemainsNativeBelowTheWindowButtons() {
        for (scale in listOf(1f, 1.25f, 1.5f, 2f)) {
            val frame = frame()
            val top = 8f * scale
            val bottom = top + DesktopTitleBarHeight.value * scale
            frame.captionBar = Rect(0f, top, 1000f * scale, bottom)
            val search = Rect(300f * scale, top + 6f * scale, 700f * scale, bottom - 6f * scale)
            for (y in listOf(32f, 40f, 47f)) {
                assertEquals(
                    HTCAPTION,
                    frame.hitTest(800f * scale, top + y * scale, false) { x, hitY -> search.contains(x, hitY) },
                )
            }
            assertEquals(
                HTCLIENT,
                frame.hitTest(500f * scale, top + 40f * scale, false) { x, y -> search.contains(x, y) },
            )
            assertEquals(HTCLIENT, frame.hitTest(800f * scale, bottom, false) { _, _ -> true })
        }
    }

    @Test
    fun blankCaptionRemainsNativeAfterRestoreAndScaling() {
        val frame = WindowsCaptionHitTester()
        for ((scale, top) in listOf(1f to 8f, 1f to 0f, 1.5f to 12f, 1.5f to 0f, 1f to 0f)) {
            frame.captionBar = Rect(0f, top, 1000f * scale, top + 32f * scale)
            assertEquals(
                HTCAPTION,
                frame.hitTest(200f * scale, top + 16f * scale, false) { _, y ->
                    y >= frame.captionBar.bottom
                },
            )
            assertEquals(HTCLIENT, frame.hitTest(200f * scale, frame.captionBar.bottom, false) { _, _ -> true })
        }
    }

    @Test
    fun pointerControlsKeepClientInputWithinTheCaption() {
        val frame = frame()
        val controls = listOf(Rect(50f, 0f, 90f, 48f), Rect(100f, 0f, 140f, 48f), Rect(300f, 6f, 700f, 42f))
        for (x in listOf(70f, 120f, 500f, 200f, 800f)) {
            val overControl = controls.any { it.contains(x, 16f) }
            assertEquals(
                if (overControl) HTCLIENT else HTCAPTION,
                frame.hitTest(x, 16f, false) { hitX, hitY -> controls.any { it.contains(hitX, hitY) } },
            )
        }
    }

    @Test
    fun nativeButtonsTakePriorityOverContentAndExpandedPlayer() {
        val frame = frame()
        for (expanded in listOf(false, true)) {
            for ((x, expected) in listOf(880f to HTMINBUTTON, 930f to HTMAXBUTTON, 980f to HTCLOSE)) {
                assertEquals(expected, frame.hitTest(x, 16f, expanded) { _, _ -> error("Native button reached content") })
            }
        }
        assertEquals(HTCLIENT, frame.hitTest(20f, 16f, true) { _, _ -> error("Back button reached content") })
        assertEquals(HTCAPTION, frame.hitTest(200f, 16f, true) { _, _ -> true })
    }

    @Test
    fun captionBoundsExcludeTheContentAndHiddenCaption() {
        val frame = WindowsCaptionHitTester()
        assertEquals(HTCLIENT, frame.hitTest(0f, 0f, false) { _, _ -> error("Empty caption reached content") })
        frame.captionBar = Rect(0f, 8f, 1000f, 40f)
        for ((x, y) in listOf(-1f to 16f, 1000f to 16f, 200f to 7f, 200f to 40f)) {
            assertEquals(HTCLIENT, frame.hitTest(x, y, false) { _, _ -> error("Outside caption reached content") })
        }
        assertEquals(HTCAPTION, frame.hitTest(0f, 8f, false) { _, _ -> false })
        frame.captionBar = Rect(0f, -32f, 1000f, 0f)
        assertEquals(HTCLIENT, frame.hitTest(200f, 0f, true) { _, _ -> false })
    }

    private fun frame() =
        WindowsCaptionHitTester().apply {
            captionBar = Rect(0f, 0f, 1000f, 32f)
            minimizeButton = Rect(862f, 0f, 908f, 32f)
            maximizeButton = Rect(908f, 0f, 954f, 32f)
            closeButton = Rect(954f, 0f, 1000f, 32f)
            backButton = Rect(0f, 0f, 46f, 32f)
        }

    private fun Rect.contains(
        x: Float,
        y: Float,
    ): Boolean = x >= left && x < right && y >= top && y < bottom
}
