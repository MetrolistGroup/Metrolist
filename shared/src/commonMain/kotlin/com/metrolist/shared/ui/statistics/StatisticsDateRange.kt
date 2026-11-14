package com.metrolist.shared.ui.statistics

import com.metrolist.shared.ui.screens.StatisticsPeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
data class StatisticsDateRange(
    val start: LocalDate,
    val end: LocalDate,
) {
    init {
        require(start <= end)
    }

    // History queries include both bounds; the next local midnight handles DST correctly.
    fun epochRange(zone: TimeZone = TimeZone.currentSystemDefault()): Pair<Long, Long> =
        start.atStartOfDayIn(zone).toEpochMilliseconds() to
            (end.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds() - 1)
}

internal enum class StatisticsCalendarPeriod {
    MONDAY_WEEK,
    FRIDAY_WEEK,
    MONTH,
    YEAR,
    ;

    fun containing(date: LocalDate): StatisticsDateRange {
        val start =
            when (this) {
                MONDAY_WEEK -> date.minus(date.dayOfWeek.ordinal, DateTimeUnit.DAY)
                FRIDAY_WEEK -> date.minus((date.dayOfWeek.ordinal + 3) % 7, DateTimeUnit.DAY)
                MONTH -> LocalDate(date.year, date.month, 1)
                YEAR -> LocalDate(date.year, 1, 1)
            }
        val unit =
            when (this) {
                MONDAY_WEEK, FRIDAY_WEEK -> DateTimeUnit.WEEK
                MONTH -> DateTimeUnit.MONTH
                YEAR -> DateTimeUnit.YEAR
            }
        return StatisticsDateRange(start, start.plus(1, unit).minus(1, DateTimeUnit.DAY))
    }
}

@Serializable
enum class StatisticsPeriodMode {
    CONTINUOUS,
    CALENDAR,
}

internal expect fun statisticsFirstDayOfWeek(): DayOfWeek

fun StatisticsPeriod.calendarRange(
    clock: Clock = Clock.System,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    firstDayOfWeek: DayOfWeek = statisticsFirstDayOfWeek(),
): StatisticsDateRange? {
    val today = clock.todayIn(zone)
    val start =
        when (this) {
            StatisticsPeriod.WEEK -> today.minus((today.dayOfWeek.ordinal - firstDayOfWeek.ordinal + 7) % 7, DateTimeUnit.DAY)
            StatisticsPeriod.MONTH -> LocalDate(today.year, today.month, 1)
            StatisticsPeriod.THREE_MONTHS -> LocalDate(today.year, ((today.month.ordinal / 3) * 3) + 1, 1)
            StatisticsPeriod.SIX_MONTHS -> LocalDate(today.year, ((today.month.ordinal / 6) * 6) + 1, 1)
            StatisticsPeriod.YEAR -> LocalDate(today.year, 1, 1)
            StatisticsPeriod.ALL -> return null
        }
    return StatisticsDateRange(start, today)
}

fun StatisticsPeriod.epochRange(
    dateRange: StatisticsDateRange? = null,
    clock: Clock = Clock.System,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    mode: StatisticsPeriodMode = StatisticsPeriodMode.CONTINUOUS,
    firstDayOfWeek: DayOfWeek = statisticsFirstDayOfWeek(),
): Pair<Long, Long> {
    if (dateRange != null) return dateRange.epochRange(zone)
    val now = clock.now()
    val start =
        if (mode == StatisticsPeriodMode.CALENDAR) {
            calendarRange(clock, zone, firstDayOfWeek)?.start?.atStartOfDayIn(zone)
        } else {
            val days =
                when (this) {
                    StatisticsPeriod.WEEK -> 7
                    StatisticsPeriod.MONTH -> 30
                    StatisticsPeriod.THREE_MONTHS -> 90
                    StatisticsPeriod.SIX_MONTHS -> 180
                    StatisticsPeriod.YEAR -> 365
                    StatisticsPeriod.ALL -> null
                }
            days?.let { now.minus(it, DateTimeUnit.DAY, zone) }
        }
    return (start?.toEpochMilliseconds() ?: 0L) to now.toEpochMilliseconds()
}
