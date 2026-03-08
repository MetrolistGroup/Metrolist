package com.metrolist.shared.ui.player

import androidx.compose.ui.unit.IntRect
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtistMarqueeFocusTest {
    @Test
    fun fullyVisibleArtistKeepsThePausedOffset() {
        assertEquals(40.5f, artistMarqueeFocusOffset(40.5f, IntRect(50, 0, 100, 24), 120))
        assertEquals(40f, artistMarqueeFocusOffset(40f, IntRect(40, 0, 160, 24), 120))
    }

    @Test
    fun clippedArtistsScrollJustEnoughToRevealTheirBounds() {
        assertEquals(30f, artistMarqueeFocusOffset(40f, IntRect(30, 0, 80, 24), 120))
        assertEquals(80f, artistMarqueeFocusOffset(40f, IntRect(150, 0, 200, 24), 120))
        assertEquals(200f, artistMarqueeFocusOffset(0f, IntRect(270, 0, 320, 24), 120))
    }

    @Test
    fun focusReturnsFromTheScrollingCopyToTheOriginalArtist() {
        assertEquals(0f, artistMarqueeFocusOffset(360f, IntRect(0, 0, 50, 24), 120))
        assertEquals(270f, artistMarqueeFocusOffset(360f, IntRect(270, 0, 320, 24), 120))
    }

    @Test
    fun artistWiderThanTheViewportShowsItsLogicalStart() {
        assertEquals(50f, artistMarqueeFocusOffset(100f, IntRect(50, 0, 250, 24), 120))
        assertEquals(50f, artistMarqueeFocusOffset(0f, IntRect(50, 0, 250, 24), 120))
    }
}
