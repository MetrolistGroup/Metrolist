package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeLocale
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YouTubeNextRequestsTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    private fun TestScope.mockClient(handler: MockRequestHandler) =
        HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler(handler)
            }
            install(ContentNegotiation) { json() }
        }

    @Test
    fun songDetailsRequestsUseAppLanguageWithoutChangingContentLocale() =
        runTest {
            val requestedLanguages = mutableListOf<String>()
            val client =
                mockClient { request ->
                    val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    val context =
                        body
                            .getValue("context")
                            .jsonObject
                            .getValue("client")
                            .jsonObject
                    requestedLanguages += context.getValue("hl").jsonPrimitive.content
                    assertEquals("PL", context.getValue("gl").jsonPrimitive.content)
                    respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val tube = InnerTube(client, retryDelay = {}).apply { locale = YouTubeLocale(gl = "PL", hl = "pl") }
            try {
                val repository = YouTubeRepository(tube)
                repository.getSongWatchInformation("video", "fr-FR")
                repository.getSongWatchInformation("video", "ar")
                assertEquals(listOf("fr-FR", "ar"), requestedLanguages)
                assertEquals(YouTubeLocale(gl = "PL", hl = "pl"), tube.locale)
            } finally {
                tube.close()
                client.close()
            }
        }

    @Test
    fun concurrentMetadataAndRadioShareBodiesButRefreshStillFetches() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val metadataStarted = CompletableDeferred<Unit>()
            val parametersStarted = CompletableDeferred<Unit>()
            val radioStarted = CompletableDeferred<Unit>()
            var requests = 0
            val client =
                mockClient { request ->
                    requests++
                    val body = request.body.toByteArray().decodeToString()
                    when {
                        "RDAMVM" in body -> radioStarted.complete(Unit)
                        "different" in body -> parametersStarted.complete(Unit)
                        else -> metadataStarted.complete(Unit)
                    }
                    release.await()
                    respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                val album = async { repository.getSongAlbum("video") }
                metadataStarted.await()
                val counterpart = async(start = CoroutineStart.UNDISPATCHED) { repository.getMusicVideoCounterpart("video") }
                val parameters = async { repository.getMusicVideoCounterpart("video", params = "different") }
                parametersStarted.await()
                val radio = async { repository.startRadioQueue("video") }
                radioStarted.await()
                val otherRadio = async(start = CoroutineStart.UNDISPATCHED) { repository.startRadioQueue("video") }
                release.complete(Unit)
                album.await()
                counterpart.await()
                parameters.await()
                radio.await()
                otherRadio.await()
                assertEquals(3, requests)

                repository.startRadioQueue("video")
                repository.getSongAlbum("video")
                assertEquals(5, requests)
            } finally {
                release.complete(Unit)
                client.close()
            }
        }

    @Test
    fun cancellingAFollowerDoesNotCancelTheRequestAndFailureCanRetry() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var fail = true
            var requests = 0
            val client =
                mockClient {
                    requests++
                    started.complete(Unit)
                    release.await()
                    respond(
                        "{}",
                        if (fail) HttpStatusCode.BadRequest else HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                val owner = async { runCatching { repository.startRadioQueue("video") } }
                started.await()
                val follower = async(start = CoroutineStart.UNDISPATCHED) { repository.startRadioQueue("video") }
                follower.cancelAndJoin()
                release.complete(Unit)
                assertTrue(owner.await().isFailure)
                assertEquals(1, requests)
                fail = false
                repository.startRadioQueue("video")
                assertEquals(2, requests)
            } finally {
                release.complete(Unit)
                client.close()
            }
        }

    @Test
    fun cancellingTheOwnerLetsIndependentFollowersRetry() =
        runTest {
            val started = CompletableDeferred<Unit>()
            var requests = 0
            val client =
                mockClient {
                    requests++
                    if (requests == 1) {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                    respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                val owner = async { repository.startRadioQueue("video") }
                started.await()
                val follower = async(start = CoroutineStart.UNDISPATCHED) { repository.startRadioQueue("video") }
                owner.cancelAndJoin()
                follower.await()
                assertEquals(2, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun accountChangesDoNotShareOrPublishOldResponses() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var requests = 0
            val client =
                mockClient {
                    requests++
                    if (requests == 1) {
                        started.complete(Unit)
                        release.await()
                    }
                    respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            try {
                val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=first" }
                val repository = YouTubeRepository(tube)
                val old = async { runCatching { repository.startRadioQueue("video") } }
                started.await()
                tube.cookie = "SAPISID=second"
                repository.startRadioQueue("video")
                assertEquals(2, requests)
                release.complete(Unit)
                assertTrue(old.await().exceptionOrNull() is CancellationException)
            } finally {
                release.complete(Unit)
                client.close()
            }
        }
}
