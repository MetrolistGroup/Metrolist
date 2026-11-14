package com.metrolist.shared.db

import com.metrolist.shared.api.YouTubeHistoryEntry
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistorySyncTest {
    @Test
    fun localizedMonthDigitsImportIntoHistory() =
        runBlocking {
            val path = Files.createTempFile("history-localized-year-", ".db")
            Files.delete(path)
            val db = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val now = Instant.parse("2026-10-04T12:00:00Z").toEpochMilli()
                val entry = YouTubeHistoryEntry(SongItem("song", "Title", null, emptyList(), null), "سبتمبر ٢٠٢٦", "month")
                val date = assertNotNull(parseHistoryDate(entry.playedText, now, Locale.forLanguageTag("ar"), ZoneId.of("UTC")))
                assertTrue(db.applyYouTubeHistoryEntry(entry, date))
                assertEquals(Instant.parse("2026-09-01T00:00:00Z").toEpochMilli(), db.history().single().playedAt)
            } finally {
                db.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun repeatedTodayImportsRepairBucketsWithoutInventingFreshPlaysOrHourlyStatistics() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val path = Files.createTempFile("history-coarse-date-", ".db")
            Files.delete(path)
            val db = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val midnight = Instant.parse("2026-10-25T00:00:00Z").toEpochMilli()
                val morning = Instant.parse("2026-10-25T08:00:00Z").toEpochMilli()
                val evening = Instant.parse("2026-10-25T20:00:00Z").toEpochMilli()
                val song = SongItem("song", "Title", null, emptyList(), null)
                val localId = db.recordHistoryPlay(song, morning, token = "local", playTimeMs = 15_000)
                val repairId = db.recordHistoryPlay(song, morning, "YOUTUBE", "repair", playTimeMs = 9_000)
                db.recordHistoryPlay(song.copy(id = "legacy"), morning, "YOUTUBE", "legacy")
                val entries = listOf("new", "repair", "local").map { YouTubeHistoryEntry(song, "Today", it) }
                for (now in listOf(morning, evening)) {
                    val parsed = entries.map { it to parseHistoryDate(it.playedText, now, Locale.UK, ZoneId.of("UTC")) }
                    val index = db.historySyncIndex(parsed)
                    parsed.forEachIndexed { order, (entry, date) -> db.applyYouTubeHistoryEntry(entry, date, index, -order) }
                    val rows = db.history()
                    assertEquals(4, rows.size)
                    assertEquals(midnight, rows.single { it.remoteFeedbackToken == "new" }.playedAt)
                    assertEquals(midnight, rows.single { it.id == repairId }.playedAt)
                    assertNotNull(rows.single { it.id == repairId }.remoteDateLabel)
                    assertEquals(morning, rows.single { it.id == localId }.playedAt)
                    assertEquals("LOCAL", rows.single { it.id == localId }.source)
                    assertNull(rows.single { it.id == localId }.remoteDateLabel)
                    val stats = db.historyStatisticsSnapshot(midnight, evening, localTime = false)
                    assertEquals(HistoryTotals(4, 24_000), stats.totals)
                    assertEquals(listOf(HistoryBucket("2026-10-25", 4)), stats.daily)
                    assertEquals(listOf(HistoryBucket("08", 1)), stats.hourly)
                    assertEquals(stats.hourly, db.historyTimeBuckets(midnight, evening, hourly = true, localTime = false))
                    assertEquals(HistoryTotals(0, 0), db.historyStatisticsTotals(evening - 3_600_000, evening))
                }
                val before = db.history()
                val yesterday = parseHistoryDate("Yesterday", evening + 86_400_000, Locale.UK, ZoneId.of("UTC"))
                assertFalse(db.applyYouTubeHistoryEntry(entries.first().copy(playedText = "Yesterday"), yesterday))
                assertEquals(before, db.history())
            } finally {
                db.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun resyncRepairsMonthBucketsAndKeepsTodayStableWithoutChangingLocalPlays() =
        runBlocking {
            val path = Files.createTempFile("history-date-precision-", ".db")
            Files.delete(path)
            var db = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val song = SongItem("song", "Title", null, emptyList(), null)
                val now = Instant.parse("2026-10-04T12:00:00Z").toEpochMilli()
                val september = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()
                val legacyId = db.recordHistoryPlay(song, september - 1000, "YOUTUBE", "month-second")
                val localId = db.recordHistoryPlay(song, now - 1000, token = "local", playTimeMs = 30_000)
                val entries =
                    listOf(
                        YouTubeHistoryEntry(song, "Today", "today"),
                        YouTubeHistoryEntry(song, "September 2026", "month-first"),
                        YouTubeHistoryEntry(song, "September 2026", "month-second"),
                        YouTubeHistoryEntry(song, "Today", "local"),
                    )

                suspend fun sync(at: Long): Boolean {
                    val parsed = entries.map { it to parseHistoryDate(it.playedText, at, Locale.ENGLISH, ZoneId.of("UTC")) }
                    val index = db.historySyncIndex(parsed)
                    return parsed.mapIndexed { order, (entry, date) -> db.applyYouTubeHistoryEntry(entry, date, index, -order) }.any { it }
                }
                assertTrue(sync(now))
                val first = db.history()
                assertFalse(sync(now + 3_600_000))
                assertEquals(first, db.history())
                assertEquals(september, first.single { it.id == legacyId }.playedAt)
                assertEquals("September 2026", first.single { it.id == legacyId }.remoteDateLabel)
                assertEquals(now - 1000, first.single { it.id == localId }.playedAt)
                assertNull(first.single { it.id == localId }.remoteDateLabel)
                assertEquals(30_000L, first.single { it.id == localId }.playTimeMs)
                db.close()
                db = getDesktopDatabaseBuilder(path.toFile()).build()
                assertEquals(first, db.history())
                val paged = mutableListOf<StoredHistoryRow>()
                var cursor: HistoryCursor? = null
                while (true) {
                    val row = db.recentVisibleHistory(1, before = cursor).singleOrNull() ?: break
                    paged.add(row)
                    cursor = HistoryCursor(row.playedAt, row.id, row.remoteOrder)
                }
                assertEquals(listOf(localId), paged.map { it.id })
                assertEquals(listOf(first.single { it.id == localId }), paged)
                val today = assertNotNull(first.single { it.remoteFeedbackToken == "today" }.remoteDateLabel)
                assertEquals("Oct 4, 2026", today)
                val previousZone = TimeZone.getDefault()
                try {
                    TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"))
                    val tokenlessSong = song.copy(id = "tokenless")
                    val tokenlessId = db.recordHistoryPlay(tokenlessSong, now - 60_000, "YOUTUBE")
                    val date = assertNotNull(parseHistoryDate("Today", now, Locale.ENGLISH))
                    assertTrue(db.applyYouTubeHistoryEntry(YouTubeHistoryEntry(tokenlessSong, "Today", null), date))
                    assertEquals(date.label, db.history().single { it.id == tokenlessId }.remoteDateLabel)
                    assertEquals(5, db.history().size)
                } finally {
                    TimeZone.setDefault(previousZone)
                }
            } finally {
                db.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun unknownDatesNeitherInsertPlaysNorOverwriteKnownTimestamps() =
        runBlocking {
            val path = Files.createTempFile("history-unknown-date-", ".db")
            Files.delete(path)
            val db = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val song = SongItem("song", "Title", null, emptyList(), null)
                val timestamp = 1_765_000_000_000L
                db.recordHistoryPlay(song, timestamp, source = "LOCAL", token = "known", playTimeMs = 15_000L)
                val known =
                    db.historySyncIndex(
                        listOf(YouTubeHistoryEntry(song, "some day", "known") to RemoteHistoryDate(timestamp, "date")),
                    )
                val original = db.history()
                for (text in listOf("", "Earlier", "最近")) {
                    val estimate = parseHistoryDate(text, timestamp + 86_400_000L)
                    db.applyYouTubeHistoryEntry(YouTubeHistoryEntry(song, text, "known"), estimate, known)
                    db.applyYouTubeHistoryEntry(YouTubeHistoryEntry(song.copy(id = "new"), text, "new"), estimate, known)
                }
                assertEquals(original, db.history())
            } finally {
                db.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun snapshotEditsDetermineArtistAndAlbumStatsDespiteUnchangedCatalog() =
        runBlocking {
            val path = Files.createTempFile("history-edited-stats-", ".db")
            Files.delete(path)
            val db = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val lead = ArtistTiny("lead", "Earth, Wind & Fire")
                val guest = ArtistTiny("guest", "Guest")
                val album = AlbumTiny("album-id", "Original album")
                val one = SongItem("one", "First", "cover", listOf(lead), album)
                val two = SongItem("two", "Second", "cover", listOf(lead, guest), album)
                val firstId = requireNotNull(db.recordHistoryPlay(one, 1_765_000_000_000L, playTimeMs = 10_000))
                db.recordHistoryPlay(two, 1_765_000_000_001L, playTimeMs = 20_000)
                assertEquals(setOf("Earth, Wind & Fire", "Guest"), db.historyArtistStatistics(0, Long.MAX_VALUE).map { it.name }.toSet())
                assertEquals(2, db.historyAlbumStatistics(0, Long.MAX_VALUE).single().playCount)
                db.updateHistoryEntry(firstId, "Edited", "Tyler, The Creator", "Edited album")
                val rows = db.history()
                assertEquals(
                    setOf("Tyler, The Creator", "Earth, Wind & Fire", "Guest"),
                    db
                        .historyArtistStatistics(0, Long.MAX_VALUE)
                        .map {
                            it.name
                        }.toSet(),
                )
                assertEquals(
                    10_000L,
                    db.historyArtistStatistics(0, Long.MAX_VALUE).single { it.name == "Tyler, The Creator" }.playTimeMs,
                )
                assertEquals(setOf("Edited album", "Original album"), db.historyAlbumStatistics(0, Long.MAX_VALUE).map { it.name }.toSet())
                assertEquals(null, rows.single { it.id == firstId }.albumId)
                assertEquals("album-id", rows.single { it.songId == "two" }.albumId)
            } finally {
                db.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun albumIdentityWithoutIdIncludesHistoricalArtistCredits() =
        runBlocking {
            val path = Files.createTempFile("history-album-identity-", ".db")
            Files.delete(path)
            val db = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val title = "Greatest Hits"
                db.recordHistoryPlay(
                    SongItem("first", "Track", "first-cover", listOf(ArtistTiny("a", "Artist A")), AlbumTiny(null, title)),
                    1L,
                )
                db.recordHistoryPlay(
                    SongItem("second", "Track", "second-cover", listOf(ArtistTiny("b", "Artist B")), AlbumTiny(null, title)),
                    2L,
                )
                db.recordHistoryPlay(
                    SongItem("third", "Track", "third-cover", listOf(ArtistTiny("a", "Artist A")), AlbumTiny(null, title)),
                    3L,
                )
                db.recordHistoryPlay(
                    SongItem(
                        "featured",
                        "Track",
                        null,
                        listOf(ArtistTiny("a", "Artist A"), ArtistTiny("guest", "Guest")),
                        AlbumTiny(null, title),
                    ),
                    4L,
                )
                db.importHistorySources(
                    listOf(
                        "legacy" to
                            listOf(
                                StoredHistoryPlay(
                                    1,
                                    "legacy-featured",
                                    "Track",
                                    "Artist A, Guest",
                                    title,
                                    null,
                                    null,
                                    5L,
                                    "LOCAL",
                                    null,
                                    false,
                                ),
                            ),
                    ),
                )
                db.recordHistoryPlay(
                    SongItem("comma-name", "Track", null, listOf(ArtistTiny("earth", "Earth, Wind & Fire")), AlbumTiny(null, title)),
                    6L,
                )
                db.recordHistoryPlay(
                    SongItem("id-one", "Track", null, listOf(ArtistTiny("a", "Artist A")), AlbumTiny("same-id", title)),
                    7L,
                )
                db.recordHistoryPlay(
                    SongItem("id-two", "Track", null, listOf(ArtistTiny("b", "Artist B")), AlbumTiny("same-id", title)),
                    8L,
                )
                val groups = db.historyAlbumStatistics(0, Long.MAX_VALUE)
                assertEquals(listOf(1, 1, 2, 4), groups.map { it.playCount }.sorted())
                assertEquals("Artist A, Guest", groups.single { it.playCount == 4 }.artists)
                assertEquals("Artist B", groups.single { it.playCount == 2 }.artists)
            } finally {
                db.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun artistArtworkPrefersCatalogImageAndFallsBackToPlayArt() =
        runBlocking {
            val path = Files.createTempFile("history-artist-art-", ".db")
            Files.delete(path)
            val db = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                db.recordHistoryPlay(SongItem("song", "Track", "cover", listOf(ArtistTiny("artist-id", "Artist")), null), 1L)
                db.recordHistoryPlay(SongItem("other", "Track", "wrong-cover", listOf(ArtistTiny("other-id", "Artist")), null), 2L)
                db.dao().upsert(ArtistEntity("other-id", "Artist", thumbnailUrl = "unrelated-portrait"))
                assertEquals("cover", db.historyArtistStatistics(1L, 1L, true).single().thumbnailUrl)
                db.dao().upsert(ArtistEntity("artist-id", "Artist", thumbnailUrl = "portrait"))
                assertEquals("portrait", db.historyArtistStatistics(1L, 1L, true).single().thumbnailUrl)
                db.updateHistoryEntry(db.history().single { it.songId == "song" }.id, "Track", "Unknown", null)
                assertEquals("cover", db.historyArtistStatistics(1L, 1L, true).single().thumbnailUrl)
            } finally {
                db.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun localPlayPersistsMeasuredListeningTimeIndependentlyOfTrackDuration() =
        runBlocking {
            val path = Files.createTempFile("history-local-", ".db")
            Files.delete(path)
            val db = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val song = SongItem("song", "Title", null, emptyList(), null, duration = 240)
                db.recordHistoryPlay(song, 1_765_000_000_000L, playTimeMs = 30_000L)
                assertEquals(30_000L, db.history().single().playTimeMs)
                assertEquals(240L, db.history().single().duration)
                assertEquals(30_000L, db.historyStatisticsTotals(0, Long.MAX_VALUE).playTimeMs)
            } finally {
                db.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun batchCacheTracksRefinedDatesAndRealIds() =
        runBlocking {
            val path = Files.createTempFile("history-sync-batch-", ".db")
            Files.delete(path)
            val db = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val song = SongItem("song", "Title", null, emptyList(), null, duration = 240)
                val start = 1_765_000_000_000L
                val first = YouTubeHistoryEntry(song, "some day", "first")
                db.recordHistoryPlay(song, start, source = "YOUTUBE", token = "first", playTimeMs = 15_000L)
                val known = db.historySyncIndex(listOf(first to RemoteHistoryDate(start, "date")))
                val firstId = db.history().single().id
                db.applyYouTubeHistoryEntry(first, RemoteHistoryDate(start + 86_400_000L, "date"), known)
                assertEquals(firstId, db.history().single().id)
                assertEquals(start + 86_400_000L, db.history().single().playedAt)
                assertEquals("YOUTUBE", db.history().single().source)
                assertEquals(15_000L, db.history().single().playTimeMs)
                db.applyYouTubeHistoryEntry(first.copy(feedbackToken = null), RemoteHistoryDate(start + 86_410_000L, "date"), known)
                assertEquals(1, db.history().size)

                db.applyYouTubeHistoryEntry(first.copy(feedbackToken = "second"), RemoteHistoryDate(start + 2 * 86_400_000L, "date"), known)
                val second = db.history().first { it.remoteFeedbackToken == "second" }
                assertEquals(db.history().first { it.remoteFeedbackToken == "second" }.id, second.id)
                db.applyYouTubeHistoryEntry(first.copy(feedbackToken = "second"), RemoteHistoryDate(start + 3 * 86_400_000L, "date"), known)
                db.applyYouTubeHistoryEntry(first.copy(feedbackToken = null), RemoteHistoryDate(start + 3 * 86_400_000L, "date"), known)
                assertEquals(2, db.history().size)
                assertEquals(start + 3 * 86_400_000L, db.history().first { it.id == second.id }.playedAt)
            } finally {
                db.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun tokenRefreshAndDayDedupNeverInferListeningTimeFromDuration() =
        runBlocking {
            val path = Files.createTempFile("history-sync-", ".db")
            Files.delete(path)
            val db = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val song = SongItem("song", "Title", null, emptyList(), null, duration = 240)
                val first = YouTubeHistoryEntry(song, "one day ago", "token")
                val start = 1_765_000_000_000L
                db.applyYouTubeHistoryEntry(first, RemoteHistoryDate(start, "date"))
                assertEquals(1, db.history().size)
                assertNull(db.history().single().playTimeMs)
                db.applyYouTubeHistoryEntry(first, RemoteHistoryDate(start + 90_000L, "date"))
                assertEquals(start + 90_000L, db.history().single().playedAt)
                db.applyYouTubeHistoryEntry(first.copy(feedbackToken = "another"), RemoteHistoryDate(start + 120_000L, "date"))
                assertEquals(2, db.history().size)
                db.applyYouTubeHistoryEntry(first.copy(feedbackToken = null), RemoteHistoryDate(start + 180_000L, "date"))
                assertEquals(2, db.history().size)
                db.applyYouTubeHistoryEntry(first.copy(feedbackToken = "next-day"), RemoteHistoryDate(start + 86_400_000L, "date"))
                assertEquals(3, db.history().size)
                assertEquals(0L, db.history().sumOf { it.playTimeMs ?: 0L })
            } finally {
                db.close()
                Files.deleteIfExists(path)
            }
        }
}
