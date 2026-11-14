package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.PodcastPage
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.PodcastPager
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

class PodcastContinuationTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun loadsEveryEpisodePageAndPreservesSavedMembership() =
        runBlocking {
            val responses =
                mapOf(
                    "first" to response("episode-21", "second"),
                    "second" to response("episode-22", null),
                )
            val requested = mutableListOf<String>()
            val page =
                YouTubePodcastParser.loadContinuations(
                    initial =
                        PodcastPage(
                            PodcastItem("podcast", "Podcast", null, author = "Creator"),
                            (1..20).map { EpisodeItem("episode-$it", "Episode $it", null) },
                            continuations = listOf("first"),
                        ),
                    savedEpisodes = listOf(EpisodeItem("episode-22", "Episode 22", null, playlistSetVideoId = "saved-22")),
                ) { token ->
                    requested += token
                    responses.getValue(token)
                }

            assertEquals(listOf("first", "second"), requested)
            assertEquals((1..22).map { "episode-$it" }, page.episodes.map { it.id })
            assertEquals("22", page.podcast.episodeCount)
            assertEquals("podcast", page.episodes.last().podcastId)
            assertEquals(
                "Creator",
                page.episodes
                    .last()
                    .author
                    ?.name,
            )
            assertTrue(page.episodes.last().saved)
            assertEquals("saved-22", page.episodes.last().playlistSetVideoId)
            assertNull(page.continuation)
        }

    @Test
    fun multipleBranchesSurviveScrollRequestsAndRetryWithoutPrefetchOrCycles() =
        runBlocking {
            val requested = mutableListOf<String?>()
            var fail = true
            val engine =
                MockEngine { request ->
                    val token =
                        json
                            .parseToJsonElement(
                                (request.body as TextContent).text,
                            ).jsonObject["continuation"]
                            ?.jsonPrimitive
                            ?.content
                    requested += token
                    if (token == "b" && fail) {
                        respond("{}", HttpStatusCode.Forbidden)
                    } else {
                        val body =
                            when (token) {
                                null -> multipleContinuationResponse
                                "a" -> json.encodeToString(BrowseResponse.serializer(), response("first", "c", "b", "a"))
                                "b" -> json.encodeToString(BrowseResponse.serializer(), response("second", "a", "c"))
                                "c" -> json.encodeToString(BrowseResponse.serializer(), response("third", null))
                                else -> error("Unexpected continuation")
                            }
                        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            HttpClient(engine) { install(ContentNegotiation) { json(json) } }.use { client ->
                val pager = PodcastPager(this, YouTubeRepository(InnerTube(client, retryDelay = {})).podcastPageLoader("show"))
                pager.loadMore()
                val first = assertNotNull(pager.state.first { !it.isLoading }.page)
                assertEquals(listOf<String?>(null), requested)
                assertEquals(listOf("a", "b"), first.continuations)
                pager.loadMore()
                val second = assertNotNull(pager.state.first { !it.isLoading }.page)
                assertEquals(listOf(null, "a"), requested)
                assertEquals(listOf("b", "c"), second.continuations)
                assertEquals(listOf("first"), second.episodes.map { it.id })
                pager.loadMore()
                val failed = pager.state.first { !it.isLoading }
                assertNotNull(failed.error)
                assertEquals(second, failed.page)
                fail = false
                pager.retry()
                val retried = assertNotNull(pager.state.first { !it.isLoading }.page)
                assertEquals(listOf("c"), retried.continuations)
                pager.loadMore()
                val complete = assertNotNull(pager.state.first { !it.isLoading }.page)
                assertEquals(listOf("first", "second", "third"), complete.episodes.map { it.id })
                assertNull(complete.continuation)
                pager.loadMore()
                assertEquals(listOf(null, "a", "b", "b", "c"), requested)
            }
        }

    @Test
    fun wholePodcastRetryRetainsPendingBranchesAndConsumedTokens() =
        runBlocking {
            val initial = YouTubePodcastParser.parsePodcast(json.decodeFromString(multipleContinuationResponse), "show", emptyList())
            val partial =
                YouTubePodcastParser.loadContinuations(initial, emptyList()) { token ->
                    if (token == "a") response("second", "c", "b") else error("offline")
                }
            assertEquals(listOf("b", "c"), partial.continuations)
            assertEquals(listOf("first", "second"), partial.episodes.map { it.id })
            val requested = mutableListOf<String>()
            val complete =
                YouTubePodcastParser.loadContinuations(partial, emptyList()) { token ->
                    requested += token
                    if (token == "b") response("second", "a", "c") else response("third", null)
                }
            assertEquals(listOf("b", "c"), requested)
            assertEquals(listOf("first", "second", "third"), complete.episodes.map { it.id })
            assertNull(complete.continuation)
        }

    @Test
    fun preservesLoadedEpisodesOnContinuationFailure() =
        runBlocking {
            val initial =
                PodcastPage(
                    PodcastItem("podcast", "Podcast", null),
                    listOf(EpisodeItem("first", "First", null)),
                    continuations = listOf("next"),
                )
            val page = YouTubePodcastParser.loadContinuations(initial, emptyList()) { error("offline") }
            assertEquals(initial, page)
        }

    @Test
    fun retryResumesTheFailedContinuationWithoutLosingOrDuplicatingEpisodes() =
        runBlocking {
            val initial =
                PodcastPage(
                    PodcastItem("podcast", "Podcast", null),
                    listOf(EpisodeItem("first", "First", null)),
                    continuations = listOf("next"),
                )
            val partial =
                YouTubePodcastParser.loadContinuations(initial, emptyList()) { token ->
                    if (token == "next") response("second", "failed") else error("offline")
                }
            assertEquals("failed", partial.continuation)
            assertEquals(listOf("first", "second"), partial.episodes.map { it.id })
            val requested = mutableListOf<String>()
            val complete =
                YouTubePodcastParser.loadContinuations(partial, emptyList()) { token ->
                    requested += token
                    if (token == "failed") response("second", "last") else response("third", null)
                }
            assertEquals(listOf("failed", "last"), requested)
            assertEquals(listOf("first", "second", "third"), complete.episodes.map { it.id })
            assertNull(complete.continuation)
        }

    @Test
    fun repositoryKeepsTheFailedCursorAndResumesWithoutReloadingTheFirstPage() =
        runBlocking {
            val initial =
                PodcastPage(
                    PodcastItem("podcast", "Podcast", null),
                    listOf(EpisodeItem("first", "First", null)),
                    continuations = listOf("failed"),
                )
            var fail = true
            val engine =
                MockEngine {
                    if (fail) {
                        respond("{}", HttpStatusCode.Forbidden)
                    } else {
                        respond(
                            json.encodeToString(BrowseResponse.serializer(), response("second", null)),
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            HttpClient(engine) {
                install(ContentNegotiation) { json(json) }
            }.use { client ->
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                val partial = assertNotNull(repository.getPodcast("podcast", initial))
                assertEquals(initial, partial)
                fail = false
                val complete = assertNotNull(repository.getPodcast("podcast", partial))
                assertEquals(listOf("first", "second"), complete.episodes.map { it.id })
                assertNull(complete.continuation)
                val bodies = engine.requestHistory.map { json.parseToJsonElement((it.body as TextContent).text).jsonObject }
                assertTrue(bodies.none { it["browseId"]?.jsonPrimitive?.content == "podcast" })
                assertEquals(listOf("failed", "failed"), bodies.mapNotNull { it["continuation"]?.jsonPrimitive?.content })
            }
        }

    @Test
    fun streamEmitsFirstPageWithoutLoadingAndDeduplicatesOnlyNewEpisodes() =
        runBlocking {
            val initial =
                PodcastPage(
                    PodcastItem("podcast", "Podcast", null),
                    listOf(EpisodeItem("first", "First", null)),
                    continuations = listOf("next"),
                )
            var calls = 0
            val pages =
                YouTubePodcastParser.pages(initial, emptyList()) { token ->
                    calls++
                    if (token == "next") response("first", "last") else response("second", null)
                }
            assertEquals(initial, pages.first())
            assertEquals(0, calls)
            val all = pages.toList()
            assertEquals(listOf(listOf("first"), emptyList(), listOf("second")), all.map { it.episodes.map { it.id } })
            assertEquals("2", all.last().podcast.episodeCount)
            assertEquals(2, calls)
        }

    @Test
    fun streamAndCollectorPropagateCancellation() =
        runBlocking<Unit> {
            val initial = PodcastPage(PodcastItem("podcast", "Podcast", null), emptyList(), continuations = listOf("next"))
            assertFailsWith<CancellationException> {
                YouTubePodcastParser.loadContinuations(initial, emptyList()) { throw CancellationException() }
            }
        }

    @Test
    fun firstPageCacheAvoidsRepeatRequestsAndNeverCachesSavedMembership() =
        runBlocking {
            val engine =
                MockEngine {
                    respond(
                        json.encodeToString(BrowseResponse.serializer(), response("one", "next")),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            HttpClient(engine) { install(ContentNegotiation) { json(json) } }.use { client ->
                val innerTube = InnerTube(client, retryDelay = {})
                val repository = YouTubeRepository(innerTube)
                val saved = listOf(EpisodeItem("one", "One", null, saved = true, playlistSetVideoId = "entry"))
                assertTrue(
                    repository
                        .getPodcastPages("show", saved)
                        .first()
                        .episodes
                        .single()
                        .saved,
                )
                assertEquals(
                    false,
                    repository
                        .getPodcastPages("show")
                        .first()
                        .episodes
                        .single()
                        .saved,
                )
                assertEquals(1, engine.requestHistory.size)
                innerTube.cookie = "SAPISID=changed-account"
                repository.getPodcastPages("show").first()
                assertEquals(2, engine.requestHistory.size)
            }
        }

    @Test
    fun latestEpisodeStopsBeforeLoadingOlderPages() =
        runBlocking {
            val engine =
                MockEngine {
                    respond(
                        json.encodeToString(BrowseResponse.serializer(), response("latest", "older")),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            HttpClient(engine) { install(ContentNegotiation) { json(json) } }.use { client ->
                val episode = YouTubeRepository(InnerTube(client, retryDelay = {})).getLatestPodcastEpisode("show")
                assertEquals("latest", episode?.id)
                assertEquals("show", episode?.podcastId)
                assertEquals(1, engine.requestHistory.size)
            }
        }

    @Test
    fun latestEpisodeFollowsEmptyFirstPage() =
        runBlocking {
            var requests = 0
            val engine =
                MockEngine {
                    val page =
                        if (requests++ == 0) {
                            """{"continuationContents":{"musicShelfContinuation":{"contents":[],"continuations":[{"nextContinuationData":{"continuation":"next"}}]}}}"""
                        } else {
                            json.encodeToString(BrowseResponse.serializer(), response("latest", "older"))
                        }
                    respond(page, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            HttpClient(engine) { install(ContentNegotiation) { json(json) } }.use { client ->
                assertEquals("latest", YouTubeRepository(InnerTube(client, retryDelay = {})).getLatestPodcastEpisode("show")?.id)
                assertEquals(2, requests)
            }
        }

    @Test
    fun latestEpisodeReturnsNullForEmptyPodcast() =
        runBlocking {
            val engine =
                MockEngine {
                    respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            HttpClient(engine) { install(ContentNegotiation) { json(json) } }.use { client ->
                assertNull(YouTubeRepository(InnerTube(client, retryDelay = {})).getLatestPodcastEpisode("show"))
                assertEquals(1, engine.requestHistory.size)
            }
        }

    @Test
    fun latestEpisodePropagatesFailuresAndCancellation() =
        runBlocking {
            val failure = IllegalStateException("Offline")
            for (error in listOf(failure, CancellationException("Cancelled"))) {
                val engine = MockEngine { throw error }
                HttpClient(engine) { install(ContentNegotiation) { json(json) } }.use { client ->
                    val thrown =
                        assertFailsWith<Exception> {
                            YouTubeRepository(InnerTube(client, retryDelay = {})).getLatestPodcastEpisode("show")
                        }
                    assertEquals(error::class, thrown::class)
                }
            }
        }

    @Test
    fun screenLoaderRejectsContinuationAfterAccountChange() =
        runBlocking {
            val engine =
                MockEngine {
                    respond(
                        json.encodeToString(BrowseResponse.serializer(), response("one", "next")),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            HttpClient(engine) { install(ContentNegotiation) { json(json) } }.use { client ->
                val innerTube = InnerTube(client, retryDelay = {})
                val loader = YouTubeRepository(innerTube).podcastPageLoader("show")
                val first = loader(null).first()
                innerTube.cookie = "SAPISID=changed-account"
                assertFailsWith<CancellationException> { loader(first).toList() }
                assertEquals(1, engine.requestHistory.size)
            }
        }

    @Test
    fun firstPageCacheHasBoundedCapacity() =
        runBlocking {
            val engine =
                MockEngine {
                    respond(
                        json.encodeToString(BrowseResponse.serializer(), response("one", null)),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            HttpClient(engine) { install(ContentNegotiation) { json(json) } }.use { client ->
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                repeat(9) { repository.getPodcastPages("show-$it").first() }
                repository.getPodcastPages("show-8").first()
                assertEquals(9, engine.requestHistory.size)
                repository.getPodcastPages("show-0").first()
                assertEquals(10, engine.requestHistory.size)
            }
        }

    private val multipleContinuationResponse =
        """
        {
          "contents": {
            "sectionListRenderer": {
              "contents": [{
                "musicShelfRenderer": {
                  "contents": [{
                    "musicMultiRowListItemRenderer": {
                      "onTap": {"watchEndpoint": {"videoId": "first"}},
                      "title": {"runs": [{"text": "First"}]}
                    }
                  }],
                  "continuations": [{"nextContinuationData": {"continuation": "a"}}]
                }
              }],
              "continuations": [
                {"nextContinuationData": {"continuation": "b"}},
                {"nextContinuationData": {"continuation": "a"}},
                {"reloadContinuationData": {"continuation": "refresh"}}
              ]
            }
          }
        }
        """.trimIndent()

    private fun response(
        id: String,
        vararg next: String?,
    ): BrowseResponse =
        json.decodeFromString(
            """
            {
              "continuationContents": {
                "musicShelfContinuation": {
                  "contents": [{
                    "musicMultiRowListItemRenderer": {
                      "onTap": {"watchEndpoint": {"videoId": "$id"}},
                      "title": {"runs": [{"text": "$id"}]}
                    }
                  }]
                  , "continuations": [${next.filterNotNull().joinToString { """{"nextContinuationData":{"continuation":"$it"}}""" }}]
                }
              }
            }
            """.trimIndent(),
        )
}
