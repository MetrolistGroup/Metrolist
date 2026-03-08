package com.metrolist.shared.api.lastfm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LastFmArtistResponseTest {
    @Test
    fun oversizedPagesAreRejectedWithoutWaitingForTheirBodies() =
        runBlocking {
            LastFM.initialize("api-key", "secret")
            val channels = mutableListOf<ByteChannel>()
            HttpClient(
                MockEngine {
                    val channel = ByteChannel(autoFlush = true)
                    channels += channel
                    respond(channel, headers = headersOf(HttpHeaders.ContentLength, "3000000"))
                },
            ).use { client ->
                try {
                    withTimeout(5_000) {
                        assertNull(LastFM.getArtistInfo(client, "Artist"))
                        assertNull(LastFM.getArtistTopTitles(client, "Artist"))
                        assertTrue(LastFM.getArtistPhotos(client, "Artist").isEmpty())
                        assertTrue(LastFM.scrapeArtistPage(client, "Artist").topTitles.isEmpty())
                    }
                    assertTrue(channels.isNotEmpty())
                    assertTrue(channels.all { it.isClosedForWrite })
                } finally {
                    channels.forEach { it.cancel(null) }
                }
            }
        }
}
