package com.metrolist.shared.viewmodels

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.responseDecodeDispatcher
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {
    @Test
    fun emptyShelfLoadsOnDemandAndFailedCursorRetriesBeforeTheOuterPage() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val previous = responseDecodeDispatcher
            responseDecodeDispatcher = StandardTestDispatcher(testScheduler)
            val requests = mutableListOf<String?>()
            var failShelf = true
            val client =
                HttpClient(
                    MockEngine(
                        MockEngineConfig().apply {
                            dispatcher = StandardTestDispatcher(testScheduler)
                            addHandler { request ->
                                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                                val token = body["continuation"]?.jsonPrimitive?.content
                                requests += token
                                val response =
                                    when (token) {
                                        null -> {
                                            """{"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"title":{"simpleText":"Tracks"},

"contents":[],
"continuations":[{"nextContinuationData":{"continuation":"shelf"}}]}}],
"continuations":[{"nextContinuationData":{"continuation":"outer"}}]}}}"""
                                        }

                                        "shelf" -> {
                                            if (failShelf) {
                                                "{}"
                                            } else {
                                                """{"continuationContents":{"musicShelfContinuation":{"contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"song"},

"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Song"}}}]}}]}}}"""
                                            }
                                        }

                                        "outer" -> {
                                            """{"continuationContents":{"sectionListContinuation":{"contents":[{"musicShelfRenderer":{"title":{"simpleText":"Second"},

"contents":[],
"continuations":[{"nextContinuationData":{"continuation":"second-shelf"}}]}}]}}}"""
                                        }

                                        "second-shelf" -> {
                                            """{"continuationContents":{"musicShelfContinuation":{"contents":[]}}}"""
                                        }

                                        else -> {
                                            error("Unexpected cursor")
                                        }
                                    }
                                respond(
                                    response,
                                    if (token == "shelf" && failShelf) HttpStatusCode.BadRequest else HttpStatusCode.OK,
                                    headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }
                        },
                    ),
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val model =
                    BrowseViewModel("artist-songs", null, null, this, YouTubeRepository(InnerTube(client, retryDelay = {})))
                advanceUntilIdle()
                assertEquals(listOf<String?>(null), requests)
                assertFalse(model.isLoading)
                assertTrue(
                    model.page.sections
                        .single()
                        .items
                        .isEmpty(),
                )
                assertTrue(model.hasMore)
                model.loadMore()
                advanceUntilIdle()
                assertTrue(model.loadMoreFailed)
                assertEquals(
                    "shelf",
                    model.page.sections
                        .single()
                        .continuation,
                )
                failShelf = false
                model.loadMore()
                advanceUntilIdle()
                assertFalse(model.loadMoreFailed)
                assertEquals(
                    listOf("song"),
                    model.page.sections
                        .single()
                        .items
                        .map { it.id },
                )
                model.loadMore()
                advanceUntilIdle()
                assertEquals(listOf(null, "shelf", "shelf", "outer"), requests)
                assertEquals(
                    "second-shelf",
                    model.page.sections
                        .last()
                        .continuation,
                )
                model.loadMore()
                advanceUntilIdle()
                assertEquals(listOf(null, "shelf", "shelf", "outer", "second-shelf"), requests)
                assertFalse(model.hasMore)
                assertEquals(listOf("Tracks", "Second"), model.page.sections.map { it.title })
            } finally {
                client.close()
                responseDecodeDispatcher = previous
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun localShelfWithoutEndpointShowsItsItemsWithoutNetwork() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        error("Local shelf must not request browse")
                    },
                )
            try {
                val section = HomeSection("Local", listOf(SongItem("seed", "Seed", null, emptyList(), null)))
                val model = BrowseViewModel(null, null, section.title, this, YouTubeRepository(InnerTube(client)), section)
                advanceUntilIdle()
                assertEquals(listOf(section), model.page.sections)
                assertFalse(model.isLoading)
                assertFalse(model.hasMore)
                model.loadMore()
                model.load()
                advanceUntilIdle()
                assertEquals(0, requests)
                assertEquals(listOf(section), model.page.sections)
            } finally {
                client.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun shelfCursorLoadsBeforeMoreEndpointAndRetainsFailuresForRetry() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val previous = responseDecodeDispatcher
            responseDecodeDispatcher = StandardTestDispatcher(testScheduler)
            var fail = true
            val requests = mutableListOf<JsonObject>()
            val cookies = mutableListOf<String?>()
            val client =
                browseClient { request ->
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    requests += body
                    cookies += request.headers[HttpHeaders.Cookie]
                    val token = body["continuation"]?.jsonPrimitive?.content
                    Triple(
                        if (fail) "{}" else shelfResponse(if (token == "shelf") "second" else "third", "next"),
                        if (fail) HttpStatusCode.BadRequest else HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            try {
                val section =
                    HomeSection(
                        "Recommendations",
                        listOf(SongItem("seed", "Seed", null, emptyList(), null)),
                        browseId = "more-endpoint",
                        params = "more-params",
                        continuation = "shelf",
                        isPersonal = true,
                    )
                val tube =
                    InnerTube(client, retryDelay = {}).apply {
                        cookie = "SAPISID=section-test"
                        useLoginForBrowse = false
                    }
                val model = BrowseViewModel(section.browseId, section.params, section.title, this, YouTubeRepository(tube), section)
                assertEquals(listOf(section), model.page.sections)
                assertTrue(model.isLoadingMore)
                advanceUntilIdle()
                assertTrue(model.loadMoreFailed)
                assertEquals(listOf(section), model.page.sections)
                fail = false
                model.loadMore()
                advanceUntilIdle()
                assertFalse(model.loadMoreFailed)
                assertEquals(
                    listOf("seed", "second"),
                    model.page.sections
                        .single()
                        .items
                        .map { it.id },
                )
                assertEquals(
                    "next",
                    model.page.sections
                        .single()
                        .continuation,
                )
                model.loadMore()
                advanceUntilIdle()
                assertEquals(
                    listOf("seed", "second", "third"),
                    model.page.sections
                        .single()
                        .items
                        .map { it.id },
                )
                assertFalse(model.hasMore)
                model.loadMore()
                advanceUntilIdle()
                assertEquals(listOf("shelf", "shelf", "next"), requests.map { it["continuation"]?.jsonPrimitive?.content })
                assertTrue(requests.all { it["browseId"] == null })
                assertTrue(cookies.all { it?.contains("SAPISID=section-test") == true })
            } finally {
                client.close()
                responseDecodeDispatcher = previous
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun moreEndpointKeepsSeedOnFailureAndLoadsOnlyFirstPageOnRetry() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val previous = responseDecodeDispatcher
            responseDecodeDispatcher = StandardTestDispatcher(testScheduler)
            var fail = true
            val requests = mutableListOf<JsonObject>()
            val client =
                browseClient { request ->
                    requests += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    Triple(
                        if (fail) {
                            "{}"
                        } else {
                            """{"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{
                              "title":{"simpleText":"More"},
                              "contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"remote"},
                                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Remote"}}}]}}],
                              "continuations":[{"nextContinuationData":{"continuation":"remote-next"}}]
                            }}]}}}"""
                        },
                        if (fail) HttpStatusCode.BadRequest else HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            try {
                val section = HomeSection("Seed", listOf(SongItem("seed", "Seed", null, emptyList(), null)))
                val model =
                    BrowseViewModel("more-endpoint", "more-params", section.title, this, YouTubeRepository(InnerTube(client)), section)
                assertEquals(listOf(section), model.page.sections)
                advanceUntilIdle()
                assertTrue(model.loadFailed)
                assertEquals(listOf(section), model.page.sections)
                fail = false
                model.load()
                advanceUntilIdle()
                assertFalse(model.loadFailed)
                assertFalse(model.isLoading)
                assertEquals(
                    listOf("remote"),
                    model.page.sections
                        .single()
                        .items
                        .map { it.id },
                )
                assertEquals(
                    "remote-next",
                    model.page.sections
                        .single()
                        .continuation,
                )
                assertTrue(model.hasMore)
                assertEquals(2, requests.size)
                requests.forEach { body ->
                    assertEquals("more-endpoint", body["browseId"]?.jsonPrimitive?.content)
                    assertEquals("more-params", body["params"]?.jsonPrimitive?.content)
                    assertNull(body["continuation"])
                }
            } finally {
                client.close()
                responseDecodeDispatcher = previous
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun refreshedMetadataPreservesShelfCursorAndSongPlaybackMetadata() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val previous = responseDecodeDispatcher
            responseDecodeDispatcher = StandardTestDispatcher(testScheduler)
            val client =
                browseClient {
                    Triple("{}", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            try {
                val target =
                    SongItem("target", "Old", "old.jpg", emptyList(), null, endpointParams = "play-target", playlistSetVideoId = "first")
                val secondOccurrence = target.copy(playlistSetVideoId = "second")
                val untouched = SongItem("other", "Other", null, emptyList(), null)
                val playlist = PlaylistItem("target", "Playlist", null)
                val section =
                    HomeSection(
                        "Shelf",
                        listOf(target, untouched, playlist, secondOccurrence),
                        subtitle = "Subtitle",
                        params = "shelf-params",
                        continuation = "next",
                        isPersonal = true,
                    )
                val model = BrowseViewModel(null, null, section.title, this, YouTubeRepository(InnerTube(client)), section)
                advanceUntilIdle()
                assertTrue(model.loadMoreFailed)
                val refreshed = target.copy(title = "New", thumbnail = "new.jpg", endpointParams = null)
                model.updateSongMetadata(refreshed)
                assertEquals(
                    listOf(
                        section.copy(
                            items =
                                listOf(
                                    target.copy(title = "New", thumbnail = "new.jpg"),
                                    untouched,
                                    playlist,
                                    secondOccurrence.copy(title = "New", thumbnail = "new.jpg"),
                                ),
                        ),
                    ),
                    model.page.sections,
                )
                assertTrue(model.hasMore)
                assertTrue(model.loadMoreFailed)
            } finally {
                client.close()
                responseDecodeDispatcher = previous
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun metadataRefreshSurvivesInFlightContinuationAndReload() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val previous = responseDecodeDispatcher
            responseDecodeDispatcher = StandardTestDispatcher(testScheduler)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client =
                browseClient {
                    started.complete(Unit)
                    release.await()
                    Triple(shelfResponse("target", "tail"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            try {
                val target = SongItem("target", "Old", "old.jpg", emptyList(), null, endpointParams = "play-target")
                val section = HomeSection("Shelf", listOf(target), continuation = "next")
                val model = BrowseViewModel(null, null, section.title, this, YouTubeRepository(InnerTube(client)), section)
                runCurrent()
                assertTrue(started.isCompleted)
                assertTrue(model.isLoadingMore)
                val refreshed = target.copy(title = "New", thumbnail = "new.jpg", endpointParams = null)
                model.updateSongMetadata(refreshed)
                release.complete(Unit)
                advanceUntilIdle()
                val items =
                    model.page.sections
                        .single()
                        .items
                assertEquals(listOf("New", "New"), items.map { it.title })
                assertEquals(listOf("new.jpg", "new.jpg"), items.map { it.thumbnail })
                assertEquals("play-target", (items.first() as SongItem).endpointParams)
                model.load()
                assertEquals(
                    listOf(target.copy(title = "New", thumbnail = "new.jpg")),
                    model.page.sections
                        .single()
                        .items,
                )
                advanceUntilIdle()
                assertEquals(
                    listOf("New", "New"),
                    model.page.sections
                        .single()
                        .items
                        .map { it.title },
                )
            } finally {
                release.complete(Unit)
                client.close()
                responseDecodeDispatcher = previous
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun shelfWithoutBrowseIdUsesConfiguredBrowseLogin() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val previous = responseDecodeDispatcher
            responseDecodeDispatcher = StandardTestDispatcher(testScheduler)
            val cookies = mutableListOf<String?>()
            val client =
                browseClient { request ->
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("shelf", body["continuation"]?.jsonPrimitive?.content)
                    assertNull(body["browseId"])
                    cookies += request.headers[HttpHeaders.Cookie]
                    Triple(shelfResponse("next-song", "shelf"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            try {
                val tube =
                    InnerTube(client).apply {
                        cookie = "SAPISID=browse-default-test"
                        useLoginForBrowse = true
                    }
                val section = HomeSection("Shelf", emptyList(), continuation = "shelf")
                val model = BrowseViewModel(null, null, section.title, this, YouTubeRepository(tube), section)
                advanceUntilIdle()
                assertFalse(model.loadMoreFailed)
                assertTrue(cookies.single()?.contains("SAPISID=browse-default-test") == true)
                assertEquals(
                    listOf("next-song"),
                    model.page.sections
                        .single()
                        .items
                        .map { it.id },
                )
            } finally {
                client.close()
                responseDecodeDispatcher = previous
                Settings.replaceSharedForTest(null)
            }
        }

    private fun TestScope.browseClient(response: suspend (HttpRequestData) -> Triple<String, HttpStatusCode, Headers>): HttpClient =
        HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        val (body, status, headers) = response(request)
                        respond(body, status, headers)
                    }
                },
            ),
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    private fun shelfResponse(
        id: String,
        continuation: String,
    ) = """{"continuationContents":{"musicShelfContinuation":{
          "contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"$id"},
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"$id"}}}]}}],
          "continuations":[{"nextContinuationData":{"continuation":"$continuation"}}]
        }}}"""
}
