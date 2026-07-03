package com.metrolist.music.utils.cipher

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Main cipher deobfuscation orchestrator for YouTube stream URLs.
 *
 * Handles both signature deobfuscation (for signatureCipher streams) and
 * n-parameter transformation (for throttle avoidance / 403 fix).
 */
object CipherDeobfuscator {
    private const val TAG = "Metrolist_CipherDeobfusc"

    lateinit var appContext: Context
        private set

    fun initialize(context: Context) {
        Timber.tag(TAG).d("CipherDeobfuscator initializing...")
        appContext = context.applicationContext
        // Load the player-config table (bundled asset + last-good cached remote overlay) so
        // configs exist before any lookup, then kick a non-blocking TTL-gated refresh against
        // the remote config file. Order is load-bearing: synchronous load first, async refresh after.
        Timber.tag(TAG).d("Initializing PlayerConfigStore (bundled + cached overlay)...")
        PlayerConfigStore.initialize(appContext)
        Timber.tag(TAG).d("Known config hashes after init: ${PlayerConfigStore.knownHashes().sorted().joinToString()}")
        PlayerConfigStore.scheduleStartupRefresh()
        // Cosmetic "cipher support added" dates for the song-details sheet — pulled purely from a
        // remote file and decoupled from the decipher path (any failure just yields an unknown date).
        PlayerDatesStore.initialize(appContext)
        Timber.tag(TAG).d("CipherDeobfuscator initialized")
    }

    private var cipherWebView: CipherWebView? = null

    // The PlayerConfigStore.configEpoch the cached WebView was built under. When the config table
    // changes (epoch advances), the cached WebView may have been built from a missing or wrong
    // config for the current player, so getOrCreateWebView() rebuilds it instead of trusting it for
    // the life of the process — the staleness that previously required an app restart to recover.
    private var builtConfigEpoch = -1

    // Written on the decipher coroutine (Dispatchers.IO) but read via lastUsedPlayerHash from the
    // Compose UI thread (song-details sheet), so @Volatile to publish the write across threads.
    @Volatile
    private var currentPlayerHash: String? = null

    /**
     * The player_ias hash last used to decipher a web stream (sig/n), or null if none yet.
     * Diagnostic only — surfaced in the song-details sheet. Direct-URL clients (ANDROID_VR/IOS)
     * never run the cipher, so this reflects the last web stream.
     */
    val lastUsedPlayerHash: String? get() = currentPlayerHash

    private val deobfuscateMutex = Mutex()

    // After repeated renderer deaths (low-RAM device under sustained memory pressure OOM-killing
    // the sandboxed renderer), skip re-parsing ~2.8 MB of player.js per song for a SHORT backoff
    // window. The window must stay short/half-open: the non-WebView fallbacks are unreliable, so
    // the cipher WebView is the primary path and we retry it as soon as pressure may have eased.
    // Guarded by deobfuscateMutex.
    private val rendererRecoveryPolicy = RendererRecoveryPolicy()

    /**
     * SignatureTimestamp of the player JS this cipher actually deciphers with, fetching (or
     * reusing the cached) player JS if needed. API callers must send THIS value in the
     * /player request: during A/B rollouts other sources (e.g. NewPipe's own player fetch)
     * can land on a different player generation, and a sig minted for one player but
     * deciphered by another produces a URL the CDN 403s.
     */
    suspend fun signatureTimestamp(): Int? {
        Timber.tag(TAG).d("Resolving cipher player signatureTimestamp...")
        val (playerJs, hash) = PlayerJsFetcher.getPlayerJs(forceRefresh = false) ?: run {
            Timber.tag(TAG).w("signatureTimestamp: could not fetch player JS")
            return null
        }
        val sts = FunctionNameExtractor.extractSignatureTimestamp(playerJs)
        Timber.tag(TAG).d("Cipher player STS (hash=$hash): $sts")
        return sts
    }

