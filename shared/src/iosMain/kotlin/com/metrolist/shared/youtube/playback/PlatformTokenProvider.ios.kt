package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.youtube.logging.YtLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class IosTokenProvider : TokenProvider {
    private val client = HttpClient(Darwin) { install(HttpFailureLog) }
    private val mutex = Mutex()
    private val failureCooldown = TokenFailureCooldown()
    private var minter: PageBoundPoTokenMinter? = null
    private var minterVisitorData: String? = null
    private var minterCookie: String? = null
    private var playerRequestToken: String? = null

    override val capabilities: TokenProviderCapabilities
        get() =
            TokenProviderCapabilities(
                providers =
                    if (IosPageBoundTokenRuntime.available) {
                        setOf(PoTokenProviderKind.WEBPAGE_ATTESTATION)
                    } else {
                        emptySet()
                    },
                usesWebView = IosPageBoundTokenRuntime.available,
            )

    override suspend fun getPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? =
        mutex.withLock {
            if (failureCooldown.isCoolingDown(visitorData, cookie)) return@withLock null
            try {
                val activeMinter = getOrCreateMinter(visitorData, cookie, videoId)
                PoTokenResult(
                    playerRequestToken =
                        playerRequestToken
                            ?: activeMinter.generate(visitorData).also { playerRequestToken = it },
                    streamingDataToken = activeMinter.generate(videoId),
                    visitorData = visitorData,
                ).also { failureCooldown.reset() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                YtLogger.w("IosTokenProvider", "getPoToken failed type=${error::class.simpleName}")
                resetMinter()
                failureCooldown.recordFailure(visitorData, cookie)
                null
            }
        }

    override suspend fun prewarm(cookie: String?) = Unit

    override suspend fun close() {
        mutex.withLock {
            resetMinter()
            failureCooldown.reset()
            client.close()
        }
    }

    override suspend fun invalidateAttestation() {
        mutex.withLock {
            resetMinter()
            IosPageBoundTokenRuntime.reset()
        }
    }

    private suspend fun getOrCreateMinter(
        visitorData: String,
        cookie: String?,
        videoId: String,
    ): PageBoundPoTokenMinter {
        minter
            ?.takeIf { !it.isExpired && minterVisitorData == visitorData && minterCookie == cookie }
            ?.let { return it }
        resetMinter()
        return PageBoundPoTokenMinter
            .create(
                client = client,
                runtime = IosPageBoundTokenRuntime,
                visitorData = visitorData,
                cookie = cookie,
                videoId = videoId,
                userAgent = IOS_PAGE_BOUND_PO_TOKEN_USER_AGENT,
            ).also {
                minter = it
                minterVisitorData = visitorData
                minterCookie = cookie
            }
    }

    private fun resetMinter() {
        minter = null
        minterVisitorData = null
        minterCookie = null
        playerRequestToken = null
    }
}

@Suppress("FunctionName")
actual fun PlatformTokenProvider(): TokenProvider = IosTokenProvider()
