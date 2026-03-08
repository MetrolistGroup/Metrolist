package com.metrolist.shared.playback.ffmpeg

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DirectMediaRedirectInterceptorTest {
    @Test
    fun followsCdnRedirectsWithoutLosingTheBoundedRange() {
        val requests = mutableListOf<Request>()
        val client = client(requests, "https://rr2.googlevideo.com/videoplayback?redirected=1")
        client.newCall(request()).execute().use { response ->
            assertEquals(206, response.code)
            assertEquals("media", response.body.string())
        }
        assertEquals(listOf("rr1.googlevideo.com", "rr2.googlevideo.com"), requests.map { it.url.host })
        assertEquals(listOf("bytes=10-14", "bytes=10-14"), requests.map { it.header("Range") })
    }

    @Test
    fun youtubeMediaEndpointsCanRedirectToTheCdn() {
        val requests = mutableListOf<Request>()
        val client = client(requests, "https://rr2.googlevideo.com/videoplayback?redirected=1")
        client.newCall(request().newBuilder().url("https://www.youtube.com/videoplayback").build()).execute().use {
            assertEquals(206, it.code)
        }
        assertEquals(listOf("www.youtube.com", "rr2.googlevideo.com"), requests.map { it.url.host })
    }

    @Test
    fun rejectsUntrustedRedirectsBeforeSendingHeadersToTheTarget() {
        for (target in listOf(
            "https://googlevideo.com.evil.test/videoplayback",
            "https://youtube.com.evil.test/videoplayback",
            "http://rr2.googlevideo.com/videoplayback",
            "https://rr2.googlevideo.com:444/videoplayback",
            "https://user@rr2.googlevideo.com/videoplayback",
            "https://rr2.googlevideo.com/another-path",
            "https://127.0.0.1/videoplayback",
        )) {
            val requests = mutableListOf<Request>()
            val client = client(requests, target)
            assertFailsWith<IOException> { client.newCall(request()).execute().close() }
            assertEquals(1, requests.size)
        }
    }

    @Test
    fun redirectLoopsAreBounded() {
        val requests = mutableListOf<Request>()
        val client = client(requests, "https://rr1.googlevideo.com/videoplayback")
        assertFailsWith<IOException> { client.newCall(request()).execute().close() }
        assertEquals(6, requests.size)
    }

    private fun request() =
        Request
            .Builder()
            .url("https://rr1.googlevideo.com/videoplayback")
            .header("Range", "bytes=10-14")
            .build()

    private fun client(
        requests: MutableList<Request>,
        target: String,
    ) = OkHttpClient
        .Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(DirectMediaRedirectInterceptor)
        .addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val redirected = request.url.queryParameter("redirected") == "1"
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (redirected) 206 else 302)
                .message(if (redirected) "Partial Content" else "Redirect")
                .header("Location", target)
                .body((if (redirected) "media" else "").toResponseBody())
                .build()
        }.build()
}
