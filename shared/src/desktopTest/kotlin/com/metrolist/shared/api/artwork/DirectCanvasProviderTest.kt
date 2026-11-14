package com.metrolist.shared.api.artwork

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import meow.nyx.spotlib.SpotifySession
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectCanvasProviderTest {
    private val now = 1_800_000_000_000L
    private val jwt =
        "eyJhbGciOiJIUzI1NiJ9." +
            Base64.getUrlEncoder().withoutPadding().encodeToString("""{"iss":"AMPWebPlay","exp":4000000000}""".toByteArray()) +
            ".abcdefghijklmnop123456"

    @Test
    fun localSourcesPreferSpotifyAndCacheResults() =
        runBlocking {
            var searches = 0
            val session =
                Json.encodeToString(
                    SpotifySession(mapOf("sp_dc" to "own-cookie"), "access", clientToken = "client"),
                )
            val client =
                HttpClient(
                    MockEngine { request ->
                        val url = request.url.toString()
                        assertFalse(url.contains("lyprio"))
                        val body =
                            when {
                                url.contains("api-partner.spotify.com") &&
                                    (request.body as TextContent).text.contains("searchTopResultsList") -> {
                                    searches++
                                    assertEquals("Bearer access", request.headers[HttpHeaders.Authorization])
                                    """{"data":{"searchV2":{"topResultsV2":{"itemsV2":[{"item":{"data":{"__typename":"Track","id":"song-id","name":"Song","artists":{"items":[{"profile":{"name":"Artist"}}]},"albumOfTrack":{"name":"Album"},"duration":{"totalMilliseconds":200000}}}}]}}}}"""
                                }

                                url.contains("api-partner.spotify.com") -> {
                                    """{"data":{"trackUnion":{"canvas":{"url":"https://canvaz.scdn.co/canvas.mp4"}}}}"""
                                }

                                url == "https://music.apple.com/de/browse" -> {
                                    " ".repeat(3 * 1024 * 1024) + """<script type="module" crossorigin src="/assets/index.js"></script>"""
                                }

                                url.endsWith("/assets/index.js") -> {
                                    jwt
                                }

                                url.contains("/search") -> {
                                    """{"results":{"songs":{"data":[{"id":"123","attributes":{"name":"Song","artistName":"Artist","albumName":"Album","durationInMillis":200000}}]}}}"""
                                }

                                url.contains("/songs/123") -> {
                                    """{"data":[{"relationships":{"albums":{"data":[{"attributes":{"editorialVideo":{"motionSquareVideo1x1":{"video":"https://mvod.itunes.apple.com/art.m3u8"}}}}]}}}]}"""
                                }

                                else -> {
                                    error("Unexpected URL $url")
                                }
                            }
                        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                )
            client.use {
                val provider = DirectCanvasProvider(client, { session }, { "DE" }, { "de-DE" }, now = { now })
                val result = provider.getCanvases("Song", "Artist", 200_000, "Album")
                assertEquals("https://canvaz.scdn.co/canvas.mp4", result.spotify)
                assertEquals("https://mvod.itunes.apple.com/art.m3u8", result.appleMusic)
                assertEquals(result, provider.getCanvases("Song", "Artist", 200_000, "Album"))
                assertEquals(1, searches)
            }
        }

    @Test
    fun spotifyOnlyLookupLeavesAppleArtworkToTheResolutionAwareService() =
        runBlocking {
            var appleRequests = 0
            val session =
                Json.encodeToString(
                    SpotifySession(mapOf("sp_dc" to "own-cookie"), "access", clientToken = "client"),
                )
            val client =
                HttpClient(
                    MockEngine { request ->
                        when {
                            request.url.host == "api-partner.spotify.com" &&
                                (request.body as TextContent).text.contains("searchTopResultsList") -> {
                                respond(
                                    """{"data":{"searchV2":{"topResultsV2":{"itemsV2":[{"item":{"data":{"__typename":"Track","id":"song-id","name":"Song","artists":{"items":[{"profile":{"name":"Artist"}}]},"albumOfTrack":{"name":"Album"},"duration":{"totalMilliseconds":200000}}}}]}}}}""",
                                    HttpStatusCode.OK,
                                    headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }

                            request.url.host == "api-partner.spotify.com" -> {
                                respond(
                                    """{"data":{"trackUnion":{"canvas":{"url":"https://canvaz.scdn.co/canvas.mp4"}}}}""",
                                    HttpStatusCode.OK,
                                    headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }

                            request.url.host.endsWith("apple.com") -> {
                                appleRequests++
                                respond("", HttpStatusCode.NotFound)
                            }

                            else -> {
                                error("Unexpected URL ${request.url}")
                            }
                        }
                    },
                )
            client.use {
                val provider = DirectCanvasProvider(client, { session }, now = { now })
                assertEquals("https://canvaz.scdn.co/canvas.mp4", provider.getSpotifyCanvas("Song", "Artist", 200_000, "Album"))
                assertEquals(0, appleRequests)
            }
        }

    @Test
    fun anonymousSpotifyResolvesCoversAndReusesTokensWithoutSavingALogin() =
        runBlocking {
            var tokenRequests = 0
            var clientTokenRequests = 0
            var searches = 0
            HttpClient(
                MockEngine { request ->
                    assertNull(request.headers[HttpHeaders.Cookie])
                    when (request.url.host) {
                        "open.spotify.com" -> {
                            tokenRequests++
                            respond(
                                """{"accessToken":"anonymous-access","accessTokenExpirationTimestampMs":4000000000000,"isAnonymous":true}""",
                            )
                        }

                        "clienttoken.spotify.com" -> {
                            clientTokenRequests++
                            respond("""{"granted_token":{"token":"anonymous-client"}}""")
                        }

                        "api-partner.spotify.com" -> {
                            assertEquals("Bearer anonymous-access", request.headers[HttpHeaders.Authorization])
                            assertEquals("anonymous-client", request.headers["client-token"])
                            assertTrue((request.body as TextContent).text.contains("searchTopResultsList"))
                            searches++
                            respond(
                                """{"data":{"searchV2":{"topResultsV2":{"itemsV2":[{"item":{"data":{"__typename":"Track","id":"0000000000000000000000","name":"Song","artists":{"items":[{"profile":{"name":"Artist"}}]},"duration":{"totalMilliseconds":200000}}}}]}}}}""",
                            )
                        }

                        "spotify-canva.vercel.app" -> {
                            assertNull(request.headers[HttpHeaders.Authorization])
                            assertNull(request.headers["client-token"])
                            assertEquals("0000000000000000000000", request.url.parameters["trackId"])
                            respond(
                                """{"canvasesList":[{"trackUri":"spotify:track:0000000000000000000000","canvasUrl":"https://canvaz.scdn.co/canvas.mp4"}]}""",
                            )
                        }

                        else -> {
                            error("Unexpected host ${request.url.host}")
                        }
                    }
                },
            ).use { client ->
                val provider =
                    DirectCanvasProvider(
                        client,
                        { "" },
                        now = { now },
                        saveSession = { error("Anonymous tokens must not become a saved login") },
                    )
                assertEquals("https://canvaz.scdn.co/canvas.mp4", provider.getSpotifyCanvas("Song", "Artist", 200_000))
                assertEquals("https://canvaz.scdn.co/canvas.mp4", provider.getSpotifyCanvas("Song", "Artist", 200_000))
                assertEquals("https://canvaz.scdn.co/canvas.mp4", provider.getSpotifyCanvas("Song", "Artist", 200_001))
                assertEquals(1, tokenRequests)
                assertEquals(1, clientTokenRequests)
                assertEquals(2, searches)
            }
        }

    @Test
    fun publicCanvasValidatesTrackAndUrlWithoutForwardingSpotifyCredentials() =
        runBlocking {
            val session = Json.encodeToString(SpotifySession(mapOf("sp_t" to "device-cookie"), "access", Long.MAX_VALUE, "client"))
            val responses =
                listOf(
                    Pair(
                        """{"canvasesList":[{"trackUri":"spotify:track:1111111111111111111111","canvasUrl":"https://canvaz.scdn.co/canvas.mp4"}]}""",
                        HttpStatusCode.OK,
                    ),
                    Pair(
                        """{"canvasesList":[{"trackUri":"spotify:track:0000000000000000000000","canvasUrl":"https://canvaz.scdn.co.evil.example/canvas.mp4"}]}""",
                        HttpStatusCode.OK,
                    ),
                    "{" to HttpStatusCode.OK,
                    "x".repeat(256 * 1024 + 1) to HttpStatusCode.OK,
                    "" to HttpStatusCode.ServiceUnavailable,
                    "" to HttpStatusCode.Found,
                )
            for ((body, status) in responses) {
                var publicCalls = 0
                HttpClient(
                    MockEngine { request ->
                        when (request.url.host) {
                            "api-partner.spotify.com" -> {
                                respond(
                                    """{"data":{"searchV2":{"topResultsV2":{"itemsV2":[{"item":{"data":{"__typename":"Track","id":"0000000000000000000000","name":"Song","artists":{"items":[{"profile":{"name":"Artist"}}]}}}}]}}}}""",
                                )
                            }

                            "spotify-canva.vercel.app" -> {
                                publicCalls++
                                assertNull(request.headers[HttpHeaders.Authorization])
                                assertNull(request.headers[HttpHeaders.Cookie])
                                assertNull(request.headers["client-token"])
                                respond(body, status, headersOf(HttpHeaders.Location, "https://evil.example/"))
                            }

                            else -> {
                                error("Unexpected host ${request.url.host}")
                            }
                        }
                    },
                ).use { client ->
                    val provider =
                        DirectCanvasProvider(
                            client,
                            { session },
                            now = { now },
                            saveSession = { error("Do not save anonymous sessions") },
                        )
                    assertNull(provider.getSpotifyCanvas("Song", "Artist", null))
                    assertNull(provider.getSpotifyCanvas("Song", "Artist", null))
                    assertEquals(1, publicCalls)
                }
            }
        }

    @Test
    fun anonymousSpotifyFailureAndExpiredAppleTokenStillTryAppleWithOneRefresh() =
        runBlocking {
            var searches = 0
            var bundles = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        val url = request.url.toString()
                        assertFalse(url.contains("lyprio"))
                        if (request.url.host == "open.spotify.com") return@MockEngine respond("", HttpStatusCode.Unauthorized)
                        val status = if (url.contains("/search") && searches++ == 0) HttpStatusCode.Unauthorized else HttpStatusCode.OK
                        val body =
                            when {
                                url == "https://music.apple.com/jp/browse" -> {
                                    return@MockEngine respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/jp/new"))
                                }

                                url == "https://music.apple.com/jp/new" -> {
                                    " ".repeat(3 * 1024 * 1024) + """<script type="module" crossorigin src="/assets/index.js"></script>"""
                                }

                                url.endsWith(".js") -> {
                                    bundles++
                                    jwt
                                }

                                url.contains("/search") -> {
                                    """{"results":{"songs":{"data":[{"id":"123","attributes":{"name":"Song","artistName":"Artist"}}]}}}"""
                                }

                                else -> {
                                    """{"data":[{"relationships":{"albums":{"data":[{"attributes":{"editorialVideo":{"motionSquareVideo1x1":{"video":"https://evil.example/file"},"motionDetailSquare":{"video":"https://is1-ssl.mzstatic.com/art.m3u8"}}}}]}}}]}"""
                                }
                            }
                        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                )
            client.use {
                val result =
                    DirectCanvasProvider(
                        client,
                        { "" },
                        { "JP" },
                        { "ja-JP" },
                        now = { now },
                    ).getCanvases("Song", "Artist", null)
                assertNull(result.spotify)
                assertEquals("https://is1-ssl.mzstatic.com/art.m3u8", result.appleMusic)
                assertEquals(2, searches)
                assertEquals(2, bundles)
            }
        }

    @Test
    fun spotify401RefreshesOnlyOnceWithoutErasingImportLogin() =
        runBlocking {
            var searches = 0
            var tokenRefreshes = 0
            var session =
                Json.encodeToString(
                    SpotifySession(mapOf("sp_dc" to "own-cookie"), "stale", clientToken = "client"),
                )
            val client =
                HttpClient(
                    MockEngine { request ->
                        val url = request.url.toString()
                        when {
                            url.contains("api-partner.spotify.com") -> {
                                searches++
                                respond("", HttpStatusCode.Unauthorized)
                            }

                            url.contains("open.spotify.com/api/token") -> {
                                tokenRefreshes++
                                respond("""{"accessToken":"fresh","accessTokenExpirationTimestampMs":4000000000000,"isAnonymous":false}""")
                            }

                            url.contains("clienttoken.spotify.com") -> {
                                respond("""{"granted_token":{"token":"new-client"}}""")
                            }

                            url == "https://music.apple.com/jp/browse" -> {
                                respond("")
                            }

                            else -> {
                                error("Unexpected URL $url")
                            }
                        }
                    },
                )
            client.use {
                val provider =
                    DirectCanvasProvider(client, { session }, { "JP" }, { "ja-JP" }, now = { now }, saveSession = { session = it })
                assertEquals(DirectCanvasUrls(), provider.getCanvases("Song", "Artist", null))
                assertEquals(DirectCanvasUrls(), provider.getCanvases("Song", "Artist", null))
                assertEquals(2, searches)
                assertEquals(1, tokenRefreshes)
                val retained = Json.decodeFromString<SpotifySession>(session)
                assertEquals("own-cookie", retained.cookies["sp_dc"])
                assertEquals("fresh", retained.accessToken)
            }
        }

    @Test
    fun independentSourcesStartBeforeEitherFinishes() =
        runBlocking {
            val spotifyStarted = CompletableDeferred<Unit>()
            val appleStarted = CompletableDeferred<Unit>()
            val session =
                Json.encodeToString(
                    SpotifySession(mapOf("sp_dc" to "own-cookie"), "access", clientToken = "client"),
                )
            HttpClient(
                MockEngine { request ->
                    if (request.url.host == "api-partner.spotify.com") {
                        spotifyStarted.complete(Unit)
                        appleStarted.await()
                        respond("""{"data":{"searchV2":{"topResultsV2":{"itemsV2":[]}}}}""")
                    } else {
                        assertEquals("music.apple.com", request.url.host)
                        appleStarted.complete(Unit)
                        spotifyStarted.await()
                        respond("")
                    }
                },
            ).use { client ->
                val provider = DirectCanvasProvider(client, { session }, { "JP" }, { "ja-JP" }, now = { now })
                assertEquals(DirectCanvasUrls(), provider.getCanvases("Song", "Artist", null))
                assertTrue(spotifyStarted.isCompleted)
                assertTrue(appleStarted.isCompleted)
            }
        }

    @Test
    fun refreshedSpotifyTokensAreStoredForTheNextTrack() =
        runBlocking {
            var saved =
                Json.encodeToString(SpotifySession(mapOf("sp_dc" to "own-cookie"), "expired", 1, "client"))
            var refreshes = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        val url = request.url.toString()
                        when {
                            url.contains("open.spotify.com/api/token") -> {
                                refreshes++
                                respond(
                                    """{"accessToken":"new-access","isAnonymous":false,"accessTokenExpirationTimestampMs":4000000000000}""",
                                )
                            }

                            url.contains("api-partner.spotify.com") -> {
                                assertEquals("Bearer new-access", request.headers[HttpHeaders.Authorization])
                                respond("""{"data":{"searchV2":{"topResultsV2":{"itemsV2":[]}}}}""")
                            }

                            url == "https://music.apple.com/jp/browse" -> {
                                respond("")
                            }

                            else -> {
                                error("Unexpected URL $url")
                            }
                        }
                    },
                )
            client.use {
                val provider =
                    DirectCanvasProvider(client, { saved }, { "JP" }, { "ja-JP" }, now = { now }, saveSession = { saved = it })
                provider.getCanvases("One", "Artist", null)
                provider.getCanvases("Two", "Artist", null)
                assertEquals(1, refreshes)
                assertEquals("new-access", Json.decodeFromString<SpotifySession>(saved).accessToken)
            }
        }

    @Test
    fun rejectsAppleBootstrapRedirectToUntrustedHost() =
        runBlocking {
            var calls = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.host == "open.spotify.com") return@MockEngine respond("", HttpStatusCode.Unauthorized)
                        calls++
                        assertEquals("https://music.apple.com/jp/browse", request.url.toString())
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example/"))
                    },
                )
            client.use {
                val result =
                    DirectCanvasProvider(
                        client,
                        { "" },
                        { "JP" },
                        { "ja-JP" },
                        now = { now },
                    ).getCanvases("Song", "Artist", null)
                assertEquals(DirectCanvasUrls(), result)
                assertEquals(1, calls)
            }
        }

    @Test
    fun rejectsMismatchedTracksUntrustedUrlsAndCancelledWork() =
        runBlocking {
            assertFalse(DirectCanvasProvider.matches("Song", "Artist", "Album", 200_000, "Song", "Artist", "Album", 250_000))
            assertFalse(DirectCanvasProvider.matches("Song", "Artist", null, null, "Song", "Someone Else", null, null))
            assertFalse(DirectCanvasProvider.matches("Song", "DJ Snake", null, null, "Song", "DJ Khaled", null, null))
            assertTrue(DirectCanvasProvider.matches("Song", "Beyonce", null, null, "Song", "Beyoncé", null, null))
            assertFalse(DirectCanvasProvider.matches("Song", "Tyler, the Creator", null, null, "Song", "Tyler, the Composer", null, null))
            assertFalse(DirectCanvasProvider.matches("Song", "DJ Snake, Guest", null, null, "Song", "DJ Snake", null, null))
            assertTrue(
                DirectCanvasProvider.matches(
                    "Song",
                    "DJ Snake, Guest",
                    null,
                    null,
                    "Song",
                    "DJ Snake",
                    null,
                    null,
                    primaryArtist = "DJ Snake",
                    foundPrimaryArtist = "DJ Snake",
                ),
            )
            assertFalse(
                DirectCanvasProvider.matches(
                    "Song",
                    "Tyler, the Creator",
                    null,
                    null,
                    "Song",
                    "Tyler, the Composer",
                    null,
                    null,
                    primaryArtist = "Tyler, the Creator",
                    foundPrimaryArtist = "Tyler, the Composer",
                ),
            )
            assertFalse(DirectCanvasProvider.trustedMedia("https://canvaz.scdn.co.evil.example/file", false))
            assertFalse(DirectCanvasProvider.trustedMedia("http://mvod.itunes.apple.com/file", true))
            assertFalse(DirectCanvasProvider.trustedMedia("https://user:pass@mvod.itunes.apple.com/file", true))
            val started = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine {
                        started.complete(Unit)
                        awaitCancellation()
                    },
                )
            client.use {
                val provider = DirectCanvasProvider(client, { "" }, { "JP" }, { "ja-JP" }, now = { now })
                val job = async { provider.getCanvases("Song", "Artist", null) }
                started.await()
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
            }
        }
}
