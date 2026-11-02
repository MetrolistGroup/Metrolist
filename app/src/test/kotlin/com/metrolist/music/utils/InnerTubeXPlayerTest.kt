package com.metrolist.music.utils

import com.metrolist.innertubex.extraction.PlayerConfig
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.YtConfigParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InnerTubeXPlayerTest {
    @After
    fun tearDown() {
        InnerTubeXPlayer.clearStreamClientFailures()
    }

    @Test
    fun `failed stream clients accumulate per video and expire`() {
        InnerTubeXPlayer.markStreamClientFailed("song", "VISIONOS", nowMs = 1_000L)
        InnerTubeXPlayer.markStreamClientFailed("song", "WEB_REMIX", nowMs = 2_000L)

        assertEquals(
            setOf("VISIONOS", "WEB_REMIX"),
            InnerTubeXPlayer.failedStreamClients("song", nowMs = 2_000L),
        )
        assertTrue(InnerTubeXPlayer.failedStreamClients("other", nowMs = 2_000L).isEmpty())
        assertTrue(InnerTubeXPlayer.failedStreamClients("song", nowMs = 302_000L).isEmpty())
    }

    @Test
    fun `exhausted alternatives retry rejected client at low quality`() =
        runBlocking {
            val attempts = mutableListOf<Pair<Set<String>, Boolean>>()
            val result = InnerTubeXPlayer.resolveWithClientFallback(setOf("WEB_REMIX")) { clients, lowQuality ->
                attempts += clients to lowQuality
                if (lowQuality) "recovered" else null
            }

            assertEquals("recovered", result)
            assertEquals(
                listOf(setOf("WEB_REMIX") to false, emptySet<String>() to true),
                attempts,
            )
        }

    @Test
    fun `no playable stream exception reopens rejected client`() =
        runBlocking {
            val attempts = mutableListOf<Pair<Set<String>, Boolean>>()
            val result = InnerTubeXPlayer.resolveWithClientFallback(setOf("WEB_REMIX")) { clients, lowQuality ->
                attempts += clients to lowQuality
                if (!lowQuality) {
                    throw StreamResolveException(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "no playable stream")
                }
                "recovered"
            }

            assertEquals("recovered", result)
            assertEquals(
                listOf(setOf("WEB_REMIX") to false, emptySet<String>() to true),
                attempts,
            )
        }

    @Test
    fun `network errors do not reopen rejected clients`() =
        runBlocking {
            val expected = StreamResolveException(StreamResolveException.Reason.NETWORK, "network unavailable")
            var attempts = 0
            try {
                InnerTubeXPlayer.resolveWithClientFallback(setOf("WEB_REMIX")) { _, _ ->
                    attempts++
                    throw expected
                }
                fail("Expected the network error to propagate")
            } catch (actual: StreamResolveException) {
                assertTrue(expected === actual)
            }
            assertEquals(1, attempts)
        }

    @Test
    fun `working alternate client is kept without retrying rejected one`() =
        runBlocking {
            var attempts = 0
            val result = InnerTubeXPlayer.resolveWithClientFallback(setOf("WEB_REMIX")) { clients, lowQuality ->
                attempts++
                assertEquals(setOf("WEB_REMIX"), clients)
                assertEquals(false, lowQuality)
                "alternate"
            }

            assertEquals("alternate", result)
            assertEquals(1, attempts)
        }

    @Test
    fun `normal resolution does not downgrade quality or repeat`() =
        runBlocking {
            var attempts = 0
            InnerTubeXPlayer.resolveWithClientFallback(emptySet()) { clients, lowQuality ->
                attempts++
                assertTrue(clients.isEmpty())
                assertEquals(false, lowQuality)
                "normal"
            }
            assertEquals(1, attempts)
        }

    @Test
    fun `failed watch page falls back to anonymous embedded config`() =
        runBlocking {
            val expected = PlayerConfig("embedded", 123, null, null)
            var embeddedUsesLogin = true
            val parser =
                object : YtConfigParser {
                    override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean): PlayerConfig =
                        error("HTTP 302")

                    override suspend fun fetchEmbeddedConfig(videoId: String, useLoginCookies: Boolean): PlayerConfig {
                        embeddedUsesLogin = useLoginCookies
                        return expected
                    }
                }

            assertEquals(expected, InnerTubeXPlayer.run { parser.withEmbeddedConfigFallback() }.fetchConfig("song", true))
            assertEquals(false, embeddedUsesLogin)
        }
}
