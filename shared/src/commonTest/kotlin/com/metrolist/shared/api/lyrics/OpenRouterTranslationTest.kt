package com.metrolist.shared.api.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenRouterTranslationTest {
    @Test
    fun upstreamThrottleUsesCatalogFreeModelsAndRemembersBusyModelsAcrossRequests() =
        runBlocking {
            val models = mutableListOf<String>()
            HttpClient(
                MockEngine { request ->
                    if (request.method == HttpMethod.Get) {
                        respond(if (request.url.host == "models.dev") "{}" else CATALOG)
                    } else {
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val model = body.getValue("model").jsonPrimitive.content
                        models += model
                        if (model == PRIMARY) {
                            respond(UPSTREAM_LIMIT, HttpStatusCode.TooManyRequests, headersOf("Retry-After", "60"))
                        } else {
                            respond(completion("""{"lines":["Cześć"]}"""))
                        }
                    }
                },
            ).use { http ->
                val client = LyricsTranslationClient(http)
                repeat(2) { assertEquals(ALTERNATIVE, client.verifyOpenRouterTranslation("oauth-key", PRIMARY, "pl").getOrThrow()) }
                assertEquals(listOf(PRIMARY, ALTERNATIVE, ALTERNATIVE), models)
            }
        }

    @Test
    fun exhaustedFreeModelsNeverUsePaidOrImageModelsOrRepeatBusyRequests() =
        runBlocking {
            val models = mutableListOf<String>()
            HttpClient(
                MockEngine { request ->
                    if (request.method == HttpMethod.Get) {
                        respond(if (request.url.host == "models.dev") "{}" else CATALOG)
                    } else {
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        models += body.getValue("model").jsonPrimitive.content
                        respond(UPSTREAM_LIMIT, HttpStatusCode.TooManyRequests)
                    }
                },
            ).use { http ->
                val client = LyricsTranslationClient(http)
                repeat(2) {
                    assertEquals(
                        LyricsTranslationFailure.MODEL_BUSY,
                        client.verifyOpenRouterTranslation("oauth-key", PRIMARY, "pl").exceptionOrNull()?.lyricsTranslationFailure(),
                    )
                }
                assertEquals(listOf(PRIMARY, ALTERNATIVE), models)
            }
        }

    @Test
    fun platformDailyLimitStopsRequestsForOtherSongsAndSetupWhileMinuteAndUnknownLimitsNeverFallBack() =
        runBlocking {
            for (message in listOf("free-models-per-day", "free-models-per-min", "Rate limit exceeded")) {
                var requests = 0
                HttpClient(
                    MockEngine {
                        requests++
                        respond("""{"error":{"code":429,"message":"$message"}}""", HttpStatusCode.TooManyRequests)
                    },
                ).use { http ->
                    val client = LyricsTranslationClient(http)
                    assertTrue(client.verifyOpenRouterTranslation("oauth-key", PRIMARY, "pl").isFailure)
                    assertTrue(
                        client
                            .translateLyricsLines(
                                listOf("Another song"),
                                "OpenRouter",
                                "pl",
                                "Literal",
                                "oauth-key",
                                ENDPOINT,
                                PRIMARY,
                            ).isFailure,
                    )
                    assertEquals(if (message == "free-models-per-day") 1 else 2, requests)
                }
            }
        }

    @Test
    fun setupRetryChecksDailyQuotaWithoutInferenceUntilCreditsOrQuotaBecomeAvailable() =
        runBlocking {
            var inferenceCalls = 0
            var quotaAvailable = false
            HttpClient(
                MockEngine { request ->
                    if (request.method == HttpMethod.Get) {
                        assertEquals("/api/v1/key", request.url.encodedPath)
                        assertEquals("Bearer oauth-key", request.headers["Authorization"])
                        respond("""{"data":{"free_model_daily_requests":{"remaining":${if (quotaAvailable) 1000 else 0}}}}""")
                    } else {
                        inferenceCalls++
                        if (quotaAvailable) {
                            respond(completion("""{"lines":["Cześć"]}"""))
                        } else {
                            respond("""{"error":{"code":429,"message":"free-models-per-day"}}""", HttpStatusCode.TooManyRequests)
                        }
                    }
                },
            ).use { http ->
                val client = LyricsTranslationClient(http)
                repeat(2) {
                    assertEquals(
                        LyricsTranslationFailure.FREE_DAILY_LIMIT,
                        client.verifyOpenRouterTranslation("oauth-key", PRIMARY, "pl").exceptionOrNull()?.lyricsTranslationFailure(),
                    )
                }
                assertEquals(1, inferenceCalls)
                quotaAvailable = true
                assertEquals(PRIMARY, client.verifyOpenRouterTranslation("oauth-key", PRIMARY, "pl").getOrThrow())
                assertEquals(2, inferenceCalls)
            }
        }

    @Test
    fun schemaAndReasoningRejectionsHaveABoundedFallbackAndKeepJsonValidation() =
        runBlocking {
            for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.UnprocessableEntity)) {
                val formats = mutableListOf<String?>()
                HttpClient(
                    MockEngine { request ->
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        formats +=
                            body["response_format"]
                                ?.jsonObject
                                ?.get("type")
                                ?.jsonPrimitive
                                ?.content
                        if (body["reasoning"] != null) {
                            respond("""{"error":{"message":"Unsupported reasoning parameter"}}""", status)
                        } else {
                            respond(completion("Here is the translation:\n```JSON\n{\"lines\":[\"Cześć\"]}\n```\n{done}"))
                        }
                    },
                ).use { http ->
                    assertTrue(LyricsTranslationClient(http).verifyOpenRouterTranslation("oauth-key", PRIMARY, "pl").isSuccess)
                    assertEquals(listOf("json_schema", "json_object", null, null), formats)
                }
            }
        }

    @Test
    fun fencedAndExplainedJsonPreservesQuotedBracesBackticksAndLineAlignment() =
        runBlocking {
            val lines = listOf("Cześć {world} \"quoted\" ```json", "Again")
            val payload = "{\"lines\":${JsonPrimitive(lines[0]).let { "[$it,${JsonPrimitive(lines[1])}]" }}}"
            for (content in listOf(
                "```json\n$payload\n```",
                "{source}\nTranslation:\n$payload\n{done}",
                "Here:\n${payload.substringAfter(":").dropLast(1)}\nDone",
            )) {
                HttpClient(MockEngine { respond(completion(content)) }).use { http ->
                    val result =
                        LyricsTranslationClient(
                            http,
                        ).translateLyricsLines(listOf("Hello", "Again"), "OpenRouter", "pl", "Literal", "key", ENDPOINT, PRIMARY)
                    assertEquals(lines, result.getOrThrow().translatedLines)
                }
            }
        }

    @Test
    fun wrappedLanguageDetectionUsesTheRequestedLanguageAndRejectsMalformedDecisions() =
        runBlocking {
            val responses =
                listOf("```json\n{\"foreign_text\":\"bonjour\"}\n```", "Explanation {source}\n{\"lines\":[\"bonjour\",\"\"]}\n{done}")
            var calls = 0
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
                    assertTrue("pt-BR" in prompt)
                    respond(completion(responses[calls++]))
                },
            ).use { http ->
                val client = LyricsTranslationClient(http)
                assertTrue(client.shouldTranslateLyrics(listOf("bonjour"), "OpenRouter", "pt-BR", "key", ENDPOINT, PRIMARY).getOrThrow())
                assertEquals(
                    listOf(true, false),
                    client
                        .shouldTranslateLyricsLines(
                            listOf("bonjour", "Olá"),
                            "OpenRouter",
                            "pt-BR",
                            "key",
                            ENDPOINT,
                            PRIMARY,
                        ).getOrThrow(),
                )
            }
        }

    @Test
    fun errorsInsideHttp200CompletionsCannotBeAcceptedAsTranslations() =
        runBlocking {
            for (response in listOf(
                """{"error":{"code":429,"message":"free-models-per-day"}}""",
                """
                {"choices":[{"message":{"content":"{\"lines\":[\"Cześć\"]}"},
                "finish_reason":"error","error":{"code":429,
                "message":"free-models-per-day"}}]}
                """,
            )) {
                HttpClient(MockEngine { respond(response) }).use { http ->
                    assertEquals(
                        LyricsTranslationFailure.FREE_DAILY_LIMIT,
                        LyricsTranslationClient(
                            http,
                        ).verifyOpenRouterTranslation("key", PRIMARY, "pl").exceptionOrNull()?.lyricsTranslationFailure(),
                    )
                }
            }
        }

    private companion object {
        const val PRIMARY = "google/gemma-4-26b-a4b-it:free"
        const val ALTERNATIVE = "google/gemma-4-31b-it:free"
        const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        const val UPSTREAM_LIMIT = """
                {"error":{"code":429,
                "message":"Temporarily rate-limited upstream",
                "metadata":{"provider_name":"Google"}}}
                """
        val CATALOG = """{"data":[
            {"id":"$PRIMARY","architecture":{"input_modalities":["text"],"output_modalities":["text"]},"pricing":{"prompt":"0","completion":"0"}},
            {"id":"$ALTERNATIVE","architecture":{"input_modalities":["text"],"output_modalities":["text"]},"pricing":{"prompt":"0","completion":"0"}},
            {"id":"paid/model","pricing":{"prompt":"0.0000001","completion":"0.0000001"}},
            {"id":"image-only:free","architecture":{"input_modalities":["image"],"output_modalities":["image"]},"pricing":{"prompt":"0","completion":"0"}}
        ]}"""

        fun completion(content: String) = """{"choices":[{"message":{"content":${JsonPrimitive(content)}},"finish_reason":"stop"}]}"""
    }
}
