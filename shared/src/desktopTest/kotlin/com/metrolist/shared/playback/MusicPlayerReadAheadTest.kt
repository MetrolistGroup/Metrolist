package com.metrolist.shared.playback

import com.metrolist.shared.playback.ffmpeg.HttpRangeInputStream
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MusicPlayerReadAheadTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun directAudioFetchesBoundedRangesBeforeTheDecoderExhaustsItsCurrentRange() {
        verifyReadAhead(size = 600_000, resourceChunkSize = 1_048_576)
    }

    @Test
    fun readAheadRespectsSmallerClientRangesAndTheFinalShortRange() {
        verifyReadAhead(size = 70_123, resourceChunkSize = 16_384)
    }

    @Test
    fun shortDirectAudioEndsWithoutRequestingBeyondTheFile() {
        verifyReadAhead(size = 32, resourceChunkSize = 1_048_576)
    }

    private fun verifyReadAhead(
        size: Int,
        resourceChunkSize: Long,
    ) {
        val bytes = ByteArray(size) { (it % 251).toByte() }
        val ranges = CopyOnWriteArrayList<LongRange>()
        val ahead = CountDownLatch(1)
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val (start, end) = checkNotNull(chain.request().header("Range")).removePrefix("bytes=").split('-').map(String::toLong)
                    ranges += start..end
                    if (start > 0) ahead.countDown()
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes $start-$end/$size")
                        .body(bytes.copyOfRange(start.toInt(), end.toInt() + 1).toResponseBody())
                        .build()
                }.build()
        val player = MusicPlayer(mediaHttpClient = client)
        val resource =
            PlaybackResource("read-ahead", "https://media.example.test/audio", emptyMap(), null, null)
                .copy(contentLengthBytes = size.toLong(), rangeChunkSizeBytes = resourceChunkSize)
        try {
            val factory = MusicPlayer::class.java.getDeclaredMethod("httpRangeInputStream", PlaybackResource::class.java)
            factory.isAccessible = true
            val input = assertNotNull(factory.invoke(player, resource) as? HttpRangeInputStream)
            input.use {
                assertEquals(bytes[0].toInt() and 0xff, it.read())
                if (size > minOf(resourceChunkSize, 65_536)) {
                    assertTrue(ahead.await(5, TimeUnit.SECONDS), "The next range must start before another decoder read")
                }
                assertEquals(1L, it.mediaPosition, "Read ahead must not move the consumer cursor")
                assertContentEquals(bytes.copyOfRange(1, size), it.readBytes())
                assertEquals(-1, it.read())
                assertTrue(ranges.all { range -> range.last < size && range.last - range.first + 1 <= minOf(resourceChunkSize, 131_072) })
                it.seekToMediaPosition(23)
                assertContentEquals(bytes.copyOfRange(23, minOf(123, size)), it.readNBytes(100))
            }
        } finally {
            player.release()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
