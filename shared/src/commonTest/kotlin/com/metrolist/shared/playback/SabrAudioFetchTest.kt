package com.metrolist.shared.playback

import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.shared.playback.opus.OpusMp4Remuxer
import com.metrolist.shared.playback.opus.opusWebmFixture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SabrAudioFetchTest {
    @Test
    fun fetchPreservesWebmForRemuxAndMp4ForNativePlayback() =
        runBlocking {
            for (itag in listOf(251, 140)) {
                val audio = if (itag == 251) opusWebmFixture() else ByteArray(4096) { it.toByte() }
                val resource = sabrAudioResource(audio.size.toLong(), itag)
                val client =
                    HttpClient(
                        MockEngine {
                            respond(
                                sabrAudioResponse(audio, itag),
                                headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                            )
                        },
                    )
                try {
                    val disk = ByteArray(audio.size)
                    assertEquals(
                        audio.size.toLong(),
                        fetchSabrAudio(client, resource, audio.size.toLong()) { offset, bytes, length ->
                            bytes.copyInto(disk, offset.toInt(), 0, length)
                        },
                    )
                    assertContentEquals(audio, disk)
                    if (itag == 251) assertContentEquals(OpusMp4Remuxer.remux(audio), OpusMp4Remuxer.remux(disk))
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun captureAcceptsInitialServerBackoffBeforeAudio() =
        runBlocking {
            val audio = ByteArray(4096) { it.toByte() }
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        val response =
                            if (++requests == 1) {
                                byteArrayOf(35, 2, 32, 1)
                            } else {
                                sabrAudioResponse(audio)
                            }
                        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                    },
                )
            try {
                assertEquals(
                    audio.size.toLong(),
                    fetchSabrAudio(client, sabrAudioResource(audio.size.toLong()), audio.size.toLong()) { _, _, _ -> },
                )
                assertEquals(2, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun captureForwardsTheLateTokenSupplierAndRetriesProtection() =
        runBlocking {
            val audio = opusWebmFixture()
            var requests = 0
            var mints = 0
            val client =
                HttpClient(
                    MockEngine {
                        val response = if (++requests == 1) byteArrayOf(58, 2, 8, 2) else sabrAudioResponse(audio)
                        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                    },
                )
            try {
                val resource =
                    sabrAudioResource(audio.size.toLong()).copy(sabrTokenSupplier = {
                        mints++
                        byteArrayOf(1, 2)
                    })
                assertEquals(audio.size.toLong(), fetchSabrAudio(client, resource, audio.size.toLong()) { _, _, _ -> })
                assertEquals(1, mints)
                assertEquals(2, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun fetchRejectsShortServerMetadataWithoutAKnownByteLength() =
        runBlocking {
            val audio = ByteArray(4096)
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            sabrAudioResponse(audio, durationMs = 100_000),
                            headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                        )
                    },
                )
            try {
                var written = 0
                assertFailsWith<IncompleteAudioDownloadException> {
                    fetchSabrAudio(client, sabrAudioResource(durationMs = 240_000), 4096) { _, _, count -> written += count }
                }
                assertEquals(audio.size, written)
            } finally {
                client.close()
            }
        }

    @Test
    fun collectorRejectsTruncationEvenWithoutContentLengthAndBoundsWrites() =
        runTest {
            val first = SabrChunk(ByteArray(1024), 0, true, null, null, null)
            val last = SabrChunk(ByteArray(1024), 1024, false, 0, 0, 100_000)
            for (length in listOf(null, 4096L)) {
                assertFailsWith<IncompleteAudioDownloadException> {
                    collectSabrAudio(flowOf(first, last), length, 4096, expectedDurationMs = 240_000) { _, _, _ -> }
                }
            }
            for (invalid in listOf(last.copy(startRange = 1025), last.copy(startRange = 1023), last.copy(data = ByteArray(2049)))) {
                var written = 0
                assertFailsWith<IncompleteAudioDownloadException> {
                    collectSabrAudio(flowOf(first, invalid), null, 3072) { _, _, count -> written += count }
                }
                assertEquals(1024, written)
            }
            assertFailsWith<IncompleteAudioDownloadException> {
                collectSabrAudio(flowOf(first), 4097, 4096) { _, _, _ -> error("Must reject before writing") }
            }
        }

    @Test
    fun cancellationStopsProducerAndDoesNotBecomeAShortSuccess() =
        runTest {
            val started = CompletableDeferred<Unit>()
            var stopped = false
            val download =
                async {
                    collectSabrAudio(
                        flow {
                            try {
                                emit(SabrChunk(ByteArray(1024), 0, true, null, null, null))
                                started.complete(Unit)
                                awaitCancellation()
                            } finally {
                                stopped = true
                            }
                        },
                        null,
                        4096,
                    ) { _, _, _ -> }
                }
            started.await()
            download.cancelAndJoin()
            assertFailsWith<CancellationException> { download.await() }
            assertTrue(stopped)
        }
}