    /**
     * Best-effort: create the cipher WebView (fetch player JS + load it) ahead of first playback so
     * the deobfuscation hot path is already warm. Holds the same mutex as deobfuscateStreamUrl /
     * transformNParamInUrl so it can't race a real request for the shared single-WebView state. On
     * failure the WebView is simply created lazily on first use.
     */
    suspend fun prewarm() {
        Timber.tag(TAG).d("Prewarming cipher WebView...")
        deobfuscateMutex.withLock {
            try {
                getOrCreateWebView(forceRefresh = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CipherRendererGoneException) {
                onRendererGone(e, "prewarm")
            }
        }
    }

    /**
     * Deobfuscate a signatureCipher stream URL.
     *
     * The signatureCipher is a query string containing:
     * - s: The obfuscated signature
     * - sp: The signature parameter name (usually "sig" or "signature")
     * - url: The base stream URL
     *
     * Returns the full URL with deobfuscated signature, or null if failed.
     */
    suspend fun deobfuscateStreamUrl(signatureCipher: String, videoId: String): String? = deobfuscateMutex.withLock {
        try {
            deobfuscateInternal(signatureCipher, videoId, isRetry = false)
                ?.also { rendererRecoveryPolicy.onSuccess() }
        } catch (e: CancellationException) {
            throw e // request superseded/cancelled — propagate, don't treat as a decipher failure
        } catch (e: CipherRendererGoneException) {
            // Renderer died (OOM kill) — a fresh-JS retry would just re-parse ~2.8 MB under the
            // same memory pressure. Fail fast so playback falls through to the other paths.
            onRendererGone(e, "deobfuscate")
            null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Cipher deobfuscation failed, retrying with fresh JS: ${e.message}")
            try {
                PlayerJsFetcher.invalidateCache()
                closeWebView()
                deobfuscateInternal(signatureCipher, videoId, isRetry = true)
                    ?.also { rendererRecoveryPolicy.onSuccess() }
            } catch (retryE: CancellationException) {
                throw retryE
            } catch (retryE: CipherRendererGoneException) {
                onRendererGone(retryE, "deobfuscate-retry")
                null
            } catch (retryE: Exception) {
                Timber.tag(TAG).e(retryE, "Cipher deobfuscation retry also failed: ${retryE.message}")
                null
            }
        }
    }

    /**
     * Called when a deciphered stream URL was rejected by the CDN (e.g. a WEB_REMIX 403). A wrong
     * signature that the player JS computes WITHOUT throwing — a stale/wrong player config or a
     * legacy-regex false positive — is invisible to [deobfuscateStreamUrl]'s exception-retry, so
     * the rejected stream is the only signal it was wrong. Re-fetch the player-config table
     * (rate-limited); if it changes, [PlayerConfigStore.configEpoch] advances and the next decipher
     * rebuilds the WebView from the corrected config, recovering playback without an app restart.
     * Returns whether the config table changed.
     */
    suspend fun onStreamRejected(): Boolean = PlayerConfigStore.refreshAfterStreamRejection()

    private suspend fun deobfuscateInternal(signatureCipher: String, videoId: String, isRetry: Boolean): String? {
        Timber.tag(TAG).d("deobfuscateInternal: videoId=$videoId, isRetry=$isRetry")

        // Parse the signatureCipher query string
        val params = parseQueryParams(signatureCipher)
        val obfuscatedSig = params["s"]
        val sigParam = params["sp"] ?: "signature"
        val baseUrl = params["url"]

        Timber.tag(TAG).d("Parsed signatureCipher params:")
        Timber.tag(TAG).d("  s (obfuscated sig): ${obfuscatedSig?.take(30)}... (length=${obfuscatedSig?.length})")
        Timber.tag(TAG).d("  sp (sig param name): $sigParam")
        Timber.tag(TAG).d("  url: ${baseUrl?.take(80)}...")

        if (obfuscatedSig == null || baseUrl == null) {
            Timber.tag(TAG).e("Could not parse signatureCipher params: s=${obfuscatedSig != null}, url=${baseUrl != null}")
            return null
        }

        val webView = getOrCreateWebView(forceRefresh = isRetry)
        if (webView == null) {
            Timber.tag(TAG).e("Failed to get/create CipherWebView")
            return null
        }

        Timber.tag(TAG).d("Calling webView.deobfuscateSignature()...")
        val deobfuscatedSig = webView.deobfuscateSignature(obfuscatedSig)
        Timber.tag(TAG).d("Deobfuscated signature: ${deobfuscatedSig.take(30)}... (length=${deobfuscatedSig.length})")

        // Build the URL with deobfuscated signature
        val separator = if ("?" in baseUrl) "&" else "?"
        val finalUrl = "$baseUrl${separator}${sigParam}=${Uri.encode(deobfuscatedSig)}"

        Timber.tag(TAG).d("=== CIPHER DEOBFUSCATION SUCCESS ===")
        Timber.tag(TAG).d("videoId: $videoId")
        Timber.tag(TAG).d("Final URL length: ${finalUrl.length}")
        Timber.tag(TAG).d("Final URL preview: ${finalUrl.take(100)}...")

        return finalUrl
    }

    /**
     * Transform the 'n' parameter in a streaming URL to avoid throttling/403.
     *
     * Uses the runtime-discovered n-function from the player JS WebView.
     * Returns the URL with the transformed 'n' value, or the original URL if transform fails.
     *
     * IMPORTANT: This must be called for WEB_REMIX, WEB, WEB_CREATOR, TVHTML5 clients
     * and for privately owned tracks (uploaded songs).
     */
    suspend fun transformNParamInUrl(url: String): String = deobfuscateMutex.withLock {
        // Hold the same mutex as deobfuscateStreamUrl/prewarm: the shared CipherWebView has
        // single-shot continuation slots, so sig deciphering, n-transform, and warm-up must never
        // touch it concurrently (concurrent calls would clobber each other's WebView state).
        Timber.tag(TAG).d("=== N-TRANSFORM URL ===")
        Timber.tag(TAG).d("Input URL length: ${url.length}")
        Timber.tag(TAG).d("Input URL preview: ${url.take(100)}...")

        try {
            transformNInternal(url)
        } catch (e: CancellationException) {
            throw e // request superseded/cancelled — propagate rather than masking as a no-op transform
        } catch (e: CipherRendererGoneException) {
            onRendererGone(e, "n-transform")
            url
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "N-transform failed, returning original URL: ${e.message}")
            url
        }
    }

