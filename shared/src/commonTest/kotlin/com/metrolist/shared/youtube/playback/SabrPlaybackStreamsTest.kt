package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.innertubex.sabr.SabrVideoStream
import com.metrolist.shared.playback.sabrAudioResource
import com.metrolist.shared.playback.sabrAudioResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalSabrApi::class)
class SabrPlaybackStreamsTest {
    @Test
    fun pendingAttestationWithTokenFailsBeforeTheSixtySecondCutoff() =
        runBlocking {
            var requests = 0
            val audio = ByteArray(4096) { it.toByte() }
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        val response =
                            if (requests == 1) {
                                byteArrayOf(35, 3, 32, 0xc0.toByte(), 0x25)
                            } else {
                                sabrAudioResponse(audio) + byteArrayOf(58, 2, 8, 2)
                            }
                        respond(
                            response,
                            headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                        )
                    },
                )
            try {
                val error =
                    assertFailsWith<SabrProtocolException> {
                        sabrAudioPlaybackChunks(
                            client,
                            bootstrap.copy(poToken = byteArrayOf(1)),
                            { 0L },
                            0L,
                            requestRetryDelay = {},
                        ).toList()
                    }
                assertEquals(SabrFailureKind.ATTESTATION_REQUIRED, error.kind)
                assertEquals(2, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun acceptedAttestationAfterInitialBackoffStillPlays() =
        runBlocking {
            var requests = 0
            val audio = ByteArray(4096) { it.toByte() }
            val client =
                HttpClient(
                    MockEngine {
                        val response =
                            if (++requests == 1) {
                                byteArrayOf(35, 3, 32, 0xc0.toByte(), 0x25)
                            } else {
                                sabrAudioResponse(audio) + byteArrayOf(58, 2, 8, 1)
                            }
                        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                    },
                )
            try {
                val chunks =
                    sabrAudioPlaybackChunks(
                        client,
                        checkNotNull(sabrAudioResource(audio.size.toLong()).sabrBootstrap).copy(poToken = byteArrayOf(1)),
                        { 0L },
                        0L,
                        requestRetryDelay = {},
                    ).toList()
                assertEquals(audio.size, chunks.sumOf { it.data.size })
                assertEquals(2, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun playbackFailsAfterTwoResponsesWithoutMediaProgress() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(byteArrayOf(), headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                    },
                )

            val error =
                assertFailsWith<SabrProtocolException> {
                    sabrAudioPlaybackChunks(
                        httpClient = client,
                        bootstrap = bootstrap,
                        playbackPositionMs = { 0L },
                        initialPlayerTimeMs = 0L,
                        requestRetryDelay = {},
                    ).toList()
                }

            assertEquals(2, requests)
            assertTrue(error.message.orEmpty().contains("no audio progress for 2 requests"))
            client.close()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun playbackWaitsForInitialServerBackoffBeforeAudio() =
        runTest {
            val audio = ByteArray(4096) { it.toByte() }
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        val response =
                            if (++requests == 1) {
                                byteArrayOf(35, 3, 32, 0xc0.toByte(), 0x25)
                            } else {
                                sabrAudioResponse(audio)
                            }
                        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                    },
                )
            try {
                val responses = mutableListOf<Long>()
                val chunks =
                    sabrAudioPlaybackChunks(
                        httpClient = client,
                        bootstrap = checkNotNull(sabrAudioResource(audio.size.toLong()).sabrBootstrap),
                        playbackPositionMs = { 0L },
                        initialPlayerTimeMs = 0L,
                        onResponse = { responses += it.backoffTimeMs },
                    ).toList()

                assertEquals(2, requests)
                assertEquals(4800L, responses.first())
                assertEquals(audio.size, chunks.sumOf { it.data.size })
                assertTrue(testScheduler.currentTime >= 4800L)
            } finally {
                client.close()
            }
        }

    @Test
    fun emptyResponseWithSafeReadaheadIsAllowedToRequestAgain() =
        runBlocking {
            var requests = 0
            var positionReads = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        if (requests == 1) {
                            respond(byteArrayOf(), headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                        } else {
                            error("Safe readahead must allow the next SABR request")
                        }
                    },
                )

            assertFailsWith<IllegalStateException> {
                sabrAudioPlaybackChunks(
                    httpClient = client,
                    bootstrap = bootstrap,
                    playbackPositionMs = { if (positionReads++ < 2) 0L else 1L },
                    initialPlayerTimeMs = 5_000L,
                    requestRetryDelay = {},
                ).toList()
            }

            assertEquals(2, requests)
            client.close()
        }

    @Test
    fun defaultSabrStreamKeepsItsThreeRequestNoProgressBudget() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(byteArrayOf(), headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                    },
                )

            val error =
                assertFailsWith<SabrProtocolException> {
                    SabrAudioStream(client, bootstrap, requestRetryDelay = {}).chunks().toList()
                }

            assertEquals(3, requests)
            assertTrue(error.message.orEmpty().contains("no audio progress for 3 requests"))
            client.close()
        }

    @Test
    fun videoKeepsItsThreeRequestNoProgressBudget() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(byteArrayOf(), headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                    },
                )

            val error =
                assertFailsWith<SabrProtocolException> {
                    SabrVideoStream(
                        httpClient = client,
                        bootstrap =
                            bootstrap.copy(
                                selectedVideoFormat = SabrFormatId(137),
                                selectedVideoHeight = 1080,
                                selectedVideoContentLengthBytes = null,
                            ),
                        requestRetryDelay = {},
                    ).chunks().toList()
                }

            assertEquals(3, requests)
            assertTrue(error.message.orEmpty().contains("no video progress for 3 requests"))
            client.close()
        }

    private companion object {
        val bootstrap =
            SabrBootstrap(
                videoId = "video",
                serverAbrStreamingUrl = "https://example.googlevideo.com/videoplayback?sabr=1",
                videoPlaybackUstreamerConfig = byteArrayOf(1),
                clientName = 1,
                clientVersion = "test",
                audioFormat = SabrFormatId(251),
                discardVideoFormat = SabrFormatId(137),
                discardVideoHeight = 1080,
                durationMs = 120_000,
                contentLengthBytes = null,
                mimeType = "audio/webm",
            )
    }
}
