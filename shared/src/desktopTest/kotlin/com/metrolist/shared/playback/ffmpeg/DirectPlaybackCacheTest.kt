package com.metrolist.shared.playback.ffmpeg

import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DirectPlaybackCacheTest {
    @Test
    fun startupAndClearRemoveOrphanCapturesBeforePlaybackStarts() {
        for (clear in listOf(false, true)) {
            val directory = Files.createTempDirectory("orphan-playback-tee").toFile()
            try {
                val stale = directory.resolve("playback-crashed.part").apply { writeBytes(ByteArray(2048)) }
                val unrelated = directory.resolve("other.part").apply { writeText("keep") }
                if (clear) DirectPlaybackCache.clear(directory) else DirectPlaybackCache.reconcileDirectory(directory)
                assertFalse(stale.exists())
                assertEquals("keep", unrelated.readText())
                val resource = resource("active-$clear", 2048)
                val capture = DirectPlaybackCache.begin(resource, directory, 4096)!!
                capture.record(0, ByteArray(1024), 2048)
                DirectPlaybackCache.reconcileDirectory(directory)
                assertNotNull(capture.read(0, 1024))
                capture.discard()
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun tailProbeAndSeekToEofDoNotCompleteAbandonedPlayback() =
        runBlocking {
            val directory = Files.createTempDirectory("playback-tail-probe").toFile()
            val bytes = ByteArray(8192) { it.toByte() }
            val resource = resource("tail-only", bytes.size.toLong())
            val capture = DirectPlaybackCache.begin(resource, directory, 16384)!!
            var requests = 0
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        requests++
                        respondWithBytes(chain, bytes)
                    }.build()
            try {
                HttpRangeInputStream(
                    client,
                    resource.url,
                    emptyMap(),
                    bytes.size.toLong(),
                    chunkSize = 1024,
                    cacheCapture = capture,
                    prefetchAhead = false,
                ).use {
                    it.readAtMediaPosition(7168, ByteArray(1024), 0, 1024)
                    assertEquals(-1, it.readAtMediaPosition(8192, ByteArray(1), 0, 1))
                    it.seekToMediaPosition(7168)
                    assertEquals(1024, it.readBytes().size)
                    it.seekToMediaPosition(0)
                    assertEquals(0, it.read())
                    it.seekToMediaPosition(8192)
                }
                val requestsBeforeClose = requests
                assertFalse(DirectPlaybackCache.awaitCompletion(resource, client))
                assertEquals(requestsBeforeClose, requests)
            } finally {
                capture.discard()
                directory.deleteRecursively()
            }
        }

    @Test
    fun clearingReleasesWaitersAndDiscardsCompleteAndPartialFiles() =
        runBlocking {
            val directory = Files.createTempDirectory("playback-tee-test").toFile()
            try {
                val complete = resource("clear-complete", 2048)
                val partial = resource("clear-partial", 2048)
                DirectPlaybackCache.begin(complete, directory, 8192)!!.record(0, ByteArray(2048), 2048)
                val capture = DirectPlaybackCache.begin(partial, directory, 8192)!!
                capture.record(0, ByteArray(1024), 2048)
                val waiting = async(start = CoroutineStart.UNDISPATCHED) { DirectPlaybackCache.awaitCompletion(partial) }
                DirectPlaybackCache.clear()
                assertFalse(waiting.await())
                assertFalse(DirectPlaybackCache.copyComplete(complete, directory.resolve("bad.audio")))
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun validatedPlaybackRangesAreReusedWithoutAnotherRequest() =
        runBlocking {
            val directory = Files.createTempDirectory("playback-tee-test").toFile()
            val bytes = ByteArray(4096) { (it % 251).toByte() }
            val resource = resource("complete", bytes.size.toLong())
            val capture = DirectPlaybackCache.begin(resource, directory, 8192)!!
            var requests = 0
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        requests++
                        respondWithBytes(chain, bytes)
                    }.build()
            try {
                HttpRangeInputStream(
                    client,
                    resource.url,
                    emptyMap(),
                    bytes.size.toLong(),
                    chunkSize = 1024,
                    cacheCapture = capture,
                    targetRequestDurationMs = 5_000,
                ).use {
                    it.readAtMediaPosition(3072, ByteArray(1024), 0, 1024)
                    assertContentEquals(bytes, it.readBytes())
                }
                assertTrue(DirectPlaybackCache.awaitCompletion(resource))
                val destination = directory.resolve("cached.audio")
                assertTrue(DirectPlaybackCache.copyComplete(resource, destination))
                assertContentEquals(bytes, destination.readBytes())
                assertTrue(requests <= 5)
                assertFalse(DirectPlaybackCache.copyComplete(resource, directory.resolve("second.audio")))
            } finally {
                capture.discard()
                directory.deleteRecursively()
            }
        }

    @Test
    fun forwardSeekGapsAreFilledOnPlaybackCompletion() =
        runBlocking {
            val directory = Files.createTempDirectory("playback-tee-test").toFile()
            val bytes = ByteArray(4096) { (it % 251).toByte() }
            val resource = resource("seek-fill", bytes.size.toLong())
            val capture = DirectPlaybackCache.begin(resource, directory, 8192)!!
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        respondWithBytes(chain, bytes)
                    }.build()
            try {
                HttpRangeInputStream(
                    client,
                    resource.url,
                    emptyMap(),
                    bytes.size.toLong(),
                    chunkSize = 1024,
                    cacheCapture = capture,
                    targetRequestDurationMs = 5_000,
                ).use { input ->
                    val buffer = input.readNBytes(1024)
                    assertEquals(1024, buffer.size)
                    assertContentEquals(bytes.copyOfRange(0, 1024), buffer)

                    input.seekToMediaPosition(2048)
                    val remaining = input.readNBytes(2048)
                    assertEquals(2048, remaining.size)
                    assertContentEquals(bytes.copyOfRange(2048, 4096), remaining)

                    assertEquals(-1, input.read())
                }
                assertTrue(DirectPlaybackCache.awaitCompletion(resource, client))
                val destination = directory.resolve("seek-completed.audio")
                assertTrue(DirectPlaybackCache.copyComplete(resource, destination))
                assertContentEquals(bytes, destination.readBytes())
            } finally {
                capture.discard()
                directory.deleteRecursively()
            }
        }

    @Test
    fun forwardSeekGapsAreNotFilledIfPlaybackAbandonedBeforeEnd() =
        runBlocking {
            val directory = Files.createTempDirectory("playback-tee-test").toFile()
            val bytes = ByteArray(4096) { (it % 251).toByte() }
            val resource = resource("seek-abandoned", bytes.size.toLong())
            val capture = DirectPlaybackCache.begin(resource, directory, 8192)!!
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        respondWithBytes(chain, bytes)
                    }.build()
            try {
                HttpRangeInputStream(
                    client,
                    resource.url,
                    emptyMap(),
                    bytes.size.toLong(),
                    chunkSize = 1024,
                    cacheCapture = capture,
                ).use { input ->
                    val buffer = input.readNBytes(1024)
                    assertEquals(1024, buffer.size)

                    input.seekToMediaPosition(2048)
                    val chunk = input.readNBytes(512)
                    assertEquals(512, chunk.size)
                }
                assertFalse(DirectPlaybackCache.awaitCompletion(resource, client))
                assertFalse(DirectPlaybackCache.copyComplete(resource, directory.resolve("bad.audio")))
            } finally {
                DirectPlaybackCache.clear()
                directory.deleteRecursively()
            }
        }

    @Test
    fun capturedBytesAreServedWithoutNetwork() =
        runBlocking {
            val directory = Files.createTempDirectory("playback-tee-test").toFile()
            val bytes = ByteArray(4096) { (it % 251).toByte() }
            val resource = resource("local", bytes.size.toLong())
            val capture = DirectPlaybackCache.begin(resource, directory, 8192)!!
            var requests = 0
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        requests++
                        respondWithBytes(chain, bytes)
                    }.build()
            try {
                capture.record(0, bytes.copyOfRange(0, 1024), bytes.size.toLong())
                HttpRangeInputStream(
                    client,
                    resource.url,
                    emptyMap(),
                    bytes.size.toLong(),
                    chunkSize = 1024,
                    cacheCapture = capture,
                ).use { input ->
                    val buffer = ByteArray(1024)
                    assertEquals(1024, input.readAtMediaPosition(0, buffer, 0, buffer.size))
                    assertContentEquals(bytes.copyOfRange(0, 1024), buffer)
                    assertEquals(0, requests)
                }
                assertFalse(DirectPlaybackCache.awaitCompletion(resource))
                assertFalse(DirectPlaybackCache.copyComplete(resource, directory.resolve("bad.audio")))
            } finally {
                DirectPlaybackCache.clear()
                assertTrue(directory.listFiles().orEmpty().isEmpty())
                directory.deleteRecursively()
            }
        }

    @Test
    fun capturedPrefixDoesNotUpsizeTheFirstNetworkRequest() {
        val directory = Files.createTempDirectory("playback-cached-probe").toFile()
        val bytes = ByteArray(2_097_152) { (it % 251).toByte() }
        val resource = resource("cached-prefix", bytes.size.toLong())
        val capture = DirectPlaybackCache.begin(resource, directory, bytes.size.toLong())!!
        val ranges = mutableListOf<String>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    ranges += checkNotNull(chain.request().header("Range"))
                    respondWithBytes(chain, bytes)
                }.build()
        try {
            capture.record(0, bytes.copyOfRange(0, 524_288), bytes.size.toLong())
            HttpRangeInputStream(
                client,
                resource.url,
                emptyMap(),
                bytes.size.toLong(),
                chunkSize = 1_048_576,
                cacheCapture = capture,
                targetRequestDurationMs = 2_000,
            ).use { input ->
                assertContentEquals(bytes.copyOfRange(0, 524_288), input.readNBytes(524_288))
                assertTrue(ranges.isEmpty())
                assertEquals(524_288 % 251, input.read())
                assertEquals(listOf("bytes=524288-589823"), ranges)
            }
        } finally {
            capture.discard()
            directory.deleteRecursively()
        }
    }

    @Test
    fun incompleteCapturesStayReadableUntilCleared() =
        runBlocking {
            val directory = Files.createTempDirectory("playback-tee-test").toFile()
            try {
                val resource = resource("partial", 4096)
                val capture = DirectPlaybackCache.begin(resource, directory, 8192)!!
                capture.record(0, ByteArray(1024) { it.toByte() }, 4096)
                capture.record(2048, ByteArray(2048) { (it + 1).toByte() }, 4096)
                capture.close()
                assertFalse(DirectPlaybackCache.awaitCompletion(resource))
                assertEquals(1, directory.listFiles().orEmpty().size)
                assertNotNull(capture.read(0, 1024))
                assertEquals(null, capture.read(0, 2048))
            } finally {
                DirectPlaybackCache.clear()
                assertTrue(directory.listFiles().orEmpty().isEmpty())
                directory.deleteRecursively()
            }
        }

    @Test
    fun changedLengthsAndIdentitiesNeverPublish() =
        runBlocking {
            val directory = Files.createTempDirectory("playback-tee-test").toFile()
            try {
                val resource = resource("identity", 4096)
                val capture = DirectPlaybackCache.begin(resource, directory, 8192)!!
                capture.record(0, ByteArray(1024), 4096)
                capture.record(2048, ByteArray(2048), 5000)
                capture.close()
                assertFalse(DirectPlaybackCache.awaitCompletion(resource))
                assertFalse(DirectPlaybackCache.copyComplete(resource, directory.resolve("bad.audio")))

                val complete = resource("complete", 2048)
                DirectPlaybackCache.begin(complete, directory, 8192)!!.record(0, ByteArray(2048), 2048)
                assertTrue(DirectPlaybackCache.copyComplete(complete.copy(url = complete.url + "?other-format"), directory.resolve("good")))
                assertFalse(DirectPlaybackCache.copyComplete(complete.copy(itag = 141), directory.resolve("wrong-itag")))
            } finally {
                DirectPlaybackCache.clear()
                directory.deleteRecursively()
            }
        }

    @Test
    fun identityAndBudgetMustMatch() =
        runBlocking {
            val directory = Files.createTempDirectory("playback-tee-test").toFile()
            val resource = resource("budget", 2048)
            try {
                assertTrue(DirectPlaybackCache.begin(resource, directory, 1024) == null)
                val capture = DirectPlaybackCache.begin(resource, directory, 4096)!!
                capture.record(0, ByteArray(2048), 2048)
                assertTrue(DirectPlaybackCache.copyComplete(resource, directory.resolve("good")))
            } finally {
                DirectPlaybackCache.clear()
                directory.deleteRecursively()
            }
        }

    private fun respondWithBytes(
        chain: okhttp3.Interceptor.Chain,
        bytes: ByteArray,
    ): Response {
        val range =
            chain
                .request()
                .header("Range")!!
                .removePrefix("bytes=")
                .split('-')
        val start = range[0].toInt()
        val end = range[1].toInt()
        return Response
            .Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(206)
            .message("Partial Content")
            .header("Content-Range", "bytes $start-$end/${bytes.size}")
            .body(bytes.copyOfRange(start, end + 1).toResponseBody())
            .build()
    }

    private fun resource(
        id: String,
        size: Long,
    ) = PlaybackResource(
        videoId = id,
        url = "https://media.example.test/$id",
        headers = emptyMap(),
        loudnessDb = null,
        expiresAt = null,
        contentLengthBytes = size,
        mimeType = "audio/mp4",
        itag = 140,
        codecs = "mp4a.40.2",
    )
}
