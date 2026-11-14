package com.metrolist.shared.youtube.playback

import android.content.Context
import android.os.SystemClock
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.shared.youtube.logging.YtLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidTokenProvider(
    context: Context,
) : TokenProvider {
    override val capabilities =
        TokenProviderCapabilities(
            providers = setOf(PoTokenProviderKind.WEB_BOTGUARD, PoTokenProviderKind.WEBPAGE_ATTESTATION),
            usesWebView = true,
        )

    private val mutex = Mutex()
    private val mintScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingMints = mutableMapOf<MintKey, Deferred<PoTokenResult?>>()
    private val resultCache = mutableMapOf<String, CachedResult>()
    private val remote by lazy { RemotePoTokenMinter(context) }
    private var minterVisitorData: String? = null
    private var minterCookie: String? = null
    private var unavailableVisitorData: String? = null
    private var unavailableCookie: String? = null
    private var unavailableUntilMs = 0L

    suspend fun prewarm() {
        withContext(Dispatchers.Main.immediate) {
            try {
                remote.prewarm()
            } catch (error: Exception) {
                remote.close()
                YtLogger.d(TAG, "PoToken prewarm failed type=${error::class.simpleName}")
            }
        }
    }

    override suspend fun getPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? =
        withContext(Dispatchers.Main.immediate) {
            awaitPoToken(videoId, visitorData, cookie)
        }

    private suspend fun awaitPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? {
        val key = MintKey(videoId, visitorData, cookie)
        while (true) {
            cachedResult(videoId, visitorData, cookie)?.let { return it }
            val request =
                pendingMints[key] ?: mintScope
                    .async(start = CoroutineStart.LAZY) {
                        mintPoToken(videoId, visitorData, cookie)
                    }.also { request ->
                        pendingMints[key] = request
                        request.invokeOnCompletion {
                            if (pendingMints[key] === request) pendingMints.remove(key)
                        }
                    }
            try {
                return request.await()
            } catch (_: CancellationException) {
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private suspend fun mintPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? =
        mutex.withLock {
            val nowMs = System.currentTimeMillis()
            resultCache.entries.removeAll { nowMs >= it.value.expiresAtMs }
            val elapsedNowMs = SystemClock.elapsedRealtime()
            cachedResult(videoId, visitorData, cookie)?.let { return@withLock it }
            if (
                visitorData == unavailableVisitorData &&
                cookie == unavailableCookie &&
                elapsedNowMs < unavailableUntilMs
            ) {
                YtLogger.d(TAG, "getPoToken unavailable cooldown hit target=video")
                return@withLock null
            }

            try {
                if (minterVisitorData != null && (minterVisitorData != visitorData || minterCookie != cookie)) resetMinter()
                val minted = remote.mint(videoId, visitorData, cookie)
                minterVisitorData = visitorData
                minterCookie = cookie
                val result = minted.result
                clearFailureCooldown()
                resultCache[videoId] =
                    CachedResult(
                        visitorData = visitorData,
                        cookie = cookie,
                        result = result,
                        expiresAtMs = minOf(nowMs + TOKEN_CACHE_TTL_MS, minted.expiresAtMs),
                    )
                YtLogger.d(
                    TAG,
                    "getPoToken success target=video elapsed=${SystemClock.elapsedRealtime() - elapsedNowMs}ms",
                )
                result
            } catch (error: CancellationException) {
                resetMinter()
                throw error
            } catch (error: Exception) {
                YtLogger.w(
                    TAG,
                    "getPoToken failed target=video elapsed=${SystemClock.elapsedRealtime() - elapsedNowMs}ms type=${error::class.simpleName}",
                )
                resetMinter()
                unavailableVisitorData = visitorData
                unavailableCookie = cookie
                unavailableUntilMs = SystemClock.elapsedRealtime() + FAILURE_COOLDOWN_MS
                null
            }
        }

    override suspend fun close() {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            pendingMints.values.toList().forEach { it.cancel() }
            pendingMints.clear()
            mutex.withLock {
                resetMinter()
                clearFailureCooldown()
            }
        }
    }

    override suspend fun invalidateAttestation() = close()

    private fun cachedResult(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? =
        resultCache[videoId]
            ?.takeIf { it.visitorData == visitorData && it.cookie == cookie && System.currentTimeMillis() < it.expiresAtMs }
            ?.result
            ?.also { YtLogger.d(TAG, "getPoToken cache hit target=video") }

    private fun resetMinter() {
        remote.close()
        minterVisitorData = null
        minterCookie = null
        resultCache.clear()
    }

    private fun clearFailureCooldown() {
        unavailableVisitorData = null
        unavailableCookie = null
        unavailableUntilMs = 0L
    }

    private data class MintKey(
        val videoId: String,
        val visitorData: String,
        val cookie: String?,
    )

    private data class CachedResult(
        val visitorData: String,
        val cookie: String?,
        val result: PoTokenResult,
        val expiresAtMs: Long,
    )

    companion object {
        private const val TAG = "AndroidTokenProvider"
        private const val TOKEN_CACHE_TTL_MS = 10L * 60L * 1000L
        private const val FAILURE_COOLDOWN_MS = 30_000L
    }
}
