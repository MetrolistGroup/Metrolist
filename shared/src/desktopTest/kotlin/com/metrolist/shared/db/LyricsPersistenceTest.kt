package com.metrolist.shared.db

import com.metrolist.shared.api.lyrics.LrcLibLyricsProvider
import com.metrolist.shared.api.lyrics.LyricsFetcher
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.api.lyrics.MlxBuilder
import com.metrolist.shared.api.lyrics.MlxWord
import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.db.entities.LyricsEntity
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsProviderResult
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.repository.LOCAL_LYRICS_PROVIDER
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.repository.USER_LYRICS_PROVIDER
import com.metrolist.shared.repository.isLocalLyricsProvider
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class LyricsPersistenceTest {
    @Test
    fun retiredProviderLyricsStillLoadFromTheDatabase() =
        runBlocking {
            val path = Files.createTempFile("lyrics-retired-providers", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
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
                    ): LyricsWithProvider? = null
                }
            HttpClient(MockEngine { error("Unexpected HTTP request") }).use { client ->
                try {
                    val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                    val providers = listOf(LyricsProvider.LRCLIB)
                    for (provider in listOf("LyricsPlus", "LYRICSPLUS", "Zemer", "ZEMER")) {
                        val formats =
                            listOf(
                                "[00:01]Saved lyrics",
                                MlxBuilder().meta(provider).line(1_000, 2_000, "Saved lyrics").build(),
                                """{"version":2,"source":{"name":"$provider"},
                                    "lines":[{"startMs":1000,"endMs":2000,"text":"Saved lyrics"}]}""",
                            )
                        for (lyrics in formats) {
                            for (selected in listOf(false, true)) {
                                val saved = LyricsEntity("song", lyrics, provider, offsetMs = 425, isUserSelected = selected)
                                database.dao().saveLyrics(saved)
                                val expected = LyricsWithProvider(lyrics, provider, isUserSelected = selected)
                                assertEquals(expected, repository.getCachedLyrics("song", providers))
                                assertEquals(expected, repository.getLyrics("song", "Song", "Artist", null, preferredProviders = providers))
                                assertEquals(expected, repository.getVideoSpecificLyrics("song", "Song", "Artist", null))
                                val displayed = LyricsUtils.parseLyrics(lyrics).single()
                                assertEquals("Saved lyrics", displayed.text)
                                assertEquals(1_000L, displayed.time)
                                if (lyrics != formats.first()) {
                                    assertEquals(provider, LyricsUtils.parseMlxDocument(lyrics)?.meta?.lyricSource)
                                }
                                assertEquals(saved, database.dao().cachedLyrics("song"))
                                assertEquals(425, repository.getLyricsOffset("song"))
                            }
                        }
                    }
                } finally {
                    database.close()
                    Files.deleteIfExists(path)
                }
            }
        }

    @Test
    fun unsyncedPreferredCacheAllowsTimedFallbackUnlessExplicitlySelected() =
        runBlocking {
            val path = Files.createTempFile("lyrics-plain-preferred-cache", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val plain =
                """{"version":2,"syncType":"plain","lines":[
                    {"endMs":179999,"text":"First"},{"endMs":179999,"text":"Second"}]}"""
            val synced = LyricsWithProvider("[00:01]First\n[00:02]Second", "LrcLib")
            val calls = mutableListOf<LyricsProvider>()
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
                        calls += provider
                        return if (provider == LyricsProvider.LYPRIO) LyricsWithProvider(plain, "Lyprio") else synced
                    }
                }
            HttpClient(MockEngine { error("Unexpected HTTP request") }).use { client ->
                try {
                    val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                    for (selected in listOf(false, true)) {
                        calls.clear()
                        val saved = LyricsEntity("song", plain, "Lyprio", offsetMs = 425, isUserSelected = selected)
                        database.dao().saveLyrics(saved)
                        val result =
                            repository.getLyrics(
                                "song",
                                "Song",
                                "Artist",
                                null,
                                preferredProviders = listOf(LyricsProvider.LYPRIO, LyricsProvider.LRCLIB),
                            )
                        assertEquals(if (selected) plain else synced.lyrics, result?.lyrics)
                        assertEquals(if (selected) emptyList() else listOf(LyricsProvider.LYPRIO, LyricsProvider.LRCLIB), calls)
                        assertEquals(if (selected) plain else synced.lyrics, database.dao().cachedLyrics("song")?.lyrics)
                        assertEquals(425, repository.getLyricsOffset("song"))
                    }
                } finally {
                    database.close()
                    Files.deleteIfExists(path)
                }
            }
        }

    @Test
    fun cacheOnlyLookupValidatesSavedLyricsWithoutFetchingOrChangingTheCache() =
        runBlocking {
            val path = Files.createTempFile("lyrics-cache-only", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Unexpected translation request") })
            var providerCalls = 0
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
                    ): LyricsWithProvider? {
                        providerCalls++
                        error("Cache-only lookup must not request providers")
                    }
                }
            try {
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                val providers = listOf(LyricsProvider.LYPRIO, LyricsProvider.PAXSENIX)
                assertNull(repository.getCachedLyrics("missing", providers))
                val saved = LyricsEntity("song", "[00:01]Saved lyrics", "PAXSENIX")
                val cases =
                    listOf(
                        saved to LyricsWithProvider(saved.lyrics, saved.provider),
                        saved.copy(lyrics = "") to null,
                        saved.copy(provider = LyricsEntity.LYRICS_NOT_FOUND) to null,
                        saved.copy(lyrics = "[ar:Metadata without lyrics]") to null,
                        saved.copy(provider = "LRCLIB") to null,
                        saved.copy(provider = "SIMPMUSIC") to null,
                        saved.copy(provider = "LRCLIB", isUserSelected = true) to
                            LyricsWithProvider(saved.lyrics, "LRCLIB", isUserSelected = true),
                        saved.copy(provider = LOCAL_LYRICS_PROVIDER) to LyricsWithProvider(saved.lyrics, LOCAL_LYRICS_PROVIDER),
                    )
                for ((entity, expected) in cases) {
                    database.dao().saveLyrics(entity)
                    val stored = database.dao().cachedLyrics(entity.id)
                    assertEquals(expected, repository.getCachedLyrics(entity.id, providers))
                    assertEquals(stored, database.dao().cachedLyrics(entity.id))
                }
                database.dao().saveLyrics(saved.copy(provider = "SIMPMUSIC"))
                assertEquals(
                    LyricsWithProvider(saved.lyrics, "SIMPMUSIC"),
                    repository.getCachedLyrics(saved.id, providers, isVideoContext = true),
                )
                assertEquals(0, providerCalls)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun vocalTranslationCacheTracksBothSourcesAndForcedRequests() =
        runBlocking {
            val path = Files.createTempFile("vocal-translations", ".db")
            Files.delete(path)
            var database = getDesktopDatabaseBuilder(path.toFile()).build()
            Settings.replaceSharedForTest(Settings(MapSettings()))
            Settings.shared.lyricsAiTranslationEnabled = true
            Settings.shared.lyricsAiActivationMode = "Automatic"
            Settings.shared.lyricsAiProvider = "OpenRouter"
            Settings.shared.lyricsAiTargetLanguage = "fr"
            Settings.shared.lyricsAiApiKey = "test-key"
            Settings.shared.lyricsRomanizationEnabled = false
            val responses =
                ArrayDeque(
                    listOf(
                        """{"lines":["","Goodbye"]}""",
                        "{\"lines\":[\"Au revoir\"]}",
                        """{"lines":["","Good night"]}""",
                        "{\"lines\":[\"Bonne nuit\"]}",
                        "{\"lines\":[\"Salut\",\"Bonne nuit\"]}",
                        """{"lines":["",""]}""",
                        "{\"lines\":[\"Salut\",\"À bientôt\"]}",
                    ),
                )
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        val payload = responses.removeFirst()
                        respond("{\"choices\":[{\"message\":{\"content\":${JsonPrimitive(payload)}}}]}")
                    },
                )
            try {
                var repository = LyricsRepository(NoOpLyricsFetcher, LyricsTranslationClient(client), database.dao())
                val original = listOf(LyricsEntry(1000, "Bonjour", backgroundText = "Goodbye"))
                database.dao().saveLyrics(
                    LyricsEntity(
                        "song",
                        "source",
                        "Local",
                        translatedLyrics = """METROLIST_TRANSLATION_V2:{"lines":["Bonjour","Old translation"]}""",
                        translationLanguage = "fr",
                        translationMode = "Literal",
                        translationSourceHash = "Bonjour\nGoodbye".hashCode().toString(),
                        translationConfigKey =
                            repository.buildTranslationConfigKey().replaceFirst("v5|", "v4|") + "|vocals-v1|false",
                    ),
                )
                val offline = repository.enrichLyricsEntries("song", original, allowRemoteTranslation = false)
                assertEquals("Old translation", offline.single().backgroundTranslatedText)
                assertEquals(0, requests)
                val translated = repository.enrichLyricsEntries("song", original)
                assertNull(translated.single().translatedText)
                assertEquals("Au revoir", translated.single().backgroundTranslatedText)
                assertEquals(2, requests)
                database.close()
                database = getDesktopDatabaseBuilder(path.toFile()).build()
                repository = LyricsRepository(NoOpLyricsFetcher, LyricsTranslationClient(client), database.dao())
                assertEquals(translated, repository.enrichLyricsEntries("song", original, allowRemoteTranslation = false))
                assertEquals(2, requests)

                val changed = listOf(original.single().copy(backgroundText = "Good night"))
                assertEquals("Bonne nuit", repository.enrichLyricsEntries("song", changed).single().backgroundTranslatedText)
                assertEquals(4, requests)
                val forced = repository.enrichLyricsEntries("song", changed, forceAiTranslation = true)
                assertEquals("Salut", forced.single().translatedText)
                assertEquals("Bonne nuit", forced.single().backgroundTranslatedText)
                assertEquals(forced, repository.enrichLyricsEntries("song", changed, forceAiTranslation = true))
                assertEquals(5, requests)

                val unnecessary = listOf(original.single().copy(backgroundText = "Au revoir"))
                assertEquals(unnecessary, repository.enrichLyricsEntries("song", unnecessary))
                assertEquals(unnecessary, repository.enrichLyricsEntries("song", unnecessary))
                assertEquals(6, requests)
                assertEquals(
                    "À bientôt",
                    repository
                        .enrichLyricsEntries("song", unnecessary, forceAiTranslation = true)
                        .single()
                        .backgroundTranslatedText,
                )
                assertEquals(7, requests)
                assertTrue(responses.isEmpty())
            } finally {
                client.close()
                database.close()
                Settings.replaceSharedForTest(null)
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun editedTranslationsSurviveSavingReopeningAndAiEnrichment() =
        runBlocking {
            val path = Files.createTempFile("edited-translations", ".db")
            Files.delete(path)
            var database = getDesktopDatabaseBuilder(path.toFile()).build()
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond("""{"choices":[{"message":{"content":"{\"lines\":[\"Bonjour\",\"Monde\"]}"}}]}""")
                    },
                )
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                Settings.shared.lyricsAiTranslationEnabled = true
                Settings.shared.lyricsAiActivationMode = "Manual"
                Settings.shared.lyricsAiProvider = "OpenRouter"
                Settings.shared.lyricsAiTargetLanguage = "fr"
                Settings.shared.lyricsAiApiKey = "test-key"
                Settings.shared.lyricsRomanizationEnabled = false
                val raw =
                    MlxBuilder()
                        .meta("Provider")
                        .line(1000, "Hello")
                        .line(2000, "World")
                        .build()
                database.dao().saveLyrics(LyricsEntity("song", raw, "Provider", offsetMs = 250))
                val repository = LyricsRepository(NoOpLyricsFetcher, LyricsTranslationClient(client), database.dao())
                val generated = repository.enrichLyricsEntries("song", LyricsUtils.parseLyrics(raw), forceAiTranslation = true)
                assertEquals(listOf("Bonjour", "Monde"), generated.map { it.translatedText })
                assertTrue(
                    database
                        .dao()
                        .cachedLyrics("song")!!
                        .translatedLyrics
                        .isNotBlank(),
                )
                val edited = LyricsUtils.lyricsForEditing(raw, generated).replace("Bonjour", "Salut")
                repository.saveCustomLyrics("song", edited)
                database.close()
                database = getDesktopDatabaseBuilder(path.toFile()).build()
                val reopened = LyricsRepository(NoOpLyricsFetcher, LyricsTranslationClient(client), database.dao())
                val saved = database.dao().cachedLyrics("song")!!
                assertEquals(edited, saved.userLyrics)
                assertEquals(raw, saved.lyrics)
                assertEquals(250, saved.offsetMs)
                assertEquals("Provider", saved.provider)
                assertTrue(saved.translatedLyrics.isNotBlank())
                val parsed = LyricsUtils.parseLyrics(saved.userLyrics!!)
                assertEquals(listOf("Salut", "Monde"), parsed.map { it.translatedText })
                assertEquals(parsed, reopened.enrichLyricsEntries("song", parsed, forceAiTranslation = true))
                assertEquals(1, requests)
                assertEquals(parsed, reopened.enrichLyricsEntries("song", parsed))
                assertEquals(1, requests)
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun cachedKugouLyricsAreValidatedBeforeLocalRomanizationOffline() =
        runBlocking {
            val path = Files.createTempFile("kugou-lyrics-cache", ".db")
            Files.deleteIfExists(path)
            var database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("No network expected") })
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                Settings.shared.lyricsAiTranslationEnabled = false
                Settings.shared.lyricsRomanizationEnabled = true
                Settings.shared.lyricsRomanizeLanguages = "Korean:true"
                val payloads =
                    listOf(
                        """{"version":2,"provider":"LYPRIO","subprovider":"KUGOU","lines":[{"text":"안녕","romanizedText":"你好"}]}""",
                        "[meta]\nprovider=LYPRIO\nsubprovider=KUGOU\n[lyrics]\n안녕\n[romanLyrics]\n你好",
                    )
                payloads.forEachIndexed { index, payload ->
                    database.dao().saveLyrics(LyricsEntity("song$index", payload, "Lyprio/KUGOU"))
                }
                database.close()
                database = getDesktopDatabaseBuilder(path.toFile()).build()
                val repository = LyricsRepository(NoOpLyricsFetcher, LyricsTranslationClient(client), database.dao())
                payloads.forEachIndexed { index, payload ->
                    val videoId = "song$index"
                    val cached =
                        requireNotNull(
                            repository.getLyrics(videoId, "Song", "Artist", null, preferredProviders = listOf(LyricsProvider.LYPRIO)),
                        )
                    assertEquals(payload, cached.lyrics)
                    assertNull(repository.getVideoSpecificLyrics(videoId, "Song", "Artist", null))
                    database.dao().saveLyrics(LyricsEntity(videoId, payload, "Lyprio/KUGOU", isUserSelected = true))
                    assertEquals(cached.copy(isUserSelected = true), repository.getVideoSpecificLyrics(videoId, "Song", "Artist", null))
                    val parsed = requireNotNull(LyricsUtils.parseMlxDocument(cached.lyrics)).lines
                    assertNull(parsed.single().romanizedText)
                    assertNull(parsed.single().romanizedWords)
                    val enriched = repository.enrichLyricsEntries(videoId, parsed).single()
                    assertEquals("안녕", enriched.text)
                    assertEquals("annyeong", enriched.romanizedText)
                }
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun videoLyricsPreserveManualChoicesAndOnlyReuseVideoTimedAutomaticCache() =
        runBlocking {
            val path = Files.createTempFile("video-lyrics-cache", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("No network expected") })
            Settings.replaceSharedForTest(Settings(MapSettings()))
            var requests = 0
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
                    ): LyricsWithProvider? {
                        requests++
                        return null
                    }
                }
            try {
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                for (provider in listOf(LOCAL_LYRICS_PROVIDER, "YouTube")) {
                    val lyrics = MlxBuilder().meta(provider).line(1000, "Saved lyrics").build()
                    database.dao().saveLyrics(LyricsEntity("video", lyrics, provider, isUserSelected = true))
                    assertEquals(
                        LyricsWithProvider(lyrics, provider, isUserSelected = true),
                        repository.getVideoSpecificLyrics("video", "Song", "Artist", null),
                    )
                    assertEquals(0, requests)
                }
                val automaticLyrics = MlxBuilder().meta("YouTube").line(1000, "Cached video line").build()
                database.dao().saveLyrics(LyricsEntity("video", automaticLyrics, "YouTube"))
                assertEquals(automaticLyrics, repository.getVideoSpecificLyrics("video", "Song", "Artist", null)?.lyrics)
                assertTrue(requests > 0)
                assertNull(repository.getVideoSpecificLyrics("video", "Song", "Artist", null, skipCache = true))
                database.dao().saveLyrics(LyricsEntity("video", automaticLyrics, "Lyprio"))
                assertNull(repository.getVideoSpecificLyrics("video", "Song", "Artist", null))
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun manualSelectionSurvivesDatabaseReopenAndProtectsIdenticalSourceFromStaleFetch() =
        runBlocking {
            val path = Files.createTempFile("lyrics-selection", ".db")
            Files.delete(path)
            var database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("No network expected") })
            try {
                val lyrics = "[00:01.00]Chosen source"
                val automatic = LyricsEntity("song", lyrics, "Lyprio/KKBOX", offsetMs = 450)
                database.dao().saveLyrics(automatic)
                val repository = LyricsRepository(NoOpLyricsFetcher, LyricsTranslationClient(client), database.dao())
                repository.selectProviderResult(
                    "song",
                    LyricsProviderResult(LyricsProvider.LYPRIO, automatic.provider, lyrics, LyricsTimingKind.LINE_SYNCED),
                )
                val selected = requireNotNull(database.dao().cachedLyrics("song"))
                assertTrue(selected.isUserSelected)
                assertEquals(450, selected.offsetMs)
                assertEquals(selected, database.dao().cacheLyricsIfUnchanged(automatic, automatic))
                database.close()
                database = getDesktopDatabaseBuilder(path.toFile()).build()
                val reopened = LyricsRepository(NoOpLyricsFetcher, LyricsTranslationClient(client), database.dao())
                val expected = LyricsWithProvider(lyrics, automatic.provider, isUserSelected = true)
                assertEquals(expected, reopened.getLyrics("song", "Song", "Artist", null))
                assertEquals(expected, reopened.getVideoSpecificLyrics("song", "Song", "Artist", null))
                assertEquals(450, reopened.getLyricsOffset("song"))
                reopened.saveCustomLyrics("song", "Edited source")
                assertTrue(requireNotNull(database.dao().cachedLyrics("song")).isUserSelected)
                val edited = requireNotNull(database.dao().cachedLyrics("song"))
                assertEquals(
                    "Edited source",
                    reopened.getLyrics("song", "Song", "Artist", null, preferredProviders = emptyList(), skipCache = true)?.lyrics,
                )
                assertEquals(edited, database.dao().cachedLyrics("song"))
                reopened.revertUserLyrics("song")
                val refreshed = LyricsRepository(StaticLyricsFetcher, LyricsTranslationClient(client), database.dao())
                assertEquals(
                    StaticLyricsFetcher.lyrics,
                    refreshed
                        .getLyrics(
                            "song",
                            "Song",
                            "Artist",
                            null,
                            preferredProviders = listOf(LyricsProvider.YOUTUBE),
                            skipCache = true,
                        )?.lyrics,
                )
                assertFalse(requireNotNull(database.dao().cachedLyrics("song")).isUserSelected)
                assertEquals(450, refreshed.getLyricsOffset("song"))
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun partialLyricsAreReturnedWithoutCachingAndCompletedLyricsAreRefetched() =
        runBlocking {
            val path = Files.createTempFile("lyrics-partial", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Translation must not run") })
            try {
                val partial =
                    LyricsWithProvider(MlxBuilder().meta("Lyprio").line(1_000, "Earlier line").build(), "Lyprio", isComplete = false)
                val complete =
                    LyricsWithProvider(
                        MlxBuilder().meta("Lyprio").line(1_000, 2_000, words = listOf(MlxWord("Later words", 1_000, 2_000))).build(),
                        "Lyprio",
                    )
                var requests = 0
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
                        ) = if (++requests == 1) partial else complete
                    }
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())

                suspend fun fetch() =
                    repository.getLyrics("song", "Song", "Artist", null, preferredProviders = listOf(LyricsProvider.LYPRIO))

                assertEquals(partial, fetch())
                assertNull(database.dao().cachedLyrics("song"))
                assertEquals(complete, fetch())
                assertEquals(complete.lyrics, database.dao().cachedLyrics("song")?.lyrics)
                assertEquals(complete, fetch())
                assertEquals(2, requests)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun transientLrcLibFailureIsNotPersistedAndNextLookupCanSucceed() =
        runBlocking {
            val path = Files.createTempFile("lyrics-provider-failure", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            var fail = true
            val client =
                HttpClient(
                    MockEngine {
                        if (fail) {
                            respond("outage", HttpStatusCode.ServiceUnavailable)
                        } else {
                            respond(
                                """[{"id":1,"name":"Song","artistName":"Artist","duration":180,"instrumental":false,"syncedLyrics":"[00:12]A recovered lyric"}]""",
                            )
                        }
                    },
                )
            try {
                val provider = LrcLibLyricsProvider(client)
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
                        ): LyricsWithProvider? {
                            if (provider != LyricsProvider.LRCLIB) return null
                            return providerLyrics(title, artist, duration)
                        }

                        private suspend fun providerLyrics(
                            title: String,
                            artist: String,
                            duration: Long?,
                        ) = provider.getLyrics(title, artist, duration)?.let { LyricsWithProvider(it, "LrcLib") }
                    }
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                assertNull(repository.getLyrics("song", "Song", "Artist", null, preferredProviders = listOf(LyricsProvider.LRCLIB)))
                assertNull(database.dao().cachedLyrics("song"))
                fail = false
                assertTrue(repository.getLyrics("song", "Song", "Artist", null, preferredProviders = listOf(LyricsProvider.LRCLIB)) != null)
                assertTrue(database.dao().cachedLyrics("song") != null)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun providerFixturesAreRefetchedWithoutCachingAMissAndCustomLyricsArePreserved() =
        runBlocking {
            val path = Files.createTempFile("metrolist-lyrics-placeholder", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Translation must not run") })
            try {
                val placeholder = "[00:01.00]Test line\n[00:02.00]Test line 2"
                var response = placeholder
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
                        ) = LyricsWithProvider(response, "Lyprio")
                    }
                val dao = database.dao()
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), dao)

                suspend fun fetch(id: String) =
                    repository.getLyrics(id, "Song", "Artist", null, preferredProviders = listOf(LyricsProvider.LYPRIO))
                assertNull(fetch("missing"))
                assertNull(dao.cachedLyrics("missing"))
                dao.upsert(LyricsEntity("cached", placeholder, "Lyprio"))
                response = "[00:01.00]Valid provider content"
                assertEquals(response, fetch("cached")?.lyrics)
                assertEquals(response, dao.cachedLyrics("cached")?.lyrics)
                repository.saveCustomLyrics("custom", placeholder)
                assertEquals(placeholder, fetch("custom")?.lyrics)
                assertEquals(USER_LYRICS_PROVIDER, fetch("custom")?.provider)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun offsetChangesDuringProviderReplacementAndCustomSaveArePreserved() =
        runBlocking {
            val path = Files.createTempFile("metrolist-lyrics", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Translation client must not be called") })
            try {
                database.dao().upsert(LyricsEntity("video", "first", "First"))
                assertEquals(1, database.dao().updateLyricsOffset("video", 750))
                val actualDao = database.dao()
                var nextOffset = 1200
                val dao =
                    object : DatabaseDao by actualDao {
                        override suspend fun saveLyrics(lyrics: LyricsEntity) {
                            actualDao.setLyricsOffset(lyrics.id, nextOffset)
                            actualDao.saveLyrics(lyrics)
                        }
                    }
                val repository = LyricsRepository(NoOpLyricsFetcher, LyricsTranslationClient(client), dao)
                repository.selectProviderResult(
                    "video",
                    LyricsProviderResult(
                        provider = LyricsProvider.YOUTUBE,
                        providerName = "YouTube Music",
                        lyrics = "second",
                        timingKind = LyricsTimingKind.UNSYNCED,
                    ),
                )

                val stored = database.dao().lyrics("video").first()!!
                assertEquals("second", stored.lyrics)
                assertEquals("YouTube Music", stored.provider)
                assertEquals(1200, stored.offsetMs)

                nextOffset = -400
                repository.saveCustomLyrics("video", "custom")
                val custom = database.dao().lyrics("video").first()!!
                assertEquals(1200, custom.offsetMs)
                assertEquals("YouTube Music", custom.provider)
                assertEquals("custom", custom.userLyrics)
                assertTrue(isLocalLyricsProvider(USER_LYRICS_PROVIDER))
                assertTrue(isLocalLyricsProvider("Local"))
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun migrationCopiesLegacySongOffset() =
        runBlocking {
            val path = Files.createTempFile("metrolist-lyrics-migration", ".db")
            try {
                val connection = JdbcSQLiteDriver().open(path.toString())
                connection.prepare("CREATE TABLE song (id TEXT NOT NULL PRIMARY KEY, lyricsOffset INTEGER NOT NULL DEFAULT 0)").use {
                    assertFalse(it.step())
                }
                connection.prepare("CREATE TABLE lyrics (id TEXT NOT NULL PRIMARY KEY, lyrics TEXT NOT NULL)").use {
                    assertFalse(it.step())
                }
                connection.prepare("CREATE TABLE speed_dial (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL)").use {
                    assertFalse(it.step())
                }
                connection.prepare("INSERT INTO song(id, lyricsOffset) VALUES ('video', 425)").use { assertFalse(it.step()) }
                connection.prepare("INSERT INTO lyrics(id, lyrics) VALUES ('video', 'lyrics')").use { assertFalse(it.step()) }
                connection.prepare("INSERT INTO speed_dial(id, type) VALUES ('MPSPpodcast', 'PLAYLIST')").use { assertFalse(it.step()) }

                MIGRATION_44_45.migrate(connection)

                connection.prepare("SELECT offsetMs FROM lyrics WHERE id = 'video'").use {
                    assertEquals(true, it.step())
                    assertEquals(425L, it.getLong(0))
                }
                connection.prepare("SELECT type FROM speed_dial WHERE id = 'MPSPpodcast'").use {
                    assertEquals(true, it.step())
                    assertEquals("PODCAST", it.getText(0))
                }
                connection.close()
            } finally {
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun firstLyricsFetchPreservesLegacyOffsetWithoutCachedLyrics() =
        runBlocking {
            val path = Files.createTempFile("metrolist-lyrics-legacy-offset", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Translation client must not be called") })
            try {
                database.dao().upsert(SongEntity(id = "video", title = "Song", lyricsOffset = 625))
                val repository = LyricsRepository(StaticLyricsFetcher, LyricsTranslationClient(client), database.dao())

                repository.getLyrics(
                    videoId = "video",
                    title = "Song",
                    artist = "Artist",
                    duration = null,
                    preferredProviders = listOf(LyricsProvider.YOUTUBE),
                )

                assertEquals(
                    625,
                    database
                        .dao()
                        .lyrics("video")
                        .first()!!
                        .offsetMs,
                )
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun cachedUnsynchronizedLyricsCanBeUpgradedWithoutExplicitRefresh() =
        runBlocking {
            val path = Files.createTempFile("metrolist-lyrics-cache", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Translation client must not be called") })
            val fetcher = CountingLyricsFetcher()
            val cachedLyrics = MlxBuilder().meta("Cached").unsyncedLine("Cached plain lyrics").build()
            try {
                database.dao().upsert(LyricsEntity("video", cachedLyrics, "Cached"))
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())

                val result =
                    repository.getLyrics(
                        videoId = "video",
                        title = "Song",
                        artist = "Artist",
                        duration = null,
                        preferredProviders = listOf(LyricsProvider.YOUTUBE),
                    )

                assertEquals(fetcher.lyrics, result?.lyrics)
                assertEquals(1, fetcher.calls)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun firstFetchAndRefetchPersistProviderOrderOverTimingAndPreserveOffset() =
        runBlocking {
            val path = Files.createTempFile("metrolist-lyrics-word-priority", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Translation client must not be called") })
            val line = MlxBuilder().meta("Line").line(1_000, "Line text").build()
            val word = MlxBuilder().meta("Word").line(1_000, 2_000, words = listOf(MlxWord("Word", 1_000, 2_000))).build()
            val calls = mutableListOf<LyricsProvider>()
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
                        calls += provider
                        return LyricsWithProvider(if (provider == LyricsProvider.LRCLIB) line else word, provider.name)
                    }
                }
            try {
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                repository.setLyricsOffset("video", 425)
                val providers = listOf(LyricsProvider.LRCLIB, LyricsProvider.PAXSENIX)
                for (refresh in listOf(false, true)) {
                    val result = repository.getLyrics("video", "Song", "Artist", null, preferredProviders = providers, skipCache = refresh)
                    assertEquals(line, result?.lyrics)
                    assertEquals("LRCLIB", result?.provider)
                    assertEquals(line, database.dao().cachedLyrics("video")?.lyrics)
                    assertEquals(425, repository.getLyricsOffset("video"))
                }
                assertEquals(listOf(LyricsProvider.LRCLIB, LyricsProvider.LRCLIB), calls)
                assertEquals(line, repository.getLyrics("video", "Song", "Artist", null, preferredProviders = providers)?.lyrics)
                assertEquals(listOf(LyricsProvider.LRCLIB, LyricsProvider.LRCLIB), calls)
                val videoProviders = listOf(LyricsProvider.LRCLIB)
                val videoResult =
                    repository.getLyrics(
                        "native-video",
                        "Song",
                        "Artist",
                        null,
                        preferredProviders = videoProviders,
                        isVideoContext = true,
                    )
                assertEquals("LRCLIB", videoResult?.provider)
                assertEquals(line, database.dao().cachedLyrics("native-video")?.lyrics)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun firstFetchAndRefetchWaitForLyprioWithoutRequestingFallbacks() =
        runTest {
            val path = Files.createTempFile("metrolist-lyrics-pending-quality", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(StandardTestDispatcher(testScheduler)).build()
            val client = HttpClient(MockEngine { error("Translation client must not be called") })
            val line = MlxBuilder().meta("Line").line(1_000, "Line text").build()
            val word = MlxBuilder().meta("Word").line(1_000, 2_000, words = listOf(MlxWord("Word", 1_000, 2_000))).build()
            val calls = mutableListOf<LyricsProvider>()
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
                        calls += provider
                        if (provider == LyricsProvider.LYPRIO) delay(4_000.milliseconds)
                        return LyricsWithProvider(if (provider == LyricsProvider.LRCLIB) line else word, provider.name)
                    }
                }
            try {
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                repository.setLyricsOffset("video", 425)
                val providers = listOf(LyricsProvider.LYPRIO, LyricsProvider.LRCLIB)
                for (refresh in listOf(false, true)) {
                    val result = repository.getLyrics("video", "Song", "Artist", null, preferredProviders = providers, skipCache = refresh)
                    assertEquals(word, result?.lyrics)
                    assertEquals("LYPRIO", result?.provider)
                    assertEquals(word, database.dao().cachedLyrics("video")?.lyrics)
                    assertEquals(425, repository.getLyricsOffset("video"))
                }
                assertEquals(listOf(LyricsProvider.LYPRIO, LyricsProvider.LYPRIO), calls)
                assertEquals(word, repository.getLyrics("video", "Song", "Artist", null, preferredProviders = providers)?.lyrics)
                assertEquals(listOf(LyricsProvider.LYPRIO, LyricsProvider.LYPRIO), calls)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cachedFallbackCannotPreemptPendingLyprioButManualSelectionsCan() =
        runTest {
            val path = Files.createTempFile("lyrics-cached-pending", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(StandardTestDispatcher(testScheduler)).build()
            val client = HttpClient(MockEngine { error("No network expected") })
            val line = MlxBuilder().meta("LrcLib").line(1_000, "Cached line").build()
            val word = MlxBuilder().meta("Lyprio").line(1_000, 2_000, words = listOf(MlxWord("Sung words", 1_000, 2_000))).build()
            try {
                for (writeDuringFetch in listOf(false, true)) {
                    for (manual in listOf(false, true)) {
                        val id = "video-$writeDuringFetch-$manual"
                        val cached = LyricsEntity(id, word, "LrcLib", isUserSelected = manual)
                        var lyprioCompleted = false
                        if (!writeDuringFetch) database.dao().saveLyrics(cached)
                        database.dao().setLyricsOffset(id, 425)
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
                                ): LyricsWithProvider? =
                                    when (provider) {
                                        LyricsProvider.LYPRIO -> {
                                            delay(4_000.milliseconds)
                                            if (writeDuringFetch) database.dao().saveLyrics(cached)
                                            lyprioCompleted = true
                                            LyricsWithProvider(line, "Lyprio")
                                        }

                                        LyricsProvider.LRCLIB -> {
                                            LyricsWithProvider(word, "LrcLib")
                                        }

                                        else -> {
                                            null
                                        }
                                    }
                            }
                        val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                        val result =
                            repository.getLyrics(
                                id,
                                "Song",
                                "Artist",
                                null,
                                preferredProviders = listOf(LyricsProvider.LYPRIO, LyricsProvider.LRCLIB),
                            )
                        assertEquals(if (manual) word else line, result?.lyrics)
                        assertEquals(if (manual) word else line, database.dao().cachedLyrics(id)?.lyrics)
                        assertEquals(manual, result?.isUserSelected)
                        assertEquals(writeDuringFetch || !manual, lyprioCompleted)
                        assertEquals(425, repository.getLyricsOffset(id))
                    }
                }
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun disabledAutomaticCacheIsNeverPublishedOrReturned() =
        runBlocking {
            val path = Files.createTempFile("lyrics-disabled-cache", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("No network expected") })
            Settings.replaceSharedForTest(Settings(MapSettings()))
            Settings.shared.lyricsEnabledProviders = "PAXSENIX"
            val enabled = LyricsWithProvider("[00:01.00]Enabled lyrics", "Paxsenix")
            try {
                for (cachedProvider in listOf("LrcLib", "Lyprio/KUGOU")) {
                    for (outcome in listOf("success", "empty", "failure")) {
                        for (writeDuringFetch in listOf(false, true)) {
                            val id = "$cachedProvider-$outcome-$writeDuringFetch"
                            val cached = LyricsEntity(id, "[00:01.00]Disabled lyrics", cachedProvider, offsetMs = 425)
                            if (!writeDuringFetch) database.dao().saveLyrics(cached)
                            val calls = mutableListOf<LyricsProvider>()
                            val published = mutableListOf<LyricsWithProvider>()
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
                                    ): LyricsWithProvider? {
                                        calls += provider
                                        if (writeDuringFetch) database.dao().saveLyrics(cached)
                                        return when (outcome) {
                                            "success" -> enabled
                                            "empty" -> null
                                            else -> error("Provider unavailable")
                                        }
                                    }
                                }
                            val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                            val result = repository.getLyrics(id, "Song", "Artist", null, onLyricsAvailable = { published += it })
                            assertEquals(if (outcome == "success") enabled else null, result, id)
                            assertEquals(if (outcome == "success") listOf(enabled) else emptyList(), published, id)
                            assertEquals(
                                if (outcome == "success") {
                                    listOf(LyricsProvider.PAXSENIX)
                                } else {
                                    listOf(LyricsProvider.PAXSENIX) + LyricsProvider.afterUserLyricsFallbacks
                                },
                                calls,
                            )
                            val stored = requireNotNull(database.dao().cachedLyrics(id))
                            assertEquals(425, stored.offsetMs)
                            when {
                                outcome == "success" -> assertEquals(enabled.lyrics, stored.lyrics)
                                outcome == "failure" || writeDuringFetch -> assertEquals(cached, stored)
                                else -> assertEquals(LyricsEntity.LYRICS_NOT_FOUND, stored.provider)
                            }
                        }
                    }
                }
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun disabledProvidersDoNotInvalidateManualSelectionsOrLocalEdits() =
        runBlocking {
            val path = Files.createTempFile("lyrics-disabled-manual", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("No network expected") })
            val fetcher = CountingLyricsFetcher()
            try {
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                for ((provider, selected) in listOf("Lyprio/KUGOU" to true, LOCAL_LYRICS_PROVIDER to false, "Local" to false)) {
                    val lyrics = "[00:01.00]Saved lyrics"
                    val cached = LyricsEntity(provider, lyrics, provider, isUserSelected = selected, offsetMs = 425)
                    database.dao().saveLyrics(cached)
                    val result =
                        repository.getLyrics(
                            provider,
                            "Song",
                            "Artist",
                            null,
                            preferredProviders = listOf(LyricsProvider.PAXSENIX),
                        )
                    assertEquals(LyricsWithProvider(lyrics, provider, isUserSelected = selected), result)
                    assertEquals(cached, database.dao().cachedLyrics(provider))
                }
                assertEquals(0, fetcher.calls)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun automaticCacheSurvivesOfflineLookupAndReorderingReplacesRicherLyrics() =
        runBlocking {
            val path = Files.createTempFile("lyrics-cached-quality", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("No network expected") })
            val line = MlxBuilder().meta("LrcLib").line(1_000, "Cached line").build()
            val words = MlxBuilder().meta("Provider").line(1_000, 2_000, words = listOf(MlxWord("Sung words", 1_000, 2_000))).build()
            try {
                database.dao().saveLyrics(LyricsEntity("offline", line, "LrcLib"))
                val offline = LyricsRepository(NoOpLyricsFetcher, LyricsTranslationClient(client), database.dao())
                assertEquals(
                    line,
                    offline
                        .getLyrics(
                            "offline",
                            "Song",
                            "Artist",
                            null,
                            preferredProviders = listOf(LyricsProvider.LYPRIO, LyricsProvider.LRCLIB),
                        )?.lyrics,
                )
                assertEquals(line, database.dao().cachedLyrics("offline")?.lyrics)
                database.dao().saveLyrics(LyricsEntity("reordered", words, "LrcLib"))
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
                        ) = LyricsWithProvider(line, "BetterLyrics")
                    }
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                assertEquals(
                    "BetterLyrics",
                    repository
                        .getLyrics(
                            "reordered",
                            "Song",
                            "Artist",
                            null,
                            preferredProviders = listOf(LyricsProvider.BETTERLYRICS, LyricsProvider.LRCLIB),
                        )?.provider,
                )
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun explicitCacheRefreshCanUpgradeUnsynchronizedLyrics() =
        runBlocking {
            val path = Files.createTempFile("metrolist-lyrics-refresh", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Translation client must not be called") })
            val fetcher = CountingLyricsFetcher()
            val cachedLyrics = MlxBuilder().meta("Cached").unsyncedLine("Cached plain lyrics").build()
            try {
                database.dao().upsert(LyricsEntity("video", cachedLyrics, "Cached"))
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())

                val result =
                    repository.getLyrics(
                        videoId = "video",
                        title = "Song",
                        artist = "Artist",
                        duration = null,
                        preferredProviders = listOf(LyricsProvider.YOUTUBE),
                        skipCache = true,
                    )

                val expectedLyrics = fetcher.lyrics
                assertEquals(expectedLyrics, result?.lyrics)
                assertEquals(1, fetcher.calls)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun providerTimeoutDoesNotCacheMissingLyricsAndRetries() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val path = Files.createTempFile("metrolist-lyrics-quality-timeout", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(dispatcher).build()
            val client = HttpClient(MockEngine { error("Translation client must not be called") })
            val lyrics = MlxBuilder().meta("YouTube Music").line(1_000, "Lyrics").build()
            var calls = 0
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
                    ): LyricsWithProvider? {
                        calls++
                        if (calls == 1) delay(20_000.milliseconds)
                        return if (calls == 1) null else LyricsWithProvider(lyrics, "YouTube Music")
                    }
                }
            try {
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                assertEquals(
                    null,
                    repository.getLyrics(
                        videoId = "video",
                        title = "Song",
                        artist = "Artist",
                        duration = null,
                        preferredProviders = listOf(LyricsProvider.YOUTUBE),
                    ),
                )
                assertEquals(null, database.dao().cachedLyrics("video"))

                assertEquals(
                    lyrics,
                    repository
                        .getLyrics(
                            videoId = "video",
                            title = "Song",
                            artist = "Artist",
                            duration = null,
                            preferredProviders = listOf(LyricsProvider.YOUTUBE),
                        )?.lyrics,
                )
                assertEquals("YouTube Music", database.dao().cachedLyrics("video")?.provider)
                assertEquals(2, calls)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun staleMissCannotOverwriteAChangedLookupOrItsOffset() =
        runBlocking {
            val path = Files.createTempFile("lyrics-miss-race", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val dao = database.dao()
                val original = LyricsEntity("song", "", LyricsEntity.LYRICS_NOT_FOUND, missLookupKey = "original", missExpiresAt = 300_000)
                dao.saveLyrics(original)
                val corrected = original.copy(missLookupKey = "corrected", missExpiresAt = 310_000)
                dao.cacheLyricsIfUnchanged(corrected, original)
                dao.setLyricsOffset("song", 425)
                val expected = corrected.copy(offsetMs = 425)
                assertEquals(expected, dao.cacheLyricsIfUnchanged(original.copy(missExpiresAt = 320_000), original))
                assertEquals(expected, dao.cachedLyrics("song"))
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun missingLyricsSurviveRestartExpireAndExplicitRefreshBypassesTheCache() =
        runBlocking {
            val path = Files.createTempFile("metrolist-lyrics-missing", ".db")
            Files.deleteIfExists(path)
            var database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Translation client must not be called") })
            var calls = 0
            var nowMs = 0L
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
                    ): LyricsWithProvider? {
                        calls++
                        return null
                    }
                }
            try {
                var repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao(), nowMs = { nowMs })
                repeat(2) {
                    repository.getLyrics(
                        videoId = "video",
                        title = "Song",
                        artist = "Artist",
                        duration = null,
                        preferredProviders = listOf(LyricsProvider.YOUTUBE),
                    )
                }
                assertEquals(1, calls)
                database.close()
                database = getDesktopDatabaseBuilder(path.toFile()).build()
                repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao(), nowMs = { nowMs })
                assertEquals(
                    7 * 24 * 60 * 60_000L,
                    repository.getCachedLyricsMissExpiry(
                        "video",
                        "Song",
                        "Artist",
                        null,
                        preferredProviders = listOf(LyricsProvider.YOUTUBE),
                    ),
                )
                nowMs = 5 * 60_000L
                assertNull(repository.getLyrics("video", "Song", "Artist", null, preferredProviders = listOf(LyricsProvider.YOUTUBE)))
                assertEquals(1, calls)
                nowMs = 7 * 24 * 60 * 60_000L - 1
                assertNull(repository.getLyrics("video", "Song", "Artist", null, preferredProviders = listOf(LyricsProvider.YOUTUBE)))
                assertEquals(1, calls)
                nowMs++
                assertNull(
                    repository.getCachedLyricsMissExpiry(
                        "video",
                        "Song",
                        "Artist",
                        null,
                        preferredProviders = listOf(LyricsProvider.YOUTUBE),
                    ),
                )
                assertNull(repository.getLyrics("video", "Song", "Artist", null, preferredProviders = listOf(LyricsProvider.YOUTUBE)))
                assertEquals(2, calls)
                assertEquals(
                    LyricsEntity.LYRICS_NOT_FOUND,
                    database
                        .dao()
                        .lyrics("video")
                        .first()
                        ?.provider,
                )

                repository.getLyrics(
                    videoId = "video",
                    title = "Song",
                    artist = "Artist",
                    duration = null,
                    preferredProviders = listOf(LyricsProvider.YOUTUBE),
                    skipCache = true,
                )
                assertEquals(3, calls)
                nowMs = 0
                assertNull(
                    repository.getCachedLyricsMissExpiry(
                        "video",
                        "Song",
                        "Artist",
                        null,
                        preferredProviders = listOf(LyricsProvider.YOUTUBE),
                    ),
                )
                assertNull(repository.getLyrics("video", "Song", "Artist", null, preferredProviders = listOf(LyricsProvider.YOUTUBE)))
                assertEquals(4, calls)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun missingLyricsAreRetriedWhenProvidersOrLookupMetadataChange() =
        runBlocking {
            val path = Files.createTempFile("metrolist-lyrics-missing-config", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Translation client must not be called") })
            val calls = mutableListOf<LyricsProvider>()
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
                    ): LyricsWithProvider? {
                        calls += provider
                        return null
                    }
                }
            try {
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                val providers = listOf(LyricsProvider.LYPRIO, LyricsProvider.YOUTUBE)
                assertNull(repository.getLyrics("video", "Song", "Artist", null, preferredProviders = listOf(LyricsProvider.YOUTUBE)))
                assertEquals(listOf(LyricsProvider.YOUTUBE), calls)

                data class LookupMetadata(
                    val title: String,
                    val artist: String,
                    val duration: Long?,
                    val album: String?,
                )
                val metadata =
                    listOf(
                        LookupMetadata("Song", "Artist", null, null),
                        LookupMetadata("Corrected title", "Artist", null, null),
                        LookupMetadata("Corrected title", "Corrected artist", null, null),
                        LookupMetadata("Corrected title", "Corrected artist", 180_000L, null),
                        LookupMetadata("Corrected title", "Corrected artist", 180_000L, "Album"),
                    )
                for ((index, values) in metadata.withIndex()) {
                    repeat(2) {
                        assertNull(
                            repository.getLyrics(
                                "video",
                                values.title,
                                values.artist,
                                values.duration,
                                values.album,
                                preferredProviders = providers,
                            ),
                        )
                    }
                    assertEquals(listOf(LyricsProvider.YOUTUBE) + List(index + 1) { providers }.flatten(), calls)
                }
                val restarted = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                assertNull(restarted.getLyrics("video", "Corrected title", "Corrected artist", 180_000L, "Album", providers))
                assertEquals(listOf(LyricsProvider.YOUTUBE) + List(metadata.size) { providers }.flatten(), calls)
                assertNull(
                    restarted.getLyrics(
                        "video",
                        "Corrected title",
                        "Corrected artist",
                        180_000L,
                        "Album",
                        providers,
                        isVideoContext = true,
                    ),
                )
                assertEquals(
                    listOf(LyricsProvider.YOUTUBE) + List(metadata.size + 1) { providers }.flatten() + LyricsProvider.SIMPMUSIC,
                    calls,
                )
                calls.clear()
                val reordered = providers.reversed()
                repeat(2) {
                    assertNull(
                        restarted.getLyrics(
                            "video",
                            "Corrected title",
                            "Corrected artist",
                            180_000L,
                            "Album",
                            reordered,
                            isVideoContext = true,
                        ),
                    )
                }
                assertEquals(reordered + LyricsProvider.SIMPMUSIC, calls)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun legacyMissingLyricsCacheIsRetried() =
        runBlocking {
            val path = Files.createTempFile("metrolist-lyrics-legacy-missing", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Translation client must not be called") })
            var calls = 0
            val lyrics = MlxBuilder().meta("Recovered").line(1_000, "Recovered lyrics").build()
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
                        calls++
                        return LyricsWithProvider(lyrics, "LrcLib")
                    }
                }
            try {
                for (marker in listOf("LYRICS_NOT_FOUND", LyricsEntity.LYRICS_NOT_FOUND)) {
                    database.dao().saveLyrics(
                        LyricsEntity(
                            id = "video",
                            lyrics = "",
                            provider = marker,
                        ),
                    )
                    val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())

                    val result =
                        repository.getLyrics(
                            videoId = "video",
                            title = "Song",
                            artist = "Artist",
                            duration = null,
                            preferredProviders = listOf(LyricsProvider.LRCLIB),
                        )

                    assertEquals(lyrics, result?.lyrics)
                    assertEquals(1, calls)
                    calls = 0
                    assertEquals("LrcLib", database.dao().cachedLyrics("video")?.provider)
                }
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun transientProviderFailureIsNotCachedAsMissingLyrics() =
        runBlocking {
            val path = Files.createTempFile("metrolist-lyrics-failure", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Translation client must not be called") })
            var fail = false
            var calls = 0
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
                    ): LyricsWithProvider? {
                        calls++
                        if (fail) error("Temporary provider failure")
                        return null
                    }
                }
            try {
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())

                suspend fun fetch(skipCache: Boolean = false) =
                    repository.getLyrics(
                        videoId = "video",
                        title = "Song",
                        artist = "Artist",
                        duration = null,
                        preferredProviders = listOf(LyricsProvider.YOUTUBE),
                        skipCache = skipCache,
                    )

                suspend fun cachedMissExpiry() =
                    repository.getCachedLyricsMissExpiry(
                        "video",
                        "Song",
                        "Artist",
                        null,
                        preferredProviders = listOf(LyricsProvider.YOUTUBE),
                    )
                fail = true
                assertNull(fetch())
                assertNull(database.dao().cachedLyrics("video"))
                assertNull(cachedMissExpiry())
                fail = false
                assertNull(fetch())
                assertTrue(cachedMissExpiry() != null)
                fail = true
                assertNull(fetch(skipCache = true))
                assertNull(cachedMissExpiry())
                assertNull(fetch())
                assertEquals(4, calls)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun setLyricsOffsetBeforeOrDuringFirstFetchPreservesLyricsAndOffset() =
        runBlocking {
            val path = Files.createTempFile("metrolist-lyrics-stub-offset", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Translation client must not be called") })
            try {
                for (beforeFetch in listOf(true, false)) {
                    val videoId = "video-$beforeFetch"
                    lateinit var repository: LyricsRepository
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
                                if (!beforeFetch) assertTrue(repository.setLyricsOffset(checkNotNull(videoId), -250))
                                return StaticLyricsFetcher.getLyrics(title, artist, duration, album, videoId, provider)
                            }
                        }
                    repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao())
                    if (beforeFetch) assertTrue(repository.setLyricsOffset(videoId, -250))
                    val lyrics =
                        repository.getLyrics(
                            videoId = videoId,
                            title = "Song",
                            artist = "Artist",
                            duration = null,
                            preferredProviders = listOf(LyricsProvider.YOUTUBE),
                        )
                    val expectedLyrics = StaticLyricsFetcher.lyrics
                    assertEquals(expectedLyrics, lyrics?.lyrics)
                    assertEquals(-250, repository.getLyricsOffset(videoId))
                    assertTrue(repository.setLyricsOffset(videoId, 150))
                    assertEquals(150, repository.getLyricsOffset(videoId))
                    assertEquals(expectedLyrics, database.dao().cachedLyrics(videoId)?.lyrics)
                }
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun mvOffsetIsSeparatedFromSongOffset() =
        runBlocking {
            val path = Files.createTempFile("metrolist-lyrics-mv-offset", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Translation client must not be called") })
            try {
                val repository = LyricsRepository(StaticLyricsFetcher, LyricsTranslationClient(client), database.dao())
                repository.setLyricsOffset("song_audio_id", 350)
                repository.setLyricsOffset("song_mv_id", -400)

                assertEquals(350, repository.getLyricsOffset("song_audio_id"))
                assertEquals(-400, repository.getLyricsOffset("song_mv_id"))
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }
}

private object NoOpLyricsFetcher : LyricsFetcher {
    override suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
        videoId: String?,
        provider: LyricsProvider,
        isVideoContext: Boolean,
    ): LyricsWithProvider? = null
}

private object StaticLyricsFetcher : LyricsFetcher {
    val lyrics = MlxBuilder().meta("YouTube Music").line(0, "Lyrics").build()

    override suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
        videoId: String?,
        provider: LyricsProvider,
        isVideoContext: Boolean,
    ): LyricsWithProvider = LyricsWithProvider(lyrics, "YouTube Music")
}

private class CountingLyricsFetcher : LyricsFetcher {
    val lyrics = MlxBuilder().meta("YouTube Music").line(1_000, "Fresh synced lyrics").build()

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
        return LyricsWithProvider(lyrics, "YouTube Music")
    }
}
