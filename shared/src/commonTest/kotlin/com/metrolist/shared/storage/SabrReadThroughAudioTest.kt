package com.metrolist.shared.storage

import com.metrolist.shared.playback.sabrAudioResource
import com.metrolist.shared.playback.sabrAudioResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SabrReadThroughAudioTest {
    @Test
    fun wholeCaptureWaitsForValidationAndReusesBytesForSeeks() =
        runTest {
            val audio = ByteArray(8192) { it.toByte() }
            val disk = ByteArray(audio.size)
            val response = sabrAudioResponse(audio)
            val body = ByteChannel(autoFlush = true)
            var requests = 0
            val client =
                HttpClient(MockEngine) {
                    engine {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler {
                            requests++
                            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
                        }
                    }
                }
            val source =
                ReadThroughAudio(
                    client,
                    sabrAudioResource(),
                    audio.size.toLong(),
                    backgroundScope,
                    write = { offset, bytes, count -> bytes.copyInto(disk, offset.toInt(), 0, count) },
                    read = { offset, length -> disk.copyOfRange(offset.toInt(), offset.toInt() + length) },
                )
            try {
                val information = async { source.information() }
                body.writeFully(response, 0, response.size / 2)
                runCurrent()
                assertFalse(information.isCompleted)
                body.writeFully(response, response.size / 2, response.size)
                body.close()
                assertEquals("audio/webm" to audio.size.toLong(), information.await())
                assertContentEquals(audio.copyOfRange(5000, 7000), source.readAt(5000, 2000))
                assertContentEquals(audio.copyOfRange(0, 2000), source.readAt(0, 2000))
                source.copyComplete { _, length -> assertEquals(audio.size.toLong(), length) }
                assertEquals(1, requests)
                assertEquals(0L, source.pendingBytes)
            } finally {
                source.close { }
                client.close()
            }
        }

    @Test
    fun closingCancelledOrTruncatedCaptureReclaimsStorageAndWakesReaders() =
        runTest {
            for (cancel in listOf(false, true)) {
                val body = ByteChannel(autoFlush = true)
                val client =
                    HttpClient(MockEngine) {
                        engine {
                            dispatcher = StandardTestDispatcher(testScheduler)
                            addHandler { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump")) }
                        }
                    }
                var cleaned = false
                val source =
                    ReadThroughAudio(
                        client,
                        sabrAudioResource(),
                        8192,
                        backgroundScope,
                        write = { _, _, _ -> check(!cleaned) },
                        read = { _, _ -> error("No complete file") },
                    )
                try {
                    val result = async { runCatching { source.information() } }
                    body.writeFully(byteArrayOf(20, 100, 1))
                    if (cancel) {
                        runCurrent()
                        source.cancelPendingRequests()
                        source.close { cleaned = true }
                    } else {
                        body.close()
                    }
                    assertTrue(result.await().isFailure)
                    source.close { cleaned = true }
                    assertTrue(cleaned)
                    assertEquals(0L, source.reservation)
                    assertEquals(0L, source.pendingBytes)
                    assertFails { source.readAt(0, 1) }
                } finally {
                    source.close { cleaned = true }
                    client.close()
                }
            }
        }
}