    private suspend fun transformNInternal(url: String): String {
        // Extract the 'n' parameter value from the URL
        val nMatch = Regex("[?&]n=([^&]+)").find(url)
        if (nMatch == null) {
            Timber.tag(TAG).d("No 'n' parameter found in URL, skipping transform")
            return url
        }

        val nValueEncoded = nMatch.groupValues[1]
        val nValue = Uri.decode(nValueEncoded)
        Timber.tag(TAG).d("N-param found:")
        Timber.tag(TAG).d("  encoded: $nValueEncoded")
        Timber.tag(TAG).d("  decoded: $nValue")

        val webView = getOrCreateWebView(forceRefresh = false)
        if (webView == null) {
            Timber.tag(TAG).e("Failed to get CipherWebView for n-transform")
            return url
        }

        Timber.tag(TAG).d("CipherWebView state:")
        Timber.tag(TAG).d("  nFunctionAvailable: ${webView.nFunctionAvailable}")
        Timber.tag(TAG).d("  discoveredNFuncName: ${webView.discoveredNFuncName}")
        Timber.tag(TAG).d("  usingHardcodedMode: ${webView.usingHardcodedMode}")

        if (!webView.nFunctionAvailable) {
            Timber.tag(TAG).e("N-transform function was not discovered at init time")
            return url
        }

        Timber.tag(TAG).d("Calling webView.transformN()...")
        val transformedN = webView.transformN(nValue)
        rendererRecoveryPolicy.onSuccess()

        Timber.tag(TAG).d("=== N-TRANSFORM SUCCESS ===")
        Timber.tag(TAG).d("N-param: $nValue -> $transformedN")

        // Replace n= parameter in URL
        val transformedUrl = url.replaceFirst(
            Regex("([?&])n=[^&]+"),
            "$1n=${Uri.encode(transformedN)}"
        )

        Timber.tag(TAG).d("Transformed URL length: ${transformedUrl.length}")
        return transformedUrl
    }

