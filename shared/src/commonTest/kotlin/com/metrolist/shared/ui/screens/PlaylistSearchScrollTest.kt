package com.metrolist.shared.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistSearchScrollTest {
    @Test
    fun wideSearchStartsAtTheFirstMatchAfterScrollingOrAnEarlierSearch() {
        val unfiltered = LazyListState(80, 24)
        val filtered = LazyListState(7, 16)

        filtered.preparePlaylistSearch("", "song", isWide = true, unfiltered)

        assertEquals(0, filtered.firstVisibleItemIndex)
        assertEquals(0, filtered.firstVisibleItemScrollOffset)
        assertEquals(80, unfiltered.firstVisibleItemIndex)
        assertEquals(24, unfiltered.firstVisibleItemScrollOffset)
    }

    @Test
    fun narrowSearchKeepsTheSearchRowInViewWhenTheFilteredListTakesOver() {
        val unfiltered = LazyListState(2, 24)
        val filtered = LazyListState()

        filtered.preparePlaylistSearch("", "song", isWide = false, unfiltered)

        assertEquals(2, filtered.firstVisibleItemIndex)
        assertEquals(24, filtered.firstVisibleItemScrollOffset)
    }

    @Test
    fun editingAnActiveQueryKeepsTheFilteredPosition() {
        val filtered = LazyListState(3, 16)

        filtered.preparePlaylistSearch("so", "song", isWide = true, LazyListState(80, 24))

        assertEquals(3, filtered.firstVisibleItemIndex)
        assertEquals(16, filtered.firstVisibleItemScrollOffset)
    }

    @Test
    fun clearingTheQueryLeavesBothScrollPositionsIntact() {
        val unfiltered = LazyListState(2, 24)
        val filtered = LazyListState(3, 16)

        filtered.preparePlaylistSearch("song", "", isWide = false, unfiltered)

        assertEquals(3, filtered.firstVisibleItemIndex)
        assertEquals(16, filtered.firstVisibleItemScrollOffset)
        assertEquals(2, unfiltered.firstVisibleItemIndex)
        assertEquals(24, unfiltered.firstVisibleItemScrollOffset)
    }
}
