package com.metrolist.shared.db

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongMetadataMigrationTest {
    @Test
    fun migrationFrom47AddsNullableCustomMetadataWithoutChangingSongs() =
        runBlocking<Unit> {
            val path = Files.createTempFile("metrolist-song-metadata-migration", ".db")
            try {
                val connection = JdbcSQLiteDriver().open(path.toString())
                connection
                    .prepare("CREATE TABLE song (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)")
                    .use { assertFalse(it.step()) }
                connection
                    .prepare("INSERT INTO song(id, title) VALUES ('song', 'Remote title')")
                    .use { assertFalse(it.step()) }

                MIGRATION_47_48.migrate(connection)

                val columns = mutableListOf<String>()
                connection.prepare("PRAGMA table_info(song)").use { statement ->
                    while (statement.step()) columns += statement.getText(1)
                }
                assertTrue("customTitle" in columns)
                assertTrue("customArtist" in columns)
                connection.prepare("SELECT id, title, customTitle, customArtist FROM song").use { statement ->
                    assertTrue(statement.step())
                    assertEquals("song", statement.getText(0))
                    assertEquals("Remote title", statement.getText(1))
                    assertTrue(statement.isNull(2))
                    assertTrue(statement.isNull(3))
                    assertFalse(statement.step())
                }
                connection.close()
            } finally {
                Files.deleteIfExists(path)
            }
        }
}
