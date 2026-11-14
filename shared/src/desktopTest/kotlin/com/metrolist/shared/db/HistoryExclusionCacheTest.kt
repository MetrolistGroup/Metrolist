package com.metrolist.shared.db

import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryExclusionCacheTest {
    @Test
    fun pagesReuseCreditsAndChangesInvalidateExclusionsAtomically() =
        runBlocking {
            val previous = Settings.sharedOverrideForTest()
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val directory = Files.createTempDirectory("history-exclusion-cache")
            var creditQueries = 0
            val delegate = JdbcSQLiteDriver()
            val driver =
                object : SQLiteDriver by delegate {
                    override fun open(fileName: String): SQLiteConnection {
                        val connection = delegate.open(fileName)
                        return object : SQLiteConnection by connection {
                            override fun prepare(sql: String): SQLiteStatement {
                                val statement = connection.prepare(sql)
                                if (!sql.startsWith("SELECT DISTINCT e.songId, h.artists")) return statement
                                return object : SQLiteStatement by statement {
                                    private var firstStep = true

                                    override fun step(): Boolean {
                                        if (firstStep) creditQueries++
                                        firstStep = false
                                        return statement.step()
                                    }

                                    override fun reset() {
                                        firstStep = true
                                        statement.reset()
                                    }
                                }
                            }
                        }
                    }
                }
            val db = getDesktopDatabaseBuilder(directory.resolve("music.db").toFile()).setDriver(driver).build()
            try {
                val song = SongItem("one", "One", null, listOf(ArtistTiny("artist", "Ärtist")), null)
                val first = requireNotNull(db.recordHistoryPlay(song, 1))
                db.recordHistoryPlay(song.copy(id = "two"), 2)
                BlockedContent.blockArtist("absent", "Absent")
                runCatching {
                    db.withDatabaseTransaction {
                        db.recentVisibleHistory(1)
                        error("Roll back initial preparation and triggers")
                    }
                }
                assertNull(db.dao().historyExclusionScope())
                creditQueries = 0
                val page = db.recentVisibleHistory(1).single()
                assertEquals(1, creditQueries)
                db.recentVisibleHistory(1, before = HistoryCursor(page.playedAt, page.id, page.remoteOrder))
                db.visibleHistoryIds("")
                assertEquals(1, creditQueries)
                BlockedContent.blockArtist("artist", "ärtist")
                assertTrue(db.recentVisibleHistory(100).isEmpty())
                assertEquals(2, creditQueries)
                BlockedContent.unblockArtist("artist")
                assertEquals(2, db.recentVisibleHistory(100).size)
                assertEquals(3, creditQueries)
                db.dao().updateHistorySnapshot(first, "One", "Absent", null, "[\"Absent\"]")
                assertEquals(listOf("two"), db.recentVisibleHistory(100).map { it.songId })
                assertEquals(4, creditQueries)
                db.recordHistoryPlay(song.copy(id = "three", artists = listOf(ArtistTiny("absent", "Absent"))), 3)
                assertEquals(listOf("two"), db.recentVisibleHistory(100).map { it.songId })
                assertEquals(5, creditQueries)
                runCatching {
                    db.withDatabaseTransaction {
                        db.dao().updateHistorySnapshot(first, "One", "Allowed", null, "[\"Allowed\"]")
                        assertNull(db.dao().historyExclusionScope())
                        error("Rollback")
                    }
                }
                assertFalse(db.dao().historyExclusionScope().isNullOrEmpty())
                db.recentVisibleHistory(100)
                assertEquals(5, creditQueries)
                for ((index, sql) in listOf(
                    "UPDATE artist SET name = 'Renamed' WHERE id = 'artist'",
                    "UPDATE song_artist_map SET position = position + 1 WHERE songId = 'two'",
                    "UPDATE event SET remoteOrder = remoteOrder + 1 WHERE songId = 'two'",
                    "DELETE FROM event WHERE songId = 'two'",
                ).withIndex()) {
                    db.useWriterConnection { connection -> connection.usePrepared(sql) { it.step() } }
                    assertNull(db.dao().historyExclusionScope())
                    db.recentVisibleHistory(100)
                    assertEquals(6 + index, creditQueries)
                }
            } finally {
                db.close()
                directory.toFile().deleteRecursively()
                Settings.replaceSharedForTest(previous)
            }
        }
}
