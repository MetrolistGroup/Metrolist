package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
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
import kotlin.test.assertTrue

class YouTubeVoteAuthenticationTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.shared.pendingSongVotesJson = "{}"
        Settings.shared.dislikedSongIds = ""
        SongLikeState.refreshFromSettings()
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun playlistAndNextVotesReconcileOnlyAuthenticatedResponses() =
        runBlocking {
            SongLikeState.refreshFromSettings()
            var status = "DISLIKE"
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            """{"contents":{"likeButtonRenderer":{"target":{"videoId":"vote-song"},"likeStatus":"$status"}}}""",
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            client.use {
                val innerTube = InnerTube(it)
                val repository = YouTubeRepository(innerTube)
                repository.getPlaylistPageData("PLtest")
                assertFalse(SongLikeState.isDisliked("vote-song"))
                innerTube.cookie = "SAPISID=test"
                repository.getPlaylistPageData("PLtest")
                assertTrue(SongLikeState.isDisliked("vote-song"))
                status = "INDIFFERENT"
                innerTube.useLoginForBrowse = false
                repository.getMusicVideoCounterpart("vote-song")
                assertTrue(SongLikeState.isDisliked("vote-song"))
                innerTube.useLoginForBrowse = true
                repository.getMusicVideoCounterpart("vote-song")
                assertFalse(SongLikeState.isDisliked("vote-song"))
            }
        }

    @Test
    fun successfulLikeSurvivesStaleMembershipUntilAdditionIsConfirmed() =
        runBlocking {
            SongLikeState.refreshFromSettings()
            val song = SongItem("cached-song", "Cached song", null, emptyList(), null)
            assertTrue(SongLikeState.toggleLike(song))
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond("{}", HttpStatusCode.OK, JSON_HEADERS)
                },
            ) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }.use { client ->
                val repository =
                    YouTubeRepository(
                        InnerTube(client).apply { cookie = "SAPISID=test" },
                        databaseProvider = { error("Song votes must not require a database") },
                    )
                SongLikeState.syncPendingVotes(repository)
                assertEquals(1, requests)
                assertEquals(
                    mapOf(song.id to SongVote.LIKE),
                    Json.decodeFromString<Map<String, SongVote>>(Settings.shared.pendingSongVotesJson),
                )
                SongLikeState.reconcileRemoteVotes(mapOf(song.id to SongVote.INDIFFERENT), SongLikeState.voteObservationRevision())
                SongLikeState.awaitPersistence()
                SongLikeState.refreshFromSettings()
                repeat(2) {
                    SongLikeState.replaceLikesFromRemote(emptyList())
                    assertTrue(SongLikeState.isLiked(song.id))
                    assertEquals(listOf(song), SongLikeState.likedSongsFlow.value)
                }
                SongLikeState.replaceLikesFromRemote(listOf(song))
                assertEquals("{}", Settings.shared.pendingSongVotesJson)
                SongLikeState.syncPendingVotes(repository)
                assertEquals(1, requests)
                SongLikeState.replaceLikesFromRemote(emptyList())
                assertFalse(SongLikeState.isLiked(song.id))
                SongLikeState.awaitPersistence()
            }
        }

    @Test
    fun rowLikeDoesNotConfirmLikedPlaylistMembership() =
        runBlocking {
            SongLikeState.refreshFromSettings()
            val song = SongItem("downloaded-song", "Downloaded song", null, emptyList(), null)
            SongLikeState.toggleLike(song)
            SongLikeState.reconcileRemoteVotes(mapOf(song.id to SongVote.LIKE), SongLikeState.voteObservationRevision())
            SongLikeState.replaceLikesFromRemote(emptyList())
            assertTrue(SongLikeState.isLiked(song.id))
            SongLikeState.replaceLikesFromRemote(listOf(song))
            assertEquals("{}", Settings.shared.pendingSongVotesJson)
            SongLikeState.awaitPersistence()
        }

    @Test
    fun signedOutSongTogglePersistsWithoutSendingVotes() =
        runBlocking {
            SongLikeState.refreshFromSettings()
            val song = SongItem("cached-song", "Cached song", null, emptyList(), null)
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond("{}", HttpStatusCode.OK, JSON_HEADERS)
                },
            ).use { client ->
                val repository = YouTubeRepository(InnerTube(client))
                assertTrue(SongLikeState.toggleLike(song))
                SongLikeState.syncPendingVotes(repository)
                SongLikeState.awaitPersistence()
                SongLikeState.refreshFromSettings()
                assertTrue(SongLikeState.isLiked(song.id))
                assertEquals(listOf(song), SongLikeState.likedSongsFlow.value)
                assertFalse(SongLikeState.toggleLike(song))
                SongLikeState.syncPendingVotes(repository)
                SongLikeState.awaitPersistence()
                SongLikeState.refreshFromSettings()
                assertFalse(SongLikeState.isLiked(song.id))
                assertEquals(0, requests)
            }
        }

    @Test
    fun successfulUnlikeSurvivesStaleMembershipUntilRemovalIsConfirmed() =
        runBlocking {
            SongLikeState.refreshFromSettings()
            val song = SongItem("unliked-song", "Song", null, emptyList(), null)
            SongLikeState.replaceLikesFromRemote(listOf(song))
            SongLikeState.removeLike(song.id)
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond("{}", HttpStatusCode.OK, JSON_HEADERS)
                },
            ) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }.use { client ->
                val repository = YouTubeRepository(InnerTube(client).apply { cookie = "SAPISID=test" })
                SongLikeState.syncPendingVotes(repository)
                assertEquals(1, requests)
                assertEquals(
                    mapOf(song.id to SongVote.INDIFFERENT),
                    Json.decodeFromString<Map<String, SongVote>>(Settings.shared.pendingSongVotesJson),
                )
                SongLikeState.reconcileRemoteVotes(mapOf(song.id to SongVote.INDIFFERENT), SongLikeState.voteObservationRevision())
                SongLikeState.awaitPersistence()
                SongLikeState.refreshFromSettings()
                repeat(2) {
                    SongLikeState.replaceLikesFromRemote(listOf(song))
                    assertFalse(SongLikeState.isLiked(song.id))
                }
                SongLikeState.replaceLikesFromRemote(emptyList())
                assertEquals("{}", Settings.shared.pendingSongVotesJson)
                SongLikeState.syncPendingVotes(repository)
                assertEquals(1, requests)
                SongLikeState.replaceLikesFromRemote(listOf(song))
                assertTrue(SongLikeState.isLiked(song.id))
                SongLikeState.awaitPersistence()
            }
        }

    @Test
    fun loggedOutVotesStayLocal() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond("{}", HttpStatusCode.OK, JSON_HEADERS)
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val repository = YouTubeRepository(InnerTube(client))

            assertFalse(repository.likeVideo("liked"))
            assertFalse(repository.dislikeVideo("disliked"))
            assertFalse(repository.unlikeVideo("unliked"))

            assertEquals(0, requests)
            client.close()
        }

    @Test
    fun authenticatedBatchLikesUseTheSameSessionSnapshot() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond("{}", HttpStatusCode.OK, JSON_HEADERS)
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            Settings.shared.innerTubeCookie = "SAPISID=test"
            val innerTube = InnerTube(client).apply { cookie = Settings.shared.innerTubeCookie }

            YouTubeRepository(innerTube).likeVideos(listOf("one", "two"))

            assertEquals(2, requests)
            client.close()
        }

    @Test
    fun batchReportsOnlySettledSuccessfulLikes() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond("{}", if (requests == 2) HttpStatusCode.InternalServerError else HttpStatusCode.OK, JSON_HEADERS)
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            Settings.shared.innerTubeCookie = "SAPISID=test"
            val innerTube = InnerTube(client).apply { cookie = Settings.shared.innerTubeCookie }
            val settled = mutableListOf<String>()

            val result = YouTubeRepository(innerTube).likeVideos(listOf("one", "two", "three")) { settled += it }

            assertEquals(listOf("one", "three"), settled)
            assertEquals(listOf("one", "three"), result.succeededIds)
            client.close()
        }

    @Test
    fun loggedOutBatchDoesNotIssueRequests() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond("{}", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )

            val result = YouTubeRepository(InnerTube(client)).likeVideos(listOf("one", "two"))

            assertFalse(result.authenticated)
            assertEquals(0, requests)
            client.close()
        }

    @Test
    fun rejectedVoteClearsInMemorySessionOnly() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond("{}", HttpStatusCode.Unauthorized, JSON_HEADERS)
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            Settings.shared.innerTubeCookie = "SAPISID=test"
            val innerTube = InnerTube(client).apply { cookie = Settings.shared.innerTubeCookie }

            YouTubeRepository(innerTube).likeVideo("liked")

            assertEquals(1, requests)
            assertFalse(innerTube.hasSapCookieAuth())
            assertEquals("SAPISID=test", Settings.shared.innerTubeCookie)
            client.close()
        }

    @Test
    fun batchKeepsSettledProgressWhenTheSessionChanges() =
        runBlocking {
            var requests = 0
            val secondStarted = CompletableDeferred<Unit>()
            val respondToSecond = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        if (requests == 2) {
                            secondStarted.complete(Unit)
                            respondToSecond.await()
                        }
                        respond("{}", HttpStatusCode.OK, JSON_HEADERS)
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            Settings.shared.innerTubeCookie = "SAPISID=old"
            val innerTube = InnerTube(client).apply { cookie = Settings.shared.innerTubeCookie }
            val settled = mutableListOf<String>()
            val vote = async { YouTubeRepository(innerTube).likeVideos(listOf("one", "two")) { settled += it } }

            secondStarted.await()
            innerTube.cookie = "SAPISID=new"
            respondToSecond.complete(Unit)
            assertFailsWith<CancellationException> { vote.await() }

            assertEquals(listOf("one"), settled)
            assertEquals("SAPISID=new", innerTube.cookie)
            client.close()
        }

    @Test
    fun delayedRejectionDoesNotClearNewSession() =
        runBlocking {
            val requestStarted = CompletableDeferred<Unit>()
            val respondToRequest = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine {
                        requestStarted.complete(Unit)
                        respondToRequest.await()
                        respond("{}", HttpStatusCode.Unauthorized, JSON_HEADERS)
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            Settings.shared.innerTubeCookie = "SAPISID=old"
            val innerTube = InnerTube(client).apply { cookie = Settings.shared.innerTubeCookie }
            val vote = async { YouTubeRepository(innerTube).likeVideo("liked") }

            requestStarted.await()
            Settings.shared.innerTubeCookie = "SAPISID=new"
            innerTube.cookie = Settings.shared.innerTubeCookie
            respondToRequest.complete(Unit)
            assertFailsWith<CancellationException> { vote.await() }

            assertEquals("SAPISID=new", innerTube.cookie)
            assertEquals("SAPISID=new", Settings.shared.innerTubeCookie)
            client.close()
        }

    private companion object {
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
