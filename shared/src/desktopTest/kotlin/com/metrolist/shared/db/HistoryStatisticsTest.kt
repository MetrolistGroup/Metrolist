package com.metrolist.shared.db

import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class HistoryStatisticsTest {
    @Test
    fun fullRankingLoadsInBatchesWithoutExpandingTheInitialMetadataCache() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val path = Files.createTempFile("statistics-ranking", ".db")
            Files.delete(path)
            val db = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val start = 1_765_000_000_000L
                db.withDatabaseTransaction {
                    repeat(501) { index ->
                        val song = SongItem("song-$index", "Song $index", null, emptyList(), null)
                        db.dao().upsertHistorySong(song)
                        db.dao().insert(
                            Event(
                                songId = song.id,
                                timestamp = Instant.fromEpochMilliseconds(start + index).toLocalDateTime(TimeZone.UTC),
                                playTime =
                                    index + 1L,
                            ),
                        )
                    }
                }
                val snapshot = db.historyStatisticsSnapshot(0, Long.MAX_VALUE)
                assertEquals(500, snapshot.songItems.size)
                val ranking = snapshot.songs.reversed()
                assertEquals(ranking.map { it.songId }, db.statisticsSongs(ranking).map { it.id })
                val mobile = db.mobileHistoryStatisticsSnapshot(0, Long.MAX_VALUE)
                assertEquals(100, mobile.songItems.size)
                assertEquals(mobile.songs.map { it.songId }, db.statisticsSongs(mobile.songs).map { it.id })
                assertEquals(emptyList(), db.statisticsSongs(emptyList()))
            } finally {
                db.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun snapshotBucketsUseTheSameLocalCalendarAsTheCharts() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val path = Files.createTempFile("history-local-time", ".db")
            Files.delete(path)
            val db = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val timestamp = LocalDateTime(2026, 10, 27, 0, 30).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                db.recordHistoryPlay(SongItem("midnight", "After midnight", null, emptyList(), null), timestamp)
                val snapshot = db.historyStatisticsSnapshot(0, Long.MAX_VALUE)
                assertEquals(listOf(HistoryBucket("2026-10-27", 1)), snapshot.daily)
                assertEquals(listOf(HistoryBucket("00", 1)), snapshot.hourly)
            } finally {
                db.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun aggregatesUncappedHistoryWithPeriodAndSnapshotVisibility() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val path = Files.createTempFile("history-statistics", ".db")
            Files.delete(path)
            val db = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val start = 1_765_000_000_000L
                val song =
                    SongItem("song", "Title", "cover", listOf(ArtistTiny("artist", "Earth, Wind & Fire")), AlbumTiny("album", "Album"))
                db.withDatabaseTransaction {
                    db.dao().upsertHistorySong(song)
                    repeat(10_001) { index ->
                        db.dao().insert(
                            Event(
                                songId = song.id,
                                timestamp = Instant.fromEpochMilliseconds(start + index).toLocalDateTime(TimeZone.UTC),
                                playTime = 10L,
                            ),
                        )
                    }
                }
                val edited = requireNotNull(db.recordHistoryPlay(song, start + 20_000, playTimeMs = 23L))
                db.updateHistoryEntry(edited, "Past title", "Tyler, The Creator", "Past album")
                db.recordHistoryPlay(song, start - 1, playTimeMs = 999L)
                db.recordHistoryPlay(song, start + 30_000, source = "YOUTUBE")
                val until = start + 30_000
                assertEquals(HistoryTotals(10_003, 100_033L), db.historyStatisticsTotals(start, until, true))
                val songs = db.historySongStatistics(start, until, true)
                assertEquals(10_003, songs.single().playCount)
                assertEquals("Title", songs.single().title)
                val snapshot = db.historyStatisticsSnapshot(start, until, true)
                assertEquals(
                    listOf("Earth, Wind & Fire"),
                    snapshot.songItems
                        .single()
                        .artists
                        .map { it.name },
                )
                val editedStats = db.historyStatisticsSnapshot(start + 20_000, start + 20_001, true)
                assertEquals(
                    listOf("Tyler, The Creator"),
                    editedStats.songItems
                        .single()
                        .artists
                        .map { it.name },
                )
                val artists = db.historyArtistStatistics(start, until, true).associateBy { it.name }
                assertEquals(10_002, artists.getValue("Earth, Wind & Fire").playCount)
                assertEquals(23L, artists.getValue("Tyler, The Creator").playTimeMs)
                assertEquals(setOf("Album", "Past album"), db.historyAlbumStatistics(start, until, true).map { it.name }.toSet())
                assertEquals(
                    10_003,
                    db.historyTimeBuckets(start, until, hourly = false, localTime = false, catalogArtistIds = true).sumOf { it.playCount },
                )
                BlockedContent.blockArtist("edited-artist", "Tyler, The Creator")
                assertEquals(HistoryTotals(10_002, 100_010L), db.historyStatisticsTotals(start, until, true))
                Settings.shared.blockedSongIds = (0..1200).joinToString(",") { "blocked-$it" }
                Settings.shared.blockedArtistIds = (0..1200).joinToString(",") { "artist-$it" }
                assertEquals(HistoryTotals(10_002, 100_010L), db.historyStatisticsTotals(start, until, true))
                assertEquals(10_002, db.historySongStatistics(start, until, true).sumOf { it.playCount })
                assertEquals(10_002, db.historyTimeBuckets(start, until, false, false, true).sumOf { it.playCount })
                Settings.shared.dislikedSongIds = "song"
                assertEquals(HistoryTotals(0, 0L), db.historyStatisticsTotals(start, until, true))
            } finally {
                db.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(null)
            }
        }
}
