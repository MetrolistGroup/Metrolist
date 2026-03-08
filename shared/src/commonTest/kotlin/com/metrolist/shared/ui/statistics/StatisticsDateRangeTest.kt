package com.metrolist.shared.ui.statistics

import com.metrolist.shared.ui.screens.StatisticsPeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Instant

class StatisticsDateRangeTest {
    private val zone = TimeZone.of("Europe/Warsaw")

    private fun clock(value: String) =
        object : Clock {
            override fun now(): Instant = Instant.parse(value)
        }

    @Test
    fun rollingWindowsUseTheRequestedNumberOfLocalDays() {
        val now = clock("2024-03-31T10:30:00Z")
        val starts =
            listOf("2024-03-24T11:30:00Z", "2024-03-01T11:30:00Z", "2024-01-01T11:30:00Z", "2023-10-03T10:30:00Z", "2023-04-01T10:30:00Z")
        StatisticsPeriod.entries.dropLast(1).zip(starts).forEach { (period, start) ->
            val range = period.epochRange(clock = now, zone = zone)
            assertEquals(Instant.parse(start).toEpochMilliseconds(), range.first, period.name)
            assertEquals(now.now().toEpochMilliseconds(), range.second)
        }
    }

    @Test
    fun calendarWeeksRespectFirstDayAndYearBoundaries() {
        val now = clock("2023-01-01T12:00:00Z")
        assertEquals(LocalDate(2022, 12, 26), StatisticsPeriod.WEEK.calendarRange(now, zone, DayOfWeek.MONDAY)?.start)
        assertEquals(LocalDate(2023, 1, 1), StatisticsPeriod.WEEK.calendarRange(now, zone, DayOfWeek.SUNDAY)?.start)
        assertEquals(LocalDate(2022, 12, 30), StatisticsPeriod.WEEK.calendarRange(now, zone, DayOfWeek.FRIDAY)?.start)
    }

    @Test
    fun calendarStartsAlignWithMonthQuarterHalfYearAndYear() {
        for ((instant, starts) in listOf(
            "2024-02-29T12:00:00Z" to listOf("2024-02-01", "2024-01-01", "2024-01-01", "2024-01-01"),
            "2024-07-01T12:00:00Z" to listOf("2024-07-01", "2024-07-01", "2024-07-01", "2024-01-01"),
            "2024-12-31T12:00:00Z" to listOf("2024-12-01", "2024-10-01", "2024-07-01", "2024-01-01"),
        )) {
            val now = clock(instant)
            listOf(
                StatisticsPeriod.MONTH,
                StatisticsPeriod.THREE_MONTHS,
                StatisticsPeriod.SIX_MONTHS,
                StatisticsPeriod.YEAR,
            ).zip(starts).forEach { (period, start) ->
                val range = period.epochRange(clock = now, zone = zone, mode = StatisticsPeriodMode.CALENDAR)
                assertEquals(LocalDate.parse(start).atStartOfDayIn(zone).toEpochMilliseconds(), range.first)
                assertEquals(now.now().toEpochMilliseconds(), range.second)
            }
        }
    }

    @Test
    fun customDatesOverrideBothModesAndIncludeDstEndpoints() {
        for ((date, hours) in listOf("2024-03-31" to 23, "2024-10-27" to 25)) {
            val dates = StatisticsDateRange(LocalDate.parse(date), LocalDate.parse(date))
            val expected = dates.epochRange(zone)
            assertEquals(hours * 3_600_000L, expected.second - expected.first + 1)
            StatisticsPeriodMode.entries.forEach { mode ->
                assertEquals(expected, StatisticsPeriod.YEAR.epochRange(dates, zone = zone, mode = mode))
            }
        }
        assertFailsWith<IllegalArgumentException> { StatisticsDateRange(LocalDate(2024, 3, 2), LocalDate(2024, 3, 1)) }
    }

    @Test
    fun calendarUsesTheLocalDateAndAllKeepsItsUnboundedStart() {
        val now = clock("2024-12-31T23:30:00Z")
        assertEquals(LocalDate(2025, 1, 1), StatisticsPeriod.YEAR.calendarRange(now, zone)?.start)
        StatisticsPeriodMode.entries.forEach { mode ->
            assertEquals(0L to now.now().toEpochMilliseconds(), StatisticsPeriod.ALL.epochRange(clock = now, zone = zone, mode = mode))
        }
    }

    @Test
    fun existingCalendarPickerStillSelectsFullPastPeriods() {
        val day = LocalDate(2024, 2, 29)
        assertEquals(StatisticsDateRange(LocalDate(2024, 2, 1), day), StatisticsCalendarPeriod.MONTH.containing(day))
        assertEquals(StatisticsDateRange(LocalDate(2024, 2, 23), day), StatisticsCalendarPeriod.FRIDAY_WEEK.containing(day))
        assertEquals(
            StatisticsDateRange(LocalDate(2024, 2, 26), LocalDate(2024, 3, 3)),
            StatisticsCalendarPeriod.MONDAY_WEEK.containing(day),
        )
        assertEquals(StatisticsDateRange(LocalDate(2024, 1, 1), LocalDate(2024, 12, 31)), StatisticsCalendarPeriod.YEAR.containing(day))
    }
}
