package com.metrolist.shared.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDatabaseMigrationTest {
    @Test
    fun reportedSchemasUpgradeThroughRoomWithoutLosingLibraryData() =
        runBlocking<Unit> {
            val directory = createTempDirectory("database-migration").toFile()
            try {
                (DATABASE_VERSION downTo 36).forEach { version ->
                    val file = File(directory, "library-$version.db")
                    createHistoricalLibrary(file.absolutePath, version, JdbcSQLiteDriver())
                    val database = getDesktopDatabaseBuilder(file).build()
                    try {
                        assertEquals(
                            "Keep my music",
                            database
                                .dao()
                                .song("song")
                                .first()
                                ?.song
                                ?.title,
                        )
                        assertEquals(
                            listOf("song"),
                            database
                                .dao()
                                .playlistSongs("playlist")
                                .first()
                                .map { it.song.id },
                        )
                        assertFalse(
                            database
                                .dao()
                                .song("song")
                                .first()!!
                                .song.isUnavailable,
                        )
                        assertEquals(1, database.dao().eventCount().first())
                        assertTrue(database.pendingScrobbleDao().all().isEmpty())
                        assertEquals(listOf(historicalSpeedDialItem(version)), database.speedDialDao().getAllOnce())
                        val cached = requireNotNull(database.dao().cachedLyrics("song"))
                        assertEquals("[00:01]Saved source", cached.lyrics)
                        assertEquals("SimpMusic", cached.provider)
                        assertEquals(425, cached.offsetMs)
                        assertEquals("Saved translation", cached.translatedLyrics)
                        assertFalse(cached.isUserSelected)
                        assertNull(cached.missLookupKey)
                        assertNull(cached.missExpiresAt)
                        assertTrue(requireNotNull(database.dao().cachedLyrics("local")).let { it.isUserSelected || it.userLyrics != null })
                    } finally {
                        database.close()
                    }
                    JdbcSQLiteDriver().open(file.absolutePath).use { connection ->
                        connection.prepare("PRAGMA user_version").use {
                            assertTrue(it.step())
                            assertEquals(DATABASE_VERSION.toLong(), it.getLong(0))
                        }
                        connection.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) }
                    }
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun version38PreservesExistingSpeedDialMetadataColumns() =
        runBlocking<Unit> {
            val directory = createTempDirectory("speed-dial-migration").toFile()
            val columns = listOf("subtitleIds", "albumId", "albumName")
            val values = listOf("artist", "album", "Saved album")
            try {
                for (shape in 0..7) {
                    val file = File(directory, "library-$shape.db")
                    createHistoricalLibrary(file.absolutePath, 38, JdbcSQLiteDriver())
                    JdbcSQLiteDriver().open(file.path).use { connection ->
                        columns.forEachIndexed { index, column ->
                            if (shape and (1 shl index) != 0) {
                                connection.execSQL("ALTER TABLE speed_dial ADD COLUMN `$column` TEXT")
                                connection.execSQL("UPDATE speed_dial SET `$column` = '${values[index]}'")
                            }
                        }
                    }
                    val database = getDesktopDatabaseBuilder(file).build()
                    try {
                        val expected =
                            historicalSpeedDialItem(38).copy(
                                subtitleIds = values[0].takeIf { shape and 1 != 0 },
                                albumId = values[1].takeIf { shape and 2 != 0 },
                                albumName = values[2].takeIf { shape and 4 != 0 },
                            )
                        assertEquals(listOf(expected), database.speedDialDao().getAllOnce(), "shape $shape")
                    } finally {
                        database.close()
                    }
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun booleanDefaultsMigrationPreservesRelationsAndNormalizesLegacyTextDefaults() =
        runBlocking<Unit> {
            val directory = createTempDirectory("database-boolean-defaults").toFile()
            val file = File(directory, "library.db")
            try {
                createHistoricalLibrary(file.absolutePath, 58, JdbcSQLiteDriver())
                val tables =
                    listOf(
                        "song_artist_map",
                        "song_album_map",
                        "album_artist_map",
                        "playlist_song_map",
                        "import_track_match",
                        "related_song_map",
                        "history_snapshot",
                        "history_provenance",
                    )
                val before =
                    JdbcSQLiteDriver().open(file.absolutePath).use { connection ->
                        connection.execSQL("PRAGMA foreign_keys = ON")
                        connection.execSQL("INSERT INTO artist (id, name, lastUpdateTime) VALUES ('artist', 'Artist', 0)")
                        connection.execSQL(
                            "INSERT INTO album (id, title, songCount, duration, lastUpdateTime) VALUES ('album', 'Album', 1, 240, 0)",
                        )
                        connection.execSQL("INSERT INTO song_artist_map VALUES ('song', 'artist', 0)")
                        connection.execSQL("INSERT INTO song_album_map VALUES ('song', 'album', 0)")
                        connection.execSQL("INSERT INTO album_artist_map VALUES ('album', 'artist', 0)")
                        connection.execSQL("INSERT INTO import_track_match VALUES ('playlist', 'provider', 'key', 'song')")
                        connection.execSQL("INSERT INTO related_song_map (songId, relatedSongId) VALUES ('song', 'song')")
                        connection.execSQL(
                            "INSERT INTO history_snapshot (eventId, title, artists) VALUES (1, 'Saved title', 'Saved artist')",
                        )
                        connection.execSQL(
                            "INSERT INTO history_provenance (source, legacyId, songId, playedAt, eventId) VALUES ('legacy', 7, 'song', 1000, 1)",
                        )
                        // SQLite before 3.23 stored bare boolean defaults as text.
                        connection.execSQL("UPDATE song SET explicit = 'false', romanizeLyrics = 'true', isDownloaded = 1")
                        connection.execSQL("UPDATE lyrics SET translationNotNeeded = 'false'")
                        connection.execSQL("UPDATE sqlite_sequence SET seq = 100 WHERE name = 'event'")
                        tables.associateWith { rows(connection, it) }
                    }
                val database = getDesktopDatabaseBuilder(file).build()
                try {
                    val song = requireNotNull(database.dao().song("song").first()).song
                    assertFalse(song.explicit)
                    assertTrue(song.romanizeLyrics)
                    assertTrue(song.isDownloaded)
                    assertTrue(requireNotNull(database.dao().cachedLyrics("local")).let { it.isUserSelected || it.userLyrics != null })
                } finally {
                    database.close()
                }
                JdbcSQLiteDriver().open(file.absolutePath).use { connection ->
                    assertEquals(before, tables.associateWith { rows(connection, it) })
                    connection.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) }
                    listOf("song", "artist", "album", "playlist", "lyrics", "event").forEach { table ->
                        connection.prepare("PRAGMA table_info(`$table`)").use { statement ->
                            while (statement.step()) {
                                if (!statement.isNull(4)) {
                                    assertFalse(statement.getText(4) in setOf("true", "false"), "$table.${statement.getText(1)}")
                                }
                            }
                        }
                    }
                    connection.execSQL("INSERT INTO song (id, title, duration, liked, totalPlayTime) VALUES ('new', 'New', 1, 0, 0)")
                    connection
                        .prepare(
                            "SELECT explicit, romanizeLyrics, typeof(explicit), typeof(romanizeLyrics) FROM song WHERE id = 'new'",
                        ).use {
                            assertTrue(it.step())
                            assertEquals(0L, it.getLong(0))
                            assertEquals(1L, it.getLong(1))
                            assertEquals("integer", it.getText(2))
                            assertEquals("integer", it.getText(3))
                        }
                    connection.execSQL("INSERT INTO event (songId, timestamp, playTime) VALUES ('song', 2000, 1)")
                    connection.prepare("SELECT MAX(id) FROM event").use {
                        assertTrue(it.step())
                        assertEquals(101L, it.getLong(0))
                    }
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    private fun rows(
        connection: SQLiteConnection,
        table: String,
    ): List<List<String?>> =
        connection.prepare("SELECT * FROM `$table` ORDER BY rowid").use { statement ->
            buildList {
                while (statement.step()) {
                    add(List(statement.getColumnCount()) { if (statement.isNull(it)) null else statement.getText(it) })
                }
            }
        }

    @Test
    fun unknownMigrationPathIsRejectedWithoutDeletingLibraryData() =
        runBlocking<Unit> {
            val directory = createTempDirectory("database-unknown-version").toFile()
            val file = File(directory, "library.db")
            try {
                createHistoricalLibrary(file.absolutePath, 47, JdbcSQLiteDriver())
                JdbcSQLiteDriver().open(file.absolutePath).use { it.execSQL("PRAGMA user_version = 35") }
                val database = getDesktopDatabaseBuilder(file).build()
                try {
                    val error = assertFailsWith<IllegalStateException> { database.dao().historyRows() }
                    assertTrue(error.message.orEmpty().contains("A migration from 35 to $DATABASE_VERSION was required but not found"))
                } finally {
                    database.close()
                }
                JdbcSQLiteDriver().open(file.absolutePath).use { connection ->
                    connection.prepare("PRAGMA user_version").use {
                        assertTrue(it.step())
                        assertEquals(35L, it.getLong(0))
                    }
                    connection.prepare("SELECT title FROM song WHERE id = 'song'").use {
                        assertTrue(it.step())
                        assertEquals("Keep my music", it.getText(0))
                    }
                    connection.prepare("SELECT songId FROM playlist_song_map WHERE playlistId = 'playlist'").use {
                        assertTrue(it.step())
                        assertEquals("song", it.getText(0))
                    }
                    connection.prepare("SELECT playTime FROM event WHERE id = 1").use {
                        assertTrue(it.step())
                        assertEquals(30_000L, it.getLong(0))
                    }
                }
            } finally {
                directory.deleteRecursively()
            }
        }
}
