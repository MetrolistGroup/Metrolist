package com.metrolist.shared.db

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistMigrationTest {
    @Test
    fun coverMigrationKeepsExistingPlaylistArtworkAndAllowsCustomCovers() =
        runBlocking<Unit> {
            val connection = JdbcSQLiteDriver().open(":memory:")
            try {
                connection.prepare("CREATE TABLE playlist (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, thumbnailUrl TEXT)").use {
                    assertFalse(it.step())
                }
                connection.prepare("INSERT INTO playlist VALUES ('saved', 'Saved', 'original-artwork')").use { assertFalse(it.step()) }

                MIGRATION_53_54.migrate(connection)

                connection.prepare("SELECT name, thumbnailUrl, customThumbnailUrl FROM playlist WHERE id = 'saved'").use {
                    assertTrue(it.step())
                    assertEquals("Saved", it.getText(0))
                    assertEquals("original-artwork", it.getText(1))
                    assertTrue(it.isNull(2))
                }
                connection.prepare("UPDATE playlist SET customThumbnailUrl = 'custom-artwork' WHERE id = 'saved'").use {
                    assertFalse(it.step())
                }
                connection.prepare("SELECT thumbnailUrl, customThumbnailUrl FROM playlist WHERE id = 'saved'").use {
                    assertTrue(it.step())
                    assertEquals("original-artwork", it.getText(0))
                    assertEquals("custom-artwork", it.getText(1))
                }
            } finally {
                connection.close()
            }
        }

    @Test
    fun migrationRemovesOnlyCorruptedLocalPlaylistPlaceholders() =
        runBlocking<Unit> {
            val path = Files.createTempFile("metrolist-playlist-migration", ".db")
            try {
                val connection = JdbcSQLiteDriver().open(path.toString())
                connection
                    .prepare(
                        """
                        CREATE TABLE playlist (
                            id TEXT NOT NULL PRIMARY KEY,
                            name TEXT NOT NULL,
                            browseId TEXT,
                            isEditable INTEGER NOT NULL,
                            isLocal INTEGER NOT NULL,
                            remoteSongCount INTEGER,
                            thumbnailUrl TEXT
                        )
                        """.trimIndent(),
                    ).use { assertFalse(it.step()) }
                connection
                    .prepare(
                        """
                        CREATE TABLE playlist_song_map (
                            id INTEGER PRIMARY KEY,
                            playlistId TEXT NOT NULL
                        )
                        """.trimIndent(),
                    ).use { assertFalse(it.step()) }
                connection
                    .prepare(
                        """
                        INSERT INTO playlist(id, name, browseId, isEditable, isLocal, remoteSongCount, thumbnailUrl)
                        VALUES
                            ('LPdead0001', 'Playlist', NULL, 0, 0, 0, NULL),
                            ('LPvalid001', 'Playlist', NULL, 1, 0, NULL, NULL),
                            ('LPsongs001', 'Playlist', NULL, 0, 0, 0, NULL),
                            ('VLremote01', 'Playlist', NULL, 0, 0, 0, NULL)
                        """.trimIndent(),
                    ).use { assertFalse(it.step()) }
                connection
                    .prepare(
                        "INSERT INTO playlist_song_map(id, playlistId) VALUES (1, 'LPsongs001')",
                    ).use { assertFalse(it.step()) }

                MIGRATION_45_46.migrate(connection)

                val remainingIds = mutableListOf<String>()
                connection.prepare("SELECT id FROM playlist ORDER BY id").use { statement ->
                    while (statement.step()) remainingIds += statement.getText(0)
                }
                assertEquals(listOf("LPsongs001", "LPvalid001", "VLremote01"), remainingIds)
                connection.close()
            } finally {
                Files.deleteIfExists(path)
            }
        }
}
