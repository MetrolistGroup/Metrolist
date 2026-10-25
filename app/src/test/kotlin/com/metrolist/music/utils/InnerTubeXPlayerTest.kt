package com.metrolist.music.utils

import com.metrolist.innertubex.extraction.PlayerConfig
import com.metrolist.innertubex.extraction.YtConfigParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
