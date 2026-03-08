package com.metrolist.shared.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.scrobble.PendingScrobble
import com.metrolist.shared.scrobble.ScrobbleProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LegacyBackupConversionTest {
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
