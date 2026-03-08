package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchHistorySyncTest {
    @Test
    fun clearingHistoryCancelsWhenAccountChangesDuringFeedbackOrRead() =
        runTest {
            for (switchDuringRead in listOf(false, true)) {
                lateinit var tube: InnerTube
                val engine =
                    MockEngine { request ->
                        val isRead = !request.url.encodedPath.endsWith("/feedback")
                        if (isRead == switchDuringRead) tube.cookie = "SAPISID=replacement"
                        respondJson(
                            if (isRead) {
                                """{"historySuggestionRenderer":{
                                    "suggestion":{"runs":[{"text":"older search"}]},
                                    "serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"next-token"}}
                                }}"""
                            } else {
                                "{}"
                            },
                        )
                    }
                tube = authenticatedInnerTube(engine)
                try {
                    assertFailsWith<CancellationException> { YouTubeRepository(tube).clearSearchHistory(listOf("old-token")) }
                    assertEquals(if (switchDuringRead) 2 else 1, engine.requestHistory.size)
                    engine.requestHistory.forEach { assertTrue(it.headers[HttpHeaders.Cookie].orEmpty().contains("SAPISID=session")) }
                } finally {
                    tube.close()
                }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun clearingHistoryCancelsDuringBatchDelay() =
        runTest {
            val engine =
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler { respondJson("{}") }
                    },
                )
            val tube = authenticatedInnerTube(engine)
            try {
                val clearing = async { YouTubeRepository(tube).clearSearchHistory(listOf("old-token")) }
                runCurrent()
                tube.cookie = "SAPISID=replacement"
                advanceUntilIdle()
                assertFailsWith<CancellationException> { clearing.await() }
                assertEquals(1, engine.requestHistory.size)
            } finally {
                tube.close()
            }
        }

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun automaticSearchesOmitAccountIdentityByDefault() =
        runBlocking {
            val engine = MockEngine { respondJson("{}") }
            val repository = YouTubeRepository(authenticatedInnerTube(engine))

            repository.search("artist lookup")
            repository.getSearchSummary("preview")
            SearchFilter.entries.forEach { filter ->
                repository.searchFiltered("track lookup", filter)
                repository.searchFiltered("track lookup", filter, continuation = "next-page")
            }
            repository.getSearchSuggestions("unfinished query")

            assertEquals(3 + SearchFilter.entries.size * 2, engine.requestHistory.size)
            engine.requestHistory.forEach { assertAccountIdentity(it, expected = false) }
        }

    @Test
    fun searchAuthenticationRequiresExplicitOptIn() =
        runBlocking {
            listOf(false, true).forEach { recordSearchHistory ->
                val engine = MockEngine { respondJson("{}") }
                val repository = YouTubeRepository(authenticatedInnerTube(engine))

                repository.search("submitted search", recordSearchHistory = recordSearchHistory)
                repository.getSearchSummary("submitted search", recordSearchHistory = recordSearchHistory)
                SearchFilter.entries.forEach { filter ->
                    repository.searchFiltered("submitted search", filter, recordSearchHistory = recordSearchHistory)
                    repository.searchFiltered(
                        "submitted search",
                        filter,
                        continuation = "next-page",
                        recordSearchHistory = recordSearchHistory,
                    )
                }

                assertEquals(2 + SearchFilter.entries.size * 2, engine.requestHistory.size)
                engine.requestHistory.forEach { assertAccountIdentity(it, expected = recordSearchHistory) }
            }
        }

    private fun assertAccountIdentity(
        request: HttpRequestData,
        expected: Boolean,
    ) {
        val account = requestBody(request)["context"]!!.jsonObject["user"]?.jsonObject?.get("onBehalfOfUser")
        if (expected) {
            assertNotNull(request.headers[HttpHeaders.Authorization])
            assertTrue(request.headers[HttpHeaders.Cookie].orEmpty().contains("SAPISID=session"))
            assertEquals("channel-sync-id", account?.jsonPrimitive?.content)
        } else {
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers[HttpHeaders.Cookie])
            assertNull(request.headers["X-Goog-AuthUser"])
            assertNull(account)
        }
    }

    @Test
    fun remoteHistoryKeepsOnlyHistorySuggestionsAndRemovalTokens() =
        runBlocking {
            val response =
                """
                {
                  "contents": [{
                    "searchSuggestionsSectionRenderer": {
                      "contents": [
                        {
                          "historySuggestionRenderer": {
                            "suggestion": {"runs": [{"text": "website search"}]},
                            "icon": {"iconType": "SEARCH_HISTORY"},
                            "serviceEndpoint": {"feedbackEndpoint": {"feedbackToken": "remove-one"}}
                          }
                        },
                        {
                          "searchSuggestionRenderer": {
                            "suggestion": {"runs": [{"text": "ordinary suggestion"}]},
                            "icon": {"iconType": "SEARCH"}
                          }
                        },
                        {
                          "searchSuggestionRenderer": {
                            "suggestion": {"runs": [{"text": "older website search"}]},
                            "toggledServiceEndpoint": {"feedbackEndpoint": {"feedbackToken": "remove-two"}}
                          }
                        }
                      ]
                    }
                  }]
                }
                """.trimIndent()
            val engine = MockEngine { respondJson(response) }
            val repository = YouTubeRepository(authenticatedInnerTube(engine))

            val history = repository.getSearchHistory()

            assertEquals(
                listOf(
                    YouTubeSearchHistoryEntry("website search", "remove-one"),
                    YouTubeSearchHistoryEntry("older website search", "remove-two"),
                ),
                history,
            )
            val request = engine.requestHistory.single()
            assertNotNull(request.headers[HttpHeaders.Authorization])
            assertEquals("", requestBody(request)["input"]!!.jsonPrimitive.content)
        }

    @Test
    fun clearingRemoteHistoryContinuesPastTheVisiblePage() =
        runTest {
            var suggestionsRequest = 0
            val nextPage =
                """
                {"contents":[{"historySuggestionRenderer":{
                  "suggestion":{"runs":[{"text":"older search"}]},
                  "serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"remove-two"}}
                }}]}
                """.trimIndent()
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/feedback")) {
                        respondJson("{}")
                    } else {
                        respondJson(if (suggestionsRequest++ == 0) nextPage else "{}")
                    }
                }
            val repository = YouTubeRepository(authenticatedInnerTube(engine))

            repository.clearSearchHistory(listOf("remove-one"))

            val removedTokens =
                engine.requestHistory
                    .filter { it.url.encodedPath.endsWith("/feedback") }
                    .map {
                        requestBody(it)["feedbackTokens"]!!
                            .jsonArray
                            .single()
                            .jsonPrimitive.content
                    }
            assertEquals(listOf("remove-one", "remove-two"), removedTokens)
        }

    @Test
    fun removingRemoteHistoryUsesAuthenticatedFeedback() =
        runBlocking {
            val engine = MockEngine { respondJson("{}") }
            val repository = YouTubeRepository(authenticatedInnerTube(engine))

            repository.removeSearchHistoryItems(listOf("remove-one", "remove-one", "", "remove-two"))

            val request = engine.requestHistory.single()
            assertTrue(request.url.encodedPath.endsWith("/feedback"))
            assertNotNull(request.headers[HttpHeaders.Authorization])
            val tokens = requestBody(request)["feedbackTokens"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("remove-one", "remove-two"), tokens)
        }

    private fun authenticatedInnerTube(engine: MockEngine): InnerTube =
        InnerTube(
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            },
            retryDelay = {},
        ).apply {
            cookie = "SAPISID=session; PREF=theme=3"
            visitorData = "visitor-data"
            dataSyncId = "channel-sync-id"
            authUser = "0"
            useLoginForBrowse = true
        }

    private fun MockRequestHandleScope.respondJson(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

    private fun requestBody(request: HttpRequestData) = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
}
