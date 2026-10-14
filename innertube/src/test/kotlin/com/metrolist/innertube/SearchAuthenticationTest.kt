package com.metrolist.innertube

import com.metrolist.innertube.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAuthenticationTest {
    @Test
    fun explicitSearchAuthenticationDoesNotLeakIntoLaterLookups() =
        runBlocking {
            val engine =
                MockEngine {
                    respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            HttpClient(engine) {
                install(ContentNegotiation) { json() }
            }.use { client ->
                val innerTube =
                    InnerTube(client).apply {
                        cookie = "SAPISID=session"
                        dataSyncId = "channel-sync-id"
                        authUser = "1"
                        useLoginForBrowse = true
                    }
                innerTube.search(YouTubeClient.WEB_REMIX, query = "submitted search", setLogin = true)
                innerTube.search(YouTubeClient.WEB_REMIX, query = "submitted search", params = "song-filter", setLogin = true)
                innerTube.search(YouTubeClient.WEB_REMIX, continuation = "next-page", setLogin = true)
                innerTube.search(YouTubeClient.WEB_REMIX, query = "paused history", setLogin = false)
                innerTube.search(YouTubeClient.WEB_REMIX, query = "artist lookup")

                assertEquals(5, engine.requestHistory.size)
                engine.requestHistory.forEachIndexed { index, request ->
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val account = body["context"]!!.jsonObject["user"]?.jsonObject?.get("onBehalfOfUser")
                    if (index < 3) {
                        assertNotNull(request.headers[HttpHeaders.Authorization])
                        assertTrue(request.headers[HttpHeaders.Cookie].orEmpty().contains("SAPISID=session"))
                        assertEquals("channel-sync-id", account?.jsonPrimitive?.content)
                    } else {
                        assertNull(request.headers[HttpHeaders.Authorization])
                        assertNull(request.headers[HttpHeaders.Cookie])
                        assertNull(request.headers["X-Goog-AuthUser"])
                        assertNull(account?.jsonPrimitive?.contentOrNull)
                    }
                }
            }
        }

    @Test
    fun automaticSearchesOmitAccountIdentityByDefault() =
        runBlocking {
            val engine =
                MockEngine {
                    respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            HttpClient(engine) {
                install(ContentNegotiation) { json() }
            }.use { client ->
                val innerTube =
                    InnerTube(client).apply {
                        cookie = "SAPISID=session"
                        dataSyncId = "channel-sync-id"
                        authUser = "1"
                        useLoginForBrowse = true
                    }

                innerTube.search(YouTubeClient.WEB_REMIX, query = "artist lookup")
                innerTube.search(YouTubeClient.WEB_REMIX, query = "14 m reproducciones", params = "artist-filter")
                innerTube.search(YouTubeClient.WEB_REMIX, continuation = "next-page")
                innerTube.getSearchSuggestions(YouTubeClient.WEB_REMIX, "unfinished query")

                assertEquals(4, engine.requestHistory.size)
                engine.requestHistory.forEach { request ->
                    assertNull(request.headers[HttpHeaders.Authorization])
                    assertNull(request.headers[HttpHeaders.Cookie])
                    assertNull(request.headers["X-Goog-AuthUser"])
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertNull(
                        body["context"]!!
                            .jsonObject["user"]
                            ?.jsonObject
                            ?.get("onBehalfOfUser")
                            ?.jsonPrimitive
                            ?.contentOrNull,
                    )
                }
            }
        }
}
