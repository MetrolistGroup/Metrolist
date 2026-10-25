package com.metrolist.music.utils.potoken

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

class PoTokenGenerator(context: Context) {
    private val TAG = "PoTokenGenerator"
    private val applicationContext = context.applicationContext

    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
    private var webViewBadImpl = false // whether the system has a bad WebView implementation

    private val webPoTokenGenLock = Mutex()
    private var webPoTokenSessionId: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenWebView? = null

    suspend fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
        Timber.tag(TAG).d("WebView state: supported=$webViewSupported, badImpl=$webViewBadImpl")
        if (!webViewSupported || webViewBadImpl) {
            Timber.tag(TAG).d("WebView not available: supported=$webViewSupported, badImpl=$webViewBadImpl")
            return null
        }

        return try {
            withTimeout(POTOKEN_TIMEOUT_MS) {
                getWebClientPoToken(videoId, sessionId, forceRecreate = false)
            }
        } catch (e: TimeoutCancellationException) {
            // The WebView's sandboxed process can be culled by the OS (storage pressure, low
            // memory, etc.) which leaves the PoToken WebView call hung indefinitely. Cap it so
            // playerResponseForPlayback can fall through to non-PoToken fallback clients (e.g.
            // ANDROID_VR) instead of blocking the entire playback path.
            Timber.tag(TAG).w("poToken generation timed out after ${POTOKEN_TIMEOUT_MS}ms; proceeding without PoToken")
            clearGenerator()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: BadWebViewException) {
            Timber.tag(TAG).e("Could not obtain PO token because WebView is unavailable")
            webViewBadImpl = true
            null
        } catch (e: Exception) {
            Timber.tag(TAG).e("PO token generation failed type=${e::class.simpleName ?: "unknown"}")
            throw e
        }
    }

    suspend fun close() {
        clearGenerator()
    }

    private suspend fun clearGenerator() {
        webPoTokenGenLock.withLock {
            try {
                withContext(Dispatchers.Main) {
                    webPoTokenGenerator?.close()
                }
            } catch (error: Exception) {
                Timber.tag(TAG).e("PO token WebView cleanup failed type=${error::class.simpleName ?: "unknown"}")
            }
            webPoTokenGenerator = null
            webPoTokenStreamingPot = null
            webPoTokenSessionId = null
        }
    }

    private companion object {
        // Healthy cold-start (WebView spin-up + botguard JS + token gen) is ~2–5s in practice;
        // 8s leaves slack for a slow device without making the user wait too long before the
        // fallback chain (ANDROID_VR, etc.) takes over when the WebView hangs.
        const val POTOKEN_TIMEOUT_MS = 8_000L
    }

    /**
     * @param forceRecreate whether to force the recreation of [webPoTokenGenerator], to be used in
     * case the current [webPoTokenGenerator] threw an error last time
     * [PoTokenWebView.generatePoToken] was called
     */
    private suspend fun getWebClientPoToken(videoId: String, sessionId: String, forceRecreate: Boolean): PoTokenResult {
        val (poTokenGenerator, streamingPot, hasBeenRecreated) =
            webPoTokenGenLock.withLock {
                val shouldRecreate =
                    forceRecreate || webPoTokenGenerator == null || webPoTokenGenerator!!.isExpired ||
                        // Renderer died (OOM kill) — recreate proactively instead of letting the
                        // first post-crash generatePoToken() fail against the dead instance.
                        webPoTokenGenerator!!.isDead ||
                        webPoTokenSessionId != sessionId

                if (shouldRecreate) {
                    Timber.tag(TAG).d("Creating new PoTokenWebView (forceRecreate=$forceRecreate)")

                    withContext(Dispatchers.Main) {
                        webPoTokenGenerator?.close()
                    }

                    // Clear the committed state BEFORE the fallible steps below: if creation or
                    // the streaming-pot mint throws, the next call must compute
                    // shouldRecreate=true instead of pairing the already-updated sessionId with
                    // a null/stale streaming pot at the Triple below.
                    webPoTokenGenerator = null
                    webPoTokenStreamingPot = null
                    webPoTokenSessionId = null

                    val newGenerator = PoTokenWebView.getNewPoTokenGenerator(applicationContext)

                    // The streaming poToken needs to be generated exactly once before generating
                    // any other (player) tokens.
                    val newStreamingPot = try {
                        newGenerator.generatePoToken(sessionId)
                    } catch (t: Throwable) {
                        // Don't leak the freshly created WebView (close() hops to Main itself).
                        runCatching { newGenerator.close() }
                        throw t
                    }

                    webPoTokenGenerator = newGenerator
                    webPoTokenStreamingPot = newStreamingPot
                    webPoTokenSessionId = sessionId
                    Timber.tag(TAG).d("Streaming PO token generated")
                }

                Triple(webPoTokenGenerator!!, webPoTokenStreamingPot!!, shouldRecreate)
            }

        val playerPot = try {
            poTokenGenerator.generatePoToken(videoId)
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                // the poTokenGenerator has just been recreated (and possibly this is already the
                // second time we try), so there is likely nothing we can do
                throw throwable
            } else {
                // retry, this time recreating the [webPoTokenGenerator] from scratch;
                // this might happen for example if the app goes in the background and the WebView
                // content is lost
                Timber.tag(TAG).e("PO-token generation failed; recreating WebView")
                return getWebClientPoToken(videoId = videoId, sessionId = sessionId, forceRecreate = true)
            }
        }

        Timber.tag(TAG).d("PO token generated successfully")

        return PoTokenResult(
            playerRequestPoToken = streamingPot,
            streamingDataPoToken = playerPot,
        )
    }
}
