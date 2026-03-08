package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isDesktopHomeDiscoverySection
import com.metrolist.shared.model.mergeAndDeduplicateHomeSections
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.HomeViewModel.HomeUiState
import com.metrolist.shared.viewmodels.uncategorizedHomeSections
import com.metrolist.shared.viewmodels.withoutBlockedContent
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HomePageParsingTest {
    @Test
    fun missingLibraryPlaylistCountComesFromDetailHeader() =
        runBlocking {
            val requested = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        val browseId =
                            Json
                                .parseToJsonElement((request.body as TextContent).text)
                                .jsonObject["browseId"]!!
                                .jsonPrimitive.content
                        requested += browseId
                        respond(
                            """{"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Shared"},"subtitle":{"runs":[{"text":"Original creator"}]},"secondSubtitle":{"runs":[{"text":"42 songs"}]}}}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                val missing = PlaylistItem("VLPLmissing", "Shared", null)
                val known = PlaylistItem("VLPLknown", "Known", null, songCount = "5 songs")
                val resolved = repository.resolveMissingPlaylistCounts(listOf(missing, known)).filterIsInstance<PlaylistItem>()
                assertEquals("42 songs", resolved[0].songCount)
                assertEquals("Original creator", resolved[0].author)
                assertEquals(known, resolved[1])
                assertEquals(listOf("VLPLmissing"), requested)
                val published = mutableListOf<PlaylistItem>()
                assertEquals(resolved, repository.resolveMissingPlaylistCounts(listOf(missing, known)) { published += it })
                assertEquals(listOf(resolved.first()), published)
                assertEquals(listOf("VLPLmissing"), requested)
            } finally {
                client.close()
            }
        }

    @Test
    fun unresolvedPlaylistCountIsCachedUntilSourceCountChanges() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(
                            """{"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Shared"},
                        "secondSubtitle":{"runs":[{"text":"1.2K songs"}]}}}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                val playlist = PlaylistItem("VLPLmissing", "Shared", null)
                repeat(2) { assertEquals(listOf(playlist), repository.resolveMissingPlaylistCounts(listOf(playlist))) }
                assertEquals(1, requests)
                repository.resolveMissingPlaylistCounts(listOf(playlist.copy(songCount = "2K songs")))
                assertEquals(2, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun repeatedUploadContinuationFailsWithoutLoopingOrReturningPartialSuccess() =
        runBlocking {
            var calls = 0
            val client =
                HttpClient(
                    MockEngine {
                        calls++
                        val shelf = """{"contents":[],"continuations":[{"nextContinuationData":{"continuation":"repeat"}}]}"""
                        val body =
                            if (calls == 1) {
                                """{"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":$shelf}]}}}"""
                            } else {
                                """{"continuationContents":{"musicShelfContinuation":$shelf}}"""
                            }
                        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=session" })
                assertTrue(repository.getLibraryUploadSongsResult(limit = null).isFailure)
                assertEquals(2, calls)
            } finally {
                client.close()
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
    fun exploreKeepsMoodAndGenresShelfSeparateFromCategoryChips() =
        runBlocking {
            val repository = repository(EXPLORE_RESPONSE)

            val page = repository.getHomePageData(browseId = "FEmusic_explore")

            assertEquals(listOf("New releases"), page.chips.map { it.title })
            val section = page.sections.single()
            assertEquals("Moods and genres", section.title)
            assertEquals("FEmusic_moods_and_genres", section.browseId)
            assertEquals(listOf("Chill", "Energise"), section.items.map { it.title })
            assertIs<BrowseItem>(section.items.first())
            Unit
        }

    @Test
    fun homeCanExplicitlyUseAuthenticationWhenGeneralBrowseLoginIsDisabled() =
        runBlocking {
            val engine =
                MockEngine {
                    respond(
                        content = "{}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val innerTube =
                InnerTube(
                    HttpClient(engine) {
                        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                    },
                    retryDelay = {},
                ).apply {
                    cookie = "SAPISID=session"
                    visitorData = "visitor-data"
                    dataSyncId = "channel-sync-id"
                    authUser = "0"
                    useLoginForBrowse = false
                }

            YouTubeRepository(innerTube).getHomePageData(setLogin = true)

            assertNotNull(engine.requestHistory.single().headers[HttpHeaders.Authorization])
            Unit
        }

    @Test
    fun homeUsesSelectedTabWhenAuthenticatedResponseStartsWithEmptyTab() =
        runBlocking {
            val page = repository(SELECTED_TAB_RESPONSE).getHomePageData()

            assertEquals(listOf("Quick picks"), page.sections.map { it.title })
            assertEquals(
                listOf("selected-song"),
                page.sections
                    .single()
                    .items
                    .map { it.id },
            )
        }

    @Test
    fun homeContinuationKeepsShelvesAndNextToken() =
        runBlocking {
            val repository = repository(CONTINUATION_RESPONSE)

            val (sections, continuation) = repository.getHomeSectionsContinuation("page-1")

            assertEquals("page-2", continuation)
            assertEquals(listOf("Quick picks", "One row"), sections.map { it.title })
            assertEquals(listOf("song-1"), sections.first().items.map { it.id })
            assertEquals(4, sections.first().numItemsPerColumn)
            assertTrue(sections.first().isResponsiveList)
            assertEquals(true, sections.first().isMultiRow)
            assertEquals(false, sections.last().isMultiRow)
            assertFalse(sections.any { it.isPersonal })
            assertIs<SongItem>(sections.first().items.single())
            Unit
        }

    @Test
    fun homeContinuationPropagatesFailures() =
        runBlocking {
            assertFails { repository("{").getHomeSectionsContinuation("page-1") }
            for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.TooManyRequests, HttpStatusCode.InternalServerError)) {
                assertFails { repository("{}", status).getHomeSectionsContinuation("page-1") }
            }
        }

    @Test
    fun browseContinuationMarksListShelvesAsPersonalOnlyWhenRequested() =
        runBlocking {
            val repository = repository(LIST_CONTINUATION_RESPONSE)

            val (homeSections) = repository.getHomeSectionsContinuation("page-1")
            val (browseSections) = repository.getHomeSectionsContinuation("page-1", isPersonal = true)

            assertFalse(homeSections.single().isPersonal)
            assertTrue(browseSections.single().isPersonal)
        }

    @Test
    fun userPlaylistsExcludeSystemCollections() =
        runBlocking {
            val repository = repository(PLAYLIST_LIBRARY_RESPONSE)

            val playlists = repository.getUserPlaylists()

            assertEquals(listOf("VLPL_user"), playlists.map { it.id })
            assertTrue(playlists.single().isEditable)
        }

    @Test
    fun completeLibraryResolvesPlaylistEditability() =
        runBlocking {
            val result = repository(PLAYLIST_LIBRARY_RESPONSE, authenticated = true).getCompleteLibraryItemsResult()

            assertTrue(result.complete)
            val playlist = result.items.single { it.id == "VLPL_user" }
            assertTrue(assertIs<PlaylistItem>(playlist).isEditable)
        }

    @Test
    fun ordinaryPlaylistHeaderDoesNotClaimEditability() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    val browseId =
                        Json
                            .parseToJsonElement(
                                (request.body as TextContent).text,
                            ).jsonObject["browseId"]
                            ?.jsonPrimitive
                            ?.content
                    respond(
                        if (browseId == "FEmusic_liked_playlists") {
                            PLAYLIST_LIBRARY_RESPONSE
                        } else {
                            """{"header":{"musicDetailHeaderRenderer":{"title":{"runs":[{"text":"Shared playlist"}]}}}}"""
                        },
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=session" })
                val result = repository.getUserPlaylistsResult()
                assertTrue(result.complete)
                assertFalse(assertIs<PlaylistItem>(result.items.single()).isEditable)
                assertEquals(2, engine.requestHistory.size)
            } finally {
                client.close()
            }
        }

    @Test
    fun homeAndLibraryShareRequestsAndCacheConfirmedOwnership() =
        runTest {
            val playlistStarted = CompletableDeferred<Unit>()
            val releasePlaylist = CompletableDeferred<Unit>()
            val engine =
                MockEngine { request ->
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val browseId = body["browseId"]?.jsonPrimitive?.content
                    if (browseId == "FEmusic_liked_playlists") {
                        playlistStarted.complete(Unit)
                        releasePlaylist.await()
                    }
                    respond(
                        content =
                            if (browseId in setOf("FEmusic_liked_playlists", "VLPL_user")) {
                                PLAYLIST_LIBRARY_RESPONSE
                            } else {
                                """{"contents":{"sectionListRenderer":{"contents":[]}}}"""
                            },
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client =
                HttpClient(engine) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            try {
                val innerTube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=session" }
                val repository = YouTubeRepository(innerTube)
                val library = async { repository.getCompleteLibraryItemsResult() }
                playlistStarted.await()
                val home = async(start = CoroutineStart.UNDISPATCHED) { repository.getUserPlaylistsResult() }
                releasePlaylist.complete(Unit)
                assertTrue(library.await().complete)
                val playlists = home.await()
                assertTrue(playlists.complete)
                assertTrue(assertIs<PlaylistItem>(playlists.items.single()).isEditable)

                fun requestsFor(browseId: String) =
                    engine.requestHistory.count {
                        Json
                            .parseToJsonElement((it.body as TextContent).text)
                            .jsonObject["browseId"]
                            ?.jsonPrimitive
                            ?.content == browseId
                    }
                assertEquals(1, requestsFor("FEmusic_liked_playlists"))
                assertEquals(1, requestsFor("VLPL_user"))
                assertTrue(repository.getUserPlaylistsResult().complete)
                assertEquals(2, requestsFor("FEmusic_liked_playlists"))
                assertEquals(1, requestsFor("VLPL_user"))
            } finally {
                client.close()
            }
        }

    @Test
    fun cancelledPlaylistOwnerDoesNotCancelItsWaiter() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine {
                        started.complete(Unit)
                        release.await()
                        respond(
                            """{"contents":{"sectionListRenderer":{"contents":[]}}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=session" })
                val owner = async { repository.getUserPlaylistsResult() }
                started.await()
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { repository.getUserPlaylistsResult() }
                owner.cancel()
                release.complete(Unit)
                assertTrue(waiter.await().complete)
            } finally {
                client.close()
            }
        }

    @Test
    fun independentLibraryFeedsStartBeforeAnyCompletes() =
        runTest {
            val started = mutableSetOf<String>()
            val mutex = Mutex()
            val allStarted = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        val id =
                            Json
                                .parseToJsonElement((request.body as TextContent).text)
                                .jsonObject["browseId"]!!
                                .jsonPrimitive.content
                        mutex.withLock {
                            started += id
                            if (started.size == 5) allStarted.complete(Unit)
                        }
                        allStarted.await()
                        respond(
                            """{"contents":{"sectionListRenderer":{"contents":[]}}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=session" })
                assertTrue(repository.getCompleteLibraryItemsResult().complete)
                assertEquals(5, started.size)
            } finally {
                client.close()
            }
        }

    @Test
    fun directUploadShelfContinuationKeepsPaging() =
        runBlocking {
            var calls = 0
            val client =
                HttpClient(
                    MockEngine {
                        calls++
                        val shelf =
                            if (calls <
                                3
                            ) {
                                """{"contents":[],"continuations":[{"nextContinuationData":{"continuation":"page-$calls"}}]}"""
                            } else {
                                """{"contents":[]}"""
                            }
                        val body =
                            if (calls == 1) {
                                """{"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":$shelf}]}}}"""
                            } else {
                                """{"continuationContents":{"musicShelfContinuation":$shelf}}"""
                            }
                        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=session" })
                repository.getLibraryUploadSongs(limit = null)
                assertEquals(3, calls)
            } finally {
                client.close()
            }
        }

    @Test
    fun libraryOwnershipLookupRejectsAccountChanges() =
        runBlocking {
            for (completeLibrary in listOf(false, true)) {
                lateinit var innerTube: InnerTube
                val client =
                    HttpClient(
                        MockEngine { request ->
                            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                            if (body["browseId"]?.jsonPrimitive?.content == "VLPL_user") {
                                innerTube.cookie = "SAPISID=next-session"
                            }
                            respond(
                                content = PLAYLIST_LIBRARY_RESPONSE,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ) {
                        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                    }
                try {
                    innerTube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=first-session" }
                    val repository = YouTubeRepository(innerTube)
                    assertFailsWith<CancellationException> {
                        if (completeLibrary) repository.getCompleteLibraryItemsResult() else repository.getUserPlaylists()
                    }
                    assertEquals("SAPISID=next-session", innerTube.cookie)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun homeKeepsPodcastLibraryShelvesSeparateFromSavedAlbums() =
        runBlocking {
            for (browseId in listOf("FEmusic_library_non_music_audio_list", "FEmusic_library_podcasts")) {
                for (podcastsFirst in listOf(false, true)) {
                    val shows =
                        """
                        {"musicCarouselShelfRenderer":{
                          "header":{"musicCarouselShelfBasicHeaderRenderer":{
                            "title":{"runs":[{"text":"Your shows","navigationEndpoint":{
                              "browseEndpoint":{"browseId":"$browseId","params":"shows-params"}
                            }}]}
                          }},
                          "contents":[{"musicTwoRowItemRenderer":{
                            "title":{"runs":[{"text":"Saved show"}]},
                            "navigationEndpoint":{"browseEndpoint":{"browseId":"MPSPPL_show"}}
                          }}, {"continuationItemRenderer":{
                            "continuationEndpoint":{"continuationCommand":{"token":"shows-next"}}
                          }}]
                        }}
                        """.trimIndent()
                    val music = libraryAlbumShelves(endpointInTitle = false)
                    val shelves = if (podcastsFirst) "$shows,$music" else "$music,$shows"
                    val page = repository("""{"contents":{"sectionListRenderer":{"contents":[$shelves]}}}""").getHomePageData()
                    val source = page.sections.single { it.title == "Your shows" }
                    assertIs<PodcastItem>(source.items.single())
                    assertEquals("shows-params", source.params)
                    assertEquals("shows-next", source.continuation)

                    fun visible(sections: List<HomeSection>) = uncategorizedHomeSections(mergeAndDeduplicateHomeSections(sections))

                    val sections = visible(page.sections)
                    assertEquals(source, sections.single { it.title == "Your shows" })
                    assertTrue(sections.single { it.title == "De tu biblioteca" }.items.all { it is AlbumItem })
                    assertFalse(source.isDesktopHomeDiscoverySection())

                    val (continued, _) =
                        repository("""{"continuationContents":{"sectionListContinuation":{"contents":[$shelves]}}}""")
                            .getHomeSectionsContinuation("next-page", loadAllPages = false)
                    assertEquals(sections, visible(continued))

                    val restored = HomeUiState(data = page.sections, sections = page.sections).withoutBlockedContent()
                    assertEquals(source, restored.sections.single { it.title == "Your shows" })
                    Settings.shared.hidePodcasts = true
                    assertTrue(
                        restored
                            .withoutBlockedContent()
                            .sections
                            .flatMap { it.items }
                            .none { it is PodcastItem },
                    )
                    Settings.shared.hidePodcasts = false
                }
            }
        }

    @Test
    fun homeLibraryShelfKeepsOnlyAlbumsWithoutMatchingItsTitle() =
        runBlocking {
            for (endpointInTitle in listOf(false, true)) {
                val shelves = libraryAlbumShelves(endpointInTitle)
                val homeRepository = repository("""{"contents":{"sectionListRenderer":{"contents":[$shelves]}}}""")
                val page = homeRepository.getHomePageData()
                val source = page.sections.first()
                assertEquals("FEmusic_library_landing", source.browseId)
                assertEquals("library-params", source.params)
                assertEquals("library-next", source.continuation)
                assertEquals(6, source.items.size)
                assertIs<SongItem>(source.items[0])
                assertIs<PlaylistItem>(source.items[2])
                assertIs<ArtistItem>(source.items[4])

                val sections = uncategorizedHomeSections(page.sections)
                assertEquals(listOf("De tu biblioteca", "From your library"), sections.map { it.title })
                val albums = source.items.filterIsInstance<AlbumItem>()
                assertEquals(listOf("MPREb_album", "MPREb_ep", "MPREb_single"), albums.map { it.id })
                assertEquals(source.copy(items = albums), sections.first())
                assertEquals(page.sections.last(), sections.last())

                val continuation = repository("""{"continuationContents":{"sectionListContinuation":{"contents":[$shelves]}}}""")
                val (nextSections, _) = continuation.getHomeSectionsContinuation("next-page", loadAllPages = false)
                assertEquals(sections, uncategorizedHomeSections(nextSections))

                val restored = HomeUiState(data = page.sections, sections = page.sections).withoutBlockedContent()
                assertEquals(sections, restored.sections)
                assertEquals(page.sections, restored.data)
            }
        }

    @Test
    fun homeMarksPlaylistShelvesAsPersonalWithoutDependingOnLocalizedTitle() =
        runBlocking {
            val page = repository(HOME_PLAYLIST_SHELF_RESPONSE).getHomePageData()

            assertEquals(listOf("Titres", "Pistes", "Selection editoriale"), page.sections.map { it.title })
            assertTrue(page.sections.first().isPersonal)
            assertTrue(page.sections[1].isPersonal)
            assertFalse(page.sections.last().isPersonal)
        }

    private fun repository(
        response: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        authenticated: Boolean = false,
    ): YouTubeRepository {
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        content = response,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        val innerTube = InnerTube(client, retryDelay = {})
        if (authenticated) innerTube.cookie = "SAPISID=test"
        return YouTubeRepository(innerTube)
    }

    private companion object {
        fun libraryAlbumShelves(endpointInTitle: Boolean): String {
            val endpoint = """{"browseEndpoint":{"browseId":"FEmusic_library_landing","params":"library-params"}}"""
            val titleEndpoint = if (endpointInTitle) """, "navigationEndpoint":$endpoint""" else ""
            val moreButton = if (endpointInTitle) "" else """, "moreContentButton":{"buttonRenderer":{"navigationEndpoint":$endpoint}}"""
            return """
                {"musicCarouselShelfRenderer":{
                  "header":{"musicCarouselShelfBasicHeaderRenderer":{
                    "title":{"runs":[{"text":"De tu biblioteca"$titleEndpoint}]}$moreButton,
                    "strapline":{"simpleText":"Saved releases"}
                  }},
                  "contents":[$LIBRARY_MIXED_ITEMS, {"continuationItemRenderer":{
                    "continuationEndpoint":{"continuationCommand":{"token":"library-next"}}
                  }}]
                }},
                {"musicCarouselShelfRenderer":{
                  "header":{"musicCarouselShelfBasicHeaderRenderer":{
                    "title":{"runs":[{"text":"Empty library shelf","navigationEndpoint":{
                      "browseEndpoint":{"browseId":"FEmusic_library"}
                    }}]}
                  }},
                  "contents":[{"musicResponsiveListItemRenderer":{
                    "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Saved song"}]}}}],
                    "playlistItemData":{"videoId":"saved-song"}
                  }}]
                }},
                {"musicCarouselShelfRenderer":{
                  "header":{"musicCarouselShelfBasicHeaderRenderer":{
                    "title":{"runs":[{"text":"From your library","navigationEndpoint":{
                      "browseEndpoint":{"browseId":"FEmusic_recommendations","params":"recommendation-params"}
                    }}]}
                  }},
                  "contents":[$LIBRARY_MIXED_ITEMS]
                }}
                """
        }

        const val LIBRARY_MIXED_ITEMS =
            """
            {"musicResponsiveListItemRenderer":{
              "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Saved song"}]}}}],
              "playlistItemData":{"videoId":"saved-song"}
            }},
            {"musicTwoRowItemRenderer":{
              "title":{"runs":[{"text":"Saved album"}]},
              "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_album"}}
            }},
            {"musicTwoRowItemRenderer":{
              "title":{"runs":[{"text":"Saved playlist"}]},
              "navigationEndpoint":{"browseEndpoint":{"browseId":"VLPL_saved"}}
            }},
            {"musicTwoRowItemRenderer":{
              "title":{"runs":[{"text":"Saved EP"}]},
              "subtitle":{"runs":[{"text":"EP"}]},
              "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_ep"}}
            }},
            {"musicTwoRowItemRenderer":{
              "title":{"runs":[{"text":"Saved artist"}]},
              "navigationEndpoint":{"browseEndpoint":{"browseId":"UC_saved"}}
            }},
            {"musicTwoRowItemRenderer":{
              "title":{"runs":[{"text":"Saved single"}]},
              "subtitle":{"runs":[{"text":"Single"}]},
              "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_single"}}
            }}
            """

        const val EXPLORE_RESPONSE =
            """
            {
              "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {
                "sectionListRenderer": {"contents": [
                  {"musicCategoryListRenderer": {"items": [
                    {"musicCategoryListItemRenderer": {
                      "title": {"runs": [{"text": "New releases"}]},
                      "navigationEndpoint": {"browseEndpoint": {"browseId": "FEmusic_new_releases"}}
                    }}
                  ]}},
                  {"musicCarouselShelfRenderer": {
                    "header": {"musicCarouselShelfBasicHeaderRenderer": {
                      "title": {"runs": [{
                        "text": "Moods and genres",
                        "navigationEndpoint": {"browseEndpoint": {"browseId": "FEmusic_moods_and_genres"}}
                      }]}
                    }},
                    "contents": [
                      {"musicNavigationButtonRenderer": {
                        "buttonText": {"runs": [{"text": "Chill"}]},
                        "clickCommand": {"browseEndpoint": {"browseId": "FEmusic_moods_and_genres_category", "params": "chill"}}
                      }},
                      {"musicNavigationButtonRenderer": {
                        "buttonText": {"runs": [{"text": "Energise"}]},
                        "clickCommand": {"browseEndpoint": {"browseId": "FEmusic_moods_and_genres_category", "params": "energy"}}
                      }}
                    ]
                  }}
                ]}
              }}}]}}
            }
            """

        const val SELECTED_TAB_RESPONSE =
            """
            {
              "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [
                {"tabRenderer": {"content": {"sectionListRenderer": {"contents": []}}}},
                {"tabRenderer": {"selected": true, "content": {
                  "sectionListRenderer": {"contents": [{"musicCarouselShelfRenderer": {
                    "header": {"musicCarouselShelfBasicHeaderRenderer": {"title": {"runs": [{"text": "Quick picks"}]}}},
                    "contents": [{"musicResponsiveListItemRenderer": {
                      "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {
                        "text": {"runs": [{"text": "Selected song"}]}
                      }}],
                      "playlistItemData": {"videoId": "selected-song"}
                    }}]
                  }}]}
                }}}
              ]}}
            }
            """

        const val CONTINUATION_RESPONSE =
            """
            {
              "continuationContents": {"sectionListContinuation": {
                "contents": [{"musicCarouselShelfRenderer": {
                  "header": {"musicCarouselShelfBasicHeaderRenderer": {"title": {"runs": [
                    {"text": "Quick "}, {"text": "picks"}
                  ]}}},
                  "numItemsPerColumn": "4",
                  "contents": [{"musicResponsiveListItemRenderer": {
                    "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {
                      "text": {"runs": [{"text": "Song one"}]}
                    }}],
                    "playlistItemData": {"videoId": "song-1"}
                  }}]
                }}, {"musicCarouselShelfRenderer": {
                  "header": {"musicCarouselShelfBasicHeaderRenderer": {"title": {"runs": [
                    {"text": "One row"}
                  ]}}},
                  "numItemsPerColumn": "1",
                  "contents": [{"musicResponsiveListItemRenderer": {
                    "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {
                      "text": {"runs": [{"text": "Song two"}]}
                    }}],
                    "playlistItemData": {"videoId": "song-2"}
                  }}]
                }}],
                "continuations": [{"nextContinuationData": {"continuation": "page-2"}}]
              }}
            }
            """

        const val PLAYLIST_LIBRARY_RESPONSE =
            """
            {
              "header": {"musicEditablePlaylistDetailHeaderRenderer": {}},
              "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {
                "sectionListRenderer": {"contents": [{"gridRenderer": {"items": [
                  {"musicTwoRowItemRenderer": {
                    "title": {"runs": [{"text": "Liked Music"}]},
                    "navigationEndpoint": {"browseEndpoint": {"browseId": "VLLM"}}
                  }},
                  {"musicTwoRowItemRenderer": {
                    "title": {"runs": [{"text": "New Episodes"}]},
                    "navigationEndpoint": {"browseEndpoint": {"browseId": "VLRDPN"}}
                  }},
                  {"musicTwoRowItemRenderer": {
                    "title": {"runs": [{"text": "My playlist"}]},
                    "navigationEndpoint": {"browseEndpoint": {"browseId": "VLPL_user"}}
                  }},
                  {"musicTwoRowItemRenderer": {
                    "title": {"runs": [{"text": "Episodes for Later"}]},
                    "navigationEndpoint": {"browseEndpoint": {"browseId": "VLSE"}}
                  }}
                ]}}]}
              }}}]}}
            }
            """

        const val LIST_CONTINUATION_RESPONSE =
            """
            {
              "continuationContents": {"sectionListContinuation": {
                "contents": [{"musicShelfRenderer": {
                  "title": {"runs": [{"text": "Discovery list"}]},
                  "contents": [{"musicResponsiveListItemRenderer": {
                    "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {
                      "text": {"runs": [{"text": "Recommended song"}]}
                    }}],
                    "playlistItemData": {"videoId": "recommended-song"}
                  }}]
                }}]
              }}
            }
            """

        const val HOME_PLAYLIST_SHELF_RESPONSE =
            """
            {
              "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {
                "sectionListRenderer": {"contents": [
                  {"musicPlaylistShelfRenderer": {
                    "title": {"runs": [{"text": "Titres"}]},
                    "contents": [{"musicResponsiveListItemRenderer": {
                      "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {
                        "text": {"runs": [{"text": "Personal song"}]}
                      }}],
                      "playlistItemData": {"videoId": "personal-song"}
                    }}]
                  }},
                  {"musicShelfRenderer": {
                    "title": {"runs": [{"text": "Pistes"}]},
                    "contents": [{"musicResponsiveListItemRenderer": {
                      "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {
                        "text": {"runs": [{"text": "Personal list song"}]}
                      }}],
                      "playlistItemData": {"videoId": "personal-list-song"}
                    }}]
                  }},
                  {"musicCarouselShelfRenderer": {
                    "header": {"musicCarouselShelfBasicHeaderRenderer": {
                      "title": {"runs": [{"text": "Selection editoriale"}]}
                    }},
                    "contents": [{"musicTwoRowItemRenderer": {
                      "title": {"runs": [{"text": "Editorial playlist"}]},
                      "navigationEndpoint": {"browseEndpoint": {"browseId": "VLPL_editorial"}}
                    }}]
                  }}
                ]}
              }}}]}}
            }
            """
    }
}
