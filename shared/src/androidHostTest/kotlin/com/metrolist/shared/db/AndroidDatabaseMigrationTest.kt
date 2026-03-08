package com.metrolist.shared.db

import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [24, 29, 34])
class AndroidDatabaseMigrationTest {
    @Test
    fun everyExportedSchemaOpensWithoutLosingLibraryData() =
        runBlocking<Unit> {
            val context = RuntimeEnvironment.getApplication()
            val file = context.getDatabasePath("song.db")
            val driver = AndroidSQLiteDriver()
            for (version in DATABASE_VERSION downTo 36) {
                context.deleteDatabase("song.db")
                file.parentFile?.mkdirs()
                createHistoricalLibrary(file.absolutePath, version, driver)
                val database = getDatabaseBuilder(context).build()
                try {
                    assertEquals(
                        "Keep my music",
                        database
                            .dao()
                            .getSongById("song")
                            ?.song
                            ?.title,
                        "version $version",
                    )
                    assertEquals(
                        listOf("song"),
                        database
                            .dao()
                            .playlistSongs("playlist")
                            .first()
                            .map { it.song.id },
                    )
                    assertEquals(1, database.dao().eventCount().first())
                    assertEquals(425, database.dao().cachedLyrics("song")?.offsetMs)
                    assertEquals(listOf(historicalSpeedDialItem(version)), database.speedDialDao().getAllOnce())
                } finally {
                    database.close()
                }
                driver.open(file.path).use { connection ->
                    connection.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step(), "version $version") }
                    connection.prepare("PRAGMA user_version").use {
                        check(it.step())
                        assertEquals(DATABASE_VERSION.toLong(), it.getLong(0))
                    }
                }
            }
            context.deleteDatabase("song.db")
        }

    @Test
    fun originalMetrolistTablesKeepRelationsDuringUpgrade() =
        runBlocking<Unit> {
            val context = RuntimeEnvironment.getApplication()
            val file = context.getDatabasePath("song.db")
            context.deleteDatabase("song.db")
            file.parentFile?.mkdirs()
            val driver = AndroidSQLiteDriver()
            createHistoricalLibrary(file.absolutePath, 38, driver)
            driver.open(file.path).use { connection ->
                connection.execSQL("ALTER TABLE speed_dial RENAME TO speed_dial_item")
                connection.execSQL("ALTER TABLE artist ADD COLUMN cachedPageJson TEXT")
                connection.execSQL(
                    "INSERT INTO artist (id, name, lastUpdateTime, cachedPageJson) VALUES ('artist', 'Saved artist', 0, '{}')",
                )
                connection.execSQL("INSERT INTO song_artist_map VALUES ('song', 'artist', 0)")
            }
            val database = getDatabaseBuilder(context).build()
            try {
                assertEquals(
                    listOf("Saved artist"),
                    database
                        .dao()
                        .getSongById("song")
                        ?.artists
                        ?.map { it.name },
                )
                assertEquals(
                    listOf("song"),
                    database
                        .dao()
                        .playlistSongs("playlist")
                        .first()
                        .map { it.song.id },
                )
                assertEquals(1, database.dao().eventCount().first())
                assertEquals(listOf(historicalSpeedDialItem(38)), database.speedDialDao().getAllOnce())
            } finally {
                database.close()
                context.deleteDatabase("song.db")
            }
        }
}