    /**
     * A renderer death (or renderer-gone-equivalent timeout) was detected: drop the dead
     * instance so the next call recreates it, and record the failure so repeated deaths open
     * the [rendererRecoveryPolicy] backoff window. Must be called with [deobfuscateMutex] held.
     */
    private suspend fun onRendererGone(e: CipherRendererGoneException, where: String) {
        rendererRecoveryPolicy.onFailure(SystemClock.elapsedRealtime())
        Timber.tag(TAG).e(
            e,
            "WebView renderer gone during $where (consecutive failures: " +
                "${rendererRecoveryPolicy.consecutiveFailures}) — dropping cipher WebView"
        )
        closeWebView()
    }

    private suspend fun getOrCreateWebView(forceRefresh: Boolean): CipherWebView? {
        Timber.tag(TAG).d("getOrCreateWebView: forceRefresh=$forceRefresh, existing=${cipherWebView != null}")

        // A dead renderer means the cached instance is a zombie — drop it so we rebuild below.
        if (cipherWebView?.isDead == true) {
            Timber.tag(TAG).w("Cached cipher WebView renderer is dead — discarding")
            closeWebView()
        }

        // Under sustained memory pressure the fresh renderer gets OOM-killed again seconds in;
        // during the (short, half-open) backoff window skip WebView creation so the current song
        // fails over fast instead of stalling on a doomed ~2.8 MB player.js parse. The next song
        // after the window retries the WebView — the fallback paths are too weak to live on.
        if (!rendererRecoveryPolicy.shouldAttempt(SystemClock.elapsedRealtime())) {
            Timber.tag(TAG).w(
                "Skipping cipher WebView creation: ${rendererRecoveryPolicy.consecutiveFailures} " +
                    "consecutive renderer deaths, in backoff window"
            )
            return null
        }

        // Snapshot the epoch BEFORE extracting/building. A refresh that lands on another thread
        // during this (multi-second) build then leaves builtConfigEpoch behind the live epoch,
        // forcing a rebuild on the next decipher instead of masking the change. Capturing the epoch
        // AFTER the build would record a config this WebView never actually incorporated — the
        // staleness this whole mechanism exists to prevent.
        val epochAtStart = PlayerConfigStore.configEpoch
        if (!forceRefresh && cipherWebView != null && builtConfigEpoch == epochAtStart) {
            Timber.tag(TAG).d("Reusing existing CipherWebView (hash=$currentPlayerHash)")
            return cipherWebView
        }

        // The epoch whose config this build incorporates. Defaults to the pre-build snapshot; the
        // heal path below advances it only after a same-thread forceRefresh whose new config we
        // re-extract and therefore HAVE incorporated (avoids a needless next rebuild).
        var builtEpoch = epochAtStart

        // Close existing WebView if any
        if (cipherWebView != null) {
            Timber.tag(TAG).d("Closing existing CipherWebView...")
            closeWebView()
        }

        // Fetch player JS
        Timber.tag(TAG).d("Fetching player JS...")
        val result = PlayerJsFetcher.getPlayerJs(forceRefresh = forceRefresh)
        if (result == null) {
            Timber.tag(TAG).e("Failed to get player JS")
            return null
        }
        val (playerJs, hash) = result
        Timber.tag(TAG).d("Got player JS: hash=$hash, length=${playerJs.length}")

        // Run full analysis for logging - pass the known hash from PlayerJsFetcher
        Timber.tag(TAG).d("Analyzing player JS for cipher functions (knownHash=$hash)...")
        var analysis = FunctionNameExtractor.analyzePlayerJs(playerJs, knownHash = hash)

        // Mid-session self-heal: a rotated player_ias whose validated config may already be
        // published in the remote config file. Trigger when EITHER transform is missing OR was
        // resolved by the legacy regex heuristics (isHardcoded == false) instead of a validated
        // config. The regexes are unanchored and can false-match anywhere in the ~2 MB player JS,
        // returning a non-null but WRONG result; gating on null alone would let that shadow the
        // validated config and silently break playback. forceRefresh returns true only when the
        // hash is now in the table, so re-extraction runs exactly when it can succeed; a genuine
        // old-style regex player with no config simply gets false back (one cooldown-gated fetch)
        // and keeps its working regex result.
        val sigFromConfig = analysis.sigInfo?.isHardcoded == true
        val nFromConfig = analysis.nFuncInfo?.isHardcoded == true
        if (!sigFromConfig || !nFromConfig) {
            Timber.tag(TAG).w("Extraction not fully config-backed for player $hash (sigConfig=$sigFromConfig, nConfig=$nFromConfig; sig=${analysis.sigInfo != null}, n=${analysis.nFuncInfo != null}) — forcing remote config refresh")
            val healed = PlayerConfigStore.forceRefresh(missingHash = hash)
            Timber.tag(TAG).d("forceRefresh($hash) -> hashNowKnown=$healed")
            if (healed) {
                analysis = FunctionNameExtractor.analyzePlayerJs(playerJs, knownHash = hash)
                builtEpoch = PlayerConfigStore.configEpoch
                Timber.tag(TAG).d("Re-extracted after refresh: sigConfig=${analysis.sigInfo?.isHardcoded == true}, nConfig=${analysis.nFuncInfo?.isHardcoded == true}")
            }
        }

        if (analysis.sigInfo == null) {
            Timber.tag(TAG).e("Could not extract signature function info from player JS")
            return null
        }

        if (analysis.nFuncInfo == null) {
            Timber.tag(TAG).w("Could not extract n-function info from player JS (will try brute-force)")
        }

        Timber.tag(TAG).d("Creating CipherWebView...")
        Timber.tag(TAG).d("  sig: ${analysis.sigInfo.name} (constantArg=${analysis.sigInfo.constantArg}, hardcoded=${analysis.sigInfo.isHardcoded})")
        Timber.tag(TAG).d("  nFunc: ${analysis.nFuncInfo?.name}[${analysis.nFuncInfo?.arrayIndex}] (hardcoded=${analysis.nFuncInfo?.isHardcoded})")

        // Create WebView
        val webView = CipherWebView.create(
            context = appContext,
            playerJs = playerJs,
            sigInfo = analysis.sigInfo,
            nFuncInfo = analysis.nFuncInfo,
        )

        Timber.tag(TAG).d("CipherWebView created successfully")
        Timber.tag(TAG).d("  nFunctionAvailable: ${webView.nFunctionAvailable}")
        Timber.tag(TAG).d("  sigFunctionAvailable: ${webView.sigFunctionAvailable}")
        Timber.tag(TAG).d("  discoveredNFuncName: ${webView.discoveredNFuncName}")

        cipherWebView = webView
        currentPlayerHash = hash
        builtConfigEpoch = builtEpoch
        return webView
    }

    private suspend fun closeWebView() {
        Timber.tag(TAG).d("closeWebView: existing=${cipherWebView != null}")
        withContext(Dispatchers.Main) {
            runCatching { cipherWebView?.close() }
                .onFailure { Timber.tag(TAG).w("closeWebView threw: $it") }
        }
        cipherWebView = null
        currentPlayerHash = null
        Timber.tag(TAG).d("CipherWebView closed and cleared")
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = Uri.decode(pair.substring(0, idx))
                val value = Uri.decode(pair.substring(idx + 1))
                result[key] = value
            }
        }
        Timber.tag(TAG).v("parseQueryParams: ${result.keys.joinToString()}")
        return result
    }

    /**
     * Debug method: Get current state information
     */
    fun getDebugInfo(): Map<String, Any?> {
        return mapOf(
            "hasWebView" to (cipherWebView != null),
            "playerHash" to currentPlayerHash,
            "nFunctionAvailable" to cipherWebView?.nFunctionAvailable,
            "sigFunctionAvailable" to cipherWebView?.sigFunctionAvailable,
            "discoveredNFuncName" to cipherWebView?.discoveredNFuncName,
            "usingHardcodedMode" to cipherWebView?.usingHardcodedMode,
        )
    }
}
