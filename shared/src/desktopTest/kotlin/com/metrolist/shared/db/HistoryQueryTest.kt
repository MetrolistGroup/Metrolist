package com.metrolist.shared.db

import com.metrolist.shared.api.YouTubeHistoryEntry
import com.metrolist.shared.db.daos.WidgetSong
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.entities.HistorySnapshot
import com.metrolist.shared.db.entities.SongAlbumMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.HistorySortMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.filterAndSortHistory
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class HistoryQueryTest {
    @Test
    fun bulkSelectionFindsRemoteTokensOutsideTheLoadedPage() =
        databaseTest { db ->
            val local = requireNotNull(db.recordHistoryPlay(SongItem("local", "Local", null, emptyList(), null), 2))
            val remote = requireNotNull(db.recordHistoryPlay(SongItem("remote", "Remote", null, emptyList(), null), 1, token = "feedback"))
            assertFalse(db.historyHasRemoteTokens(setOf(local)))
            assertTrue(db.historyHasRemoteTokens(db.visibleHistoryIds("")))
            assertTrue(db.historyHasRemoteTokens(setOf(remote)))
        }

    @Test
    fun pagedPresentationPreservesUnicodeSearchAndStableTieOrdering() =
        databaseTest { db ->
            val names = listOf("Ä", "ä", "z", "İ", "ı", "I", "i", "same", "same", "same")
            names.forEachIndexed { index, name ->
                db.recordHistoryPlay(
                    SongItem("song-$index", name, null, listOf(ArtistTiny(null, name)), null),
                    (index / 2).toLong(),
                    playTimeMs = (index % 3).toLong(),
                )
            }
            val rows = db.recentVisibleHistory(100)
            for (mode in HistorySortMode.entries) {
                for (direction in SortDirection.entries) {
                    for (query in listOf("", "i", "ä", "same")) {
                        val expected =
                            rows.filterAndSortHistory(
                                query,
                                mode,
                                StoredHistoryRow::title,
                                StoredHistoryRow::artists,
                                StoredHistoryRow::album,
                                StoredHistoryRow::playedAt,
                                StoredHistoryRow::playTimeMs,
                                direction = direction,
                                extractRemoteOrder = StoredHistoryRow::remoteOrder,
                            )
                        val actual =
                            (0..names.size step 3).flatMap {
                                db.visibleHistoryPage(3, it, query, mode, direction, preserveUnicodeOrder = true)
                            }
                        assertEquals(expected.map { it.id }, actual.map { it.id }, "$mode $direction $query")
                        assertEquals(expected.mapTo(mutableSetOf()) { it.id }, db.visibleHistoryIds(query, preserveUnicodeOrder = true))
                    }
                }
            }
        }

    @Test
    fun filteredSortedHistoryPagesUseSnapshotsAndSelectionOnlyReturnsMatchingIds() =
        databaseTest { db ->
            val ids = mutableSetOf<Long>()
            repeat(205) { index ->
                val song =
                    SongItem("page-$index", "Song ${index.toString().padStart(3, '0')}", null, listOf(ArtistTiny(null, "Ärtist")), null)
                ids += requireNotNull(db.recordHistoryPlay(song, index.toLong(), playTimeMs = index.toLong()))
            }
            db.recordHistoryPlay(SongItem("other", "Unrelated", null, emptyList(), null), 1000L)
            val pages =
                listOf(0, 100, 200).flatMap { offset ->
                    db.visibleHistoryPage(100, offset, "ärtist", HistorySortMode.TITLE, SortDirection.ASCENDING)
                }
            assertEquals(205, pages.size)
            assertEquals(ids, pages.map { it.id }.toSet())
            assertEquals((0..204).map { "Song ${it.toString().padStart(3, '0')}" }, pages.map { it.title })
            assertEquals(ids, db.visibleHistoryIds("ÄRTIST"))
            assertEquals(
                listOf(
                    204L,
                    203L,
                ),
                db.visibleHistoryPage(2, 0, "ärtist", HistorySortMode.PLAY_TIME, SortDirection.DESCENDING).map {
                    it.playTimeMs
                },
            )
            val edited = pages.first().id
            db.updateHistoryEntry(edited, "Literal [*?]", "Updated", "Album")
            assertEquals(setOf(edited), db.visibleHistoryIds("[*?]"))
            assertEquals(204, db.visibleHistoryIds("ärtist").size)
            assertEquals(
                listOf(edited),
                db.visibleHistoryPage(100, 0, "updated", HistorySortMode.NEWEST, SortDirection.DESCENDING).map { it.id },
            )
        }

    @Test
    fun selectionAndDeletionUseOnlyDisplayedLatestPlays() =
        databaseTest { db ->
            val olderIds = mutableSetOf<Long>()
            val latestIds = mutableSetOf<Long>()
            db.withDatabaseTransaction {
                repeat(205) { index ->
                    val song = SongItem("selected-$index", "Selected $index", null, emptyList(), null)
                    olderIds += requireNotNull(db.recordHistoryPlay(song, 1000L + index, token = "older-$index"))
                    val latest = requireNotNull(db.recordHistoryPlay(song, 2000L + index, token = "latest-$index"))
                    db.updateHistoryEntry(latest, "Displayed $index", "Artist", null)
                    latestIds += latest
                }
            }
            val blocked = SongItem("blocked", "Displayed blocked", null, emptyList(), null)
            db.recordHistoryPlay(blocked, 5000)
            Settings.shared.dislikedSongIds = blocked.id
            for (catalogArtistIds in listOf(false, true)) {
                for (mode in HistorySortMode.entries) {
                    for (direction in SortDirection.entries) {
                        val pages =
                            listOf(0, 100, 200).flatMap { offset ->
                                db.visibleHistoryPage(100, offset, "Displayed", mode, direction, catalogArtistIds)
                            }
                        assertEquals(205, pages.size)
                        assertEquals(latestIds, pages.mapTo(mutableSetOf()) { it.id })
                        assertEquals(latestIds, db.visibleHistoryIds("Displayed", catalogArtistIds))
                    }
                }
                assertEquals(emptySet(), db.visibleHistoryIds("Selected", catalogArtistIds))
            }

            val single = latestIds.first()
            var tokens = emptyList<String>()
            db.deleteHistoryEntries(setOf(single)) { tokens = it }
            assertEquals(listOf("latest-0"), tokens)
            assertEquals((latestIds - single) + olderIds.first(), db.visibleHistoryIds(""))
            val selection = db.visibleHistoryIds("Displayed")
            db.deleteHistoryEntries(selection) { tokens = it }
            assertEquals((1..204).map { "latest-$it" }.toSet(), tokens.toSet())
            assertEquals(olderIds, db.visibleHistoryIds(""))
            assertEquals(olderIds, db.recentVisibleHistory(Int.MAX_VALUE).mapTo(mutableSetOf()) { it.id })
            assertEquals(206, db.history().size)
        }

    @Test
    fun deletesLargeSelectionsAndInvalidatesHistoryObservers() =
        databaseTest { db ->
            val song = SongItem("delete-song", "Delete", null, emptyList(), null)
            val ids = mutableSetOf<Long>()
            db.withDatabaseTransaction {
                repeat(1_201) { index ->
                    ids += requireNotNull(db.recordHistoryPlay(song, index.toLong(), token = "token-$index"))
                }
            }
            val remaining = requireNotNull(db.recordHistoryPlay(song, 5_000L))
            coroutineScope {
                val observed =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000L) { db.dao().eventCount().first { it == 1 } }
                    }
                var tokens = emptyList<String>()
                db.deleteHistoryEntries(ids) { tokens = it }
                assertEquals(1_201, tokens.size)
                assertEquals(1, observed.await())
            }
            assertEquals(listOf(remaining), db.recentVisibleHistory(10).map { it.id })
        }

    private fun databaseTest(block: suspend (MusicDatabase) -> Unit) =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val directory = Files.createTempDirectory("history-query")
            val database = getDesktopDatabaseBuilder(directory.resolve("music.db").toFile()).build()
            try {
                block(database)
            } finally {
                database.close()
                directory.toFile().deleteRecursively()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun albumTitlesAndArtistNamesDoNotReplaceCatalogIdentity() =
        databaseTest { db ->
            val first = SongItem("one", "One", null, listOf(ArtistTiny("UCfirst", "Same name")), AlbumTiny(null, "Greatest Hits"))
            val second = first.copy(id = "two", artists = listOf(ArtistTiny("UCsecond", "Same name")))
            db.recordHistoryPlay(first, 1000, playTimeMs = 10)
            db.recordHistoryPlay(second, 2000, playTimeMs = 20)
            val snapshot = db.mobileHistoryStatisticsSnapshot(0, Long.MAX_VALUE)
            assertEquals(listOf(1, 1), snapshot.albums.map { it.playCount })
            assertTrue(snapshot.albums.all { it.id == null })
            for (useIds in listOf(false, true)) {
                assertEquals(
                    setOf("UCfirst", "UCsecond"),
                    db
                        .historyStatisticsSnapshot(0, Long.MAX_VALUE, useIds)
                        .artists
                        .map { it.id }
                        .toSet(),
                )
            }
            db.updateHistoryEntry(db.history().first { it.songId == "one" }.id, "One", "Historical name", "Greatest Hits")
            assertNull(
                db
                    .mobileHistoryStatisticsSnapshot(0, Long.MAX_VALUE)
                    .artists
                    .first { it.name == "Historical name" }
                    .id,
            )
        }

    @Test
    fun nameOnlyCreditsShareAHistoryIdentityWithoutABrowseId() =
        databaseTest { db ->
            val song = SongItem("one", "One", null, listOf(ArtistTiny(null, "Historical artist")), null)
            db.recordHistoryPlay(song, 1000, playTimeMs = 10)
            db.recordHistoryPlay(song.copy(id = "two"), 2000, playTimeMs = 10)
            val artist = db.mobileHistoryStatisticsSnapshot(0, Long.MAX_VALUE).artists.single()
            assertEquals(2, artist.playCount)
            assertNull(artist.id)
        }

    @Test
    fun legacyCatalogBlocksMatchIndividualCreditsAndOnlyReadTheRequestedRange() =
        databaseTest { db ->
            val song = SongItem("legacy", "Legacy", null, listOf(ArtistTiny("a", "A"), ArtistTiny("b", "B")), null)
            db.dao().upsertHistorySong(song)
            db.dao().insert(
                Event(songId = song.id, timestamp = Instant.fromEpochMilliseconds(1000).toLocalDateTime(TimeZone.UTC), playTime = 10),
            )
            db.recordHistoryPlay(song.copy(id = "old"), 1, playTimeMs = 10)
            db.recordHistoryPlay(song.copy(id = "new"), 2000, playTimeMs = 10)
            assertEquals(
                setOf("A", "B"),
                db
                    .dao()
                    .historyCatalogNames(1000, 1000)
                    .map { it.artists }
                    .toSet(),
            )
            assertTrue(db.dao().historyCatalogNames(1001, 2000).isEmpty())
            assertEquals(listOf("new"), db.dao().historySnapshotCredits(1000, 2000).map { it.songId })
            assertEquals(
                setOf("legacy", "new"),
                db
                    .dao()
                    .historyBlockedArtistCredits(listOf("b"), 1000, 2000)
                    .map { it.songId }
                    .toSet(),
            )
            BlockedContent.blockArtist("different-id", "B")
            assertTrue(db.recentVisibleHistory(10, sinceMs = 1000, untilMs = 1000).isEmpty())
            assertEquals(HistoryTotals(0, 0), db.historyStatisticsTotals(1000, 1000))
        }

    @Test
    fun storedAlbumUnionsMappedAndDirectMembershipWithStableOrdering() =
        databaseTest { db ->
            val target = AlbumTiny("album", "Album")
            val first = SongItem("first", "First", null, emptyList(), target)
            val second = first.copy(id = "second")
            db.storeAlbumPage(AlbumPage(AlbumItem("album", "Album", null, emptyList()), listOf(second, first)))
            db.dao().upsertHistorySong(first.copy(id = "direct-one"))
            db.dao().upsertHistorySong(first.copy(id = "direct-two"))
            db.dao().upsertHistorySong(first.copy(id = "unrelated", album = AlbumTiny("other", "Other")))
            assertEquals(
                listOf("second", "first", "direct-one", "direct-two"),
                db
                    .dao()
                    .storedAlbumSongs("album")
                    .first()
                    .map { it.id },
            )
            db.dao().upsert(SongAlbumMap("first", "album", 0))
            assertEquals(
                listOf("second", "first", "direct-one", "direct-two"),
                db
                    .dao()
                    .storedAlbumSongs("album")
                    .first()
                    .map { it.id },
            )
            assertEquals(
                listOf("unrelated"),
                db
                    .dao()
                    .storedAlbumSongs("other")
                    .first()
                    .map { it.id },
            )
        }

    @Test
    fun countsAndSnapshotsMatchExistingStatisticsIncludingHistoricalEdits() =
        databaseTest { db ->
            val song = SongItem("song", "Song", "cover", listOf(ArtistTiny("artist", "Earth, Wind & Fire")), AlbumTiny("album", "Album"))
            repeat(12) { db.recordHistoryPlay(song, 1000L + it, playTimeMs = 20L) }
            db.recordHistoryPlay(song.copy(id = "other"), 1100, source = "YOUTUBE")
            val edited = requireNotNull(db.recordHistoryPlay(song, 1200, playTimeMs = 40))
            db.updateHistoryEntry(edited, "Earlier title", "Tyler, The Creator", "Earlier album")
            BlockedContent.blockArtist("edited", "Tyler, The Creator")
            assertEquals(mapOf("song" to 12), db.collectionPlayCounts(listOf("song", "absent")).first())
            assertEquals(emptyMap(), db.collectionPlayCounts(emptyList()).first())
            assertEquals(
                historySongStats(
                    db
                        .dao()
                        .events()
                        .visibleHistoryEvents()
                        .first(),
                ).associate { it.first.id to it.second },
                db.collectionPlayCounts().first(),
            )
            for (catalog in listOf(false, true)) {
                val snapshot = db.historyStatisticsSnapshot(1005, 1300, catalog, false)
                assertEquals(db.historySongStatistics(1005, 1300, catalog), snapshot.songs)
                assertEquals(db.historyArtistStatistics(1005, 1300, catalog), snapshot.artists)
                assertEquals(db.historyAlbumStatistics(1005, 1300, catalog), snapshot.albums)
                assertEquals(db.historyStatisticsTotals(1005, 1300, catalog), snapshot.totals)
                assertEquals(db.historyTimeBuckets(1005, 1300, false, false, catalog), snapshot.daily)
                assertEquals(db.historyTimeBuckets(1005, 1300, true, false, catalog), snapshot.hourly)
            }
        }

    @Test
    fun aggregateVisibilityKeepsUnicodeNamesAndSnapshotArtistIdMatching() =
        databaseTest { db ->
            val song = SongItem("song", "Song", null, listOf(ArtistTiny("artist", "BJÖRK")), null)
            db.recordHistoryPlay(song, 1000, playTimeMs = 10)
            BlockedContent.blockArtist("different", "björk")
            assertEquals(emptyMap(), db.collectionPlayCounts().first())
            assertEquals(emptyList(), db.recentVisibleHistory(100))
            BlockedContent.unblockArtist("different")
            BlockedContent.blockArtist("artist", "Outdated saved name")
            assertEquals(
                emptyList(),
                db
                    .dao()
                    .events()
                    .visibleHistoryEvents()
                    .first(),
            )
            assertEquals(emptyMap(), db.collectionPlayCounts().first())
            assertEquals(emptyList(), db.recentVisibleHistory(100, catalogArtistIds = true))
            assertEquals(1, db.recentVisibleHistory(100).size)
        }

    @Test
    fun mobileStatisticsKeepTimeRankingAndDistinctArtistIdentities() =
        databaseTest { db ->
            val first = SongItem("one", "One", "one-cover", listOf(ArtistTiny("artist-one", "Same name")), AlbumTiny("a", "Album"))
            val second = first.copy(id = "two", title = "Two", artists = listOf(ArtistTiny("artist-two", "Same name")))
            repeat(5) { db.recordHistoryPlay(first, 1000L + it, playTimeMs = 10) }
            db.recordHistoryPlay(second, 2000, playTimeMs = 100)
            db.recordHistoryPlay(first.copy(id = "unknown"), 3000, source = "YOUTUBE")
            val events =
                db
                    .dao()
                    .events()
                    .visibleHistoryEvents()
                    .first()
            val snapshot = db.mobileHistoryStatisticsSnapshot(0, Long.MAX_VALUE)
            assertEquals(historySongStats(events).map { it.first.id to it.second }, snapshot.songs.map { it.songId to it.playCount })
            assertEquals(historyArtistStats(events).map { it.first.id to it.second }, snapshot.artists.map { it.id to it.playCount })
            assertEquals(historyAlbumStats(events).map { it.id to it.playCount }, snapshot.albums.map { it.id to it.playCount })
            assertEquals(listOf("two", "one", "unknown"), db.mostPlayedHistorySongs().first().map { it.id })
            assertEquals(listOf("two", "unknown"), db.mostPlayedHistorySongs(sinceMs = 2000).first().map { it.id })
            assertEquals(listOf("two"), db.mostPlayedHistorySongs(limit = 1, sinceMs = 2000).first().map { it.id })
            assertEquals(emptyList(), db.mostPlayedHistorySongs(sinceMs = 3001).first())
            assertEquals(150L, snapshot.totals.playTimeMs)
        }

    @Test
    fun keysetPagesReturnDistinctSongsAndSkipBlockedRowsWithoutTruncation() =
        databaseTest { db ->
            val song = SongItem("visible", "Visible", null, emptyList(), null)
            db.withDatabaseTransaction {
                repeat(305) { index -> db.recordHistoryPlay(song.copy(id = "visible-$index"), 1000, playTimeMs = 1) }
                repeat(2) { db.recordHistoryPlay(song, 1000, playTimeMs = 1) }
                repeat(310) { db.recordHistoryPlay(song.copy(id = "blocked"), 2000, playTimeMs = 1) }
                db.recordHistoryPlay(song, 500, playTimeMs = 1)
            }
            Settings.shared.dislikedSongIds = "blocked"
            val loaded = mutableListOf<StoredHistoryRow>()
            var cursor: HistoryCursor? = null
            do {
                val page = db.recentVisibleHistory(100, before = cursor)
                loaded += page
                cursor = page.lastOrNull()?.let { HistoryCursor(it.playedAt, it.id) }
            } while (page.size == 100)
            assertEquals(306, loaded.size)
            assertEquals(loaded.size, loaded.map { it.songId }.distinct().size)
            assertEquals(1, loaded.count { it.songId == "visible" })
            assertEquals(306, db.recentVisibleHistory(Int.MAX_VALUE, 1000, 1000).size)
        }

    @Test
    fun boundedHistoryKeepsLatestPlayWithinRequestedWindow() =
        databaseTest { db ->
            val song = SongItem("bounded", "Bounded", null, emptyList(), null)
            db.recordHistoryPlay(song, 1000, playTimeMs = 1)
            db.recordHistoryPlay(song, 2000, playTimeMs = 1)

            val rows = db.recentVisibleHistory(10, sinceMs = 1000, untilMs = 1000)

            assertEquals(listOf(1000L), rows.map { it.playedAt })
        }

    @Test
    fun mostPlayedUsesWindowOnceAndWidgetIncludesLaterPlays() =
        databaseTest { db ->
            val a = SongItem("a", "A", "a-cover", emptyList(), null)
            val b = a.copy(id = "b", thumbnail = "b-cover")
            db.recordHistoryPlay(a, 1000, playTimeMs = 500)
            db.recordHistoryPlay(a, 2000, playTimeMs = 10)
            db.recordHistoryPlay(b, 2001, playTimeMs = 20)
            db.recordHistoryPlay(a, 2002, source = "YOUTUBE")
            assertEquals(
                listOf("b", "a"),
                db
                    .dao()
                    .mostPlayedSongs(1000, toTimeStamp = 2002)
                    .first()
                    .map { it.id },
            )
            assertEquals(
                listOf("a"),
                db
                    .dao()
                    .mostPlayedSongs(1000, limit = 1, offset = 1, toTimeStamp = 2002)
                    .first()
                    .map { it.id },
            )
            assertEquals(
                "a",
                db
                    .dao()
                    .topWidgetSong()
                    .first()
                    .single()
                    .id,
            )
            db.recordHistoryPlay(b, 2_000_000_000_000L, playTimeMs = 1000)
            assertEquals(
                "b",
                db
                    .dao()
                    .topWidgetSong()
                    .first()
                    .single()
                    .id,
            )
        }

    @Test
    fun widgetWinnerTracksArtworkEventCorrectionsAndDeletions() =
        databaseTest { db ->
            withTimeout(5_000) {
                val dao = db.dao()
                val a = SongEntity("a", "A", thumbnailUrl = "a-cover")
                val b = a.copy(id = "b", thumbnailUrl = "b-cover")
                dao.insert(a)
                dao.insert(b)
                val dated = LocalDateTime(2026, 1, 1, 0, 0)
                val undated = LocalDateTime(1970, 1, 1, 0, 0)
                val first = Event(songId = "a", timestamp = dated, playTime = 100)
                val firstId = dao.insert(first)
                dao.insert(Event(songId = "b", timestamp = dated, playTime = 20))
                dao.insert(Event(songId = "b", timestamp = dated, playTime = 10_000, playTimeKnown = false))
                dao.insert(Event(songId = "b", timestamp = undated, playTime = 10_000))
                val results = Channel<List<WidgetSong>>(Channel.UNLIMITED)
                val observer = launch { dao.topWidgetSong().distinctUntilChanged().collect { results.send(it) } }
                try {
                    assertEquals("a-cover", results.receive().single().thumbnailUrl)
                    dao.updatePlaybackPosition("a", 30_000)
                    dao.update(a.copy(playbackPosition = 30_000, thumbnailUrl = "new-cover"))
                    assertEquals("new-cover", results.receive().single().thumbnailUrl)

                    dao.updateCopiedHistoryEvent(firstId, undated, "LOCAL", false)
                    assertEquals("b", results.receive().single().id)
                    val later = first.copy(playTime = 200)
                    val laterId = dao.insert(later)
                    assertEquals("a", results.receive().single().id)
                    dao.delete(later.copy(id = laterId))
                    assertEquals("b", results.receive().single().id)
                    dao.delete(b)
                    assertTrue(results.receive().isEmpty())
                } finally {
                    observer.cancelAndJoin()
                }
            }
        }

    @Test
    fun syncProjectionHonorsSnapshotTokenPrecedenceAndSkipsNoOpWrites() =
        databaseTest { db ->
            val song = SongItem("song", "Song", null, emptyList(), null)
            val start = 1_765_000_000_000L
            val id =
                requireNotNull(
                    db.recordHistoryPlay(
                        song,
                        start,
                        source = "YOUTUBE",
                        token = "catalog-token",
                        remoteSynced = true,
                        remoteDateLabel = "date",
                    ),
                )
            db.dao().insert(HistorySnapshot(id, "Snapshot", "", null, null, null, "snapshot-token"))
            val incoming = YouTubeHistoryEntry(song, "Today", "snapshot-token")
            val index = db.historySyncIndex(listOf(incoming to RemoteHistoryDate(start, "date")))
            assertEquals(id, index.find("snapshot-token")?.id)
            assertNull(index.find("catalog-token"))
            assertFalse(db.applyYouTubeHistoryEntry(incoming, RemoteHistoryDate(start, "date"), index))
            assertTrue(db.applyYouTubeHistoryEntry(incoming, RemoteHistoryDate(start + 86_400_000, "date"), index))
            assertTrue(
                db.applyYouTubeHistoryEntry(incoming.copy(feedbackToken = "other"), RemoteHistoryDate(start + 86_400_000, "date"), index),
            )
            assertEquals(2, db.dao().eventCount().first())
        }
}
