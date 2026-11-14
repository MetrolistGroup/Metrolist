package com.metrolist.desktop.history

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyHistoryMigrationTest {
    @Test
    fun purgesOwnerlessQueueFromBothRetainedRecoveryFilesWithoutRemovingPlays() {
        val root = Files.createTempDirectory("metrolist-history-queue-").toFile()
        try {
            listOf(root.resolve("original/history.db"), root.resolve("xdg/history.db")).forEach { file ->
                file.parentFile.mkdirs()
                DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
                    db.createStatement().use { sql ->
                        sql.execute("CREATE TABLE play_history (id INTEGER PRIMARY KEY, song_id TEXT)")
                        sql.execute("INSERT INTO play_history VALUES (1, 'track')")
                        sql.execute("CREATE TABLE scrobble_queue (id INTEGER PRIMARY KEY, track TEXT)")
                        sql.execute("INSERT INTO scrobble_queue VALUES (1, 'unowned')")
                    }
                }
                purgeLegacyScrobbleQueue(file)
                purgeLegacyScrobbleQueue(file)
                assertTrue(file.isFile)
                DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
                    db.createStatement().use { sql ->
                        sql.executeQuery("SELECT COUNT(*) FROM play_history").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(1, rows.getInt(1))
                        }
                        sql.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='scrobble_queue'").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(0, rows.getInt(1))
                        }
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
