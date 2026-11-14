package com.metrolist.shared.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.db.createHistoricalLibrary
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.scrobble.PendingScrobble
import com.metrolist.shared.scrobble.ScrobbleProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class LegacyBackupConversionTest {
    @Test
    fun conversionRemovesDanglingChildrenAndKeepsLibraryData() =
        runBlocking {
            val directory = Files.createTempDirectory("orphan-backup-conversion").toFile()
            try {
                for (version in listOf(38, 51)) {
                    val legacy = directory.resolve("legacy-$version.db")
                    val current = directory.resolve("current-$version.db")
                    createHistoricalLibrary(legacy.path, version, JdbcSQLiteDriver())
                    val database = getDatabaseBuilder(current).build()
                    try {
                        database.dao().eventCount().first()
                    } finally {
                        database.close()
                    }
                    JdbcSQLiteDriver().open(legacy.path).use { connection ->
                        connection.execSQL("PRAGMA foreign_keys = OFF")
                        listOf(
                            "INSERT INTO artist (id, name, lastUpdateTime) VALUES ('artist', 'Saved artist', 0)",
                            "INSERT INTO album (id, title, songCount, duration, lastUpdateTime) VALUES ('album', 'Saved album', 1, 240, 0)",
                            "INSERT INTO song_artist_map VALUES ('song', 'artist', 0), ('missing', 'artist', 0), ('song', 'missing', 1)",
                            "INSERT INTO song_album_map VALUES ('song', 'album', 0), ('missing', 'album', 0), ('song', 'missing', 1)",
                            "INSERT INTO album_artist_map VALUES ('album', 'artist', 0), ('missing', 'artist', 0), ('album', 'missing', 1)",
                            "INSERT INTO related_song_map (songId, relatedSongId) VALUES ('song', 'song'), ('missing', 'song'), ('song', 'missing'), ('missing', 'missing')",
                            "INSERT INTO playlist_song_map (playlistId, songId, position) VALUES ('playlist', 'missing', 1), ('missing', 'song', 0)",
                            "INSERT INTO event (id, songId, timestamp, playTime) VALUES (2, 'missing', 2000, 30000)",
                            "INSERT INTO search_history (query) VALUES ('Saved search')",
                            "INSERT INTO format (id, itag, mimeType, codecs, bitrate, contentLength) VALUES ('song', 1, 'audio/mp4', 'mp4a', 128000, 1000), ('uncatalogued', 1, 'audio/mp4', 'mp4a', 128000, 1000)",
                            "INSERT INTO playCount (song, year, month, count) VALUES ('uncatalogued', 2026, 1, 3)",
                            "INSERT INTO set_video_id (videoId, setVideoId) VALUES ('uncatalogued', 'saved-set-id')",
                        ).forEach(connection::execSQL)
                        if (version >= 51) {
                            listOf(
                                "INSERT INTO import_track_match VALUES ('playlist', 'provider', 'valid', 'song'), ('missing', 'provider', 'playlist', 'song'), ('playlist', 'provider', 'song', 'missing')",
                                "INSERT INTO history_snapshot (eventId, title, artists) VALUES (1, 'Saved title', 'Saved artist'), (2, 'Dangling song', ''), (3, 'Dangling event', '')",
                                "INSERT INTO history_provenance (source, legacyId, songId, playedAt, eventId) VALUES ('LOCAL', 1, 'song', 1000, 1), ('LOCAL', 2, 'missing', 2000, 2), ('LOCAL', 3, 'song', 3000, 3), ('LOCAL', 4, 'song', 4000, NULL)",
                            ).forEach(connection::execSQL)
                        }
                        validateBackupDatabaseIntegrity(connection)
                        assertFails { validateBackupDatabase(connection) }
                    }
                    JdbcSQLiteDriver().open(current.path).use { connection ->
                        copyLegacyBackupTables(connection, legacy.path, version)
                        validateBackupDatabase(connection)
                        for (table in listOf(
                            "song",
                            "artist",
                            "album",
                            "playlist",
                            "song_artist_map",
                            "song_album_map",
                            "album_artist_map",
                            "related_song_map",
                            "playlist_song_map",
                            "event",
                            "search_history",
                        )) {
                            assertEquals(listOf("1"), connection.texts("SELECT COUNT(*) FROM $table"), "$table for version $version")
                        }
                        assertEquals(listOf("song"), connection.texts("SELECT id FROM song WHERE liked = 1 AND totalPlayTime = 30000"))
                        assertEquals(
                            listOf("song"),
                            connection.texts("SELECT songId FROM playlist_song_map WHERE playlistId = 'playlist' AND position = 0"),
                        )
                        assertEquals(listOf("1"), connection.texts("SELECT id FROM event WHERE songId = 'song' AND playTime = 30000"))
                        assertEquals(listOf("local", "song"), connection.texts("SELECT id FROM lyrics ORDER BY id"))
                        assertEquals(listOf("425"), connection.texts("SELECT offsetMs FROM lyrics WHERE id = 'song'"))
                        assertEquals(listOf("song", "uncatalogued"), connection.texts("SELECT id FROM format ORDER BY id"))
                        assertEquals(listOf("3"), connection.texts("SELECT count FROM playCount WHERE song = 'uncatalogued'"))
                        assertEquals(
                            listOf("saved-set-id"),
                            connection.texts("SELECT setVideoId FROM set_video_id WHERE videoId = 'uncatalogued'"),
                        )
                        if (version >= 51) {
                            assertEquals(listOf("valid"), connection.texts("SELECT trackKey FROM import_track_match"))
                            assertEquals(listOf("Saved title"), connection.texts("SELECT title FROM history_snapshot"))
                            assertEquals(listOf("1"), connection.texts("SELECT eventId FROM history_provenance WHERE eventId IS NOT NULL"))
                            assertEquals(
                                listOf("2", "3", "4"),
                                connection.texts("SELECT legacyId FROM history_provenance WHERE eventId IS NULL ORDER BY legacyId"),
                            )
                        }
                        connection.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) }
                    }
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun conversionPreservesPendingScrobblesOnlyFromSchemasThatContainThem() =
        runBlocking {
            val directory = Files.createTempDirectory("scrobble-backup-conversion").toFile()
            try {
                for (version in listOf(57, 58, 59)) {
                    val legacy = directory.resolve("legacy-$version.db")
                    val current = directory.resolve("current-$version.db")
                    val pending = PendingScrobble("play", ScrobbleProvider.LAST_FM, "account", "Track", "Artist", timestamp = 123)
                    for (file in listOf(legacy, current)) {
                        val database = getDatabaseBuilder(file).build()
                        try {
                            database.pendingScrobbleDao().upsert(listOf(pending.copy(id = if (file == legacy) "play" else "replaced")))
                        } finally {
                            database.close()
                        }
                    }
                    JdbcSQLiteDriver().open(legacy.path).use { connection ->
                        if (version < 58) connection.execSQL("DROP TABLE pending_scrobble")
                        connection.execSQL("PRAGMA user_version = $version")
                    }
                    JdbcSQLiteDriver().open(current.path).use { connection ->
                        copyLegacyBackupTables(connection, legacy.path, version)
                    }
                    val restored = getDatabaseBuilder(current).build()
                    try {
                        assertEquals(if (version < 58) emptyList() else listOf(pending), restored.pendingScrobbleDao().all())
                    } finally {
                        restored.close()
                    }
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun failedConversionRollsBackAndDetachesTheLegacyDatabase() {
        val directory = Files.createTempDirectory("legacy-conversion").toFile()
        try {
            val current = directory.resolve("current.db")
            val legacy = directory.resolve("legacy.db")
            val database = getDatabaseBuilder(current).build()
            try {
                runBlocking { database.dao().eventCount().first() }
            } finally {
                database.close()
            }
            JdbcSQLiteDriver().open(legacy.absolutePath).use { connection ->
                listOf(
                    "CREATE TABLE song (id TEXT PRIMARY KEY)",
                    "CREATE TABLE artist (id INTEGER PRIMARY KEY, name TEXT)",
                    "CREATE TABLE playlist (playlistId INTEGER PRIMARY KEY, name TEXT)",
                    "CREATE TABLE playlist_song (id INTEGER PRIMARY KEY, playlistId INTEGER, songId TEXT, idInPlaylist INTEGER)",
                ).forEach(connection::execSQL)
            }

            JdbcSQLiteDriver().open(current.absolutePath).use { connection ->
                connection.execSQL("INSERT INTO search_history (query) VALUES ('kept')")

                assertFails { copyLegacyBackupTables(connection, legacy.absolutePath, legacyVersion = 1) }

                assertEquals(listOf("kept"), connection.texts("SELECT query FROM search_history"))
                assertEquals(listOf("main"), connection.texts("SELECT name FROM pragma_database_list"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun SQLiteConnection.texts(sql: String): List<String> =
        prepare(sql).use { statement -> buildList { while (statement.step()) add(statement.getText(0)) } }
}
