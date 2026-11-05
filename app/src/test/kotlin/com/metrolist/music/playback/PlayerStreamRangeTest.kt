package com.metrolist.music.playback

import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.file.Files

// Mirrors MusicService's player stack: ResolvingDataSource -> CacheDataSource -> OkHttpDataSource.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerStreamRangeTest {
    private val content = ByteArray(10) { it.toByte() }
    private val stream = CachedStreamUrl("https://rr1---sn-test.googlevideo.com/videoplayback?id=1", emptyMap(), "WEB_REMIX")
    private val sentRanges = mutableListOf<List<String>>()
    private lateinit var cache: SimpleCache

    // Serves `content` like googlevideo, honouring the first Range header it receives.
    private val server =
        Interceptor { chain ->
            val request = chain.request()
            val ranges = request.headers("Range")
            sentRanges += ranges
            val range = ranges.firstOrNull()?.removePrefix("bytes=")
            val start = range?.substringBefore('-')?.toInt() ?: 0
            val end = range?.substringAfter('-')?.takeIf { it.isNotEmpty() }?.toInt() ?: content.lastIndex
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (range == null) 200 else 206)
                .message("OK")
                .apply { if (range != null) header("Content-Range", "bytes $start-$end/${content.size}") }
                .body(content.copyOfRange(start, end + 1).toResponseBody())
                .build()
        }

    @Before
    fun setUp() {
        cache =
            SimpleCache(
                Files.createTempDirectory("player-cache").toFile(),
                NoOpCacheEvictor(),
                StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()),
            )
    }

    @After
    fun tearDown() {
        cache.release()
    }

    private val client =
        OkHttpClient
            .Builder()
            .addInterceptor(OpenRangeInterceptor)
            .addInterceptor(server)
            .build()

    private fun playerSource(): DataSource =
        ResolvingDataSource
            .Factory(
                CacheDataSource
                    .Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(client)),
            ) { it.withResolvedStream(stream) }
            .createDataSource()

    private fun send(request: Request) {
        client.newCall(request).execute().close()
    }

    private fun spec(length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec
            .Builder()
            .setUri("song")
            .setKey("song")
            .setLength(length)
            .build()

    private fun DataSource.readAll(spec: DataSpec): ByteArray {
        open(spec)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16)
        while (true) {
            val read = read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
        }
        close()
        return out.toByteArray()
    }

    @Test
    fun `uncached song is requested with an open range`() {
        val bytes = playerSource().readAll(spec())

        assertEquals(listOf(listOf("bytes=0-")), sentRanges)
        assertArrayEquals(content, bytes)
    }

    @Test
    fun `partly cached song continues from the cached end with a single range`() {
        val source = playerSource()
        source.readAll(spec(length = 4))
        sentRanges.clear()

        val bytes = source.readAll(spec())

        assertEquals(listOf(listOf("bytes=4-")), sentRanges)
        assertArrayEquals(content, bytes)
    }

    @Test
    fun `existing range header is kept as the only range`() {
        send(Request.Builder().url(stream.url).header("range", "bytes=2-5").build())

        assertEquals(listOf(listOf("bytes=2-5")), sentRanges)
    }

    @Test
    fun `url that already carries a range parameter is left alone`() {
        send(Request.Builder().url(stream.url + "&range=0-9").build())

        assertEquals(listOf(emptyList<String>()), sentRanges)
    }
}
