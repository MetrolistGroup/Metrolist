package com.metrolist.shared.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PopupMenuAnchorStateTest {
    @Test
    fun aMenuKeepsItsAnchorWhileClosingAfterTheSourceOfferWasConsumed() {
        val state = PopupMenuAnchorState()
        assertFalse(state.update(expanded = false, hasSource = false))
        assertTrue(state.update(expanded = true, hasSource = true))
        assertTrue(state.update(expanded = true, hasSource = false))
        assertTrue(state.update(expanded = false, hasSource = false))
        assertFalse(state.update(expanded = true, hasSource = false))
    }

    @Test
    fun anUnrelatedPressCannotChangeTheSourceOfAnOpenMenu() {
        val state = PopupMenuAnchorState()
        assertFalse(state.update(expanded = true, hasSource = false))
        assertFalse(state.update(expanded = true, hasSource = true))
        assertFalse(state.update(expanded = false, hasSource = true))
        assertTrue(state.update(expanded = true, hasSource = true))
    }
}
