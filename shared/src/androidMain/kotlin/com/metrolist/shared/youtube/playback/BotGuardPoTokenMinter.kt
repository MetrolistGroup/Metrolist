package com.metrolist.shared.youtube.playback

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import com.metrolist.innertubex.extraction.potoken.PoTokenException
import com.metrolist.innertubex.extraction.potoken.WebPageAttestationContext
import com.metrolist.innertubex.extraction.potoken.parseAttestationChallengeData
import com.metrolist.innertubex.extraction.potoken.parseAttestationInterpreterUrl
import com.metrolist.innertubex.extraction.potoken.parseIntegrityTokenData
import com.metrolist.innertubex.extraction.potoken.parseWebPageAttestationContext
import com.metrolist.innertubex.extraction.potoken.requireTrustedAttestationInterpreterUrl
import com.metrolist.innertubex.extraction.potoken.stringToU8
import com.metrolist.innertubex.extraction.potoken.u8ToBase64
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.youtube.logging.YtLogger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("SetJavaScriptEnabled")
internal class BotGuardPoTokenMinter private constructor(
    private val webView: WebView,
    private val visitorData: String,
    private val cookie: String?,
    private val challengeVideoId: String,
    private val initialization: CancellableContinuation<BotGuardPoTokenMinter>,
) {
    private val scope = MainScope()
    private val initializationCompleted = AtomicBoolean(false)
    private val requestCounter = AtomicLong()
    private val pendingTokens = ConcurrentHashMap<String, CancellableContinuation<String>>()
    private val exceptionHandler = CoroutineExceptionHandler { _, error -> failInitialization(error) }

    @Volatile
    var expiresAtMs = Long.MAX_VALUE
        private set

    @Volatile
    private var initializationStage = "asset"

    @Volatile
    private var closed = false

    @Volatile
    var isDead = false
        private set

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            userAgentString = USER_AGENT
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        webView.addJavascriptInterface(this, JS_INTERFACE)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient =
            object : WebViewClient() {
                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    isDead = true
                    val error = PoTokenException("BotGuard WebView renderer exited")
                    failInitialization(error)
                    failPendingTokens(error)
                    return true
                }
            }
    }

    val isExpired: Boolean
        get() = System.currentTimeMillis() >= expiresAtMs

    private fun initialize() {
        scope.launch(exceptionHandler) {
            val html =
                withContext(Dispatchers.IO) {
                    webView.context.assets
                        .open(ASSET_NAME)
                        .bufferedReader()
                        .use { it.readText() }
                }
            val data = html.replaceFirst("</script>", "\n$JS_INTERFACE.downloadAndRunBotguard()</script>")
            initializationStage = "bootstrap"
            webView.loadDataWithBaseURL("https://www.youtube.com", data, "text/html", "utf-8", null)
        }
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        initializationStage = "challenge-request"
        requestMusicWebPageChallenge(::runBotGuardChallenge)
    }

    private fun runBotGuardChallenge(attestation: WebPageAttestationContext) {
        initializationStage = "interpreter-request"
        requestInterpreter(parseAttestationInterpreterUrl(attestation.challenge)) { interpreterJavascript ->
            initializationStage = "challenge-javascript"
            val challengeData = parseAttestationChallengeData(attestation.challenge, interpreterJavascript)
            val eventId = Json.encodeToString(attestation.eventId)
            webView.evaluateJavascript(
                """
                try {
                    globalThis.yt = { config_: { EVENT_ID: $eventId } };
                    window.yt = globalThis.yt;
                    const data = $challengeData;
                    runBotGuard(data).then(function(result) {
                        globalThis.webPoSignalOutput = result.webPoSignalOutput;
                        $JS_INTERFACE.onRunBotguardResult(result.botguardResponse);
                    }).catch(function(error) {
                        $JS_INTERFACE.onJsInitializationError(String(error));
                    });
                } catch (error) {
                    $JS_INTERFACE.onJsInitializationError(String(error));
                }
                """.trimIndent(),
                null,
            )
        }
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        initializationStage = "integrity-request"
        requestBotGuard(
            url = "https://www.youtube.com/api/jnn/v1/GenerateIT",
            data = "[\"$REQUEST_KEY\",${Json.encodeToString(botguardResponse)}]",
        ) { responseBody ->
            initializationStage = "minter-javascript"
            val integrity = parseIntegrityTokenData(responseBody)
            expiresAtMs = System.currentTimeMillis() + (integrity.lifetimeSeconds - EXPIRY_MARGIN_SECONDS).coerceAtLeast(60L) * 1000L
            webView.evaluateJavascript(
                """
                try {
                    globalThis.integrityToken = ${integrity.tokenJavaScript};
                    createPoTokenMinter(webPoSignalOutput, integrityToken).then(function() {
                        $JS_INTERFACE.onMinterCreated();
                    }).catch(function(error) {
                        $JS_INTERFACE.onJsInitializationError(String(error));
                    });
                } catch (error) {
                    $JS_INTERFACE.onJsInitializationError(String(error));
                }
                """.trimIndent(),
                null,
            )
        }
    }

    @JavascriptInterface
    fun onJsInitializationProgress(stage: String) {
        if (stage in setOf("empty", "invalid", "factory", "await", "result")) {
            initializationStage = "minter-$stage"
        }
    }

    @JavascriptInterface
    fun onMinterCreated() {
        initializationStage = "ready"
        if (initializationCompleted.compareAndSet(false, true) && initialization.isActive) {
            initialization.resume(this)
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        initializationStage =
            when {
                error.contains("minter factory is unavailable") -> "minter-factory-unavailable"
                error.contains("minter is unavailable") -> "minter-unavailable"
                else -> "minter-evaluation"
            }
        failInitialization(PoTokenException("BotGuard JavaScript initialization failed"))
    }

    suspend fun generate(identifier: String): String {
        if (closed || isDead || isExpired) throw PoTokenException("BotGuard minter is unavailable")
        val requestKey = "${requestCounter.incrementAndGet()}"
        return try {
            withTimeout(GENERATE_TIMEOUT_MS.milliseconds) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { continuation ->
                        pendingTokens[requestKey] = continuation
                        continuation.invokeOnCancellation { pendingTokens.remove(requestKey) }
                        webView.evaluateJavascript(
                            """
                            (function() {
                                const requestKey = "$requestKey";
                                try {
                                    obtainPoToken(${stringToU8(identifier)}).then(function(token) {
                                        $JS_INTERFACE.onTokenResult(requestKey, token.join(","));
                                    }).catch(function(error) {
                                        $JS_INTERFACE.onTokenError(requestKey, String(error));
                                    });
                                } catch (error) {
                                    $JS_INTERFACE.onTokenError(requestKey, String(error));
                                }
                            })();
                            """.trimIndent(),
                            null,
                        )
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            isDead = true
            pendingTokens.remove(requestKey)
            throw PoTokenException("BotGuard token generation timed out")
        }
    }

    @JavascriptInterface
    fun onTokenResult(
        requestKey: String,
        tokenBytes: String,
    ) {
        val continuation = pendingTokens.remove(requestKey) ?: return
        runCatching { u8ToBase64(tokenBytes) }
            .onSuccess { continuation.resume(it) }
            .onFailure { continuation.resumeWithException(it) }
    }

    @JavascriptInterface
    fun onTokenError(
        requestKey: String,
        error: String,
    ) {
        pendingTokens.remove(requestKey)?.resumeWithException(PoTokenException(error))
    }

    private fun requestBotGuard(
        url: String,
        data: String,
        contentType: String = "application/json+protobuf",
        extraHeaders: Map<String, String> = emptyMap(),
        onSuccess: (String) -> Unit,
    ) {
        scope.launch(exceptionHandler) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .apply {
                        header("User-Agent", USER_AGENT)
                        header("Accept", "application/json")
                        header("Content-Type", contentType)
                        header("x-goog-api-key", GOOGLE_API_KEY)
                        header("x-user-agent", "grpc-web-javascript/0.1")
                        extraHeaders.forEach(::header)
                    }.post(data.toRequestBody(contentType.toMediaType()))
                    .build()
            val body =
                withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw PoTokenException("BotGuard request failed with HTTP ${response.code}")
                        }
                        response.body.readLimited(BOTGUARD_BODY_LIMIT_BYTES).takeIf { it.isNotBlank() }
                            ?: throw PoTokenException("BotGuard returned an empty response")
                    }
                }
            onSuccess(body)
        }
    }

    private fun requestMusicWebPageChallenge(onSuccess: (WebPageAttestationContext) -> Unit) {
        scope.launch(exceptionHandler) {
            val request =
                Request
                    .Builder()
                    .url(
                        requireNotNull(WEB_PAGE_URL.toHttpUrlOrNull())
                            .newBuilder()
                            .addQueryParameter("v", challengeVideoId)
                            .build(),
                    ).header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html")
                    .header("X-Goog-Visitor-Id", visitorData)
                    .header("Cookie", listOfNotNull(cookie, CONSENT_COOKIE).joinToString("; "))
                    .get()
                    .build()
            val webPage =
                withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw PoTokenException("BotGuard web page request failed with HTTP ${response.code}")
                        }
                        response.body.readLimited(WATCH_PAGE_BODY_LIMIT_BYTES).takeIf { it.isNotBlank() }
                            ?: throw PoTokenException("BotGuard web page request returned an empty response")
                    }
                }
            initializationStage = "challenge-parse"
            onSuccess(parseWebPageAttestationContext(webPage))
        }
    }

    private fun requestInterpreter(
        interpreterUrl: String,
        onSuccess: (String) -> Unit,
    ) {
        scope.launch(exceptionHandler) {
            val url =
                requireTrustedAttestationInterpreterUrl(interpreterUrl).toHttpUrlOrNull()
                    ?: throw PoTokenException("BotGuard returned an invalid interpreter URL")
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
            val body =
                withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw PoTokenException("BotGuard interpreter request failed with HTTP ${response.code}")
                        }
                        response.body.readLimited(INTERPRETER_BODY_LIMIT_BYTES).takeIf { it.isNotBlank() }
                            ?: throw PoTokenException("BotGuard returned an empty interpreter")
                    }
                }
            onSuccess(body)
        }
    }

    private fun failInitialization(error: Throwable) {
        close()
        if (initializationCompleted.compareAndSet(false, true) && initialization.isActive) {
            initialization.resumeWithException(error)
        }
    }

    private fun failPendingTokens(error: Throwable) {
        pendingTokens.values.forEach { continuation ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        pendingTokens.clear()
    }

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        failPendingTokens(PoTokenException("BotGuard minter closed"))
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyWebView()
        } else {
            webView.post { destroyWebView() }
        }
    }

    @MainThread
    private fun destroyWebView() {
        runCatching {
            webView.loadUrl("about:blank")
            webView.removeJavascriptInterface(JS_INTERFACE)
            webView.removeAllViews()
            webView.destroy()
        }
    }

    companion object {
        private const val TAG = "BotGuardPoTokenMinter"
        private const val ASSET_NAME = "po_token.html"
        private const val JS_INTERFACE = "PoTokenBridge"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val WEB_PAGE_URL = "https://www.youtube.com/watch"
        private const val CONSENT_COOKIE = "SOCS=CAI"
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val INITIALIZATION_TIMEOUT_MS = 17_000L
        private const val GENERATE_TIMEOUT_MS = 5_000L
        private const val EXPIRY_MARGIN_SECONDS = 600L
        private const val BOTGUARD_BODY_LIMIT_BYTES = 1 * 1024 * 1024
        private const val WATCH_PAGE_BODY_LIMIT_BYTES = 4 * 1024 * 1024
        private const val INTERPRETER_BODY_LIMIT_BYTES = 8 * 1024 * 1024
        private val httpClient =
            sharedOkHttpClient
                .newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

        suspend fun create(
            context: Context,
            visitorData: String,
            cookie: String?,
            videoId: String,
            prewarmedWebView: WebView? = null,
        ): BotGuardPoTokenMinter {
            var created: BotGuardPoTokenMinter? = null
            try {
                return withTimeout(INITIALIZATION_TIMEOUT_MS.milliseconds) {
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine { continuation ->
                            BotGuardPoTokenMinter(
                                prewarmedWebView ?: WebView(context.applicationContext),
                                visitorData,
                                cookie,
                                videoId,
                                continuation,
                            ).also { minter ->
                                created = minter
                                continuation.invokeOnCancellation { minter.close() }
                                minter.initialize()
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    created?.close() ?: prewarmedWebView?.destroy()
                }
                throw error
            } catch (error: Exception) {
                YtLogger.w(
                    TAG,
                    "BotGuard minter initialization failed stage=${created?.initializationStage ?: "create"} " +
                        "type=${error::class.simpleName}",
                )
                withContext(NonCancellable + Dispatchers.Main) {
                    created?.close() ?: prewarmedWebView?.destroy()
                }
                throw error
            }
        }
    }
}

private fun okhttp3.ResponseBody.readLimited(maxBytes: Int): String {
    check(contentLength() <= maxBytes.toLong() || contentLength() == -1L) {
        "Response exceeded the $maxBytes byte limit"
    }
    val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val buffer = Buffer()
    var total = 0L
    source().use { source ->
        while (true) {
            val count = source.read(buffer, 8 * 1024L)
            if (count == -1L) break
            total += count
            check(total <= maxBytes) { "Response exceeded the $maxBytes byte limit" }
            output.write(buffer.readByteArray())
        }
    }
    return output.toString(Charsets.UTF_8.name())
}
