package com.metrolist.android.update

import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit
import android.icu.text.RelativeDateTimeFormatter.Direction
import android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit
import android.os.Build
import androidx.annotation.RequiresApi
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

internal fun formatReleaseAge(
    publishedAt: String?,
    now: Instant,
    formatter: RelativeDateTimeFormatter,
): String? {
    val published =
        try {
            Instant.parse(publishedAt ?: return null)
        } catch (_: DateTimeParseException) {
            return null
        }
    if (published > now) return null

    val elapsed = Duration.between(published, now)
    val months = ChronoUnit.MONTHS.between(published.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC))
    val (amount, unit) =
        when {
            months >= 12 -> months / 12 to RelativeUnit.YEARS
            months >= 1 -> months to RelativeUnit.MONTHS
            elapsed.toDays() >= 1 -> elapsed.toDays() to RelativeUnit.DAYS
            elapsed.toHours() >= 1 -> elapsed.toHours() to RelativeUnit.HOURS
            elapsed.toMinutes() >= 1 -> elapsed.toMinutes() to RelativeUnit.MINUTES
            else -> return formatNow(formatter)
        }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        formatter.formatNumeric(-amount.toDouble(), unit.toDateTimeUnit())
    } else {
        formatter.format(amount.toDouble(), Direction.LAST, unit)
    }
}

private fun formatNow(formatter: RelativeDateTimeFormatter): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        formatter.format(0.0, RelativeDateTimeUnit.SECOND)
    } else {
        formatter.format(Direction.PLAIN, AbsoluteUnit.NOW)
    }

@RequiresApi(Build.VERSION_CODES.P)
private fun RelativeUnit.toDateTimeUnit(): RelativeDateTimeUnit =
    when (this) {
        RelativeUnit.YEARS -> RelativeDateTimeUnit.YEAR
        RelativeUnit.MONTHS -> RelativeDateTimeUnit.MONTH
        RelativeUnit.DAYS -> RelativeDateTimeUnit.DAY
        RelativeUnit.HOURS -> RelativeDateTimeUnit.HOUR
        RelativeUnit.MINUTES -> RelativeDateTimeUnit.MINUTE
        else -> throw IllegalArgumentException("Unsupported release age unit $this")
    }
