package com.metrolist.shared.api

import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.api.lastfm.artistName
import com.metrolist.shared.api.listenbrainz.ListenBrainz
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScrobblingApiTest {
    @BeforeTest
    fun initializeLastFm() {
        LastFM.initialize("api-key", "secret")
        LastFM.sessionKey = "session-key"
    }

    @Test
    fun lastFmTreatsHttpAndApiErrorsAsFailures() =
        runBlocking {
            val httpFailure = client("server error", HttpStatusCode.InternalServerError)
            val apiFailure = client("""{"error":9,"message":"Invalid session key"}""")

            assertTrue(LastFM.scrobble(httpFailure, "Artist", "Track", 1L).isFailure)
            assertTrue(LastFM.scrobble(apiFailure, "Artist", "Track", 1L).isFailure)
            assertEquals(
                ScrobbleFailureDisposition.RETRY,
                LastFM.scrobble(httpFailure, "Artist", "Track", 1L).exceptionOrNullAsApi().disposition,
            )
            assertEquals(
                ScrobbleFailureDisposition.AUTHENTICATION,
                LastFM.scrobble(apiFailure, "Artist", "Track", 1L).exceptionOrNullAsApi().disposition,
            )
        }

    @Test
    fun lastFmRejectsIgnoredScrobble() =
        runBlocking {
            val client = client("""{"scrobbles":{"@attr":{"accepted":"0","ignored":"1"}}}""")

            assertTrue(LastFM.scrobble(client, "Artist", "Track", 1L).isFailure)
        }

    @Test
    fun listenBrainzValidatesTokenAndSubmissionFailures() =
        runBlocking {
            val validClient =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("Token token", request.headers[HttpHeaders.Authorization])
                        respond(
                            """{"code":200,"valid":true,"user_name":"listener"}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )
            val user = ListenBrainz.validateToken(validClient, "token").getOrThrow()
            assertEquals("listener", user.username)

            val failed =
                ListenBrainz.scrobble(
                    client("""{"error":"bad token"}""", HttpStatusCode.Unauthorized),
                    "bad",
                    "Artist",
                    "Track",
                    1L,
                )
            assertTrue(failed.isFailure)
        }

    @Test
    fun transientStatusesAndRetryAfterArePreserved() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            """{"error":"timeout"}""",
                            HttpStatusCode.RequestTimeout,
                            headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("7")),
                        )
                    },
                )

            val error = ListenBrainz.scrobble(client, "token", "Artist", "Track", 1L).exceptionOrNullAsApi()
            assertEquals(ScrobbleFailureDisposition.RETRY, error.disposition)
            assertEquals(7_000L, error.retryAfterMs)
        }

    @Test
    fun cancellationIsNotConvertedToFailure() =
        runBlocking {
            val client = HttpClient(MockEngine { throw CancellationException("cancelled") })
            assertFailsWith<CancellationException> {
                LastFM.scrobble(client, "Artist", "Track", 1L)
            }
            assertFailsWith<CancellationException> {
                ListenBrainz.scrobble(client, "token", "Artist", "Track", 1L)
            }
            Unit
        }

    @Test
    fun listenBrainzRequiresServerUsername() =
        runBlocking {
            assertTrue(ListenBrainz.validateToken(client("""{"valid":true}"""), "token").isFailure)
        }

    @Test
    fun getFriends_parsesFriendList() =
        runBlocking {
            val response =
                """
                {
                  "friends": {
                    "@attr": {"user": "me", "total": "2", "page": "1", "perPage": "50", "totalPages": "1"},
                    "user": [
                      {
                        "name": "alice",
                        "realname": "Alice",
                        "url": "https://www.last.fm/user/alice",
                        "image": [
                          {"size": "small", "#text": "https://lastfm.freetls.fastly.net/i/u/34s/abc.png"},
                          {"size": "large", "#text": "https://lastfm.freetls.fastly.net/i/u/174s/abc.png"}
                        ]
                      },
                      {
                        "name": "bob",
                        "url": "https://www.last.fm/user/bob",
                        "image": []
                      }
                    ]
                  }
                }
                """.trimIndent()
            val friends = LastFM.getFriends(client(response), "me").getOrThrow()

            assertEquals(2, friends.size)
            val alice = friends.first()
            assertEquals("alice", alice.name)
            assertEquals("Alice", alice.realname)
            assertEquals("https://www.last.fm/user/alice", alice.url)
            assertTrue(alice.image.isNotEmpty())
            assertEquals("bob", friends[1].name)
        }

    @Test
    fun getRecentTrack_parsesNowPlayingTrack() =
        runBlocking {
            val response =
                """
                {
                  "recenttracks": {
                    "track": [
                      {
                        "artist": {"mbid": "x", "#text": "Radiohead"},
                        "name": "Karma Police",
                        "url": "https://www.last.fm/music/Radiohead/_/Karma+Police",
                        "@attr": {"nowplaying": "true"}
                      }
                    ]
                  }
                }
                """.trimIndent()
            val track = LastFM.getRecentTrack(client(response), "me").getOrThrow()

            assertEquals("Karma Police", track?.name)
            assertEquals("Radiohead", track?.artistName())
            assertEquals("true", track?.attr?.nowplaying)
            assertEquals(null, track?.date)
        }

    @Test
    fun getRecentTrack_parsesLastPlayedTrack() =
        runBlocking {
            val response =
                """
                {
                  "recenttracks": {
                    "track": [
                      {
                        "artist": "Pixies",
                        "name": "Where Is My Mind?",
                        "url": "https://www.last.fm/music/Pixies/_/Where+Is+My+Mind%3F",
                        "date": {"uts": "1700000000", "#text": "14 Nov 2023, 23:00"}
                      }
                    ]
                  }
                }
                """.trimIndent()
            val track = LastFM.getRecentTrack(client(response), "me").getOrThrow()

            assertEquals("Where Is My Mind?", track?.name)
            assertEquals("Pixies", track?.artistName())
            assertEquals("1700000000", track?.date?.uts)
            assertEquals(null, track?.attr)
        }

    @Test
    fun getRecentTrack_returnsNullWhenNoTracks() =
        runBlocking {
            val response = """{"recenttracks": {}}"""
            val track = LastFM.getRecentTrack(client(response), "me").getOrThrow()

            assertEquals(null, track)
        }

    @Test
    fun getRecentTrack_returnsFailureOnApiError() =
        runBlocking {
            val response = """{"error": 6, "message": "Invalid parameters"}"""
            assertTrue(LastFM.getRecentTrack(client(response), "me").isFailure)
        }

    @Test
    fun getFriends_handlesSingleFriendObject() =
        runBlocking {
            val response =
                """
                {
                  "friends": {
                    "@attr": {"user": "me", "total": "1", "page": "1", "perPage": "50", "totalPages": "1"},
                    "user": {
                      "name": "solo",
                      "url": "https://www.last.fm/user/solo"
                    }
                  }
                }
                """.trimIndent()
            val friends = LastFM.getFriends(client(response), "me").getOrThrow()

            assertEquals(1, friends.size)
            assertEquals("solo", friends.first().name)
        }

    @Test
    fun getFriends_returnsEmptyWhenNoFriends() =
        runBlocking {
            val response =
                """{"friends": {"@attr": {"user": "me", "total": "0", "page": "1", "perPage": "50", "totalPages": "0"}}}"""
            val friends = LastFM.getFriends(client(response), "me").getOrThrow()

            assertTrue(friends.isEmpty())
        }

    @Test
    fun getFriends_returnsFailureOnApiError() =
        runBlocking {
            val response = """{"error": 6, "message": "Invalid parameters"}"""
            assertTrue(LastFM.getFriends(client(response), "me").isFailure)
        }

    @Test
    fun getFriends_skipsNonObjectFriendEntries() =
        runBlocking {
            val response =
                """
                {
                  "friends": {
                    "user": [
                      {"name": "good", "url": "https://www.last.fm/user/good"},
                      "not-an-object"
                    ]
                  }
                }
                """.trimIndent()
            val friends = LastFM.getFriends(client(response), "me").getOrThrow()

            assertEquals(1, friends.size)
            assertEquals("good", friends.first().name)
        }

    private fun client(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = HttpClient(
        MockEngine {
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )
}

private fun Result<*>.exceptionOrNullAsApi(): ScrobbleApiException =
    exceptionOrNull() as? ScrobbleApiException ?: error("Expected ScrobbleApiException")
