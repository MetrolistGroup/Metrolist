package com.metrolist.music.playback

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ResolvedStreamRangeTest {
    private val stream = CachedStreamUrl("https://rr1---sn-test.googlevideo.com/videoplayback?id=1", emptyMap(), "WEB_REMIX")

    private fun spec(
        position: Long = 0,
        length: Long = C.LENGTH_UNSET.toLong(),
    ) = DataSpec
        .Builder()
        .setUri("song")
        .setKey("song")
        .setPosition(position)
        .setLength(length)
        .build()

    @Test
    fun `read from the start with unknown length asks for an open range`() {
        val resolved = spec().withResolvedStream(stream)

        assertEquals("bytes=0-", resolved.httpRequestHeaders["Range"])
    }

    @Test
    fun `reads that media3 already ranges are left alone`() {
        assertNull(spec(position = 1_000).withResolvedStream(stream).httpRequestHeaders["Range"])
        assertNull(spec(length = 4_096).withResolvedStream(stream).httpRequestHeaders["Range"])
    }

    @Test
    fun `url that already carries a range parameter is left alone`() {
        val ranged = stream.copy(url = stream.url + "&range=0-4095")

        assertNull(spec().withResolvedStream(ranged).httpRequestHeaders["Range"])
    }

    @Test
    fun `bounded chunk streams keep their subrange without an extra header`() {
        val chunked = stream.copy(requireBoundedRange = true, rangeChunkSizeBytes = 1_024)

        val resolved = spec().withResolvedStream(chunked)

        assertEquals(1_024, resolved.length)
        assertNull(resolved.httpRequestHeaders["Range"])
    }

    @Test
    fun `open range header reaches the wire through OkHttpDataSource`() {
        val seen = mutableListOf<Request>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    seen += chain.request()
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes 0-3/4")
                        .body(byteArrayOf(1, 2, 3, 4).toResponseBody())
                        .build()
                }.build()
        val source = OkHttpDataSource.Factory(client).createDataSource()

        val length = source.open(spec().withResolvedStream(stream))
        val buffer = ByteArray(8)
        val read = source.read(buffer, 0, buffer.size)
        source.close()

        assertEquals("bytes=0-", seen.single().header("Range"))
        assertEquals(4, length)
        assertEquals(4, read)
    }
}
