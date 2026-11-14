package com.metrolist.shared.ui.mobile

import com.metrolist.shared.db.HistoryStatisticsSnapshot
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import com.metrolist.shared.db.historyChanges
import com.metrolist.shared.db.historyStatisticsSnapshot
import com.metrolist.shared.db.mobileHistoryStatisticsSnapshot
import com.metrolist.shared.db.recordHistoryPlay
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.screens.StatisticsPeriod
import com.metrolist.shared.ui.statistics.StatisticsDateRange
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class MobileStatisticsTest {
    @Test
    fun summaryTotalsCountRepeatedPlaysFromOneSnapshot() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val path = Files.createTempFile("statistics-summary", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val start = Instant.parse("2026-11-01T12:00:00Z").toEpochMilliseconds()
                val first = SongItem("first", "First", null, emptyList(), null)
                val second = SongItem("second", "Second", null, emptyList(), null)
                database.recordHistoryPlay(first, start - 1, playTimeMs = 99_000)
                database.recordHistoryPlay(first, start, playTimeMs = 1_000)
                database.recordHistoryPlay(first, start + 1, playTimeMs = 2_000)
                database.recordHistoryPlay(second, start + 2, playTimeMs = 3_000)
                val mobile = database.mobileHistoryStatisticsSnapshot(start, start + 2)
                val desktop = database.historyStatisticsSnapshot(start, start + 2)
                database.recordHistoryPlay(second, start + 2, playTimeMs = 4_000)
                for (snapshot in listOf(mobile, desktop)) {
                    assertEquals(2, snapshot.songs.size)
                    assertEquals(3, snapshot.totals.playCount)
                    assertEquals(6_000L, snapshot.totals.playTimeMs)
                    assertEquals(snapshot.totals.playCount, snapshot.songs.sumOf { it.playCount })
                    assertEquals(snapshot.totals.playTimeMs, snapshot.songs.sumOf { it.playTimeMs })
                    assertEquals(snapshot.totals.playCount, snapshot.daily.sumOf { it.playCount })
                }
                assertEquals(4, database.historyStatisticsSnapshot(start, start + 2).totals.playCount)
            } finally {
                database.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun selectedDatesIncludeBothEndpointsAcrossAllSnapshotQueries() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val path = Files.createTempFile("statistics-dates", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val dates = StatisticsDateRange(LocalDate(2024, 2, 1), LocalDate(2024, 2, 29))
                val (start, end) = dates.epochRange()
                val song = SongItem("song", "Song", null, emptyList(), null)
                for (timestamp in listOf(start - 1, start, end, end + 1)) {
                    database.recordHistoryPlay(song, timestamp, playTimeMs = 1000)
                }
                val mobile = database.loadMobileStatistics(StatisticsPeriod.WEEK, dateRange = dates)
                val jvm = database.historyStatisticsSnapshot(start, end)
                for (snapshot in listOf(mobile, jvm)) {
                    assertEquals(2, snapshot.totals.playCount)
                    assertEquals(2000L, snapshot.totals.playTimeMs)
                    assertEquals(2, snapshot.songs.single().playCount)
                    assertEquals(listOf("2024-02-01", "2024-02-29"), snapshot.daily.map { it.bucket })
                    assertEquals(2, snapshot.hourly.sumOf { it.playCount })
                }
            } finally {
                database.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun historyChangeIncludesPlaysAfterTheInitialQuery() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val path = Files.createTempFile("mobile-statistics", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            var now = Instant.parse("2026-10-30T12:00:00Z")
            val clock =
                object : Clock {
                    override fun now(): Instant = now
                }
            val snapshots = Channel<HistoryStatisticsSnapshot>(Channel.CONFLATED)
            try {
                val song = SongItem("song", "Song", null, emptyList(), null)
                database.recordHistoryPlay(song, now.toEpochMilliseconds() - 1000)
                val observer =
                    launch {
                        database.historyChanges().collectLatest {
                            snapshots.send(database.loadMobileStatistics(StatisticsPeriod.WEEK, clock))
                        }
                    }
                try {
                    withTimeout(10_000) {
                        assertEquals(1, snapshots.receive().totals.playCount)
                        now = Instant.parse("2026-10-30T12:05:00Z")
                        database.recordHistoryPlay(song, now.toEpochMilliseconds() - 1000)
                        var snapshot = snapshots.receive()
                        while (snapshot.totals.playCount != 2) snapshot = snapshots.receive()
                        assertEquals(2, snapshot.songs.single().playCount)
                    }
                } finally {
                    observer.cancel()
                    observer.join()
                }
            } finally {
                database.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(null)
            }
        }
}
