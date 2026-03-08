package com.metrolist.desktop.youtube

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.utils.isLinuxWebKitAvailable
import com.metrolist.desktop.utils.prepareDesktopWebViewDataDirectory
import com.metrolist.shared.utils.OsUtil
import com.metrolist.shared.youtube.playback.DesktopPoTokenJavaScriptRuntime
import com.metrolist.shared.youtube.playback.PAGE_BOUND_PO_TOKEN_USER_AGENT
import com.metrolist.shared.youtube.playback.PAGE_BOUND_TOKEN_ORIGIN_URL
import com.metrolist.shared.youtube.playback.PAGE_BOUND_TOKEN_SCRIPT
import io.github.kdroidfilter.webview.web.LoadingState
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.WebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewState
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

class DesktopPageBoundTokenRuntime(
    isPlatformAvailable: () -> Boolean = { !OsUtil.isLinux || isLinuxWebKitAvailable() },
) : DesktopPoTokenJavaScriptRuntime {
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CancellableContinuation<String>>()

    internal val requested = MutableStateFlow(false)
    internal val session = MutableStateFlow(0L)
    private val navigator = MutableStateFlow<Result<WebViewNavigator>?>(null)
    private val platformAvailable by lazy(isPlatformAvailable)
    private val reaper = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val available: Boolean
        get() = navigator.value?.isFailure != true && platformAvailable

    internal fun failInitialization(error: Throwable) {
        navigator.value = Result.failure(error)
        requested.value = false
        failPending(error)
    }

    internal fun attach(
        sessionId: Long,
        navigator: WebViewNavigator,
    ) {
        if (sessionId == session.value && requested.value && available) this.navigator.value = Result.success(navigator)
    }

    internal fun detach(sessionId: Long = session.value) {
        if (sessionId != session.value) return
        if (navigator.value?.isFailure != true) navigator.value = null
        failPending(IllegalStateException("Desktop page-bound WebView detached"))
    }

    override suspend fun evaluate(script: String): String =
        mutex.withLock {
            check(available) { "Desktop page-bound WebView is unavailable" }
            requested.value = true
            withTimeoutOrNull(EVALUATION_TIMEOUT_MS.milliseconds) {
                val activeNavigator = navigator.filterNotNull().first().getOrThrow()
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { continuation ->
                        val requestId = generation.incrementAndGet()
                        pending[requestId] = continuation
                        continuation.invokeOnCancellation { pending.remove(requestId) }
                        activeNavigator.evaluateJavaScript(script) { result ->
                            pending.remove(requestId)?.resume(normalizeJavaScriptResult(result))
                        }
                    }
                }
            } ?: run {
                val error = IllegalStateException("Desktop page-bound WebView did not become ready or respond")
                failInitialization(error)
                throw error
            }
        }

    override suspend fun reset() {
        withContext(NonCancellable + Dispatchers.Main) {
            mutex.withLock {
                requested.value = false
                session.value++
                if (navigator.value?.isFailure != true) navigator.value = null
                failPending(IllegalStateException("Desktop page-bound WebView reset"))
            }
        }
        if (OsUtil.isLinux) {
            reaper.launch {
                delay(NETWORK_PROCESS_REAP_DELAY_MS)
                if (!requested.value) reapIdleWebKitNetworkProcess()
            }
        }
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { continuation ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        pending.clear()
    }

    private companion object {
        // Leave room for sidecar fallback inside the extractor's 18-second token deadline.
        const val EVALUATION_TIMEOUT_MS = 10_000L
        const val NETWORK_PROCESS_REAP_DELAY_MS = 3_000L
    }
}

@Composable
fun DesktopPageBoundTokenHost(runtime: DesktopPageBoundTokenRuntime) {
    val requested by runtime.requested.collectAsState()
    val session by runtime.session.collectAsState()
    if (!requested || !runtime.available) return
    key(session) { DesktopPageBoundTokenSession(runtime, session) }
}

@Composable
private fun DesktopPageBoundTokenSession(
    runtime: DesktopPageBoundTokenRuntime,
    session: Long,
) {
    val webViewDataDirectory = remember { runCatching { prepareDesktopWebViewDataDirectory().absolutePath } }
    if (webViewDataDirectory.isFailure) {
        LaunchedEffect(runtime, webViewDataDirectory) {
            runtime.failInitialization(checkNotNull(webViewDataDirectory.exceptionOrNull()))
        }
        return
    }
    val state =
        rememberWebViewState(PAGE_BOUND_TOKEN_ORIGIN_URL).apply {
            webSettings.customUserAgentString = PAGE_BOUND_PO_TOKEN_USER_AGENT
            webSettings.desktopWebSettings.dataDirectory = webViewDataDirectory.getOrNull()
            webSettings.desktopWebSettings.incognito = true
            webSettings.desktopWebSettings.focused = false
            webSettings.desktopWebSettings.initScript = PAGE_BOUND_TOKEN_SCRIPT
        }
    val scope = rememberCoroutineScope()
    val navigator = rememberWebViewNavigator(scope)
    LaunchedEffect(state.loadingState) {
        if (state.loadingState is LoadingState.Finished) {
            runtime.attach(session, navigator)
        }
    }
    DisposableEffect(runtime, navigator) {
        onDispose { runtime.detach(session) }
    }
    Box(Modifier.size(1.dp)) {
        WebView(
            state = state,
            navigator = navigator,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun normalizeJavaScriptResult(value: String): String {
    val raw = value.trim()
    if (raw == "null" || raw == "undefined") return ""
    return runCatching { Json.decodeFromString<String>(raw) }.getOrDefault(raw.trim('"'))
}

// WebKitGTK retains its network process after WebViews close; stop it only when no web process remains.
internal fun reapIdleWebKitNetworkProcess(children: List<ProcessHandle> = ProcessHandle.current().children().toList()) {
    fun ProcessHandle.isNamed(name: String) = info().command().orElse("").endsWith("/$name")
    if (children.any { it.isNamed("WebKitWebProcess") }) return
    children.filter { it.isNamed("WebKitNetworkProcess") }.forEach { it.destroy() }
}
