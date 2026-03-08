package com.metrolist.shared.db

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HistoryImportTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")

    @Test
    fun localizedMonthHeadingsKeepTheirMonthPrecision() {
        val september = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()
        for ((tag, heading) in listOf(
            "ja" to "2026年9月",
            "zh" to "2026年9月",
            "ko" to "2026년 9월",
            "de" to "September 2026",
            "pl" to "wrzesień 2026",
        )) {
            val date = assertNotNull(parseHistoryDate(heading, now, Locale.forLanguageTag(tag), utc))
            assertEquals(september, date.startMs)
            assertEquals(heading, date.label)
        }
    }

    @Test
    fun localizedYearDigitsPreserveMonthAndDateValidation() {
        val locale = Locale.forLanguageTag("ar")
        val expected = parseHistoryDate("سبتمبر 2026", now, locale, utc)
        assertNotNull(expected)
        assertEquals(expected, parseHistoryDate("سبتمبر ٢٠٢٦", now, locale, utc))
        assertNull(parseHistoryDate("نوفمبر ٢٠٢٦", now, locale, utc))
    }

    @Test
    fun requestLocaleWinsOverAppLocaleAndTodayDoesNotMoveWithSyncTime() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMAN)
            val first = parseHistoryDate("Dzisiaj", now, Locale.forLanguageTag("pl"), utc)
            assertEquals(first, parseHistoryDate("Dzisiaj", now + 3_600_000, Locale.forLanguageTag("pl"), utc))
            assertEquals(Instant.parse("2026-10-04T00:00:00Z").toEpochMilli(), first?.startMs)
            assertEquals("4 paź 2026", first?.label)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun calendarDaysRespectTimezoneAndDaylightSaving() {
        val zone = ZoneId.of("Europe/Warsaw")
        val afterTransition = Instant.parse("2026-03-29T22:30:00Z").toEpochMilli()
        val yesterday = assertNotNull(parseHistoryDate("Gestern", afterTransition, Locale.GERMAN, zone))
        assertEquals(Instant.parse("2026-03-28T23:00:00Z").toEpochMilli(), yesterday.startMs)
        assertEquals(yesterday, parseHistoryDate("vor 1 Tag", afterTransition, Locale.GERMAN, zone))
    }

    @Test
    fun relativeMonthsAndWeeksUseCalendarBoundaries() {
        assertEquals(
            Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(),
            parseHistoryDate("this month", now, Locale.UK, utc)?.startMs,
        )
        assertEquals("October 2026", parseHistoryDate("this month", now, Locale.UK, utc)?.label)
        assertEquals(
            Instant.parse("2026-09-21T00:00:00Z").toEpochMilli(),
            parseHistoryDate("last week", now, Locale.UK, utc)?.startMs,
        )
        assertEquals(
            parseHistoryDate("Today", now, Locale.ENGLISH, utc),
            parseHistoryDate("2 minutes ago", now, Locale.ENGLISH, utc),
        )
    }

    @Test
    fun unknownAndFutureHistoryDatesAreNotInvented() {
        listOf(
            "",
            " ",
            "History",
            "Earlier",
            "最近",
            "some day",
            "Today’s picks",
            "999999999999999999999 years ago",
            "9999999999 years ago",
            "November 2026",
        ).forEach {
            assertNull(parseHistoryDate(it, now, Locale.ENGLISH, utc))
        }
    }
}
