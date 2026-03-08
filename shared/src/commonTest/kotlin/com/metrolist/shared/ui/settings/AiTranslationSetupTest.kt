package com.metrolist.shared.ui.settings

import com.metrolist.shared.api.lyrics.AiProviderPresets
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.api.lyrics.LyricsTranslationFailure
import com.metrolist.shared.api.lyrics.lyricsTranslationFailure
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiTranslationSetupTest {
    @Test
    fun targetLanguageUsesSavedChoiceThenAppThenSystemLocale() {
        assertEquals("pl", lyricsSetupLanguage("pl", "fr", "ja"))
        assertEquals("pt-BR", lyricsSetupLanguage("device", "pt_BR", "ja"))
        assertEquals("ja", lyricsSetupLanguage("device", "SYSTEM", "ja"))
        assertEquals("ja", lyricsSetupLanguage("SYSTEM", "SYSTEM", "ja"))
        assertEquals("hi-Latn", lyricsSetupLanguage("", "hin", "ja"))
        assertEquals("zh-Hant-TW", lyricsSetupLanguage("device", "SYSTEM", "zh_Hant_TW"))
        assertEquals("", lyricsSetupLanguage("device", "SYSTEM", ""))
    }

    @Test
    fun completionTestsOAuthBeforeEnablingAndPreservesOtherProviderKeys() =
        runBlocking {
            val settings = Settings(MapSettings())
            settings.lyricsAiProvider = "OpenAI"
            settings.lyricsAiApiKey = "existing-manual-key"
            settings.lyricsAiModel = "existing-model"
            var requests = 0
            HttpClient(
                MockEngine { request ->
                    requests++
                    assertFalse(settings.lyricsAiTranslationEnabled)
                    assertFalse(settings.lyricsShowTranslations)
                    assertEquals("existing-model", settings.lyricsAiModel)
                    assertEquals("Bearer oauth-key", request.headers["Authorization"])
                    assertEquals("Metrolist", request.headers["X-Title"])
                    assertEquals("https://github.com/MetrolistGroup/Metrolist", request.headers["HTTP-Referer"])
                    assertEquals("https://openrouter.ai/api/v1/chat/completions", request.url.toString())
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("128", body["max_tokens"]?.jsonPrimitive?.content)
                    assertEquals(AiProviderPresets.FREE_LYRICS_AI_MODEL, body["model"]?.jsonPrimitive?.content)
                    respond("""{"choices":[{"message":{"content":"{\"lines\":[\"Cześć\"]}"},"finish_reason":"stop"}]}""")
                },
            ).use { http ->
                val client = LyricsTranslationClient(http)
                assertFailsWith<IllegalArgumentException> {
                    completeLyricsSetup(
                        settings,
                        client,
                        AiProviderPresets.FREE_LYRICS_AI_MODEL,
                        "pl",
                    )
                }
                settings.lyricsOpenRouterOAuthKey = "oauth-key"
                completeLyricsSetup(settings, client, AiProviderPresets.FREE_LYRICS_AI_MODEL, "pl").getOrThrow()
            }
            assertEquals(1, requests)
            assertEquals("OpenRouter", settings.lyricsAiProvider)
            assertEquals(AiProviderPresets.FREE_LYRICS_AI_MODEL, settings.lyricsAiModel)
            assertEquals("pl", settings.lyricsAiTargetLanguage)
            assertEquals("existing-manual-key", settings.lyricsAiApiKeyFor("OpenAI"))
            assertEquals("oauth-key", settings.lyricsOpenRouterOAuthKey)
            assertTrue(settings.lyricsAiTranslationEnabled)
            assertTrue(settings.lyricsShowTranslations)
            assertEquals("oauth-key" to settings.lyricsAiBaseUrl, settings.lyricsAiCredentials("OpenRouter"))
        }

    @Test
    fun failedVerificationLeavesConfigurationUntouchedAndCanRetry() =
        runBlocking {
            val settings = Settings(MapSettings())
            settings.lyricsOpenRouterOAuthKey = "oauth-key"
            settings.lyricsAiProvider = "OpenAI"
            settings.lyricsAiModel = "existing-model"
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    if (requests == 1) {
                        respond(
                            """{"error":{"code":404,"message":"No endpoints found matching your data policy"}}""",
                            HttpStatusCode.NotFound,
                        )
                    } else {
                        respond("""{"choices":[{"message":{"content":"{\"lines\":[\"Cześć\"]}"}}]}""")
                    }
                },
            ).use { http ->
                val client = LyricsTranslationClient(http)
                val failed = completeLyricsSetup(settings, client, AiProviderPresets.FREE_LYRICS_AI_MODEL, "pl")
                assertEquals(LyricsTranslationFailure.DATA_POLICY, failed.exceptionOrNull()?.lyricsTranslationFailure())
                assertFalse(settings.lyricsAiTranslationEnabled)
                assertFalse(settings.lyricsShowTranslations)
                assertEquals("OpenAI", settings.lyricsAiProvider)
                assertEquals("existing-model", settings.lyricsAiModel)
                completeLyricsSetup(settings, client, AiProviderPresets.FREE_LYRICS_AI_MODEL, "pl").getOrThrow()
                assertTrue(settings.lyricsAiTranslationEnabled)
            }
            assertEquals(2, requests)
        }

    @Test
    fun setupSavesTheFreeModelThatPassedAfterAnUpstreamThrottle() =
        runBlocking {
            val settings = Settings(MapSettings())
            settings.lyricsOpenRouterOAuthKey = "oauth-key"
            val alternative = "google/gemma-4-31b-it:free"
            var requests = 0
            HttpClient(
                MockEngine { request ->
                    assertFalse(settings.lyricsAiTranslationEnabled)
                    if (request.method == HttpMethod.Get) {
                        respond(
                            if (request.url.host == "models.dev") {
                                "{}"
                            } else {
                                """{"data":[{"id":"$alternative",
                        "architecture":{"input_modalities":["text"],"output_modalities":["text"]},
                        "pricing":{"prompt":"0","completion":"0"}}]}"""
                            },
                        )
                    } else {
                        requests++
                        if (requests == 1) {
                            respond(
                                """{"error":{"code":429,
                                    "message":"Temporarily rate-limited upstream"}}""",
                                HttpStatusCode.TooManyRequests,
                            )
                        } else {
                            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                            assertEquals(alternative, body.getValue("model").jsonPrimitive.content)
                            respond("""{"choices":[{"message":{"content":"{\"lines\":[\"Cześć\"]}"}}]}""")
                        }
                    }
                },
            ).use { http ->
                completeLyricsSetup(settings, LyricsTranslationClient(http), AiProviderPresets.FREE_LYRICS_AI_MODEL, "pl").getOrThrow()
            }
            assertEquals(2, requests)
            assertEquals(alternative, settings.lyricsAiModel)
            assertTrue(settings.lyricsAiTranslationEnabled)
        }

    @Test
    fun cancelledOrChangedCredentialsCannotEnableTranslation() =
        runBlocking {
            for (cancel in listOf(true, false)) {
                val settings = Settings(MapSettings())
                settings.lyricsOpenRouterOAuthKey = "oauth-key"
                HttpClient(
                    MockEngine {
                        if (cancel) throw CancellationException()
                        settings.lyricsOpenRouterOAuthKey = "replacement-key"
                        respond("""{"choices":[{"message":{"content":"{\"lines\":[\"Cześć\"]}"}}]}""")
                    },
                ).use { http ->
                    val client = LyricsTranslationClient(http)
                    if (cancel) {
                        assertFailsWith<CancellationException> {
                            completeLyricsSetup(
                                settings,
                                client,
                                AiProviderPresets.FREE_LYRICS_AI_MODEL,
                                "pl",
                            )
                        }
                    } else {
                        assertTrue(completeLyricsSetup(settings, client, AiProviderPresets.FREE_LYRICS_AI_MODEL, "pl").isFailure)
                    }
                }
                assertFalse(settings.lyricsAiTranslationEnabled)
            }
        }
}
