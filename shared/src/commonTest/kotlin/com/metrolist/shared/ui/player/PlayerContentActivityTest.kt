package com.metrolist.shared.ui.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerContentActivityTest {
    @Test
    fun lyricsAndPositionRemainActiveDuringExpansionAndCollapse() {
        for (settled in listOf(false, true, false)) {
            val state = resolvePlayerContentState(true, settled, true, false)
            assertTrue(state.isVisible)
            assertTrue(state.isPositionActive)
            assertTrue(state.isActive == settled)
        }
    }

    @Test
    fun hiddenBackgroundAndPipPlayersSuspendLyrics() {
        for (visible in listOf(false, true)) {
            for (screenVisible in listOf(false, true)) {
                for (pip in listOf(false, true)) {
                    if (visible && screenVisible && !pip) continue
                    val state = resolvePlayerContentState(visible, true, screenVisible, pip)
                    assertFalse(state.isPositionActive)
                    assertFalse(state.isActive)
                }
            }
        }
    }
}
