package com.metrolist.shared.playback.ffmpeg

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class LiveHttpRangeInputStreamTest {
    @Test
    fun transientInitialFailureRetriesWithinStartupBudget() {
        var requestCount = 0
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    requestCount++
                    if (requestCount == 1) throw IOException("transient startup failure")
                    response(chain, ByteArray(8) { it.toByte() }, contentRange = "bytes 0-7/8")
                }.build()
        try {
            HttpRangeInputStream(
                httpClient = client,
                url = "https://rr1.googlevideo.com/videoplayback",
                requestHeaders = emptyMap(),
                mediaSize = 8L,
                startupRequestTimeoutMs = 250L,
            ).use { input ->
                assertEquals(0, input.read())
                assertEquals(2, requestCount)
            }
        } finally {
            client.closeTestResources()
        }
    }

    @Test
    fun stalledInitialRangeRequestIsBounded() {
        val cancelled = CountDownLatch(1)
        val client =
            OkHttpClient
                .Builder()
                .eventListener(
                    object : EventListener() {
                        override fun canceled(call: Call) {
                            cancelled.countDown()
                        }
                    },
                ).addInterceptor { chain ->
                    check(cancelled.await(5, TimeUnit.SECONDS))
                    throw IOException("cancelled")
                }.build()
        try {
            val input =
                HttpRangeInputStream(
                    httpClient = client,
                    url = "https://rr1.googlevideo.com/videoplayback",
                    requestHeaders = emptyMap(),
                    mediaSize = 8L,
                    startupRequestTimeoutMs = 25L,
                )
            try {
                assertFailsWith<IOException> { input.read() }
                assertNotNull(input.streamFailure)
            } finally {
                input.close()
            }
        } finally {
            client.closeTestResources()
        }
    }

    private fun response(
        chain: okhttp3.Interceptor.Chain,
        body: ByteArray,
        contentRange: String? = null,
    ): Response =
        Response
            .Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(206)
            .message("Partial Content")
            .apply { contentRange?.let { header("Content-Range", it) } }
            .body(body.toResponseBody())
            .build()

    private fun OkHttpClient.closeTestResources() {
        connectionPool.evictAll()
        dispatcher.executorService.shutdownNow()
    }
}
