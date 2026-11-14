@file:Suppress("FunctionName")

package com.metrolist.shared.youtube.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

internal const val IOS_PAGE_BOUND_PO_TOKEN_USER_AGENT =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

internal object IosPageBoundTokenRuntime : PageBoundPoTokenJavaScriptRuntime {
    private val mutex = Mutex()
    private var webView: WKWebView? = null

    override val available: Boolean
        get() = webView != null

    fun attach(view: WKWebView) {
        webView = view
    }

    fun detach(view: WKWebView) {
        if (webView === view) webView = null
    }

    override suspend fun evaluate(script: String): String =
        withTimeout(20_000L.milliseconds) {
            mutex.withLock {
                withContext(Dispatchers.Main) {
                    val view = checkNotNull(webView) { "iOS page-bound WebView is unavailable" }
                    suspendCancellableCoroutine { continuation ->
                        view.evaluateJavaScript(script) { result, error ->
                            continuation.completeJavaScriptEvaluation(result, error)
                        }
                    }
                }
            }
        }

    override suspend fun reset() {
        if (!available) return
        evaluate(
            """
            globalThis.bgVmFunctions = null;
            globalThis.poTokenMinter = null;
            delete globalThis.__metrolistWebPoSignalOutput;
            delete globalThis.__metrolistPoSnapshot;
            delete globalThis.__metrolistPoMinter;
            delete globalThis.__metrolistPoToken;
            "reset";
            """.trimIndent(),
        )
    }
}

internal fun CancellableContinuation<String>.completeJavaScriptEvaluation(
    result: Any?,
    error: NSError?,
) {
    if (!isActive) return
    if (error != null) {
        resumeWithException(IllegalStateException("WebKit evaluation failed ${error.domain}:${error.code}"))
    } else {
        resume(result as? String ?: result?.toString().orEmpty())
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun IosPageBoundTokenHost() {
    val scope = rememberCoroutineScope()
    val delegate = remember(scope) { IosPageBoundNavigationDelegate(scope) }
    Box(Modifier.size(1.dp).alpha(0f)) {
        UIKitView(
            factory = {
                val configuration =
                    WKWebViewConfiguration().apply {
                        websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
                        userContentController.addUserScript(
                            WKUserScript(
                                source = PAGE_BOUND_TOKEN_SCRIPT,
                                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                                forMainFrameOnly = true,
                            ),
                        )
                    }
                WKWebView(CGRectMake(0.0, 0.0, 1.0, 1.0), configuration).apply {
                    customUserAgent = IOS_PAGE_BOUND_PO_TOKEN_USER_AGENT
                    navigationDelegate = delegate
                    delegate.onReady = { IosPageBoundTokenRuntime.attach(this) }
                    loadRequest(
                        NSURLRequest.requestWithURL(
                            requireNotNull(NSURL.URLWithString(PAGE_BOUND_TOKEN_ORIGIN_URL)),
                        ),
                    )
                }
            },
            modifier = Modifier.size(1.dp),
            onRelease = {
                delegate.close()
                IosPageBoundTokenRuntime.detach(it)
                it.stopLoading()
                it.navigationDelegate = null
            },
        )
    }
    DisposableEffect(Unit) {
        onDispose { delegate.close() }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class IosPageBoundNavigationDelegate(
    private val scope: CoroutineScope,
) : NSObject(),
    WKNavigationDelegateProtocol {
    var onReady: () -> Unit = {}
    private var closed = false
    private val retries = IosTokenNavigationRetry(scope)
    private var latestNavigationError: NSError? = null

    fun close() {
        closed = true
        retries.close()
        onReady = {}
    }

    private fun retry(view: WKWebView) {
        if (closed) return
        IosPageBoundTokenRuntime.detach(view)
        retries.failed(
            onExhausted = { latestNavigationError?.let(::logNavigationFailure) },
            reload = {
                view.loadRequest(NSURLRequest.requestWithURL(requireNotNull(NSURL.URLWithString(PAGE_BOUND_TOKEN_ORIGIN_URL))))
            },
        )
    }

    private fun logNavigationFailure(error: NSError) {
        PlaybackEventLog.error(
            kind = PlaybackEventKind.TOKEN,
            message = "Page-bound token navigation failed after retries",
            details =
                buildMap {
                    error.domain?.let { put("errorDomain", it) }
                    put("errorCode", error.code.toString())
                },
        )
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFinishNavigation: WKNavigation?,
    ) {
        if (closed) return
        retries.ready()
        latestNavigationError = null
        onReady()
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailNavigation: WKNavigation?,
        withError: NSError,
    ) {
        if (!closed) {
            latestNavigationError = withError
        }
        retry(webView)
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) {
        if (!closed) {
            latestNavigationError = withError
        }
        retry(webView)
    }
}

internal class IosTokenNavigationRetry(
    private val scope: CoroutineScope,
    private val maxAttempts: Int = 7,
    private val initialDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 30_000L,
) {
    private var job: Job? = null
    private var delayMs = initialDelayMs
    private var closed = false
    private var attempts = 0
    private var exhaustionReported = false

    fun failed(
        onExhausted: () -> Unit,
        reload: () -> Unit,
    ) {
        if (closed || job?.isActive == true) return
        attempts++
        if (attempts >= maxAttempts && !exhaustionReported) {
            exhaustionReported = true
            onExhausted()
        }
        job =
            scope.launch {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
                reload()
            }
    }

    fun ready() {
        job?.cancel()
        attempts = 0
        delayMs = initialDelayMs
        exhaustionReported = false
    }

    fun close() {
        closed = true
        job?.cancel()
    }
}
