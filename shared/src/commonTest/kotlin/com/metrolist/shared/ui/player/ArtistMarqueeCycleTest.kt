package com.metrolist.shared.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtistMarqueeCycleTest {
    @Test
    fun fittingTextDoesNotScroll() {
        assertNull(artistMarqueeCycle(0, 0, 30f))
        assertNull(artistMarqueeCycle(90, 120, 30f))
        assertNull(artistMarqueeCycle(120, 120, 30f))
    }

    @Test
    fun cycleIncludesTheExistingGapAndThirtyDpPerSecondSpeed() {
        assertEquals(ArtistMarqueeCycle(360, 12000), artistMarqueeCycle(300, 180, 30f))
        assertEquals(ArtistMarqueeCycle(720, 12000), artistMarqueeCycle(600, 360, 60f))
        assertEquals(ArtistMarqueeCycle(945, 12000), artistMarqueeCycle(787, 473, 78.75f))
    }

    @Test
    fun gapRoundsToPixelsAndDurationCoversTheWholeCycle() {
        assertEquals(ArtistMarqueeCycle(134, 4467), artistMarqueeCycle(101, 100, 30f))
        assertEquals(ArtistMarqueeCycle(136, 4534), artistMarqueeCycle(102, 101, 30f))
    }
}
