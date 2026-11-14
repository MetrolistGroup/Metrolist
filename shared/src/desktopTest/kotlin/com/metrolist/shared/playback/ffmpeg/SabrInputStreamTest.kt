package com.metrolist.shared.playback.ffmpeg

import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SabrInputStreamTest {
    @Test
    fun identicalRepeatAfterBackwardSeekReusesTheSameProducerAndCache() =
        runBlocking {
            val starts = CopyOnWriteArrayList<Long>()
            val firstRangeEmitted = CountDownLatch(1)
            val continueAfterSeek = CompletableDeferred<Unit>()
            val chunksEmitted = CompletableDeferred<Unit>()
            val input =
                SabrInputStream(
                    mediaSize = 10L,
                    streamFactory = { initial, _ ->
                        starts += initial
                        flow {
                            emit(chunk("z", 0))
                            emit(chunk("abc", 1))
                            firstRangeEmitted.countDown()
                            continueAfterSeek.await()
                            emit(chunk("ghi", 7))
                            emit(chunk("def", 4))
                            emit(chunk("abc", 1))
                            chunksEmitted.complete(Unit)
                        }
                    },
                )
            try {
                assertContentEquals("zabc".encodeToByteArray(), input.readNBytes(4))
                assertTrue(firstRangeEmitted.await(1, TimeUnit.SECONDS))
                input.beginPlaybackSeek(5_000L)
                input.seekToMediaPosition(1L)
                input.endPlaybackSeek(5_000L)
                continueAfterSeek.complete(Unit)
                withTimeout(5_000L) { chunksEmitted.await() }

                assertEquals(listOf(0L), starts)
                assertContentEquals("abcdefghi".encodeToByteArray(), input.readNBytes(9))
                assertNull(input.streamFailure)
            } finally {
                input.close()
            }
        }

    @Test
    fun overlappingMediaChunkWithDifferentBytesFailsWithoutReplacingCache() {
        val input =
            SabrInputStream(
                mediaSize = 10L,
                streamFactory = { _, _ ->
                    flowOf(
                        chunk("z", 0),
                        chunk("abc", 1),
                        chunk("X", 2),
                    )
                },
            )
        try {
            assertFailsWith<IOException> { input.readNBytes(10) }
            assertTrue(input.streamFailure is SabrProtocolException)
            val cached = ByteArray(4)
            assertEquals(4, input.readAtMediaPosition(0L, cached, 0, cached.size))
            assertContentEquals("zabc".encodeToByteArray(), cached)
        } finally {
            input.close()
        }
    }

    @Test
    fun changedInitializationChunkFailsBeforeOverwritingCachedHeader() {
        val input =
            SabrInputStream(
                mediaSize = 10L,
                streamFactory = { _, _ -> flowOf(chunk("z", 0), chunk("x", 0)) },
            )
        try {
            assertFailsWith<IOException> { input.readNBytes(10) }
            assertTrue(input.streamFailure is SabrProtocolException)
            val cached = ByteArray(1)
            assertEquals(1, input.readAtMediaPosition(0L, cached, 0, cached.size))
            assertContentEquals("z".encodeToByteArray(), cached)
        } finally {
            input.close()
        }
    }

    @Test
    fun retainedCompleteCaptureKeepsSharedBackingAfterEveryPlaybackReaderCloses() =
        runBlocking {
            val directory = Files.createTempDirectory("sabr-export").toFile()
            val original = SabrInputStream(6L, { _, _ -> flowOf(chunk("abcdef", 0)) }, tempDirectory = directory)
            val replacement = SabrInputStream(6L, { _, _ -> error("Fully cached") }, startImmediately = false)
            var capture: SabrInputStream.CompleteCapture? = null
            try {
                assertContentEquals("abcdef".encodeToByteArray(), original.readNBytes(6))
                replacement.shareCachedDataFrom(original)
                capture = assertNotNull(original.retainCompleteCapture())
                val channel = original.cacheChannelForTest()
                original.closeAsync().join()
                val output = directory.resolve("audio")
                assertTrue(capture.copyTo(output))
                assertContentEquals("abcdef".encodeToByteArray(), replacement.readNBytes(6))
                replacement.closeAsync().join()
                assertTrue(channel.isOpen)
                assertTrue(capture.copyTo(output))
                assertContentEquals("abcdef".encodeToByteArray(), output.readBytes())
                channel.truncate(5)
                assertFalse(capture.copyTo(output))
                capture.close()
                assertFalse(channel.isOpen)
                assertFalse(capture.copyTo(output))
            } finally {
                original.close()
                replacement.close()
                capture?.close()
                directory.deleteRecursively()
            }
        }

    @Test
    fun producerCompletionWithSparseCoverageAndUnknownLengthCannotBeExported() {
        for (size in listOf(6L, null)) {
            val input = SabrInputStream(size, { _, _ -> flowOf(chunk("ab", 0), chunk("ef", 4)) })
            try {
                val bytes = ByteArray(2)
                assertEquals(2, input.readAtMediaPosition(4, bytes, 0, 2))
                assertContentEquals("ef".encodeToByteArray(), bytes)
                assertNull(input.retainCompleteCapture())
            } finally {
                input.close()
            }
        }
    }

    @Test
    fun cancelledCacheReadDoesNotCloseBackingForRewindOrReplacement() {
        val original = SabrInputStream(6L, { _, _ -> flowOf(chunk("abcdef", 0)) })
        val replacement = SabrInputStream(6L, { _, _ -> error("Fully cached") }, startImmediately = false)
        try {
            assertContentEquals("abcdef".encodeToByteArray(), original.readNBytes(6))
            replacement.shareCachedDataFrom(original)
            val reader = Executors.newSingleThreadExecutor()
            try {
                reader
                    .submit {
                        Thread.currentThread().interrupt()
                        try {
                            original.readAtMediaPosition(0, ByteArray(3), 0, 3)
                        } catch (_: IOException) {
                        } finally {
                            Thread.interrupted()
                        }
                    }.get(2, TimeUnit.SECONDS)
            } finally {
                reader.shutdownNow()
            }
            original.seekToMediaPosition(0)
            assertContentEquals("abcdef".encodeToByteArray(), original.readNBytes(6))
            assertContentEquals("abcdef".encodeToByteArray(), replacement.readNBytes(6))
        } finally {
            original.close()
            replacement.close()
        }
    }

    @Test
    fun supersededFailureCannotCompleteReplacementProducer() {
        val input = SabrInputStream(mediaSize = null, streamFactory = { _, _ -> flow { awaitCancellation() } })
        try {
            val start =
                SabrInputStream::class.java.getDeclaredMethod("startProducer", Long::class.javaPrimitiveType).apply {
                    isAccessible =
                        true
                }
            val generation = SabrInputStream::class.java.getDeclaredField("producerGeneration").apply { isAccessible = true }
            val fail =
                SabrInputStream::class.java.getDeclaredMethod("recordFailure", Long::class.javaPrimitiveType, Throwable::class.java).apply {
                    isAccessible =
                        true
                }
            val completed = SabrInputStream::class.java.getDeclaredField("completed").apply { isAccessible = true }
            start.invoke(input, 0L)
            val previous = generation.getLong(input)
            start.invoke(input, 30_000L)
            for (failure in listOf(IOException("old request"), CancellationException("old request"))) {
                fail.invoke(input, previous, failure)
                assertNull(input.streamFailure)
                assertFalse(completed.getBoolean(input))
            }
            val failure = IOException("current request")
            fail.invoke(input, generation.getLong(input), failure)
            assertEquals(failure, input.streamFailure)
            assertTrue(completed.getBoolean(input))
        } finally {
            input.close()
        }
    }

    @Test
    fun stalledInitialProducerTimesOut() {
        val input =
            SabrInputStream(
                mediaSize = null,
                streamFactory = { _, _ -> flow { awaitCancellation() } },
                startupTimeoutMs = 50L,
            )
        try {
            val error = assertFailsWith<IOException> { input.read() }
            assertTrue(error.cause is SocketTimeoutException)
        } finally {
            input.close()
        }
    }

    @Test
    fun producerStallAfterInitialMediaTimesOut() {
        val input =
            SabrInputStream(
                mediaSize = null,
                streamFactory = { _, _ ->
                    flow {
                        emit(chunk("abc", 0))
                        awaitCancellation()
                    }
                },
                readTimeoutMs = 50L,
            )
        try {
            assertContentEquals("abc".encodeToByteArray(), input.readNBytes(3))
            val error = assertFailsWith<IOException> { input.read() }
            assertTrue(error.cause is SocketTimeoutException)
        } finally {
            input.close()
        }
    }

    @Test
    fun earlyCompletionOfUnknownLengthSabrStreamFailsInsteadOfEof() {
        val input =
            SabrInputStream(
                mediaSize = null,
                expectedDurationMs = 10_000L,
                streamFactory = { _, _ -> flowOf(chunk("a", 0), chunk("b", 1, startMs = 1_000L)) },
            )
        try {
            assertContentEquals("ab".encodeToByteArray(), input.readNBytes(2))

            val error = assertFailsWith<IOException> { input.read() }
            assertNotNull(input.streamFailure)
            assertEquals("SABR stream ended at 2000 ms before expected duration 10000 ms", error.cause?.message)
        } finally {
            input.close()
        }
    }

    @Test
    fun unknownLengthSabrStreamWithinDurationToleranceStillReturnsEof() {
        val input =
            SabrInputStream(
                mediaSize = null,
                expectedDurationMs = 3_000L,
                streamFactory = { _, _ -> flowOf(chunk("a", 0), chunk("b", 1, startMs = 1_000L)) },
            )
        try {
            assertContentEquals("ab".encodeToByteArray(), input.readNBytes(2))
            assertEquals(-1, input.read())
        } finally {
            input.close()
        }
    }

    @Test
    fun readsAndSeeksThroughGrowingCache() {
        val directory = Files.createTempDirectory("metrolist-sabr-input-test")
        lateinit var playbackPositionMs: () -> Long
        var producerStarts = 0
        val input =
            SabrInputStream(
                mediaSize = 6L,
                streamFactory = { _, position ->
                    producerStarts++
                    playbackPositionMs = position
                    flowOf(chunk("abc", 0), chunk("def", 3))
                },
                tempDirectory = directory.toFile(),
            )
        val cacheFile = input.cacheFileForTest()

        try {
            assertContentEquals("abcdef".encodeToByteArray(), input.readNBytes(6))
            assertEquals(1L, input.seekToMediaPosition(1L))
            assertContentEquals("bc".encodeToByteArray(), input.readNBytes(2))
            assertEquals(5L, input.seekToMediaPosition(5L))
            input.updatePlaybackPosition(5_000L)
            assertEquals(5_000L, playbackPositionMs())
            input.beginPlaybackSeek(5_500L)
            input.updatePlaybackPosition(100L)
            assertEquals(5_500L, playbackPositionMs())
            input.endPlaybackSeek(5_400L)
            assertEquals(5_400L, playbackPositionMs())
            assertEquals('f'.code, input.read())
            assertEquals(-1, input.read())
            assertEquals(1, producerStarts)
        } finally {
            input.close()
            assertFalse(cacheFile.exists())
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun closeUnblocksPendingReadAndDeletesCache() {
        val directory = Files.createTempDirectory("metrolist-sabr-input-close-test")
        val started = CountDownLatch(1)
        val input =
            SabrInputStream(
                mediaSize = null,
                streamFactory = { _, _ ->
                    flow {
                        started.countDown()
                        awaitCancellation()
                    }
                },
                tempDirectory = directory.toFile(),
            )
        val cacheFile = input.cacheFileForTest()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pendingRead = executor.submit<Int> { input.read() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            input.close()

            assertEquals(-1, pendingRead.get(2, TimeUnit.SECONDS))
            assertFalse(cacheFile.exists())
        } finally {
            input.close()
            executor.shutdownNow()
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun transientReconnectKeepsCachedBytesAndThePlaybackClock() {
        val starts = CopyOnWriteArrayList<Long>()
        val clocks = CopyOnWriteArrayList<Long>()
        val input =
            SabrInputStream(
                mediaSize = 6L,
                streamFactory = { initial, position ->
                    starts += initial
                    flow {
                        if (starts.size == 1) {
                            emit(chunk("abc", 0))
                            throw IOException("connection lost")
                        }
                        clocks += position()
                        emit(chunk("def", 3))
                    }
                },
            )
        try {
            assertContentEquals("abc".encodeToByteArray(), input.readNBytes(3))
            repeat(2) { assertFailsWith<IOException> { input.read() } }
            input.updatePlaybackPosition(42_345L)
            input.seekToMediaPosition(3L)
            assertContentEquals("def".encodeToByteArray(), input.readNBytes(3))
            assertEquals(listOf(0L, 32_345L), starts)
            assertEquals(listOf(42_345L), clocks)
            input.seekToMediaPosition(0L)
            assertContentEquals("abcdef".encodeToByteArray(), input.readNBytes(6))
            assertEquals(2, starts.size)
        } finally {
            input.close()
        }
    }

    @Test
    fun streamFailureIsNotReportedAsEndOfFile() {
        val input =
            SabrInputStream(
                mediaSize = null,
                streamFactory = { _, _ ->
                    flow {
                        throw IllegalStateException("transport failed")
                    }
                },
            )
        try {
            val error = assertFailsWith<IOException> { input.read() }
            assertEquals("transport failed", error.cause?.message)
            assertNotNull(input.streamFailure)
            assertFailsWith<IOException> { input.read() }
        } finally {
            input.close()
        }
    }

    @Test
    fun terminalSabrFailureSurvivesReopenAndCachedSeekWithoutRestarting() {
        for (kind in SabrFailureKind.entries) {
            var starts = 0
            val failure = SabrProtocolException("terminal", kind)
            val input =
                SabrInputStream(
                    mediaSize = 6L,
                    streamFactory = { _, _ ->
                        starts++
                        flow {
                            emit(chunk("abc", 0))
                            throw failure
                        }
                    },
                )
            try {
                assertContentEquals("abc".encodeToByteArray(), input.readNBytes(3))
                assertEquals(failure, assertFailsWith<IOException> { input.read() }.cause)
                repeat(3) {
                    input.seekToMediaPosition(3L)
                    assertEquals(failure, assertFailsWith<IOException> { input.read() }.cause)
                    input.seekToMediaPosition(0L)
                    assertContentEquals("abc".encodeToByteArray(), input.readNBytes(3))
                }
                assertEquals(1, starts)
                assertEquals(failure, input.streamFailure)
            } finally {
                input.close()
            }
        }
    }

    @Test
    fun cachedRandomAccessRemainsReadableAfterProducerFailure() {
        val producerFailed = java.util.concurrent.CountDownLatch(1)
        val input =
            SabrInputStream(
                mediaSize = null,
                streamFactory = { _, _ ->
                    flow {
                        emit(chunk("abc", 0))
                        producerFailed.countDown()
                        throw IllegalStateException("transport failed")
                    }
                },
            )
        try {
            assertTrue(producerFailed.await(1, TimeUnit.SECONDS))
            val cached = ByteArray(3)
            assertEquals(3, input.readAtMediaPosition(0, cached, 0, cached.size))
            assertContentEquals("abc".encodeToByteArray(), cached)
            assertFailsWith<IOException> { input.readAtMediaPosition(3, ByteArray(1), 0, 1) }
            assertEquals(3, input.readAtMediaPosition(0, cached, 0, cached.size))
        } finally {
            input.close()
        }
    }

    @Test
    fun ffmpegDecodesSeekableSabrInput() {
        val wav = silentWav(sampleRate = 8_000, samples = 8_000)
        val input =
            SabrInputStream(
                mediaSize = wav.size.toLong(),
                streamFactory = { _, _ ->
                    flowOf(
                        chunk(wav.copyOfRange(0, 256), 0),
                        chunk(wav.copyOfRange(256, wav.size), 256),
                    )
                },
            )
        val cacheFile = input.cacheFileForTest()
        val decoder = AudioDecoder(input, wav.size)
        try {
            assertEquals(8_000, decoder.sampleRate)
            assertNotNull(decoder.readPCMFrame())
            decoder.seekTo(500L)
            assertNotNull(decoder.readPCMFrame())
        } finally {
            decoder.close()
            assertFalse(cacheFile.exists())
        }
    }

    @Test
    fun uncachedSeekRestartsProducerAtPlaybackTargetAndWritesAbsoluteRange() {
        val starts = mutableListOf<Long>()
        val firstProducer = java.util.concurrent.CountDownLatch(1)
        val input =
            SabrInputStream(
                mediaSize = 12,
                seekPrerollMs = 3_000L,
                streamFactory = { initialPlayerTimeMs, _ ->
                    starts += initialPlayerTimeMs
                    flow {
                        when (initialPlayerTimeMs) {
                            0L -> {
                                emit(chunk("abc", 0))
                                firstProducer.countDown()
                            }

                            67_000L -> {
                                emit(chunk("XYZ", 6, startMs = 67_000L))
                            }

                            137_000L -> {
                                emit(chunk("123", 9, startMs = 137_000L))
                            }
                        }
                        awaitCancellation()
                    }
                },
            )
        try {
            assertTrue(firstProducer.await(1, TimeUnit.SECONDS))
            assertContentEquals("abc".encodeToByteArray(), input.readNBytes(3))
            input.updatePlaybackPosition(70_000L)
            assertEquals(6, input.seekToMediaPosition(6))
            assertContentEquals("XYZ".encodeToByteArray(), input.readNBytes(3))
            input.updatePlaybackPosition(140_000L)
            assertEquals(9, input.seekToMediaPosition(9))
            assertContentEquals("123".encodeToByteArray(), input.readNBytes(3))
            assertEquals(listOf(0L, 67_000L, 137_000L), starts)
        } finally {
            input.close()
        }
    }

    @Test
    fun cachedBackwardSeekRestartsSparseProducerBeforeTheReaderReachesTheGap() {
        for ((mediaSize, finishTail) in listOf(12L to false, 12L to true, null to false, null to true)) {
            val starts = CopyOnWriteArrayList<Long>()
            val input =
                SabrInputStream(
                    mediaSize = mediaSize,
                    readTimeoutMs = 250L,
                    streamFactory = { initial, _ ->
                        starts += initial
                        val restartedFromBeginning = starts.count { it == 0L } > 1
                        flow {
                            if (initial == 0L) {
                                emit(chunk(if (restartedFromBeginning) "abcdefghi" else "abc", 0))
                            } else {
                                emit(chunk("XYZ", 9, startMs = initial))
                            }
                            if (initial == 0L || !finishTail) awaitCancellation()
                        }
                    },
                )
            try {
                assertContentEquals("abc".encodeToByteArray(), input.readNBytes(3))
                input.updatePlaybackPosition(70_000L)
                input.seekToMediaPosition(9L)
                assertContentEquals("XYZ".encodeToByteArray(), input.readNBytes(3))

                input.seekToMediaPosition(0L)
                assertContentEquals("abc".encodeToByteArray(), input.readNBytes(3))
                assertEquals(listOf(0L, 60_000L), starts)

                input.updatePlaybackPosition(1_000L)
                input.seekToMediaPosition(1L)
                assertContentEquals("bcdefghiXYZ".encodeToByteArray(), input.readNBytes(11))
                assertEquals(listOf(0L, 60_000L, 0L), starts)

                input.updatePlaybackPosition(70_000L)
                input.seekToMediaPosition(9L)
                assertEquals('X'.code, input.read())
                input.updatePlaybackPosition(1_000L)
                input.seekToMediaPosition(1L)
                assertEquals('b'.code, input.read())
                assertEquals(listOf(0L, 60_000L, 0L), starts)
            } finally {
                input.close()
            }
        }
    }

    @Test
    fun equivalentUncachedSeeksReuseTheInFlightProducer() {
        val starts = CopyOnWriteArrayList<Long>()
        val initialProducer = java.util.concurrent.CountDownLatch(1)
        val seekProducer = java.util.concurrent.CountDownLatch(1)
        val input =
            SabrInputStream(
                mediaSize = 12,
                streamFactory = { initialPlayerTimeMs, _ ->
                    starts += initialPlayerTimeMs
                    flow {
                        if (initialPlayerTimeMs == 0L) initialProducer.countDown() else seekProducer.countDown()
                        awaitCancellation()
                    }
                },
            )
        try {
            assertTrue(initialProducer.await(1, TimeUnit.SECONDS))
            input.beginPlaybackSeek(70_000L)
            input.seekToMediaPosition(6)
            assertTrue(seekProducer.await(1, TimeUnit.SECONDS))

            input.endPlaybackSeek(71_000L)
            input.seekToMediaPosition(7)
            input.updatePlaybackPosition(72_000L)
            input.seekToMediaPosition(8)

            assertEquals(listOf(0L, 60_000L), starts)
        } finally {
            input.close()
        }
    }

    @Test
    fun continuousPlaybackDoesNotRestartActiveProducer() {
        val starts = CopyOnWriteArrayList<Long>()
        val input =
            SabrInputStream(
                mediaSize = 100,
                streamFactory = { initialPlayerTimeMs, _ ->
                    starts += initialPlayerTimeMs
                    flow {
                        emit(chunk("a".repeat(10), 0, startMs = 0L))
                        emit(chunk("b".repeat(10), 10, startMs = 10_000L))
                        emit(chunk("c".repeat(10), 20, startMs = 20_000L))
                        emit(chunk("d".repeat(10), 30, startMs = 30_000L))
                        awaitCancellation()
                    }
                },
            )
        try {
            assertContentEquals("a".repeat(10).encodeToByteArray(), input.readNBytes(10))
            input.updatePlaybackPosition(15_000L)
            assertContentEquals("b".repeat(10).encodeToByteArray(), input.readNBytes(10))
            input.updatePlaybackPosition(25_000L)
            assertContentEquals("c".repeat(10).encodeToByteArray(), input.readNBytes(10))
            input.updatePlaybackPosition(35_000L)
            assertContentEquals("d".repeat(10).encodeToByteArray(), input.readNBytes(10))

            assertEquals(listOf(0L), starts)
        } finally {
            input.close()
        }
    }

    @Test
    fun openingAtByteZeroKeepsDecoderStartupDataWhenResumingNearTheEnd() {
        val starts = CopyOnWriteArrayList<Long>()
        val initialStarted = CountDownLatch(1)
        val releaseInitial = CompletableDeferred<Unit>()
        val input =
            SabrInputStream(
                mediaSize = 12L,
                readTimeoutMs = 250L,
                streamFactory = { initial, _ ->
                    starts += initial
                    flow {
                        if (initial == 0L) {
                            initialStarted.countDown()
                            releaseInitial.await()
                            emit(chunk("abcdef", 0))
                        } else {
                            emit(chunk("abc", 0))
                            emit(chunk("XYZ", 9))
                        }
                        awaitCancellation()
                    }
                },
            )
        try {
            assertTrue(initialStarted.await(1, TimeUnit.SECONDS))
            input.updatePlaybackPosition(201_418L)
            input.seekToMediaPosition(0L)
            releaseInitial.complete(Unit)
            assertContentEquals("abcdef".encodeToByteArray(), input.readNBytes(6))
            assertEquals(listOf(0L), starts)
        } finally {
            input.close()
        }
    }

    @Test
    fun completedSparseStreamDoesNotRefetchTheSameTailForAMissingByte() {
        val starts = CopyOnWriteArrayList<Long>()
        val input =
            SabrInputStream(
                mediaSize = 12L,
                readTimeoutMs = 250L,
                streamFactory = { initial, _ ->
                    starts += initial
                    flowOf(chunk("abc", 0), chunk("XYZ", 9))
                },
            )
        try {
            assertContentEquals("abc".encodeToByteArray(), input.readNBytes(3))
            val error = assertFailsWith<IOException> { input.read() }
            assertFalse(error.cause is SocketTimeoutException)
            assertEquals(listOf(0L), starts)
            val tail = ByteArray(3)
            assertEquals(3, input.readAtMediaPosition(9L, tail, 0, tail.size))
            assertContentEquals("XYZ".encodeToByteArray(), tail)
        } finally {
            input.close()
        }
    }

    @Test
    fun uncachedSeekCanRestartACompletedSparseStream() {
        val starts = CopyOnWriteArrayList<Long>()
        val input =
            SabrInputStream(
                mediaSize = 12L,
                readTimeoutMs = 250L,
                streamFactory = { initial, _ ->
                    starts += initial
                    if (initial == 0L) {
                        flowOf(chunk("abc", 0), chunk("XYZ", 9))
                    } else {
                        flowOf(chunk("defghi", 3))
                    }
                },
            )
        try {
            assertContentEquals("abc".encodeToByteArray(), input.readNBytes(3))
            assertFailsWith<IOException> { input.read() }
            input.updatePlaybackPosition(70_000L)
            input.seekToMediaPosition(3L)
            assertContentEquals("defghiXYZ".encodeToByteArray(), input.readNBytes(9))
            assertEquals(listOf(0L, 60_000L), starts)
        } finally {
            input.close()
        }
    }

    @Test
    fun unknownLengthSparseStreamReportsGapsAndCanRestartAfterSeek() {
        val starts = CopyOnWriteArrayList<Long>()
        val input =
            SabrInputStream(
                mediaSize = null,
                readTimeoutMs = 250L,
                streamFactory = { initial, _ ->
                    starts += initial
                    if (initial == 0L) {
                        flowOf(chunk("abc", 0), chunk("XYZ", 9))
                    } else {
                        flowOf(chunk("defghi", 3))
                    }
                },
            )
        try {
            assertContentEquals("abc".encodeToByteArray(), input.readNBytes(3))
            val error = assertFailsWith<IOException> { input.read() }
            assertFalse(error.cause is SocketTimeoutException)
            val tail = ByteArray(3)
            assertEquals(3, input.readAtMediaPosition(9L, tail, 0, tail.size))
            assertContentEquals("XYZ".encodeToByteArray(), tail)
            input.updatePlaybackPosition(70_000L)
            input.seekToMediaPosition(3L)
            assertContentEquals("defghiXYZ".encodeToByteArray(), input.readNBytes(9))
            assertEquals(-1, input.read())
            assertEquals(listOf(0L, 60_000L), starts)
        } finally {
            input.close()
        }
    }

    @Test
    fun bufferedBytePositionTracksContiguousDownloadedChunks() {
        val input =
            SabrInputStream(
                mediaSize = 100L,
                streamFactory = { _, _ ->
                    flowOf(
                        chunk("a".repeat(10), 0),
                        chunk("b".repeat(20), 10),
                        chunk("c".repeat(20), 30),
                    )
                },
            )
        try {
            val buf = input.readNBytes(45)
            assertEquals(45, buf.size)
            assertEquals(45L, input.mediaPosition)
            assertEquals(50L, input.bufferedBytePosition)
        } finally {
            input.close()
        }
    }

    @Test
    fun replacementSharesBufferedAudioAndStartsAfterItsCachedPrefix() =
        runBlocking {
            val original =
                SabrInputStream(12L, { _, _ ->
                    flow {
                        emit(chunk("abc", 0))
                        emit(chunk("def", 3, 0).copy(durationMs = 10_000L))
                        emit(chunk("ghi", 6, 10_000L).copy(durationMs = 10_000L))
                        awaitCancellation()
                    }
                })
            val started = CompletableDeferred<Long>()
            val release = CompletableDeferred<Unit>()
            val starts = CopyOnWriteArrayList<Long>()
            val replacement =
                SabrInputStream(12L, { initial, _ ->
                    starts += initial
                    check(initial == 20_000L)
                    started.complete(initial)
                    flow {
                        release.await()
                        emit(chunk("jkl", 9, initial))
                    }
                }, startImmediately = false)
            try {
                assertContentEquals("abcdefghi".encodeToByteArray(), original.readNBytes(9))
                replacement.shareCachedDataFrom(original)
                original.close()
                replacement.updatePlaybackPosition(15_000L)
                replacement.seekToMediaPosition(0L)
                assertEquals(20_000L, withTimeout(5_000L) { started.await() })
                assertContentEquals("abc".encodeToByteArray(), replacement.readNBytes(3))
                replacement.seekToMediaPosition(3L)
                assertContentEquals("defghi".encodeToByteArray(), replacement.readNBytes(6))
                release.complete(Unit)
                assertContentEquals("jkl".encodeToByteArray(), replacement.readNBytes(3))
                assertEquals(listOf(20_000L), starts)
            } finally {
                original.close()
                replacement.close()
            }
        }

    @Test
    fun replacementKeepsSparseHolesAndFillsThemFromItsOwnStream() =
        runBlocking {
            val original =
                SabrInputStream(12L, { _, _ ->
                    flow {
                        emit(chunk("abc", 0))
                        emit(chunk("XYZ", 9, 90_000L))
                    }
                })
            val starts = CopyOnWriteArrayList<Long>()
            val replacement =
                SabrInputStream(12L, { initial, _ ->
                    starts += initial
                    flow { emit(chunk("defghi", 3)) }
                }, startImmediately = false)
            try {
                assertContentEquals("abc".encodeToByteArray(), original.readNBytes(3))
                val tail = ByteArray(3)
                assertEquals(3, original.readAtMediaPosition(9L, tail, 0, 3))
                replacement.shareCachedDataFrom(original)
                original.close()
                assertContentEquals("abcdefghiXYZ".encodeToByteArray(), replacement.readNBytes(12))
                assertEquals(listOf(0L), starts)
            } finally {
                original.close()
                replacement.close()
            }
        }

    @Test
    fun completedCacheReplacementNeedsNoNetworkEvenWithoutKnownLength() =
        runBlocking {
            for (size in listOf(3L, null)) {
                val original = SabrInputStream(size, { _, _ -> flow { emit(chunk("abc", 0)) } })
                val replacement =
                    SabrInputStream(size, { _, _ -> error("Completed audio must not be downloaded again") }, startImmediately = false)
                try {
                    assertContentEquals("abc".encodeToByteArray(), original.readNBytes(3))
                    assertEquals(-1, original.read())
                    replacement.shareCachedDataFrom(original)
                    original.close()
                    assertContentEquals("abc".encodeToByteArray(), replacement.readNBytes(3))
                    assertEquals(-1, replacement.read())
                } finally {
                    original.close()
                    replacement.close()
                }
            }
        }

    @Test
    fun sharedBackingHasIndependentCursorsAndSurvivesEitherCloseOrder() =
        runBlocking {
            for (closeOriginalFirst in listOf(true, false)) {
                val original = SabrInputStream(6L, { _, _ -> flowOf(chunk("abcdef", 0)) })
                val replacement = SabrInputStream(6L, { _, _ -> error("Fully cached") }, startImmediately = false)
                try {
                    assertContentEquals("abcdef".encodeToByteArray(), original.readNBytes(6))
                    replacement.shareCachedDataFrom(original)
                    assertEquals(original.cacheFileForTest(), replacement.cacheFileForTest())
                    val channel = original.cacheChannelForTest()
                    assertTrue(channel === replacement.cacheChannelForTest())
                    original.seekToMediaPosition(2)
                    assertEquals('a'.code, replacement.read())
                    assertEquals('c'.code, original.read())
                    val survivor = if (closeOriginalFirst) replacement else original
                    val retired = if (closeOriginalFirst) original else replacement
                    retired.closeAsync().join()
                    retired.close()
                    survivor.seekToMediaPosition(0)
                    assertContentEquals("abcdef".encodeToByteArray(), survivor.readNBytes(6))
                    assertTrue(channel.isOpen)
                    survivor.closeAsync().join()
                    assertFalse(channel.isOpen)
                } finally {
                    original.close()
                    replacement.close()
                }
            }
        }

    @Test
    fun constructionAndUnopenedDisposalDoNotTouchFilesystem() =
        runBlocking {
            val directory = Files.createTempDirectory("metrolist-lazy-sabr")
            try {
                val input =
                    SabrInputStream(
                        null,
                        { _, _ -> flow { awaitCancellation() } },
                        startImmediately = false,
                        tempDirectory = directory.resolve("missing").toFile(),
                    )
                input.closeAsync().join()
                assertEquals(0L, Files.list(directory).use { it.count() })
            } finally {
                Files.delete(directory)
            }
        }

    @Test
    fun suspendedProducerStopsAndResumesAtCurrentPlaybackWithoutTimingOutReaders() =
        runBlocking {
            val firstStopped = CompletableDeferred<Unit>()
            val restart = CompletableDeferred<Long>()
            val input =
                SabrInputStream(6L, { initial, _ ->
                    flow {
                        if (initial == 0L) {
                            try {
                                emit(chunk("abc", 0))
                                awaitCancellation()
                            } finally {
                                firstStopped.complete(Unit)
                            }
                        } else {
                            restart.complete(initial)
                            emit(chunk("def", 3, initial))
                        }
                    }
                }, readTimeoutMs = 50L)
            val executor = Executors.newSingleThreadExecutor()
            try {
                assertContentEquals("abc".encodeToByteArray(), input.readNBytes(3))
                input.suspendProducer()
                withTimeout(5_000) { firstStopped.await() }
                val pending = executor.submit<Int> { input.read() }
                assertFalse(pending.isDone)
                assertNull(input.streamFailure)
                input.resumeProducer(40_000)
                assertEquals(30_000L, withTimeout(5_000) { restart.await() })
                assertEquals('d'.code, pending.get(5, TimeUnit.SECONDS))
            } finally {
                input.closeAsync().join()
                executor.shutdownNow()
            }
        }

    private fun chunk(
        value: String,
        startRange: Long,
        startMs: Long = 0L,
    ) = chunk(value.encodeToByteArray(), startRange, startMs)

    private fun chunk(
        value: ByteArray,
        startRange: Long,
        startMs: Long = 0L,
    ) = SabrChunk(
        data = value,
        startRange = startRange,
        isInitialization = startRange == 0L,
        sequenceNumber = if (startRange == 0L) null else 1,
        startMs = if (startRange == 0L) null else startMs,
        durationMs = if (startRange == 0L) null else 1_000,
    )

    private fun silentWav(
        sampleRate: Int,
        samples: Int,
    ): ByteArray {
        val dataSize = samples * 2
        val bytes = ByteArray(44 + dataSize)

        fun putAscii(
            offset: Int,
            value: String,
        ) {
            value.toByteArray(Charsets.US_ASCII).copyInto(bytes, offset)
        }

        fun putInt(
            offset: Int,
            value: Int,
        ) {
            ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        }

        fun putShort(
            offset: Int,
            value: Int,
        ) {
            ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
        }

        putAscii(0, "RIFF")
        putInt(4, bytes.size - 8)
        putAscii(8, "WAVEfmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2)
        putShort(32, 2)
        putShort(34, 16)
        putAscii(36, "data")
        putInt(40, dataSize)
        return bytes
    }
}
