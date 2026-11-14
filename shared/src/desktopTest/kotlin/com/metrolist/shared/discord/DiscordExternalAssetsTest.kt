package com.metrolist.shared.discord

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscordExternalAssetsTest {
    @Test
    fun concurrentFailuresBackOffAndHonorRateLimit() =
        runTest {
            var now = 0L
            var calls = 0
            val resolver = DiscordExternalAssetResolver { now }
            val client =
                HttpClient(
                    MockEngine {
                        calls++
                        respond("""{"retry_after":120}""", HttpStatusCode.TooManyRequests, headersOf("Content-Type", "application/json"))
                    },
                )
            try {
                List(10) { async { resolver.resolveAll(listOf("image"), "app", "token", client) } }.awaitAll()
                assertEquals(1, calls)
                now = 119_999
                resolver.resolveAll(listOf("image"), "app", "token", client)
                assertEquals(1, calls)
                now = 120_000
                resolver.resolveAll(listOf("image"), "app", "token", client)
                assertEquals(2, calls)
            } finally {
                client.close()
            }
        }

    @Test
    fun partialResponseKeepsResolvedImageAndBacksOffMissingImage() =
        runTest {
            var now = 0L
            var calls = 0
            val resolver = DiscordExternalAssetResolver { now }
            val client =
                HttpClient(
                    MockEngine {
                        calls++
                        respond(
                            """[{"url":"large","external_asset_path":"large-asset"}]""",
                            HttpStatusCode.OK,
                            headersOf("Content-Type", "application/json"),
                        )
                    },
                )
            try {
                assertEquals(mapOf("large" to "mp:large-asset"), resolver.resolveAll(listOf("large", "small"), "app", "token", client))
                assertEquals("mp:large-asset", resolver.peekCache("large"))
                now = 1_000
                assertEquals(mapOf("large" to "mp:large-asset"), resolver.resolveAll(listOf("large", "small"), "app", "token", client))
                assertEquals(1, calls)
                now = 30_000
                resolver.resolveAll(listOf("large", "small"), "app", "token", client)
                assertEquals(2, calls)
            } finally {
                client.close()
            }
        }
}
