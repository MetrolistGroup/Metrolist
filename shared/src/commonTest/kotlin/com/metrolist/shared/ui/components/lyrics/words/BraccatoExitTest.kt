package com.metrolist.shared.ui.components.lyrics.words

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BraccatoExitTest {
    @Test
    fun highlightFadesDuringTheCompletionWipeAndReachesZeroBeforeTheGlowTailEnds() {
        assertEquals(1f, braccatoExitOpacity(0f))
        val duringWipe = braccatoExitOpacity(100f)
        val afterWipe = braccatoExitOpacity(180f)
        val nearEnd = braccatoExitOpacity(499f)
        assertTrue(duringWipe in 0f..1f && duringWipe < 1f)
        assertTrue(afterWipe > 0f && afterWipe < duringWipe)
        assertTrue(nearEnd >= 0f && nearEnd < afterWipe)
        assertEquals(0f, braccatoExitOpacity(500f))
        assertEquals(0f, braccatoExitOpacity(1_200f))
    }

    @Test
    fun resumingAfterTheExitExpiresKeepsTheHighlightUnlit() {
        assertEquals(0f, braccatoExitOpacity(15_000f))
        assertEquals(0f, braccatoExitOpacity(60_000f))
    }
}
