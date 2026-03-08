package com.metrolist.shared.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountMenuDismissalTest {
    @Test
    fun navigationWaitsUntilThePopupCloses() {
        val dismissal = AccountMenuDismissal()
        val events = mutableListOf<String>()
        dismissal.dismiss { events += "navigate" }
        assertFalse(dismissal.visible)
        assertTrue(events.isEmpty())
        dismissal.finish { events += "close" }
        assertEquals(listOf("close", "navigate"), events)
    }

    @Test
    fun repeatedDismissalCannotReplaceOrRepeatTheSelectedAction() {
        val dismissal = AccountMenuDismissal()
        val events = mutableListOf<String>()
        dismissal.dismiss { events += "selected" }
        dismissal.dismiss { events += "second tap" }
        dismissal.finish {}
        dismissal.finish {}
        assertEquals(listOf("selected"), events)
    }

    @Test
    fun outsideAndBackDismissalDoNotRunAnAction() {
        val dismissal = AccountMenuDismissal()
        var closed = false
        dismissal.dismiss()
        dismissal.finish { closed = true }
        assertFalse(dismissal.visible)
        assertTrue(closed)
    }
}
