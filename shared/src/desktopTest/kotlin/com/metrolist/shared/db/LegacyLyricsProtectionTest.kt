package com.metrolist.shared.db

import androidx.sqlite.execSQL
import com.metrolist.shared.api.lyrics.LyricsFetcher
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.api.lyrics.MlxBuilder
import com.metrolist.shared.api.lyrics.MlxWord
import com.metrolist.shared.backup.copyLegacyBackupTables
import com.metrolist.shared.db.entities.LyricsEntity
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsProviderResult
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyLyricsProtectionTest {
    @Test
    fun providerEditsSurviveAutomaticFetchesAndTimingChangesUntilManualRefetch() =
        runBlocking {
            val directory = Files.createTempDirectory("legacy-lyrics-protection").toFile()
            val client = HttpClient(MockEngine { error("No translation expected") })
            val fetched = MlxBuilder().meta("Lyprio").line(1_000, 2_000, words = listOf(MlxWord("Replacement", 1_000, 2_000))).build()
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                for (version in listOf(38, 65)) {
                    val file = directory.resolve("library-$version.db")
                    createHistoricalLibrary(file.path, version, JdbcSQLiteDriver())
                    val originals = mapOf("song" to "[00:01.25] My edited timing", "plain" to "My edited plain lyrics", "modern" to fetched)
                    JdbcSQLiteDriver().open(file.path).use { connection ->
                        connection.execSQL("DELETE FROM lyrics WHERE id = 'song'")
                        for ((id, text) in originals) {
                            connection.prepare("INSERT INTO lyrics (id, lyrics, provider) VALUES (?, ?, 'SimpMusic')").use {
                                it.bindText(1, id)
                                it.bindText(2, text)
                                it.step()
                            }
                        }
                        if (version >= 45) connection.execSQL("UPDATE lyrics SET offsetMs = 425 WHERE id = 'song'")
                        connection.execSQL(
                            "INSERT INTO lyrics (id, lyrics, provider) VALUES ('miss', 'LYRICS_NOT_FOUND', 'None'), ('offset', '', 'Unknown')",
                        )
                    }
                    val fetcher = RecordingFetcher(fetched)
                    repeat(3) {
                        val database = getDesktopDatabaseBuilder(file).build()
                        try {
                            val dao = database.dao()
                            val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), dao)
                            for (preferWords in listOf(false, true)) {
                                Settings.shared.preferWordSyncedLyrics = preferWords
                                val providers =
                                    if (preferWords) {
                                        listOf(
                                            LyricsProvider.SIMPMUSIC,
                                            LyricsProvider.LYPRIO,
                                        )
                                    } else {
                                        listOf(LyricsProvider.LYPRIO)
                                    }
                                for ((id, text) in originals) {
                                    val result =
                                        repository.getLyrics(
                                            id,
                                            "Synthetic song",
                                            "Synthetic artist",
                                            null,
                                            preferredProviders = providers,
                                        )
                                    assertEquals(text, result?.lyrics)
                                    assertEquals("SimpMusic", result?.provider)
                                    assertEquals(text, repository.getCachedLyrics(id, emptyList())?.lyrics)
                                    assertEquals(
                                        text,
                                        repository.getVideoSpecificLyrics(id, "Synthetic song", "Synthetic artist", null)?.lyrics,
                                    )
                                    assertNull(repository.getUserLyrics(id))
                                    val row = requireNotNull(dao.cachedLyrics(id))
                                    assertTrue(row.isLegacyProtected)
                                    assertFalse(row.isUserSelected)
                                    val translated =
                                        requireNotNull(dao.cacheLyricsIfUnchanged(row.copy(translatedLyrics = "Saved translation"), row))
                                    assertTrue(translated.isLegacyProtected)
                                    assertEquals("Saved translation", translated.translatedLyrics)
                                    assertEquals(translated, dao.cacheLyricsIfUnchanged(LyricsEntity(id, fetched, "Lyprio"), translated))
                                    assertEquals(
                                        translated,
                                        dao.cacheLyricsIfUnchanged(LyricsEntity(id, "", LyricsEntity.LYRICS_NOT_FOUND), translated),
                                    )
                                }
                            }
                            assertEquals(0, fetcher.calls)
                            assertEquals(425, repository.getLyricsOffset("song"))
                            assertFalse(requireNotNull(dao.cachedLyrics("miss")).isLegacyProtected)
                            assertFalse(requireNotNull(dao.cachedLyrics("offset")).isLegacyProtected)
                        } finally {
                            database.close()
                        }
                    }
                    val database = getDesktopDatabaseBuilder(file).build()
                    try {
                        val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                        val result =
                            repository.getLyrics(
                                "song",
                                "Synthetic song",
                                "Synthetic artist",
                                null,
                                preferredProviders = listOf(LyricsProvider.LYPRIO),
                                skipCache = true,
                            )
                        assertEquals(fetched, result?.lyrics)
                        assertEquals(1, fetcher.calls)
                        val row = requireNotNull(database.dao().cachedLyrics("song"))
                        assertEquals(fetched, row.lyrics)
                        assertFalse(row.isLegacyProtected)
                        assertNull(row.userLyrics)
                        assertEquals(425, row.offsetMs)
                        repository.selectProviderResult(
                            "plain",
                            LyricsProviderResult(LyricsProvider.LYPRIO, "Lyprio", fetched, LyricsTimingKind.WORD_SYNCED),
                        )
                        assertFalse(requireNotNull(database.dao().cachedLyrics("plain")).isLegacyProtected)
                        database.dao().upsert(LyricsEntity("new", fetched, "Lyprio"))
                    } finally {
                        database.close()
                    }
                    val reopened = getDesktopDatabaseBuilder(file).build()
                    try {
                        val dao = reopened.dao()
                        for (id in listOf("song", "plain", "new")) assertFalse(requireNotNull(dao.cachedLyrics(id)).isLegacyProtected)
                        assertTrue(requireNotNull(dao.cachedLyrics("modern")).isLegacyProtected)
                        val row = requireNotNull(dao.cachedLyrics("song"))
                        val replacement = row.copy(lyrics = "[00:02.00] Later automatic lyrics")
                        assertEquals(replacement, dao.cacheLyricsIfUnchanged(replacement, row))
                    } finally {
                        reopened.close()
                    }
                }
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
                directory.deleteRecursively()
            }
        }

    @Test
    fun currentBackupKeepsReleasedRowsUnprotected() =
        runBlocking {
            val directory = Files.createTempDirectory("legacy-lyrics-protection-backup").toFile()
            try {
                val source = directory.resolve("source.db")
                val target = directory.resolve("target.db")
                createHistoricalLibrary(source.path, 65, JdbcSQLiteDriver())
                val sourceDatabase = getDesktopDatabaseBuilder(source).build()
                try {
                    assertTrue(requireNotNull(sourceDatabase.dao().cachedLyrics("song")).isLegacyProtected)
                    sourceDatabase.dao().saveLyrics(LyricsEntity("song", "[00:02.00] Selected lyrics", "LrcLib", isUserSelected = true))
                    sourceDatabase.dao().upsert(LyricsEntity("protected", "[00:01.00] Legacy edit", "SimpMusic", isLegacyProtected = true))
                } finally {
                    sourceDatabase.close()
                }
                val targetDatabase = getDesktopDatabaseBuilder(target).build()
                try {
                    targetDatabase.dao().cachedLyrics("song")
                } finally {
                    targetDatabase.close()
                }
                JdbcSQLiteDriver().open(target.path).use { copyLegacyBackupTables(it, source.path, DATABASE_VERSION) }
                val restored = getDesktopDatabaseBuilder(target).build()
                try {
                    assertFalse(requireNotNull(restored.dao().cachedLyrics("song")).isLegacyProtected)
                    assertTrue(requireNotNull(restored.dao().cachedLyrics("protected")).isLegacyProtected)
                } finally {
                    restored.close()
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    private class RecordingFetcher(
        private val lyrics: String,
    ) : LyricsFetcher {
        var calls = 0

        override suspend fun getLyrics(
            title: String,
            artist: String,
            duration: Long?,
            album: String?,
            videoId: String?,
            provider: LyricsProvider,
            isVideoContext: Boolean,
        ): LyricsWithProvider {
            calls++
            return LyricsWithProvider(lyrics, "Lyprio")
        }
    }
}
