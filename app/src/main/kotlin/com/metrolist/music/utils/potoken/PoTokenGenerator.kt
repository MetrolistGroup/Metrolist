package com.metrolist.music.utils.potoken

import android.webkit.CookieManager
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

class PoTokenGenerator {
    private val TAG = "PoTokenGenerator"

    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
    private var webViewBadImpl = false // whether the system has a bad WebView implementation

    private val webPoTokenGenLock = Mutex()
    private var webPoTokenSessionId: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenWebView? = null

    fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
        Timber.tag(TAG).d("getWebClientPoToken called: videoId=$videoId, sessionId=$sessionId")
        if (!webViewSupported || webViewBadImpl) {
            Timber.tag(TAG).d("WebView not available: supported=$webViewSupported, badImpl=$webViewBadImpl")
            return null
        }

        val deadlineMs = System.currentTimeMillis() + POTOKEN_TIMEOUT_MS
        repeat(POTOKEN_MAX_RETRIES) { attempt ->
            val remainingMs = deadlineMs - System.currentTimeMillis()
            if (remainingMs <= 0) {
                Timber.tag(TAG).w("PoToken deadline exceeded; aborting retry loop")
                return null
            }
            val isLastAttempt = attempt == POTOKEN_MAX_RETRIES - 1
            try {
                Timber.tag(TAG).d("PoToken attempt ${attempt + 1}/$POTOKEN_MAX_RETRIES (${remainingMs}ms remaining)")
                val result = runBlocking {
                    withTimeout(remainingMs) {
                        getWebClientPoToken(videoId, sessionId, forceRecreate = attempt > 0)
                    }
                }
                if (result != null) return result
                Timber.tag(TAG).w("PoToken returned null on attempt ${attempt + 1}; not retrying")
                return null
            } catch (e: TimeoutCancellationException) {
                Timber.tag(TAG).w("PoToken timed out on attempt ${attempt + 1}/${POTOKEN_MAX_RETRIES}")
                resetWebViewState()
                if (!isLastAttempt) {
                    Timber.tag(TAG).d("Retrying PoToken after timeout...")
                    return@repeat
                }
                return null
            } catch (e: BadWebViewException) {
                Timber.tag(TAG).e(e, "WebView broken; disabling PoToken")
                resetWebViewState()
                webViewBadImpl = true
                return null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "PoToken attempt ${attempt + 1} failed: ${e.message}")
                if (!isLastAttempt) {
                    Timber.tag(TAG).d("Retrying PoToken after failure...")
                    resetWebViewState()
                    return@repeat
                }
                throw e
            }
        }
        return null
    }

    private fun resetWebViewState() {
        runBlocking {
            webPoTokenGenLock.withLock {
                try {
                    withContext(Dispatchers.Main) {
                        webPoTokenGenerator?.close()
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Exception closing PoTokenWebView during reset")
                }
                webPoTokenGenerator = null
                webPoTokenStreamingPot = null
                webPoTokenSessionId = null
            }
        }
    }

    private companion object {
        // Healthy cold-start (WebView spin-up + botguard JS + token gen) is ~2–5s in practice;
        // 8s leaves slack for a slow device without making the user wait too long before the
        // fallback chain (ANDROID_VR, etc.) takes over when the WebView hangs.
        const val POTOKEN_TIMEOUT_MS = 8_000L
        const val POTOKEN_MAX_RETRIES = 2
    }

    /**
     * @param forceRecreate whether to force the recreation of [webPoTokenGenerator], to be used in
     * case the current [webPoTokenGenerator] threw an error last time
     * [PoTokenWebView.generatePoToken] was called
     */
    private suspend fun getWebClientPoToken(videoId: String, sessionId: String, forceRecreate: Boolean): PoTokenResult {
        Timber.tag(TAG).d("Web poToken requested: videoId=$videoId, sessionId=$sessionId")

        return webPoTokenGenLock.withLock {
            val shouldRecreate =
                forceRecreate || webPoTokenGenerator == null || webPoTokenGenerator!!.isExpired ||
                    webPoTokenGenerator!!.isDead ||
                    webPoTokenSessionId != sessionId

            if (shouldRecreate) {
                Timber.tag(TAG).d("Creating new PoTokenWebView (forceRecreate=$forceRecreate)")

                withContext(Dispatchers.Main) {
                    webPoTokenGenerator?.close()
                }

                webPoTokenGenerator = null
                webPoTokenStreamingPot = null
                webPoTokenSessionId = null

                val newGenerator = PoTokenWebView.getNewPoTokenGenerator(CipherDeobfuscator.appContext)

                val newStreamingPot = try {
                    newGenerator.generatePoToken(sessionId)
                } catch (t: Throwable) {
                    runCatching { newGenerator.close() }
                    throw t
                }

                webPoTokenGenerator = newGenerator
                webPoTokenStreamingPot = newStreamingPot
                webPoTokenSessionId = sessionId
                Timber.tag(TAG).d("Streaming poToken generated for sessionId=${sessionId.take(20)}...")
            }

            val generator = webPoTokenGenerator!!
            val streamingPot = webPoTokenStreamingPot!!

            val playerPot = try {
                generator.generatePoToken(videoId)
            } catch (throwable: Throwable) {
                if (shouldRecreate) {
                    throw throwable
                } else {
                    Timber.tag(TAG).e(throwable, "Failed to obtain poToken, retrying")
                    return@withLock getWebClientPoToken(videoId = videoId, sessionId = sessionId, forceRecreate = true)
                }
            }

            Timber.tag(TAG).d("poToken generated successfully: session=${streamingPot.take(20)}..., video=${playerPot.take(20)}...")

            PoTokenResult(
                playerRequestPoToken = streamingPot,
                streamingDataPoToken = playerPot,
            )
        }
    }
}
