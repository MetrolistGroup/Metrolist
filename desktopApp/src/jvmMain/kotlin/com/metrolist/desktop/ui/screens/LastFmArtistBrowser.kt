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
import com.metrolist.desktop.utils.WaylandScale
import com.metrolist.desktop.utils.isLinuxWebKitAvailable
import com.metrolist.desktop.utils.isWebKitGtkLibraryAvailable
import com.metrolist.desktop.web.rememberDesktopBrowser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.net.URI

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
    val available = remember { if (WaylandScale.isNativeWaylandActive()) isWebKitGtkLibraryAvailable() else isLinuxWebKitAvailable() }
    if (!available) {
        LaunchedEffect(request) { request.result.complete(null) }
        return
    }
    key(request) {
        val webBrowser =
            rememberDesktopBrowser(
                initialUrl = request.url,
                incognito = true,
                focused = false,
                allowNavigation = ::isLastFmArtistUrl,
            )
        LaunchedEffect(request) {
            while (true) {
                val result =
                    withTimeoutOrNull(1_000L) {
                        webBrowser.evaluateJavaScript(
                            """
                            (() => {
                                if (location.hostname !== 'www.last.fm' || document.readyState !== 'complete' ||
                                    document.title === 'Client Challenge') return null;
                                const html = document.documentElement.outerHTML;
                                return html.length <= 2097152 ? html : null;
                            })()
                            """.trimIndent(),
                        )
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
            webBrowser.Content(Modifier.fillMaxSize())
        }
    }
}
