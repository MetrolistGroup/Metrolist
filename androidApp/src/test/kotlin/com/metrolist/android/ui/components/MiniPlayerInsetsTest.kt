package com.metrolist.android.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerInsetsTest {
    @Test
    fun detailPagesReserveTheSystemBarForEveryPlayerStyle() {
        for (systemInset in listOf(24.dp, 48.dp)) {
            for (spacing in listOf(0.dp, 8.dp)) {
                val insets = calculateMiniPlayerInsets(systemInset, 0.dp, true, 64.dp, spacing)
                assertEquals(systemInset + spacing, insets.bottomOffset)
                assertEquals(64.dp + systemInset + spacing, insets.contentBottomInset)
            }
        }
    }

    @Test
    fun attachedNavigationIncludesTheSystemInsetOnce() {
        for (systemInset in listOf(24.dp, 48.dp)) {
            for (barHeight in listOf(56.dp, 80.dp)) {
                val insets = calculateMiniPlayerInsets(systemInset, barHeight + systemInset, true, 64.dp, 8.dp)
                assertEquals(barHeight + systemInset + 8.dp, insets.bottomOffset)
                assertEquals(72.dp, insets.contentBottomInset)
            }
        }
    }

    @Test
    fun floatingNavigationReservesItsOverlayInTheList() {
        val insets = calculateMiniPlayerInsets(24.dp, 96.dp, false, 64.dp, 8.dp)
        assertEquals(104.dp, insets.bottomOffset)
        assertEquals(168.dp, insets.contentBottomInset)
    }

    @Test
    fun appearingAndDisappearingBarsKeepThePlayerAndLastRowAboveTheGestureArea() {
        for (consumesSpace in listOf(false, true)) {
            for (barHeight in (0..104).toList() + (104 downTo 0).toList()) {
                val insets = calculateMiniPlayerInsets(24.dp, barHeight.dp, consumesSpace, 64.dp, 8.dp)
                val contentViewportBottom = 800.dp - if (consumesSpace) barHeight.dp else 0.dp
                val lastRowBottom = contentViewportBottom - insets.contentBottomInset
                val playerTop = 800.dp - insets.bottomOffset - 64.dp
                assertEquals(playerTop, lastRowBottom)
                assertTrue(insets.bottomOffset >= 32.dp)
                assertTrue(insets.bottomOffset >= barHeight.dp + 8.dp)
            }
        }
    }

    @Test
    fun dismissedPlaybackLeavesOnlyTheUnconsumedNavigationInset() {
        assertEquals(0.dp, calculateMiniPlayerInsets(24.dp, 104.dp, true, 0.dp, 0.dp).contentBottomInset)
        assertEquals(104.dp, calculateMiniPlayerInsets(24.dp, 104.dp, false, 0.dp, 0.dp).contentBottomInset)
        assertEquals(24.dp, calculateMiniPlayerInsets(24.dp, 0.dp, true, 0.dp, 0.dp).contentBottomInset)
    }

    @Test
    fun landscapeWithSideNavigationDoesNotAddABottomSystemInset() {
        val insets = calculateMiniPlayerInsets(0.dp, 0.dp, false, 64.dp, 8.dp)
        assertEquals(8.dp, insets.bottomOffset)
        assertEquals(72.dp, insets.contentBottomInset)
    }
}
