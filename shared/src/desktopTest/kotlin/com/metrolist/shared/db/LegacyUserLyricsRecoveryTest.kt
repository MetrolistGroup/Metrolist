package com.metrolist.shared.db

import androidx.sqlite.execSQL
import com.metrolist.shared.api.lyrics.LyricsFetcher
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.backup.copyLegacyBackupTables
import com.metrolist.shared.db.entities.LyricsEntity
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.repository.USER_LYRICS_PROVIDER
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyUserLyricsRecoveryTest {
    @Test
    fun v13ManualEditsAndSyncSurviveMigrationRefetchAndRepeatedStarts() =
        runBlocking {
            val directory = Files.createTempDirectory("legacy-user-lyrics").toFile()
            val file = directory.resolve("library.db")
            val client = HttpClient(MockEngine { error("No translation expected") })
            Settings.replaceSharedForTest(Settings(MapSettings()))
            var fetches = 0
            val fetcher =
                object : LyricsFetcher {
                    override suspend fun getLyrics(
                        title: String,
                        artist: String,
                        duration: Long?,
                        album: String?,
                        videoId: String?,
                        provider: LyricsProvider,
                        isVideoContext: Boolean,
                    ): LyricsWithProvider {
                        fetches++
                        return LyricsWithProvider("[00:01.00] Fetched replacement", "LrcLib")
                    }
                }
            try {
                createHistoricalLibrary(file.path, 38, JdbcSQLiteDriver())
                JdbcSQLiteDriver().open(file.path).use { connection ->
                    connection.execSQL(
                        "INSERT INTO lyrics (id, lyrics, provider, translatedLyrics) " +
                            "VALUES ('edited', 'My edited text', 'Manual', 'Saved translation'), " +
                            "('synced', '[00:01.25] My timing', 'Manual', '')",
                    )
                    connection.execSQL(
                        "INSERT INTO song (id, title, duration, liked, totalPlayTime, lyricsOffset) " +
                            "VALUES ('synced', 'Synthetic song', 180, 0, 0, -350)",
                    )
                }
                repeat(3) {
                    val database = getDesktopDatabaseBuilder(file).build()
                    try {
                        val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                        for (preferWords in listOf(false, true)) {
                            Settings.shared.preferWordSyncedLyrics = preferWords
                            for ((id, text) in mapOf("edited" to "My edited text", "synced" to "[00:01.25] My timing")) {
                                assertEquals(text, repository.getUserLyrics(id))
                                assertEquals(USER_LYRICS_PROVIDER, repository.getCachedLyrics(id, emptyList())?.provider)
                                val result =
                                    repository.getLyrics(
                                        id,
                                        "Synthetic song",
                                        "Synthetic artist",
                                        null,
                                        preferredProviders = listOf(LyricsProvider.LRCLIB),
                                        skipCache = true,
                                    )
                                assertEquals(text, result?.lyrics)
                                assertEquals(USER_LYRICS_PROVIDER, result?.provider)
                                assertEquals(
                                    text,
                                    repository
                                        .getVideoSpecificLyrics(
                                            id,
                                            "Synthetic song",
                                            "Synthetic artist",
                                            null,
                                            skipCache = true,
                                        )?.lyrics,
                                )
                            }
                        }
                        assertEquals(0, fetches)
                        assertEquals(-350, repository.getLyricsOffset("synced"))
                        assertEquals("Saved translation", database.dao().cachedLyrics("edited")?.translatedLyrics)
                        assertNull(repository.getUserLyrics("song"))
                        assertEquals("SimpMusic", database.dao().cachedLyrics("song")?.provider)
                        assertEquals("[00:01]Saved source", database.dao().cachedLyrics("song")?.lyrics)
                        assertEquals(425, repository.getLyricsOffset("song"))
                    } finally {
                        database.close()
                    }
                }
                val database = getDesktopDatabaseBuilder(file).build()
                try {
                    val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                    repository.getLyrics(
                        "song",
                        "Synthetic song",
                        "Synthetic artist",
                        null,
                        preferredProviders = listOf(LyricsProvider.LRCLIB),
                        skipCache = true,
                    )
                    assertTrue(fetches > 0)
                    assertNull(repository.getUserLyrics("song"))
                    assertEquals("[00:01.00] Fetched replacement", database.dao().cachedLyrics("song")?.lyrics)
                } finally {
                    database.close()
                }
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
                directory.deleteRecursively()
            }
        }

    @Test
    fun schema65RepairKeepsNewerEditsDraftsAndStashesAndDoesNotUndoRevert() =
        runBlocking {
            val directory = Files.createTempDirectory("upgraded-user-lyrics").toFile()
            val file = directory.resolve("library.db")
            try {
                val initial = getDesktopDatabaseBuilder(file).build()
                val existing = LyricsEntity("existing", "Old edit", "Manual", userLyrics = "New edit", userLyricsDraft = "Draft")
                val stashed =
                    LyricsEntity("stashed", "Old edit", "Manual", stashedUserLyrics = "Saved edit", stashedUserLyricsDraft = "Saved draft")
                try {
                    initial.dao().upsert(LyricsEntity("manual", "My edit", "Manual", offsetMs = 275))
                    initial.dao().upsert(LyricsEntity("local", "Local edit", "Local"))
                    initial.dao().upsert(existing)
                    initial.dao().upsert(stashed)
                } finally {
                    initial.close()
                }
                repeat(2) { start ->
                    val database = getDesktopDatabaseBuilder(file).build()
                    try {
                        assertEquals(existing, database.dao().cachedLyrics("existing"))
                        assertEquals(stashed, database.dao().cachedLyrics("stashed"))
                        assertEquals("Local edit", database.dao().cachedLyrics("local")?.userLyrics)
                        val manual = requireNotNull(database.dao().cachedLyrics("manual"))
                        assertEquals("My edit".takeIf { start == 0 }, manual.userLyrics)
                        assertEquals(275, manual.offsetMs)
                        assertEquals("", manual.lyrics)
                        assertEquals("Unknown", manual.provider)
                        database.dao().saveUserLyrics("manual", null, null)
                    } finally {
                        database.close()
                    }
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun backupConversionRecoversManualLyricsWithoutReopeningRoom() =
        runBlocking {
            val directory = Files.createTempDirectory("legacy-user-lyrics-backup").toFile()
            try {
                val legacy = directory.resolve("legacy.db")
                val current = directory.resolve("current.db")
                createHistoricalLibrary(legacy.path, 38, JdbcSQLiteDriver())
                JdbcSQLiteDriver().open(legacy.path).use { connection ->
                    connection.execSQL("INSERT INTO lyrics (id, lyrics, provider) VALUES ('manual', '[00:01.25] My timing', 'Manual')")
                }
                val database = getDesktopDatabaseBuilder(current).build()
                try {
                    database.dao().cachedLyrics("manual")
                } finally {
                    database.close()
                }
                JdbcSQLiteDriver().open(current.path).use { connection ->
                    copyLegacyBackupTables(connection, legacy.path, 38)
                    connection.prepare("SELECT userLyrics, lyrics FROM lyrics WHERE id = 'manual'").use {
                        assertTrue(it.step())
                        assertEquals("[00:01.25] My timing", it.getText(0))
                        assertEquals("", it.getText(1))
                    }
                    connection.prepare("SELECT userLyrics, provider, isLegacyProtected FROM lyrics WHERE id = 'song'").use {
                        assertTrue(it.step())
                        assertTrue(it.isNull(0))
                        assertEquals("SimpMusic", it.getText(1))
                        assertEquals(1L, it.getLong(2))
                    }
                }
            } finally {
                directory.deleteRecursively()
            }
        }
}
