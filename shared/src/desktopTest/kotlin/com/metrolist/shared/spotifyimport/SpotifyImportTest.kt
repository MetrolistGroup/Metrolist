package com.metrolist.shared.spotifyimport

import com.metrolist.shared.playlistimport.ImportErrorKey
import com.metrolist.shared.playlistimport.ImportException
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import meow.nyx.spotlib.LogLevel
import meow.nyx.spotlib.PlaylistMeta
import meow.nyx.spotlib.SpotifyClient
import meow.nyx.spotlib.SpotifyConfig
import meow.nyx.spotlib.SpotifySession
import meow.nyx.spotlib.SpotifySessionExpiredException
import meow.nyx.spotlib.TrackItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

class SpotifyImportTest {
    @Test
    fun `only one spotify import can run at once`() {
        assertTrue(SpotifyImportOperationGate.tryStart())
        try {
            assertFalse(SpotifyImportOperationGate.tryStart())
        } finally {
            SpotifyImportOperationGate.finish()
        }
        assertTrue(SpotifyImportOperationGate.tryStart())
        SpotifyImportOperationGate.finish()
    }

    @Test
    fun `session cookie parser returns only sp dc`() {
        assertEquals(
            "spotify-session-value",
            spotifySessionCookie("sp_t=device; sp_dc=spotify-session-value; other=value=with-equals"),
        )
        assertNull(spotifySessionCookie("sp_t=device; other=value"))
        assertNull(spotifySessionCookie("sp_dc="))
        assertNull(spotifySessionCookie(null))
    }

    @Test
    fun `spotify tracks become metadata import tracks even when unavailable on spotify`() {
        val tracks =
            SpotifyImportManager.tracksForImport(
                listOf(
                    TrackItem(
                        uri = "spotify:track:one",
                        name = "  Track title  ",
                        artists = listOf("Artist one", "Artist two"),
                        album = "Source album",
                        durationMs = 180_500,
                    ),
                    TrackItem(uri = "spotify:track:blank", name = "  ", artists = listOf("Artist")),
                    TrackItem(uri = "spotify:episode:one", name = "Episode", type = "Episode"),
                    TrackItem(uri = "spotify:local:one", name = "Local song", isLocal = true),
                    TrackItem(uri = "spotify:track:blocked", name = "Blocked song", isPlayable = false),
                ),
            )

        assertEquals(
            listOf(
                SpotifyImportManager.ImportTrack("Track title", "Artist one, Artist two", "Source album", 180),
                SpotifyImportManager.ImportTrack("Blocked song", "", "", null),
            ),
            tracks,
        )
    }

    @Test
    fun `spotify session is saved restored refreshed and cleared only on logout or expiry`() =
        runBlocking {
            var stored = ""
            val tokenCount = AtomicInteger()
            val manager =
                SpotifyImportManager(
                    clientFactory = { spotifyClient(tokenCount) },
                    loadStoredSession = { stored },
                    saveStoredSession = { stored = it },
                )

            manager.authenticate("cookie-one")
            assertTrue(manager.isAuthenticated)
            assertEquals("cookie-one", Json.decodeFromString<SpotifySession>(stored).cookies["sp_dc"])
            assertEquals("access-1", Json.decodeFromString<SpotifySession>(stored).accessToken)

            manager.close()
            val restored =
                SpotifyImportManager(
                    clientFactory = { spotifyClient(tokenCount) },
                    loadStoredSession = { stored },
                    saveStoredSession = { stored = it },
                )
            assertTrue(restored.restorePersistedSession())
            assertTrue(restored.isAuthenticated)
            assertEquals("cookie-one", Json.decodeFromString<SpotifySession>(stored).cookies["sp_dc"])

            restored.logout()
            assertFalse(restored.isAuthenticated)
            assertEquals("", stored)

            stored = SpotifyImportManager.sessionJsonForTest(SpotifySession(cookies = mapOf("sp_dc" to "cookie-two")))
            val expired =
                SpotifyImportManager(
                    clientFactory = { spotifyClient(tokenCount, tokenStatus = HttpStatusCode.Unauthorized) },
                    loadStoredSession = { stored },
                    saveStoredSession = { stored = it },
                )
            assertFalse(expired.restorePersistedSession())
            assertEquals("", stored)

            stored =
                SpotifyImportManager.sessionJsonForTest(
                    SpotifySession(
                        cookies = mapOf("sp_dc" to "cookie-three"),
                        accessToken = "old-access",
                        accessTokenExpiresAtMs = 0,
                        clientToken = "client-token",
                        clientTokenFetchedAtMs = System.currentTimeMillis(),
                    ),
                )
            val refreshed =
                SpotifyImportManager(
                    clientFactory = { spotifyClient(tokenCount) },
                    loadStoredSession = { stored },
                    saveStoredSession = { stored = it },
                )
            assertTrue(refreshed.restorePersistedSession())
            assertEquals("access-3", Json.decodeFromString<SpotifySession>(stored).accessToken)
        }

