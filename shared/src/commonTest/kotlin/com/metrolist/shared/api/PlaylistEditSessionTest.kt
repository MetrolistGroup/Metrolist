package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlaylistEditSessionTest {
    @Test
    fun removalsReorderAndRenameRequireAcknowledgement() =
        runBlocking {
            val edits: List<suspend (YouTubePlaylistEdits) -> Result<Unit>> =
                listOf(
                    { it.renamePlaylist("PL", "New") },
                    { it.movePlaylistSong("PL", "set", null) },
                    { it.removePlaylistSong("PL", "set", "song") },
                    { it.removePlaylistSongs("PL", listOf("set" to "song")) },
                )
            for (edit in edits) {
                for (status in listOf("STATUS_SUCCEEDED", "STATUS_FAILED", null)) {
                    val client =
                        HttpClient(
                            MockEngine {
                                respond(
                                    if (status == null) {
                                        "{}"
                                    } else {
                                        """{"status":"$status"}"""
                                    },
                                    HttpStatusCode.OK,
                                    headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            },
                        ) { install(ContentNegotiation) { json() } }
                    val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=original" }
                    try {
                        assertEquals(status == "STATUS_SUCCEEDED", edit(YouTubePlaylistEdits(tube, {}, { _, _ -> false })).isSuccess)
                    } finally {
                        tube.close()
                        client.close()
                    }
                }
            }
        }

    @Test
    fun queuedEditsRejectAnAccountSwitchBeforeSending() =
        runTest {
            val mutations: List<suspend (YouTubePlaylistEdits) -> Any> =
                listOf(
                    { it.setPlaylistSaved("PL", true) },
                    { it.renamePlaylist("PL", "Name") },
                    { it.setPlaylistDescription("PL", "Description") },
                    { it.setPlaylistThumbnail("PL", byteArrayOf(1)) },
                    { it.removePlaylistThumbnail("PL") },
                    { it.movePlaylistSong("PL", "set", null) },
                    { it.removePlaylistSong("PL", "set", "song") },
                    { it.removePlaylistSongs("PL", listOf("set" to "song")) },
                    { it.addToPlaylist("PL", "song") },
                    { it.addPlaylistToPlaylist("PL", "PL-source") },
                    { it.deletePlaylist("PL") },
                )
            for (mutation in mutations) {
                var calls = 0
                val client =
                    HttpClient(
                        MockEngine {
                            calls++
                            respond("{}")
                        },
                    ) { install(ContentNegotiation) { json() } }
                val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=original" }
                val edits = YouTubePlaylistEdits(tube, {}, { _, _ -> false })
                try {
                    val release = CompletableDeferred<Unit>()
                    val blocker = launch(start = CoroutineStart.UNDISPATCHED) { edits.runQueuedPlaylistEdit { release.await() } }
                    val queued = async(start = CoroutineStart.UNDISPATCHED) { mutation(edits) }
                    tube.cookie = "SAPISID=replacement"
                    release.complete(Unit)
                    blocker.join()
                    assertFailsWith<CancellationException> { queued.await() }
                    assertEquals(0, calls)
                } finally {
                    tube.close()
                    client.close()
                }
            }
        }

    @Test
    fun queuedEpisodeEditsRejectAnAccountSwitchBeforeLookupOrMutation() =
        runTest {
            for (saved in listOf(true, false)) {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var calls = 0
                val client =
                    HttpClient(
                        MockEngine {
                            calls++
                            entered.complete(Unit)
                            release.await()
                            respond("""{"status":"STATUS_SUCCEEDED"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    ) { install(ContentNegotiation) { json() } }
                val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=original" }
                try {
                    val repository = YouTubeRepository(tube)
                    val blocker = async { repository.renamePlaylist("PL", "Name") }
                    entered.await()
                    val queued = async(start = CoroutineStart.UNDISPATCHED) { repository.setEpisodeSavedWithToken("episode", saved) }
                    tube.cookie = "SAPISID=replacement"
                    release.complete(Unit)
                    assertFailsWith<CancellationException> { blocker.await() }
                    assertFailsWith<CancellationException> { queued.await() }
                    assertEquals(1, calls)
                } finally {
                    tube.close()
                    client.close()
                }
            }
        }

    @Test
    fun episodeResponseFromAnOldAccountIsRejectedBeforePublishingItsToken(): Unit =
        runBlocking {
            lateinit var tube: InnerTube
            val client =
                HttpClient(
                    MockEngine {
                        tube.cookie = "SAPISID=replacement"
                        respond(
                            """{"status":"STATUS_SUCCEEDED","playlistEditResults":[{"playlistEditVideoAddedResultData":{"setVideoId":"entry"}}]}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json() } }
            tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=original" }
            try {
                assertFailsWith<CancellationException> { YouTubeRepository(tube).setEpisodeSavedWithToken("episode", true) }
            } finally {
                tube.close()
                client.close()
            }
        }

    @Test
    fun wholePlaylistCopyUsesOneActionAndRequiresAcknowledgement() =
        runBlocking {
            for (status in listOf("STATUS_SUCCEEDED", "STATUS_FAILED")) {
                val bodies = mutableListOf<String>()
                val client =
                    HttpClient(
                        MockEngine { request ->
                            bodies += (request.body as TextContent).text
                            respond("""{"status":"$status"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    ) { install(ContentNegotiation) { json() } }
                val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=original" }
                try {
                    val result =
                        YouTubePlaylistEdits(tube, {}, { _, _ -> error("No per-song reads") })
                            .addPlaylistToPlaylist("VLPLdestination", "VLPLsource")
                    assertEquals(status == "STATUS_SUCCEEDED", result.isSuccess)
                    val body = Json.parseToJsonElement(bodies.single()).jsonObject
                    assertEquals("PLdestination", body["playlistId"]?.jsonPrimitive?.content)
                    val action = body["actions"]!!.jsonArray.single().jsonObject
                    assertEquals("ACTION_ADD_PLAYLIST", action["action"]?.jsonPrimitive?.content)
                    assertEquals("PLsource", action["addedFullListId"]?.jsonPrimitive?.content)
                } finally {
                    tube.close()
                    client.close()
                }
            }
        }

    @Test
    fun songAdditionRequiresAcknowledgementAndReturnsRejectionsAsFailures() =
        runBlocking {
            for (response in listOf("""{"status":"STATUS_SUCCEEDED"}""", """{"status":"STATUS_FAILED"}""", "{}")) {
                val bodies = mutableListOf<String>()
                val client =
                    HttpClient(
                        MockEngine { request ->
                            bodies += (request.body as TextContent).text
                            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    ) { install(ContentNegotiation) { json() } }
                val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=original" }
                try {
                    val result =
                        YouTubePlaylistEdits(tube, {}, { _, _ -> error("Duplicate check must be skipped when duplicates are allowed") })
                            .addToPlaylist("PLdestination", "song", allowDuplicate = true)

                    assertEquals(response.contains("STATUS_SUCCEEDED"), result.isSuccess)
                    if (result.isFailure) assertTrue(result.exceptionOrNull() is IllegalStateException)
                    val body = Json.parseToJsonElement(bodies.single()).jsonObject
                    assertEquals("PLdestination", body["playlistId"]?.jsonPrimitive?.content)
                    val action = body["actions"]!!.jsonArray.single().jsonObject
                    assertEquals("ACTION_ADD_VIDEO", action["action"]?.jsonPrimitive?.content)
                    assertEquals("song", action["addedVideoId"]?.jsonPrimitive?.content)
                } finally {
                    tube.close()
                    client.close()
                }
            }
        }

    @Test
    fun failedRemoteReorderReturnsFailureWithTheRequestedOccurrence() =
        runBlocking {
            var requestBody: String? = null
            val client =
                HttpClient(
                    MockEngine { request ->
                        requestBody = (request.body as TextContent).text
                        respond("{}", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json() } }
            val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=original" }
            try {
                val result = YouTubeRepository(tube).movePlaylistSong("PLplaylist", "second-occurrence", "successor")
                assertTrue(result.isFailure)
                val body = Json.parseToJsonElement(checkNotNull(requestBody)).jsonObject
                val action = body["actions"]!!.jsonArray.single().jsonObject
                assertEquals("second-occurrence", action["setVideoId"]?.jsonPrimitive?.content)
                assertEquals("successor", action["movedSetVideoIdSuccessor"]?.jsonPrimitive?.content)
            } finally {
                tube.close()
                client.close()
            }
        }

    @Test
    fun successfulResponseFromAnOldAccountIsRejected(): Unit =
        runBlocking {
            lateinit var tube: InnerTube
            val client =
                HttpClient(
                    MockEngine {
                        tube.cookie = "SAPISID=replacement"
                        respond(
                            """{"status":"STATUS_SUCCEEDED"}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json() } }
            tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=original" }
            try {
                val edits = YouTubePlaylistEdits(tube, {}, { _, _ -> false })
                assertFailsWith<CancellationException> { edits.renamePlaylist("PL", "Name") }
            } finally {
                tube.close()
                client.close()
            }
        }
}
