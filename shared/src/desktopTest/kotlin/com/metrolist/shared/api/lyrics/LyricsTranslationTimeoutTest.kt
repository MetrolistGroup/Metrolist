package com.metrolist.shared.api.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LyricsTranslationTimeoutTest {
    @Test
    fun aiRequestsOverrideBothSharedRequestAndSocketTimeouts() =
        runTest {
            HttpClient(
                MockEngine { request ->
                    val timeout = assertNotNull(request.getCapabilityOrNull(HttpTimeoutCapability))
                    assertEquals(120_000L, timeout.requestTimeoutMillis)
                    assertEquals(120_000L, timeout.socketTimeoutMillis)
                    respond("""{"choices":[{"message":{"content":"{\"lines\":[\"Hola\"]}"}}]}""")
                },
            ) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 100
                    socketTimeoutMillis = 100
                }
            }.use { client ->
                val result =
                    LyricsTranslationClient(client).translateLyricsLines(
                        lines = listOf("Hello"),
                        provider = "OpenRouter",
                        targetLanguage = "es",
                        mode = "Literal",
                        apiKey = "test-key",
                        baseUrl = "https://translation.example/chat",
                        model = "test-model",
                    )
                assertEquals(listOf("Hola"), result.getOrThrow().translatedLines)
            }
        }
}
