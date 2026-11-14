package com.metrolist.shared.state

import androidx.sqlite.execSQL
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.db.createHistoricalLibrary
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseLikesUpgradeTest {
    @Test
    fun version38UpgradeRecoversEmptyAndPartialCatalogsWithoutChangingRoomLikes() =
        runBlocking {
            val directory = createTempDirectory("likes-upgrade").toFile()
            val previousSettings = Settings.sharedOverrideForTest()
            Settings.replaceSharedForTest(Settings(MapSettings()))
            SongLikeState.awaitPersistence()
            try {
                for (partial in listOf(false, true)) {
                    val file = File(directory, "library-$partial.db")
                    createHistoricalLibrary(file.path, 38, JdbcSQLiteDriver())
                    JdbcSQLiteDriver().open(file.path).use { connection ->
                        connection.execSQL("UPDATE song SET likedDate = 1000")
                        connection.execSQL(
                            "INSERT INTO song (id,title,duration,liked,likedDate,totalPlayTime) VALUES ('new','New',10,1,2000,0)",
                        )
                    }
                    val database = getDesktopDatabaseBuilder(file).build()
                    try {
                        val settings = Settings(MapSettings())
                        Settings.replaceSharedForTest(settings)
                        val rows = database.dao().likedSongsByCreateDateAsc().first()
                        if (partial) settings.likedSongsJson = Json.encodeToString(listOf(rows.first().toSongItem()))
                        SongLikeState.refreshFromSettings()
                        SongLikeState.recoverDatabaseLikes(database)
                        assertEquals(if (partial) listOf("song", "new") else listOf("new", "song"), SongLikeState.likedSongs.map { it.id })
                        assertEquals(2, database.dao().likedSongsCount().first())
                        assertEquals(
                            rows.map { it.song.likedDate },
                            database
                                .dao()
                                .likedSongsByCreateDateAsc()
                                .first()
                                .map { it.song.likedDate },
                        )
                        SongLikeState.removeLike("song")
                        SongLikeState.awaitPersistence()
                        assertFalse(SongLikeState.isLiked("song"))
                        SongLikeState.recoverDatabaseLikes(database)
                        assertFalse(SongLikeState.isLiked("song"))
                        // Retry an interrupted migration against stale Room rows after a process restart.
                        settings.databaseLikesRecoveryVersion = 0
                        SongLikeState.refreshFromSettings()
                        SongLikeState.recoverDatabaseLikes(database)
                        assertFalse(SongLikeState.isLiked("song"))
                        assertTrue(SongLikeState.isLiked("new"))
                    } finally {
                        database.close()
                    }
                }
            } finally {
                SongLikeState.awaitPersistence()
                Settings.replaceSharedForTest(previousSettings)
                if (previousSettings != null) SongLikeState.refreshFromSettings()
                directory.deleteRecursively()
            }
        }
}
