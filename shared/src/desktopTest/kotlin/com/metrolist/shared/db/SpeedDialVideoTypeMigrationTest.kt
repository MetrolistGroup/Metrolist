package com.metrolist.shared.db

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SpeedDialVideoTypeMigrationTest {
    @Test
    fun migrationFrom46AddsVideoTypeOnce() =
        runBlocking<Unit> {
            for (alreadyPresent in listOf(false, true)) {
                val path = Files.createTempFile("metrolist-speed-dial-migration", ".db")
                try {
                    val connection = JdbcSQLiteDriver().open(path.toString())
                    val extra = if (alreadyPresent) ", musicVideoType TEXT" else ""
                    connection.prepare("CREATE TABLE speed_dial (id TEXT NOT NULL PRIMARY KEY$extra)").use { assertFalse(it.step()) }

                    MIGRATION_46_47.migrate(connection)

                    val columns = mutableListOf<String>()
                    connection.prepare("PRAGMA table_info(speed_dial)").use { statement ->
                        while (statement.step()) columns += statement.getText(1)
                    }
                    assertEquals(1, columns.count { it == "musicVideoType" })
                    connection.close()
                } finally {
                    Files.deleteIfExists(path)
                }
            }
        }
}
