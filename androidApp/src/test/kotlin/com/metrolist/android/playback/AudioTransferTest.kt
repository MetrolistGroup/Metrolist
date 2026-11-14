package com.metrolist.android.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AudioTransferTest {
    @Test
    fun shortCleanResponsesCannotBePublished() {
        assertThrows(IllegalStateException::class.java) { verifyAudioLength(2048, 4096) }
        assertThrows(IllegalStateException::class.java) { verifyAudioLength(4097, 4096) }
        verifyAudioLength(4096, 4096)
        verifyAudioLength(4096, null)
    }

    @Test
    fun cancellationInterruptsBlockedHttpWithAndWithoutDownloadControl() =
        runBlocking {
            for ((useControl, blockBody) in listOf(false to false, true to false, false to true, true to true)) {
                val started = CompletableDeferred<Unit>()
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
                            fun waitForCancel(): Nothing {
                                started.complete(Unit)
                                check(cancelled.await(10, TimeUnit.SECONDS)) { "HTTP cancellation was not delivered" }
                                throw IOException("Cancelled")
                            }
                            if (!blockBody) waitForCancel()
                            val source =
                                object : Source {
                                    override fun read(
                                        sink: Buffer,
                                        byteCount: Long,
                                    ): Long = waitForCancel()

                                    override fun timeout(): Timeout = Timeout.NONE

                                    override fun close() = Unit
                                }.buffer()
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(
                                    object : ResponseBody() {
                                        override fun contentType() = null

                                        override fun contentLength() = 4096L

                                        override fun source() = source
                                    },
                                ).build()
                        }.build()
                val call = client.newCall(Request.Builder().url("https://media.example.test/audio").build())
                val control = if (useControl) AudioDownloadControl() else null
                val download =
                    async(Dispatchers.IO) {
                        runCatching { withCancellableAudioCall(call, control) { it.body.string() } }
                    }
                try {
                    withTimeout(10_000) {
                        started.await()
                        if (control != null) {
                            control.cancel()
                            download.await()
                        } else {
                            download.cancelAndJoin()
                        }
                    }
                    assertTrue(call.isCanceled())
                } finally {
                    call.cancel()
                    download.cancelAndJoin()
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdownNow()
                }
            }
        }
}