    @Test
    fun `batch import retains successes and reports individual failures`() =
        runBlocking {
            val errors = mutableListOf<String>()
            val manager =
                SpotifyImportManager({
                    spotifyClient(playlistResponse = { request ->
                        val uri =
                            Json
                                .parseToJsonElement(
                                    (request.body as TextContent).text,
                                ).jsonObject
                                .getValue("variables")
                                .jsonObject
                                .getValue("uri")
                                .jsonPrimitive.content
                        if (uri == "failed") respond("{}", HttpStatusCode.NotFound) else respond(playlistTracks(1))
                    })
                })
            try {
                manager.authenticate("cookie")
                val result =
                    manager.loadPlaylistsForImport(listOf(PlaylistMeta("ok", "Good"), PlaylistMeta("failed", "Failed")), onError = {
                        playlist,
                        _,
                        ->
                        errors +=
                            playlist.name
                    })
                assertEquals(listOf("ok"), result.playlists.map { it.uri })
                assertEquals(1, result.failedCount)
                assertEquals(listOf("Failed"), errors)
                assertEquals(listOf("Failed"), result.failures.map { it.playlistName })
            } finally {
                manager.close()
            }
        }

    @Test
    fun `batch import stops on expired session and cancellation`() =
        runBlocking {
            for (cancel in listOf(false, true)) {
                PlaybackEventLog.clear()
                var requests = 0
                var stored = ""
                val manager =
                    SpotifyImportManager({
                        spotifyClient(playlistResponse = {
                            requests++
                            if (cancel) throw CancellationException("Cancelled import")
                            respond("{}", HttpStatusCode.Unauthorized)
                        })
                    }, saveStoredSession = { stored = it })
                try {
                    manager.authenticate("cookie")
                    val selected = listOf(PlaylistMeta("first", "First"), PlaylistMeta("second", "Second"))
                    if (cancel) {
                        assertFailsWith<CancellationException> { manager.loadPlaylistsForImport(selected) }
                        assertTrue(manager.isAuthenticated)
                    } else {
                        assertFailsWith<SpotifySessionExpiredException> { manager.loadPlaylistsForImport(selected) }
                        assertFalse(manager.isAuthenticated)
                        assertEquals("", stored)
                        assertEquals(1, PlaybackEventLog.events.value.size)
                    }
                    assertEquals(1, requests)
                } finally {
                    manager.close()
                }
            }
        }

    @Test
    fun `batch total permits the limit and rejects one more track`() =
        runBlocking {
            val limit = PlaylistImportManager.MAX_PLAYLIST_TRACKS
            val pages =
                mapOf(
                    "first" to playlistTracks(limit / 2),
                    "second" to playlistTracks(limit / 4),
                    "third" to playlistTracks(limit - limit / 2 - limit / 4),
                    "extra" to playlistTracks(1),
                    "oversized" to playlistTracks(limit + 1),
                )
            val manager =
                SpotifyImportManager({
                    spotifyClient(playlistResponse = { request ->
                        val uri =
                            Json
                                .parseToJsonElement(
                                    (request.body as TextContent).text,
                                ).jsonObject
                                .getValue("variables")
                                .jsonObject
                                .getValue("uri")
                                .jsonPrimitive.content
                        respond(pages.getValue(uri))
                    })
                })
            try {
                manager.authenticate("cookie")
                val selected = listOf("first", "second", "third").map { PlaylistMeta(it, it) }
                val result = manager.loadPlaylistsForImport(selected)
                assertEquals(limit, result.playlists.sumOf { it.tracks.size })
                val error =
                    assertFailsWith<ImportException> { manager.loadPlaylistsForImport(selected + PlaylistMeta("extra", "Extra")) }
                assertEquals(ImportErrorKey.TooManyTracks, error.errorKey)
                val oversized = manager.loadPlaylistsForImport(listOf(PlaylistMeta("oversized", "Oversized"))).playlists.single()
                assertEquals(limit, oversized.tracks.size)
                assertTrue(oversized.reachedTrackLimit)
            } finally {
                manager.close()
            }
        }

    private fun playlistTracks(count: Int): String =
        """{"data":{"playlistV2":{"content":{"totalCount":$count,"items":[${List(
            count,
        ) { """{"name":"Track"}""" }.joinToString(",")}]}}}}"""

    private fun spotifyClient(
        tokenCount: AtomicInteger = AtomicInteger(),
        tokenStatus: HttpStatusCode = HttpStatusCode.OK,
        playlistResponse: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { error("Unexpected playlist request") },
    ): SpotifyClient =
        SpotifyClient.create(
            SpotifyConfig(
                engine =
                    MockEngine { request ->
                        when (request.url.host) {
                            "open.spotify.com" -> {
                                respond(
                                    """{"accessToken":"access-${tokenCount.incrementAndGet()}","accessTokenExpirationTimestampMs":9223372036854775807,"isAnonymous":false}""",
                                    tokenStatus,
                                    headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }

                            "clienttoken.spotify.com" -> {
                                respond(
                                    """{"granted_token":{"token":"client-token"}}""",
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }

                            "api-partner.spotify.com" -> {
                                playlistResponse(request)
                            }

                            else -> {
                                error("Unexpected host ${request.url.host}")
                            }
                        }
                    },
                logLevel = LogLevel.NONE,
            ),
        )
}
