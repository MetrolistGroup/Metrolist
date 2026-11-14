package com.metrolist.shared.db

import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryVisibilityTest {
    @Test
    fun recentHistoryPagesPastBlockedRowsAndDeletesOnlyRequestedIds() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val root = Files.createTempDirectory("bounded-history").toFile()
            val db = getDatabaseBuilder(root.resolve("music.db")).build()
            try {
                val time = 1_765_000_000_000L
                val first = db.recordHistoryPlay(SongItem("first", "First", null, listOf(ArtistTiny(null, "Visible")), null), time)!!
                val second = db.recordHistoryPlay(SongItem("second", "Second", null, listOf(ArtistTiny(null, "Visible")), null), time)!!
                repeat(260) { index ->
                    db.recordHistoryPlay(
                        SongItem("blocked$index", "Blocked", null, listOf(ArtistTiny(null, "Tyler, The Creator")), null),
                        time + index + 1,
                        token = "token$index",
                    )
                }
                BlockedContent.blockArtist("artist", "Tyler, The Creator")
                assertEquals(listOf(second, first), db.recentVisibleHistory(2).map { it.id })
                assertEquals(listOf(second), db.recentVisibleHistory(1, time, time).map { it.id })
                assertEquals(emptyList(), db.recentVisibleHistory(2, time + 1))
                db.withDatabaseTransaction { db.dao().deleteHistoryIds(listOf(first)) }
                assertEquals(listOf(second), db.recentVisibleHistory(2).map { it.id })
                assertEquals(261, db.history().size)
            } finally {
                db.close()
                root.deleteRecursively()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun blocksSnapshotArtistNamesAndSongIdsWithoutLosingUnknownListeningTime() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        try {
            val rows =
                listOf(
                    StoredHistoryRow(1, "first", "One", "Artist One", null, null, null, 1, "LOCAL", null, null),
                    StoredHistoryRow(2, "second", "Two", "Artist Two", null, 300, null, 1, "YOUTUBE", null, null),
                    StoredHistoryRow(3, "third", "Three", "Artist Two", null, null, null, 1, "LOCAL", null, 25_000),
                    StoredHistoryRow(4, "fourth", "Four", "Museum of Love", null, null, null, 1, "LOCAL", null, null),
                    StoredHistoryRow(
                        5,
                        "fifth",
                        "Five",
                        "Muse, A_B",
                        null,
                        null,
                        null,
                        1,
                        "LOCAL",
                        null,
                        null,
                        artistNames = listOf("Muse", "A_B"),
                    ),
                    StoredHistoryRow(
                        6,
                        "sixth",
                        "Six",
                        "Tyler, The Creator",
                        null,
                        null,
                        null,
                        1,
                        "LOCAL",
                        null,
                        null,
                        artistNames = listOf("Tyler, The Creator"),
                    ),
                )
            assertEquals(25_000L, rows.sumOf { it.playTimeMs ?: 0 })
            BlockedContent.blockArtist("two", "Artist Two")
            assertEquals(listOf("first", "fourth", "fifth", "sixth"), rows.visibleHistory().map { it.songId })
            BlockedContent.blockArtist("muse", "Muse")
            assertEquals(listOf("first", "fourth", "sixth"), rows.visibleHistory().map { it.songId })
            BlockedContent.blockArtist("tyler", "Tyler, The Creator")
            Settings.shared.dislikedSongIds = "first"
            assertEquals(listOf("fourth"), rows.visibleHistory().map { it.songId })
        } finally {
            Settings.replaceSharedForTest(null)
        }
    }

    @Test
    fun sqlArtistFilterMatchesDelimitedNamesLiterally() {
        val db = JdbcSQLiteDriver().open(":memory:")
        try {
            val matches =
                db.prepare(
                    "SELECT $HISTORY_SNAPSHOT_ARTIST_MATCH_SQL AS matched FROM (SELECT ? AS artistNamesJson, ? AS artists) AS blocked_snapshot",
                )
            matches.use { query ->
                fun match(
                    artists: String,
                    names: List<String>?,
                    blocked: String,
                ): Long {
                    query.bindText(1, Json.encodeToString(blocked))
                    query.bindText(2, blocked)
                    if (names == null) query.bindNull(3) else query.bindText(3, Json.encodeToString(names))
                    query.bindText(4, artists)
                    assertEquals(true, query.step())
                    return query.getLong(0).also { query.reset() }
                }
                assertEquals(0L, match("Museum of Love", listOf("Museum of Love"), "Muse"))
                assertEquals(1L, match("Muse, Museum of Love", listOf("Muse", "Museum of Love"), "mUsE"))
                assertEquals(1L, match("A%B, C_D", listOf("A%B", "C_D"), "A%B"))
                assertEquals(0L, match("A%B, C_D", listOf("A%B", "C_D"), "A_B"))
                assertEquals(1L, match("Tyler, The Creator", listOf("Tyler, The Creator"), "Tyler, The Creator"))
                assertEquals(0L, match("Tyler, The Creator", listOf("Tyler, The Creator"), "Tyler"))
                assertEquals(1L, match("Tyler, The Creator", null, "Tyler, The Creator"))
                assertEquals(0L, match("Tyler, The Creator", null, "Tyler"))
            }
        } finally {
            db.close()
        }
    }
}
