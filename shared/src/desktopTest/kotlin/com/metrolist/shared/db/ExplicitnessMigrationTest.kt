package com.metrolist.shared.db

import androidx.sqlite.SQLiteConnection
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ExplicitnessMigrationTest {
    @Test
    fun migrationAddsNullableExplicitnessToPersistedSongs() =
        runBlocking<Unit> {
            val path = Files.createTempFile("metrolist-explicitness-migration", ".db")
            try {
                val connection = JdbcSQLiteDriver().open(path.toString())
                connection.prepare("CREATE TABLE song (id TEXT NOT NULL PRIMARY KEY)").use { assertFalse(it.step()) }
                connection.prepare("CREATE TABLE speed_dial (id TEXT NOT NULL PRIMARY KEY)").use { assertFalse(it.step()) }

                MIGRATION_48_49.migrate(connection)

                assertEquals(listOf("id", "explicitness"), columns(connection, "song"))
                assertEquals(listOf("id", "explicitness"), columns(connection, "speed_dial"))
                connection.close()
            } finally {
                Files.deleteIfExists(path)
            }
        }

    private fun columns(
        connection: SQLiteConnection,
        table: String,
    ): List<String> =
        buildList {
            connection.prepare("PRAGMA table_info($table)").use { statement ->
                while (statement.step()) add(statement.getText(1))
            }
        }
}
