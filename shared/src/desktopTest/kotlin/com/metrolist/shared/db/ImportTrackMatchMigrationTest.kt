package com.metrolist.shared.db

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ImportTrackMatchMigrationTest {
    @Test
    fun migrationCreatesCascadeScopedCache() =
        runBlocking<Unit> {
            val connection = JdbcSQLiteDriver().open(":memory:")
            try {
                connection.prepare("PRAGMA foreign_keys = ON").use { assertFalse(it.step()) }
                connection.prepare("CREATE TABLE playlist (id TEXT NOT NULL PRIMARY KEY)").use { assertFalse(it.step()) }
                connection.prepare("CREATE TABLE song (id TEXT NOT NULL PRIMARY KEY)").use { assertFalse(it.step()) }
                MIGRATION_49_50.migrate(connection)
                connection.prepare("INSERT INTO playlist (id) VALUES ('p')").use { assertFalse(it.step()) }
                connection.prepare("INSERT INTO song (id) VALUES ('s')").use { assertFalse(it.step()) }
                connection.prepare("INSERT INTO import_track_match VALUES ('p', 'SPOTIFY', 'key', 's')").use { assertFalse(it.step()) }
                connection.prepare("DELETE FROM playlist WHERE id = 'p'").use { assertFalse(it.step()) }
                connection.prepare("SELECT COUNT(*) FROM import_track_match").use { statement ->
                    assertEquals(true, statement.step())
                    assertEquals(0, statement.getLong(0))
                }
            } finally {
                connection.close()
            }
        }
}
