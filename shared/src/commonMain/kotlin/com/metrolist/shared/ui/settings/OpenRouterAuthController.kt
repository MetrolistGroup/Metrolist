package com.metrolist.shared.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.shared.api.lyrics.OpenRouterAuth
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.tryOpenUrl
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class OpenRouterAuthController(
    private val settings: Settings,
    private val client: HttpClient,
    private val scope: CoroutineScope,
    private val openUrl: (String, (Boolean) -> Unit) -> Unit,
) {
    constructor(settings: Settings) : this(
        settings,
        HttpClient {
            followRedirects = false
            install(HttpTimeout) { requestTimeoutMillis = 15_000 }
        },
        CoroutineScope(SupervisorJob() + Dispatchers.Main),
        ::tryOpenUrl,
    )

    private val auth = OpenRouterAuth(client)
    var awaitingCode by mutableStateOf(settings.lyricsOpenRouterOAuthSession.takeIf(String::isNotBlank)?.let(auth::restore) == true)
        private set
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var disposed = false

    init {
        if (!awaitingCode && settings.lyricsOpenRouterOAuthSession.isNotBlank()) settings.lyricsOpenRouterOAuthSession = ""
    }

    fun start() {
        if (disposed || busy) return
        busy = true
        try {
            val url = auth.start()
            settings.lyricsOpenRouterOAuthSession = checkNotNull(auth.pendingSession())
            openUrl(url, ::finishBrowserLaunch)
        } catch (_: Exception) {
            finishBrowserLaunch(false)
        }
    }

    private fun finishBrowserLaunch(opened: Boolean) {
        busy = false
        awaitingCode = opened
        failed = !opened
        if (!opened) {
            auth.cancel()
            settings.lyricsOpenRouterOAuthSession = ""
        }
        if (disposed) close()
    }

    fun exchange(code: String) {
        if (disposed || busy || !awaitingCode) return
        busy = true
        failed = false
        scope.launch {
            try {
                settings.lyricsOpenRouterOAuthSession = ""
                val key = auth.exchange(code.trim())
                settings.lyricsOpenRouterOAuthKey = key
                if (!disposed) awaitingCode = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!disposed) {
                    awaitingCode = false
                    failed = true
                }
            } finally {
                busy = false
                if (disposed) close()
            }
        }
    }

    fun cancel() {
        if (disposed || busy) return
        auth.cancel()
        settings.lyricsOpenRouterOAuthSession = ""
        awaitingCode = false
        failed = false
    }

    fun disconnect() {
        if (disposed || busy) return
        cancel()
        settings.lyricsOpenRouterOAuthKey = ""
    }

    fun dispose() {
        disposed = true
        if (!busy) close()
    }

    private fun close() {
        auth.cancel()
        scope.cancel()
        client.close()
    }
}
