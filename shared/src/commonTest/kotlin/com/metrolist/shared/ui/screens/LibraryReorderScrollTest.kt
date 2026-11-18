package com.metrolist.shared.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryReorderScrollTest {
    @Test
    fun dragAtTopDoesNotPassUnusedScrollToPullToRefresh() {
        var dragging = false
        val connection = LibraryReorderScrollConnection { dragging }
        val available = Offset(5f, 80f)

        assertEquals(Offset.Zero, connection.onPostScroll(Offset.Zero, available, NestedScrollSource.UserInput))

        dragging = true
        assertEquals(Offset(0f, 80f), connection.onPostScroll(Offset.Zero, available, NestedScrollSource.UserInput))
        assertEquals(
            Offset(0f, -30f),
            connection.onPostScroll(Offset(0f, -20f), Offset(5f, -30f), NestedScrollSource.UserInput),
        )

        dragging = false
        assertEquals(Offset.Zero, connection.onPostScroll(Offset.Zero, available, NestedScrollSource.UserInput))
    }

    @Test
    fun dragConsumesOnlyUnusedVerticalScrollAndKeepsAutoScroll() {
        val connection = LibraryReorderScrollConnection { true }

        assertEquals(
            Offset.Zero,
            connection.onPostScroll(Offset(0f, 80f), Offset(5f, 0f), NestedScrollSource.SideEffect),
        )
        assertEquals(
            Offset(0f, 30f),
            connection.onPostScroll(Offset(0f, 50f), Offset(5f, 30f), NestedScrollSource.SideEffect),
        )
    }
}
