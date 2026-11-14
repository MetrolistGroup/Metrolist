package com.metrolist.shared.api.lyrics

import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenRouterAuthTest {
    @Test
    fun pkceMatchesRfc7636Vector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            OpenRouterAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun headlessFlowExchangesOnlyWithOpenRouterAndConsumesCode() =
        runBlocking {
            var calls = 0
            var challenge: String? = null
            val client =
                HttpClient(
                    MockEngine { request ->
                        calls++
                        assertEquals("https://openrouter.ai/api/v1/auth/keys", request.url.toString())
                        assertEquals(HttpMethod.Post, request.method)
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        assertEquals("sample-code", body["code"]?.jsonPrimitive?.content)
                        assertEquals("S256", body["code_challenge_method"]?.jsonPrimitive?.content)
                        val verifier = body["code_verifier"]?.jsonPrimitive?.content.orEmpty()
                        assertTrue(verifier.length in 43..128)
                        assertEquals(OpenRouterAuth.challenge(verifier), challenge)
                        respond("""{"key":"oauth-key"}""")
                    },
                )
            val auth = OpenRouterAuth(client)
            val url = Url(auth.start())
            assertEquals("openrouter.ai", url.host)
            assertEquals("/auth", url.encodedPath)
            assertTrue("callback_url" !in url.parameters.names())
            assertEquals("S256", url.parameters["code_challenge_method"])
            challenge = url.parameters["code_challenge"]
            assertEquals("oauth-key", auth.exchange("sample-code"))
            assertFailsWith<IllegalStateException> { auth.exchange("sample-code") }
            assertEquals(1, calls)
            client.close()
        }

    @Test
    fun restoredPendingVerifierCanExchangeButExpiredOrMalformedSessionsCannot() =
        runBlocking {
            var now = 1_000_000L
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("https://openrouter.ai/api/v1/auth/keys", request.url.toString())
                        respond("""{"key":"restored-key"}""")
                    },
                )
            val first = OpenRouterAuth(client) { now }
            val challenge = Url(first.start()).parameters["code_challenge"]
            val backing = MapSettings()
            Settings(backing).lyricsOpenRouterOAuthSession = assertNotNull(first.pendingSession())
            val pending = Settings(backing).lyricsOpenRouterOAuthSession
            val restored = OpenRouterAuth(client) { now }
            assertTrue(restored.restore(pending))
            assertEquals(challenge, OpenRouterAuth.challenge(pending.substringAfter(':')))
            assertEquals("restored-key", restored.exchange("code"))
            assertNull(restored.pendingSession())
            assertFalse(restored.restore("1000000:invalid-verifier"))
            assertFailsWith<IllegalStateException> { restored.exchange("code") }
            now += 600_001
            assertFalse(restored.restore(pending))
            assertNull(restored.pendingSession())
            client.close()
        }

    @Test
    fun expirationCancellationAndFailedExchangeNeverReturnKeys() =
        runBlocking {
            var now = 1_000_000L
            var calls = 0
            val client =
                HttpClient(
                    MockEngine {
                        calls++
                        respond("""{"key":"ignored"}""", HttpStatusCode.Forbidden)
                    },
                )
            val auth = OpenRouterAuth(client) { now }
            auth.start()
            auth.cancel()
            assertFailsWith<IllegalStateException> { auth.exchange("code") }
            auth.start()
            now += 600_001
            assertFailsWith<IllegalStateException> { auth.exchange("code") }
            assertEquals(0, calls)
            now++
            auth.start()
            assertFailsWith<IllegalArgumentException> { auth.exchange(" ") }
            assertFailsWith<IllegalStateException> { auth.exchange("code") }
            assertFailsWith<IllegalStateException> { auth.exchange("code") }
            assertEquals(1, calls)
            client.close()
        }

    @Test
    fun oauthKeyCannotBeSentToCustomEndpointsOrOtherProviders() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        try {
            val settings = Settings.shared
            settings.lyricsAiApiKey = "manual-key"
            settings.lyricsOpenRouterOAuthKey = "oauth-key"
            settings.lyricsAiBaseUrl = "https://untrusted.example/chat"
            assertEquals("oauth-key" to "https://openrouter.ai/api/v1/chat/completions", settings.lyricsAiCredentials("OpenRouter"))
            settings.setLyricsAiApiKey("OpenRouter", "manual-key")
            settings.lyricsAiProvider = "Anthropic"
            assertEquals("" to "https://untrusted.example/chat", settings.lyricsAiCredentials("Anthropic"))
            settings.setLyricsAiApiKey("Anthropic", "anthropic-key")
            assertEquals("anthropic-key" to "https://untrusted.example/chat", settings.lyricsAiCredentials("Anthropic"))
            assertEquals("oauth-key" to "https://openrouter.ai/api/v1/chat/completions", settings.lyricsAiCredentials("OpenRouter"))
            settings.lyricsOpenRouterOAuthKey = ""
            assertEquals("manual-key" to "https://untrusted.example/chat", settings.lyricsAiCredentials("OpenRouter"))
        } finally {
            Settings.replaceSharedForTest(null)
        }
    }
}
