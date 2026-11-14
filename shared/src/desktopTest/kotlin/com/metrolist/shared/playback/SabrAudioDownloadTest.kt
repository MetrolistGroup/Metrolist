package com.metrolist.shared.playback

import com.metrolist.innertubex.sabr.SabrChunk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SabrAudioDownloadTest {
    private fun chunk(
        start: Long,
        size: Int = 1024,
    ) = SabrChunk(ByteArray(size) { it.toByte() }, start, start == 0L, null, null, null)

    @Test
    fun unknownLengthStopsBeforeWritingAChunkBeyondTheConfiguredLimit() =
        runBlocking {
            val directory = Files.createTempDirectory("sabr-cache-limit").toFile()
            val file = directory.resolve("audio.webm")
            val progress = mutableListOf<Long>()
            try {
                assertFailsWith<IOException> {
                    writeSabrAudio(flowOf(chunk(0), chunk(1024)), file, null, maxBytes = 1500L, onProgress = {
                        bytes,
                        _,
                        ->
                        progress += bytes
                    })
                }
                assertEquals(listOf(1024L), progress)
                assertFalse(file.exists())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun downloadsOriginalAudioBytesAndReportsByteProgress() =
        runBlocking {
            val directory = Files.createTempDirectory("sabr-download").toFile()
            try {
                for (length in listOf(2048L, null)) {
                    val file = directory.resolve("audio.webm")
                    val progress = mutableListOf<Pair<Long, Long?>>()
                    writeSabrAudio(flowOf(chunk(0), chunk(1024)), file, length, onProgress = { bytes, total -> progress += bytes to total })
                    assertContentEquals(chunk(0).data + chunk(1024).data, file.readBytes())
                    assertEquals(listOf(1024L to length, 2048L to length), progress)
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun rejectsTruncationGapsOverlapsOversizeAndTransportFailuresWithoutLeavingFiles() =
        runBlocking {
            val directory = Files.createTempDirectory("sabr-download-failure").toFile()
            try {
                val file = directory.resolve("audio.webm")
                val invalid =
                    listOf(
                        flowOf(chunk(0)),
                        flowOf(chunk(0), chunk(1500, 548)),
                        flowOf(chunk(0), chunk(512)),
                        flowOf(chunk(0), chunk(1024, 1025)),
                        flow {
                            emit(chunk(0))
                            throw IOException("Transport failed")
                        },
                        flow {
                            emit(chunk(0))
                            emit(chunk(1024))
                            throw IOException("Final protocol validation failed")
                        },
                    )
                for (chunks in invalid) {
                    assertFailsWith<IOException> { writeSabrAudio(chunks, file, 2048L) }
                    assertFalse(file.exists())
                }
                assertFailsWith<IOException> { writeSabrAudio(flowOf(chunk(0, 1023)), file, null) }
                assertFalse(file.exists())
                assertFailsWith<IOException> { writeSabrAudio(flowOf(chunk(0)), file, 513L * 1024 * 1024) }
                assertFalse(file.exists())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun pausedDownloadWaitsBeforeWritingAndResumesWithoutLosingChunks() =
        runBlocking {
            val directory = Files.createTempDirectory("sabr-download-pause").toFile()
            val file = directory.resolve("audio.webm")
            val paused = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val progress = mutableListOf<Long>()
            try {
                val download =
                    launch {
                        writeSabrAudio(
                            flowOf(chunk(0), chunk(1024)),
                            file,
                            2048L,
                            awaitReady = {
                                paused.complete(Unit)
                                resume.await()
                            },
                            onProgress = { bytes, _ -> progress += bytes },
                        )
                    }
                withTimeout(5000) {
                    paused.await()
                    assertEquals(0L, file.length())
                    assertTrue(progress.isEmpty())
                    resume.complete(Unit)
                    download.join()
                }
                assertEquals(listOf(1024L, 2048L), progress)
                assertContentEquals(chunk(0).data + chunk(1024).data, file.readBytes())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun cancellationStopsStalledProducerAndDeletesPartialAudio() =
        runBlocking {
            val directory = Files.createTempDirectory("sabr-download-cancel").toFile()
            val file = directory.resolve("audio.webm")
            val started = CompletableDeferred<Unit>()
            var closed = false
            try {
                val download =
                    launch {
                        writeSabrAudio(
                            flow {
                                try {
                                    emit(chunk(0))
                                    started.complete(Unit)
                                    awaitCancellation()
                                } finally {
                                    closed = true
                                }
                            },
                            file,
                            2048L,
                        )
                    }
                withTimeout(5000) {
                    started.await()
                    download.cancelAndJoin()
                }
                assertTrue(closed)
                assertFalse(file.exists())
            } finally {
                directory.deleteRecursively()
            }
        }
}
