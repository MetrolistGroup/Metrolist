package com.metrolist.desktop.window

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerFullscreenPlacementTest {
    @Test
    fun windowsBorderlessFullscreenRestoresBoundsEvenWhenComposePlacementHasNotChanged() {
        for (placement in listOf(WindowPlacement.Floating, WindowPlacement.Maximized)) {
            val fullscreen = PlayerFullscreenPlacement()
            val original = WindowState(placement = placement, size = DpSize(1100.dp, 720.dp), position = WindowPosition(40.dp, 50.dp))
            fullscreen.toggle(original, false, WindowPlacement.Floating)

            val borderless = WindowState(placement = placement, size = DpSize(1920.dp, 1080.dp), position = WindowPosition(0.dp, 0.dp))
            val exited = fullscreen.toggle(borderless, false, WindowPlacement.Floating, nativeFullscreen = true)

            assertFalse(exited.playerFullscreen)
            assertEquals(placement, exited.windowState.placement)
            assertEquals(original.size, exited.windowState.size)
            assertEquals(original.position, exited.windowState.position)
        }
    }

    @Test
    fun restoresPlacementAndBoundsAcrossRepeatedFullscreenSessions() {
        val fullscreen = PlayerFullscreenPlacement()
        for (placement in listOf(WindowPlacement.Floating, WindowPlacement.Maximized)) {
            val state =
                WindowState(
                    placement = placement,
                    size = DpSize(1100.dp, 720.dp),
                    position = WindowPosition((-1400).dp, 120.dp),
                )
            val entered = fullscreen.toggle(state, false, WindowPlacement.Floating)
            assertEquals(WindowPlacement.Fullscreen, entered.windowState.placement)
            assertTrue(entered.playerFullscreen)

            state.placement = WindowPlacement.Fullscreen
            state.size = DpSize(1920.dp, 1080.dp)
            state.position = WindowPosition(0.dp, 0.dp)

            val exited = fullscreen.toggle(state, true, WindowPlacement.Floating)
            assertFalse(exited.playerFullscreen)
            val restored = exited.windowState
            assertEquals(placement, restored.placement)
            assertEquals(DpSize(1100.dp, 720.dp), restored.size)
            assertEquals(WindowPosition((-1400).dp, 120.dp), restored.position)
        }
    }

    @Test
    fun nativeFullscreenExitRestoresTheFallbackPlacement() {
        run {
            val fullscreen = PlayerFullscreenPlacement()
            val state =
                WindowState(
                    placement = WindowPlacement.Fullscreen,
                    size = DpSize(1100.dp, 720.dp),
                    position = WindowPosition((-1400).dp, 120.dp),
                )
            val transition = fullscreen.toggle(state, false, WindowPlacement.Maximized)
            assertFalse(transition.playerFullscreen)
            assertEquals(WindowPlacement.Maximized, transition.windowState.placement)
            assertEquals(state.size, transition.windowState.size)
            assertEquals(state.position, transition.windowState.position)
        }
    }

    @Test
    fun exitRestoresTheSavedWindowState() {
        val fullscreen = PlayerFullscreenPlacement()
        fullscreen.toggle(WindowState(placement = WindowPlacement.Maximized), false, WindowPlacement.Floating)
        val restored =
            fullscreen
                .toggle(
                    WindowState(placement = WindowPlacement.Fullscreen),
                    true,
                    WindowPlacement.Floating,
                ).windowState
        assertEquals(WindowPlacement.Maximized, restored.placement)
    }

    @Test
    fun nativeExitKeepsTheNewBoundsAndDiscardsTheOldSnapshot() {
        val fullscreen = PlayerFullscreenPlacement()
        fullscreen.toggle(WindowState(placement = WindowPlacement.Maximized), false, WindowPlacement.Floating)
        val nativeExit = WindowState(size = DpSize(900.dp, 600.dp), position = WindowPosition(40.dp, 50.dp))
        val restored = fullscreen.toggle(nativeExit, true, WindowPlacement.Maximized).windowState
        assertEquals(nativeExit, restored)

        fullscreen.toggle(nativeExit, false, WindowPlacement.Maximized)
        fullscreen.cancel()
        val afterCancel =
            fullscreen
                .toggle(
                    WindowState(placement = WindowPlacement.Fullscreen),
                    true,
                    WindowPlacement.Maximized,
                ).windowState
        assertEquals(WindowPlacement.Maximized, afterCancel.placement)
    }
}
