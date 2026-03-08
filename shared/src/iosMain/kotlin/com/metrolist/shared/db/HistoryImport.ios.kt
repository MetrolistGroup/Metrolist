package com.metrolist.shared.db

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateIntervalFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSRelativeDateTimeFormatter
import platform.Foundation.NSRelativeDateTimeFormatterStyleNamed
import platform.Foundation.NSRelativeDateTimeFormatterStyleNumeric
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import kotlin.time.Instant

private enum class HistoryDateUnit(
    val durationMs: Long,
) {
    SECOND(1_000L),
    MINUTE(60_000L),
    HOUR(3_600_000L),
    DAY(86_400_000L),
    WEEK(7 * 86_400_000L),
    MONTH(30 * 86_400_000L),
    YEAR(365 * 86_400_000L),
}

internal actual fun parseYouTubeHistoryDate(
    text: String,
    now: Long,
    language: String,
): RemoteHistoryDate? {
    val locale = NSLocale(language)
    val calendar = NSCalendar(NSCalendarIdentifierGregorian).apply { this.locale = locale }
    val zone = TimeZone.currentSystemDefault()

    fun normalize(value: String) =
        value
            .trim()
            .lowercase()
            .replace('\u00a0', ' ')
            .replace('\u202f', ' ')
    val heading = normalize(text)
    if (heading.isEmpty()) return null

    fun relative(
        amount: Long,
        unit: HistoryDateUnit,
        numeric: Boolean,
    ): String? {
        // Foundation uses weekOfMonth for relative weeks.
        val components =
            NSDateComponents().apply {
                when (unit) {
                    HistoryDateUnit.SECOND -> second = amount
                    HistoryDateUnit.MINUTE -> minute = amount
                    HistoryDateUnit.HOUR -> hour = amount
                    HistoryDateUnit.DAY -> day = amount
                    HistoryDateUnit.WEEK -> weekOfMonth = amount
                    HistoryDateUnit.MONTH -> month = amount
                    HistoryDateUnit.YEAR -> year = amount
                }
            }
        return NSRelativeDateTimeFormatter()
            .apply {
                this.locale = locale
                this.calendar = calendar
                dateTimeStyle =
                    if (numeric) {
                        NSRelativeDateTimeFormatterStyleNumeric
                    } else {
                        NSRelativeDateTimeFormatterStyleNamed
                    }
            }.localizedStringFromDateComponents(components)
    }
    val headings =
        listOf(
            0L to HistoryDateUnit.SECOND,
            0L to HistoryDateUnit.DAY,
            -1L to HistoryDateUnit.DAY,
            -2L to HistoryDateUnit.DAY,
            0L to HistoryDateUnit.WEEK,
            -1L to HistoryDateUnit.WEEK,
            0L to HistoryDateUnit.MONTH,
            -1L to HistoryDateUnit.MONTH,
            0L to HistoryDateUnit.YEAR,
            -1L to HistoryDateUnit.YEAR,
        )
    val relative =
        headings.firstOrNull { (amount, unit) -> relative(amount, unit, false)?.let(::normalize) == heading }
            ?: run {
                val count =
                    Regex("""\p{N}+""")
                        .find(heading)
                        ?.value
                        ?.map { it.digitToIntOrNull() ?: return null }
                        ?.joinToString("")
                        ?.toLongOrNull()
                HistoryDateUnit.entries
                    .firstOrNull { unit ->
                        count != null && count > 0 && count <= now / unit.durationMs &&
                            relative(-count, unit, true)?.let(::normalize) == heading
                    }?.let { -checkNotNull(count) to it }
            }
    val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
    val monthFormatter =
        NSDateFormatter().apply {
            this.locale = locale
            this.calendar = calendar
            setLocalizedDateFormatFromTemplate("yMMMM")
        }
    val (start, end, unit) =
        if (relative != null) {
            val (amount, unit) = relative
            val start =
                when (unit) {
                    HistoryDateUnit.SECOND, HistoryDateUnit.MINUTE, HistoryDateUnit.HOUR -> {
                        Instant.fromEpochMilliseconds(now + amount * unit.durationMs).toLocalDateTime(zone).date
                    }

                    HistoryDateUnit.DAY -> {
                        today.plus(DatePeriod(days = amount.toInt()))
                    }

                    HistoryDateUnit.WEEK -> {
                        val firstDay = (calendar.firstWeekday.toInt() + 5) % 7
                        val elapsedDays = (today.dayOfWeek.ordinal - firstDay + 7) % 7
                        today.plus(DatePeriod(days = amount.toInt() * 7 - elapsedDays))
                    }

                    HistoryDateUnit.MONTH -> {
                        LocalDate(today.year, today.month, 1).plus(DatePeriod(months = amount.toInt()))
                    }

                    HistoryDateUnit.YEAR -> {
                        LocalDate(today.year, 1, 1).plus(DatePeriod(years = amount.toInt()))
                    }
                }
            val end =
                when (unit) {
                    HistoryDateUnit.WEEK -> start.plus(DatePeriod(days = 6))
                    HistoryDateUnit.MONTH -> start.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
                    HistoryDateUnit.YEAR -> start.plus(DatePeriod(years = 1)).minus(DatePeriod(days = 1))
                    else -> start
                }
            Triple(start, end, unit)
        } else {
            val month =
                sequenceOf(monthFormatter.dateFormat, "MMMM yyyy", "LLLL yyyy")
                    .mapNotNull { pattern ->
                        val formatter =
                            NSDateFormatter().apply {
                                this.locale = locale
                                this.calendar = calendar
                                dateFormat = pattern
                                lenient = false
                            }
                        formatter.dateFromString(text)?.takeIf { normalize(formatter.stringFromDate(it)) == heading }
                    }.firstOrNull() ?: return null
            val date = Instant.fromEpochMilliseconds((month.timeIntervalSince1970 * 1000).toLong()).toLocalDateTime(zone).date
            val start = LocalDate(date.year, date.month, 1)
            Triple(start, start.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1)), HistoryDateUnit.MONTH)
        }
    val startMs = start.atStartOfDayIn(zone).toEpochMilliseconds()
    if (startMs !in 1..now) return null

    fun nativeDate(date: LocalDate) = NSDate.dateWithTimeIntervalSince1970(date.atStartOfDayIn(zone).toEpochMilliseconds() / 1000.0)
    val label =
        if (unit == HistoryDateUnit.MONTH) {
            monthFormatter.stringFromDate(nativeDate(start))
        } else {
            NSDateIntervalFormatter()
                .apply {
                    this.locale = locale
                    this.calendar = calendar
                    dateTemplate = "yMMMd"
                }.stringFromDate(nativeDate(start), toDate = nativeDate(end))
        }
    return RemoteHistoryDate(startMs, label)
}
