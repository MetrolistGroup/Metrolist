package com.metrolist.desktop.web

import com.metrolist.desktop.utils.AppLogger
import io.github.kdroidfilter.webview.wry.WebViewException
import io.github.kdroidfilter.webview.wry.WryWebViewPanel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DesktopWebViewAvailability {
    private val failure = MutableStateFlow<Exception?>(null)
    val creationFailure = failure.asStateFlow()

    fun dispatchEvent(dispatch: () -> Unit) {
        try {
            dispatch()
        } catch (error: Exception) {
            // Wry creates the native browser on a Swing timer, outside the Compose call.
            if (error !is WebViewException ||
                error.stackTrace.none { it.className == WryWebViewPanel::class.java.name && it.methodName == "createIfNeeded" }
            ) {
                throw error
            }
            if (failure.compareAndSet(null, error)) {
                AppLogger.warning("WebView", "Native browser creation failed", error)
            }
        }
    }

    companion object {
        val shared = DesktopWebViewAvailability()
    }
}
