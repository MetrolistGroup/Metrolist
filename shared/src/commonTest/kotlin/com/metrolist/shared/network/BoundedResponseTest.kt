package com.metrolist.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoundedResponseTest {
    @Test
    fun rejectsOversizedResponseWithoutContentLength() =
        runBlocking {
            val client = HttpClient(MockEngine { respond(ByteReadChannel("abcdef")) })
            assertNull(client.get("https://example.test").bodyAsTextLimited(5))
            client.close()
        }

    @Test
    fun acceptsResponseAtLimit() =
        runBlocking {
            val client = HttpClient(MockEngine { respond(ByteReadChannel("abcde")) })
            assertEquals("abcde", client.get("https://example.test").bodyAsTextLimited(5))
            client.close()
        }
}
