package com.metrolist.shared.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerControlGroupLayoutTest {
    @Test
    fun connectedEndFollowsTheLastButtonThatFitsBesideOverflow() {
        assertEquals(3, lastVisible(198f))
        assertEquals(1, lastVisible(197f))
        assertEquals(1, lastVisible(148f))
        assertEquals(0, lastVisible(147f))
        assertEquals(0, lastVisible(98f))
        assertEquals(-1, lastVisible(97f))
    }

    @Test
    fun unconstrainedAndSingleButtonGroupsKeepTheirOuterCorners() {
        assertEquals(3, lastVisible(0f))
        assertEquals(0, playerControlLastVisibleIndex(48f, 1, 48f, 2f, 48f))
        assertEquals(-1, playerControlLastVisibleIndex(48f, 0, 48f, 2f, 48f))
    }

    private fun lastVisible(width: Float): Int = playerControlLastVisibleIndex(width, 4, 48f, 2f, 48f)
}
