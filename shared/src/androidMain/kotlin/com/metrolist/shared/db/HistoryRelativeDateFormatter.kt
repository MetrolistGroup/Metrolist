package com.metrolist.shared.db

import android.icu.text.DateIntervalFormat
import android.icu.text.DateTimePatternGenerator
import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit
import android.icu.text.RelativeDateTimeFormatter.Direction
import android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit
import android.icu.util.DateInterval
import android.icu.util.TimeZone
import android.os.Build
import java.util.Locale
import kotlin.math.abs

internal actual fun historyRelativeDateFormatter(locale: Locale): (Double, HistoryDateUnit, Boolean) -> String {
    val formatter = RelativeDateTimeFormatter.getInstance(locale)
    return { amount, unit, numeric ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val relativeUnit = RelativeDateTimeUnit.valueOf(unit.name)
            if (numeric) formatter.formatNumeric(amount, relativeUnit) else formatter.format(amount, relativeUnit)
        } else {
            formatLegacy(formatter, amount, unit, numeric)
        }
    }
}

private fun formatLegacy(
    formatter: RelativeDateTimeFormatter,
    amount: Double,
    unit: HistoryDateUnit,
    numeric: Boolean,
): String {
    if (!numeric) {
        if (unit == HistoryDateUnit.SECOND && amount == 0.0) return formatter.format(Direction.PLAIN, AbsoluteUnit.NOW)
        val absoluteUnit =
            when (unit) {
                HistoryDateUnit.DAY -> AbsoluteUnit.DAY
                HistoryDateUnit.WEEK -> AbsoluteUnit.WEEK
                HistoryDateUnit.MONTH -> AbsoluteUnit.MONTH
                HistoryDateUnit.YEAR -> AbsoluteUnit.YEAR
                else -> null
            }
        val direction =
            when (amount) {
                -2.0 -> Direction.LAST_2
                -1.0 -> Direction.LAST
                0.0 -> Direction.THIS
                1.0 -> Direction.NEXT
                2.0 -> Direction.NEXT_2
                else -> null
            }
        if (absoluteUnit != null && direction != null) formatter.format(direction, absoluteUnit)?.let { return it }
    }
    val relativeUnit =
        when (unit) {
            HistoryDateUnit.SECOND -> RelativeUnit.SECONDS
            HistoryDateUnit.MINUTE -> RelativeUnit.MINUTES
            HistoryDateUnit.HOUR -> RelativeUnit.HOURS
            HistoryDateUnit.DAY -> RelativeUnit.DAYS
            HistoryDateUnit.WEEK -> RelativeUnit.WEEKS
            HistoryDateUnit.MONTH -> RelativeUnit.MONTHS
            HistoryDateUnit.YEAR -> RelativeUnit.YEARS
        }
    return formatter.format(abs(amount), if (amount < 0) Direction.LAST else Direction.NEXT, relativeUnit)
}

internal actual fun historyYearMonthPattern(locale: Locale): String = DateTimePatternGenerator.getInstance(locale).getBestPattern("yMMMM")

internal actual fun historyDateRangeLabel(
    start: Long,
    end: Long,
    locale: Locale,
    zone: String,
): String = DateIntervalFormat.getInstance("yMMMd", locale).apply { timeZone = TimeZone.getTimeZone(zone) }.format(DateInterval(start, end))
