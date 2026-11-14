package com.metrolist.shared.db

import com.metrolist.shared.api.lyrics.LyricsEditor
import com.metrolist.shared.api.lyrics.LyricsFetcher
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.db.entities.LyricsEntity
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsProviderResult
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.repository.LyricsEditorAutosave
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.repository.USER_LYRICS_PROVIDER
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UserLyricsPersistenceTest {
    @Test
    fun firstChangeForksAfterDebounceAndRevertKeepsProviderCache() =
        runTest {
            val path = Files.createTempFile("user-lyrics", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("No translation expected") })
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
                        return LyricsWithProvider("[00:01.00] Refetched", "LrcLib")
                    }
                }
            try {
                val source = LyricsEntity("song", "[00:01.00] Original", "LrcLib", offsetMs = 250, translatedLyrics = "Saved translation")
                database.dao().saveLyrics(source)
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                val editor = LyricsEditor(source.lyrics)
                var changes = 0
                val autosave = LyricsEditorAutosave("song", repository, this) { changes++ }
                assertNull(repository.getUserLyrics("song"))
                editor.edit(editor.current.text.replace("Original", "My lyrics"), 0)
                autosave.changed(editor.current)
                runCurrent()
                advanceTimeBy(499)
                assertNull(repository.getUserLyrics("song"))
                advanceTimeBy(1)
                runCurrent()
                assertTrue(autosave.flush())
                val json = editor.current.json
                assertEquals(json, repository.getUserLyrics("song"))
                assertEquals(source, database.dao().cachedLyrics("song")?.copy(userLyrics = null))
                val result = repository.getLyrics("song", "Song", "Artist", null, preferredProviders = emptyList(), skipCache = true)
                assertEquals(USER_LYRICS_PROVIDER, result?.provider)
                assertEquals(json, result?.lyrics)
                assertEquals(0, fetches)
                val other = LyricsProviderResult(LyricsProvider.LRCLIB, "LrcLib", "Other", LyricsTimingKind.UNSYNCED)
                assertEquals(other, repository.selectProviderResult("song", other))
                assertNull(repository.getUserLyrics("song"))
                assertEquals("Other", repository.getCachedLyrics("song", listOf(LyricsProvider.LRCLIB))?.lyrics)
                val stashed =
                    repository
                        .getProviderResults("song", "Song", "Artist", null, preferredProviders = listOf(LyricsProvider.USER))
                        .single()
                assertEquals(json, stashed.lyrics)
                val restored = repository.selectProviderResult("song", stashed)
                assertEquals(USER_LYRICS_PROVIDER, restored.providerName)
                assertEquals(json, restored.lyrics)
                assertNull(database.dao().cachedLyrics("song")?.stashedUserLyrics)
                val expected = database.dao().cachedLyrics("song")
                database.dao().cacheLyricsIfUnchanged(source.copy(lyrics = "[00:01.00] Updated provider"), expected)
                assertEquals(json, repository.getUserLyrics("song"))
                assertEquals(250, repository.getLyricsOffset("song"))
                editor.shift(500)
                autosave.changed(editor.current)
                assertTrue(autosave.revert())
                advanceTimeBy(1000)
                runCurrent()
                assertNull(repository.getUserLyrics("song"))
                assertNull(repository.getUserLyricsDraft("song"))
                assertEquals("[00:01.00] Updated provider", repository.getCachedLyrics("song", listOf(LyricsProvider.LRCLIB))?.lyrics)
                assertFalse(autosave.failed.value)
                assertEquals(2, changes)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun invalidDraftAndEmptyForkSurviveReopening() =
        runTest {
            val path = Files.createTempFile("user-lyrics-draft", ".db")
            Files.delete(path)
            var database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("No translation expected") })
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
                    ): LyricsWithProvider? = error("User fork must win")
                }
            try {
                var repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                val editor = LyricsEditor("[00:01.00] Original")
                editor.edit("[bad] Original", 0)
                val autosave = LyricsEditorAutosave("song", repository, this) {}
                autosave.changed(editor.current)
                assertTrue(autosave.flush())
                database.close()
                database = getDesktopDatabaseBuilder(path.toFile()).build()
                repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                assertEquals("[bad] Original", repository.getUserLyricsDraft("song"))
                assertNotNull(repository.getUserLyrics("song"))
                val recovered = LyricsEditor(repository.getUserLyrics("song")!!)
                recovered.edit(repository.getUserLyricsDraft("song")!!, 0)
                assertEquals(listOf(0), recovered.current.errors)
                recovered.deleteLines()
                repository.saveCustomLyrics("song", recovered.current.json)
                assertEquals(USER_LYRICS_PROVIDER, repository.getCachedLyrics("song", emptyList())?.provider)
                assertEquals(
                    USER_LYRICS_PROVIDER,
                    repository.getVideoSpecificLyrics("song", "Song", "Artist", null, skipCache = true)?.provider,
                )
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }
}
