package com.metrolist.desktop.state.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerPresentationStateTest {
    @Test
    fun switchingArtworkAndVideoKeepsThePlayerAndWindowFullscreen() {
        val state = PlayerPresentationState()
        state.enterExpanded()
        state.isFullscreen = true

        repeat(3) {
            state.toggleFullscreenVideo()
            assertTrue(state.isExpanded)
            assertTrue(state.isFullscreen)
            assertTrue(state.showVideoFullscreen)

            state.toggleFullscreenVideo()
            assertTrue(state.isExpanded)
            assertTrue(state.isFullscreen)
            assertFalse(state.showVideoFullscreen)
        }
    }

    @Test
    fun closingEitherFullscreenPresentationReturnsToThePlayerBar() {
        for (video in listOf(false, true)) {
            val state = PlayerPresentationState()
            state.enterExpanded()
            state.isFullscreen = true
            state.showVideoFullscreen = video

            state.isFullscreen = false
            state.closePlayer()

            assertFalse(state.isExpanded)
            assertFalse(state.isFullscreen)
            assertFalse(state.showVideoFullscreen)

            state.enterExpanded()
            assertFalse(state.showVideoFullscreen)
        }
    }

    @Test
    fun windowFullscreenExitAloneKeepsTheExpandedPlayer() {
        val state = PlayerPresentationState()
        state.enterExpanded()
        state.isFullscreen = true
        state.isFullscreen = false

        assertTrue(state.isExpanded)
        assertFalse(state.showVideoFullscreen)

        state.toggleFullscreenVideo()
        assertTrue(state.isExpanded)
        assertTrue(state.showVideoFullscreen)
        assertFalse(state.isFullscreen)
    }
}
