package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.settings.Settings
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BrowsePageSectionsTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun browsePageFollowsShelfContinuationWhenFirstPageIsEmpty() =
        runBlocking {
            val responses =
                ArrayDeque(
                    listOf(
                        EMPTY_FIRST_PAGE_PLAYLIST_BROWSE,
                        PLAYLIST_SHELF_CONTINUATION,
                    ),
                )
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = responses.removeFirst(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

            val pageData = YouTubeRepository(InnerTube(client)).getBrowsePageData("RDAMPLmoodmix")

            assertEquals(1, pageData.sections.size)
            assertEquals("Tracks", pageData.sections[0].title)
            assertTrue(pageData.sections[0].isListStyle)
            assertEquals(listOf("track"), pageData.sections[0].items.map { it.id })
            client.close()
        }

    @Test
    fun initialBrowseKeepsEmptyShelfCursorWithoutDrainingIt() =
        runBlocking {
            val responses = ArrayDeque(listOf(EMPTY_FIRST_PAGE_PLAYLIST_BROWSE, PLAYLIST_SHELF_CONTINUATION))
            val engine = responseEngine(responses)
            val client = client(engine)
            try {
                val repository = YouTubeRepository(InnerTube(client))
                val initial = repository.getBrowsePageData("RDAMPLmoodmix", loadAllPages = false)
                assertEquals(1, engine.requestHistory.size)
                val shelf = initial.sections.single()
                assertTrue(shelf.items.isEmpty())
                val next = repository.getBrowseShelfContinuation(requireNotNull(shelf.continuation), setLogin = false)
                assertEquals(listOf("track"), next.items.map { it.id })
                assertEquals(2, engine.requestHistory.size)
            } finally {
                client.close()
            }
        }

    @Test
    fun homeContinuationPreservesHeaderAndContinuationItemCursor() =
        runBlocking {
            val response = """{"continuationContents":{"sectionListContinuation":{"contents":[{
          "musicShelfRenderer":{
            "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Shelf title"}]}}},
            "contents":[{"musicResponsiveListItemRenderer":{
              "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track"}]}}}],
              "playlistItemData":{"videoId":"track"}
            }},{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next"}}}}]
          }
        }]}}}"""
            val client = client(responseEngine(ArrayDeque(listOf(response))))
            try {
                val (sections, _) = YouTubeRepository(InnerTube(client)).getHomeSectionsContinuation("home", loadAllPages = false)
                assertEquals("Shelf title", sections.single().title)
                assertEquals("next", sections.single().continuation)
                assertEquals(listOf("track"), sections.single().items.map { it.id })
            } finally {
                client.close()
            }
        }

    @Test
    fun homeContinuationKeepsShelfCursorAfterFourPages() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        val response =
                            if (requests++ == 0) {
                                """{"continuationContents":{"sectionListContinuation":{"contents":[{
                    "musicShelfRenderer":{"title":{"runs":[{"text":"Tracks"}]},"contents":[],
                    "continuations":[{"nextContinuationData":{"continuation":"shelf-1"}}]}
                }]}}}"""
                            } else {
                                """{"continuationContents":{"musicShelfContinuation":{"contents":[],
                    "continuations":[{"nextContinuationData":{"continuation":"shelf-$requests"}}]}}}"""
                            }
                        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val (sections, _) = YouTubeRepository(InnerTube(client)).getHomeSectionsContinuation("home-next")
                assertEquals(5, requests)
                assertEquals("shelf-5", sections.single().continuation)
            } finally {
                client.close()
            }
        }

    @Test
    fun browsePagePropagatesRequestFailure() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = "{}",
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

            assertFailsWith<IllegalStateException> {
                YouTubeRepository(InnerTube(client, retryDelay = {})).getBrowsePageData("RDAMPLmoodmix")
            }
            client.close()
        }

    @Test
    fun accountBrowseShelfContinuationKeepsLoginContext() =
        runBlocking {
            val responses = ArrayDeque(listOf(EMPTY_FIRST_PAGE_PLAYLIST_BROWSE, PLAYLIST_SHELF_CONTINUATION))
            val engine = responseEngine(responses)
            val innerTube = InnerTube(client(engine)).also { it.dataSyncId = "account-sync-id" }

            YouTubeRepository(innerTube).getBrowsePageData("FEmusic_library_landing")

            assertTrue(requestBody(engine, 0).contains("account-sync-id"))
            assertTrue(requestBody(engine, 1).contains("account-sync-id"))
        }

    @Test
    fun moodsAndGenrePagesKeepPersonalizedLoginContext() =
        runBlocking {
            for (browseId in listOf("FEmusic_moods_and_genres", "FEmusic_moods_and_genres_category")) {
                val responses = ArrayDeque(listOf(EMPTY_FIRST_PAGE_PLAYLIST_BROWSE, PLAYLIST_SHELF_CONTINUATION))
                val engine = responseEngine(responses)
                val client = client(engine)
                try {
                    val innerTube = InnerTube(client).also { it.dataSyncId = "account-sync-id" }
                    YouTubeRepository(innerTube).getBrowsePageData(browseId)
                    assertTrue(requestBody(engine, 0).contains("account-sync-id"))
                    assertTrue(requestBody(engine, 1).contains("account-sync-id"))
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun publicBrowseShelfContinuationStaysAnonymous() =
        runBlocking {
            val responses = ArrayDeque(listOf(EMPTY_FIRST_PAGE_PLAYLIST_BROWSE, PLAYLIST_SHELF_CONTINUATION))
            val engine = responseEngine(responses)
            val innerTube = InnerTube(client(engine)).also { it.dataSyncId = "account-sync-id" }

            YouTubeRepository(innerTube).getBrowsePageData("RDAMPLmoodmix")

            assertTrue(!requestBody(engine, 0).contains("account-sync-id"))
            assertTrue(!requestBody(engine, 1).contains("account-sync-id"))
        }

    @Test
    fun browsePageParsesListShelfDirectlyWhenContentsPresent() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = DIRECT_TRACKS_PLAYLIST_BROWSE,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

            val pageData = YouTubeRepository(InnerTube(client)).getBrowsePageData("RDAMPLmoodmix")

            assertEquals(1, pageData.sections.size)
            assertEquals(listOf("track"), pageData.sections[0].items.map { it.id })
            client.close()
        }

    @Test
    fun browsePageFollowsActionBasedShelfContinuation() =
        runBlocking {
            val responses =
                ArrayDeque(
                    listOf(
                        EMPTY_FIRST_PAGE_PLAYLIST_BROWSE,
                        ACTIONS_PLAYLIST_CONTINUATION,
                    ),
                )
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = responses.removeFirst(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

            val pageData = YouTubeRepository(InnerTube(client)).getBrowsePageData("RDAMPLmoodmix")

            assertEquals(
                listOf("action-track"),
                pageData.sections
                    .single()
                    .items
                    .map { it.id },
            )
            client.close()
        }

    @Test
    fun browsePageKeepsCursorWhenShelfContinuationFails() =
        runBlocking {
            val responses =
                ArrayDeque(
                    listOf(
                        EMPTY_FIRST_PAGE_PLAYLIST_BROWSE,
                    ),
                )
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = responses.removeFirst(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

            val pageData = YouTubeRepository(InnerTube(client)).getBrowsePageData("RDAMPLmoodmix")

            assertTrue(
                pageData.sections
                    .single()
                    .items
                    .isEmpty(),
            )
            assertTrue(pageData.sections.single().continuation != null)
            client.close()
        }

    @Test
    fun browsePageUsesSecondaryContentsOverHeaderOnlyTabs() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = TABS_HEADER_WITH_SECONDARY_TRACKS_BROWSE,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

            val pageData = YouTubeRepository(InnerTube(client)).getBrowsePageData("RDAMPLmoodmix")

            assertEquals(1, pageData.sections.size)
            assertEquals(listOf("track"), pageData.sections[0].items.map { it.id })
            client.close()
        }

    private fun responseEngine(responses: ArrayDeque<String>) =
        MockEngine {
            respond(
                content = responses.removeFirst(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

    private fun client(engine: MockEngine) =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

    private fun requestBody(
        engine: MockEngine,
        index: Int,
    ): String = (engine.requestHistory[index].body as TextContent).text

    private companion object {
        const val EMPTY_FIRST_PAGE_PLAYLIST_BROWSE =
            """{"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"title":{"runs":[{"text":"Tracks"}]},"contents":[],"continuations":[{"nextContinuationData":{"continuation":"tracks-cont"}}]}}]}}}}}"""

        const val DIRECT_TRACKS_PLAYLIST_BROWSE =
            """{"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"title":{"runs":[{"text":"Tracks"}]},"contents":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track"}]}}}],"playlistItemData":{"videoId":"track"}}}]}}]}}}}}"""

        const val PLAYLIST_SHELF_CONTINUATION =
            """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track"}]}}}],"playlistItemData":{"videoId":"track"}}}]}}}"""

        const val ACTIONS_PLAYLIST_CONTINUATION =
            """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Action track"}]}}}],"playlistItemData":{"videoId":"action-track"}}}]}}]}"""

        const val TABS_HEADER_WITH_SECONDARY_TRACKS_BROWSE =
            """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Mood Mix"}]}}}]}}}}],"secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"title":{"runs":[{"text":"Tracks"}]},"contents":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track"}]}}}],"playlistItemData":{"videoId":"track"}}}]}}]}}}}}"""
    }
}
