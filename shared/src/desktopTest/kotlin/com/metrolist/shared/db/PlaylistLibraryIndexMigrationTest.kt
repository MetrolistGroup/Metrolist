package com.metrolist.shared.db

import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistLibraryIndexMigrationTest {
    @Test
    fun migrationIndexesGappedPositionsAndLibraryDatesWithoutLosingDependentRows() =
        runBlocking {
            val directory = Files.createTempDirectory("playlist-library-indexes")
            val path = directory.resolve("music.db")
            try {
                createHistoricalLibrary(path.toString(), 62, JdbcSQLiteDriver())
                JdbcSQLiteDriver().open(path.toString()).use { connection ->
                    connection.execSQL("UPDATE song SET inLibrary = 2000 WHERE id = 'song'")
                    connection.execSQL(
                        "INSERT INTO song (id, title, duration, liked, totalPlayTime, inLibrary) VALUES ('earlier', 'Earlier', 1, 0, 0, 1000)",
                    )
                    connection.execSQL("UPDATE playlist_song_map SET position = 4 WHERE playlistId = 'playlist'")
                    connection.execSQL("INSERT INTO playlist_song_map (playlistId, songId, position) VALUES ('playlist', 'song', 9)")
                }
                val database = getDesktopDatabaseBuilder(path.toFile()).build()
                try {
                    assertEquals(10, database.dao().nextPlaylistPosition("playlist"))
                    assertEquals(listOf(4, 9), database.dao().playlistSongMaps("playlist", 0).map { it.position })
                    assertEquals(
                        listOf("earlier", "song"),
                        database
                            .dao()
                            .songsByCreateDateAsc()
                            .first()
                            .map { it.id },
                    )
                    assertEquals(1, database.dao().eventCount().first())
                    assertEquals("[00:01]Saved source", database.dao().cachedLyrics("song")?.lyrics)
                } finally {
                    database.close()
                }
                JdbcSQLiteDriver().open(path.toString()).use { connection ->
                    fun plan(sql: String): String =
                        connection.prepare("EXPLAIN QUERY PLAN $sql").use { statement ->
                            buildList { while (statement.step()) add(statement.getText(3)) }.joinToString("\n")
                        }
                    val append = plan("SELECT COALESCE(MAX(position) + 1, 0) FROM playlist_song_map WHERE playlistId = 'playlist'")
                    assertTrue(append.contains("USING COVERING INDEX index_playlist_song_map_playlistId_position"), append)
                    val library = plan("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY inLibrary")
                    assertTrue(library.contains("USING INDEX index_song_inLibrary"), library)
                    assertFalse(library.contains("TEMP B-TREE"), library)
                    connection.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) }
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
}
