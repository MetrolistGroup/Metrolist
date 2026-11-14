package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.utils.isLinuxWebKitAvailable
import io.github.kdroidfilter.webview.request.RequestInterceptor
import io.github.kdroidfilter.webview.request.WebRequest
import io.github.kdroidfilter.webview.request.WebRequestInterceptResult
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.WebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.net.URI
import kotlin.coroutines.resume

internal class LastFmArtistBrowser {
    internal class Request(
        val url: String,
        val result: CompletableDeferred<String?> = CompletableDeferred(),
    )

    internal var request by mutableStateOf<Request?>(null)
        private set
    private val mutex = Mutex()

    suspend fun load(url: String): String? {
        require(isLastFmArtistUrl(url))
        return withTimeoutOrNull(20_000L) {
            mutex.withLock {
                withContext(Dispatchers.Main) {
                    val pending = Request(url)
                    request = pending
                    try {
                        pending.result.await()
                    } finally {
                        request = null
                    }
                }
            }
        }
    }
}

internal fun isLastFmArtistUrl(url: String): Boolean =
    runCatching { URI(url).let { it.scheme == "https" && it.host == "www.last.fm" && it.userInfo == null && it.port == -1 } }
        .getOrDefault(false)

@Composable
internal fun LastFmArtistBrowserHost(browser: LastFmArtistBrowser) {
    val request = browser.request ?: return
    val available = remember { isLinuxWebKitAvailable() }
    if (!available) {
        LaunchedEffect(request) { request.result.complete(null) }
        return
    }
    key(request) {
        val state =
            rememberWebViewState(request.url).apply {
                webSettings.desktopWebSettings.incognito = true
                webSettings.desktopWebSettings.focused = false
            }
        val navigator =
            rememberWebViewNavigator(
                requestInterceptor =
                    object : RequestInterceptor {
                        override fun onInterceptUrlRequest(
                            request: WebRequest,
                            navigator: WebViewNavigator,
                        ): WebRequestInterceptResult =
                            if (isLastFmArtistUrl(request.url)) WebRequestInterceptResult.Allow else WebRequestInterceptResult.Reject
                    },
            )
        LaunchedEffect(request) {
            while (true) {
                val result =
                    withTimeoutOrNull(1_000L) {
                        suspendCancellableCoroutine { continuation ->
                            navigator.evaluateJavaScript(
                                """
                                (() => {
                                    if (location.hostname !== 'www.last.fm' || document.readyState !== 'complete' ||
                                        document.title === 'Client Challenge') return null;
                                    const html = document.documentElement.outerHTML;
                                    return html.length <= 2097152 ? html : null;
                                })()
                                """.trimIndent(),
                            ) { value -> if (continuation.isActive) continuation.resume(value) }
                        }
                    }
                val html = result?.let { runCatching { Json.decodeFromString<String>(it) }.getOrNull() }
                if (html != null) {
                    request.result.complete(html)
                    break
                }
                delay(100)
            }
        }
        Box(Modifier.size(1.dp)) {
            WebView(state = state, navigator = navigator, modifier = Modifier.fillMaxSize())
        }
    }
}
