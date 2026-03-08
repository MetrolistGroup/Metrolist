package com.metrolist.shared.ui.statistics

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import platform.Foundation.NSCalendar
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970

@OptIn(ExperimentalForeignApi::class)
internal actual class StatisticsFormatter actual constructor(
    languageTag: String?,
) {
    private val selectedLocale = languageTag?.let { NSLocale(localeIdentifier = it) } ?: NSLocale.currentLocale
    private val numbers =
        NSNumberFormatter().apply {
            locale = selectedLocale
            numberStyle = NSNumberFormatterDecimalStyle
            maximumFractionDigits = 0u
        }

    private fun formatter(template: String) =
        NSDateFormatter().apply {
            locale = selectedLocale
            setLocalizedDateFormatFromTemplate(template)
        }

    private val dates = formatter("yMMMMEEEEd")
    private val weekdays = formatter("EEE")
    private val months = formatter("LLL")
    private val hours = formatter("jm")

    actual fun number(value: Long): String = requireNotNull(numbers.stringFromNumber(NSNumber(longLong = value)))

    actual fun date(value: LocalDate): String = dates.stringFromDate(value.nativeDate())

    actual fun weekday(value: LocalDate): String = weekdays.stringFromDate(value.nativeDate())

    actual fun month(value: LocalDate): String = months.stringFromDate(value.nativeDate())

    actual fun hour(value: Int): String = hours.stringFromDate(LocalDate(2000, 1, 1).nativeDate(value))

    private fun LocalDate.nativeDate(hour: Int = 12): NSDate =
        NSDate.dateWithTimeIntervalSince1970(
            atTime(hour, 0).toInstant(TimeZone.currentSystemDefault()).epochSeconds.toDouble(),
        )
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun statisticsFirstDayOfWeek(): DayOfWeek = DayOfWeek.entries[(NSCalendar.currentCalendar.firstWeekday.toInt() + 5) % 7]
