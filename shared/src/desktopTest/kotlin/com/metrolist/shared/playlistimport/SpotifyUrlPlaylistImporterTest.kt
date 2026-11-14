package com.metrolist.shared.playlistimport

import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyUrlPlaylistImporterTest {
    @Test
    fun providerDeadlineBecomesATypedFailureAndTerminatesImportStatus() =
        runTest {
            val client =
                HttpClient(
                    MockEngine(
                        MockEngineConfig().apply {
                            dispatcher = StandardTestDispatcher(testScheduler)
                            addHandler { awaitCancellation() }
                        },
                    ),
                )
            SpotifyUrlPlaylistImporter.resetClientForTests(client)
            val statusId = PlaylistImportStatusManager.startImport("Playlist", "Spotify")
            try {
                val result = SpotifyUrlPlaylistImporter.loadPlaylist("https://open.spotify.com/playlist/test123")
                val error = result.exceptionOrNull()
                assertEquals(ImportErrorKey.IncompleteSource, (error as? ImportException)?.errorKey)
                assertEquals(15_000L, testScheduler.currentTime)
                PlaylistImportStatusManager.fail(statusId, ImportProgressState.Failed("failed", error))
                assertEquals(false, PlaylistImportStatusManager.activeImport.value?.isRunning)
            } finally {
                PlaylistImportStatusManager.clear(statusId)
                SpotifyUrlPlaylistImporter.resetClientForTests()
                client.close()
            }
        }

    @Test
    fun callerCancellationStillClearsParsingStatus() =
        runTest {
            val client =
                HttpClient(
                    MockEngine(
                        MockEngineConfig().apply {
                            dispatcher = StandardTestDispatcher(testScheduler)
                            addHandler { awaitCancellation() }
                        },
                    ),
                )
            SpotifyUrlPlaylistImporter.resetClientForTests(client)
            val statusId = PlaylistImportStatusManager.startImport("Playlist", "Spotify")
            try {
                val parsing =
                    async {
                        try {
                            SpotifyUrlPlaylistImporter.loadPlaylist("https://open.spotify.com/playlist/test123")
                        } catch (error: CancellationException) {
                            PlaylistImportStatusManager.finishCancellation(statusId, error)
                            throw error
                        }
                    }
                runCurrent()
                parsing.cancelAndJoin()
                assertEquals(true, parsing.isCancelled)
                assertEquals(null, PlaylistImportStatusManager.activeImport.value)
            } finally {
                PlaylistImportStatusManager.clear(statusId)
                SpotifyUrlPlaylistImporter.resetClientForTests()
                client.close()
            }
        }

    @Test
    fun retriedPathfinderRateLimitDoesNotLogAnIntermediateFailure() =
        runBlocking {
            PlaybackEventLog.clear()
            val mockHtml = createEmbedHtml(trackCount = 100, hasAccessToken = true)
            var calls = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        when {
                            request.url.toString().contains("/embed/playlist/") -> {
                                respondHtml(mockHtml)
                            }

                            request.url.toString().contains("clienttoken") -> {
                                respondJson("""{"granted_token":{"token":"mock-token","expires_after_seconds":3600}}""")
                            }

                            request.url.toString().contains("pathfinder") -> {
                                if (calls++ == 0) {
                                    respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "1"))
                                } else {
                                    respondJson("""{"data":{"playlistV2":{"content":{"totalCount":100,"items":[]}}}}""")
                                }
                            }

                            else -> {
                                error("Unexpected request: ${request.url}")
                            }
                        }
                    },
                ) {
                    install(HttpFailureLog)
                }
            SpotifyUrlPlaylistImporter.resetClientForTests(client)
            try {
                assertTrue(SpotifyUrlPlaylistImporter.loadPlaylist("https://open.spotify.com/playlist/test123456789012345678").isSuccess)
                assertEquals(2, calls)
                assertTrue(PlaybackEventLog.events.value.isEmpty())
            } finally {
                client.close()
                SpotifyUrlPlaylistImporter.resetClientForTests()
                PlaybackEventLog.clear()
            }
        }

    @Test
    fun missingPaginationCredentialsAndTruncatedResponsesFailInsteadOfReturningTheEmbed() =
        runBlocking {
            for (failure in listOf("access", "client", "parse", "empty")) {
                val client =
                    HttpClient(
                        MockEngine { request ->
                            when {
                                request.url.toString().contains("/embed/") -> {
                                    respondHtml(createEmbedHtml(100, failure != "access"))
                                }

                                request.url.toString().contains("clienttoken") -> {
                                    respondJson(
                                        if (failure ==
                                            "client"
                                        ) {
                                            "{}"
                                        } else {
                                            """
                                            {"granted_token":{"token":"test","expires_after_seconds":3600}}
                                            """.trimIndent()
                                        },
                                    )
                                }

                                else -> {
                                    respondJson(
                                        if (failure ==
                                            "parse"
                                        ) {
                                            "invalid"
                                        } else {
                                            """
                                            {"data":{"playlistV2":{"content":{"totalCount":150,"items":[]}}}}
                                            """.trimIndent()
                                        },
                                    )
                                }
                            }
                        },
                    )
                try {
                    SpotifyUrlPlaylistImporter.resetClientForTests(client)
                    val result = SpotifyUrlPlaylistImporter.loadPlaylist("spotify:playlist:test")
                    assertEquals(ImportErrorKey.IncompleteSource, (result.exceptionOrNull() as ImportException).errorKey)
                } finally {
                    client.close()
                }
            }
        }

    @After
    fun tearDown() {
        SpotifyUrlPlaylistImporter.resetClientForTests(null)
    }

    @Test
    fun canHandleRecognizesSupportedSpotifyFormats() {
        assertTrue(SpotifyUrlPlaylistImporter.canHandle("https://open.spotify.com/playlist/37i9dQZF1DXbTxeAdrVG2l"))
        assertTrue(SpotifyUrlPlaylistImporter.canHandle("https://open.spotify.com/intl-it/playlist/37i9dQZF1DXbTxeAdrVG2l?si=123"))
        assertTrue(SpotifyUrlPlaylistImporter.canHandle("https://open.spotify.com/album/4m2880jivSbbyEGAKfITCa"))
        assertTrue(SpotifyUrlPlaylistImporter.canHandle("https://spotify.com/playlist/37i9dQZF1DXbTxeAdrVG2l"))
        assertTrue(SpotifyUrlPlaylistImporter.canHandle("spotify:playlist:37i9dQZF1DXbTxeAdrVG2l"))
        assertTrue(SpotifyUrlPlaylistImporter.canHandle("spotify:album:4m2880jivSbbyEGAKfITCa"))

        assertFalse(SpotifyUrlPlaylistImporter.canHandle("https://music.apple.com/us/playlist/pl.12345"))
        assertFalse(SpotifyUrlPlaylistImporter.canHandle("https://www.deezer.com/playlist/12345"))
        assertFalse(SpotifyUrlPlaylistImporter.canHandle("https://youtube.com/playlist?list=PL12345"))
        assertFalse(SpotifyUrlPlaylistImporter.canHandle("Some random song list"))
    }

    @Test
    fun parseUrlCorrectlyExtractsPlaylistAndAlbumIds() =
        runBlocking {
            SpotifyUrlPlaylistImporter.resetClientForTests(HttpClient(MockEngine { respondHtml(createEmbedHtml(1, false)) }))
            val validUrls =
                listOf(
                    "https://open.spotify.com/playlist/37i9dQZF1DXbTxeAdrVG2l" to "37i9dQZF1DXbTxeAdrVG2l",
                    "https://open.spotify.com/intl-it/playlist/37i9dQZF1DXbTxeAdrVG2l?si=abc" to "37i9dQZF1DXbTxeAdrVG2l",
                    "https://open.spotify.com/intl-es/playlist/37i9dQZF1DXbTxeAdrVG2l" to "37i9dQZF1DXbTxeAdrVG2l",
                    "spotify:playlist:37i9dQZF1DXbTxeAdrVG2l" to "37i9dQZF1DXbTxeAdrVG2l",
                    "37i9dQZF1DXbTxeAdrVG2l" to "37i9dQZF1DXbTxeAdrVG2l",
                )

            for ((url, _) in validUrls) {
                val result = SpotifyUrlPlaylistImporter.loadPlaylist(url)
                assertFalse(
                    "Failed for URL: $url",
                    result.exceptionOrNull() is IllegalArgumentException,
                )
            }
        }

    @Test
    fun missingPlaylistTitleUsesCallerFallback() =
        runBlocking {
            val html = createEmbedHtml(trackCount = 1, hasAccessToken = false).replace("\"name\": \"Test Playlist\",", "")
            val fallbackTitles =
                PlaylistImportFallbackTitles(
                    playlist = "Selected-language playlist",
                    importedPlaylist = "Selected-language imported playlist",
                    album = "Selected-language album",
                    track = "Selected-language track",
                )
            SpotifyUrlPlaylistImporter.resetClientForTests(
                HttpClient(MockEngine { respondHtml(html) }),
            )

            val result =
                UnifiedPlaylistImporter
                    .parseFromInput("https://open.spotify.com/playlist/test123456789012345678", fallbackTitles)
                    .getOrThrow()

            assertEquals(fallbackTitles.playlist, result.title)
            assertEquals(ImportSourceType.SPOTIFY, result.provider)
        }

    @Test
    fun loadsSmallPlaylistWithoutPathfinderPagination() =
        runBlocking {
            val mockHtml =
                createEmbedHtml(
                    trackCount = 5,
                    hasAccessToken = true,
                )

            var clientTokenRequested = false
            var pathfinderRequested = false

            val engine =
                MockEngine { request ->
                    val urlStr = request.url.toString()
                    when {
                        urlStr.contains("/embed/playlist/") -> {
                            respondHtml(mockHtml)
                        }

                        urlStr.contains("clienttoken") -> {
                            clientTokenRequested = true
                            respondJson("""{"granted_token":{"token":"mock-token","expires_after_seconds":3600}}""")
                        }

                        urlStr.contains("pathfinder") -> {
                            pathfinderRequested = true
                            respondJson("""{"data":{"playlistV2":{"content":{"totalCount":5,"items":[]}}}}""")
                        }

                        else -> {
                            error("Unexpected request: $urlStr")
                        }
                    }
                }

            SpotifyUrlPlaylistImporter.resetClientForTests(HttpClient(engine))

            val result = SpotifyUrlPlaylistImporter.loadPlaylist("https://open.spotify.com/playlist/test123456789012345678")
            assertTrue(result.isSuccess)

            val playlist = result.getOrThrow()
            assertEquals("Test Playlist", playlist.title)
            assertEquals(5, playlist.tracks.size)
            assertEquals("Track 1", playlist.tracks[0].title)
            assertEquals("Artist 1", playlist.tracks[0].artist)
            assertEquals("Track 5", playlist.tracks[4].title)
            assertFalse("Client token should not be requested when < 100 tracks", clientTokenRequested)
            assertFalse("Pathfinder should not be queried when < 100 tracks", pathfinderRequested)
        }

    @Test
    fun paginatesBeyond100TracksUsingPathfinder() =
        runBlocking {
            val mockHtml =
                createEmbedHtml(
                    trackCount = 100,
                    hasAccessToken = true,
                )

            var pathfinderCalls = 0
            val engine =
                MockEngine { request ->
                    val urlStr = request.url.toString()
                    when {
                        urlStr.contains("/embed/playlist/") -> {
                            respondHtml(mockHtml)
                        }

                        urlStr.contains("clienttoken") -> {
                            respondJson("""{"granted_token":{"token":"mock-client-token","expires_after_seconds":3600}}""")
                        }

                        urlStr.contains("pathfinder") -> {
                            pathfinderCalls++
                            val items =
                                (101..130).joinToString(",") { i ->
                                    """
                                    {
                                        "itemV2": {
                                            "data": {
                                                "name": "Track $i",
                                                "artists": {
                                                    "items": [{"profile": {"name": "Artist $i"}}]
                                                }
                                            }
                                        }
                                    }
                                    """.trimIndent()
                                }
                            respondJson(
                                """
                                {
                                    "data": {
                                        "playlistV2": {
                                            "content": {
                                                "totalCount": 130,
                                                "items": [$items]
                                            }
                                        }
                                    }
                                }
                                """.trimIndent(),
                            )
                        }

                        else -> {
                            error("Unexpected request: $urlStr")
                        }
                    }
                }

            SpotifyUrlPlaylistImporter.resetClientForTests(HttpClient(engine))

            var reportedTotal: Int? = null
            var reportedLoaded = 0
            val result =
                SpotifyUrlPlaylistImporter.loadPlaylist(
                    "https://open.spotify.com/playlist/test123456789012345678",
                ) { loaded, total ->
                    reportedLoaded = loaded
                    reportedTotal = total
                }

            assertTrue(result.isSuccess)
            val playlist = result.getOrThrow()
            assertEquals("Test Playlist", playlist.title)
            assertEquals(130, playlist.tracks.size)
            assertEquals("Track 1", playlist.tracks[0].title)
            assertEquals("Track 100", playlist.tracks[99].title)
            assertEquals("Track 101", playlist.tracks[100].title)
            assertEquals("Artist 101", playlist.tracks[100].artist)
            assertEquals("Track 130", playlist.tracks[129].title)
            assertEquals(1, pathfinderCalls)
            assertEquals(130, reportedLoaded)
            assertEquals(130, reportedTotal)
        }

    @Test
    fun paginationSkipsUnavailableEntriesAndSupportsWrapperVariants() =
        runBlocking {
            val mockHtml = createEmbedHtml(trackCount = 100, hasAccessToken = true)
            var pathfinderCalls = 0
            val engine =
                MockEngine { request ->
                    when {
                        request.url.toString().contains("/embed/playlist/") -> {
                            respondHtml(mockHtml)
                        }

                        request.url.toString().contains("clienttoken") -> {
                            respondJson("""{"granted_token":{"token":"mock-token","expires_after_seconds":3600}}""")
                        }

                        request.url.toString().contains("pathfinder") -> {
                            pathfinderCalls++
                            respondJson(
                                """
                                {
                                    "data": {"playlistV2": {"content": {
                                        "totalCount": 103,
                                        "items": [
                                            null,
                                            "removed",
                                            {"itemV2": null},
                                            {"track": {"data": {"name": "Track 101", "artists": {"items": [{"profile": {"name": "Artist 101"}}]}}}},
                                            {"name": "Track 102", "artists": {"items": [{"profile": {"name": "Artist 102"}}]}},
                                            {"itemV2": {"data": {"name": "Track 103", "artists": {"items": [{"profile": {"name": "Artist 103"}}]}}}}
                                        ]
                                    }}}
                                }
                                """.trimIndent(),
                            )
                        }

                        else -> {
                            error("Unexpected request: ${request.url}")
                        }
                    }
                }

            SpotifyUrlPlaylistImporter.resetClientForTests(HttpClient(engine))

            val result = SpotifyUrlPlaylistImporter.loadPlaylist("https://open.spotify.com/playlist/test123456789012345678")

            assertTrue(result.isSuccess)
            val tracks = result.getOrThrow().tracks
            assertEquals(103, tracks.size)
            assertEquals(listOf("Track 101", "Track 102", "Track 103"), tracks.takeLast(3).map { it.title })
            assertEquals(listOf("Artist 101", "Artist 102", "Artist 103"), tracks.takeLast(3).map { it.artist })
            assertEquals(1, pathfinderCalls)
        }

    @Test
    fun reportsIncompleteSourceIfPathfinderFails() =
        runBlocking {
            val mockHtml =
                createEmbedHtml(
                    trackCount = 100,
                    hasAccessToken = true,
                )

            val engine =
                MockEngine { request ->
                    val urlStr = request.url.toString()
                    when {
                        urlStr.contains("/embed/playlist/") -> {
                            respondHtml(mockHtml)
                        }

                        urlStr.contains("clienttoken") -> {
                            respondJson("""{"granted_token":{"token":"mock-token","expires_after_seconds":3600}}""")
                        }

                        urlStr.contains("pathfinder") -> {
                            respondJson("{}", HttpStatusCode.InternalServerError)
                        }

                        else -> {
                            error("Unexpected request: $urlStr")
                        }
                    }
                }

            SpotifyUrlPlaylistImporter.resetClientForTests(HttpClient(engine))

            val result = SpotifyUrlPlaylistImporter.loadPlaylist("https://open.spotify.com/playlist/test123456789012345678")
            assertEquals(ImportErrorKey.IncompleteSource, (result.exceptionOrNull() as ImportException).errorKey)
        }

    @Test
    fun normalizesNonBreakingSpacesInArtistNames() =
        runBlocking {
            val mockHtml =
                """
                <!DOCTYPE html>
                <html><body>
                <script id="__NEXT_DATA__" type="application/json">
                {
                    "props": {
                        "pageProps": {
                            "state": {
                                "data": {
                                    "entity": {
                                        "title": "NBSP Test",
                                        "trackList": [
                                            {"title": "Song One", "subtitle": "Artist\u00A0A,\u00A0Artist\u00A0B"}
                                        ]
                                    }
                                }
                            }
                        }
                    }
                }
                </script>
                </body></html>
                """.trimIndent()

            val engine =
                MockEngine { request ->
                    respondHtml(mockHtml)
                }

            SpotifyUrlPlaylistImporter.resetClientForTests(HttpClient(engine))

            val result = SpotifyUrlPlaylistImporter.loadPlaylist("https://open.spotify.com/playlist/test123456789012345678")
            assertTrue(result.isSuccess)
            val playlist = result.getOrThrow()
            assertEquals("Artist A, Artist B", playlist.tracks[0].artist)
        }

    private fun MockRequestHandleScope.respondHtml(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "text/html"),
    )

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun createEmbedHtml(
        trackCount: Int,
        hasAccessToken: Boolean,
    ): String {
        val tracksJson =
            (1..trackCount).joinToString(",") { i ->
                """{"title": "Track $i", "subtitle": "Artist $i"}"""
            }

        val sessionPart =
            if (hasAccessToken) {
                """"settings": {"session": {"accessToken": "mock-access-token", "accessTokenExpirationTimestampMs": 9999999999999}},"""
            } else {
                ""
            }

        return """
            <!DOCTYPE html>
            <html><head></head><body>
            <script id="__NEXT_DATA__" type="application/json">
            {
                "props": {
                    "pageProps": {
                        "state": {
                            $sessionPart
                            "data": {
                                "entity": {
                                    "type": "playlist",
                                    "name": "Test Playlist",
                                    "trackList": [$tracksJson]
                                }
                            }
                        }
                    }
                }
            }
            </script>
            </body></html>
            """.trimIndent()
    }
}
