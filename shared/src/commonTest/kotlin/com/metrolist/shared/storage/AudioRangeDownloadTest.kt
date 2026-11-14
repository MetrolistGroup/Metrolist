package com.metrolist.shared.storage

import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.playback.PlaybackFailureClassifier
import com.metrolist.shared.playback.findPlaybackHttpStatusCode
import com.metrolist.shared.youtube.playback.PlaybackResource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AudioRangeDownloadTest {
    @Test
    fun rejectedRangesRetainTheirHttpStatusAtStartAndMidStream() =
        runTest {
            for ((status, category) in listOf(
                HttpStatusCode.Forbidden to PlaybackFailureCategory.CDN_FORBIDDEN,
                HttpStatusCode.Gone to PlaybackFailureCategory.URL_EXPIRED,
            )) {
                val client = client { respond("", status) }
                try {
                    for (start in listOf(0L, CHUNK_BYTES.toLong())) {
                        val failure =
                            assertFailsWith<AudioRangeHttpStatusException> {
                                fetchAudioRange(client, resource(4L * CHUNK_BYTES), start, 4L * CHUNK_BYTES, 4L * CHUNK_BYTES) { _, _, _ ->
                                    error("Rejected media must not be written")
                                }
                            }
                        assertEquals(status.value, failure.findPlaybackHttpStatusCode())
                        assertEquals(category, PlaybackFailureClassifier.fromThrowable(failure))
                    }
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun downloadsBoundedRangesInParallelAndWritesOutOfOrderAtCorrectOffsets() =
        runTest {
            val audio = ByteArray(7 * CHUNK_BYTES + 117) { (it % 251).toByte() }
            val output = ByteArray(audio.size)
            val requests = mutableListOf<LongRange>()
            val completed = mutableListOf<Long>()
            var active = 0
            var peak = 0
            val client =
                client { request ->
                    assertEquals(listOf("identity"), request.headers.getAll(HttpHeaders.AcceptEncoding))
                    assertEquals("test-agent", request.headers[HttpHeaders.UserAgent])
                    val range = request.range()
                    requests += range
                    active++
                    peak = maxOf(peak, active)
                    delay(10L - range.first / CHUNK_BYTES)
                    active--
                    completed += range.first
                    partial(audio, range)
                }
            try {
                val result =
                    downloadAudioWithRanges(client, resource(audio.size.toLong()), audio.size.toLong()) { offset, bytes, length ->
                        bytes.copyInto(output, offset.toInt(), 0, length)
                    }
                assertEquals("audio/mp4" to audio.size.toLong(), result)
                assertContentEquals(audio, output)
                assertEquals(4, peak)
                assertTrue(completed != completed.sorted())
                assertEquals(
                    (0 until audio.size step CHUNK_BYTES).map { it.toLong()..minOf(it + CHUNK_BYTES - 1, audio.lastIndex).toLong() },
                    requests.sortedBy { it.first },
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun discoversLengthAndClampsTheLastRange() =
        runTest {
            val audio = ByteArray(CHUNK_BYTES + 123)
            val ranges = mutableListOf<LongRange>()
            val client =
                client { request ->
                    val range = request.range()
                    ranges += range
                    partial(audio, range)
                }
            try {
                assertEquals(
                    audio.size.toLong(),
                    downloadAudioWithRanges(client, resource(null), audio.size.toLong()) { _, _, _ -> }.second,
                )
                assertEquals(listOf(0L..CHUNK_BYTES - 1L, CHUNK_BYTES.toLong()..audio.lastIndex.toLong()), ranges)
            } finally {
                client.close()
            }
        }

    @Test
    fun honorsSmallerClientChunksAndBoundsOversizedChunks() =
        runTest {
            for (chunkBytes in listOf(2048L, Long.MAX_VALUE)) {
                val audio = ByteArray(4096)
                val client =
                    client { request ->
                        val range = request.range()
                        assertTrue(range.last - range.first + 1 <= minOf(chunkBytes, CHUNK_BYTES.toLong()))
                        partial(audio, range)
                    }
                try {
                    val result =
                        downloadAudioWithRanges(
                            client,
                            resource(null).copy(useRangeChunks = true, rangeChunkSizeBytes = chunkBytes),
                            audio.size.toLong(),
                        ) { _, _, _ -> }
                    assertEquals(audio.size.toLong(), result.second)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun acceptsCompleteResponseOnlyForTheFirstRange() =
        runTest {
            val audio = ByteArray(4096)
            for (knownLength in listOf(null, audio.size.toLong())) {
                var calls = 0
                val client =
                    client {
                        calls++
                        respond(audio, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, audio.size.toString()))
                    }
                try {
                    val result = downloadAudioWithRanges(client, resource(knownLength), audio.size.toLong()) { _, _, _ -> }
                    assertEquals(audio.size.toLong(), result.second)
                    assertEquals(1, calls)
                } finally {
                    client.close()
                }
            }
            val client =
                client { request ->
                    if (request.range().first == 0L) {
                        partial(ByteArray(CHUNK_BYTES + 1024), request.range())
                    } else {
                        respond(audio, HttpStatusCode.OK)
                    }
                }
            try {
                assertFailsWith<IllegalStateException> {
                    downloadAudioWithRanges(client, resource(null), 2L * CHUNK_BYTES) { _, _, _ -> }
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun rejectsMismatchedTruncatedAndOversizedResponses() =
        runTest {
            val cases =
                listOf(
                    "bytes 1-2048/2048" to 2048,
                    "bytes 0-2046/2048" to 2047,
                    "bytes 0-2047/4096" to 2048,
                    "bytes 0-2047/2048" to 2047,
                    "bytes 0-2047/2048" to 2049,
                    "bytes 0-2047/*" to 2048,
                    "bytes 0-2047/99999999999999999999" to 2048,
                )
            for ((contentRange, bytes) in cases) {
                val client =
                    client {
                        respond(ByteArray(bytes), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, contentRange))
                    }
                try {
                    assertFailsWith<IllegalStateException>(contentRange) {
                        downloadAudioWithRanges(client, resource(2048L), 8192) { _, _, _ -> }
                    }
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun enforcesStorageLimitBeforeWritingForKnownAndUnknownLengths() =
        runTest {
            for (size in listOf(null, 4096L)) {
                var writes = 0
                val client = client { request -> partial(ByteArray(4096), request.range()) }
                try {
                    assertFailsWith<IllegalStateException> {
                        downloadAudioWithRanges(client, resource(size), 2048) { _, _, _ -> writes++ }
                    }
                    assertEquals(0, writes)
                } finally {
                    client.close()
                }
            }
            var written = 0
            val client = client { respond(ByteArray(65_537), HttpStatusCode.OK) }
            try {
                assertFailsWith<IllegalStateException> {
                    downloadAudioWithRanges(client, resource(null), 65_536) { _, _, length -> written += length }
                }
                assertTrue(written <= 65_536)
            } finally {
                client.close()
            }
        }

    @Test
    fun followsTrustedRelativeRedirectsWithoutForwardingResourceCredentials() =
        runTest {
            val audio = ByteArray(2048)
            val client =
                client { request ->
                    assertEquals("bytes=0-2047", request.headers[HttpHeaders.Range])
                    if (request.url.parameters["redirected"] == null) {
                        assertEquals("private", request.headers[HttpHeaders.Authorization])
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/videoplayback?redirected=1"))
                    } else {
                        assertEquals(null, request.headers[HttpHeaders.Authorization])
                        partial(audio, request.range())
                    }
                }
            try {
                downloadAudioWithRanges(client, resource(2048), 2048) { _, _, _ -> }
            } finally {
                client.close()
            }
        }

    @Test
    fun rejectsUntrustedRedirectsAndRedirectLoops() =
        runTest {
            for (target in listOf("https://example.com/videoplayback", "/videoplayback")) {
                var calls = 0
                val client =
                    client {
                        calls++
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, target))
                    }
                try {
                    assertFailsWith<IllegalStateException> {
                        downloadAudioWithRanges(client, resource(2048), 2048) { _, _, _ -> }
                    }
                    assertEquals(if (target.startsWith("https")) 1 else MAX_AUDIO_DOWNLOAD_REDIRECTS + 1, calls)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun cancellationStopsTheTransferWithoutRetrying() =
        runTest {
            var calls = 0
            val client =
                client { request ->
                    calls++
                    partial(ByteArray(2 * CHUNK_BYTES), request.range())
                }
            try {
                assertFailsWith<CancellationException> {
                    downloadAudioWithRanges(
                        client,
                        resource(null),
                        2L * CHUNK_BYTES,
                    ) { _, _, _ -> throw CancellationException("cancelled") }
                }
                assertEquals(1, calls)
            } finally {
                client.close()
            }
        }

    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine) {
            followRedirects = false
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { handler(it) }
            }
        }

    private fun HttpRequestData.range(): LongRange {
        val values = requireNotNull(headers[HttpHeaders.Range]).removePrefix("bytes=").split('-').map(String::toLong)
        return values[0]..values[1]
    }

    private fun MockRequestHandleScope.partial(
        audio: ByteArray,
        range: LongRange,
    ): HttpResponseData {
        val end = minOf(range.last.toInt(), audio.lastIndex)
        return respond(
            audio.copyOfRange(range.first.toInt(), end + 1),
            HttpStatusCode.PartialContent,
            headersOf(
                HttpHeaders.ContentRange to listOf("bytes ${range.first}-$end/${audio.size}"),
                HttpHeaders.ContentLength to listOf((end - range.first + 1).toString()),
                HttpHeaders.ContentType to listOf("audio/mp4"),
            ),
        )
    }

    private fun resource(size: Long?) =
        PlaybackResource(
            videoId = "song",
            url = "https://rr1.googlevideo.com/videoplayback?sig=test",
            headers =
                mapOf(
                    HttpHeaders.Range to "bytes=0-0",
                    HttpHeaders.UserAgent to "test-agent",
                    HttpHeaders.Authorization to "private",
                ),
            loudnessDb = null,
            expiresAt = null,
            contentLengthBytes = size,
            mimeType = "audio/mp4",
        )

    private companion object {
        const val CHUNK_BYTES = 1_048_576
    }
}
