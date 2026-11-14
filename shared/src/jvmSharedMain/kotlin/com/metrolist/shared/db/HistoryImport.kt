package com.metrolist.shared.db

import com.metrolist.shared.state.GlobalInnerTube
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

private const val DAY_MS = 86_400_000L

internal enum class HistoryDateUnit(
    val durationMs: Long,
) {
    SECOND(1_000L),
    MINUTE(60_000L),
    HOUR(3_600_000L),
    DAY(DAY_MS),
    WEEK(7 * DAY_MS),
    MONTH(30 * DAY_MS),
    YEAR(365 * DAY_MS),
}

internal expect fun historyYearMonthPattern(locale: Locale): String

internal expect fun historyRelativeDateFormatter(locale: Locale): (Double, HistoryDateUnit, Boolean) -> String

internal expect fun historyDateRangeLabel(
    start: Long,
    end: Long,
    locale: Locale,
    zone: String,
): String

fun parseHistoryDate(
    playedText: String,
    now: Long,
    locale: Locale = Locale.forLanguageTag(GlobalInnerTube.instance.locale.hl),
    zone: ZoneId = ZoneId.systemDefault(),
): RemoteHistoryDate? {
    fun normalize(text: String) =
        text
            .trim()
            .lowercase(locale)
            .replace('\u00a0', ' ')
            .replace('\u202f', ' ')
    val text = normalize(playedText)
    val monthText = text.map { char -> char.digitToIntOrNull()?.let { '0' + it } ?: char }.joinToString("")
    if (text.isEmpty()) return null
    val format = historyRelativeDateFormatter(locale)
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
        headings.firstOrNull { (amount, unit) -> normalize(format(amount.toDouble(), unit, false)) == text }
            ?: run {
                val count =
                    Regex("""\p{N}+""")
                        .find(text)
                        ?.value
                        ?.map { it.digitToIntOrNull() ?: return null }
                        ?.joinToString("")
                        ?.toLongOrNull()
                HistoryDateUnit.entries
                    .firstOrNull { unit ->
                        count != null && count > 0 && count <= now / unit.durationMs &&
                            normalize(format(-count.toDouble(), unit, true)) == text
                    }?.let { -checkNotNull(count) to it }
            }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val (start, end, unit) =
        if (relative != null) {
            val (amount, unit) = relative
            val start =
                when (unit) {
                    HistoryDateUnit.SECOND, HistoryDateUnit.MINUTE, HistoryDateUnit.HOUR -> {
                        Instant.ofEpochMilli(now + amount * unit.durationMs).atZone(zone).toLocalDate()
                    }

                    HistoryDateUnit.DAY -> {
                        today.plusDays(amount)
                    }

                    HistoryDateUnit.WEEK -> {
                        today.with(TemporalAdjusters.previousOrSame(WeekFields.of(locale).firstDayOfWeek)).plusWeeks(amount)
                    }

                    HistoryDateUnit.MONTH -> {
                        today.withDayOfMonth(1).plusMonths(amount)
                    }

                    HistoryDateUnit.YEAR -> {
                        today.withDayOfYear(1).plusYears(amount)
                    }
                }
            val end =
                when (unit) {
                    HistoryDateUnit.WEEK -> start.plusWeeks(1).minusDays(1)
                    HistoryDateUnit.MONTH -> start.plusMonths(1).minusDays(1)
                    HistoryDateUnit.YEAR -> start.plusYears(1).minusDays(1)
                    else -> start
                }
            Triple(start, end, unit)
        } else {
            val month =
                sequenceOf(historyYearMonthPattern(locale), "MMMM uuuu", "LLLL uuuu")
                    .mapNotNull { pattern ->
                        runCatching {
                            YearMonth.parse(
                                monthText,
                                DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(locale),
                            )
                        }.getOrNull()
                    }.firstOrNull() ?: return null
            Triple(month.atDay(1), month.atEndOfMonth(), HistoryDateUnit.MONTH)
        }
    val startMs = start.atStartOfDay(zone).toInstant().toEpochMilli()
    if (startMs !in 1..now) return null
    val label =
        when (unit) {
            HistoryDateUnit.MONTH -> DateTimeFormatter.ofPattern(historyYearMonthPattern(locale), locale).format(start)
            else -> historyDateRangeLabel(startMs, end.atStartOfDay(zone).toInstant().toEpochMilli(), locale, zone.id)
        }
    return RemoteHistoryDate(startMs, label)
}

internal actual fun parseYouTubeHistoryDate(
    text: String,
    now: Long,
    language: String,
): RemoteHistoryDate? = parseHistoryDate(text, now, Locale.forLanguageTag(language))
