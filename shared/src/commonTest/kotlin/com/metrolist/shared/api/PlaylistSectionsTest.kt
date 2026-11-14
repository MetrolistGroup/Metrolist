package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.filterForPlaylistSearch
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistSectionsTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun responsiveHeadersKeepLocalizedTotalsAndMissingTotalsStayUnknown() =
        runBlocking {
            val header =
                """
                {"title":{"runs":[{"text":"Playlist"}]},
                 "secondSubtitle":{"runs":[{"text":"1 234 utwory"},{"text":" • "},{"text":"4 dni"}]}}
                """.trimIndent()
            for (body in listOf(
                """{"header":{"musicResponsiveHeaderRenderer":$header}}""",
                """{"header":{"musicEditablePlaylistDetailHeaderRenderer":{"header":{"musicResponsiveHeaderRenderer":$header}}}}""",
                """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicResponsiveHeaderRenderer":$header}]}}}}]}}}""",
            )) {
                val client =
                    HttpClient(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) }) {
                        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                    }
                client.use {
                    val page = YouTubeRepository(InnerTube(it)).getPlaylistPageData("PLtest")
                    assertEquals("1 234 utwory", (page.sections["header"]!!.single() as PlaylistItem).songCount)
                }
            }
            val client =
                HttpClient(
                    MockEngine {
                        respond(INITIAL_EMPTY_PLAYLIST_RESPONSE, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            client.use {
                val page = YouTubeRepository(InnerTube(it)).getPlaylistPageData("PLtest")
                assertTrue(page.hasMore)
                assertNull((page.sections["header"]!!.single() as PlaylistItem).songCount)
            }
        }

    @Test
    fun repeatedVideosKeepDistinctPlaylistOccurrences() {
        val first = SongItem("same-video", "Song", null, emptyList(), null, playlistSetVideoId = "first")
        val second = first.copy(playlistSetVideoId = "second")
        assertEquals(listOf(first, second), mapOf("Tracks" to listOf(first), "Continuation" to listOf(first, second)).playlistSongs())
        assertEquals(listOf(first, second), mapOf("songs" to listOf(first, second, first)).playlistSongs())
    }

    @Test
    fun localMappingIdentityPreservesDuplicatesWithoutRemoteIdentifiers() {
        val first = SongItem("same", "Song", null, emptyList(), null, playlistLocalMappingId = 1)
        val second = first.copy(playlistLocalMappingId = 2)
        assertEquals(listOf(first, second), mapOf("songs" to listOf(first, second)).playlistSongs())
    }

    @Test
    fun completesMoreThanFiftyPages() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        val index = requests++
                        val continuation =
                            if (index < 55) {
                                """, "continuations":[{"nextContinuationData":{"continuation":"page-${index + 1}"}}]"""
                            } else {
                                ""
                            }
                        val row =
                            """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song $index"}]}}}],"playlistItemData":{"videoId":"song-$index"}}}"""
                        val body =
                            """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[$row]$continuation}}}"""
                        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            client.use {
                val repository = YouTubeRepository(InnerTube(it))
                val page = repository.completePlaylistPage(repository.getPlaylistPageData("PLlarge"))
                assertEquals((0..55).map { "song-$it" }, page.songs.map(SongItem::id))
                assertFalse(page.hasMore)
                assertEquals(56, requests)
            }
        }

    @Test
    fun loadsPlaylistBeyondOneHundredTracksThroughEveryContinuation() =
        runBlocking {
            fun rows(range: IntRange) =
                range.joinToString(",") { index ->
                    """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song $index"}]}}}],"playlistItemData":{"videoId":"song-$index"}}}"""
                }
            val responses =
                ArrayDeque(
                    listOf(
                        """{"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Playlist"},"secondSubtitle":{"runs":[{"text":"225 songs"},{"text":" • "},{"text":"12 hours"}]}}},"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[${rows(
                            1..100,
                        )}],"continuations":[{"nextContinuationData":{"continuation":"page-2"}}]}}]}}}""",
                        """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[${rows(
                            101..200,
                        )}],"continuations":[{"nextContinuationData":{"continuation":"page-3"}}]}}}""",
                        """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[${rows(201..225)}]}}]}""",
                    ),
                )
            val client =
                HttpClient(
                    MockEngine {
                        respond(responses.removeFirst(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            client.use {
                val repository = YouTubeRepository(InnerTube(it))
                val first = repository.getPlaylistPageData("PLtest")
                assertEquals("225 songs", (first.sections["header"]!!.single() as PlaylistItem).songCount)
                assertEquals(100, first.songs.size)
                assertTrue(first.hasMore)
                val complete = repository.completePlaylistPage(first)
                assertEquals((1..225).map { index -> "song-$index" }, complete.songs.map { song -> song.id })
                assertEquals("225 songs", (complete.sections["header"]!!.single() as PlaylistItem).songCount)
                assertFalse(complete.hasMore)
                assertTrue(responses.isEmpty())
            }
        }

    @Test
    fun playlistMembershipFollowsTracksAlongsideRelatedPlaylistContinuation() =
        runBlocking {
            fun rows(range: IntRange) =
                range.joinToString(",") { index ->
                    """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song $index"}]}}}],"playlistItemData":{"videoId":"song-$index"}}}"""
                }

            fun cursor(token: String) =
                """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$token"}}}}"""

            val initial =
                """{"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[${rows(
                    1..100,
                )},${cursor("tracks-2")}]}}],"continuations":[{"nextContinuationData":{"continuation":"related"}}]}}}}}"""
            val client =
                HttpClient(
                    MockEngine { request ->
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val token = body["continuation"]?.jsonPrimitive?.content
                        val response =
                            when {
                                token == null -> {
                                    initial
                                }

                                token == "related" -> {
                                    """{"continuationContents":{"sectionListContinuation":{"contents":[]}}}"""
                                }

                                else -> {
                                    val page = token.removePrefix("tracks-").toInt()
                                    val items = rows(((page - 1) * 100 + 1)..minOf(page * 100, 1250))
                                    val next = if (page < 13) ",${cursor("tracks-${page + 1}")}" else ""
                                    """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[$items$next]}}]}"""
                                }
                            }
                        respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            client.use {
                val repository = YouTubeRepository(InnerTube(it))
                val first = repository.getPlaylistPageData("PLtest")
                assertEquals(100, first.songs.size)
                assertEquals(2, first.pending.size)
                val complete = repository.completePlaylistPage(first)
                val expected = (1..1250).map { index -> "song-$index" }
                assertEquals(expected, complete.songs.map(SongItem::id))
                assertFalse(complete.hasMore)
                assertEquals(expected, repository.getPlaylist("PLtest").playlistSongs().map(SongItem::id))
                assertEquals(expected.toSet(), repository.playlistSongIds("PLtest"))
                assertTrue(repository.playlistContainsSong("PLtest", "song-1250"))
                assertFalse(repository.playlistContainsSong("PLtest", "absent"))
            }
        }

    @Test
    fun playlistSongsExcludesSuggestions() {
        val track = song("track")
        val continuedTrack = song("continued")
        val suggestion = song("suggestion")

        val sections =
            mapOf(
                "Tracks" to listOf(track),
                "Continuation" to listOf(continuedTrack),
                "Suggestions" to listOf(suggestion),
            )

        assertEquals(listOf(track, continuedTrack), sections.playlistSongs())
        assertEquals(listOf(suggestion), sections.playlistSuggestions())
    }

    @Test
    fun sectionListContinuationKeepsSuggestionsSeparate() =
        runBlocking {
            val responses =
                ArrayDeque(
                    listOf(
                        INITIAL_EMPTY_PLAYLIST_RESPONSE,
                        SUGGESTIONS_CONTINUATION_RESPONSE,
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

            val sections = YouTubeRepository(InnerTube(client)).getPlaylist("PLtest")

            assertEquals(emptyList(), sections.playlistSongs())
            assertEquals(listOf("suggestion"), sections["Suggestions"]?.map { it.id })
            assertEquals(listOf("suggestion"), sections.playlistSuggestions().map { it.id })
            client.close()
        }

    @Test
    fun playlistReadsEveryContinuationActionAndCommand() =
        runBlocking {
            val responses =
                ArrayDeque(
                    listOf(
                        INITIAL_EMPTY_PLAYLIST_RESPONSE.replace("suggestions", "page-1"),
                        ACTIONS_CONTINUATION_RESPONSE,
                        COMMANDS_CONTINUATION_RESPONSE,
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

            val sections = YouTubeRepository(InnerTube(client)).getPlaylist("PLtest")

            assertEquals(listOf("first", "second"), sections.playlistSongs().map { it.id })
            client.close()
        }

    @Test
    fun playlistReadsTrackAndSuggestionContinuationBranches() =
        runBlocking {
            val tokenResponses =
                mapOf(
                    "suggestions-2" to TITLELESS_SUGGESTIONS_CONTINUATION_RESPONSE,
                    "suggestions" to NESTED_SUGGESTIONS_CONTINUATION_RESPONSE,
                    "tracks" to TRACKS_CONTINUATION_RESPONSE,
                )
            val client =
                HttpClient(
                    MockEngine { request ->
                        val bodyText = (request.body as TextContent).text
                        val matchedToken = tokenResponses.keys.firstOrNull { bodyText.contains("\"$it\"") }
                        val content =
                            if (matchedToken != null) {
                                tokenResponses.getValue(matchedToken)
                            } else {
                                MULTI_BRANCH_PLAYLIST_RESPONSE
                            }
                        respond(
                            content = content,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

            val sections = YouTubeRepository(InnerTube(client)).getPlaylist("PLtest")

            assertEquals(listOf("track"), sections.playlistSongs().map { it.id })
            assertEquals(listOf("suggestion", "suggestion-2"), sections.playlistSuggestions().map { it.id })
            client.close()
        }

    @Test
    fun suggestionReloadTokenDoesNotBecomeForwardPagination() =
        runBlocking {
            val responses =
                ArrayDeque(
                    listOf(
                        PLAYLIST_WITH_SUGGESTION_PAGE_RESPONSE,
                        REFRESHABLE_SUGGESTIONS_RESPONSE,
                        EMPTY_CONTINUATION_RESPONSE,
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

            val sections = YouTubeRepository(InnerTube(client)).getPlaylist("PLtest")

            assertEquals(listOf("track"), sections.playlistSongs().map { it.id })
            assertEquals(listOf("suggestion"), sections.playlistSuggestions().map { it.id })
            assertEquals(0, responses.size)
            client.close()
        }

    @Test
    fun contextOnlyResponseDoesNotCreatePlaceholderPlaylist() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = CONTEXT_ONLY_PLAYLIST_RESPONSE,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

            assertTrue(YouTubeRepository(InnerTube(client)).getPlaylist("LP-local-only").isEmpty())
            client.close()
        }

    @Test
    fun initialPageDoesNotDrainContinuationsAndFailedPageCanRetry() =
        runBlocking {
            var requests = 0
            var failNext = true
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        val content =
                            when (requests) {
                                1 -> INITIAL_EMPTY_PLAYLIST_RESPONSE.replace("suggestions", "page-1")
                                2 -> ACTIONS_CONTINUATION_RESPONSE
                                else -> COMMANDS_CONTINUATION_RESPONSE
                            }
                        val failure = requests >= 3 && failNext
                        respond(
                            content,
                            if (failure) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                val initial = repository.getPlaylistPageData("PLtest")
                assertEquals(1, requests)
                assertTrue(initial.hasMore)
                assertTrue(initial.songs.isEmpty())
                val first = repository.continuePlaylistPage(initial)
                assertEquals(2, requests)
                assertEquals(listOf("first"), first.songs.map { it.id })
                assertTrue(first.songs.filterForPlaylistSearch("second").isEmpty())
                assertFailsWith<IllegalStateException> { repository.continuePlaylistPage(first) }
                assertTrue(first.hasMore)
                failNext = false
                val last = repository.continuePlaylistPage(first)
                assertFalse(last.hasMore)
                assertEquals(listOf("first", "second"), last.songs.map { it.id })
                assertEquals(listOf("second"), last.songs.filterForPlaylistSearch("second").map { it.id })
                assertTrue(initial.songs.isEmpty(), "Published pages must remain immutable")
            } finally {
                client.close()
            }
        }

    @Test
    fun playbackStopsFetchingAsSoonAsSongsAreAvailable() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        val content =
                            if (requests++ ==
                                0
                            ) {
                                INITIAL_EMPTY_PLAYLIST_RESPONSE.replace("suggestions", "page-1")
                            } else {
                                ACTIONS_CONTINUATION_RESPONSE
                            }
                        respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val page = YouTubeRepository(InnerTube(client)).getPlaylistPlaybackPage("PLtest")
                assertEquals(2, requests)
                assertEquals(listOf("first"), page.songs.map { it.id })
                assertTrue(page.hasMore)
            } finally {
                client.close()
            }
        }

    @Test
    fun continuationCycleIsNotPublishedAsACompletePlaylist() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        val content =
                            if (requests++ == 0) {
                                INITIAL_EMPTY_PLAYLIST_RESPONSE.replace("suggestions", "page-1")
                            } else {
                                ACTIONS_CONTINUATION_RESPONSE.replace("page-2", "page-1")
                            }
                        respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val repository = YouTubeRepository(InnerTube(client))
                val initial = repository.getPlaylistPageData("PLtest")
                assertFailsWith<IllegalStateException> { repository.continuePlaylistPage(initial) }
                assertTrue(initial.hasMore)
                assertEquals(2, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun aPageCannotContinueAfterAnAccountChange() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(INITIAL_EMPTY_PLAYLIST_RESPONSE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val innerTube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=first-test-account" }
                val repository = YouTubeRepository(innerTube)
                val initial = repository.getPlaylistPageData("PLtest")
                innerTube.cookie = "SAPISID=second-test-account"
                assertFailsWith<CancellationException> { repository.continuePlaylistPage(initial) }
                assertEquals(1, requests)
            } finally {
                client.close()
            }
        }

    private fun song(id: String) =
        SongItem(
            id = id,
            title = id,
            thumbnail = null,
            artists = emptyList(),
            album = null,
        )

    private companion object {
        const val INITIAL_EMPTY_PLAYLIST_RESPONSE =
            """{"header":{"musicDetailHeaderRenderer":{"title":{"runs":[{"text":"hi"}]}}},"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[],"continuations":[{"nextContinuationData":{"continuation":"suggestions"}}]}}}}}"""

        const val SUGGESTIONS_CONTINUATION_RESPONSE =
            """{"continuationContents":{"sectionListContinuation":{"contents":[{"musicShelfRenderer":{"title":{"runs":[{"text":"Suggestions"}]},"contents":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Suggested song"}]}}}],"playlistItemData":{"videoId":"suggestion"}}}]}}]}}}"""

        const val MULTI_BRANCH_PLAYLIST_RESPONSE =
            """{"header":{"musicDetailHeaderRenderer":{"title":{"runs":[{"text":"hi"}]}}},"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"title":{"runs":[{"text":"Tracks"}]},"contents":[],"continuations":[{"nextContinuationData":{"continuation":"tracks"}}]}},{"musicShelfRenderer":{"title":{"runs":[{"text":"Suggestions"}]},"contents":[],"continuations":[{"nextContinuationData":{"continuation":"suggestions"}}]}}]}}}}}"""

        const val TRACKS_CONTINUATION_RESPONSE =
            """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track"}]}}}],"playlistItemData":{"videoId":"track"}}}]}}}"""

        const val TITLELESS_SUGGESTIONS_CONTINUATION_RESPONSE =
            """{"continuationContents":{"sectionListContinuation":{"contents":[{"musicShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Suggested song 2"}]}}}],"playlistItemData":{"videoId":"suggestion-2"}}}]}}]}}}"""

        const val NESTED_SUGGESTIONS_CONTINUATION_RESPONSE =
            """{"continuationContents":{"sectionListContinuation":{"contents":[{"musicShelfRenderer":{"title":{"runs":[{"text":"Suggestions"}]},"contents":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Suggested song"}]}}}],"playlistItemData":{"videoId":"suggestion"}}}],"continuations":[{"nextContinuationData":{"continuation":"suggestions-2"}}]}}]}}}"""

        const val PLAYLIST_WITH_SUGGESTION_PAGE_RESPONSE =
            """
            {
              "header": {"musicDetailHeaderRenderer": {"title": {"runs": [{"text": "hi"}]}}},
              "contents": {"twoColumnBrowseResultsRenderer": {"secondaryContents": {"sectionListRenderer": {
                "contents": [{"musicPlaylistShelfRenderer": {
                  "title": {"runs": [{"text": "Tracks"}]},
                  "contents": [{"musicResponsiveListItemRenderer": {
                    "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Track"}]}}}],
                    "playlistItemData": {"videoId": "track"}
                  }}]
                }}],
                "continuations": [{"nextContinuationData": {"continuation": "suggestions-page"}}]
              }}}}
            }
            """

        const val REFRESHABLE_SUGGESTIONS_RESPONSE =
            """{"continuationContents":{"sectionListContinuation":{"contents":[{"musicShelfRenderer":{"title":{"runs":[{"text":"Suggestions"}]},"contents":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Suggested song"}]}}}],"playlistItemData":{"videoId":"suggestion"}}}],"continuations":[{"reloadContinuationData":{"continuation":"refresh-suggestions"}}],"autoReloadWhenEmpty":true}}],"continuations":[{"nextContinuationData":{"continuation":"done"}}]}}}"""

        const val EMPTY_CONTINUATION_RESPONSE = """{"trackingParams":"done"}"""

        const val CONTEXT_ONLY_PLAYLIST_RESPONSE =
            """{"responseContext":{},"microformat":{"microformatDataRenderer":{"noindex":true}}}"""

        const val ACTIONS_CONTINUATION_RESPONSE =
            """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"First"}]}}}],"playlistItemData":{"videoId":"first"}}}]}},{"appendContinuationItemsAction":{"continuationItems":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"page-2"}}}}]}}]}"""

        const val COMMANDS_CONTINUATION_RESPONSE =
            """{"onResponseReceivedCommands":[{"appendContinuationItemsCommand":{"continuationItems":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Second"}]}}}],"playlistItemData":{"videoId":"second"}}}]}}]}"""
    }
}
