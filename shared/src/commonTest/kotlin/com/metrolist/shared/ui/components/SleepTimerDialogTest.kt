/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class SleepTimerDialogTest {
    @Test
    fun incrementsAndDecrementsWithZeroPadding() {
        assertEquals("16", stepDurationFieldValue("15", 1, 59))
        assertEquals("14", stepDurationFieldValue("15", -1, 59))
        assertEquals("05", stepDurationFieldValue("00", 5, 59))
    }

    @Test
    fun wrapsAcrossUpperAndLowerBounds() {
        assertEquals("00", stepDurationFieldValue("59", 1, 59))
        assertEquals("59", stepDurationFieldValue("00", -1, 59))
        assertEquals("00", stepDurationFieldValue("23", 1, 23))
        assertEquals("23", stepDurationFieldValue("00", -1, 23))
        assertEquals("02", stepDurationFieldValue("58", 4, 59))
        assertEquals("57", stepDurationFieldValue("02", -5, 59))
    }

    @Test
    fun handlesEmptyOrOutOfRangeInputGracefully() {
        assertEquals("01", stepDurationFieldValue("", 1, 59))
        assertEquals("59", stepDurationFieldValue("99", 0, 59))
    }
}
