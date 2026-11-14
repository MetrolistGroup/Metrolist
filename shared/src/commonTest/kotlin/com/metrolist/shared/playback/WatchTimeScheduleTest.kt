package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchTimeScheduleTest {
    @Test
    fun serverSchedulePrecedesRecurringInterval() {
        val schedule = WatchTimeSchedule(listOf(30, 10, 20), defaultIntervalSeconds = 40)

        assertEquals(10_000L, schedule.flushOffsetMs(0))
        assertEquals(20_000L, schedule.flushOffsetMs(1))
        assertEquals(30_000L, schedule.flushOffsetMs(2))
        assertEquals(70_000L, schedule.flushOffsetMs(3))
        assertEquals(110_000L, schedule.flushOffsetMs(4))
    }

    @Test
    fun omittedServerCadenceDoesNotInventOne() {
        val schedule = WatchTimeSchedule(scheduledSeconds = null, defaultIntervalSeconds = null)

        assertNull(schedule.flushOffsetMs(0))
    }

    @Test
    fun intervalAloneStartsAtItsFirstOffset() {
        val schedule = WatchTimeSchedule(scheduledSeconds = null, defaultIntervalSeconds = 15)

        assertEquals(15_000L, schedule.flushOffsetMs(0))
        assertEquals(30_000L, schedule.flushOffsetMs(1))
    }
}
