package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.auth.YouTubeSessionState
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeRepositoryFailureTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        YouTubeSessionState.dismissExpiredSessionNotice()
    }

    @AfterTest
    fun tearDown() {
        YouTubeSessionState.dismissExpiredSessionNotice()
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun transientAccountFailuresPreserveEveryPersistedCredential() =
        runBlocking {
            for (status in listOf(408, 429, 500, 502, 503, -1)) {
                Settings.shared.innerTubeCookie = "SAPISID=persisted"
                Settings.shared.visitorData = "persisted-visitor"
                Settings.shared.innerTubeAuthUser = "2"
                Settings.shared.lastFmSession = "persisted-lastfm"
                Settings.shared.discordAccessToken = "persisted-discord"
                val client =
                    HttpClient(
                        MockEngine {
                            if (status == -1) throw IllegalStateException("Offline")
                            respond("{}", HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
                val innerTube =
                    InnerTube(client, retryDelay = {}).apply {
                        replaceSession("SAPISID=persisted", "persisted-visitor", "sync", "2", true)
                    }
                try {
                    val before = innerTube.sessionSnapshot()
                    assertNull(YouTubeRepository(innerTube).getAccountInfo())
                    assertEquals(before, innerTube.sessionSnapshot())
                    assertEquals("SAPISID=persisted", Settings.shared.innerTubeCookie)
                    assertEquals("persisted-visitor", Settings.shared.visitorData)
                    assertEquals("2", Settings.shared.innerTubeAuthUser)
                    assertEquals("persisted-lastfm", Settings.shared.lastFmSession)
                    assertEquals("persisted-discord", Settings.shared.discordAccessToken)
                    assertFalse(YouTubeSessionState.showExpiredSessionNotice)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun accountLookupRevalidatesTransientAuthenticationFailures() =
        runBlocking {
            for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
                var requests = 0
                HttpClient(
                    MockEngine {
                        requests++
                        respond(
                            if (requests == 1) "{}" else ACCOUNT_MENU,
                            if (requests == 1) status else HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }.use { client ->
                    val innerTube = InnerTube(client).apply { cookie = "SAPISID=persisted" }
                    val before = innerTube.sessionSnapshot()
                    val repository = YouTubeRepository(innerTube)

                    assertEquals("Music account", repository.getAccountInfo()?.name)
                    assertEquals(2, requests)
                    assertEquals(before, innerTube.sessionSnapshot())
                    assertFalse(YouTubeSessionState.showExpiredSessionNotice)
                }
            }
        }

    @Test
    fun unavailableAccountRevalidationPreservesTheActiveSession() =
        runBlocking {
            for (offline in listOf(false, true)) {
                var requests = 0
                HttpClient(
                    MockEngine {
                        requests++
                        if (requests > 1 && offline) throw IllegalStateException("Offline")
                        respond(
                            "{}",
                            if (requests == 1) HttpStatusCode.Unauthorized else HttpStatusCode.ServiceUnavailable,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }.use { client ->
                    val innerTube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=persisted" }
                    val before = innerTube.sessionSnapshot()

                    assertNull(YouTubeRepository(innerTube).getAccountInfo())
                    assertTrue(requests >= 2)
                    assertEquals(before, innerTube.sessionSnapshot())
                    assertFalse(YouTubeSessionState.showExpiredSessionNotice)
                }
            }
        }

    @Test
    fun confirmedAccountRejectionExpiresOnlyTheActiveSession() =
        runBlocking {
            for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
                YouTubeSessionState.dismissExpiredSessionNotice()
                Settings.shared.innerTubeCookie = "SAPISID=persisted"
                var requests = 0
                HttpClient(
                    MockEngine {
                        requests++
                        respond("{}", status, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }.use { client ->
                    val innerTube = InnerTube(client).apply { cookie = Settings.shared.innerTubeCookie }

                    assertNull(YouTubeRepository(innerTube).getAccountInfo())
                    assertEquals(2, requests)
                    assertFalse(innerTube.hasSapCookieAuth())
                    assertEquals("SAPISID=persisted", Settings.shared.innerTubeCookie)
                    assertTrue(YouTubeSessionState.showExpiredSessionNotice)
                }
            }
        }

    @Test
    fun accountChangeDuringRevalidationCancelsTheOldLookup() =
        runBlocking {
            var requests = 0
            lateinit var innerTube: InnerTube
            HttpClient(
                MockEngine {
                    requests++
                    if (requests == 2) innerTube.cookie = "SAPISID=new"
                    respond("{}", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }.use { client ->
                innerTube = InnerTube(client).apply { cookie = "SAPISID=old" }

                assertFailsWith<CancellationException> { YouTubeRepository(innerTube).getAccountInfo() }
                assertEquals(2, requests)
                assertEquals("SAPISID=new", innerTube.cookie)
                assertFalse(YouTubeSessionState.showExpiredSessionNotice)
            }
        }

    @Test
    fun cancelledAccountRevalidationPreservesTheActiveSession() =
        runBlocking {
            var requests = 0
            HttpClient(
                MockEngine {
                    if (++requests == 2) throw CancellationException("cancelled revalidation")
                    respond("{}", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }.use { client ->
                val innerTube = InnerTube(client).apply { cookie = "SAPISID=persisted" }
                val before = innerTube.sessionSnapshot()

                assertFailsWith<CancellationException> { YouTubeRepository(innerTube).getAccountInfo() }
                assertEquals(2, requests)
                assertEquals(before, innerTube.sessionSnapshot())
                assertFalse(YouTubeSessionState.showExpiredSessionNotice)
            }
        }

    @Test
    fun candidateAccountLookupDoesNotPublishGlobalSessionExpiry() =
        runBlocking {
            Settings.shared.innerTubeCookie = "SAPISID=persisted"
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            "{}",
                            HttpStatusCode.Unauthorized,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val candidate =
                InnerTube(client, retryDelay = {}).apply {
                    replaceSession("SAPISID=candidate", "visitor", "sync", "1", true)
                }
            try {
                assertNull(
                    YouTubeRepository(candidate).getAccountInfo(
                        publishExpiredSessionNotice = false,
                    ),
                )
                assertFalse(YouTubeSessionState.showExpiredSessionNotice)
                assertEquals("SAPISID=persisted", Settings.shared.innerTubeCookie)
            } finally {
                client.close()
            }
        }

    @Test
    fun homeBrowseRejectsHttpFailure() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            "{}",
                            HttpStatusCode.ServiceUnavailable,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }

            assertFailsWith<IllegalStateException> {
                YouTubeRepository(InnerTube(client, retryDelay = {})).getHomePageData()
            }
            client.close()
        }

    @Test
    fun songMetadataRequiresASuccessfulMatchingNonblankTitle() =
        runBlocking<Unit> {
            var body = SONG_METADATA
            var status = HttpStatusCode.OK
            var cancelled = false
            val client =
                HttpClient(
                    MockEngine {
                        if (cancelled) throw CancellationException("cancelled lookup")
                        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
            try {
                val song = repository.getSongMetadata("ABCDEFGHIJK")
                assertEquals("Title", song?.title)
                assertEquals("Artist", song?.artists?.single()?.name)
                assertEquals(200, song?.duration)
                assertNull(repository.getSongMetadata("unavailable"))
                for (invalid in listOf("{}", "{", SONG_METADATA.replace("Title", " "))) {
                    body = invalid
                    assertNull(repository.getSongMetadata("ABCDEFGHIJK"))
                }
                body = SONG_METADATA
                status = HttpStatusCode.ServiceUnavailable
                assertNull(repository.getSongMetadata("ABCDEFGHIJK"))
                cancelled = true
                assertFailsWith<CancellationException> { repository.getSongMetadata("ABCDEFGHIJK") }
            } finally {
                client.close()
            }
        }

    @Test
    fun songMetadataKeepsUnlinkedAndCombinedArtistNames() =
        runBlocking {
            val bodies = mutableListOf(SONG_METADATA_UNLINKED_ARTIST, SONG_METADATA_COMBINED_BYLINE)
            val client =
                HttpClient(
                    MockEngine {
                        respond(bodies.removeAt(0), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
            try {
                val unlinked = repository.getSongMetadata("UNLINKED")
                assertEquals("Maroon 5", unlinked?.artists?.single()?.name)
                assertNull(unlinked?.artists?.single()?.id)
                assertEquals("Songs About Jane", unlinked?.album?.name)

                val combined = repository.getSongMetadata("COMBINED")
                assertEquals("Seven Lions, Last Heroes & HALIENE", combined?.artists?.single()?.name)
                assertEquals(232, combined?.duration)
            } finally {
                client.close()
            }
        }

    @Test
    fun playlistMembershipStopsAfterFindingSong() =
        runBlocking {
            var requestCount = 0
            val client =
                HttpClient(
                    MockEngine {
                        requestCount++
                        respond(
                            if (requestCount == 1) PLAYLIST_WITH_TARGET_AND_CONTINUATION else EMPTY_PLAYLIST,
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }

            assertTrue(YouTubeRepository(InnerTube(client, retryDelay = {})).playlistContainsSong("playlist", "target"))
            assertEquals(1, requestCount)
            client.close()
        }

    @Test
    fun historyLoadsContinuationRowsAndKeepsTheirSectionLabel() =
        runBlocking {
            var requestCount = 0
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            if (requestCount++ == 0) HISTORY_FIRST_PAGE else HISTORY_CONTINUATION,
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val innerTube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
            val previousSync = Settings.shared.ytmSync
            val previousPaused = Settings.shared.pauseListenHistory
            try {
                Settings.shared.ytmSync = true
                Settings.shared.pauseListenHistory = false
                val history = YouTubeRepository(innerTube).getHistory()
                assertEquals(listOf("first", "second"), history.map { it.song.id })
                assertEquals(listOf("Today", "Today"), history.map(YouTubeHistoryEntry::playedText))
                assertEquals(2, requestCount)
            } finally {
                Settings.shared.ytmSync = previousSync
                Settings.shared.pauseListenHistory = previousPaused
                client.close()
            }
        }

    @Test
    fun historyLoadsActionContinuationRowsAndKeepsTheirSectionLabel() =
        runBlocking {
            var requestCount = 0
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            if (requestCount++ == 0) HISTORY_FIRST_PAGE else HISTORY_ACTION_CONTINUATION,
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val innerTube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
            val previousSync = Settings.shared.ytmSync
            val previousPaused = Settings.shared.pauseListenHistory
            try {
                Settings.shared.ytmSync = true
                Settings.shared.pauseListenHistory = false
                val history = YouTubeRepository(innerTube).getHistory()
                assertEquals(listOf("first", "action", "command", "endpoint"), history.map { it.song.id })
                assertEquals(List(4) { "Today" }, history.map(YouTubeHistoryEntry::playedText))
                assertEquals(2, requestCount)
            } finally {
                Settings.shared.ytmSync = previousSync
                Settings.shared.pauseListenHistory = previousPaused
                client.close()
            }
        }

    @Test
    fun historyLoadsSectionListContinuationWithoutReusingLabelsAcrossShelves() =
        runBlocking {
            var requestCount = 0
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            if (requestCount++ == 0) HISTORY_SECTION_FIRST_PAGE else HISTORY_SECTION_CONTINUATION,
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val innerTube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
            val previousSync = Settings.shared.ytmSync
            val previousPaused = Settings.shared.pauseListenHistory
            try {
                Settings.shared.ytmSync = true
                Settings.shared.pauseListenHistory = false
                val history = YouTubeRepository(innerTube).getHistory()
                assertEquals(listOf("first", "second", "third"), history.map { it.song.id })
                assertEquals(listOf("Today", "Yesterday", ""), history.map(YouTubeHistoryEntry::playedText))
                assertEquals(2, requestCount)
            } finally {
                Settings.shared.ytmSync = previousSync
                Settings.shared.pauseListenHistory = previousPaused
                client.close()
            }
        }

    @Test
    fun historyLoadsBeyondFiftyContinuations() =
        runBlocking {
            var requestCount = 0
            val client =
                HttpClient(
                    MockEngine {
                        val page = requestCount++
                        respond(
                            if (page < 60) {
                                HISTORY_FIRST_PAGE
                                    .replace("\"first\"", "\"song-$page\"")
                                    .replace("\"next\"", "\"next-$page\"")
                            } else {
                                HISTORY_CONTINUATION
                            },
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            try {
                Settings.shared.ytmSync = true
                Settings.shared.pauseListenHistory = false
                val innerTube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
                val result = YouTubeRepository(innerTube).getHistoryResult()
                assertTrue(result.complete)
                assertEquals(61, requestCount)
                assertEquals((0 until 60).map { "song-$it" } + "second", result.entries.map { it.song.id })
            } finally {
                client.close()
            }
        }

    @Test
    fun historyKeepsSettledRowsWhenAContinuationFails() =
        runBlocking {
            var requestCount = 0
            val client =
                HttpClient(
                    MockEngine {
                        requestCount++
                        respond(
                            if (requestCount == 1) HISTORY_FIRST_PAGE else "{}",
                            if (requestCount == 1) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val innerTube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
            val previousSync = Settings.shared.ytmSync
            val previousPaused = Settings.shared.pauseListenHistory
            try {
                Settings.shared.ytmSync = true
                Settings.shared.pauseListenHistory = false
                val result = YouTubeRepository(innerTube).getHistoryResult()
                assertEquals(listOf("first"), result.entries.map { it.song.id })
                assertFalse(result.complete)
            } finally {
                Settings.shared.ytmSync = previousSync
                Settings.shared.pauseListenHistory = previousPaused
                client.close()
            }
        }

    @Test
    fun historyRejectsRowsWhenTheAccountChangesDuringContinuation() =
        runBlocking<Unit> {
            var requestCount = 0
            val continuationStarted = CompletableDeferred<Unit>()
            val respondToContinuation = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine {
                        requestCount++
                        if (requestCount > 1) {
                            continuationStarted.complete(Unit)
                            respondToContinuation.await()
                        }
                        respond(
                            if (requestCount == 1) HISTORY_FIRST_PAGE else HISTORY_CONTINUATION,
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val innerTube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=old" }
            val previousSync = Settings.shared.ytmSync
            val previousPaused = Settings.shared.pauseListenHistory
            try {
                Settings.shared.ytmSync = true
                Settings.shared.pauseListenHistory = false
                val history = async { YouTubeRepository(innerTube).getHistory() }
                continuationStarted.await()
                innerTube.cookie = "SAPISID=new"
                respondToContinuation.complete(Unit)
                assertFailsWith<CancellationException> { history.await() }
            } finally {
                Settings.shared.ytmSync = previousSync
                Settings.shared.pauseListenHistory = previousPaused
                client.close()
            }
        }

    @Test
    fun completeLibraryRejectsRowsWhenTheAccountChangesDuringBrowse() =
        runBlocking<Unit> {
            val browseStarted = CompletableDeferred<Unit>()
            val respondToBrowse = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine {
                        browseStarted.complete(Unit)
                        respondToBrowse.await()
                        respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val innerTube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=old" }
            try {
                val library = async { YouTubeRepository(innerTube).getCompleteLibraryItemsResult() }
                browseStarted.await()
                innerTube.cookie = "SAPISID=new"
                respondToBrowse.complete(Unit)
                assertFailsWith<CancellationException> { library.await() }
            } finally {
                client.close()
            }
        }

    @Test
    fun episodeSaveRejectsHttpFailure() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            "{}",
                            HttpStatusCode.ServiceUnavailable,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }

            assertFalse(YouTubeRepository(InnerTube(client, retryDelay = {})).saveEpisode("episode"))
            client.close()
        }

    @Test
    fun repositoryDoesNotSwallowCancellation() {
        assertFailsWith<CancellationException> {
            runBlocking {
                val client =
                    HttpClient(
                        MockEngine {
                            respond(
                                "{}",
                                HttpStatusCode.ServiceUnavailable,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ) {
                        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                    }
                YouTubeRepository(
                    InnerTube(client, retryDelay = { throw CancellationException("cancelled") }),
                ).getHomePageData()
            }
        }
    }

    companion object {
        private const val ACCOUNT_MENU =
            """{"activeAccountHeaderRenderer":{"accountName":{"runs":[{"text":"Music account"}]}}}"""
        private const val HISTORY_FIRST_PAGE =
            """{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"selected":true,"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"title":{"runs":[{"text":"Today"}]},"contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"first"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"First","navigationEndpoint":{"watchEndpoint":{"videoId":"first"}}}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"}]}}}]} }],"continuations":[{"nextContinuationData":{"continuation":"next"}}]}}]}}}}]}}}"""
        private const val HISTORY_CONTINUATION =
            """{"continuationContents":{"musicShelfContinuation":{"contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"second"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Second","navigationEndpoint":{"watchEndpoint":{"videoId":"second"}}}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"}]}}}]}}]}}}"""
        private const val HISTORY_ACTION_CONTINUATION =
            """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"action"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Action","navigationEndpoint":{"watchEndpoint":{"videoId":"action"}}}]}}}]}}]}}],"onResponseReceivedCommands":[{"reloadContinuationItemsCommand":{"continuationItems":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"command"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Command","navigationEndpoint":{"watchEndpoint":{"videoId":"command"}}}]}}}]}}]}}],"onResponseReceivedEndpoints":[{"appendContinuationItemsCommand":{"continuationItems":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"endpoint"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Endpoint","navigationEndpoint":{"watchEndpoint":{"videoId":"endpoint"}}}]}}}]}}]}}]}"""
        private const val HISTORY_SECTION_FIRST_PAGE =
            """{"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"title":{"runs":[{"text":"Today"}]},"contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"first"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"First","navigationEndpoint":{"watchEndpoint":{"videoId":"first"}}}]}}}]}}]}}],"continuations":[{"nextContinuationData":{"continuation":"section-next"}}]}}}"""
        private const val HISTORY_SECTION_CONTINUATION =
            """{"continuationContents":{"sectionListContinuation":{"contents":[{"musicShelfRenderer":{"title":{"runs":[{"text":"Yesterday"}]},"contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"second"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Second","navigationEndpoint":{"watchEndpoint":{"videoId":"second"}}}]}}}]}}]}},{"musicShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"third"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Third","navigationEndpoint":{"watchEndpoint":{"videoId":"third"}}}]}}}]}}]}}]}}}"""
        private const val SONG_METADATA_UNLINKED_ARTIST =
            """{"queueDatas":[{"content":{"playlistPanelVideoRenderer":{"videoId":"UNLINKED","title":{"runs":[{"text":"Title"}]},"longBylineText":{"runs":[{"text":"Maroon 5"},{"text":" • "},{"text":"Songs About Jane","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_album"}}}]},"lengthText":{"runs":[{"text":"3:20"}]}}}}]}"""
        private const val SONG_METADATA_COMBINED_BYLINE =
            """{"queueDatas":[{"content":{"playlistPanelVideoRenderer":{"videoId":"COMBINED","title":{"runs":[{"text":"Title"}]},"longBylineText":{"runs":[{"text":"Seven Lions, Last Heroes & HALIENE • 3:52"}]},"lengthText":{"runs":[{"text":"3:52"}]}}}}]}"""
        private const val SONG_METADATA =
            """{"queueDatas":[{"content":{"playlistPanelVideoRenderer":{"videoId":"ABCDEFGHIJK","title":{"runs":[{"text":"Title"}]},"longBylineText":{"runs":[{"text":"Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}}]},"lengthText":{"runs":[{"text":"3:20"}]}}}}]}"""
        private const val PLAYLIST_WITH_TARGET_AND_CONTINUATION =
            """{"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Target"}]}}}],"playlistItemData":{"videoId":"target"}}}],"continuations":[{"nextContinuationData":{"continuation":"next"}}]}}]}}}}}"""
        private const val EMPTY_PLAYLIST = "{}"
    }
}
