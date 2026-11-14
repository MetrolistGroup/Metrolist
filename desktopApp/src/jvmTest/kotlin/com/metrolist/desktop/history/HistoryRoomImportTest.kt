package com.metrolist.desktop.history

import com.metrolist.shared.db.StoredHistoryPlay
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.db.history
import com.metrolist.shared.db.importHistorySources
import com.metrolist.shared.db.updateHistoryEntry
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryRoomImportTest {
    @Test
    fun xdgIsAuthoritativeOverRetainedRecoveryRows() =
        runBlocking {
            val root = Files.createTempDirectory("history-active-").toFile()
            val db = getDatabaseBuilder(root.resolve("room.db")).build()
            try {
                val old = root.resolve("old/history.db")
                val xdg = root.resolve("xdg/history.db")
                listOf(old, xdg).forEach { file ->
                    file.parentFile.mkdirs()
                    DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
                        connection.createStatement().use { sql ->
                            sql.execute(
                                "CREATE TABLE play_history (id INTEGER PRIMARY KEY, song_id TEXT, title TEXT, artists TEXT, thumbnail_url TEXT, played_at INTEGER)",
                            )
                            sql.execute("INSERT INTO play_history VALUES (2, 'song', 'Present', 'Artist', NULL, 1765000000000)")
                            if (file ==
                                old
                            ) {
                                sql.execute(
                                    "INSERT INTO play_history VALUES (1, 'song', 'Deleted from XDG', 'Artist', NULL, 1764990000000)",
                                )
                            }
                        }
                    }
                }
                assertEquals(listOf(xdg), activeLegacyHistoryFiles(old, xdg))
                val originalBytes = xdg.readBytes()
                assertTrue(xdg.delete())
                assertTrue(xdg.mkdir())
                assertFailsWith<IllegalStateException> { db.initializeDesktopHistory(old, xdg) }
                assertNull(db.dao().historyImportEnabled())
                assertTrue(db.history().isEmpty())
                assertTrue(xdg.delete())
                xdg.writeBytes(originalBytes)
                db.initializeDesktopHistory(old, xdg)
                assertEquals(false, db.dao().historyImportEnabled())
                assertEquals(listOf("Present"), db.history().map { it.title })
                xdg.writeText("damaged recovery copy after successful migration")
                db.initializeDesktopHistory(old, xdg)
                assertEquals(listOf("Present"), db.history().map { it.title })
                assertEquals(2, readLegacyHistory(old).size)
                assertEquals(listOf(old), activeLegacyHistoryFiles(old, root.resolve("missing/history.db")))
            } finally {
                db.close()
                root.deleteRecursively()
            }
        }

    @Test
    fun inactiveRecoveryFailuresDoNotBlockImportOrActiveQueuePurge() =
        runBlocking {
            val root = Files.createTempDirectory("history-recovery-").toFile()
            val db = getDatabaseBuilder(root.resolve("room.db")).build()
            try {
                val xdg =
                    root.resolve("xdg/history.db").apply {
                        parentFile.mkdirs()
                        DriverManager.getConnection("jdbc:sqlite:$absolutePath").use { connection ->
                            connection.createStatement().use { sql ->
                                sql.execute(
                                    "CREATE TABLE play_history (id INTEGER PRIMARY KEY, song_id TEXT, title TEXT, artists TEXT, thumbnail_url TEXT, played_at INTEGER)",
                                )
                                sql.execute("INSERT INTO play_history VALUES (1, 'song', 'Kept', 'Artist', NULL, 1765000000000)")
                                sql.execute("CREATE TABLE scrobble_queue (id INTEGER PRIMARY KEY)")
                                sql.execute("INSERT INTO scrobble_queue VALUES (1)")
                            }
                        }
                    }
                val old = root.resolve("old/history.db")
                old.parentFile.mkdirs()
                val sources = activeLegacyHistoryFiles(old, xdg)
                db.importHistorySources(sources.map { it.canonicalPath to readLegacyHistory(it) })
                listOf("invalid SQLite", "directory", "partial").forEach { condition ->
                    when (condition) {
                        "directory" -> {
                            old.mkdirs()
                        }

                        "partial" -> {
                            DriverManager.getConnection("jdbc:sqlite:${old.absolutePath}").use { connection ->
                                connection.createStatement().use { sql -> sql.execute("CREATE TABLE scrobble_queue (id INTEGER)") }
                            }
                        }

                        else -> {
                            old.writeText(condition)
                        }
                    }
                    purgeLegacyScrobbleQueues(sources, old, xdg)
                    assertEquals(listOf("Kept"), db.history().map { it.title })
                    if (condition == "directory") assertTrue(old.isDirectory)
                    if (condition == "invalid SQLite") assertEquals(condition, old.readText())
                    if (condition == "partial") {
                        DriverManager.getConnection("jdbc:sqlite:${old.absolutePath}").use { connection ->
                            connection.createStatement().use { sql ->
                                sql.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='scrobble_queue'").use { rs ->
                                    assertTrue(rs.next())
                                    assertEquals(0, rs.getInt(1))
                                }
                            }
                        }
                    }
                    old.deleteRecursively()
                }
                DriverManager.getConnection("jdbc:sqlite:${xdg.absolutePath}").use { connection ->
                    connection.createStatement().use { sql ->
                        sql.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='scrobble_queue'").use { rs ->
                            assertTrue(rs.next())
                            assertEquals(0, rs.getInt(1))
                        }
                    }
                }
                old.writeText("invalid SQLite")
                assertFailsWith<Exception> { purgeLegacyScrobbleQueues(listOf(old), old, xdg) }
                Unit
            } finally {
                db.close()
                root.deleteRecursively()
            }
        }

    private fun play(
        id: Long,
        song: String = "song",
        token: String? = null,
    ) = StoredHistoryPlay(
        id,
        song,
        "Old title $id",
        "Artist $id",
        "Old album",
        174L,
        "https://art/$id",
        1_765_000_000_000L,
        "YOUTUBE",
        token,
        true,
    )

    @Test
    fun importsSnapshotsWithoutChangingEditedSongsAndPreservesDuplicatesAndTombstones() =
        runBlocking {
            val root = Files.createTempDirectory("history-room-").toFile()
            val db = getDatabaseBuilder(root.resolve("room.db")).build()
            try {
                db.dao().upsert(SongEntity(id = "song", title = "Current title", customTitle = "My edit", customArtist = "My artist"))
                val existingId =
                    db.dao().insert(
                        Event(
                            songId = "song",
                            timestamp = LocalDateTime(2025, 1, 1, 0, 0),
                            playTime = 3_000,
                            remoteFeedbackToken = "shared-token",
                        ),
                    )
                val originals = listOf(play(1, token = "shared-token"), play(2, token = "different-token"), play(3, token = null))
                val xdg =
                    originals.map {
                        if (it.legacyId ==
                            2L
                        ) {
                            it.copy(
                                title = "Edited on XDG",
                                duration = null,
                                playedAt = it.playedAt + 90_000L,
                                source = "LOCAL",
                                remoteSynced = false,
                            )
                        } else {
                            it
                        }
                    }
                db.importHistorySources(listOf("original" to originals, "xdg" to xdg))
                assertEquals("Edited on XDG", db.history().first { it.remoteFeedbackToken == "different-token" }.title)
                assertNull(db.history().first { it.remoteFeedbackToken == "different-token" }.duration)
                assertEquals("LOCAL", db.history().first { it.remoteFeedbackToken == "different-token" }.source)
                assertEquals(originals[1].playedAt + 90_000L, db.history().first { it.remoteFeedbackToken == "different-token" }.playedAt)
                assertEquals(4, db.history().size)
                assertEquals(3, db.history().count { it.playTimeMs == null })
                val imported = db.history().first { it.title == "Old title 1" }
                assertTrue(imported.id != 1L)
                assertEquals("shared-token", imported.remoteFeedbackToken)
                assertEquals(174L, imported.duration)
                assertEquals("Artist 1", imported.artists)
                assertEquals("https://art/1", imported.thumbnailUrl)
                assertEquals("Old album", imported.album)
                assertNull(imported.toHistoryEntry().playTimeMs)
                assertEquals(
                    3_000L,
                    db
                        .history()
                        .first { it.id == existingId }
                        .toHistoryEntry()
                        .playTimeMs,
                )
                assertEquals(listOf("shared-token"), db.dao().historyTokens(listOf(imported.id)))
                db.updateHistoryEntry(existingId, "Edited current", "Current artist", "Current album")
                assertEquals("Edited current", db.history().first { it.id == existingId }.title)
                db.updateHistoryEntry(imported.id, "Edited play", "Historical artist", "Historical album")
                assertEquals("Edited play", db.history().first { it.id == imported.id }.title)
                assertEquals("Historical artist", db.history().first { it.id == imported.id }.artists)
                assertEquals("Edited current", db.history().first { it.id == existingId }.title)
                assertEquals(
                    "My edit",
                    db
                        .dao()
                        .getSongById("song")
                        ?.song
                        ?.customTitle,
                )
                db.dao().delete(
                    db
                        .dao()
                        .historyRows()
                        .first { it.event.id == imported.id }
                        .event,
                )
                assertNull(db.dao().historyProvenance("original", 1)?.eventId)
                assertNull(db.dao().historyProvenance("xdg", 1)?.eventId)
                db.importHistorySources(listOf("original" to originals, "xdg" to xdg))
                assertEquals(3, db.history().size)
                assertEquals(1, db.history().count { it.title == "Edited on XDG" })
                assertEquals(1, db.history().count { it.title == "Old title 3" })
            } finally {
                db.close()
                root.deleteRecursively()
            }
        }

    @Test
    fun midTransactionFailureRollsBackAndRetriesWithoutLosingSource() =
        runBlocking {
            val root = Files.createTempDirectory("history-retry-").toFile()
            val db = getDatabaseBuilder(root.resolve("room.db")).build()
            try {
                val old = root.resolve("old.db")
                val plays =
                    DriverManager.getConnection("jdbc:sqlite:${old.absolutePath}").use { connection ->
                        connection.createStatement().use { sql ->
                            sql.execute("PRAGMA journal_mode=WAL")
                            sql.execute(
                                "CREATE TABLE play_history (id INTEGER PRIMARY KEY, song_id TEXT, title TEXT, artists TEXT, album TEXT, duration INTEGER, thumbnail_url TEXT, played_at INTEGER, source TEXT, remote_feedback_token TEXT, remote_synced INTEGER)",
                            )
                            sql.execute(
                                "INSERT INTO play_history VALUES (1,'s','Title','Earth, Wind & Fire','Album',123,'art',1765000000000,'LOCAL',NULL,0)",
                            )
                            sql.execute(
                                "INSERT INTO play_history VALUES (2,'s','Title','Earth, Wind & Fire','Album',123,'art',1765000000000,'LOCAL',NULL,0)",
                            )
                        }
                        assertTrue(root.resolve("old.db-wal").isFile)
                        readLegacyHistory(old)
                    }
                assertEquals(2, plays.size)
                assertFailsWith<IllegalStateException> {
                    db.importHistorySources(listOf("old" to plays)) { if (it == 0) error("injected failure") }
                }
                assertEquals(0, db.history().size)
                assertNull(db.dao().historyProvenance("old", 1))
                assertTrue(old.isFile)
                db.importHistorySources(listOf("old" to plays))
                assertEquals(2, db.history().size)
                assertEquals(
                    "Earth, Wind & Fire",
                    db
                        .dao()
                        .getSongById("s")
                        ?.artists
                        ?.single()
                        ?.name,
                )
                assertEquals("Earth, Wind & Fire", db.history().first().artists)
                db.importHistorySources(listOf("old" to plays))
                assertEquals(2, db.history().size)
            } finally {
                db.close()
                root.deleteRecursively()
            }
        }

    @Test
    fun invalidLegacySnapshotIsNeverInterpretedAsEmptyHistory() {
        val root = Files.createTempDirectory("history-invalid-").toFile()
        try {
            val corrupt = root.resolve("old.db").apply { writeText("invalid SQLite") }
            assertFailsWith<Exception> { readLegacyHistory(corrupt) }
            assertTrue(corrupt.isFile)
            assertFalse(root.resolve("room.db").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
