package com.metrolist.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.prepareGet
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingResponseTest {
    @Test
    fun chunkedOversizeIsCancelledBeforeTheWholeResponseIsDownloaded() =
        runBlocking {
            val channel = ByteChannel(autoFlush = true)
            val chunksWritten = AtomicInteger()
            val producer =
                launch {
                    try {
                        val chunk = ByteArray(1024) { 'x'.code.toByte() }
                        repeat(65_536) {
                            channel.writeFully(chunk)
                            chunksWritten.incrementAndGet()
                        }
                        channel.close()
                    } catch (error: Exception) {
                        if (!channel.isClosedForWrite) throw error
                    } finally {
                        channel.cancel(null)
                    }
                }
            try {
                HttpClient(MockEngine { respond(channel) }).use { client ->
                    val response = withTimeout(5_000) { client.prepareGet("https://example.test/body").readTextLimited(1024) }
                    assertNull(response.body)
                    assertTrue(chunksWritten.get() < 65_536)
                    assertTrue(channel.isClosedForWrite)
                }
            } finally {
                channel.cancel(null)
                producer.cancelAndJoin()
            }
        }

    @Test
    fun exactLimitRemainsUsable() =
        runBlocking {
            HttpClient(MockEngine { respond("1234") }).use { client ->
                assertEquals("1234", client.prepareGet("https://example.test/body").readTextLimited(4).body)
            }
        }
}
