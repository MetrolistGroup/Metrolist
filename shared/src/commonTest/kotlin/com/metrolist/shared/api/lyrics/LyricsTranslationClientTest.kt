package com.metrolist.shared.api.lyrics

import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackEventLevel
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class LyricsTranslationClientTest {
    @Test
    fun presetModelsDisableReasoningForTranslation() =
        runBlocking {
            for (model in AiProviderPresets.openRouterOfflineModels) {
                var requests = 0
                HttpClient(
                    MockEngine { request ->
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        assertEquals(JsonPrimitive(false), body["reasoning"]?.jsonObject?.get("enabled"))
                        assertEquals(model.id, body["model"]?.jsonPrimitive?.content)
                        requests++
                        val payload = """{"lines":["Hello"]}"""
                        respond("{\"choices\":[{\"message\":{\"content\":${JsonPrimitive(payload)}}}]}")
                    },
                ).use { client ->
                    val endpoint = checkNotNull(AiProviderPresets.find("OpenRouter")).endpoint
                    assertEquals(
                        listOf("Hello"),
                        LyricsTranslationClient(client)
                            .translateLyricsLines(listOf("bonjour"), "OpenRouter", "en", "Literal", "oauth-key", endpoint, model.id)
                            .getOrThrow()
                            .translatedLines,
                    )
                    assertEquals(1, requests)
                }
            }
        }

    @Test
    fun schemaRejectionTriesJsonModeBeforeUnconstrainedOutput() =
        runBlocking {
            for (supportsJsonMode in listOf(true, false)) {
                val formats = mutableListOf<String?>()
                HttpClient(
                    MockEngine { request ->
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val format =
                            body["response_format"]
                                ?.jsonObject
                                ?.get("type")
                                ?.jsonPrimitive
                                ?.content
                        formats += format
                        if (format == "json_schema" || (format == "json_object" && !supportsJsonMode)) {
                            respond("{}", HttpStatusCode.BadRequest, JSON_HEADERS)
                        } else {
                            respond("""{"choices":[{"message":{"content":"{\"lines\":[\"Hello\"]}"}}]}""", HttpStatusCode.OK, JSON_HEADERS)
                        }
                    },
                ).use { client ->
                    val result =
                        LyricsTranslationClient(client).translateLyricsLines(
                            listOf("bonjour"),
                            "OpenRouter",
                            "en",
                            "Literal",
                            "test-key",
                            "",
                            AiProviderPresets.FREE_LYRICS_AI_MODEL,
                        )
                    assertEquals(listOf("Hello"), result.getOrThrow().translatedLines)
                    assertEquals(
                        if (supportsJsonMode) listOf("json_schema", "json_object") else listOf("json_schema", "json_object", null),
                        formats,
                    )
                }
            }
        }

    @Test
    fun successfulHttpResponsesExposeEmptyTruncatedAndInvalidTranslationsWithoutRetrying() =
        runBlocking {
            val responses =
                listOf(
                    """{"choices":[{"message":{"content":null,"reasoning":"private reasoning"},"finish_reason":"stop"}]}""" to
                        LyricsTranslationFailure.EMPTY_RESPONSE,
                    """{"choices":[{"message":{"content":""},"finish_reason":"length"}]}""" to LyricsTranslationFailure.INCOMPLETE_RESPONSE,
                    """{"choices":[{"message":{"content":"{\"lines\":[\"Hola\"]}"},"finish_reason":"length"}]}""" to
                        LyricsTranslationFailure.INCOMPLETE_RESPONSE,
                    """{"choices":[{"message":{"content":"not JSON"}}]}""" to LyricsTranslationFailure.INVALID_RESPONSE,
                    """{"choices":[{"message":{"content":"{\"lines\":[]}"}}]}""" to LyricsTranslationFailure.INVALID_RESPONSE,
                    """{"choices":[{"message":{"content":"{\"lines\":[null]}"}}]}""" to LyricsTranslationFailure.INVALID_RESPONSE,
                    """{"choices":[{"message":{"content":"{\"lines\":\"Hola\"}"}}]}""" to LyricsTranslationFailure.INVALID_RESPONSE,
                    """{"error":{"message":"private error"}}""" to LyricsTranslationFailure.REQUEST_FAILED,
                )
            for ((response, expected) in responses) {
                PlaybackEventLog.clear()
                var requests = 0
                HttpClient(
                    MockEngine {
                        requests++
                        respond(response, HttpStatusCode.OK, JSON_HEADERS)
                    },
                ).use { client ->
                    val result =
                        LyricsTranslationClient(client).translateLyricsLines(
                            listOf("Hello"),
                            "OpenRouter",
                            "es",
                            "Literal",
                            "test-key",
                            "https://example.test/chat/completions",
                            "openrouter/free",
                        )
                    assertEquals(expected, result.exceptionOrNull()?.lyricsTranslationFailure())
                    assertEquals(1, requests)
                    val records = PlaybackEventLog.events.value.joinToString()
                    assertTrue("private reasoning" !in records && "private error" !in records && "test-key" !in records)
                }
            }
            PlaybackEventLog.clear()
        }

    @Test
    fun geminiIgnoresThoughtPartsAndReportsExhaustedOutput() =
        runBlocking {
            for (incomplete in listOf(false, true)) {
                val finishReason = if (incomplete) "MAX_TOKENS" else "STOP"
                val response =
                    """
                    {"candidates":[{"finishReason":"$finishReason","content":{"parts":[
                      {"thought":true,"text":"private reasoning"},
                      {"text":"{\"lines\":[\"Hola\"]}"}
                    ]}}]}
                    """.trimIndent()
                HttpClient(MockEngine { respond(response, HttpStatusCode.OK, JSON_HEADERS) }).use { client ->
                    val result =
                        LyricsTranslationClient(client).translateLyricsLines(
                            listOf("Hello"),
                            "Gemini",
                            "es",
                            "Literal",
                            "test-key",
                            "",
                            "gemini-test",
                        )
                    assertEquals(
                        "true",
                        PlaybackEventLog.events.value
                            .last { it.message == "AI lyrics response received." }
                            .details["reasoningPresent"],
                    )
                    if (incomplete) {
                        assertEquals(LyricsTranslationFailure.INCOMPLETE_RESPONSE, result.exceptionOrNull()?.lyricsTranslationFailure())
                    } else {
                        assertEquals(listOf("Hola"), result.getOrThrow().translatedLines)
                    }
                }
            }
        }

    @Test
    fun localDetectionPreservesOrderAndNeedsNoCredentialsOrNetwork() =
        runBlocking {
            HttpClient(MockEngine { error("Local detection must not send requests") }).use { http ->
                val client = LyricsTranslationClient(http)
                val lines =
                    listOf(
                        "Je ne veux pas oublier cette chanson",
                        "I will remember this song forever",
                        "",
                        "123 🎵",
                        "Je ne veux pas oublier cette chanson",
                    )
                assertEquals(listOf(true, false, false, false, true), client.shouldTranslateLyricsLines(lines, "en").getOrThrow())
                assertTrue(client.shouldTranslateLyrics(lines, "en").getOrThrow())
                assertEquals(listOf(false), client.shouldTranslateLyricsLines(listOf("Eu quero cantar esta canção"), "pt-BR").getOrThrow())
            }
        }

    @Test
    fun localDetectionChecksMixedScriptsAndPassages() =
        runBlocking {
            HttpClient(MockEngine { error("Local detection must not send requests") }).use { http ->
                val client = LyricsTranslationClient(http)
                assertEquals(
                    listOf(true, false),
                    client
                        .shouldTranslateLyricsLines(
                            listOf("I will remember this song forever 忘れない", "I will remember this song forever"),
                            "en",
                        ).getOrThrow(),
                )
                assertEquals(
                    listOf(true, false),
                    client.shouldTranslateLyricsLines(listOf("この歌をいつまでも覚えている I will remember you", "この歌をいつまでも覚えている"), "ja").getOrThrow(),
                )
                assertTrue(
                    client
                        .shouldTranslateLyricsLines(
                            listOf("Je garde cette chanson dans mon cœur I will remember you"),
                            "fr",
                        ).getOrThrow()
                        .single(),
                )
            }
        }

    @Test
    fun localDetectionPreservesCancellation() =
        runBlocking<Unit> {
            HttpClient(MockEngine { error("No requests expected") }).use { http ->
                val job = Job().apply { cancel() }
                assertFailsWith<CancellationException> {
                    withContext(job) { LyricsTranslationClient(http).shouldTranslateLyricsLines(listOf("Hello"), "fr") }
                }
            }
        }

    @Test
    fun bothDeepLProvidersSendTheSelectedRegionalVariant() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { contentCountry = "GB" })
            val targets = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        targets +=
                            Json
                                .parseToJsonElement((request.body as TextContent).text)
                                .jsonObject
                                .getValue("target_lang")
                                .jsonPrimitive.content
                        respond("""{"translations":[{"text":"Translated"}]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                for (provider in listOf("DeepL", "DeepL (keyless)")) {
                    LyricsTranslationClient(client)
                        .translateLyricsLines(
                            lines = listOf("Text"),
                            provider = provider,
                            targetLanguage = "en",
                            mode = "Literal",
                            apiKey = "",
                            baseUrl = "",
                            model = "",
                            deepLApiKey = "test-key",
                        ).getOrThrow()
                }
                assertEquals(listOf("EN-GB", "en-GB"), targets)
            } finally {
                client.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun deepLRegionsFollowSelectionAndCountry() {
        assertEquals("en-GB", resolveDeepLTargetLanguage("en", "GB"))
        assertEquals("pt-PT", resolveDeepLTargetLanguage("pt", "PT"))
        assertEquals("pt-BR", resolveDeepLTargetLanguage("pt", "BR"))
        assertEquals("en-GB", resolveDeepLTargetLanguage("en-GB", "US"))
        assertEquals("en", resolveDeepLTargetLanguage("en", "PL"))
    }

    @Test
    fun keylessDeepLUsesAnonymousOneshotEndpoint() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("oneshot-free.www.deepl.com", request.url.host)
                        assertEquals("None", request.headers[HttpHeaders.Authorization])
                        respond(
                            """{"translations":[{"text":"Hola"},{"text":"Mundo"}]}""",
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    },
                )

            val result =
                LyricsTranslationClient(client)
                    .translateLyricsLines(
                        lines = listOf("Hello", "World"),
                        provider = "DeepL (keyless)",
                        targetLanguage = "es",
                        mode = "Literal",
                        apiKey = "",
                        baseUrl = "",
                        model = "",
                    ).getOrThrow()

            assertEquals(listOf("Hola", "Mundo"), result.translatedLines)
        }

    @Test
    fun googleTranslationPreservesLineMarkers() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("translate.googleapis.com", request.url.host)
                        assertEquals("gtx", (request.body as FormDataContent).formData["client"])
                        respond(
                            """[[["Hola\n__METROLIST_LINE_1__\nMundo","Hello",null,null,1]],null,"en"]""",
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    },
                )

            val result =
                LyricsTranslationClient(client)
                    .translateLyricsLines(
                        lines = listOf("Hello", "World"),
                        provider = "Google Translate",
                        targetLanguage = "es",
                        mode = "Literal",
                        apiKey = "",
                        baseUrl = "",
                        model = "",
                    ).getOrThrow()

            assertEquals(listOf("Hola", "Mundo"), result.translatedLines)
        }

    @Test
    fun openAiCompatibleResponsesAcceptStructuredContentParts() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            """{"choices":[{"message":{"content":[{"type":"text","text":"{\"lines\":[\"Hola\"]}"}]}}]}""",
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    },
                )

            val result =
                LyricsTranslationClient(client)
                    .translateLyricsLines(
                        lines = listOf("Hello"),
                        provider = "OpenAI",
                        targetLanguage = "es",
                        mode = "Literal",
                        apiKey = "test-key",
                        baseUrl = "https://example.test/v1/chat/completions",
                        model = "test-model",
                    ).getOrThrow()

            assertEquals(listOf("Hola"), result.translatedLines)
        }

    @Test
    fun openAiCompatibleResponsesAcceptToolCallsWhenContentIsEmpty() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            """{"choices":[{"message":{"content":"","tool_calls":[{"function":{"arguments":"{\"lines\":[\"Hola\"]}"}}]}}]}""",
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    },
                )

            val result =
                LyricsTranslationClient(client)
                    .translateLyricsLines(
                        lines = listOf("Hello"),
                        provider = "OpenRouter",
                        targetLanguage = "es",
                        mode = "Literal",
                        apiKey = "test-key",
                        baseUrl = "https://example.test/v1/chat/completions",
                        model = "test-model",
                    ).getOrThrow()

            assertEquals(listOf("Hola"), result.translatedLines)
        }

    @Test
    fun geminiRequestStripsAdditionalProperties() =
        runBlocking {
            var capturedBody = ""
            val client =
                HttpClient(
                    MockEngine { request ->
                        capturedBody = (request.body as io.ktor.http.content.TextContent).text
                        respond(
                            """{"candidates":[{"content":{"parts":[{"text":"{\"lines\":[\"Hola\","},{"text":"\"Mundo\"]}"}]}}]}""",
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    },
                )

            val result =
                LyricsTranslationClient(client)
                    .translateLyricsLines(
                        lines = listOf("Hello", "World"),
                        provider = "Gemini",
                        targetLanguage = "es",
                        mode = "Literal",
                        apiKey = "test-key",
                        baseUrl = "https://generativelanguage.googleapis.com",
                        model = "gemini-2.5-flash",
                    ).getOrThrow()

            assertEquals(listOf("Hola", "Mundo"), result.translatedLines)
            kotlin.test.assertFalse(capturedBody.contains("additionalProperties"))
        }

    @Test
    fun geminiRequestResolvesDefaultAndSelectedModelUrls() =
        runBlocking {
            val capturedUrls = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        capturedUrls += request.url.toString()
                        respond(
                            """{"candidates":[{"content":{"parts":[{"text":"{\"lines\":[\"Hola\"]}"}]}}]}""",
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    },
                )

            val geminiPreset = AiProviderPresets.find("Gemini")!!
            LyricsTranslationClient(client)
                .translateLyricsLines(
                    lines = listOf("Hello"),
                    provider = "Gemini",
                    targetLanguage = "es",
                    mode = "Literal",
                    apiKey = "test-key",
                    baseUrl = "",
                    model = "",
                ).getOrThrow()
            LyricsTranslationClient(client)
                .translateLyricsLines(
                    lines = listOf("Hello"),
                    provider = "Gemini",
                    targetLanguage = "es",
                    mode = "Literal",
                    apiKey = "test-key",
                    baseUrl = geminiPreset.endpoint,
                    model = "gemini-2.5-flash",
                ).getOrThrow()
            LyricsTranslationClient(client)
                .translateLyricsLines(
                    lines = listOf("Hello"),
                    provider = "Gemini",
                    targetLanguage = "es",
                    mode = "Literal",
                    apiKey = "test-key",
                    baseUrl = AiProviderPresets.LEGACY_GEMINI_ENDPOINT,
                    model = "",
                ).getOrThrow()
            LyricsTranslationClient(client)
                .translateLyricsLines(
                    lines = listOf("Hello"),
                    provider = "Gemini-compatible",
                    targetLanguage = "es",
                    mode = "Literal",
                    apiKey = "test-key",
                    baseUrl = geminiPreset.endpoint,
                    model = "custom\$1",
                ).getOrThrow()

            assertEquals(
                listOf(
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:generateContent",
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:generateContent",
                    "https://generativelanguage.googleapis.com/v1beta/models/custom\$1:generateContent",
                ),
                capturedUrls,
            )
        }

    @Test
    fun anthropicRequestUsesToolSchemaAndAnthropicHeaders() =
        runBlocking {
            var capturedBody = ""
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("test-key", request.headers["x-api-key"])
                        assertEquals("2023-06-01", request.headers["anthropic-version"])
                        capturedBody = (request.body as io.ktor.http.content.TextContent).text
                        respond(
                            """{"content":[{"type":"text","text":"Preparing output"},{"type":"tool_use","input":{"lines":["Hola"]}}]}""",
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    },
                )

            val result =
                LyricsTranslationClient(client)
                    .translateLyricsLines(
                        lines = listOf("Hello"),
                        provider = "Anthropic",
                        targetLanguage = "es",
                        mode = "Literal",
                        apiKey = "test-key",
                        baseUrl = "https://api.anthropic.com/v1/messages",
                        model = "claude-test",
                    ).getOrThrow()

            val body = Json.parseToJsonElement(capturedBody).jsonObject
            assertEquals("claude-test", body["model"]?.jsonPrimitive?.content)
            assertEquals(
                "return_lyrics_translation",
                body["tool_choice"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(listOf("Hola"), result.translatedLines)
        }

    @Test
    fun rejectsMisalignedAiLineAndWordResponses() =
        runBlocking {
            var response = """{"choices":[{"message":{"content":"{\"lines\":[\"Hola\"]}"}}]}"""
            val client = HttpClient(MockEngine { respond(response, HttpStatusCode.OK, JSON_HEADERS) })
            val translationClient = LyricsTranslationClient(client)

            val linesResult =
                translationClient.translateLyricsLines(
                    lines = listOf("Hello", "World"),
                    provider = "Mistral",
                    targetLanguage = "es",
                    mode = "Literal",
                    apiKey = "test-key",
                    baseUrl = "https://example.test/chat/completions",
                    model = "test-model",
                )
            assertTrue(linesResult.isFailure)

            response =
                """{"choices":[{"message":{"content":"{\"lines\":[{\"romanizedLine\":\"kimi no\",\"romanizedWords\":[\"kimi\"]}]}"}}]}"""
            val wordsResult =
                translationClient.translateLyricsLines(
                    lines = listOf("君の"),
                    provider = "xAI",
                    targetLanguage = "en",
                    mode = "Romanized",
                    apiKey = "test-key",
                    baseUrl = "https://example.test/chat/completions",
                    model = "test-model",
                    wordsPerLine = listOf(listOf("君", "の")),
                )
            assertTrue(wordsResult.isFailure)
        }

    @Test
    fun localDetectionChecksForeignPassagesAfterLongLyrics() =
        runBlocking {
            HttpClient(MockEngine { error("No requests expected") }).use { http ->
                val client = LyricsTranslationClient(http)
                val english = List(120) { "I will remember this song forever and sing it every day" }
                assertEquals(false, client.shouldTranslateLyrics(english, "en").getOrThrow())
                assertEquals(true, client.shouldTranslateLyrics(english + "Je ne veux pas oublier cette chanson", "en").getOrThrow())
                assertEquals(
                    true,
                    client
                        .shouldTranslateLyrics(
                            listOf(english.joinToString(" ") + " Je ne veux pas oublier cette chanson"),
                            "en",
                        ).getOrThrow(),
                )
            }
        }

    @Test
    fun aiProvidersRejectMissingCredentialsWithoutRequesting() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond("", HttpStatusCode.OK)
                    },
                )

            val result =
                LyricsTranslationClient(client)
                    .translateLyricsLines(
                        lines = listOf("Hello"),
                        provider = "Perplexity",
                        targetLanguage = "es",
                        mode = "Literal",
                        apiKey = "",
                        baseUrl = "https://example.test/chat/completions",
                        model = "test-model",
                    )

            assertTrue(result.isFailure)
            assertEquals(0, requests)
        }

    @Test
    fun upstreamRejectionIsNotRetriedWithAnotherBody() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond("rejected", HttpStatusCode.Unauthorized)
                    },
                )

            val result =
                LyricsTranslationClient(client).translateLyricsLines(
                    lines = listOf("Hello"),
                    provider = "OpenAI",
                    targetLanguage = "es",
                    mode = "Literal",
                    apiKey = "invalid-key",
                    baseUrl = "https://example.test/chat/completions",
                    model = "test-model",
                )
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("401") == true)
            assertEquals(1, requests)
        }

    @Test
    fun retriedAiServerFailureIsNotLoggedWhenRetrySucceeds() =
        runBlocking {
            PlaybackEventLog.clear()
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    if (requests == 1) {
                        respond("temporarily unavailable", HttpStatusCode.InternalServerError)
                    } else {
                        respond(
                            """{"choices":[{"message":{"content":"{\"lines\":[\"Hola\"]}"}}]}""",
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    }
                },
            ) { install(HttpFailureLog) }.use { client ->
                val result =
                    retryLyricsTranslation {
                        LyricsTranslationClient(client).translateLyricsLines(
                            lines = listOf("Hello"),
                            provider = "OpenAI",
                            targetLanguage = "es",
                            mode = "Literal",
                            apiKey = "test-key",
                            baseUrl = "https://example.test/chat/completions",
                            model = "test-model",
                        )
                    }
                assertEquals(listOf("Hola"), result.getOrThrow().translatedLines)
                assertEquals(2, requests)
                assertTrue(
                    PlaybackEventLog.events.value.none { it.level == PlaybackEventLevel.WARN || it.level == PlaybackEventLevel.ERROR },
                )
            }
            PlaybackEventLog.clear()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun slowAiCompletionOutlivesSharedHttpTimeoutWithoutRetrying() =
        runTest {
            var requests = 0
            HttpClient(MockEngine) {
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler {
                        requests++
                        delay(150.milliseconds)
                        respond("""{"choices":[{"message":{"content":"{\"lines\":[\"Hola\"]}"}}]}""", HttpStatusCode.OK, JSON_HEADERS)
                    }
                }
                install(HttpTimeout) { requestTimeoutMillis = 50 }
            }.use { client ->
                val result =
                    LyricsTranslationClient(client).translateLyricsLines(
                        lines = listOf("Hello"),
                        provider = "OpenRouter",
                        targetLanguage = "es",
                        mode = "Literal",
                        apiKey = "test-key",
                        baseUrl = "https://example.test/chat/completions",
                        model = "test-model",
                    )
                assertEquals(listOf("Hola"), result.getOrThrow().translatedLines)
                assertEquals(1, requests)
                assertEquals(150L, testScheduler.currentTime)
            }
        }

    @Test
    fun reasoningOnlyCompletionFailsWithoutRepeatingPaidRequest(): Unit =
        runBlocking {
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond(
                        """{"choices":[{"message":{"content":null,"reasoning":"Thinking"},"finish_reason":null}]}""",
                        HttpStatusCode.OK,
                        JSON_HEADERS,
                    )
                },
            ).use { client ->
                val result =
                    LyricsTranslationClient(client).translateLyricsLines(
                        lines = listOf("Hello"),
                        provider = "OpenRouter",
                        targetLanguage = "es",
                        mode = "Literal",
                        apiKey = "test-key",
                        baseUrl = "https://example.test/chat/completions",
                        model = "test-model",
                    )
                assertTrue(result.isFailure)
                assertEquals(1, requests)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aiRequestCancellationIsPropagated() =
        runTest {
            var started = false
            HttpClient(MockEngine) {
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler {
                        started = true
                        awaitCancellation()
                    }
                }
            }.use { client ->
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(100.milliseconds) {
                        LyricsTranslationClient(client).translateLyricsLines(
                            lines = listOf("Hello"),
                            provider = "OpenAI",
                            targetLanguage = "es",
                            mode = "Literal",
                            apiKey = "test-key",
                            baseUrl = "https://example.test/chat/completions",
                            model = "test-model",
                        )
                    }
                }
                assertTrue(started)
                assertEquals(100L, testScheduler.currentTime)
            }
        }

    private companion object {
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
