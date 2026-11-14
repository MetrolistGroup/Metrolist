package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.shared.playback.sabrAudioResource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SabrStreamingTokenTest {
    @Test
    fun streamingTokenUsesTheMediaVideoAndPlayerVisitorWithoutCookiesForNativeProfiles() =
        runTest {
            val client = HttpClient(MockEngine { error("No HTTP needed") })
            val innerTube =
                InnerTube(client).apply {
                    visitorData = "visitor"
                    cookie = "test-cookie"
                }
            val bootstrap = checkNotNull(sabrAudioResource().sabrBootstrap).copy(videoId = "media-video", tokenVisitorData = "visitor")
            val provider = Provider()
            try {
                val supplier = assertNotNull(sabrStreamingTokenSupplier(bootstrap, "VISIONOS_SABR__nopo", innerTube, provider))
                assertContentEquals(byteArrayOf(1, 2, 3), supplier())
                assertEquals(listOf(Triple<String, String, String?>("media-video", "visitor", null)), provider.requests)
                assertNull(sabrStreamingTokenSupplier(bootstrap.copy(poToken = byteArrayOf(4)), "VISIONOS_SABR__po", innerTube, provider))
                provider.kinds = emptySet()
                assertNull(sabrStreamingTokenSupplier(bootstrap, "VISIONOS_SABR__nopo", innerTube, provider))
                provider.kinds = setOf(PoTokenProviderKind.WEB_BOTGUARD)
                assertNull(sabrStreamingTokenSupplier(bootstrap, "VISIONOS_SABR__nopo", innerTube, provider))
            } finally {
                innerTube.close()
                client.close()
            }
        }

    @Test
    fun sessionChangesVisitorMismatchAndUnavailableMintsCannotSupplyTokens() =
        runTest {
            val client = HttpClient(MockEngine { error("No HTTP needed") })
            val innerTube = InnerTube(client).apply { visitorData = "visitor" }
            val bootstrap = checkNotNull(sabrAudioResource().sabrBootstrap).copy(tokenVisitorData = "visitor")
            val provider = Provider()
            try {
                provider.result = null
                assertNull(assertNotNull(sabrStreamingTokenSupplier(bootstrap, "VISIONOS_SABR__nopo", innerTube, provider))())
                provider.result = PoTokenResult("unused", "AQID", "another-visitor")
                assertNull(assertNotNull(sabrStreamingTokenSupplier(bootstrap, "VISIONOS_SABR__nopo", innerTube, provider))())
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                provider.result = PoTokenResult("unused", "AQID", "visitor")
                provider.wait = {
                    started.complete(Unit)
                    release.await()
                }
                val supplier = assertNotNull(sabrStreamingTokenSupplier(bootstrap, "VISIONOS_SABR__nopo", innerTube, provider))
                val result = async { supplier() }
                started.await()
                innerTube.cookie = "changed"
                release.complete(Unit)
                assertNull(result.await())
                val calls = provider.requests.size
                assertNull(supplier())
                assertEquals(calls, provider.requests.size)
            } finally {
                innerTube.close()
                client.close()
            }
        }

    private class Provider : TokenProvider {
        var kinds = setOf(PoTokenProviderKind.WEBPAGE_ATTESTATION)
        override val capabilities: TokenProviderCapabilities get() = TokenProviderCapabilities(kinds)
        val requests = mutableListOf<Triple<String, String, String?>>()
        var result: PoTokenResult? = PoTokenResult("unused-player-token", "AQID", "visitor")
        var wait: suspend () -> Unit = {}

        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?,
        ): PoTokenResult? {
            requests += Triple(videoId, visitorData, cookie)
            wait()
            return result
        }
    }
}
