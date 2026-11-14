package com.metrolist.shared.storage

import com.metrolist.shared.playback.IOS_OPUS_MAX_INPUT_BYTES
import com.metrolist.shared.youtube.playback.PlaybackResource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadThroughAudioTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun playbackReceivesBytesBeforeCompletionAndSurvivesCancelledPublication() =
        runTest {
            val audio = ByteArray(8192) { (it % 251).toByte() }
            val disk = ByteArray(audio.size)
            val body = ByteChannel(autoFlush = true)
            var requests = 0
            val client =
                HttpClient(MockEngine) {
                    engine {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler {
                            requests++
                            respond(
                                body,
                                HttpStatusCode.PartialContent,
                                headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes 0-8191/8192"),
                                    HttpHeaders.ContentLength to listOf(audio.size.toString()),
                                    HttpHeaders.ContentType to listOf("audio/mp4"),
                                ),
                            )
                        }
                    }
                }
            val source =
                ReadThroughAudio(
                    client,
                    PlaybackResource(
                        "song",
                        "https://rr1.googlevideo.com/videoplayback",
                        emptyMap(),
                        null,
                        null,
                        contentLengthBytes = audio.size.toLong(),
                        rangeChunkSizeBytes = audio.size.toLong(),
                    ),
                    audio.size.toLong(),
                    backgroundScope,
                    write = { offset, bytes, length -> bytes.copyInto(disk, offset.toInt(), 0, length) },
                    read = { offset, length -> disk.copyOfRange(offset.toInt(), offset.toInt() + length) },
                )
            try {
                val firstRead = async { source.readAt(0, 1024) }
                body.writeFully(audio, 0, 1024)
                body.flush()
                assertContentEquals(audio.copyOfRange(0, 1024), firstRead.await())
                var copied = false
                val publication = async { source.copyComplete { _, _ -> copied = true } }
                runCurrent()
                assertFalse(publication.isCompleted)
                assertFalse(copied)
                publication.cancelAndJoin()
                body.writeFully(audio, 1024, audio.size)
                body.close()
                source.copyComplete { _, _ -> assertContentEquals(audio, disk) }
                assertEquals(1, requests)
            } finally {
                source.close { }
                client.close()
            }
        }

    @Test
    fun playbackAndPublicationShareOneTransferIncludingOverlapsAndBackwardSeeks() =
        runTest {
            val fixture = Fixture(this)
            try {
                val source = fixture.source()
                assertEquals(fixture.audio.size.toLong(), source.information().second)
                assertContentEquals(fixture.audio.copyOfRange(1024, 2048), source.readAt(1024, 1024))
                listOf(4096L, 0L, 4096L, 2048L)
                    .map { offset ->
                        async {
                            assertContentEquals(
                                fixture.audio.copyOfRange(offset.toInt(), offset.toInt() + 1024),
                                source.readAt(offset, 1024),
                            )
                        }
                    }.awaitAll()
                source.copyComplete { _, size ->
                    assertEquals(fixture.audio.size.toLong(), size)
                    assertContentEquals(fixture.audio, fixture.disk)
                }
                assertEquals(listOf(0L, 4096L, 2048L, 6144L), fixture.starts)
                assertEquals(fixture.audio.size, fixture.transferred)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun completionFetchesOnlyHolesAfterForwardSeekAndDiscoversUnknownLength() =
        runTest {
            val fixture = Fixture(this)
            try {
                val source = fixture.source(knownLength = false)
                source.readAt(6144, 1024)
                assertEquals(listOf(0L, 6144L), fixture.starts)
                source.copyComplete { _, _ -> assertContentEquals(fixture.audio, fixture.disk) }
                assertEquals(listOf(0L, 6144L, 2048L, 4096L), fixture.starts)
                assertEquals(fixture.audio.size, fixture.transferred)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun fullResponseIsReusedWithoutMoreRangeRequests() =
        runTest {
            val fixture = Fixture(this, fullResponse = true)
            try {
                val source = fixture.source()
                source.information()
                assertContentEquals(fixture.audio.copyOfRange(6144, 7168), source.readAt(6144, 1024))
                source.copyComplete { _, _ -> assertContentEquals(fixture.audio, fixture.disk) }
                assertEquals(listOf(0L), fixture.starts)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun unknownLengthOverTheOpusLimitFailsBeforeWritingAudio() =
        runTest {
            val fixture = Fixture(this)
            fixture.reportedSize = (IOS_OPUS_MAX_INPUT_BYTES + 1).toInt()
            val resource = fixture.resource(knownLength = false).copy(mimeType = "audio/webm", codecs = "opus")
            val source =
                ReadThroughAudio(
                    fixture.client,
                    resource,
                    IOS_OPUS_MAX_INPUT_BYTES,
                    backgroundScope,
                    write = { _, _, _ -> error("Oversized input must fail before capture") },
                    read = { _, _ -> error("Oversized input must fail before playback") },
                )
            try {
                assertFailsWith<IllegalStateException> { source.information() }
                assertContentEquals(ByteArray(fixture.audio.size), fixture.disk)
            } finally {
                source.close {}
                fixture.client.close()
            }
        }

    @Test
    fun unboundedFullResponseFailsBeforeConsumingAudioSoNativePlaybackCanTakeOver() =
        runTest {
            val fixture = Fixture(this, fullResponse = true, lengthHeader = false)
            try {
                val source = fixture.source(knownLength = false)
                assertFailsWith<IllegalStateException> { source.information() }
                assertContentEquals(ByteArray(fixture.audio.size), fixture.disk)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun truncatedRangeCannotBecomeACompleteCaptureAndRetriesTheMissingRange() =
        runTest {
            val fixture = Fixture(this, truncateAt = 2048L)
            try {
                val source = fixture.source()
                source.information()
                var copied = false
                assertFailsWith<IllegalStateException> { source.copyComplete { _, _ -> copied = true } }
                assertFalse(copied)
                fixture.truncateAt = null
                source.copyComplete { _, _ -> assertContentEquals(fixture.audio, fixture.disk) }
                assertEquals(listOf(0L, 2048L, 2048L, 4096L, 6144L), fixture.starts)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun changedLengthAndClosedCapturesCannotPublish() =
        runTest {
            val fixture = Fixture(this)
            try {
                val source = fixture.source()
                source.information()
                fixture.reportedSize++
                assertFailsWith<IllegalStateException> { source.copyComplete { _, _ -> } }
                source.close { fixture.disk.fill(0) }
                assertFailsWith<IllegalStateException> { source.readAt(0, 1024) }
                assertFailsWith<IllegalStateException> { source.copyComplete { _, _ -> } }
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun invalidatedPublicationDoesNotFetchHolesOrCancelPlayback() =
        runTest {
            val fixture = Fixture(this)
            try {
                val source = fixture.source()
                source.information()
                assertFailsWith<IllegalStateException> {
                    source.copyComplete(mayContinue = { false }) { _, _ -> }
                }
                assertEquals(listOf(0L), fixture.starts)
                assertContentEquals(fixture.audio.copyOfRange(2048, 3072), source.readAt(2048, 1024))
                assertEquals(listOf(0L, 2048L), fixture.starts)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun resumedCaptureUsesFreshUrlWithoutChangingStoredChunkBoundaries() =
        runTest {
            val fixture = Fixture(this)
            try {
                var resource = fixture.resource().copy(url = "https://rr1.googlevideo.com/videoplayback?signature=old")
                val source = fixture.source(resourceForRequest = { resource })
                source.information()
                resource = resource.copy(url = "https://rr1.googlevideo.com/videoplayback?signature=new", rangeChunkSizeBytes = 4096)
                source.readAt(4096, 1024)
                source.copyComplete { _, _ -> assertContentEquals(fixture.audio, fixture.disk) }
                assertEquals(listOf<String?>("old", "new", "new", "new"), fixture.signatures)
                assertEquals(fixture.audio.size, fixture.transferred)
            } finally {
                fixture.client.close()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun ownerlessFullResponseIsCancelledAndCanResumeWithRanges() =
        runTest {
            val fixture = Fixture(this, fullResponse = true)
            val body = ByteChannel(autoFlush = true)
            fixture.pausedBody = body
            fixture.pauseAt = 0L
            val source = fixture.source()
            try {
                val firstRead = async { source.readAt(0, 1024) }
                body.writeFully(fixture.audio, 0, 4096)
                assertContentEquals(fixture.audio.copyOfRange(0, 1024), firstRead.await())
                assertEquals(4096L, source.pendingBytes)
                source.cancelPendingRequests()
                runCurrent()
                assertTrue(body.isClosedForRead)
                assertFailsWith<IllegalStateException> { source.readAt(0, 1024) }
                assertEquals(listOf(0L), fixture.starts)
                fixture.pauseAt = null
                fixture.fullResponse = false
                source.resumeRequests()
                assertContentEquals(fixture.audio.copyOfRange(6144, 7168), source.readAt(6144, 1024))
                source.readAt(2048, 1024)
                runCurrent()
                assertEquals(2048L, source.pendingBytes)
                source.copyComplete { _, _ -> assertContentEquals(fixture.audio, fixture.disk) }
                assertEquals(listOf(0L, 0L, 6144L, 2048L, 4096L), fixture.starts)
                assertEquals(0L, source.pendingBytes)
            } finally {
                source.close { }
                fixture.client.close()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationKeepsCompletedRangesAndReservesOnlyUnwrittenBytesAcrossRetries() =
        runTest {
            val fixture = Fixture(this)
            val body = ByteChannel(autoFlush = true)
            fixture.pausedBody = body
            fixture.pauseAt = 4096L
            val source = fixture.source()
            try {
                source.readAt(0, 1024)
                runCurrent()
                val seek = async { source.readAt(4096, 1024) }
                body.writeFully(fixture.audio, 4096, 5120)
                assertContentEquals(fixture.audio.copyOfRange(4096, 5120), seek.await())
                assertEquals(5120L, source.pendingBytes)
                source.cancelPendingRequests()
                runCurrent()
                assertTrue(body.isClosedForRead)
                fixture.pauseAt = null
                source.resumeRequests()
                assertContentEquals(fixture.audio.copyOfRange(0, 1024), source.readAt(0, 1024))
                assertEquals(listOf(0L, 4096L), fixture.starts)
                source.copyComplete { _, _ -> assertContentEquals(fixture.audio, fixture.disk) }
                assertEquals(listOf(0L, 4096L, 2048L, 4096L, 6144L), fixture.starts)
                assertEquals(0L, source.pendingBytes)
            } finally {
                source.close { }
                fixture.client.close()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun closingAnActiveCaptureCancelsItsResponseBeforeFurtherFileAccess() =
        runTest {
            val fixture = Fixture(this, fullResponse = true)
            val body = ByteChannel(autoFlush = true)
            fixture.pausedBody = body
            fixture.pauseAt = 0L
            val source = fixture.source()
            try {
                val firstRead = async { source.readAt(0, 1024) }
                body.writeFully(fixture.audio, 0, 1024)
                firstRead.await()
                source.close { fixture.disk.fill(0) }
                runCurrent()
                assertTrue(body.isClosedForRead)
                assertContentEquals(ByteArray(fixture.audio.size), fixture.disk)
                assertFailsWith<IllegalStateException> { source.copyComplete { _, _ -> } }
            } finally {
                source.close { }
                fixture.client.close()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellingBeforeResponseHeadersDoesNotPoisonMetadataOnResume() =
        runTest {
            val fixture = Fixture(this)
            val headersReady = CompletableDeferred<Unit>()
            fixture.beforeResponse = { headersReady.await() }
            val source = fixture.source(knownLength = false)
            try {
                val information = async { source.information() }
                runCurrent()
                source.cancelPendingRequests()
                information.cancelAndJoin()
                runCurrent()
                headersReady.complete(Unit)
                source.resumeRequests()
                source.copyComplete { _, _ -> assertContentEquals(fixture.audio, fixture.disk) }
                assertEquals(0L, source.pendingBytes)
            } finally {
                source.close { }
                fixture.client.close()
            }
        }

    private class Fixture(
        val scope: TestScope,
        var fullResponse: Boolean = false,
        val lengthHeader: Boolean = true,
        var truncateAt: Long? = null,
    ) {
        val audio = ByteArray(8192) { (it % 251).toByte() }
        val disk = ByteArray(audio.size)
        val starts = mutableListOf<Long>()
        val signatures = mutableListOf<String?>()
        var transferred = 0
        var reportedSize = audio.size
        var pauseAt: Long? = null
        var pausedBody: ByteChannel? = null
        var beforeResponse: suspend () -> Unit = {}
        val client =
            HttpClient(MockEngine) {
                followRedirects = false
                engine {
                    dispatcher = StandardTestDispatcher(scope.testScheduler)
                    addHandler { request ->
                        val (start, end) =
                            checkNotNull(
                                request.headers[HttpHeaders.Range],
                            ).removePrefix("bytes=").split('-').map(String::toInt)
                        starts.add(start.toLong())
                        signatures.add(request.url.parameters["signature"])
                        val bytes = if (fullResponse) audio else audio.copyOfRange(start, minOf(end + 1, audio.size))
                        transferred += bytes.size
                        beforeResponse()
                        respond(
                            if (pauseAt == start.toLong()) {
                                checkNotNull(pausedBody)
                            } else {
                                ByteReadChannel(if (truncateAt == start.toLong()) bytes.copyOf(bytes.size - 1) else bytes)
                            },
                            if (fullResponse) HttpStatusCode.OK else HttpStatusCode.PartialContent,
                            Headers.build {
                                append(HttpHeaders.ContentRange, "bytes $start-$end/$reportedSize")
                                if (lengthHeader) append(HttpHeaders.ContentLength, bytes.size.toString())
                                append(HttpHeaders.ContentType, "audio/mp4")
                            },
                        )
                    }
                }
            }

        fun resource(knownLength: Boolean = true) =
            PlaybackResource(
                "song",
                "https://rr1.googlevideo.com/videoplayback",
                emptyMap(),
                null,
                null,
                contentLengthBytes = if (knownLength) audio.size.toLong() else null,
                rangeChunkSizeBytes = 2048L,
            )

        fun source(
            knownLength: Boolean = true,
            resourceForRequest: () -> PlaybackResource = { resource(knownLength) },
        ) = ReadThroughAudio(
            client,
            resource(knownLength),
            audio.size.toLong(),
            scope.backgroundScope,
            write = { offset, bytes, length -> bytes.copyInto(disk, offset.toInt(), 0, length) },
            read = { offset, length -> disk.copyOfRange(offset.toInt(), offset.toInt() + length) },
            resourceForRequest = resourceForRequest,
        )
    }
}
