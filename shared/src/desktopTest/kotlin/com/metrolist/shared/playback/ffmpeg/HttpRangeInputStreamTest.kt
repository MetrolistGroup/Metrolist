package com.metrolist.shared.playback.ffmpeg

import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpRangeInputStreamTest {
    @Test
    fun retriedMalformedRangeDoesNotLogWhenTheRetrySucceeds() {
        PlaybackEventLog.clear()
        var requests = 0
        val bytes = ByteArray(16) { it.toByte() }
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    requests++
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", if (requests == 1) "bytes 1-16/32" else "bytes 0-15/32")
                        .body(bytes.toResponseBody())
                        .build()
                }.build()
        try {
            HttpRangeInputStream(
                httpClient = client,
                url = "https://media.example.test/audio",
                requestHeaders = emptyMap(),
                mediaSize = 32,
                chunkSize = 16,
            ).use { input ->
                assertContentEquals(bytes, input.readNBytes(bytes.size))
                assertEquals(2, requests)
                assertTrue(PlaybackEventLog.events.value.isEmpty())
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            PlaybackEventLog.clear()
        }
    }

    @Test
    fun transferBudgetRespectsProbeAndMaximumRangeSizes() {
        assertEquals(81_920, transferSizedChunkLimit(65_536, elapsedMs = 4_000, targetMs = 5_000, maxChunkSize = 1_048_576))
        assertEquals(65_536, transferSizedChunkLimit(81_920, elapsedMs = 10_000, targetMs = 5_000, maxChunkSize = 1_048_576))
        assertEquals(1_048_576, transferSizedChunkLimit(65_536, elapsedMs = 10, targetMs = 5_000, maxChunkSize = 1_048_576))
        assertEquals(1_048_576, transferSizedChunkLimit(65_536, elapsedMs = 0, targetMs = 5_000, maxChunkSize = 1_048_576))
        assertEquals(65_536, transferSizedChunkLimit(1, elapsedMs = Long.MAX_VALUE, targetMs = 5_000, maxChunkSize = 1_048_576))
        assertEquals(32_000, transferSizedChunkLimit(1, elapsedMs = Long.MAX_VALUE, targetMs = 2_000, maxChunkSize = 32_000))
    }

    @Test
    fun timeoutsRetrySmallerRangesAndRetainTheLimitAfterExhaustingRetries() {
        for (targetMs in listOf(2_000L, null)) {
            for (failures in 1..2) {
                val ranges = mutableListOf<LongRange>()
                val bytes = ByteArray(1_932_345) { (it % 251).toByte() }
                var failedRequests = 0
                val client =
                    OkHttpClient
                        .Builder()
                        .addInterceptor { chain ->
                            val (start, end) =
                                checkNotNull(chain.request().header("Range")).removePrefix("bytes=").split('-').map(String::toLong)
                            ranges += start..end
                            val fail = start == 960_000L && failedRequests < failures
                            if (fail) failedRequests++
                            var reads = 0
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(206)
                                .message("Partial Content")
                                .header("Content-Range", "bytes $start-$end/${bytes.size}")
                                .body(
                                    responseBody(bytes.copyOfRange(start.toInt(), end.toInt() + 1)) {
                                        if (fail && reads++ > 0) {
                                            if (failures == 1) throw SocketTimeoutException("body timeout")
                                            throw InterruptedIOException("timeout")
                                        }
                                    },
                                ).build()
                        }.build()
                HttpRangeInputStream(
                    httpClient = client,
                    url = "https://media.example.test/audio",
                    requestHeaders = emptyMap(),
                    mediaSize = bytes.size.toLong(),
                    chunkSize = 960_000,
                    initialChunkSize = 960_000,
                    targetRequestDurationMs = targetMs,
                ).use { input ->
                    assertContentEquals(bytes.copyOfRange(0, 960_000), input.readNBytes(960_000))
                    if (failures == 2) {
                        assertFailsWith<InterruptedIOException> { input.read() }
                        assertEquals(960_000L, input.mediaPosition)
                        assertTrue(input.streamFailure is InterruptedIOException)
                    }
                    assertContentEquals(bytes.copyOfRange(960_000, bytes.size), input.readBytes())
                    assertEquals(960_000L..1_919_999L, ranges[1])
                    val retryEnd = if (targetMs != null) 1_025_535L else 1_919_999L
                    assertEquals(960_000L..retryEnd, ranges[2])
                    if (failures == 2) assertEquals(ranges[2], ranges[3])
                    assertEquals(bytes.size.toLong(), input.mediaPosition)
                    assertNull(input.streamFailure)
                }
            }
        }
    }

    @Test
    fun rateLimitRetainsRetryAfterAndDoesNotRetryTheSameRange() {
        var requests = 0
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    requests++
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .header("Retry-After", "120")
                        .body(ByteArray(0).toResponseBody())
                        .build()
                }.build()
        HttpRangeInputStream(client, "https://media.example.test/audio", emptyMap(), 1024, prefetchAhead = false).use { input ->
            val failure = assertFailsWith<HttpMediaStatusException> { input.read() }
            assertEquals(429, failure.playbackHttpStatusCode)
            assertEquals("120", failure.retryAfter)
            assertEquals(1, requests)
        }
    }

    @Test
    fun demandReadReplacesPrefetchCancelledBeforeItStarted() {
        var requests = 0
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    requests++
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes 0-0/1")
                        .body(byteArrayOf(42).toResponseBody())
                        .build()
                }.build()
        HttpRangeInputStream(
            httpClient = client,
            url = "https://media.example.test/videoplayback",
            requestHeaders = emptyMap(),
            mediaSize = 1,
        ).use { input ->
            @Suppress("UNCHECKED_CAST")
            val inFlight =
                HttpRangeInputStream::class.java
                    .getDeclaredField("inFlightChunks")
                    .apply { isAccessible = true }
                    .get(input) as MutableMap<Long, Deferred<ByteArray>>
            inFlight[0L] = CompletableDeferred<ByteArray>().apply { cancel() }
            assertEquals(42, input.read())
            assertEquals(1, requests)
            assertTrue(inFlight.isEmpty())
        }
    }

    @Test
    fun closeCancelsAnActiveRangeRequest() {
        val requestStarted = CountDownLatch(1)
        val requestCancelled = CountDownLatch(1)
        val client =
            OkHttpClient
                .Builder()
                .eventListener(
                    object : EventListener() {
                        override fun canceled(call: Call) {
                            requestCancelled.countDown()
                        }
                    },
                ).addInterceptor { chain ->
                    requestStarted.countDown()
                    check(requestCancelled.await(5, TimeUnit.SECONDS))
                    throw IOException("cancelled")
                }.build()
        val input =
            HttpRangeInputStream(
                httpClient = client,
                url = "https://media.example.test/videoplayback",
                requestHeaders = emptyMap(),
                mediaSize = 1_024,
            )
        val readFinished = CountDownLatch(1)
        val reader =
            thread(isDaemon = true) {
                runCatching { input.read(ByteArray(1)) }
                readFinished.countDown()
            }

        try {
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS), "Range request did not start")
            input.close()
            assertTrue(readFinished.await(5, TimeUnit.SECONDS), "Range request was not cancelled")
            reader.join(5_000)
            assertFalse(reader.isAlive)
        } finally {
            input.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test
    fun startupUsesSmallRangeAndReadsAcrossChunkBoundary() {
        val ranges = mutableListOf<String>()
        val size = 2_097_152L
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val range = checkNotNull(chain.request().header("Range"))
                    ranges += range
                    val (start, end) = range.removePrefix("bytes=").split('-').map(String::toLong)
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes $start-$end/$size")
                        .body(ByteArray((end - start + 1).toInt()) { ((start + it) % 251).toByte() }.toResponseBody())
                        .build()
                }.build()
        HttpRangeInputStream(
            httpClient = client,
            url = "https://media.example.test/videoplayback",
            requestHeaders = emptyMap(),
            mediaSize = size,
        ).use { input ->
            assertEquals(0, input.read())
            assertEquals(listOf("bytes=0-65535"), ranges)
            input.seekToMediaPosition(65_535)
            val bytes = ByteArray(2)
            assertEquals(1, input.read(bytes))
            assertEquals((65_535 % 251).toByte(), bytes[0])
            assertEquals(1, input.read(bytes, 1, 1))
            assertEquals((65_536 % 251).toByte(), bytes[1])
            assertEquals(listOf("bytes=0-65535", "bytes=65536-196607"), ranges)
        }
    }

    @Test
    fun configuredInitialChunkSizeIsUsedForTheFirstRequest() {
        val ranges = mutableListOf<String>()
        val size = 2_097_152L
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val range = checkNotNull(chain.request().header("Range"))
                    ranges += range
                    val (start, end) = range.removePrefix("bytes=").split('-').map(String::toLong)
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes $start-$end/$size")
                        .body(ByteArray((end - start + 1).toInt()).toResponseBody())
                        .build()
                }.build()
        HttpRangeInputStream(
            httpClient = client,
            url = "https://media.example.test/videoplayback",
            requestHeaders = emptyMap(),
            mediaSize = size,
            chunkSize = 1_048_576,
            initialChunkSize = 1_048_576,
        ).use { input ->
            assertEquals(0, input.read())
            assertEquals(listOf("bytes=0-1048575"), ranges)
        }
    }

    @Test
    fun sequentialPlaybackHasABoundedRequestBudgetAndSeeksStillStartSmall() {
        val ranges = mutableListOf<LongRange>()
        val size = 8_388_608L
        val chunkSize = 1_048_576
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val (start, end) =
                        checkNotNull(chain.request().header("Range"))
                            .removePrefix("bytes=")
                            .split('-')
                            .map(String::toLong)
                    ranges += start..end
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes $start-$end/$size")
                        .body(ByteArray((end - start + 1).toInt()).toResponseBody())
                        .build()
                }.build()
        HttpRangeInputStream(
            httpClient = client,
            url = "https://media.example.test/videoplayback",
            requestHeaders = emptyMap(),
            mediaSize = size,
            chunkSize = chunkSize,
        ).use { input ->
            val buffer = ByteArray(16_384)
            var consumed = 0
            while (consumed < 4_194_304) {
                val count = input.read(buffer, 0, minOf(buffer.size, 4_194_304 - consumed))
                assertTrue(count > 0)
                consumed += count
            }
            assertTrue(ranges.size <= 8, "Four MiB should not require dozens of requests")
            assertTrue(ranges.all { it.last - it.first < chunkSize })
            assertTrue(ranges.sumOf { it.last - it.first + 1 } <= consumed + chunkSize)
            val requests = ranges.size
            input.seekToMediaPosition(6_000_000L)
            assertEquals(0, input.read())
            assertEquals(requests + 1, ranges.size)
            assertEquals(6_000_000L..6_065_535L, ranges.last())
        }
    }

    @Test
    fun distantSeekStartsAtTheRequestedByteWithASmallRange() {
        val ranges = mutableListOf<String>()
        val size = 8_388_608L
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val range = checkNotNull(chain.request().header("Range"))
                    ranges += range
                    val (start, end) = range.removePrefix("bytes=").split('-').map(String::toLong)
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes $start-$end/$size")
                        .body(ByteArray((end - start + 1).toInt()).toResponseBody())
                        .build()
                }.build()
        HttpRangeInputStream(
            httpClient = client,
            url = "https://media.example.test/videoplayback",
            requestHeaders = emptyMap(),
            mediaSize = size,
        ).use { input ->
            assertEquals(0, input.read())
            assertEquals(3_000_123L, input.seekToMediaPosition(3_000_123L))
            assertEquals(0, input.read())
        }

        assertEquals(
            listOf("bytes=0-65535", "bytes=3000123-3065658"),
            ranges,
        )
    }

    @Test
    fun cacheRetainsOnlyFourRecentChunks() {
        val ranges = mutableListOf<String>()
        val size = 64L
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val range = checkNotNull(chain.request().header("Range"))
                    ranges += range
                    val (start, end) = range.removePrefix("bytes=").split('-').map(String::toLong)
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes $start-$end/$size")
                        .body(ByteArray((end - start + 1).toInt()).toResponseBody())
                        .build()
                }.build()
        HttpRangeInputStream(
            httpClient = client,
            url = "https://media.example.test/videoplayback",
            requestHeaders = emptyMap(),
            mediaSize = size,
            chunkSize = 8,
        ).use { input ->
            for (position in listOf(0L, 8L, 16L, 24L, 32L, 0L)) {
                input.seekToMediaPosition(position)
                assertEquals(0, input.read())
            }
        }

        assertEquals(2, ranges.count { it == "bytes=0-7" })
    }

    @Test
    fun distantSeekCancelsObsoletePrefetch() {
        val prefetchStarted = CountDownLatch(1)
        val prefetchCancelled = CountDownLatch(1)
        val size = 524_288L
        val client =
            OkHttpClient
                .Builder()
                .eventListener(
                    object : EventListener() {
                        override fun canceled(call: Call) {
                            if (call.request().header("Range") == "bytes=65536-196607") prefetchCancelled.countDown()
                        }
                    },
                ).addInterceptor { chain ->
                    val range = checkNotNull(chain.request().header("Range"))
                    if (range == "bytes=65536-196607") {
                        prefetchStarted.countDown()
                        check(prefetchCancelled.await(5, TimeUnit.SECONDS))
                        throw IOException("cancelled")
                    }
                    val (start, end) = range.removePrefix("bytes=").split('-').map(String::toLong)
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes $start-$end/$size")
                        .body(ByteArray((end - start + 1).toInt()).toResponseBody())
                        .build()
                }.build()

        try {
            HttpRangeInputStream(
                httpClient = client,
                url = "https://media.example.test/videoplayback",
                requestHeaders = emptyMap(),
                mediaSize = size,
                chunkSize = 131_072,
                prefetchAhead = true,
            ).use { input ->
                assertEquals(0, input.read())
                assertTrue(prefetchStarted.await(5, TimeUnit.SECONDS), "Prefetch did not start")
                input.seekToMediaPosition(300_000L)
                assertEquals(0, input.read())
                assertTrue(prefetchCancelled.await(5, TimeUnit.SECONDS), "Obsolete prefetch was not cancelled")
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test
    fun malformedRangesFailInsteadOfReturningCorruptAudio() {
        for ((status, contentRange, body) in listOf(
            Triple(206, "bytes 0-15/32", ByteArray(8)),
            Triple(206, "bytes 8-23/32", ByteArray(16)),
            Triple(206, "bytes 0-15/32", ByteArray(32)),
            Triple(200, "", ByteArray(32)),
        )) {
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(status)
                            .message("OK")
                            .header("Content-Range", contentRange)
                            .body(body.toResponseBody())
                            .build()
                    }.build()
            HttpRangeInputStream(
                httpClient = client,
                url = "https://media.example.test/videoplayback",
                requestHeaders = emptyMap(),
                mediaSize = 32,
                chunkSize = 16,
            ).use { input ->
                assertFailsWith<IOException>("status=$status range=$contentRange size=${body.size}") { input.read() }
            }
        }
    }

    @Test
    fun expiredStreamsNotifyRecoveryAndARefreshedUrlResumesAtTheRequestedPosition() {
        for (status in listOf(401, 403, 404, 410, 416)) {
            val requestedHosts = mutableListOf<String>()
            val expired = mutableListOf<Int?>()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        requestedHosts += request.url.host
                        if (request.url.host == "expired.example.test") {
                            Response
                                .Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(status)
                                .message("Rejected")
                                .body(ByteArray(0).toResponseBody())
                                .build()
                        } else {
                            val (start, end) =
                                checkNotNull(request.header("Range"))
                                    .removePrefix("bytes=")
                                    .split('-')
                                    .map(String::toLong)
                            Response
                                .Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(206)
                                .message("Partial Content")
                                .header("Content-Range", "bytes $start-$end/32")
                                .body(ByteArray((end - start + 1).toInt()) { (start + it).toByte() }.toResponseBody())
                                .build()
                        }
                    }.build()
            HttpRangeInputStream(
                httpClient = client,
                url = "https://expired.example.test/videoplayback",
                requestHeaders = emptyMap(),
                mediaSize = 32,
                onStreamExpired = { expired += it },
            ).use { input ->
                input.seekToMediaPosition(7L)
                assertEquals(status, assertFailsWith<HttpMediaStatusException> { input.read() }.playbackHttpStatusCode)
            }
            HttpRangeInputStream(
                httpClient = client,
                url = "https://fresh.example.test/videoplayback",
                requestHeaders = emptyMap(),
                mediaSize = 32,
            ).use { refreshed ->
                refreshed.seekToMediaPosition(7L)
                assertEquals(7, refreshed.read())
            }
            assertEquals(listOf<Int?>(status), expired)
            assertEquals(listOf("expired.example.test", "fresh.example.test"), requestedHosts)
        }
    }

    @Test
    fun initialBufferedBytePositionMatchesInitialPosition() {
        val client = OkHttpClient.Builder().build()
        val input =
            HttpRangeInputStream(
                httpClient = client,
                url = "https://media.example.test/videoplayback",
                requestHeaders = emptyMap(),
                mediaSize = 1_024,
            )
        try {
            assertEquals(0L, input.mediaPosition)
            assertEquals(0L, input.bufferedBytePosition)
        } finally {
            input.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private fun responseBody(
        bytes: ByteArray,
        beforeRead: () -> Unit,
    ): ResponseBody {
        val source =
            object : ForwardingSource(Buffer().write(bytes)) {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    beforeRead()
                    return super.read(sink, byteCount)
                }
            }.buffer()
        return object : ResponseBody() {
            override fun contentType() = null

            override fun contentLength() = bytes.size.toLong()

            override fun source() = source
        }
    }
}
