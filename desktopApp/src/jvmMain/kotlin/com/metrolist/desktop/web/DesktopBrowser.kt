package com.metrolist.desktop.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.metrolist.desktop.utils.WaylandScale
import io.github.kdroidfilter.webview.request.RequestInterceptor
import io.github.kdroidfilter.webview.request.WebRequest
import io.github.kdroidfilter.webview.request.WebRequestInterceptResult
import io.github.kdroidfilter.webview.web.LoadingState
import io.github.kdroidfilter.webview.web.WebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

internal data class BrowserCookie(
    val name: String,
    val value: String,
)

/**
 * The browser features Metrolist's sign-in and scraping flows use. wry serves X11, Windows and macOS; under native
 * Wayland, where wry cannot attach to a window, a WebKitGTK view embedded in the Tao window does.
 */
internal interface DesktopBrowser {
    /** The URL of the last page that finished loading, or null while a page loads. */
    val finishedUrl: String?

    val currentUrl: String?

    val isLoading: Boolean

    /** Cookies the browser sends to [url], including HttpOnly ones. */
    suspend fun cookies(url: String): List<BrowserCookie>

    /** Cookies read from the browser's own store when the URL lookup misses some, or empty when unsupported. */
    suspend fun storedCookies(): List<BrowserCookie> = emptyList()

    /** Runs [script] and returns its result as JSON, or an empty string when it fails. */
    suspend fun evaluateJavaScript(script: String): String

    fun loadUrl(url: String)

    @Composable
    fun Content(modifier: Modifier)
}

@Composable
internal fun rememberDesktopBrowser(
    initialUrl: String,
    userAgent: String? = null,
    dataDirectory: File? = null,
    initScript: String? = null,
    incognito: Boolean = false,
    focused: Boolean = true,
    backgroundColor: Color? = null,
    allowNavigation: ((String) -> Boolean)? = null,
): DesktopBrowser {
    val currentAllowNavigation by rememberUpdatedState(allowNavigation)
    val navigationFilter: ((String) -> Boolean)? = allowNavigation?.let { { url -> currentAllowNavigation?.invoke(url) != false } }
    if (WaylandScale.isNativeWaylandActive()) {
        val view =
            remember {
                WebKitGtkView(
                    dataDirectory = dataDirectory.takeUnless { incognito },
                    userAgent = userAgent,
                    initScript = initScript,
                    allowNavigation = navigationFilter,
                ).apply { loadUrl(initialUrl) }
            }
        DisposableEffect(view) { onDispose(view::dispose) }
        return remember(view) { WebKitGtkBrowser(view) }
    }
    val state =
        rememberWebViewState(initialUrl).apply {
            backgroundColor?.let { webSettings.backgroundColor = it }
            webSettings.desktopWebSettings.transparent = false
            webSettings.desktopWebSettings.dataDirectory = dataDirectory?.absolutePath
            webSettings.desktopWebSettings.incognito = incognito
            webSettings.desktopWebSettings.focused = focused
            initScript?.let { webSettings.desktopWebSettings.initScript = it }
            userAgent?.let { webSettings.customUserAgentString = it }
        }
    val navigator =
        rememberWebViewNavigator(
            requestInterceptor =
                navigationFilter?.let { filter ->
                    object : RequestInterceptor {
                        override fun onInterceptUrlRequest(
                            request: WebRequest,
                            navigator: WebViewNavigator,
                        ): WebRequestInterceptResult =
                            if (filter(request.url)) WebRequestInterceptResult.Allow else WebRequestInterceptResult.Reject
                    }
                },
        )
    return remember(state, navigator) {
        object : DesktopBrowser {
            override val finishedUrl get() = state.lastLoadedUrl.takeIf { state.loadingState is LoadingState.Finished }
            override val currentUrl get() = state.lastLoadedUrl
            override val isLoading get() = state.loadingState is LoadingState.Loading

            override suspend fun cookies(url: String) = state.cookieManager.getCookies(url).map { BrowserCookie(it.name, it.value) }

            override suspend fun storedCookies() = readWryStoredCookies(state.cookieManager)

            override suspend fun evaluateJavaScript(script: String): String =
                suspendCancellableCoroutine { continuation ->
                    navigator.evaluateJavaScript(script) { result -> if (continuation.isActive) continuation.resume(result) }
                }

            override fun loadUrl(url: String) = navigator.loadUrl(url)

            @Composable
            override fun Content(modifier: Modifier) = DesktopWebView(state = state, navigator = navigator, modifier = modifier)
        }
    }
}

private class WebKitGtkBrowser(
    private val view: WebKitGtkView,
) : DesktopBrowser {
    override val finishedUrl get() = view.finishedUrl
    override val currentUrl get() = view.currentUrl
    override val isLoading get() = view.isLoading

    override suspend fun cookies(url: String) = view.cookies(url).map { (name, value) -> BrowserCookie(name, value) }

    override suspend fun evaluateJavaScript(script: String) = view.evaluateJavaScript(script)

    override fun loadUrl(url: String) = view.loadUrl(url)

    @Composable
    override fun Content(modifier: Modifier) = WebKitGtkViewHost(view, modifier)
}

/** wry's URL lookup can miss cookies set for parent domains, so read its whole store for YouTube and Google. */
private suspend fun readWryStoredCookies(cookieManager: Any): List<BrowserCookie> =
    withContext(Dispatchers.IO) {
        runCatching {
            val panelField = cookieManager.javaClass.getDeclaredField("panel").apply { isAccessible = true }
            val panel = panelField.get(cookieManager) ?: return@runCatching emptyList()
            val cookies = panel.javaClass.getMethod("getCookies").invoke(panel) as? List<*> ?: return@runCatching emptyList()
            cookies.mapNotNull { cookie ->
                cookie ?: return@mapNotNull null
                val domain =
                    (cookie.javaClass.getMethod("getDomain").invoke(cookie) as? String)
                        ?.removePrefix(".")
                        ?.lowercase() ?: return@mapNotNull null
                if (domain != "youtube.com" && !domain.endsWith(".youtube.com") &&
                    domain != "google.com" && !domain.endsWith(".google.com")
                ) {
                    return@mapNotNull null
                }
                val name = cookie.javaClass.getMethod("getName").invoke(cookie) as? String ?: return@mapNotNull null
                val value = cookie.javaClass.getMethod("getValue").invoke(cookie) as? String ?: return@mapNotNull null
                BrowserCookie(name, value)
            }
        }.getOrDefault(emptyList())
    }
