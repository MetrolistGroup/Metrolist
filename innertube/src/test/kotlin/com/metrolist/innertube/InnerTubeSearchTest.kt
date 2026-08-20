package com.metrolist.innertube

import com.metrolist.innertube.models.YouTubeClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InnerTubeSearchTest {
    @Test
    fun `search attaches credentials for a signed-in login-capable client`() = runBlocking {
        val innerTube = innerTube { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/youtubei/v1/search", request.url.encodedPath)
            assertEquals("continuation-token", request.url.parameters["continuation"])
            assertEquals("continuation-token", request.url.parameters["ctoken"])
            assertEquals("false", request.url.parameters["prettyPrint"])
            assertEquals(YouTubeClient.WEB_REMIX.clientId, request.headers["X-YouTube-Client-Name"])
            assertEquals("visitor-data", request.headers["X-Goog-Visitor-Id"])
            assertEquals(SIGNED_IN_COOKIE, request.headers[HttpHeaders.Cookie])
            assertTrue(
                request.headers[HttpHeaders.Authorization]
                    ?.matches(Regex("SAPISIDHASH \\d+_[0-9a-f]{40}")) == true,
            )

            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertTrue(body.contains("\"query\":\"authenticated search\""))
            assertTrue(body.contains("\"params\":\"search-params\""))
            assertTrue(body.contains("\"visitorData\":\"visitor-data\""))

            okResponse()
        }.apply {
            visitorData = "visitor-data"
            cookie = SIGNED_IN_COOKIE
        }

        innerTube.search(
            client = YouTubeClient.WEB_REMIX,
            query = "authenticated search",
            params = "search-params",
            continuation = "continuation-token",
        )

        Unit
    }

    @Test
    fun `search remains anonymous when no cookie is configured`() = runBlocking {
        val innerTube = innerTube { request ->
            assertEquals(YouTubeClient.WEB_REMIX.clientId, request.headers["X-YouTube-Client-Name"])
            assertEquals(YouTubeClient.WEB_REMIX.clientVersion, request.headers["X-YouTube-Client-Version"])
            assertEquals("visitor-data", request.headers["X-Goog-Visitor-Id"])
            assertNull(request.headers[HttpHeaders.Cookie])
            assertNull(request.headers[HttpHeaders.Authorization])

            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertTrue(body.contains("\"query\":\"anonymous search\""))
            assertTrue(body.contains("\"visitorData\":\"visitor-data\""))

            okResponse()
        }.apply {
            visitorData = "visitor-data"
        }

        innerTube.search(
            client = YouTubeClient.WEB_REMIX,
            query = "anonymous search",
        )

        Unit
    }

    @Test
    fun `search omits credentials for a client that does not support login`() = runBlocking {
        val innerTube = innerTube { request ->
            assertEquals(YouTubeClient.WEB.clientId, request.headers["X-YouTube-Client-Name"])
            assertEquals("visitor-data", request.headers["X-Goog-Visitor-Id"])
            assertNull(request.headers[HttpHeaders.Cookie])
            assertNull(request.headers[HttpHeaders.Authorization])
            okResponse()
        }.apply {
            visitorData = "visitor-data"
            cookie = SIGNED_IN_COOKIE
        }

        innerTube.search(
            client = YouTubeClient.WEB,
            query = "unsupported login search",
        )

        Unit
    }

    private fun innerTube(handler: MockRequestHandler) = InnerTube { MockEngine(handler) }

    private fun MockRequestHandleScope.okResponse() = respond(
        content = ByteReadChannel("{}"),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private companion object {
        const val SIGNED_IN_COOKIE = "SAPISID=test-sapisid; HSID=test-hsid"
    }
}
