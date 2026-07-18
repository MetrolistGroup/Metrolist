package com.metrolist.music.discord

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DiscordAuthTest {

    private fun mockClient(handler: io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData): HttpClient {
        val engine = MockEngine(handler)
        return HttpClient(engine) {
            install(HttpTimeout)
            defaultRequest { url("https://discord.com/api/v10/oauth2/token") }
        }
    }

    @Test
    fun refresh_succeeds_withValidResponse() = runBlocking {
        val body = """
            {
              "access_token": "new-access-token",
              "refresh_token": "new-refresh-token",
              "expires_in": 604800,
              "scope": "rpc"
            }
        """.trimIndent()
        val client = mockClient { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v10/oauth2/token", request.url.encodedPath)
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val auth = DiscordAuth(client)
        val result = auth.refresh("old-refresh-token")
        assertEquals("new-access-token", result.accessToken)
        assertEquals("new-refresh-token", result.refreshToken)
        assertEquals(604800L, result.expiresInSec)
        assertEquals("rpc", result.scope)
    }

    @Test
    fun refresh_throwsInvalidGrant_onBadRequest() = runBlocking {
        val body = """{"error": "invalid_grant", "error_description": "refresh token revoked"}"""
        val client = mockClient { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v10/oauth2/token", request.url.encodedPath)
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val auth = DiscordAuth(client)
        try {
            auth.refresh("revoked-token")
            fail("Expected InvalidGrant")
        } catch (e: DiscordAuthException.InvalidGrant) {
            assertNotNull(e.message)
        } catch (e: Exception) {
            fail("Expected DiscordAuthException.InvalidGrant, got ${e::class.java.simpleName}")
        }
    }

    @Test
    fun refresh_throwsNetworkFailure_onServerError() = runBlocking {
        val body = """{"error": "server_error"}"""
        val client = mockClient { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v10/oauth2/token", request.url.encodedPath)
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val auth = DiscordAuth(client)
        try {
            auth.refresh("any-token")
            fail("Expected NetworkFailure")
        } catch (e: DiscordAuthException.NetworkFailure) {
            assertTrue(e.message!!.contains("500"))
        } catch (e: Exception) {
            fail("Expected DiscordAuthException.NetworkFailure, got ${e::class.java.simpleName}")
        }
    }

    @Test
    fun generatePkcePair_producesValidChallenge() {
        val pair = DiscordAuth.generatePkcePair()
        assertTrue("verifier should be non-empty", pair.verifier.isNotEmpty())
        assertTrue("challenge should be non-empty", pair.challenge.isNotEmpty())
        assertTrue("challenge should be URL-safe base64", !pair.challenge.contains("="))
        assertTrue("verifier should be URL-safe base64", !pair.verifier.contains("="))
        assertEquals(43, pair.challenge.length)
    }
}
