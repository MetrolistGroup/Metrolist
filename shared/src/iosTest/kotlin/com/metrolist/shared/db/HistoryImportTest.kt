package com.metrolist.shared.db

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class HistoryImportTest {
    @Test
    fun relativeWeeksUseRequestLocaleCalendarBoundaries() {
        val now = Instant.parse("2026-11-05T12:00:00Z").toEpochMilliseconds()
        val zone = TimeZone.currentSystemDefault()
        val thisWeek = LocalDate(2026, 11, 2)
        for ((language, headings) in listOf(
            "en-GB" to listOf("this week", "last week", "2 weeks ago"),
            "fr-FR" to listOf("cette semaine", "la semaine dernière", "il y a 2 semaines"),
        )) {
            headings.forEachIndexed { weeksAgo, heading ->
                val date = assertNotNull(parseYouTubeHistoryDate(heading, now, language), "$language: $heading")
                assertEquals(
                    thisWeek.minus(DatePeriod(days = weeksAgo * 7)).atStartOfDayIn(zone).toEpochMilliseconds(),
                    date.startMs,
                    "$language: $heading",
                )
            }
        }
    }

    @Test
    fun localizedHeadingsResolveToCalendarBucketsWithoutInventingPlayTimes() {
        val now = Instant.parse("2026-11-05T12:00:00Z").toEpochMilliseconds()
        val zone = TimeZone.currentSystemDefault()
        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
        assertEquals(
            today.atStartOfDayIn(zone).toEpochMilliseconds(),
            assertNotNull(parseYouTubeHistoryDate("Today", now, "en-GB")).startMs,
        )
        assertEquals(
            today.minus(DatePeriod(days = 1)).atStartOfDayIn(zone).toEpochMilliseconds(),
            assertNotNull(parseYouTubeHistoryDate("Hier", now, "fr-FR")).startMs,
        )
        val month = assertNotNull(parseYouTubeHistoryDate("October 2026", now, "en-GB"))
        assertEquals(
            "2026-10-01",
            Instant
                .fromEpochMilliseconds(month.startMs)
                .toLocalDateTime(zone)
                .date
                .toString(),
        )
        assertEquals("October 2026", month.label)
        assertNull(parseYouTubeHistoryDate("unknown date", now, "en-GB"))
        assertNull(parseYouTubeHistoryDate("December 2026", now, "en-GB"))
    }
}
