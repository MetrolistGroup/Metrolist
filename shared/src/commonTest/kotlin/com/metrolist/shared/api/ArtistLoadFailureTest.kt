package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackEventLevel
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtistLoadFailureTest {
    private lateinit var previousNetwork: PlaybackNetworkState

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        previousNetwork = PlaybackNetworkStateStore.current
        PlaybackNetworkStateStore.publish(PlaybackNetworkState("artist-test"))
        PlaybackEventLog.clear()
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
        PlaybackNetworkStateStore.publish(previousNetwork)
        PlaybackEventLog.clear()
    }

    @Test
    fun failedBrowsePreservesTheHttpStatusWhenNoOfflineArtistExists() =
        runBlocking {
            val client =
                HttpClient(MockEngine { respond("{}", HttpStatusCode.BadRequest) }) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            try {
                val error = assertFailsWith<InnerTubeHttpException> { YouTubeRepository(InnerTube(client)).getArtist("artist") }
                assertEquals(HttpStatusCode.BadRequest, error.status)
                val event = PlaybackEventLog.events.value.last()
                assertEquals("artist_browse", event.details["failureStage"])
                assertEquals("400", event.details["httpStatus"])
            } finally {
                client.close()
            }
        }

    @Test
    fun invalidResponsesPropagateAndIdentifyTheResponseStageWithoutLoggingTheBody() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            "private-response-data",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            try {
                assertFailsWith<SerializationException> { YouTubeRepository(InnerTube(client)).getArtist("artist") }
                val event = PlaybackEventLog.events.value.last()
                assertEquals("artist_response", event.details["failureStage"])
                assertEquals("200", event.details["httpStatus"])
                assertFalse(PlaybackEventLog.exportText().contains("private-response-data"))
            } finally {
                client.close()
            }
        }

    @Test
    fun cancellationDoesNotBecomeAnEmptyArtistOrAnErrorEvent() =
        runBlocking {
            val cancellation = CancellationException("Artist changed")
            val client =
                HttpClient(MockEngine { throw cancellation }) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            try {
                assertFailsWith<CancellationException> { YouTubeRepository(InnerTube(client)).getArtist("artist") }
                assertTrue(PlaybackEventLog.events.value.none { it.level == PlaybackEventLevel.ERROR })
            } finally {
                client.close()
            }
        }
}
