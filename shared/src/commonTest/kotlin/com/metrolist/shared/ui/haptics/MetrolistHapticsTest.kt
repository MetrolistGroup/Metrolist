package com.metrolist.shared.ui.haptics

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetrolistHapticsTest {
    @Test
    fun preferenceChangesGateEveryEventWithoutRecreatingThePolicy() {
        var enabled = true
        val events = mutableListOf<HapticEvent>()
        val haptics = MetrolistHaptics({ enabled }, events::add)
        HapticEvent.entries.forEach(haptics::perform)
        enabled = false
        HapticEvent.entries.forEach(haptics::perform)
        haptics.toggle(true)
        haptics.toggle(false)
        assertEquals(HapticEvent.entries.toList(), events)
        enabled = true
        haptics.toggle(true)
        haptics.toggle(false)
        assertEquals(listOf(HapticEvent.TOGGLE_ON, HapticEvent.TOGGLE_OFF), events.takeLast(2))
    }

    @Test
    fun composeFeedbackUsesTheSameSemanticPolicy() {
        assertEquals(HapticEvent.LONG_PRESS, HapticFeedbackType.LongPress.toHapticEvent())
        assertEquals(HapticEvent.DRAG_SNAP, HapticFeedbackType.GestureThresholdActivate.toHapticEvent())
        assertEquals(HapticEvent.CONFIRM, HapticFeedbackType.Confirm.toHapticEvent())
        assertEquals(HapticEvent.REJECT, HapticFeedbackType.Reject.toHapticEvent())
        assertEquals(HapticEvent.TOGGLE_ON, HapticFeedbackType.ToggleOn.toHapticEvent())
        assertEquals(HapticEvent.TOGGLE_OFF, HapticFeedbackType.ToggleOff.toHapticEvent())
        assertEquals(HapticEvent.SEGMENT_TICK, HapticFeedbackType.SegmentFrequentTick.toHapticEvent())
    }

    @Test
    fun sliderDetentsIgnoreRepeatedValuesAndExternalResets() {
        val detents = SliderDetents(0f..1f, 3, 0f)
        assertFalse(detents.moved(0.1f))
        assertTrue(detents.moved(0.25f))
        assertFalse(detents.moved(0.26f))
        assertTrue(detents.moved(0.75f))
        assertTrue(detents.moved(1.1f))
        assertFalse(detents.moved(1.2f))
        detents.reset(0.5f)
        assertFalse(detents.moved(0.5f))
        assertTrue(detents.moved(0.25f))
        assertFalse(detents.moved(Float.NaN))
        assertFalse(detents.moved(0.25f))
        assertFalse(SliderDetents(0f..1f, 0, 0f).moved(1f))
        assertFalse(SliderDetents(1f..1f, 1, 1f).moved(1f))
    }
}
