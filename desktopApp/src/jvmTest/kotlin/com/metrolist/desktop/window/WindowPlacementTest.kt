package com.metrolist.desktop.window

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowPlacementTest {
    @Test
    fun fullscreenExitClearsNativeFullscreenBeforeRestoringMaximized() {
        val native = NativePlacement()
        val state = WindowState(placement = WindowPlacement.Maximized)
        repeat(2) {
            setWindowPlacement(state, WindowPlacement.Fullscreen, native::set)
            assertTrue(native.fullscreen)
            setWindowPlacement(state, WindowPlacement.Maximized, native::set)
            assertFalse(native.fullscreen)
            assertTrue(native.maximized)
            assertEquals(WindowPlacement.Maximized, state.placement)
        }
    }

    @Test
    fun fullscreenExitRestoresFloatingWithoutMaximizing() {
        val native = NativePlacement()
        val state = WindowState()
        setWindowPlacement(state, WindowPlacement.Fullscreen, native::set)
        setWindowPlacement(state, WindowPlacement.Floating, native::set)
        assertFalse(native.fullscreen)
        assertFalse(native.maximized)
        assertEquals(WindowPlacement.Floating, state.placement)
    }

    private class NativePlacement {
        var fullscreen = false
        var maximized = false

        fun set(placement: WindowPlacement) {
            when (placement) {
                WindowPlacement.Fullscreen -> {
                    fullscreen = true
                }

                WindowPlacement.Maximized -> {
                    maximized = true
                }

                WindowPlacement.Floating -> {
                    fullscreen = false
                    maximized = false
                }
            }
        }
    }
}
