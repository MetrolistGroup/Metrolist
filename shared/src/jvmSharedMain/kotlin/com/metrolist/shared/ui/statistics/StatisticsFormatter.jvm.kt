package com.metrolist.shared.ui.statistics

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import java.text.NumberFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

internal actual class StatisticsFormatter actual constructor(
    languageTag: String?,
) {
    private val locale = languageTag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
    private val numbers = NumberFormat.getIntegerInstance(locale)
    private val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).withDecimalStyle(DecimalStyle.of(locale))
    private val hours = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withDecimalStyle(DecimalStyle.of(locale))

    actual fun number(value: Long): String = numbers.format(value)

    actual fun date(value: LocalDate): String = dates.format(value.toJavaLocalDate())

    actual fun weekday(value: LocalDate): String = value.toJavaLocalDate().dayOfWeek.getDisplayName(TextStyle.SHORT, locale)

    actual fun month(value: LocalDate): String = value.toJavaLocalDate().month.getDisplayName(TextStyle.SHORT_STANDALONE, locale)

    actual fun hour(value: Int): String = hours.format(LocalTime.of(value, 0))
}

internal actual fun statisticsFirstDayOfWeek(): DayOfWeek = DayOfWeek.entries[WeekFields.of(Locale.getDefault()).firstDayOfWeek.value - 1]
