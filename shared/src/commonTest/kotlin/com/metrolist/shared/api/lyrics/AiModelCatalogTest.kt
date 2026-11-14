package com.metrolist.shared.api.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiModelCatalogTest {
    @Test
    fun modelCacheSeparatesCredentialsAndReusesTheSameAccount() =
        runBlocking {
            var requests = 0
            HttpClient(
                MockEngine { request ->
                    requests++
                    val id = if (request.headers[HttpHeaders.Authorization] == "Bearer first") "first-model" else "second-model"
                    respond("""{"data":[{"id":"$id"}]}""")
                },
            ).use { client ->
                val preset = AiProviderPresets.find("Custom")!!
                val catalog = AiModelCatalog(client)
                val endpoint = "https://catalog.test/v1/chat/completions"
                assertEquals(
                    "first-model",
                    catalog
                        .models(preset, "first", endpoint)
                        .getOrThrow()
                        .single()
                        .id,
                )
                assertEquals(
                    "second-model",
                    catalog
                        .models(preset, "second", endpoint)
                        .getOrThrow()
                        .single()
                        .id,
                )
                assertEquals(
                    "first-model",
                    catalog
                        .models(preset, " first ", endpoint)
                        .getOrThrow()
                        .single()
                        .id,
                )
                assertEquals(2, requests)
            }
        }

    @Test
    fun openRouterListKeepsTextModelsAndScalesPricing() {
        val models =
            AiModelCatalog.parseOpenAiModels(
                """{"data":[
                {"id":"a/text","name":"A","context_length":1000,"architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},"pricing":{"prompt":"0.0000003","completion":"0.0000025"}},
                {"id":"a/image","architecture":{"input_modalities":["text"],"output_modalities":["image"]},"pricing":{"prompt":"0","completion":"0"}},
                {"id":"a/free","architecture":{"input_modalities":["text"],"output_modalities":["text"]},"pricing":{"prompt":"0","completion":"0"}},
                {"name":"no id"}]}""",
                textOnly = true,
            )
        assertEquals(listOf("a/text", "a/free"), models.map { it.id })
        assertEquals(0.3, models[0].inputUsdPerMTok!!, 1e-9)
        assertEquals(2.5, models[0].outputUsdPerMTok!!, 1e-9)
        assertTrue(models[1].free)
    }

    @Test
    fun anthropicAndGeminiShapes() {
        assertEquals(
            listOf("claude-x" to "Claude X"),
            AiModelCatalog.parseAnthropicModels("""{"data":[{"id":"claude-x","display_name":"Claude X"}]}""").map { it.id to it.name },
        )
        val gemini =
            AiModelCatalog.parseGeminiModels(
                """{"models":[
                {"name":"models/gemini-a","displayName":"Gemini A","supportedGenerationMethods":["generateContent"],"inputTokenLimit":10},
                {"name":"models/embed","supportedGenerationMethods":["embedContent"]}]}""",
            )
        assertEquals(listOf("gemini-a"), gemini.map { it.id })
        assertEquals(10, gemini.single().contextLength)
    }

    @Test
    fun modelsDevDropsUnimplementedProtocolsAndDeprecated() {
        val provider =
            Json
                .parseToJsonElement(
                    """{"npm":"@ai-sdk/openai-compatible","models":{
                    "chat":{"name":"Chat","cost":{"input":1,"output":2}},
                    "claude":{"provider":{"npm":"@ai-sdk/anthropic"}},
                    "gpt":{"provider":{"npm":"@ai-sdk/openai"}},
                    "old":{"status":"deprecated"},
                    "image":{"modalities":{"input":["text"],"output":["image"]}},
                    "audio":{"modalities":{"input":["audio"],"output":["text"]}}}}""",
                ).jsonObject
        val models = AiModelCatalog.parseModelsDevModels(provider)
        assertEquals(listOf("chat", "claude"), models.map { it.id })
        assertEquals(2.0, models[0].outputUsdPerMTok)
    }

    @Test
    fun throughputTakesBestEndpointAndToleratesNulls() {
        assertEquals(
            80.0,
            AiModelCatalog.parseOpenRouterThroughput(
                """{"data":{"endpoints":[{"throughput_last_30m":null},{"throughput_last_30m":{"p50":50}},{"throughput_last_30m":{"p50":80}}]}}""",
            ),
        )
        assertNull(AiModelCatalog.parseOpenRouterThroughput("""{"data":{"endpoints":[{"throughput_last_30m":null}]}}"""))
    }

    @Test
    fun publicOpenRouterListLoadsWithoutCredentialsOrPricingService() =
        runBlocking {
            val preset = AiProviderPresets.find("OpenRouter")!!
            var providerRequests = 0
            HttpClient(
                MockEngine { request ->
                    assertNull(request.headers[HttpHeaders.Authorization])
                    if (request.url.host == "openrouter.ai") {
                        assertEquals("/api/v1/models", request.url.encodedPath)
                        providerRequests++
                        respond(
                            """{"data":[{"id":"a/text","architecture":{"input_modalities":["text"],"output_modalities":["text"]},"pricing":{"prompt":"0","completion":"0"}}]}""",
                        )
                    } else {
                        assertEquals("models.dev", request.url.host)
                        respond("", HttpStatusCode.ServiceUnavailable)
                    }
                },
            ).use { client ->
                val catalog = AiModelCatalog(client)
                val models = catalog.models(preset, "", preset.endpoint).getOrThrow()
                assertEquals(listOf("a/text"), models.map { it.id })
                assertTrue(models.single().free)
                assertEquals(models, catalog.models(preset, "", preset.endpoint).getOrThrow())
                assertEquals(1, providerRequests)
                assertEquals(models, catalog.models(preset, "", preset.endpoint, refresh = true).getOrThrow())
                assertEquals(2, providerRequests)
            }
        }

    @Test
    fun directProviderListIsNarrowedAndPricedByModelsDev() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine { request ->
                        val headers = headersOf(HttpHeaders.ContentType, "application/json")
                        when (request.url.host) {
                            "api.openai.com" -> {
                                respond(
                                    """{"data":[{"id":"gpt-5-nano"},{"id":"text-embedding-3"}]}""",
                                    HttpStatusCode.OK,
                                    headers,
                                )
                            }

                            else -> {
                                respond(
                                    """{"openai":{"models":{"gpt-5-nano":{"name":"GPT-5 nano","cost":{"input":0.05,"output":0.4}}}}}""",
                                    HttpStatusCode.OK,
                                    headers,
                                )
                            }
                        }
                    },
                )
            val models =
                AiModelCatalog(
                    client,
                ).models(AiProviderPresets.find("OpenAI")!!, "sk-test", "https://api.openai.com/v1/chat/completions").getOrThrow()
            assertEquals(listOf("gpt-5-nano"), models.map { it.id })
            assertEquals(0.4, models.single().outputUsdPerMTok)
        }

    @Test
    fun emptyProviderListFallsBackToModelsDev() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine { request ->
                        val headers = headersOf(HttpHeaders.ContentType, "application/json")
                        when (request.url.host) {
                            "api.openai.com" -> {
                                respond("""{"data":[{"id":"text-embedding-3-small"}]}""", HttpStatusCode.OK, headers)
                            }

                            else -> {
                                respond(
                                    """{"openai":{"models":{"gpt-5-nano":{"name":"GPT-5 nano","cost":{"input":0.05,"output":0.4}}}}}""",
                                    HttpStatusCode.OK,
                                    headers,
                                )
                            }
                        }
                    },
                )
            val models =
                AiModelCatalog(client)
                    .models(
                        AiProviderPresets.find("OpenAI")!!,
                        "sk-test",
                        "https://api.openai.com/v1/chat/completions",
                    ).getOrThrow()
            assertEquals(listOf("gpt-5-nano"), models.map { it.id })
        }

    @Test
    fun pickerSortsFreeThenPriceAndDropsDuplicatesAndUnsuitableModels() {
        val models =
            AiModelCatalog.filterAndSort(
                listOf(
                    AiModelInfo("paid", inputUsdPerMTok = 0.1, outputUsdPerMTok = 0.4),
                    AiModelInfo("premium", inputUsdPerMTok = 3.0, outputUsdPerMTok = 15.0),
                    AiModelInfo("free:free", inputUsdPerMTok = 0.0, outputUsdPerMTok = 0.0),
                    AiModelInfo("cheap", inputUsdPerMTok = 0.03, outputUsdPerMTok = 0.13),
                    AiModelInfo("cheap", inputUsdPerMTok = 0.03, outputUsdPerMTok = 0.13),
                    AiModelInfo("text-embedding"),
                    AiModelInfo("chat-preview"),
                    AiModelInfo("chat-experimental"),
                    AiModelInfo("chat:batch"),
                    AiModelInfo("~chat-alias"),
                    AiModelInfo("invalid", inputUsdPerMTok = -1.0),
                    AiModelInfo("not-a-price", outputUsdPerMTok = Double.NaN),
                ),
            )
        assertEquals(listOf("free:free", "cheap", "paid"), models.map { it.id })
    }

    @Test
    fun openRouterRequiresTextChatAndDropsExpiredModels() {
        val models =
            AiModelCatalog.parseOpenAiModels(
                """{"data":[
            {"id":"chat","architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},"endpoints":["/api/v1/chat/completions"]},
            {"id":"batch","architecture":{"input_modalities":["text"],"output_modalities":["text"]},"endpoints":["/api/v1/batches"]},
            {"id":"audio","architecture":{"input_modalities":["audio"],"output_modalities":["text"]}},
            {"id":"image","architecture":{"input_modalities":["text"],"output_modalities":["image"]}},
            {"id":"old","architecture":{"input_modalities":["text"],"output_modalities":["text"]},"expiration_date":"2020-01-01"},
            {"id":"deprecated","architecture":{"input_modalities":["text"],"output_modalities":["text"]},"status":"deprecated"},
            {"id":"unknown"}]}""",
                textOnly = true,
            )
        assertEquals(listOf("chat"), models.map { it.id })
    }

    @Test
    fun defaultsStayCheapAndSetupAlwaysChoosesAFreeModel() {
        val preset = AiProviderPresets.find("OpenRouter")!!
        val offline = AiProviderPresets.openRouterOfflineModels
        assertEquals(AiProviderPresets.FREE_LYRICS_AI_MODEL, AiModelCatalog.defaultModel(preset, offline, freeOnly = true))
        assertEquals(AiProviderPresets.DEFAULT_LYRICS_AI_MODEL, AiModelCatalog.defaultModel(preset, offline))
        assertNull(AiModelCatalog.defaultModel(preset, offline.filterNot { it.free }, freeOnly = true))
        assertEquals("claude-haiku-5-5", AiProviderPresets.find("Anthropic")!!.defaultModel)
        assertEquals("gemini-2.5-flash-lite", AiProviderPresets.find("Gemini")!!.defaultModel)
    }

    @Test
    fun validationKeepsHiddenSavedModelsAndOnlyReplacesMissingModelsFromALiveList() =
        runBlocking {
            val preset = AiProviderPresets.find("OpenRouter")!!
            HttpClient(
                MockEngine { request ->
                    if (request.url.host == "openrouter.ai") {
                        respond(
                            """{"data":[
                {"id":"saved-premium","architecture":{"input_modalities":["text"],"output_modalities":["text"]},"pricing":{"prompt":"0.00001","completion":"0.00003"}},
                {"id":"${AiProviderPresets.DEFAULT_LYRICS_AI_MODEL}","architecture":{"input_modalities":["text"],"output_modalities":["text"]},"pricing":{"prompt":"0.00000003","completion":"0.00000013"}},
                {"id":"${AiProviderPresets.FREE_LYRICS_AI_MODEL}","architecture":{"input_modalities":["text"],"output_modalities":["text"]},"pricing":{"prompt":"0","completion":"0"}}]}""",
                        )
                    } else {
                        respond("", HttpStatusCode.ServiceUnavailable)
                    }
                },
            ).use { client ->
                val catalog = AiModelCatalog(client)
                assertEquals("saved-premium", catalog.validatedModel(preset, "key", preset.endpoint, "saved-premium"))
                assertEquals(AiProviderPresets.DEFAULT_LYRICS_AI_MODEL, catalog.validatedModel(preset, "key", preset.endpoint, "removed"))
                assertEquals(AiProviderPresets.FREE_LYRICS_AI_MODEL, catalog.validatedModel(preset, "key", preset.endpoint, "removed:free"))
                assertEquals("custom", catalog.validatedModel(preset, "key", "https://custom.test", "custom"))
            }
            HttpClient(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }).use { client ->
                val catalog = AiModelCatalog(client)
                assertEquals("saved", catalog.validatedModel(preset, "key", preset.endpoint, "saved"))
                assertEquals(
                    AiProviderPresets.openRouterOfflineModels
                        .map {
                            it.id
                        }.toSet(),
                    catalog
                        .models(preset, "key", preset.endpoint)
                        .getOrThrow()
                        .map { it.id }
                        .toSet(),
                )
            }
        }

    @Test
    fun aFailedRefreshCannotInvalidateASavedModelUsingAnOlderList() =
        runBlocking {
            val preset = AiProviderPresets.find("OpenRouter")!!
            var offline = false
            HttpClient(
                MockEngine { request ->
                    if (!offline && request.url.host == "openrouter.ai") {
                        respond(
                            """{"data":[{"id":"${preset.defaultModel}","architecture":{"input_modalities":["text"],"output_modalities":["text"]},"pricing":{"prompt":"0.00000003","completion":"0.00000013"}}]}""",
                        )
                    } else {
                        respond("", HttpStatusCode.ServiceUnavailable)
                    }
                },
            ).use { client ->
                val catalog = AiModelCatalog(client)
                val online = catalog.models(preset, "key", preset.endpoint).getOrThrow()
                assertEquals(listOf(preset.defaultModel), online.map { it.id })
                assertNull(AiModelCatalog.defaultModel(preset, online, freeOnly = true))
                offline = true
                catalog.models(preset, "key", preset.endpoint, refresh = true).getOrThrow()
                assertEquals("new-account-model", catalog.validatedModel(preset, "key", preset.endpoint, "new-account-model"))
            }
        }

    @Test
    fun presetsDeriveModelEndpointsAndRouteOpenCodePerModel() {
        assertEquals("https://openrouter.ai/api/v1/models", AiProviderPresets.find("OpenRouter")!!.modelsEndpoint)
        assertEquals("https://api.anthropic.com/v1/models", AiProviderPresets.find("Anthropic")!!.modelsEndpoint)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models", AiProviderPresets.find("Gemini")!!.modelsEndpoint)
        assertEquals("https://opencode.ai/zen/go/v1/models", AiProviderPresets.find("OpenCodeGo")!!.modelsEndpoint)
        assertEquals("OpenAI-compatible", AiProviderPresets.find("Custom")!!.id)

        val zen = AiProviderPresets.find("OpenCodeZen")!!.endpoint
        assertEquals(AiProtocol.ANTHROPIC, AiProviderPresets.protocolFor("OpenCodeZen", zen, "claude-opus-4-5"))
        assertEquals("https://opencode.ai/zen/v1/messages", AiProviderPresets.requestUrl("OpenCodeZen", zen, "claude-opus-4-5"))
        assertEquals(AiProtocol.OPENAI, AiProviderPresets.protocolFor("OpenCodeZen", zen, "kimi-k2.5"))
        assertEquals(zen, AiProviderPresets.requestUrl("OpenCodeZen", zen, "kimi-k2.5"))
        assertEquals(AiProtocol.GEMINI, AiProviderPresets.protocolFor("Gemini-compatible", "https://x/y", "m"))
    }
}
