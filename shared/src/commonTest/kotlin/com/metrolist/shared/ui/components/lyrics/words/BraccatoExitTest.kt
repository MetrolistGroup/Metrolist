package com.metrolist.shared.ui.components.lyrics.words

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BraccatoExitTest {
    @Test
    fun highlightStaysLitThroughTheCompletionWipeThenFadesBeforeTheGlowTailEnds() {
        assertEquals(1f, braccatoExitOpacity(0f, 180f))
        assertEquals(1f, braccatoExitOpacity(100f, 180f))
        assertEquals(1f, braccatoExitOpacity(180f, 180f))
        val afterWipe = braccatoExitOpacity(280f, 180f)
        val nearEnd = braccatoExitOpacity(679f, 180f)
        assertTrue(afterWipe > 0f && afterWipe < 1f)
        assertTrue(nearEnd >= 0f && nearEnd < afterWipe)
        assertEquals(0f, braccatoExitOpacity(680f, 180f))
        assertEquals(0f, braccatoExitOpacity(1_200f))
    }

    @Test
    fun resumingAfterTheExitExpiresKeepsTheHighlightUnlit() {
        assertEquals(0f, braccatoExitOpacity(15_000f))
        assertEquals(0f, braccatoExitOpacity(60_000f))
    }
}
