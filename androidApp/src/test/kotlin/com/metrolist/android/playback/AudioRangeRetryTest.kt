package com.metrolist.android.playback

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AudioRangeRetryTest {
    @Test
    fun interruptedChunkRetriesWithoutRestartingCompletedChunksOrDoubleCountingProgress() =
        runBlocking {
            val audio = ByteArray(3 * CHUNK_BYTES) { (it % 251).toByte() }
            val attempts = ConcurrentHashMap<Long, AtomicInteger>()
            val largestPrefix =
                ConcurrentHashMap<Long, AtomicLong>().apply {
                    repeat(3) { put(it.toLong() * CHUNK_BYTES, AtomicLong()) }
                }
            val transferred = AtomicLong()
            val firstCompleted = CountDownLatch(1)
            val singleRequests = AtomicInteger()
            val progress = mutableListOf<Pair<Long, Long>>()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val range = chain.request().header("Range")
                        if (range == null) {
                            singleRequests.incrementAndGet()
                            return@addInterceptor response(chain.request(), 200, audio.toResponseBody())
                        }
                        val (start, end) = range.removePrefix("bytes=").split('-').map(String::toLong)
                        val attempt = attempts.getOrPut(start) { AtomicInteger() }.incrementAndGet()
                        var received = 0L
                        val failing = start == CHUNK_BYTES.toLong() && attempt == 1
                        val prefixLimit = if (failing) 300 * 1024L else end - start + 1
                        val source =
                            object : Source {
                                override fun read(
                                    sink: Buffer,
                                    byteCount: Long,
                                ): Long {
                                    if (received == prefixLimit) {
                                        if (failing) {
                                            check(firstCompleted.await(10, TimeUnit.SECONDS))
                                            throw IOException("Connection reset during the second range")
                                        }
                                        if (start == 0L) firstCompleted.countDown()
                                        return -1L
                                    }
                                    val count = minOf(byteCount, prefixLimit - received).toInt()
                                    sink.write(audio, (start + received).toInt(), count)
                                    received += count
                                    transferred.addAndGet(count.toLong())
                                    largestPrefix.getValue(start).updateAndGet { maxOf(it, received) }
                                    return count.toLong()
                                }

                                override fun timeout() = Timeout.NONE

                                override fun close() = Unit
                            }.buffer()
                        val body =
                            object : ResponseBody() {
                                override fun contentType() = null

                                override fun contentLength() = end - start + 1

                                override fun source() = source
                            }
                        response(chain.request(), 206, body)
                            .newBuilder()
                            .header("Content-Range", "bytes $start-$end/${audio.size}")
                            .build()
                    }.build()
            withManager(client, audio.size) { manager, database, song, resource ->
                manager.downloadSong(song, resource, onProgress = { bytes, _ ->
                    synchronized(progress) { progress += bytes to largestPrefix.values.sumOf(AtomicLong::get) }
                })
                assertArrayEquals(audio, File(checkNotNull(database.dao().cachedAudio(song.id)).filePath).readBytes())
                assertEquals(0, singleRequests.get())
                assertEquals(mapOf(0L to 1, CHUNK_BYTES.toLong() to 2, 2L * CHUNK_BYTES to 1), attempts.mapValues { it.value.get() })
                assertEquals(audio.size + 300 * 1024L, transferred.get())
                assertEquals(audio.size.toLong(), progress.last().first)
                assertTrue(progress.all { (reported, uniqueBytes) -> reported <= uniqueBytes })
            }
        }

    @Test
    fun transportRetryLimitStillAllowsWholeFileFallback() =
        runBlocking {
            val audio = ByteArray(2 * CHUNK_BYTES) { (it % 251).toByte() }
            val attempts = ConcurrentHashMap<String, AtomicInteger>()
            val singleRequests = AtomicInteger()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val range = chain.request().header("Range")
                        if (range != null) {
                            attempts.getOrPut(range) { AtomicInteger() }.incrementAndGet()
                            throw IOException("Connection reset")
                        }
                        singleRequests.incrementAndGet()
                        response(chain.request(), 200, audio.toResponseBody())
                    }.build()
            withManager(client, audio.size) { manager, database, song, resource ->
                manager.downloadSong(song, resource)
                assertArrayEquals(audio, File(checkNotNull(database.dao().cachedAudio(song.id)).filePath).readBytes())
                assertEquals(1, singleRequests.get())
                assertTrue(attempts.values.all { it.get() <= 3 })
                assertEquals(3, attempts.values.maxOf(AtomicInteger::get))
            }
        }

    @Test
    fun unsupportedRangesFallBackWithoutRetryingEachRange() =
        runBlocking {
            val audio = ByteArray(2 * CHUNK_BYTES)
            val attempts = ConcurrentHashMap<String, AtomicInteger>()
            val singleRequests = AtomicInteger()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val range = chain.request().header("Range")
                        if (range ==
                            null
                        ) {
                            singleRequests.incrementAndGet()
                        } else {
                            attempts.getOrPut(range) { AtomicInteger() }.incrementAndGet()
                        }
                        response(chain.request(), 200, audio.toResponseBody())
                    }.build()
            withManager(client, audio.size) { manager, database, song, resource ->
                manager.downloadSong(song, resource)
                assertArrayEquals(audio, File(checkNotNull(database.dao().cachedAudio(song.id)).filePath).readBytes())
                assertEquals(1, singleRequests.get())
                assertTrue(attempts.isNotEmpty())
                assertTrue(attempts.values.all { it.get() == 1 })
            }
        }

    @Test
    fun cancelledTransferDoesNotRetryOrStartFallback() =
        runBlocking {
            val attempts = AtomicInteger()
            val control = AudioDownloadControl()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor {
                        attempts.incrementAndGet()
                        control.cancel()
                        throw IOException("Cancelled")
                    }.build()
            withManager(client, 2 * CHUNK_BYTES) { manager, database, song, resource ->
                var failed = false
                try {
                    manager.downloadSong(song, resource, control)
                } catch (_: DownloadTransferException) {
                    failed = true
                }
                assertTrue(failed)
                assertTrue(attempts.get() in 1..2)
                assertEquals(null, database.dao().cachedAudio(song.id))
            }
        }

    @Test
    fun storageFailuresDoNotRetryTheRange() =
        runBlocking {
            val audio = ByteArray(2 * CHUNK_BYTES)
            val attempts = ConcurrentHashMap<String, AtomicInteger>()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val range = chain.request().header("Range")
                        if (range != null) {
                            attempts.getOrPut(range) { AtomicInteger() }.incrementAndGet()
                            val temp = File(RuntimeEnvironment.getApplication().filesDir, "downloads/range-retry.tmp")
                            synchronized(attempts) {
                                if (temp.isFile) {
                                    check(temp.delete())
                                    check(temp.mkdir())
                                }
                            }
                            val (start, end) = range.removePrefix("bytes=").split('-').map(String::toInt)
                            response(chain.request(), 206, audio.copyOfRange(start, end + 1).toResponseBody())
                                .newBuilder()
                                .header("Content-Range", "bytes $start-$end/${audio.size}")
                                .build()
                        } else {
                            response(chain.request(), 200, audio.toResponseBody())
                        }
                    }.build()
            withManager(client, audio.size) { manager, _, song, resource ->
                var failed = false
                try {
                    manager.downloadSong(song, resource)
                } catch (_: DownloadTransferException) {
                    failed = true
                }
                assertTrue(failed)
                assertTrue(attempts.isNotEmpty())
                assertTrue(attempts.values.all { it.get() == 1 })
            }
        }

    private suspend fun withManager(
        client: OkHttpClient,
        bytes: Int,
        test: suspend (AudioCacheManager, MusicDatabase, SongItem, PlaybackResource) -> Unit,
    ) {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
        val song = SongItem("range-retry", "Track", null, listOf(ArtistTiny("artist", "Artist")), null)
        val resource =
            PlaybackResource(
                song.id,
                "https://media.example.test/audio",
                emptyMap(),
                null,
                null,
                contentLengthBytes = bytes.toLong(),
                mimeType = "audio/mpeg",
                requireBoundedRange = false,
                useRangeChunks = true,
                rangeChunkSizeBytes = CHUNK_BYTES.toLong(),
            )
        try {
            test(AudioCacheManager(context, database, client), database, song, resource)
        } finally {
            database.close()
            File(context.filesDir, "downloads").deleteRecursively()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            Settings.replaceSharedForTest(null)
        }
    }

    private fun response(
        request: Request,
        code: Int,
        body: ResponseBody,
    ) = Response
        .Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("fixture")
        .body(body)
        .build()

    private companion object {
        const val CHUNK_BYTES = 512 * 1024
    }
}
