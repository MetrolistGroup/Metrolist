package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.canSwitchToAudioTrack
import com.metrolist.shared.model.canSwitchToMusicVideo
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.LIBRARY_REFRESH_TTL_MILLIS
import com.metrolist.shared.youtube.extraction.models.toContentHints
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
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadedSongsTest {
    @BeforeTest
    fun setUp() = Settings.replaceSharedForTest(Settings(MapSettings()))

    @AfterTest
    fun tearDown() = Settings.replaceSharedForTest(null)

    @Test
    fun uploadRowsKeepPlayableIdsMetadataAndAccountHintsAcrossParsers() {
        for (endpoint in listOf(
            """"menu":{"queueAddEndpoint":{"queueTarget":{"videoId":"upload"}}}""",
            """"playlistItemData":{"videoId":"upload","playlistSetVideoId":"entry"}""",
            """"navigationEndpoint":{"watchEndpoint":{"videoId":"upload"}}""",
            """"overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{"videoId":"upload"}}}}}}""",
        )) {
            val renderer = Json.parseToJsonElement(row("upload", endpoint)).jsonObject["musicResponsiveListItemRenderer"]!!.jsonObject
            for (song in listOf(
                YouTubeResponseParser.parseUploadedSongRenderer(renderer),
                assertIs<SongItem>(YouTubeItemParser.parseItem(renderer)),
            )) {
                requireNotNull(song)
                assertEquals("upload", song.id)
                assertEquals("My recording", song.title)
                assertEquals("My artist", song.artists.single().name)
                assertEquals("My album", song.album?.name)
                assertEquals(361L, song.duration)
                assertEquals("entity-upload", song.uploadEntityId)
                assertTrue(song.isUploaded)
                assertFalse(song.isVideoSong)
                assertFalse(song.canSwitchToAudioTrack)
                assertFalse(song.canSwitchToMusicVideo)
                assertTrue(song.toContentHints().isUploaded == true)
                assertFalse(song.toContentHints(wantVideo = true).wantVideo)
                val restored = Json.decodeFromString<SongItem>(Json.encodeToString(song))
                assertEquals(song, restored)
                assertTrue(restored.toContentHints().isUploaded == true)
            }
        }
    }

    @Test
    fun searchCardsAndQueueRowsRecognizePrivateUploadType() {
        for (renderer in listOf(
            """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Upload"}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"upload","musicVideoType":"$UPLOAD_TYPE"}}}}""",
            """{"musicMultiRowListItemRenderer":{"title":{"runs":[{"text":"Upload"}]},"onTap":{"watchEndpoint":{"videoId":"upload","musicVideoType":"$UPLOAD_TYPE"}}}}""",
        )) {
            val song = assertIs<SongItem>(YouTubeItemParser.parseItem(Json.parseToJsonElement(renderer).jsonObject))
            assertTrue(song.isUploaded)
            assertFalse(song.isVideoSong)
            assertTrue(song.toContentHints().isUploaded == true)
            assertTrue(song.copy(isUploaded = false).toContentHints().isUploaded == true)
            assertFalse(song.copy(isUploaded = false).canSwitchToMusicVideo)
        }
    }

    @Test
    fun queueMetadataKeepsUploadHintForPlaybackById() {
        val content =
            Json
                .parseToJsonElement(
                    """{"playlistPanelVideoRenderer":{"videoId":"upload","title":{"runs":[{"text":"My recording"}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"upload","musicVideoType":"$UPLOAD_TYPE"}}}}""",
                ).jsonObject
        val song = requireNotNull(YouTubeRadioParser.parsePlaylistPanelItem(content))
        assertTrue(song.isUploaded)
        assertFalse(song.isVideoSong)
        assertTrue(song.toContentHints().isUploaded == true)
    }

    @Test
    fun completeLibraryIncludesUploadsAndBothShelfContinuationShapes() =
        runBlocking {
            for (continuationType in listOf("musicShelfContinuation", "musicPlaylistShelfContinuation")) {
                val continuations = mutableListOf<String>()
                val client =
                    HttpClient(
                        MockEngine { request ->
                            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                            val browseId = body["browseId"]?.jsonPrimitive?.content
                            val cursor = body["continuation"]?.jsonPrimitive?.content
                            val response =
                                when {
                                    browseId == "FEmusic_library_privately_owned_tracks" -> {
                                        assertTrue(request.headers[HttpHeaders.Authorization]?.startsWith("SAPISIDHASH ") == true)
                                        assertEquals("1", request.headers["X-Goog-AuthUser"])
                                        val firstRow = row("first")
                                        """{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{}},{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[$firstRow],"continuations":[{"nextContinuationData":{"continuation":"next"}}]}}]}}}}]}}}"""
                                    }

                                    cursor != null -> {
                                        continuations += cursor
                                        assertTrue(request.headers[HttpHeaders.Authorization]?.startsWith("SAPISIDHASH ") == true)
                                        """{"continuationContents":{"$continuationType":{"contents":[${row("second")} ]}}}"""
                                    }

                                    else -> {
                                        """{"contents":{"sectionListRenderer":{"contents":[]}}}"""
                                    }
                                }
                            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
                try {
                    val tube =
                        InnerTube(client, retryDelay = {}).apply {
                            cookie = "SAPISID=test"
                            authUser = "1"
                            useLoginForBrowse = false
                        }
                    val result = YouTubeRepository(tube).getCompleteLibraryItemsResult()
                    assertTrue(result.complete)
                    assertEquals(listOf("first", "second"), result.items.map { it.id })
                    assertEquals(listOf("first", "second"), result.uploadedSongs?.map { it.id })
                    assertTrue(result.items.filterIsInstance<SongItem>().all { it.isUploaded })
                    assertEquals(listOf("next"), continuations)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun failedUploadBrowseDoesNotClaimACompleteLibrary() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine { request ->
                        val body = (request.body as TextContent).text
                        respond(
                            "{}",
                            status = if ("privately_owned_tracks" in body) HttpStatusCode.Forbidden else HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" })
                assertTrue(repository.getLibraryUploadSongsResult().isFailure)
                val result = repository.getCompleteLibraryItemsResult()
                assertFalse(result.complete)
                assertNull(result.uploadedSongs)
            } finally {
                client.close()
            }
        }

    @Test
    fun signedOutUploadsFetchCannotConfirmRemovals() =
        runBlocking {
            val client = HttpClient(MockEngine { error("Signed-out uploads must not send a request") })
            try {
                assertTrue(YouTubeRepository(InnerTube(client, retryDelay = {})).getLibraryUploadSongsResult(limit = null).isFailure)
            } finally {
                client.close()
            }
        }

    @Test
    fun uploadsRemainAuthoritativeWhenAnUnrelatedLibraryRequestFails() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine { request ->
                        val body = (request.body as TextContent).text
                        val contents = if ("FEmusic_liked_videos" in body) row("removed-upload") else ""
                        respond(
                            """{"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[$contents]}}]}}}""",
                            status = if ("FEmusic_liked_albums" in body) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val result =
                    YouTubeRepository(
                        InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" },
                    ).getCompleteLibraryItemsResult()
                assertFalse(result.complete)
                assertEquals(emptyList(), result.uploadedSongs)
                assertTrue(result.items.isEmpty())
            } finally {
                client.close()
            }
        }

    @Test
    fun emptyUploadLibraryIsACompleteSuccessfulFetch() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            """{"contents":{"sectionListRenderer":{"contents":[]}}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" })
                assertTrue(repository.getLibraryUploadSongsResult(limit = null).getOrThrow().isEmpty())
                assertTrue(repository.getCompleteLibraryItemsResult().complete)
            } finally {
                client.close()
            }
        }

    @Test
    fun incompleteUploadPaginationCannotConfirmLibraryRemovals() =
        runBlocking {
            for (failure in listOf("http", "cycle", "ambiguous", "missing-shelf", "missing-library")) {
                val client =
                    HttpClient(
                        MockEngine { request ->
                            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                            val upload = body["browseId"]?.jsonPrimitive?.content == "FEmusic_library_privately_owned_tracks"
                            val continuation = body["continuation"] != null
                            val next = """{"nextContinuationData":{"continuation":"next"}}"""
                            val tokens =
                                if (failure == "ambiguous") {
                                    "$next," + """{"nextContinuationData":{"continuation":"other"}}"""
                                } else {
                                    next
                                }
                            val response =
                                when {
                                    upload && failure == "missing-library" -> {
                                        "{}"
                                    }

                                    upload -> {
                                        """{"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[${row(
                                            "first",
                                        )}],"continuations":[$tokens]}}]}}}"""
                                    }

                                    continuation && failure == "cycle" -> {
                                        val shelf = """{"contents":[],"continuations":[$next]}"""
                                        """{"continuationContents":{"musicShelfContinuation":$shelf}}"""
                                    }

                                    else -> {
                                        "{}"
                                    }
                                }
                            respond(
                                response,
                                status = if (continuation && failure == "http") HttpStatusCode.Forbidden else HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
                try {
                    val repository = YouTubeRepository(InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" })
                    assertTrue(repository.getLibraryUploadSongsResult(limit = null, force = false).isFailure, failure)
                    assertTrue(repository.getLibraryUploadSongsResult(limit = null, force = false).isFailure, failure)
                    assertFalse(repository.getCompleteLibraryItemsResult().complete, failure)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun accountSwitchDuringUploadBrowseCancelsInsteadOfPublishingOldSongs() =
        runBlocking {
            lateinit var tube: InnerTube
            val client =
                HttpClient(
                    MockEngine {
                        tube.cookie = "SAPISID=other"
                        respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
                assertFailsWith<CancellationException> { YouTubeRepository(tube).getLibraryUploadSongsResult() }
                Unit
            } finally {
                client.close()
            }
        }

    @Test
    fun pollingPublishesLaterUploadsAfterAnEarlierUploadAppears() =
        runTest {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("account_menu")) {
                            return@MockEngine respond(
                                """{"activeAccountHeaderRenderer":{"accountName":{"runs":[{"text":"Music account"}]}}}""",
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        requests++
                        val ids = if (requests < 3) listOf("first") else listOf("first", "second")
                        if (requests == 2) {
                            respond("{}", HttpStatusCode.Forbidden)
                        } else {
                            respond(
                                """{"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[${ids.joinToString {
                                    row(
                                        it,
                                    )
                                }}]}}]}}}""",
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
            try {
                val updates = mutableListOf<List<String>>()
                val result =
                    YouTubeRepository(tube).awaitProcessedUpload(emptySet()) { songs ->
                        updates += songs.map { it.id }
                    }
                assertEquals(listOf("first"), updates.first())
                assertEquals(listOf("first", "second"), updates.last())
                assertEquals(listOf("first", "second"), result?.map { it.id })
                assertEquals(12, requests)
            } finally {
                tube.close()
                client.close()
            }
        }

    @Test
    fun automaticUploadScansSharePagesAndReuseOnlyFreshCompleteResults() =
        runTest {
            var now = 1_000L
            var requests = 0
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        val body = (request.body as TextContent).text
                        when {
                            "privately_owned_tracks" in body -> {
                                requests++
                                if (requests == 1) {
                                    started.complete(Unit)
                                    release.await()
                                }
                                respond(
                                    """{"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[${row(
                                        "first",
                                    )}],"continuations":[{"nextContinuationData":{"continuation":"next"}}]}}]}}}""",
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }

                            "next" in body -> {
                                requests++
                                respond(
                                    """{"continuationContents":{"musicShelfContinuation":{"contents":[${row("second")}]}}}""",
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }

                            else -> {
                                respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                            }
                        }
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
            val repository = YouTubeRepository(tube, nowMs = { now })
            try {
                val first = async { repository.getLibraryUploadSongsResult(limit = null, force = false).getOrThrow() }
                started.await()
                val joined =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        repository.getLibraryUploadSongsResult(limit = null, force = true).getOrThrow()
                    }
                assertFalse(joined.isCompleted)
                release.complete(Unit)
                assertEquals(listOf("first", "second"), first.await().map { it.id })
                assertEquals(first.await(), joined.await())
                assertEquals(2, requests)
                assertEquals(first.await(), repository.getCompleteLibraryItemsResult(force = false).uploadedSongs)
                assertEquals(first.await(), repository.getLibraryUploadSongsResult(limit = null, force = false).getOrThrow())
                assertEquals(2, requests)

                repository.getLibraryUploadSongsResult(limit = null, force = true).getOrThrow()
                assertEquals(4, requests)
                now += LIBRARY_REFRESH_TTL_MILLIS - 1
                repository.getLibraryUploadSongsResult(limit = null, force = false).getOrThrow()
                assertEquals(4, requests)
                now++
                repository.getLibraryUploadSongsResult(limit = null, force = false).getOrThrow()
                assertEquals(6, requests)
                tube.authUser = "1"
                repository.getLibraryUploadSongsResult(limit = null, force = false).getOrThrow()
                assertEquals(8, requests)
                tube.authUser = "0"
                repository.getLibraryUploadSongsResult(limit = null, force = false).getOrThrow()
                assertEquals(10, requests)
            } finally {
                tube.close()
                client.close()
            }
        }

    @Test
    fun limitedAndFailedUploadScansDoNotMakeAutomaticScansFresh() =
        runTest {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(
                            if (requests == 2) "{}" else """{"contents":{"sectionListRenderer":{"contents":[]}}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
            val repository = YouTubeRepository(tube)
            try {
                assertTrue(repository.getLibraryUploadSongsResult(limit = 1).isSuccess)
                assertTrue(repository.getLibraryUploadSongsResult(limit = null, force = false).isFailure)
                assertTrue(repository.getLibraryUploadSongsResult(limit = null, force = false).isSuccess)
                assertTrue(repository.getLibraryUploadSongsResult(limit = null, force = false).isSuccess)
                assertEquals(3, requests)
            } finally {
                tube.close()
                client.close()
            }
        }

    @Test
    fun uploadMutationsInvalidateCompletedAndActiveScans() =
        runTest {
            var scans = 0
            var holdScan = false
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        when {
                            request.url.encodedPath.endsWith("/browse") -> {
                                scans++
                                if (holdScan) {
                                    started.complete(Unit)
                                    release.await()
                                }
                                respond(
                                    """{"contents":{"sectionListRenderer":{"contents":[]}}}""",
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }

                            request.headers["X-Goog-Upload-Command"] == "start" -> {
                                respond("", headers = headersOf("X-Goog-Upload-URL", "https://upload.youtube.com/?upload_id=session"))
                            }

                            else -> {
                                respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                            }
                        }
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
            val repository = YouTubeRepository(tube)
            try {
                repository.getLibraryUploadSongsResult(limit = null, force = false).getOrThrow()
                assertFalse(repository.uploadSong("song.wav", 1) { error("Unsupported upload") }.success)
                repository.getLibraryUploadSongsResult(limit = null, force = false).getOrThrow()
                assertEquals(1, scans)
                assertTrue(repository.uploadSong("song.mp3", 1) { ByteReadChannel(byteArrayOf(1)) }.success)
                repository.getLibraryUploadSongsResult(limit = null, force = false).getOrThrow()
                assertEquals(2, scans)
                assertTrue(repository.deleteUploadedSong("entity"))
                holdScan = true
                val old = async { repository.getLibraryUploadSongsResult(limit = null, force = false) }
                started.await()
                assertTrue(repository.deleteUploadedSong("entity"))
                holdScan = false
                release.complete(Unit)
                assertFailsWith<CancellationException> { old.await() }
                repository.getLibraryUploadSongsResult(limit = null, force = false).getOrThrow()
                assertEquals(4, scans)
            } finally {
                tube.close()
                client.close()
            }
        }

    @Test
    fun activeUploadWaiterRetriesWhenOwnerIsCancelled() =
        runTest {
            var requests = 0
            val started = CompletableDeferred<Unit>()
            val retryStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        if (requests == 1) {
                            started.complete(Unit)
                            awaitCancellation()
                        }
                        retryStarted.complete(Unit)
                        release.await()
                        respond(
                            """{"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[${row(
                                "upload",
                            )}]}}]}}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
            val repository = YouTubeRepository(tube)
            try {
                val owner = async { repository.getLibraryUploadSongsResult(limit = null, force = false) }
                started.await()
                val cancelledWaiter =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        repository.getLibraryUploadSongsResult(limit = null, force = false)
                    }
                cancelledWaiter.cancelAndJoin()
                assertFailsWith<CancellationException> { cancelledWaiter.await() }
                assertTrue(owner.isActive)
                assertEquals(1, requests)
                val waiter =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        repository.getLibraryUploadSongsResult(limit = null, force = false).getOrThrow()
                    }
                owner.cancelAndJoin()
                assertFailsWith<CancellationException> { owner.await() }
                retryStarted.await()
                assertFalse(waiter.isCompleted)
                assertEquals(2, requests)
                release.complete(Unit)
                assertEquals(listOf("upload"), waiter.await().map { it.id })
                assertEquals(waiter.await(), repository.getLibraryUploadSongsResult(limit = null, force = false).getOrThrow())
                assertEquals(2, requests)
            } finally {
                tube.close()
                client.close()
            }
        }

    @Test
    fun cancelledUploadOwnerDoesNotRetryWaiterAfterSessionChanges() =
        runTest {
            var requests = 0
            val started = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        started.complete(Unit)
                        awaitCancellation()
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
            val repository = YouTubeRepository(tube)
            try {
                val owner = async { repository.getLibraryUploadSongsResult(limit = null, force = false) }
                started.await()
                val waiter =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        repository.getLibraryUploadSongsResult(limit = null, force = false)
                    }
                tube.authUser = "1"
                owner.cancelAndJoin()
                assertFailsWith<CancellationException> { waiter.await() }
                assertEquals(1, requests)
            } finally {
                tube.close()
                client.close()
            }
        }

    @Test
    fun cancelledUploadScanCanBeRetriedAutomatically() =
        runTest {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        if (requests == 1) throw CancellationException("Screen closed")
                        respond(
                            """{"contents":{"sectionListRenderer":{"contents":[]}}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
            val repository = YouTubeRepository(tube)
            try {
                assertFailsWith<CancellationException> { repository.getLibraryUploadSongsResult(limit = null, force = false) }
                assertTrue(repository.getLibraryUploadSongsResult(limit = null, force = false).isSuccess)
                assertEquals(2, requests)
            } finally {
                tube.close()
                client.close()
            }
        }

    private companion object {
        const val UPLOAD_TYPE = "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

        fun row(
            id: String,
            endpoint: String = """"playlistItemData":{"videoId":"$id"}""",
        ) = """
            {"musicResponsiveListItemRenderer":{
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"My recording"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"My artist"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"My album"}]}}}
              ],
              "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"simpleText":"6:01"}}}],
              "musicVideoType":"$UPLOAD_TYPE",
              "delete":{"musicDeletePrivatelyOwnedEntityCommand":{"entityId":"entity-$id"}},
              $endpoint
            }}
            """.trimIndent()
    }
}
