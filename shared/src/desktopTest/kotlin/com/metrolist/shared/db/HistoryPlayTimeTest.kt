package com.metrolist.shared.db

import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.entities.HistorySnapshot
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.screens.LibrarySortType
import com.metrolist.shared.ui.screens.sortLibraryItems
import com.metrolist.shared.viewmodels.LibraryViewModel
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.datetime.LocalDateTime
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HistoryPlayTimeTest {
    @BeforeTest
    fun isolateSettings() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun restoreSettings() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun songDetailsTotalsCountHistoryWithoutInventingUnknownPlaytime() =
        runBlocking {
            val root = Files.createTempDirectory("song-details-totals").toFile()
            val db = getDesktopDatabaseBuilder(root.resolve("room.db")).build()
            try {
                val dao = db.dao()
                assertEquals(HistoryTotals(0, 0), dao.songListeningTotals("a").first())
                db.recordHistoryPlay(song("a"), 1_000, playTimeMs = 8_000)
                db.recordHistoryPlay(song("a"), 2_000, source = "YOUTUBE", token = "remote", remoteSynced = true)
                db.recordHistoryPlay(song("a"), 3_000, playTimeMs = 0)
                db.recordHistoryPlay(song("b"), 4_000, playTimeMs = 90_000)
                assertEquals(HistoryTotals(3, 8_000), dao.songListeningTotals("a").first())
                val ids =
                    db
                        .history()
                        .filter { it.songId == "a" }
                        .map { it.id }
                        .toSet()
                db.deleteHistoryEntries(ids)
                assertEquals(HistoryTotals(0, 0), dao.songListeningTotals("a").first())
                assertEquals(HistoryTotals(1, 90_000), dao.songListeningTotals("b").first())
            } finally {
                db.close()
                root.deleteRecursively()
            }
        }

    @Test
    fun acceptedLocalPlaysUpdateAllCollectionPlaytimeSorts() =
        runBlocking {
            val root = Files.createTempDirectory("history-playtime").toFile()
            val db = getDesktopDatabaseBuilder(root.resolve("room.db")).build()
            val scope = CoroutineScope(StandardTestDispatcher())
            try {
                val dao = db.dao()
                val date = LocalDateTime(2026, 1, 1, 0, 0)
                for (id in listOf("a", "b")) {
                    dao.upsert(SongEntity(id, id, albumId = "album-$id", totalPlayTime = if (id == "a") 5_000 else 20_000))
                    dao.upsert(ArtistEntity("artist-$id", id, bookmarkedAt = date))
                    dao.upsert(AlbumEntity("album-$id", title = id, songCount = 1, duration = 60, bookmarkedAt = date))
                    dao.upsert(PlaylistEntity("playlist-$id", name = id, isLocal = true))
                    dao.insert(PlaylistSongMap(songId = id, playlistId = "playlist-$id", position = 0))
                    dao.upsertHistorySong(song(id))
                }
                val library = LibraryViewModel(scope)

                suspend fun assertOrder(
                    first: String,
                    second: String,
                ) {
                    val items = library.loadLocalItems(db)
                    val categories =
                        listOf(
                            "artist" to items.filterIsInstance<ArtistItem>(),
                            "album" to items.filterIsInstance<AlbumItem>(),
                            "playlist" to items.filterIsInstance<PlaylistItem>(),
                        )
                    for ((prefix, collections) in categories) {
                        assertEquals(
                            listOf("$prefix-$first", "$prefix-$second"),
                            sortLibraryItems(collections, LibrarySortType.PLAYTIME, "", SortDirection.DESCENDING).map(YTItem::id),
                        )
                        assertEquals(
                            listOf("$prefix-$second", "$prefix-$first"),
                            sortLibraryItems(collections, LibrarySortType.PLAYTIME, "", SortDirection.ASCENDING).map(YTItem::id),
                        )
                    }
                }

                assertOrder("b", "a")
                assertNotNull(db.recordHistoryPlay(song("a"), 1_000, token = "accepted", playTimeMs = 10_000))
                assertNotNull(db.recordHistoryPlay(song("a"), 2_000, playTimeMs = 10_000))
                assertEquals(25_000L, dao.getSongById("a")?.song?.totalPlayTime)
                assertOrder("a", "b")

                assertNull(db.recordHistoryPlay(song("a"), 3_000, token = "accepted", playTimeMs = 50_000))
                db.recordHistoryPlay(song("a"), 4_000, source = "YOUTUBE", token = "remote", remoteSynced = true)
                db.recordHistoryPlay(song("a"), 5_000)
                db.recordHistoryPlay(song("a"), 6_000, playTimeMs = 0)
                db.recordHistoryPlay(song("a"), 7_000, playTimeMs = -1)
                assertNull(db.recordHistoryPlay(song(""), 8_000, playTimeMs = 50_000))
                assertEquals(25_000L, dao.getSongById("a")?.song?.totalPlayTime)
                assertOrder("a", "b")
            } finally {
                scope.cancel()
                db.close()
                root.deleteRecursively()
            }
        }

    @Test
    fun failedOrIgnoredHistoryWritesDoNotIncrementTotals() =
        runBlocking {
            val root = Files.createTempDirectory("history-playtime-rollback").toFile()
            val db = getDesktopDatabaseBuilder(root.resolve("room.db")).build()
            try {
                val dao = db.dao()
                dao.upsert(SongEntity("a", "Original", totalPlayTime = 5_000))
                db.useWriterConnection { connection ->
                    connection.usePrepared(
                        """
                        CREATE TRIGGER fail_history_snapshot BEFORE INSERT ON history_snapshot
                        BEGIN SELECT RAISE(ABORT, 'snapshot failure'); END
                        """.trimIndent(),
                    ) { it.step() }
                }
                assertFailsWith<SQLiteException> {
                    db.recordHistoryPlay(song("a"), 1_000, token = "retry", playTimeMs = 10_000)
                }
                assertEquals(5_000L, dao.getSongById("a")?.song?.totalPlayTime)
                assertEquals(emptyList(), db.history())
                db.useWriterConnection { connection ->
                    connection.usePrepared("DROP TRIGGER fail_history_snapshot") { it.step() }
                }
                assertNotNull(db.recordHistoryPlay(song("a"), 1_000, token = "retry", playTimeMs = 10_000))
                assertEquals(15_000L, dao.getSongById("a")?.song?.totalPlayTime)
                assertEquals(1, db.history().size)
                db.useWriterConnection { connection ->
                    connection.usePrepared(
                        """
                        CREATE TRIGGER ignore_history_event BEFORE INSERT ON event
                        BEGIN SELECT RAISE(IGNORE); END
                        """.trimIndent(),
                    ) { it.step() }
                }
                assertNull(db.recordHistoryPlay(song("a"), 2_000, playTimeMs = 10_000))
                assertEquals(15_000L, dao.getSongById("a")?.song?.totalPlayTime)
                assertEquals(1, db.history().size)
            } finally {
                db.close()
                root.deleteRecursively()
            }
        }

    @Test
    fun reopeningRepairsRetainedMeasuredHistoryWithoutAddingImportedTotalsAgain() =
        runBlocking {
            val root = Files.createTempDirectory("history-playtime-repair").toFile()
            val file = root.resolve("room.db")
            try {
                val original = getDesktopDatabaseBuilder(file).build()
                try {
                    val dao = original.dao()
                    val date = LocalDateTime(2026, 1, 1, 0, 0)
                    for ((id, total) in listOf("stale" to 0L, "partial" to 10_000L, "imported" to 90_000L)) {
                        dao.upsert(SongEntity(id, id, totalPlayTime = total))
                        val local = dao.insert(Event(songId = id, timestamp = date, playTime = 20_000))
                        dao.insert(HistorySnapshot(local, id, "Artist", null, null, null))
                        dao.insert(Event(songId = id, timestamp = date, playTime = 30_000, source = "YOUTUBE", remoteSynced = true))
                        dao.insert(Event(songId = id, timestamp = date, playTime = 500_000, playTimeKnown = false))
                        dao.insert(Event(songId = id, timestamp = date, playTime = -1))
                    }
                    dao.upsert(SongEntity("deleted-history", "Kept total", totalPlayTime = 70_000))
                } finally {
                    original.close()
                }

                repeat(2) { reopen ->
                    val db = getDesktopDatabaseBuilder(file).build()
                    try {
                        val dao = db.dao()
                        assertEquals(50_000L + reopen * 15_000L, dao.getSongById("stale")?.song?.totalPlayTime)
                        assertEquals(50_000L, dao.getSongById("partial")?.song?.totalPlayTime)
                        assertEquals(90_000L + reopen * 15_000L, dao.getSongById("imported")?.song?.totalPlayTime)
                        assertEquals(70_000L, dao.getSongById("deleted-history")?.song?.totalPlayTime)
                        if (reopen == 0) {
                            for (id in listOf("stale", "imported")) {
                                db.recordHistoryPlay(song(id), 2_000, playTimeMs = 15_000)
                            }
                            db.deleteHistoryEntries(
                                db
                                    .history()
                                    .filter { it.songId == "imported" }
                                    .map { it.id }
                                    .toSet(),
                            )
                        }
                    } finally {
                        db.close()
                    }
                }
            } finally {
                root.deleteRecursively()
            }
        }

    private fun song(id: String) = SongItem(id, id, null, listOf(ArtistTiny("artist-$id", id)), AlbumTiny("album-$id", id))
}
