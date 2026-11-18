package com.metrolist.shared.model

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class HistoryGroupingTest {
    @Test
    fun mixedLocalAndRemoteDaysUseTheSameLocalizedBucketsInBothOrders() {
        val zone = TimeZone.of("America/Fortaleza")
        val now = time("2026-11-18T02:30:00Z")
        val dates =
            listOf(
                "2026-11-17T03:00:00Z" to HistoryDateBucket.TODAY,
                "2026-11-16T03:00:00Z" to HistoryDateBucket.YESTERDAY,
                "2026-11-14T03:00:00Z" to HistoryDateBucket.THIS_WEEK,
                "2026-11-07T03:00:00Z" to HistoryDateBucket.LAST_WEEK,
                "2026-10-01T03:00:00Z" to HistoryDateBucket.MONTH,
            )
        for (remoteLabel in listOf("November 17, 2026", "17 de novembro de 2026", "17. November 2026")) {
            for ((date, bucket) in dates) {
                val remote = entry("remote", time(date), remoteLabel)
                val local = entry("local", time(date) + 60_000)
                for (entries in listOf(listOf(local, remote), listOf(remote, local))) {
                    val groups = groupHistory(entries, now, labels, { "outubro 2026" }, zone)
                    val heading = if (bucket == HistoryDateBucket.MONTH) "outubro 2026" else labels.getValue(bucket)
                    assertEquals(mapOf(heading to entries), groups)
                    assertEquals(remoteLabel, remote.remoteDateLabel)
                    assertEquals(remoteLabel, historyEntryTimeText(remote, HistorySortMode.NEWEST) { "unexpected" })
                }
            }
        }
    }

    @Test
    fun midnightBoundaryUsesTheDisplayTimeZoneWithoutReorderingEntries() {
        val remote = entry("remote", time("2026-11-17T23:59:00Z"), "17 Nov")
        val local = entry("local", time("2026-11-18T00:01:00Z"))
        val now = time("2026-11-18T00:30:00Z")
        for (entries in listOf(listOf(local, remote), listOf(remote, local))) {
            val utc = groupHistory(entries, now, labels, { "unused" }, TimeZone.UTC)
            assertEquals(listOf(local), utc[labels.getValue(HistoryDateBucket.TODAY)])
            assertEquals(listOf(remote), utc[labels.getValue(HistoryDateBucket.YESTERDAY)])
            val tokyo = groupHistory(entries, now, labels, { "unused" }, TimeZone.of("Asia/Tokyo"))
            assertEquals(mapOf(labels.getValue(HistoryDateBucket.TODAY) to entries), tokyo)
        }
    }

    private fun entry(
        id: String,
        playedAt: Long,
        label: String? = null,
    ) = HistoryEntryUi(
        id = id,
        songId = id,
        title = id,
        artists = "artist",
        album = null,
        thumbnailUrl = null,
        playedAt = playedAt,
        remoteFeedbackToken = null,
        remoteDateLabel = label,
        hasEstimatedDate = label != null,
    )

    private fun time(value: String) = Instant.parse(value).toEpochMilliseconds()

    private val labels =
        mapOf(
            HistoryDateBucket.TODAY to "Hoje",
            HistoryDateBucket.YESTERDAY to "Ontem",
            HistoryDateBucket.THIS_WEEK to "Esta semana",
            HistoryDateBucket.LAST_WEEK to "Semana passada",
        )
}
