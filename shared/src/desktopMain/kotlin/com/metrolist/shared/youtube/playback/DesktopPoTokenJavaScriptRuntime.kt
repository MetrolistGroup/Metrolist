package com.metrolist.shared.youtube.playback

typealias DesktopPoTokenJavaScriptRuntime = PageBoundPoTokenJavaScriptRuntime

/**
 * User agent of the page-bound minting WebView and its requests. It must name the engine the WebView really runs:
 * BotGuard fingerprints the engine, and YouTube rejects tokens from a WebKit view that claims to be Chrome
 * (SABR stays attestation-pending and stops after about 60 s, tokenized direct URLs return 403).
 * WebView2 on Windows is Chromium; WKWebView on macOS and WebKitGTK on Linux are WebKit.
 */
val PAGE_BOUND_PO_TOKEN_USER_AGENT: String
    get() {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
            }

            os.contains("mac") -> {
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"
            }

            else -> {
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"
            }
        }
    }
