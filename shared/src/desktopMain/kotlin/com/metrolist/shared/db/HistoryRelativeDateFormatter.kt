package com.metrolist.shared.db

import com.ibm.icu.text.DateIntervalFormat
import com.ibm.icu.text.DateTimePatternGenerator
import com.ibm.icu.text.RelativeDateTimeFormatter
import com.ibm.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit
import com.ibm.icu.util.DateInterval
import com.ibm.icu.util.TimeZone
import java.util.Locale

internal actual fun historyRelativeDateFormatter(locale: Locale): (Double, HistoryDateUnit, Boolean) -> String {
    val formatter = RelativeDateTimeFormatter.getInstance(locale)
    return { amount, unit, numeric ->
        val relativeUnit = RelativeDateTimeUnit.valueOf(unit.name)
        if (numeric) formatter.formatNumeric(amount, relativeUnit) else formatter.format(amount, relativeUnit)
    }
}

internal actual fun historyYearMonthPattern(locale: Locale): String = DateTimePatternGenerator.getInstance(locale).getBestPattern("yMMMM")

internal actual fun historyDateRangeLabel(
    start: Long,
    end: Long,
    locale: Locale,
    zone: String,
): String = DateIntervalFormat.getInstance("yMMMd", locale).apply { timeZone = TimeZone.getTimeZone(zone) }.format(DateInterval(start, end))
