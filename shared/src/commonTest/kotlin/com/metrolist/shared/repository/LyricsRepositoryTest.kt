package com.metrolist.shared.repository

import com.metrolist.shared.api.lyrics.AiModelCatalog
import com.metrolist.shared.api.lyrics.AiProviderPresets
import com.metrolist.shared.api.lyrics.LrcredLyricsProvider
import com.metrolist.shared.api.lyrics.LyprioLyricsProvider
import com.metrolist.shared.api.lyrics.LyricsFetcher
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.api.lyrics.LyricsTranslationFailure
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.api.lyrics.MlxBuilder
import com.metrolist.shared.api.lyrics.MlxWord
import com.metrolist.shared.api.lyrics.romanizer.LyricsRomanizer
import com.metrolist.shared.db.entities.LyricsEntity
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.LyricsTranslationProgress
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.model.WordTimestamp
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsRepositoryTest {
    @Test
    fun freeOpenRouterTranslatesAndDetectsAllLinesInOneBatch() =
        runBlocking {
            Settings.shared.lyricsAiTranslationEnabled = true
            Settings.shared.lyricsAiProvider = "OpenRouter"
            Settings.shared.lyricsAiModel = AiProviderPresets.FREE_LYRICS_AI_MODEL
            Settings.shared.lyricsAiTargetLanguage = "fr"
            Settings.shared.lyricsAiApiKey = "oauth-key"
            var requests = 0
            val originals = List(43) { if (it % 2 == 0) "Hello bonsoir" else "Bonsoir" }
            val translated = originals.map { if (it == "Bonsoir") it else "Bonjour bonsoir" }
            HttpClient(
                MockEngine { request ->
                    requests++
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val prompt =
                        body
                            .getValue("messages")
                            .jsonArray
                            .last()
                            .jsonObject
                            .getValue("content")
                            .jsonPrimitive.content
                    assertTrue("Leave text already in French unchanged" in prompt)
                    assertTrue("translate only the foreign passages" in prompt)
                    val inputs =
                        Json
                            .parseToJsonElement(prompt.substringAfter("\n\n"))
                            .jsonObject
                            .getValue("lines")
                            .jsonArray
                    assertEquals(originals, inputs.map { it.jsonPrimitive.content })
                    val payload = "{\"lines\":${JsonArray(translated.map(::JsonPrimitive))}}"
                    respond("""{"choices":[{"message":{"content":${JsonPrimitive(payload)}}}]}""")
                },
            ).use { http ->
                val repository = LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(http))
                val entries = originals.mapIndexed { index, text -> LyricsEntry(index * 1_000L, text) }
                val result = repository.enrichLyricsEntries("song", entries)
                assertEquals("Bonjour bonsoir", result[0].translatedText)
                assertNull(result[1].translatedText)
                assertEquals(entries.map { it.time }, result.map { it.time })
                assertEquals(1, requests)
            }
        }

    @Test
    fun wordTimingPreferenceSearchesLowerSourcesAndKeepsLineFallbacks() =
        runTest {
            val line = LyricsWithProvider("[00:01]Sung line", "Lyprio")
            val words =
                LyricsWithProvider(
                    MlxBuilder().meta("Paxsenix").line(1_000, 2_000, words = listOf(MlxWord("Words", 1_000, 2_000))).build(),
                    "Paxsenix",
                )
            val order = listOf(LyricsProvider.LYPRIO, LyricsProvider.PAXSENIX)
            HttpClient(MockEngine { error("Unexpected HTTP request") }).use { client ->
                for (enabled in listOf(false, true)) {
                    Settings.shared.preferWordSyncedLyrics = enabled
                    for (fallback in listOf(null, words)) {
                        val repository =
                            LyricsRepository(
                                FakeLyricsFetcher(mapOf(LyricsProvider.LYPRIO to line, LyricsProvider.PAXSENIX to fallback)),
                                LyricsTranslationClient(client),
                            )
                        assertEquals(
                            if (enabled) fallback ?: line else line,
                            repository.getLyrics("video", "Title", "Artist", null, preferredProviders = order),
                        )
                    }
                    assertEquals(enabled, prefersLyrics(words, line, order, enabled))
                    assertEquals(!enabled, prefersLyrics(line, words, order, enabled))
                    assertFalse(prefersLyrics(words, line.copy(provider = USER_LYRICS_PROVIDER), order, enabled))
                }
            }
        }

    @Test
    fun unsyncedLyprioAllowsTimedFallbackAndKeepsPlainLyricsWhenFallbacksMiss() =
        runTest {
            val plain =
                LyricsWithProvider(
                    """{"version":2,"provider":"LYPRIO","subprovider":"APPLE_MUSIC","syncType":"plain","lines":[
                        {"endMs":179999,"text":"零れ落ちた"},{"endMs":179999,"text":"坂道は最寄駅の前"}]}""",
                    "Lyprio",
                )
            val synced = LyricsWithProvider("[00:01]First\n[00:02]Second", "LrcLib")
            val order = listOf(LyricsProvider.LYPRIO, LyricsProvider.LRCLIB)
            HttpClient(MockEngine { error("Unexpected HTTP request") }).use { client ->
                for (fallback in listOf(null, synced)) {
                    val repository =
                        LyricsRepository(
                            FakeLyricsFetcher(mapOf(LyricsProvider.LYPRIO to plain, LyricsProvider.LRCLIB to fallback)),
                            LyricsTranslationClient(client),
                        )
                    assertEquals(fallback ?: plain, repository.getLyrics("video", "Title", "Artist", null, preferredProviders = order))
                    val preview = repository.getProviderResults("video", "Title", "Artist", null, preferredProviders = order).first()
                    assertEquals(LyricsTimingKind.UNSYNCED, preview.timingKind)
                    assertEquals("零れ落ちた\n坂道は最寄駅の前", preview.preview)
                }
                assertTrue(prefersLyrics(synced, plain, order))
                assertFalse(prefersLyrics(plain, synced, order))
                assertFalse(prefersLyrics(synced, plain.copy(provider = USER_LYRICS_PROVIDER), order))
                assertTrue(prefersLyrics(plain.copy(provider = USER_LYRICS_PROVIDER), synced, order))
            }
        }

    @Test
    fun wrongArtistFromLrcredAllowsTheNextProvider() =
        runTest {
            Settings.shared.lyricsProviderOrder = "LRCRED,LRCLIB"
            Settings.shared.lyricsEnabledProviders = "LRCRED,LRCLIB"
            val calls = mutableListOf<LyricsProvider>()
            val fallback = LyricsWithProvider("[00:01]Correct lyrics", "LRCLIB")
            HttpClient(
                MockEngine { request ->
                    assertEquals("/api/v1", request.url.encodedPath)
                    respond(
                        """{"results":[{"track_name":"Song","artist_name":"Other Artist","duration":180,"timing_type":"word","lyricsUrl":"/wrong.ttml"}]}""",
                    )
                },
            ).use { client ->
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
                            return if (provider == LyricsProvider.LRCRED) {
                                LrcredLyricsProvider(client)
                                    .getLyrics(title, artist, duration, album, videoId)
                                    ?.let { LyricsWithProvider(it, "lrc.red") }
                            } else {
                                fallback
                            }
                        }
                    }
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client))
                assertEquals(fallback, repository.getLyrics("video", "Song", "Main Artist", 180_000))
                assertEquals(listOf(LyricsProvider.LRCRED, LyricsProvider.LRCLIB), calls)
            }
        }

    @Test
    fun emptySuccessfulCompletionReportsTheFailureAndDoesNotCacheIt() =
        runBlocking {
            Settings.shared.lyricsAiTranslationEnabled = true
            Settings.shared.lyricsAiActivationMode = "Manual"
            Settings.shared.lyricsAiProvider = "OpenRouter"
            Settings.shared.lyricsAiTargetLanguage = "fr"
            Settings.shared.lyricsAiApiKey = "test-key"
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond("""{"choices":[{"message":{"content":null}}]}""", HttpStatusCode.OK)
                },
            ).use { client ->
                val repository = LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
                val original = listOf(LyricsEntry(time = 0, text = "Hello"))
                val failures = mutableListOf<LyricsTranslationFailure>()
                repeat(2) {
                    assertEquals(original, repository.enrichLyricsEntries("song", original, forceAiTranslation = true) { failures += it })
                }
                assertEquals(listOf(LyricsTranslationFailure.EMPTY_RESPONSE, LyricsTranslationFailure.EMPTY_RESPONSE), failures)
                assertEquals(4, requests)
            }
        }

    @Test
    fun translationProgressStartsAfterDetectionAndClearsAfterSuccessFailureOrCancellation() =
        runTest {
            Settings.shared.lyricsAiTranslationEnabled = true
            Settings.shared.lyricsAiActivationMode = "Automatic"
            Settings.shared.lyricsAiProvider = "OpenRouter"
            Settings.shared.lyricsAiTargetLanguage = "fr"
            Settings.shared.lyricsAiApiKey = "test-key"
            for (outcome in listOf("success", "retry", "failure", "cancel")) {
                val progress = mutableListOf<LyricsTranslationProgress>()
                var requests = 0
                var failures = 0
                HttpClient(
                    MockEngine {
                        requests++
                        if (requests == 1) {
                            assertTrue(progress.none { it.isTranslating })
                            respond("""{"choices":[{"message":{"content":"{\"lines\":[\"Hello\",\"\"]}"}}]}""")
                        } else {
                            assertEquals(setOf(0), progress.last().lines)
                            when (outcome) {
                                "cancel" -> {
                                    throw CancellationException()
                                }

                                "failure", "retry" -> {
                                    if (outcome == "failure" || requests == 2) {
                                        respond("unavailable", HttpStatusCode.ServiceUnavailable)
                                    } else {
                                        respond("""{"choices":[{"message":{"content":"{\"lines\":[\"Bonjour\"]}"}}]}""")
                                    }
                                }

                                else -> {
                                    respond("""{"choices":[{"message":{"content":"{\"lines\":[\"Bonjour\"]}"}}]}""")
                                }
                            }
                        }
                    },
                ).use { client ->
                    val repository = LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
                    val original = listOf(LyricsEntry(0, "Hello"), LyricsEntry(1_000, "Bonsoir"))

                    suspend fun enrich() =
                        repository.enrichLyricsEntries(
                            "song",
                            original,
                            onTranslationProgress = { progress += it },
                            onTranslationFailure = { failures++ },
                        )
                    if (outcome == "cancel") {
                        assertFailsWith<CancellationException> { enrich() }
                    } else {
                        val result = enrich()
                        assertEquals(if (outcome in listOf("success", "retry")) "Bonjour" else null, result[0].translatedText)
                        assertNull(result[1].translatedText)
                    }
                    assertFalse(progress.last().isTranslating)
                    assertEquals(if (outcome == "failure") 1 else 0, failures)
                    assertEquals(if (outcome in listOf("failure", "retry")) 3 else 2, requests)
                }
            }
        }

    @Test
    fun translationSkipsDoNotPublishPendingLines() =
        runTest {
            Settings.shared.lyricsAiTranslationEnabled = true
            Settings.shared.lyricsAiActivationMode = "Automatic"
            Settings.shared.lyricsAiProvider = "OpenRouter"
            Settings.shared.lyricsAiTargetLanguage = "fr"
            Settings.shared.lyricsAiApiKey = "test-key"
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond("""{"choices":[{"message":{"content":"{\"lines\":[\"\"]}"}}]}""")
                },
            ).use { client ->
                val repository = LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
                for (enabled in listOf(true, false)) {
                    Settings.shared.lyricsAiTranslationEnabled = enabled
                    repository.enrichLyricsEntries(
                        "song",
                        listOf(LyricsEntry(0, "Bonjour")),
                        onTranslationProgress = { assertFalse(it.isTranslating) },
                        onTranslationFailure = { error("Unexpected failure") },
                    )
                }
                assertEquals(1, requests)
            }
        }

    @Test
    fun authenticationAndRateLimitsFailOnceDuringDetectionOrTranslation() =
        runTest {
            Settings.shared.lyricsAiTranslationEnabled = true
            Settings.shared.lyricsAiTargetLanguage = "fr"
            Settings.shared.lyricsAiApiKey = "test-key"
            for (provider in listOf("OpenRouter", "Gemini", "Anthropic")) {
                Settings.shared.lyricsAiProvider = provider
                for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.TooManyRequests)) {
                    for (force in listOf(false, true)) {
                        Settings.shared.lyricsAiActivationMode = "Automatic"
                        var requests = 0
                        var failures = 0
                        HttpClient(
                            MockEngine {
                                requests++
                                respond("rejected", status)
                            },
                        ).use { client ->
                            val repository = LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
                            val original = listOf(LyricsEntry(0, "Hello"))
                            assertEquals(
                                original,
                                repository.enrichLyricsEntries(
                                    "song",
                                    original,
                                    forceAiTranslation = force,
                                    onTranslationFailure = { failures++ },
                                ),
                            )
                            assertEquals(1, requests)
                            assertEquals(1, failures)
                        }
                    }
                }
            }
        }

    @Test
    fun missingCredentialsFailWithoutRequests() =
        runTest {
            Settings.shared.lyricsAiTranslationEnabled = true
            Settings.shared.lyricsAiActivationMode = "Automatic"
            Settings.shared.lyricsAiProvider = "OpenRouter"
            Settings.shared.lyricsAiTargetLanguage = "fr"
            Settings.shared.lyricsAiApiKey = ""
            HttpClient(MockEngine { error("Missing credentials must not reach the provider") }).use { client ->
                val repository = LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
                var failures = 0
                val original = listOf(LyricsEntry(0, "Hello"))
                for (force in listOf(false, true)) {
                    assertEquals(
                        original,
                        repository.enrichLyricsEntries(
                            "song",
                            original,
                            forceAiTranslation = force,
                            onTranslationFailure = { failures++ },
                        ),
                    )
                }
                assertEquals(2, failures)
            }
        }

    @BeforeTest
    fun setUpSettings() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun clearSettings() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun localPreparationRomanizesWithoutRequestingMissingTranslations() =
        runBlocking {
            Settings.shared.lyricsRomanizationEnabled = true
            Settings.shared.lyricsRomanizeLanguages = "Korean:true"
            Settings.shared.lyricsAiTranslationEnabled = true
            Settings.shared.lyricsAiActivationMode = "Automatic"
            Settings.shared.lyricsAiTargetLanguage = "fr"
            Settings.shared.lyricsAiProvider = "OpenRouter"
            Settings.shared.lyricsAiApiKey = "test-key"
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    error("Local preparation must not use the translator")
                },
            ).use { client ->
                val repository =
                    LyricsRepository(
                        FakeLyricsFetcher(emptyMap()),
                        LyricsTranslationClient(client),
                        aiModelCatalog = AiModelCatalog(client),
                    )
                val original = listOf(LyricsEntry(10_000, "안녕", translatedText = "Bonjour"))
                var failures = 0
                val prepared = repository.enrichLyricsEntries("song", original, allowRemoteTranslation = false) { failures++ }
                assertEquals("annyeong", prepared.single().romanizedText)
                assertEquals("Bonjour", prepared.single().translatedText)
                assertEquals(10_000L, prepared.single().time)
                assertEquals(0, requests)
                assertEquals(0, failures)
            }
        }

    @Test
    fun videoSpecificLyricsUseOnlyRemainingVideoProviders() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
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
            val client = HttpClient(MockEngine { error("No network expected") })
            try {
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client))
                for (enabled in listOf("NONE", "LYRICSPLUS,ZEMER")) {
                    Settings.shared.lyricsEnabledProviders = enabled
                    repository.getVideoSpecificLyrics("video", "Song", "Artist", null)
                    assertEquals(
                        listOf(LyricsProvider.YOUTUBESUBTITLE, LyricsProvider.YOUTUBE, LyricsProvider.SIMPMUSIC),
                        calls,
                    )
                    calls.clear()
                }
            } finally {
                client.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun deepLCacheChangesWhenTheContentRegionChanges() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        try {
            Settings.shared.lyricsAiProvider = "DeepL"
            Settings.shared.contentCountry = "GB"
            HttpClient(MockEngine { error("No network expected") }).use { client ->
                val repository = LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
                val british = repository.buildTranslationConfigKey()
                Settings.shared.contentCountry = "US"
                assertTrue(british != repository.buildTranslationConfigKey())
                Settings.shared.contentCountry = "GB"
                assertEquals(british, repository.buildTranslationConfigKey())
            }
        } finally {
            Settings.replaceSharedForTest(null)
        }
    }

    @Test
    fun detectorAndTranslationFailuresRetainOriginalLyricsAndRomanization() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                Settings.shared.lyricsAiTranslationEnabled = true
                Settings.shared.lyricsAiActivationMode = "Automatic"
                Settings.shared.lyricsAiProvider = "OpenRouter"
                Settings.shared.lyricsAiTargetLanguage = "fr"
                Settings.shared.lyricsAiApiKey = "test-key"
                HttpClient(MockEngine { respond(content = "", status = HttpStatusCode.Unauthorized) }).use { client ->
                    val repository = LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
                    val original = listOf(LyricsEntry(time = 0, text = "Hello world", romanizedText = "Provider romanization"))
                    var failures = 0
                    assertEquals(original, repository.enrichLyricsEntries("song", original) { failures++ })
                    assertEquals(1, failures)
                    Settings.shared.lyricsAiActivationMode = "Manual"
                    assertEquals(original, repository.enrichLyricsEntries("song", original, forceAiTranslation = true) { failures++ })
                    assertEquals(2, failures)
                }
            } finally {
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun manualTranslationDoesNotFetchModelsBeforeItIsRequested() =
        runBlocking {
            val settings = Settings.shared
            settings.lyricsAiTranslationEnabled = true
            settings.lyricsAiActivationMode = "Manual"
            settings.lyricsAiProvider = "OpenRouter"
            settings.lyricsAiTargetLanguage = "fr"
            settings.lyricsAiApiKey = "key"
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    error("Manual mode must not make requests without activation")
                },
            ).use { client ->
                val repository =
                    LyricsRepository(
                        FakeLyricsFetcher(emptyMap()),
                        LyricsTranslationClient(client),
                        aiModelCatalog = AiModelCatalog(client),
                    )
                val original = listOf(LyricsEntry(0, "Hello"))
                assertEquals(original, repository.enrichLyricsEntries("song", original) {})
                assertEquals(0, requests)
            }
        }

    @Test
    fun missingModelFallsBackOnlyForTheAccountWhoseListWasLoaded() =
        runBlocking {
            val preset = AiProviderPresets.find("OpenRouter")!!
            for (switchAccount in listOf(false, true)) {
                val settings = Settings.shared
                settings.lyricsAiTranslationEnabled = true
                settings.lyricsAiActivationMode = "Manual"
                settings.lyricsAiProvider = preset.id
                settings.lyricsAiBaseUrl = preset.endpoint
                settings.lyricsAiTargetLanguage = "fr"
                settings.lyricsAiModel = "account-model"
                settings.setLyricsAiApiKey(preset.id, "old-key")
                val expectedModel = if (switchAccount) "account-model" else preset.defaultModel
                HttpClient(
                    MockEngine { request ->
                        when {
                            request.url.host == "models.dev" -> {
                                respond("", HttpStatusCode.ServiceUnavailable)
                            }

                            request.url.encodedPath == "/api/v1/models" -> {
                                assertEquals("Bearer old-key", request.headers[HttpHeaders.Authorization])
                                if (switchAccount) settings.setLyricsAiApiKey(preset.id, "new-key")
                                respond(
                                    """{"data":[{"id":"${preset.defaultModel}","architecture":{"input_modalities":["text"],"output_modalities":["text"]},"pricing":{"prompt":"0.00000003","completion":"0.00000013"}}]}""",
                                )
                            }

                            else -> {
                                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                                assertEquals(expectedModel, body["model"]!!.jsonPrimitive.content)
                                assertEquals(
                                    "Bearer ${if (switchAccount) "new-key" else "old-key"}",
                                    request.headers[HttpHeaders.Authorization],
                                )
                                respond("""{"choices":[{"message":{"content":"{\"lines\":[\"Bonjour\"]}"}}]}""")
                            }
                        }
                    },
                ).use { client ->
                    val repository =
                        LyricsRepository(
                            FakeLyricsFetcher(emptyMap()),
                            LyricsTranslationClient(client),
                            aiModelCatalog = AiModelCatalog(client),
                        )
                    val translated =
                        repository.enrichLyricsEntries(
                            "song",
                            listOf(LyricsEntry(0, "Hello")),
                            forceAiTranslation = true,
                        ) {}
                    assertEquals("Bonjour", translated.single().translatedText)
                    assertEquals(expectedModel, settings.lyricsAiModel)
                }
            }
        }

    @Test
    fun oauthTranslationUsesOfficialEndpointAndNeverSendsManualKey() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                Settings.shared.lyricsAiTranslationEnabled = true
                Settings.shared.lyricsAiActivationMode = "Manual"
                Settings.shared.lyricsAiProvider = "OpenRouter"
                Settings.shared.lyricsAiTargetLanguage = "fr"
                Settings.shared.lyricsAiApiKey = "manual-key"
                Settings.shared.lyricsAiBaseUrl = "https://untrusted.example/chat"
                Settings.shared.lyricsOpenRouterOAuthKey = "oauth-key"
                HttpClient(
                    MockEngine { request ->
                        assertEquals("openrouter.ai", request.url.host)
                        assertEquals("/api/v1/chat/completions", request.url.encodedPath)
                        assertEquals("Bearer oauth-key", request.headers[HttpHeaders.Authorization])
                        respond("""{"choices":[{"message":{"content":"{\"lines\":[\"Bonjour\"]}"}}]}""")
                    },
                ).use { client ->
                    val repository = LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
                    val original = listOf(LyricsEntry(time = 0, text = "Hello"))
                    assertEquals(
                        "Bonjour",
                        repository.enrichLyricsEntries("song", original, forceAiTranslation = true) {}.single().translatedText,
                    )
                }
            } finally {
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun oauthTranslationCacheTracksEffectiveEndpoint() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        try {
            val settings = Settings.shared
            settings.lyricsAiProvider = "OpenRouter"
            settings.lyricsAiBaseUrl = "https://custom.example/chat"
            val client = HttpClient(MockEngine { error("Translation client must not be called") })
            client.use {
                val repository = LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
                val customKey = repository.buildTranslationConfigKey()
                settings.lyricsOpenRouterOAuthKey = "oauth-key"
                val oauthKey = repository.buildTranslationConfigKey()
                assertTrue(oauthKey.contains("https://openrouter.ai/api/v1/chat/completions"))
                assertFalse(oauthKey.contains("custom.example"))
                assertTrue(oauthKey != customKey)
                settings.lyricsOpenRouterOAuthKey = ""
                assertEquals(customKey, repository.buildTranslationConfigKey())
            }
        } finally {
            Settings.replaceSharedForTest(null)
        }
    }

    @Test
    fun failedTranslationCanSucceedOnRetry() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                Settings.shared.lyricsAiTranslationEnabled = true
                Settings.shared.lyricsAiProvider = "DeepL"
                Settings.shared.lyricsAiTargetLanguage = "fr"
                HttpClient(MockEngine { respond("""{"translations":[{"text":"Bonjour"}]}""") }) {
                    install(ContentNegotiation) { json() }
                }.use { client ->
                    val repository = LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
                    val original = listOf(LyricsEntry(time = 0, text = "Hello"))
                    var failures = 0
                    assertEquals(original, repository.enrichLyricsEntries("song", original) { failures++ })
                    assertEquals(1, failures)

                    Settings.shared.lyricsDeepLApiKey = "test-key"
                    val translated = repository.enrichLyricsEntries("song", original) { failures++ }
                    assertEquals(1, failures)
                    assertEquals("Bonjour", translated.single().translatedText)
                }
            } finally {
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun automaticTranslationSelectsMixedLinesAndPreservesTargetLanguageLines() =
        runBlocking {
            Settings.shared.lyricsAiTranslationEnabled = true
            Settings.shared.lyricsAiActivationMode = "Automatic"
            Settings.shared.lyricsAiProvider = "OpenRouter"
            Settings.shared.lyricsAiTargetLanguage = "en"
            Settings.shared.lyricsAiApiKey = "test-key"
            Settings.shared.lyricsRomanizationEnabled = false
            val mixed = "I will remember this song forever 忘れない"
            val target = "I will remember this song forever"
            for (withBackground in listOf(false, true)) {
                var requests = 0
                HttpClient(
                    MockEngine { request ->
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val prompt =
                            body
                                .getValue("messages")
                                .jsonArray
                                .last()
                                .jsonObject
                                .getValue("content")
                                .jsonPrimitive.content
                        val input =
                            Json
                                .parseToJsonElement(prompt.substringAfter("\n\n"))
                                .jsonObject
                                .getValue("lines")
                                .jsonArray
                        val sources = input.map { it.jsonPrimitive.content }
                        val payload =
                            if (requests++ == 0) {
                                assertEquals(listOf(mixed, target), sources)
                                """{"lines":["忘れない",""]}"""
                            } else {
                                assertEquals(listOf(mixed), sources)
                                """{"lines":["I will remember this song forever, I will not forget"]}"""
                            }
                        respond("{\"choices\":[{\"message\":{\"content\":${JsonPrimitive(payload)}}}]}")
                    },
                ).use { client ->
                    val repository = LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
                    val entries =
                        listOf(
                            LyricsEntry(0, mixed, backgroundText = target.takeIf { withBackground }),
                            LyricsEntry(1000, target),
                        )
                    val result = repository.enrichLyricsEntries("song", entries)
                    assertEquals("I will remember this song forever, I will not forget", result[0].translatedText)
                    assertNull(result[0].backgroundTranslatedText)
                    assertEquals(entries[1], result[1])
                    assertEquals(2, requests)
                }
            }
        }

    @Test
    fun mainAndBackgroundVocalsTranslateIndependently() =
        runBlocking {
            Settings.shared.lyricsAiTranslationEnabled = true
            Settings.shared.lyricsAiActivationMode = "Automatic"
            Settings.shared.lyricsAiProvider = "OpenRouter"
            Settings.shared.lyricsAiTargetLanguage = "fr"
            Settings.shared.lyricsAiApiKey = "test-key"
            Settings.shared.lyricsRomanizationEnabled = false
            for ((mainNeeded, backgroundNeeded) in listOf(true to false, false to true, true to true, false to false)) {
                val expectedSources =
                    listOfNotNull(
                        "Hello".takeIf { mainNeeded },
                        "Hello".takeIf { mainNeeded },
                        "Goodbye".takeIf { backgroundNeeded },
                        "Goodbye".takeIf { backgroundNeeded },
                        "Goodbye".takeIf { backgroundNeeded },
                        "Goodbye".takeIf { backgroundNeeded },
                    )
                var requests = 0
                HttpClient(
                    MockEngine { request ->
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val prompt =
                            body
                                .getValue("messages")
                                .jsonArray
                                .last()
                                .jsonObject
                                .getValue("content")
                                .jsonPrimitive.content
                        val payload =
                            if (requests++ == 0) {
                                assertTrue(prompt.contains("Hello"))
                                assertTrue(prompt.contains("Goodbye"))
                                "{\"lines\":${JsonArray(
                                    listOf(if (mainNeeded) "Hello" else "", if (backgroundNeeded) "Goodbye" else "").map(::JsonPrimitive),
                                )}}"
                            } else {
                                val input =
                                    Json
                                        .parseToJsonElement(prompt.substringAfter("\n\n"))
                                        .jsonObject
                                        .getValue("lines")
                                        .jsonArray
                                assertEquals(expectedSources, input.map { it.jsonPrimitive.content })
                                val output = expectedSources.map { if (it == "Hello") "Bonjour" else "Au revoir" }
                                "{\"lines\":${JsonArray(output.map(::JsonPrimitive))}}"
                            }
                        respond("{\"choices\":[{\"message\":{\"content\":${JsonPrimitive(payload)}}}]}")
                    },
                ).use { client ->
                    val repository = LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
                    val entries =
                        listOf(
                            LyricsEntry(0, ""),
                            LyricsEntry(
                                1000,
                                "Hello Goodbye",
                                words =
                                    listOf(
                                        WordTimestamp("Hello", 1.0, 2.0),
                                        WordTimestamp("Goodbye", 1.5, 2.5, isBackground = true),
                                    ),
                            ),
                            LyricsEntry(3000, "Hello", backgroundText = "Goodbye"),
                            LyricsEntry(4000, "Goodbye", isBackground = true),
                            LyricsEntry(5000, "", backgroundText = "Goodbye"),
                        )
                    val progress = mutableListOf<LyricsTranslationProgress>()
                    val result = repository.enrichLyricsEntries("song", entries, onTranslationProgress = { progress += it })
                    val pending = progress.firstOrNull { it.isTranslating } ?: LyricsTranslationProgress()
                    assertEquals(
                        buildSet {
                            if (mainNeeded) addAll(listOf(1, 2))
                            if (backgroundNeeded) add(3)
                        },
                        pending.lines,
                    )
                    assertEquals(if (backgroundNeeded) setOf(1, 2, 4) else emptySet(), pending.backgroundLines)
                    assertFalse(progress.last().isTranslating)
                    assertEquals(entries[0], result[0])
                    for (index in 1..2) {
                        assertEquals("Bonjour".takeIf { mainNeeded }, result[index].translatedText)
                        assertEquals("Au revoir".takeIf { backgroundNeeded }, result[index].backgroundTranslatedText)
                    }
                    assertEquals("Au revoir".takeIf { backgroundNeeded }, result[3].translatedText)
                    assertNull(result[3].backgroundTranslatedText)
                    assertNull(result[4].translatedText)
                    assertEquals("Au revoir".takeIf { backgroundNeeded }, result[4].backgroundTranslatedText)
                    assertEquals(if (expectedSources.isEmpty()) 1 else 2, requests)
                }
            }
        }

    @Test
    fun translationExcludesInlineBackgroundVocals() {
        val entry =
            LyricsEntry(
                time = 0,
                text = "Main (background)",
                words =
                    listOf(
                        WordTimestamp("Main", 0.0, 1.0),
                        WordTimestamp("Привет", 0.0, 1.0, isBackground = true),
                    ),
            )

        assertEquals("Main", translationSourceLine(entry))
        assertEquals(listOf("Main"), translationSourceWords(entry))
        val merged = assertNotNull(alignRomanizedWords(entry, "Mein", listOf("Mein")))
        assertEquals(listOf("Mein", "Привет"), merged.map(WordTimestamp::text))
        assertEquals(listOf(false, true), merged.map(WordTimestamp::isBackground))

        val backgroundLine =
            LyricsEntry(
                time = 0,
                text = "Привет",
                isBackground = true,
                words = listOf(WordTimestamp("Привет", 0.0, 1.0, isBackground = true)),
            )
        assertEquals("", translationSourceLine(backgroundLine))
        assertEquals(emptyList(), translationSourceWords(backgroundLine))
        assertEquals(listOf("Привет"), alignRomanizedWords(backgroundLine, "", emptyList())?.map(WordTimestamp::text))
    }

    @Test
    fun lineRomanizationInheritsWordTiming() {
        val originalWords =
            listOf(
                WordTimestamp("안", 1.0, 1.5, hasTrailingSpace = false),
                WordTimestamp("녕", 1.5, 2.0, hasTrailingSpace = false),
            )
        val entry = LyricsEntry(time = 1_000, text = "안녕", words = originalWords, romanizedText = "annyeong")
        val romanizedWords = assertNotNull(entry.withAlignedRomanizedWords().romanizedWords)

        assertEquals(listOf("an", "nyeong"), romanizedWords.map(WordTimestamp::text))
        assertEquals(originalWords.map(WordTimestamp::startTime), romanizedWords.map(WordTimestamp::startTime))
        assertEquals(originalWords.map(WordTimestamp::endTime), romanizedWords.map(WordTimestamp::endTime))
    }

    @Test
    fun romanizationKeepsProviderWordTextAndTimingsWhenLineTextIsMissing() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                Settings.shared.lyricsRomanizationEnabled = true
                Settings.shared.lyricsRomanizeLanguages = "Japanese:true"
                val sourceRomanizedWords = listOf(WordTimestamp("kimi", 2.0, 3.0, hasTrailingSpace = true))
                val entry =
                    LyricsEntry(
                        time = 1_000,
                        text = "君",
                        words = listOf(WordTimestamp("君", 2.0, 3.0, hasTrailingSpace = true)),
                        romanizedWords = sourceRomanizedWords,
                    )
                val officialLine =
                    LyricsEntry(
                        time = 4_000,
                        text = "東京",
                        words = listOf(WordTimestamp("東京", 4.0, 5.0)),
                        romanizedText = "toukyou",
                        romanizedWords = listOf(WordTimestamp("toukyou", 4.0, 5.0)),
                    )
                HttpClient(MockEngine { error("Translation client must not be called") }).use { client ->
                    val result =
                        LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
                            .enrichLyricsEntries("song", listOf(entry, officialLine))

                    assertEquals("kimi", result[0].romanizedText)
                    assertEquals(sourceRomanizedWords, result[0].romanizedWords)
                    assertEquals(entry.words?.single()?.startTime, result[0].romanizedWords?.single()?.startTime)
                    assertEquals(entry.words?.single()?.endTime, result[0].romanizedWords?.single()?.endTime)
                    assertEquals("toukyou", result[1].romanizedText)
                    assertEquals(officialLine.romanizedWords, result[1].romanizedWords)
                }
            } finally {
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun unusableProviderWordRomanizationDoesNotSuppressLocalRomanization() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                Settings.shared.lyricsRomanizationEnabled = true
                Settings.shared.lyricsRomanizeLanguages = "Arabic:true"
                val providerWords = listOf(WordTimestamp("", 0.0, 0.1, isBackground = true))
                val entry =
                    LyricsEntry(
                        time = 0,
                        text = "يا حبيبي",
                        words = listOf(WordTimestamp("يا حبيبي", 0.0, 1.0)),
                        romanizedWords = providerWords,
                    )
                HttpClient(MockEngine { error("Translation client must not be called") }).use { client ->
                    val result =
                        LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
                            .enrichLyricsEntries("song", listOf(entry))
                            .single()

                    assertEquals("ya habibi", result.romanizedText)
                    assertEquals(listOf("ya habibi"), result.romanizedWords?.map(WordTimestamp::text))
                    assertEquals(0.0, result.romanizedWords?.single()?.startTime)
                    assertEquals(1.0, result.romanizedWords?.single()?.endTime)
                }
            } finally {
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun partialProviderWordRomanizationIsCompletedLocally() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                Settings.shared.lyricsRomanizationEnabled = true
                Settings.shared.lyricsRomanizeLanguages = "Arabic:true"
                val sourceWords =
                    listOf(
                        WordTimestamp("يا", 0.0, 0.3, hasTrailingSpace = true),
                        WordTimestamp("حبيبي", 0.3, 0.7, hasTrailingSpace = true),
                        WordTimestamp("السلام", 0.7, 1.1),
                    )
                val providerWord = WordTimestamp("ya", 0.05, 0.25, hasTrailingSpace = true)
                val entry =
                    LyricsEntry(
                        time = 0,
                        text = "يا حبيبي السلام",
                        words = sourceWords,
                        romanizedWords = listOf(providerWord),
                    )
                HttpClient(MockEngine { error("Translation client must not be called") }).use { client ->
                    val result =
                        LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
                            .enrichLyricsEntries("song", listOf(entry))
                            .single()

                    val romanizedWords = assertNotNull(result.romanizedWords)
                    assertEquals(
                        "ya habibi as-salam",
                        result.romanizedText
                            ?.trim()
                            ?.split(Regex("\\s+"))
                            ?.joinToString(" "),
                    )
                    assertEquals(
                        "ya habibi as-salam",
                        romanizedWords
                            .joinToString(" ") { it.text }
                            .trim()
                            .split(Regex("\\s+"))
                            .joinToString(" "),
                    )
                    assertEquals(
                        listOf(providerWord.startTime) + sourceWords.drop(1).map(WordTimestamp::startTime),
                        romanizedWords.map(WordTimestamp::startTime),
                    )
                    assertEquals(
                        listOf(providerWord.endTime) + sourceWords.drop(1).map(WordTimestamp::endTime),
                        romanizedWords.map(WordTimestamp::endTime),
                    )
                }
            } finally {
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun providerRomanizationWithDifferentWordSegmentationIsPreserved() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                Settings.shared.lyricsRomanizationEnabled = true
                Settings.shared.lyricsRomanizeLanguages = "Japanese:true"
                val providerWords =
                    listOf(
                        WordTimestamp("tou", 0.0, 0.4, hasTrailingSpace = true),
                        WordTimestamp("kyou", 0.4, 1.0),
                    )
                val entry =
                    LyricsEntry(
                        time = 0,
                        text = "東京",
                        words = listOf(WordTimestamp("東京", 0.0, 1.0)),
                        romanizedWords = providerWords,
                    )
                HttpClient(MockEngine { error("Translation client must not be called") }).use { client ->
                    val result =
                        LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
                            .enrichLyricsEntries("song", listOf(entry))
                            .single()

                    assertEquals("tou kyou", result.romanizedText)
                    assertEquals(providerWords, result.romanizedWords)
                }
            } finally {
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun incompleteLongerProviderSegmentationIsReplacedByGeneratedAlignment() {
        val entry =
            LyricsEntry(
                time = 0,
                text = "東京",
                words = listOf(WordTimestamp("東京", 0.0, 1.0)),
                romanizedText = "toukyou",
                romanizedWords =
                    listOf(
                        WordTimestamp("tou", 0.0, 0.5, hasTrailingSpace = true),
                        WordTimestamp("", 0.5, 1.0),
                    ),
            )

        val aligned = assertNotNull(entry.withAlignedRomanizedWords().romanizedWords)

        assertEquals(listOf("toukyou"), aligned.map(WordTimestamp::text))
        assertEquals(listOf(0.0), aligned.map(WordTimestamp::startTime))
        assertEquals(listOf(1.0), aligned.map(WordTimestamp::endTime))
    }

    @Test
    fun aiDictionaryPreparationUsesCompactLineIndexAfterFiltering() {
        val sourceEntries =
            listOf(
                LyricsEntry(time = 0, text = ""),
                LyricsEntry(
                    time = 1_000,
                    text = "東京",
                    words = listOf(WordTimestamp("東京", 1.0, 2.0)),
                ),
            )
        val nonEmpty =
            sourceEntries.mapIndexedNotNull { index, entry ->
                if (entry.text.isNotBlank() || !entry.words.isNullOrEmpty()) index to entry else null
            }

        assertEquals(1, nonEmpty.single().first)
        assertTrue(needsJapaneseDictionaryForAiAlignment(nonEmpty, listOf("toukyou"), null))
    }

    @Test
    fun providerRomanizedLineDownloadsDictionaryOnlyWhenRomanizationIsEnabled() {
        val entry =
            LyricsEntry(
                time = 0,
                text = "東京",
                words = listOf(WordTimestamp("東京", 0.0, 1.0)),
                romanizedText = "toukyou",
            )

        assertFalse(needsJapaneseDictionaryPreparation(entry, romanizationEnabled = false))
        assertFalse(needsJapaneseDictionaryPreparation(entry.copy(romanizedText = null), romanizationEnabled = false))
        assertTrue(needsJapaneseDictionaryPreparation(entry, romanizationEnabled = true))
    }

    @Test
    fun existingRomanizedLineWithoutWordDataStillNeedsAlignment() {
        val entry =
            LyricsEntry(
                time = 0,
                text = "君のことが好き",
                words = listOf(WordTimestamp("君の", 0.0, 1.0), WordTimestamp("ことが好き", 1.0, 2.0)),
                romanizedText = "kimi no koto ga suki",
            )

        assertTrue(needsLocalRomanizationOrAlignment(entry))
    }

    @Test
    fun lineRomanizationWithoutSourceWordTimingsNeedsNoLocalWork() {
        val cachedEntry = LyricsEntry(time = 0, text = "東京", romanizedText = "toukyou")

        assertFalse(needsLocalRomanizationOrAlignment(cachedEntry))
    }

    @Test
    fun dictionaryDetectionTextIncludesBackgroundWords() {
        val inlineBackground =
            LyricsEntry(
                time = 0,
                text = "Main",
                words =
                    listOf(
                        WordTimestamp("Main", 0.0, 0.5),
                        WordTimestamp("君", 0.5, 1.0, isBackground = true),
                    ),
            )
        val backgroundEntry =
            LyricsEntry(
                time = 1,
                text = "愛",
                isBackground = true,
                words = listOf(WordTimestamp("愛", 1.0, 1.5, isBackground = true)),
            )

        val detectionText = romanizationDetectionText(listOf(inlineBackground, backgroundEntry))
        assertEquals("Main", translationSourceLine(inlineBackground))
        assertTrue(detectionText.contains("君"))
        assertTrue(detectionText.contains("愛"))
    }

    @Test
    fun storedBetterLyricsOrderWinsBeforeRicherFallbacks() =
        runTest {
            Settings.shared.lyricsProviderOrder = "BetterLyrics,Lyprio"
            Settings.shared.lyricsEnabledProviders = "BETTERLYRICS,LYPRIO"
            val calls = mutableListOf<LyricsProvider>()
            val preferred = LyricsWithProvider("[00:01]Preferred original lyrics", "BetterLyrics")
            val fallback =
                LyricsWithProvider(
                    MlxBuilder().line(1_000, 2_000, words = listOf(MlxWord("Fallback words", 1_000, 2_000))).build(),
                    "Lyprio/KUGOU",
                )
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
                        if (provider == LyricsProvider.BETTERLYRICS) {
                            delay(4_000)
                            return preferred
                        }
                        return fallback
                    }
                }
            HttpClient(MockEngine { error("No network expected") }).use { client ->
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client))
                val updates = mutableListOf<LyricsWithProvider>()
                assertEquals(preferred, repository.getLyrics("video", "Song", "Artist", null, onLyricsAvailable = { updates += it }))
                assertEquals(listOf(preferred), updates)
                assertEquals(listOf(LyricsProvider.BETTERLYRICS), calls)
                assertEquals(4_000L, testScheduler.currentTime)
            }
        }

    @Test
    fun plainResultIsPublishedWhileSearchContinuesToTheFirstSyncedProvider() =
        runTest {
            val providers =
                listOf(
                    LyricsProvider.LRCLIB,
                    LyricsProvider.SIMPMUSIC,
                    LyricsProvider.KUGOU,
                    LyricsProvider.BETTERLYRICS,
                    LyricsProvider.PAXSENIX,
                )
            val results =
                listOf(
                    "[ar:Metadata without lyrics]",
                    "Usable plain lyrics",
                    "[00:01]Usable synced lyrics",
                    "Another plain result",
                    MlxBuilder()
                        .meta("Words")
                        .line(1_000, 2_000, words = listOf(MlxWord("Sung words", 1_000, 2_000)))
                        .build(),
                )
            val updates = mutableListOf<Pair<Long, LyricsWithProvider>>()
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
                        if (provider == LyricsProvider.PAXSENIX) delay(5_000)
                        return LyricsWithProvider(results[providers.indexOf(provider)], provider.name)
                    }
                }
            HttpClient(MockEngine { error("No network expected") }).use { client ->
                val result =
                    LyricsRepository(fetcher, LyricsTranslationClient(client)).getLyrics(
                        "video",
                        "Title",
                        "Artist",
                        null,
                        preferredProviders = providers,
                        onLyricsAvailable = { updates += testScheduler.currentTime to it },
                    )
                assertEquals(listOf(0L, 0L), updates.map { it.first })
                assertEquals(listOf(results[1], results[2]), updates.map { it.second.lyrics })
                assertEquals(updates.last().second, result)
            }
        }

    @Test
    fun videoSpecificLyricsStopAtFirstUsableProvider() =
        runTest {
            Settings.shared.lyricsEnabledProviders = "NONE"
            val updates = mutableListOf<LyricsWithProvider>()
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
                            LyricsProvider.YOUTUBESUBTITLE -> {
                                null
                            }

                            LyricsProvider.YOUTUBE -> {
                                LyricsWithProvider("[00:01]Video lyrics", provider.name)
                            }

                            else -> {
                                assertEquals("[00:01]Video lyrics", updates.single().lyrics)
                                awaitCancellation()
                            }
                        }
                }
            HttpClient(MockEngine { error("No network expected") }).use { client ->
                val result =
                    LyricsRepository(fetcher, LyricsTranslationClient(client)).getVideoSpecificLyrics(
                        "video",
                        "Title",
                        "Artist",
                        null,
                        onLyricsAvailable = {
                            assertEquals(0L, testScheduler.currentTime)
                            updates += it
                        },
                    )
                assertEquals(0L, testScheduler.currentTime)
                assertEquals(updates.single(), result)
            }
        }

    @Test
    fun explicitProviderSearchWaitsForEarlierProviderTimeout() =
        runTest {
            val lyrics = MlxBuilder().meta("Fallback").line(1_000, "Fallback").build()
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
                        if (provider == LyricsProvider.YOUTUBE) {
                            delay(20_000.milliseconds)
                            return null
                        }
                        assertEquals(10_000L, testScheduler.currentTime)
                        return LyricsWithProvider(lyrics, "Fallback")
                    }
                }
            val client = HttpClient(MockEngine { error("Translation client must not be called") })

            client.use {
                val result =
                    LyricsRepository(fetcher, LyricsTranslationClient(client)).getLyrics(
                        videoId = "video",
                        title = "Title",
                        artist = "Artist",
                        duration = 180_000,
                        preferredProviders = listOf(LyricsProvider.YOUTUBE, LyricsProvider.PAXSENIX),
                    )

                assertEquals("Fallback", result?.provider)
                assertEquals(listOf(LyricsProvider.YOUTUBE, LyricsProvider.PAXSENIX), calls)
                assertEquals(10_000L, testScheduler.currentTime)
            }
        }

    @Test
    fun explicitProviderSearchTimesOutEachProviderBeforeTryingTheNext() =
        runTest {
            val lyrics = MlxBuilder().meta("Later").line(1_000, "Later").build()
            val leadingProviders =
                listOf(
                    LyricsProvider.BETTERLYRICS,
                    LyricsProvider.UNISON,
                    LyricsProvider.SIMPMUSIC,
                    LyricsProvider.LRCLIB,
                    LyricsProvider.KUGOU,
                )
            val providers = leadingProviders + LyricsProvider.PAXSENIX
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
                        if (provider in leadingProviders) {
                            delay(20_000.milliseconds)
                            return null
                        }
                        return LyricsWithProvider(lyrics, "Later")
                    }
                }
            val client = HttpClient(MockEngine { error("Translation client must not be called") })

            client.use {
                val result =
                    LyricsRepository(fetcher, LyricsTranslationClient(client)).getLyrics(
                        videoId = "video",
                        title = "Title",
                        artist = "Artist",
                        duration = 180_000,
                        preferredProviders = providers,
                    )

                assertEquals("Later", result?.provider)
                assertEquals(providers, calls)
                assertEquals(10_000L * leadingProviders.size, testScheduler.currentTime)
            }
        }

    @Test
    fun automaticSearchWaitsForLyprioTimeoutBeforeStartingFallback() =
        runTest {
            val lyrics = MlxBuilder().meta("Alternate").line(1_000, "Alternate").build()
            val lyprioCancelled = CompletableDeferred<Unit>()
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
                    ): LyricsWithProvider =
                        when (provider) {
                            LyricsProvider.LYPRIO -> {
                                try {
                                    awaitCancellation()
                                } finally {
                                    lyprioCancelled.complete(Unit)
                                }
                            }

                            LyricsProvider.PAXSENIX -> {
                                assertEquals(LyprioLyricsProvider.SEARCH_TIMEOUT_MS, testScheduler.currentTime)
                                LyricsWithProvider(lyrics, "Alternate")
                            }

                            else -> {
                                error("Unexpected provider: $provider")
                            }
                        }
                }
            val client = HttpClient(MockEngine { error("Translation client must not be called") })

            client.use {
                val result =
                    LyricsRepository(fetcher, LyricsTranslationClient(client)).getLyrics(
                        videoId = "video",
                        title = "Title",
                        artist = "Artist",
                        duration = 180_000,
                        preferredProviders = listOf(LyricsProvider.LYPRIO, LyricsProvider.PAXSENIX),
                    )

                assertEquals("Alternate", result?.provider)
                assertEquals(LyprioLyricsProvider.SEARCH_TIMEOUT_MS, testScheduler.currentTime)
                assertTrue(lyprioCancelled.isCompleted)
            }
        }

    @Test
    fun automaticSearchWaitsForAnUnusableLyprioResultBeforeStartingFallback() =
        runTest {
            for (lyprioResult in listOf(null, LyricsWithProvider("[ar:Metadata without lyrics]", "Lyprio"))) {
                val startedAt = testScheduler.currentTime
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
                            if (provider == LyricsProvider.LYPRIO) {
                                delay(4_000.milliseconds)
                                return lyprioResult
                            }
                            assertEquals(startedAt + 4_000, testScheduler.currentTime)
                            return LyricsWithProvider("[00:01]Fallback lyrics", "PAXSENIX")
                        }
                    }
                HttpClient(MockEngine { error("Unexpected translation request") }).use { client ->
                    val providers = listOf(LyricsProvider.LYPRIO, LyricsProvider.PAXSENIX)
                    val result =
                        LyricsRepository(fetcher, LyricsTranslationClient(client)).getLyrics(
                            "video",
                            "Song",
                            "Artist",
                            null,
                            preferredProviders = providers,
                        )
                    assertEquals("PAXSENIX", result?.provider)
                    assertEquals(providers, calls)
                    assertEquals(startedAt + 4_000, testScheduler.currentTime)
                }
            }
        }

    @Test
    fun slowLyprioSkipsFallbacksRegardlessOfTheirTiming() =
        runTest {
            val preferred = MlxBuilder().meta("Lyprio").line(1_000, "Preferred line").build()
            val fallbacks =
                listOf(
                    MlxBuilder().meta("Fallback").unsyncedLine("Plain").build(),
                    MlxBuilder().meta("Fallback").line(1_000, "Line").build(),
                    MlxBuilder().meta("Fallback").line(1_000, 2_000, words = listOf(MlxWord("Fallback", 1_000, 2_000))).build(),
                )
            for (fallback in fallbacks) {
                val startedAt = testScheduler.currentTime
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
                            if (provider == LyricsProvider.LYPRIO) {
                                delay(4_000.milliseconds)
                                return LyricsWithProvider(preferred, "Lyprio")
                            }
                            return LyricsWithProvider(fallback, "Fallback")
                        }
                    }
                HttpClient(MockEngine { error("No network expected") }).use { client ->
                    val providers = listOf(LyricsProvider.LYPRIO, LyricsProvider.PAXSENIX)
                    val result =
                        LyricsRepository(fetcher, LyricsTranslationClient(client)).getLyrics(
                            "video",
                            "Song",
                            "Artist",
                            null,
                            preferredProviders = providers,
                        )
                    assertEquals("Lyprio", result?.provider)
                    assertEquals(preferred, result?.lyrics)
                    assertEquals(listOf(LyricsProvider.LYPRIO), calls)
                    assertEquals(startedAt + 4_000, testScheduler.currentTime)
                }
            }
        }

    @Test
    fun cancellingAutomaticSearchCancelsLyprioWithoutStartingFallbacks() =
        runTest {
            val cancelled = mutableSetOf<LyricsProvider>()
            val lyprioStarted = CompletableDeferred<Unit>()
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
                        if (provider == LyricsProvider.LYPRIO) lyprioStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled += provider
                        }
                    }
                }
            HttpClient(MockEngine { error("Translation client must not be called") }).use { client ->
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client))
                val job =
                    launch {
                        repository.getLyrics(
                            "video",
                            "Title",
                            "Artist",
                            null,
                            preferredProviders = listOf(LyricsProvider.LYPRIO, LyricsProvider.PAXSENIX),
                        )
                    }
                lyprioStarted.await()
                delay(4_000.milliseconds)
                job.cancel()
                job.join()
                assertTrue(job.isCancelled)
                assertEquals(setOf(LyricsProvider.LYPRIO), cancelled)
                assertEquals(4_000L, testScheduler.currentTime)
            }
        }

    @Test
    fun automaticSearchWaitsForSlowLyprioWithoutRequestingFallbacks() =
        runTest {
            val lyrics = MlxBuilder().meta("Lyprio").line(1_000, "Fallback").build()
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
                        return when (provider) {
                            LyricsProvider.LYPRIO -> {
                                delay(35_000.milliseconds)
                                LyricsWithProvider(lyrics, "Lyprio")
                            }

                            LyricsProvider.PAXSENIX -> {
                                null
                            }

                            else -> {
                                error("Unexpected provider: $provider")
                            }
                        }
                    }
                }
            val client = HttpClient(MockEngine { error("Translation client must not be called") })

            client.use {
                val result =
                    LyricsRepository(fetcher, LyricsTranslationClient(client)).getLyrics(
                        videoId = "video",
                        title = "Title",
                        artist = "Artist",
                        duration = 180_000,
                        preferredProviders = listOf(LyricsProvider.LYPRIO, LyricsProvider.PAXSENIX),
                    )

                assertEquals("Lyprio", result?.provider)
                assertEquals(35_000L, testScheduler.currentTime)
                assertEquals(listOf(LyricsProvider.LYPRIO), calls)
            }
        }

    @Test
    fun orderedSelectionSearchesPastInvalidLyricsFailuresAndTimeouts() =
        runTest {
            val calls = mutableListOf<LyricsProvider>()
            val initial = "[ar:Metadata without lyrics]"
            val wordSynced =
                MlxBuilder()
                    .meta("Word")
                    .line(
                        startMs = 1_000,
                        endMs = 2_000,
                        words = listOf(MlxWord("Word", 1_000, 2_000)),
                    ).build()
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
                        return when (provider) {
                            LyricsProvider.YOUTUBE -> {
                                LyricsWithProvider(initial, "Initial")
                            }

                            LyricsProvider.PAXSENIX -> {
                                delay(100.milliseconds)
                                LyricsWithProvider(wordSynced, "Word")
                            }

                            LyricsProvider.UNISON -> {
                                error("Provider failed")
                            }

                            LyricsProvider.BETTERLYRICS, LyricsProvider.KUGOU -> {
                                delay(10_000.milliseconds)
                                null
                            }

                            else -> {
                                null
                            }
                        }
                    }
                }
            val client = HttpClient(MockEngine { error("Translation client must not be called") })

            client.use {
                val result =
                    LyricsRepository(fetcher, LyricsTranslationClient(client)).getLyrics(
                        videoId = "video",
                        title = "Title",
                        artist = "Artist",
                        duration = 180_000,
                        preferredProviders =
                            listOf(
                                LyricsProvider.YOUTUBE,
                                LyricsProvider.BETTERLYRICS,
                                LyricsProvider.UNISON,
                                LyricsProvider.PAXSENIX,
                                LyricsProvider.KUGOU,
                            ),
                    )

                assertEquals("Word", result?.provider)
                assertEquals(
                    listOf(LyricsProvider.YOUTUBE, LyricsProvider.BETTERLYRICS, LyricsProvider.UNISON, LyricsProvider.PAXSENIX),
                    calls,
                )
                assertEquals(10_100L, testScheduler.currentTime)
            }
        }

    @Test
    fun providerPriorityDoesNotRaceLaterResults() =
        runTest {
            val firstLine = MlxBuilder().meta("First").line(1_000, "First").build()
            val fasterLine = MlxBuilder().meta("Faster").line(1_000, "Faster").build()
            val initial = "[ar:Metadata without lyrics]"
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
                            LyricsProvider.YOUTUBE -> {
                                LyricsWithProvider(initial, "Initial")
                            }

                            LyricsProvider.BETTERLYRICS -> {
                                delay(200.milliseconds)
                                LyricsWithProvider(firstLine, "First")
                            }

                            LyricsProvider.UNISON -> {
                                delay(10.milliseconds)
                                LyricsWithProvider(fasterLine, "Faster")
                            }

                            LyricsProvider.KUGOU -> {
                                delay(10_000.milliseconds)
                                null
                            }

                            else -> {
                                null
                            }
                        }
                }
            val client = HttpClient(MockEngine { error("Translation client must not be called") })

            client.use {
                val result =
                    LyricsRepository(fetcher, LyricsTranslationClient(client)).getLyrics(
                        videoId = "video",
                        title = "Title",
                        artist = "Artist",
                        duration = 180_000,
                        preferredProviders =
                            listOf(
                                LyricsProvider.YOUTUBE,
                                LyricsProvider.BETTERLYRICS,
                                LyricsProvider.UNISON,
                                LyricsProvider.KUGOU,
                            ),
                    )

                assertEquals("First", result?.provider)
                assertEquals(200L, testScheduler.currentTime)
            }
        }

    @Test
    fun sequentialSearchPropagatesCallerCancellationWithoutStartingFallbacks() =
        runTest {
            val started = CompletableDeferred<Unit>()
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
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            val client = HttpClient(MockEngine { error("Translation client must not be called") })

            client.use {
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client))
                val job =
                    launch {
                        repository.getLyrics(
                            videoId = "video",
                            title = "Title",
                            artist = "Artist",
                            duration = 180_000,
                            preferredProviders = listOf(LyricsProvider.YOUTUBE, LyricsProvider.PAXSENIX),
                        )
                    }
                started.await()
                job.cancel()
                job.join()
                assertTrue(job.isCancelled)
                assertEquals(listOf(LyricsProvider.YOUTUBE), calls)
            }
        }

    @Test
    fun automaticDefaultRespectsEnabledProviderSettings() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            Settings.shared.lyricsProviderOrder = "PAXSENIX,LYPRIO"
            Settings.shared.lyricsEnabledProviders = "PAXSENIX"
            val calls = mutableListOf<LyricsProvider>()
            val lyrics = MlxBuilder().meta("Paxsenix").line(1_000, "Line").build()
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
                        return if (provider == LyricsProvider.PAXSENIX) LyricsWithProvider(lyrics, "Paxsenix") else null
                    }
                }
            val client = HttpClient(MockEngine { error("Translation client must not be called") })

            try {
                client.use {
                    val result =
                        LyricsRepository(fetcher, LyricsTranslationClient(client)).getLyrics(
                            videoId = "video",
                            title = "Title",
                            artist = "Artist",
                            duration = 180_000,
                        )

                    assertEquals("Paxsenix", result?.provider)
                    assertEquals(
                        listOf(LyricsProvider.PAXSENIX),
                        calls,
                    )
                    assertTrue(LyricsProvider.LYPRIO !in calls)
                }
            } finally {
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun orderedSelectionUsesProviderResultsAndKeepsOrderForEveryTimingKind() =
        runBlocking {
            val plain = MlxBuilder().meta("Plain").unsyncedLine("Plain text").build()
            val line = MlxBuilder().meta("Line").line(1_000, "Line text").build()
            val word = MlxBuilder().meta("Word").line(1_000, 2_000, words = listOf(MlxWord("Word", 1_000, 2_000))).build()
            HttpClient(MockEngine { error("Translation client must not be called") }).use { client ->
                for (lyrics in listOf(plain, line, word)) {
                    val first = LyricsWithProvider(lyrics, "First")
                    val second = LyricsWithProvider(lyrics, "Second")
                    val fetcher = FakeLyricsFetcher(mapOf(LyricsProvider.LRCLIB to first, LyricsProvider.PAXSENIX to second))
                    val repository = LyricsRepository(fetcher, LyricsTranslationClient(client))
                    for (providers in listOf(
                        listOf(LyricsProvider.LRCLIB, LyricsProvider.PAXSENIX),
                        listOf(LyricsProvider.PAXSENIX, LyricsProvider.LRCLIB),
                    )) {
                        val result = repository.getLyrics("video", "Title", "Artist", null, preferredProviders = providers)
                        assertEquals(if (providers.first() == LyricsProvider.LRCLIB) first else second, result)
                    }
                }
                val rich = LyricsWithProvider(word, "Lyprio")
                val fetcher =
                    FakeLyricsFetcher(
                        results = mapOf(LyricsProvider.LYPRIO to rich),
                        candidateResults =
                            mapOf(
                                LyricsProvider.LYPRIO to
                                    listOf(
                                        LyricsWithProvider("[meta]\nmlx_version=1.0\n[lyrics]\n", "Invalid"),
                                        LyricsWithProvider(plain, "Plain"),
                                        LyricsWithProvider(line, "Line"),
                                    ),
                            ),
                    )
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client))
                assertEquals(
                    rich,
                    repository.getLyrics(
                        "video",
                        "Title",
                        "Artist",
                        null,
                        preferredProviders = listOf(LyricsProvider.AUTO, LyricsProvider.LYPRIO, LyricsProvider.LYPRIO),
                    ),
                )
            }
        }

    @Test
    fun providerSearchKeepsCandidateMetadataAndDistinguishesFailureFromEmpty() =
        runTest {
            val lyrics =
                """
                {"version":2,
                 "song":{"title":"Matched title","artist":"Matched artist","album":"Matched album","durationMs":206000},
                 "lines":[{"startMs":24250,"endMs":26000,"text":"First line"}]}
                """.trimIndent()
            val fetcher =
                FakeLyricsFetcher(
                    mapOf(LyricsProvider.LRCLIB to LyricsWithProvider(lyrics, "LrcLib")),
                    throwing = setOf(LyricsProvider.BETTERLYRICS),
                )
            HttpClient(MockEngine { error("Unexpected network request") }).use { client ->
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), providerResultsDispatcher = null)
                val results =
                    repository.getProviderResults(
                        videoId = null,
                        title = "Query title",
                        artist = "Query artist",
                        duration = null,
                        preferredProviders = listOf(LyricsProvider.LRCLIB),
                        failOnError = true,
                    )
                val result = results.single()
                assertEquals("Matched title", result.song.title)
                assertEquals("Matched artist", result.song.artist)
                assertEquals("Matched album", result.song.album)
                assertEquals(206_000L, result.song.durationMs)
                assertEquals("[00:24.25] First line", result.preview)
                assertEquals(LyricsTimingKind.LINE_SYNCED, result.timingKind)
                assertFailsWith<IllegalStateException> {
                    repository.getProviderResults(
                        null,
                        "Title",
                        "Artist",
                        null,
                        preferredProviders = listOf(LyricsProvider.BETTERLYRICS),
                        failOnError = true,
                    )
                }
                assertTrue(
                    repository
                        .getProviderResults(
                            null,
                            "Title",
                            "Artist",
                            null,
                            preferredProviders = listOf(LyricsProvider.YOUTUBE),
                            failOnError = true,
                        ).isEmpty(),
                )
            }
        }

    @Test
    fun providerResultsPreserveOrderAndSkipFailures() =
        runBlocking {
            val fetcher =
                FakeLyricsFetcher(
                    mapOf(
                        LyricsProvider.BETTERLYRICS to
                            LyricsWithProvider(
                                MlxBuilder().meta("BetterLyrics").line(1_000, "Better").build(),
                                "BetterLyrics",
                            ),
                        LyricsProvider.LRCLIB to LyricsWithProvider("", "LrcLib"),
                        LyricsProvider.YOUTUBE to
                            LyricsWithProvider(
                                MlxBuilder().meta("YouTube Music").line(2_000, "YouTube").build(),
                                "YouTube Music",
                            ),
                    ),
                    throwing = setOf(LyricsProvider.UNISON),
                    candidateResults =
                        mapOf(
                            LyricsProvider.LYPRIO to
                                listOf(
                                    LyricsWithProvider(
                                        """{"version":2,"provider":"LYPRIO","subprovider":"RICH","syncType":"word","lines":[{"startMs":1000,"endMs":2000,"text":"Rich","words":[{"text":"Rich","startMs":1000,"endMs":2000}]}]}""",
                                        "Lyprio",
                                    ),
                                    LyricsWithProvider(
                                        """{"version":2,"provider":"LYPRIO","subprovider":"PLAIN","syncType":"plain","lines":[{"text":"Plain"}]}""",
                                        "Lyprio",
                                    ),
                                ),
                        ),
                )
            val client = HttpClient(MockEngine { error("Translation client must not be called") })
            client.use { client ->
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client))
                val results =
                    repository.getProviderResults(
                        videoId = "video",
                        title = "Title",
                        artist = "Artist",
                        duration = 180_000,
                        preferredProviders =
                            listOf(
                                LyricsProvider.AUTO,
                                LyricsProvider.BETTERLYRICS,
                                LyricsProvider.UNISON,
                                LyricsProvider.LYPRIO,
                                LyricsProvider.LRCLIB,
                                LyricsProvider.BETTERLYRICS,
                                LyricsProvider.YOUTUBE,
                            ),
                    )

                assertEquals(
                    listOf(
                        LyricsProvider.BETTERLYRICS,
                        LyricsProvider.LYPRIO,
                        LyricsProvider.LYPRIO,
                        LyricsProvider.YOUTUBE,
                    ),
                    results.map { it.provider },
                )
                assertEquals(
                    listOf("BetterLyrics", "Lyprio/RICH", "Lyprio/PLAIN", "YouTube Music"),
                    results.map { it.providerName },
                )
                assertEquals(
                    listOf(
                        LyricsTimingKind.LINE_SYNCED,
                        LyricsTimingKind.WORD_SYNCED,
                        LyricsTimingKind.UNSYNCED,
                        LyricsTimingKind.LINE_SYNCED,
                    ),
                    results.map { it.timingKind },
                )
            }
        }

    @Test
    fun providerResultsFlowFetchesConcurrentlyWithoutWaitingForSlowProviders() =
        runTest {
            val started = mutableSetOf<LyricsProvider>()
            val cancelled = mutableSetOf<LyricsProvider>()
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
                        started += provider
                        try {
                            when (provider) {
                                LyricsProvider.LYPRIO -> {
                                    delay(20_000)
                                    return LyricsWithProvider(MlxBuilder().meta("Lyprio").line(1_000, "Lyprio").build(), "Lyprio")
                                }

                                LyricsProvider.BETTERLYRICS -> {
                                    awaitCancellation()
                                }

                                LyricsProvider.LRCLIB -> {
                                    delay(200)
                                    return LyricsWithProvider(MlxBuilder().meta("LrcLib").line(1_000, "LrcLib").build(), "LrcLib")
                                }

                                LyricsProvider.YOUTUBE -> {
                                    delay(100)
                                    return LyricsWithProvider(
                                        MlxBuilder().meta("YouTube Music").line(1_000, "YouTube").build(),
                                        "YouTube Music",
                                    )
                                }

                                else -> {
                                    return null
                                }
                            }
                        } catch (error: CancellationException) {
                            cancelled += provider
                            throw error
                        }
                    }
                }
            val client = HttpClient(MockEngine { error("Translation client must not be called") })
            try {
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), providerResultsDispatcher = null)
                val providers =
                    listOf(
                        LyricsProvider.LYPRIO,
                        LyricsProvider.BETTERLYRICS,
                        LyricsProvider.LRCLIB,
                        LyricsProvider.YOUTUBE,
                    )
                val emittedAt = mutableListOf<Pair<LyricsProvider, Long>>()
                val collectJob =
                    launch {
                        repository
                            .getProviderResultsFlow(
                                videoId = "video",
                                title = "Title",
                                artist = "Artist",
                                duration = 180_000,
                                preferredProviders = providers,
                            ).collect { result ->
                                emittedAt += result.provider to testScheduler.currentTime
                            }
                    }

                testScheduler.advanceTimeBy(1)
                testScheduler.runCurrent()
                assertEquals(providers.toSet(), started)

                testScheduler.advanceTimeBy(250)
                testScheduler.runCurrent()
                assertEquals(
                    listOf(
                        LyricsProvider.YOUTUBE to 100L,
                        LyricsProvider.LRCLIB to 200L,
                    ),
                    emittedAt,
                )

                collectJob.join()
                assertEquals(
                    listOf(
                        LyricsProvider.YOUTUBE to 100L,
                        LyricsProvider.LRCLIB to 200L,
                        LyricsProvider.LYPRIO to 20_000L,
                    ),
                    emittedAt,
                )
                assertTrue(LyricsProvider.BETTERLYRICS in cancelled)

                val sortedResults =
                    repository.getProviderResults(
                        videoId = "video",
                        title = "Title",
                        artist = "Artist",
                        duration = 180_000,
                        preferredProviders = providers,
                    )
                assertEquals(
                    listOf(LyricsProvider.LYPRIO, LyricsProvider.LRCLIB, LyricsProvider.YOUTUBE),
                    sortedResults.map { it.provider },
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun ignoreVideoIdentityOmitsVideoIdFromLookups() =
        runTest {
            val observedVideoIds = mutableListOf<String?>()
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
                        observedVideoIds += videoId
                        return LyricsWithProvider(MlxBuilder().meta("LrcLib").line(1_000, "Custom query").build(), "LrcLib")
                    }
                }
            val client = HttpClient(MockEngine { error("Translation client must not be called") })
            try {
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), providerResultsDispatcher = null)
                val fetched =
                    repository.getLyrics(
                        videoId = "song-id",
                        title = "Custom Title",
                        artist = "",
                        duration = null,
                        album = null,
                        preferredProviders = listOf(LyricsProvider.LRCLIB),
                        skipCache = true,
                        ignoreVideoIdentity = true,
                    )
                assertNotNull(fetched)
                assertEquals(listOf<String?>(null), observedVideoIds)
            } finally {
                client.close()
            }
        }

    @Test
    fun unisonTimingRetimesTheSelectedDocumentWithoutReplacingIt() =
        runBlocking {
            val selected =
                MlxBuilder()
                    .meta("Selected provider")
                    .song(title = "Selected title")
                    .line(1_000, "first line")
                    .line(3_000, "second line")
                    .line(5_000, "third line")
                    .build()
            val unison =
                MlxBuilder()
                    .meta("Unison")
                    .song(title = "Different metadata")
                    .line(11_000, "first line")
                    .line(13_000, "second line")
                    .line(15_000, "third line")
                    .build()
            val fetcher = FakeLyricsFetcher(mapOf(LyricsProvider.UNISON to LyricsWithProvider(unison, "Unison")))
            val client = HttpClient(MockEngine { error("Translation client must not be called") })

            client.use {
                val selectedDocument = assertNotNull(LyricsUtils.parseMlxDocument(selected))
                val result =
                    LyricsRepository(fetcher, LyricsTranslationClient(client)).getRetimedMvLyrics(
                        videoId = "video",
                        richLyrics = selectedDocument,
                    )

                assertNotNull(result)
                assertEquals("Selected provider", result.meta.lyricSource)
                assertEquals("Selected title", result.song.title)
                assertEquals(selectedDocument.lines.map { it.text }, result.lines.map { it.text })
                assertEquals(listOf(11_000L, 13_000L, 15_000L), result.lines.map { it.time })
            }
        }

    @Test
    fun videoSpecificLyricsAreFetchedByVideoIdBeforeTrackLyricsAreRetimed() =
        runBlocking {
            val videoDocument =
                MlxBuilder()
                    .meta("YouTube Subtitle")
                    .song(youtubeId = "music-video-id")
                    .line(12_000, 15_000, "video only dialogue")
                    .build()
            val songDocument =
                MlxBuilder()
                    .meta("YouTube")
                    .line(1_000, 4_000, "song-only chorus")
                    .build()
            val calls = mutableListOf<Pair<String?, LyricsProvider>>()
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
                        calls += videoId to provider
                        return when (provider) {
                            LyricsProvider.YOUTUBESUBTITLE -> LyricsWithProvider(videoDocument, "YouTube Subtitle")
                            LyricsProvider.YOUTUBE -> LyricsWithProvider(songDocument, "YouTube")
                            else -> null
                        }
                    }
                }
            val client = HttpClient(MockEngine { error("Translation client must not be called") })

            client.use {
                val result =
                    LyricsRepository(fetcher, LyricsTranslationClient(client)).getVideoSpecificLyrics(
                        videoId = "music-video-id",
                        title = "Song",
                        artist = "Artist",
                        duration = 180_000,
                    )

                assertNotNull(result)
                assertEquals("YouTube Subtitle", result.provider)
                assertEquals(
                    listOf(LyricsProvider.YOUTUBESUBTITLE),
                    calls.map { it.second },
                )
                assertTrue(calls.all { it.first == "music-video-id" })
                assertTrue(LyricsUtils.parseLyrics(result.lyrics).any { it.text == "video only dialogue" })
                assertTrue(LyricsUtils.parseLyrics(result.lyrics).none { it.text == "song-only chorus" })
            }
        }

    @Test
    fun videoSpecificLyricsPreferSyncedSourcesThenFallbackOrder() =
        runTest {
            val words = MlxBuilder().meta("SimpMusic").line(12_000, 13_000, words = listOf(MlxWord("Video words", 12_000, 13_000))).build()
            val line = MlxBuilder().meta("YouTube Subtitle").line(12_000, "Video line").build()
            val plain = MlxBuilder().meta("YouTube Subtitle").unsyncedLine("Video text").build()
            for ((subtitle, simp, expected) in listOf(
                listOf(line, words, "YouTube Subtitle"),
                listOf(plain, words, "SimpMusic"),
                listOf(plain, plain, "YouTube Subtitle"),
                listOf(words, line, "YouTube Subtitle"),
            )) {
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
                            assertEquals("video-id", videoId)
                            assertTrue(isVideoContext)
                            return when (provider) {
                                LyricsProvider.YOUTUBESUBTITLE -> LyricsWithProvider(subtitle, "YouTube Subtitle")
                                LyricsProvider.SIMPMUSIC -> LyricsWithProvider(simp, "SimpMusic")
                                else -> null
                            }
                        }
                    }
                HttpClient(MockEngine { error("No network expected") }).use { client ->
                    val result =
                        LyricsRepository(fetcher, LyricsTranslationClient(client))
                            .getVideoSpecificLyrics("video-id", "Song", "Artist", null)
                    assertEquals(expected, result?.provider)
                }
            }
        }

    @Test
    fun liveVideoSelectionWaitsForPreferredLyprioEvenWhenCaptionsHaveWords() =
        runTest {
            val words = MlxBuilder().meta("Provider").line(1_000, 2_000, words = listOf(MlxWord("Sung words", 1_000, 2_000))).build()
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
                                LyricsWithProvider(words, "Lyprio")
                            }

                            LyricsProvider.YOUTUBESUBTITLE -> {
                                LyricsWithProvider(words, "YouTube Subtitle")
                            }

                            else -> {
                                null
                            }
                        }
                }
            HttpClient(MockEngine { error("No network expected") }).use { client ->
                val result =
                    LyricsRepository(fetcher, LyricsTranslationClient(client)).getLyrics(
                        "video",
                        "Song",
                        "Artist",
                        null,
                        preferredProviders = listOf(LyricsProvider.LYPRIO, LyricsProvider.YOUTUBESUBTITLE),
                        preferVideoProviders = true,
                        isVideoContext = true,
                    )
                assertEquals("Lyprio", result?.provider)
                assertEquals(4_000L, testScheduler.currentTime)
            }
        }

    @Test
    fun retimingSourceComparisonUsesProviderOrderBeforeTiming() {
        val words = MlxBuilder().meta("Provider").line(1_000, 2_000, words = listOf(MlxWord("Sung words", 1_000, 2_000))).build()
        val line = MlxBuilder().meta("Provider").line(1_000, "Sung line").build()
        val order = listOf(LyricsProvider.PAXSENIX, LyricsProvider.LYPRIO, LyricsProvider.YOUTUBESUBTITLE)
        assertTrue(prefersLyrics(LyricsWithProvider(line, "Paxsenix"), LyricsWithProvider(words, "Lyprio/KKBOX"), order))
        assertFalse(prefersLyrics(LyricsWithProvider(words, "YouTube Subtitle"), LyricsWithProvider(line, "Paxsenix"), order))
        assertTrue(prefersLyrics(LyricsWithProvider(words, "Paxsenix"), LyricsWithProvider(words, "Lyprio/KKBOX"), order))
        assertFalse(prefersLyrics(LyricsWithProvider(words, "YouTube Subtitle"), LyricsWithProvider(words, "Lyprio/KKBOX"), order))
    }

    @Test
    fun videoSpecificLyricsFallBackToLyricsProvidersWhenCaptionsAreMissing() =
        runBlocking {
            val songDocument = MlxBuilder().meta("YouTube").line(1_000, "song chorus").build()
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
                        return if (provider == LyricsProvider.YOUTUBE) LyricsWithProvider(songDocument, "YouTube Music") else null
                    }
                }
            val client = HttpClient(MockEngine { error("Translation client must not be called") })

            client.use {
                val result =
                    LyricsRepository(fetcher, LyricsTranslationClient(client)).getVideoSpecificLyrics(
                        videoId = "music-video-id",
                        title = "Song",
                        artist = "Artist",
                        duration = 180_000,
                    )

                assertNotNull(result)
                assertEquals("YouTube Music", result.provider)
                assertEquals(
                    listOf(
                        LyricsProvider.YOUTUBESUBTITLE,
                        LyricsProvider.YOUTUBE,
                    ),
                    calls,
                )
                assertTrue(LyricsUtils.parseLyrics(result.lyrics).any { it.text == "song chorus" })
            }
        }

    @Test
    fun differentSelectedDocumentsAreRetimedIndependently() =
        runBlocking {
            fun document(
                source: String,
                text: String,
                offsetMs: Long,
            ) = MlxBuilder()
                .meta(source)
                .line(1_000 + offsetMs, "$text one")
                .line(3_000 + offsetMs, "$text two")
                .line(5_000 + offsetMs, "$text three")
                .build()

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
                        if (provider != LyricsProvider.UNISON) return null
                        calls++
                        val text = if (calls == 1) "alpha" else "beta"
                        return LyricsWithProvider(document("Unison", text, 10_000), "Unison")
                    }
                }
            val client = HttpClient(MockEngine { error("Translation client must not be called") })

            client.use {
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client))
                val first = assertNotNull(LyricsUtils.parseMlxDocument(document("First", "alpha", 0)))
                val second = assertNotNull(LyricsUtils.parseMlxDocument(document("Second", "beta", 0)))

                assertEquals("First", repository.getRetimedMvLyrics("video", first)?.meta?.lyricSource)
                assertEquals("Second", repository.getRetimedMvLyrics("video", second)?.meta?.lyricSource)
                assertEquals(2, calls)
            }
        }

    @Test
    fun cachedTranslationNotNeededIsAHit() {
        val client = HttpClient(MockEngine { error("Translation client must not be called") })
        client.use {
            val repository = LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
            val entity =
                LyricsEntity(
                    id = "video",
                    lyrics = "lyrics",
                    translationLanguage = "fr",
                    translationMode = "Literal",
                    translationSourceHash = "source",
                    translationConfigKey = "v5|config",
                    translationNotNeeded = true,
                )

            val cached = repository.readCachedTranslation(entity, "fr", "Literal", "source", "v5|config")

            assertNotNull(cached)
            assertNull(cached.result)
            assertNull(repository.readCachedTranslation(entity, "de", "Literal", "source", "v5|config"))
            assertNull(repository.readCachedTranslation(entity, "fr", "Literal", "source", "v5|config", allowNotNeeded = false))
            for (version in listOf("v2", "v3", "v4")) {
                assertNull(
                    repository.readCachedTranslation(
                        entity.copy(translationConfigKey = "$version|config"),
                        "fr",
                        "Literal",
                        "source",
                        "v5|config",
                    ),
                )
            }
        }
    }

    @Test
    fun completedTranslationFromPreviousCacheVersionRemainsValid() {
        val client = HttpClient(MockEngine { error("Translation client must not be called") })
        client.use {
            val repository = LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
            val entity =
                LyricsEntity(
                    id = "video",
                    lyrics = "lyrics",
                    translatedLyrics = "METROLIST_TRANSLATION_V2:{\"lines\":[\"one\"]}",
                    translationLanguage = "fr",
                    translationMode = "Literal",
                    translationSourceHash = "source",
                    translationConfigKey = "v2|config",
                )

            for (version in listOf("v2", "v3", "v4")) {
                val legacy = entity.copy(translationConfigKey = "$version|config")
                val cached =
                    repository.readCachedTranslation(
                        legacy,
                        "fr",
                        "Literal",
                        "source",
                        "v5|config",
                        sourceLines = listOf("lyrics"),
                    )

                assertEquals(listOf("one"), assertNotNull(cached).result?.translatedLines)
                assertNull(repository.readCachedTranslation(legacy, "fr", "Literal", "source", "v5|other-config"))
            }
        }
    }

    @Test
    fun cachedTranslationWithStaleAlignmentIsRejected() {
        val client = HttpClient(MockEngine { error("Translation client must not be called") })
        client.use {
            val repository = LyricsRepository(FakeLyricsFetcher(emptyMap()), LyricsTranslationClient(client))
            val entity =
                LyricsEntity(
                    id = "video",
                    lyrics = "lyrics",
                    translatedLyrics = "METROLIST_TRANSLATION_V2:{\"lines\":[\"one\"]}",
                    translationLanguage = "fr",
                    translationMode = "Literal",
                    translationSourceHash = "source",
                    translationConfigKey = "config",
                )

            assertNull(
                repository.readCachedTranslation(
                    entity,
                    "fr",
                    "Literal",
                    "source",
                    "config",
                    sourceLines = listOf("first", "second"),
                ),
            )
        }
    }

    @Test
    fun romanizeWordsPreservesWordTimestampsAndTiming() =
        runBlocking {
            val originalWords =
                listOf(
                    WordTimestamp("あ", 1.0, 1.2, hasTrailingSpace = false),
                    WordTimestamp("い", 1.2, 1.5, hasTrailingSpace = true),
                )
            val result =
                LyricsRomanizer.romanizeWords(
                    fullText = "あい",
                    line = "あい",
                    words = originalWords,
                    enabledLanguages = setOf("Japanese"),
                    cyrillicByLine = false,
                )
            assertNotNull(result)
            assertEquals("ai", result.first)
            assertEquals(2, result.second.size)
            assertEquals("a", result.second[0].text)
            assertEquals(1.0, result.second[0].startTime)
            assertEquals(1.2, result.second[0].endTime)
            assertEquals("i", result.second[1].text)
        }

    @Test
    fun liveVideoFetchOrderDoesNotOverrideConfiguredQualityTies() =
        runBlocking {
            val calls = mutableListOf<LyricsProvider>()
            val lyrics = MlxBuilder().meta("Provider").line(1_000, "Line").build()
            var videoLyricsAvailable = true
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
                        return if (
                            provider == LyricsProvider.LRCLIB ||
                            (videoLyricsAvailable && provider == LyricsProvider.YOUTUBE)
                        ) {
                            LyricsWithProvider(lyrics, provider.name)
                        } else {
                            null
                        }
                    }
                }
            HttpClient(MockEngine { error("Translation client must not be called") }).use { client ->
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client))

                suspend fun fetch(preferVideoProviders: Boolean) =
                    repository.getLyrics(
                        videoId = "live-video",
                        title = "Live performance",
                        artist = "Artist",
                        duration = 180_000,
                        preferredProviders = listOf(LyricsProvider.LRCLIB, LyricsProvider.YOUTUBE, LyricsProvider.AUTO),
                        preferVideoProviders = preferVideoProviders,
                    )

                assertEquals("LRCLIB", fetch(false)?.provider)
                assertEquals(listOf(LyricsProvider.LRCLIB), calls)
                calls.clear()
                assertEquals("LRCLIB", fetch(true)?.provider)
                assertEquals(LyricsProvider.afterUserLyricsFallbacks + LyricsProvider.LRCLIB, calls)
                calls.clear()
                videoLyricsAvailable = false
                assertEquals("LRCLIB", fetch(true)?.provider)
                assertEquals(LyricsProvider.afterUserLyricsFallbacks + LyricsProvider.LRCLIB, calls)
            }
        }

    @Test
    fun videoProviderPreferencePreservesEnabledUserOrderWithoutDuplicates() {
        assertEquals(
            LyricsProvider.afterUserLyricsFallbacks + listOf(LyricsProvider.LRCLIB, LyricsProvider.KUGOU),
            LyricsProvider.preferredProvidersForFetch(
                userOrder = listOf(LyricsProvider.BETTERLYRICS, LyricsProvider.LRCLIB, LyricsProvider.KUGOU),
                enabled = setOf(LyricsProvider.LRCLIB, LyricsProvider.KUGOU),
                preferVideoProviders = true,
            ),
        )
        assertEquals(
            LyricsProvider.afterUserLyricsFallbacks,
            LyricsProvider.preferredProvidersForFetch(
                userOrder = LyricsProvider.userConfigurable,
                enabled = emptySet(),
                preferVideoProviders = true,
            ),
        )
    }

    @Test
    fun normalizedProviderOrderCanBeReorderedAndPersistedWithoutRegistryFiltering() {
        val order = LyricsProvider.parseUserOrderFromStoredCsv("YouTube,LRCLIB,unknown,LRCLIB,BetterLyrics,AUTO")
        val expected =
            listOf(LyricsProvider.LRCLIB, LyricsProvider.BETTERLYRICS) +
                LyricsProvider.userConfigurable.filter { it != LyricsProvider.LRCLIB && it != LyricsProvider.BETTERLYRICS }
        assertEquals(expected, order)

        val reordered = order.toMutableList().apply { add(0, removeAt(lastIndex)) }
        val stored = reordered.joinToString(",") { it.name }
        assertEquals(reordered, LyricsProvider.parseUserOrderFromStoredCsv(stored))
        assertEquals(LyricsProvider.userConfigurable.toSet(), reordered.toSet())
    }

    @Test
    fun parseEnabledFromStoredCsvHandlesNoneAndSubsets() {
        val previousDefault = "BETTERLYRICS,UNISON,LRCLIB,KUGOU,PAXSENIX,LYRICSPLUS,ZEMER"
        assertEquals(LyricsProvider.userConfigurable, LyricsProvider.parseUserOrderFromStoredCsv(previousDefault))
        assertEquals(LyricsProvider.userConfigurable.toSet(), LyricsProvider.parseEnabledFromStoredCsv(previousDefault))
        assertEquals(LyricsProvider.userConfigurable.toSet(), LyricsProvider.parseEnabledFromStoredCsv(""))
        assertEquals(LyricsProvider.userConfigurable.toSet(), LyricsProvider.parseEnabledFromStoredCsv("   "))
        assertEquals(emptySet(), LyricsProvider.parseEnabledFromStoredCsv("NONE"))
        assertEquals(emptySet(), LyricsProvider.parseEnabledFromStoredCsv("none"))

        val partial = LyricsProvider.parseEnabledFromStoredCsv("LRCLIB,KUGOU")
        assertEquals(setOf(LyricsProvider.LRCLIB, LyricsProvider.KUGOU), partial)

        val preferred =
            LyricsProvider.preferredProvidersForFetch(
                userOrder = listOf(LyricsProvider.BETTERLYRICS, LyricsProvider.LRCLIB, LyricsProvider.KUGOU),
                enabled = setOf(LyricsProvider.LRCLIB),
            )
        assertEquals(
            listOf(LyricsProvider.LRCLIB) + LyricsProvider.afterUserLyricsFallbacks,
            preferred,
        )

        val emptyPreferred =
            LyricsProvider.preferredProvidersForFetch(
                userOrder = LyricsProvider.userConfigurable,
                enabled = emptySet(),
            )
        assertEquals(LyricsProvider.afterUserLyricsFallbacks, emptyPreferred)
    }
}

private class FakeLyricsFetcher(
    private val results: Map<LyricsProvider, LyricsWithProvider?>,
    private val throwing: Set<LyricsProvider> = emptySet(),
    private val candidateResults: Map<LyricsProvider, List<LyricsWithProvider>> = emptyMap(),
) : LyricsFetcher {
    override suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
        videoId: String?,
        provider: LyricsProvider,
        isVideoContext: Boolean,
    ): LyricsWithProvider? {
        if (provider in throwing) error("Provider failed")
        return results[provider]
    }

    override suspend fun getLyricsResults(
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
        videoId: String?,
        provider: LyricsProvider,
        isVideoContext: Boolean,
    ): List<LyricsWithProvider> {
        if (provider in throwing) error("Provider failed")
        return candidateResults[provider] ?: listOfNotNull(results[provider])
    }
}
