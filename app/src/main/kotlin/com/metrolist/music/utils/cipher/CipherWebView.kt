package com.metrolist.music.utils.cipher

import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CipherWebView private constructor(
    context: Context,
    private val playerJs: String,
    private val sigInfo: FunctionNameExtractor.SigFunctionInfo?,
    private val nFuncInfo: FunctionNameExtractor.NFunctionInfo?,
    private val initContinuation: Continuation<CipherWebView>,
) {
    private val webView = WebView(context)
    private var sigContinuation: Continuation<String>? = null
    private var nContinuation: Continuation<String>? = null

    @Volatile
    var nFunctionAvailable: Boolean = false
        private set

    @Volatile
    var discoveredNFuncName: String? = null
        private set

    init {
        val settings = webView.settings
        @Suppress("SetJavaScriptEnabled")
        settings.javaScriptEnabled = true
        settings.allowFileAccess = true
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        settings.blockNetworkLoads = true

        webView.addJavascriptInterface(this, JS_INTERFACE)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.message().contains("Uncaught") && !m.message().contains("is not defined")) {
                    Timber.tag(TAG).e("WebView JS error: ${m.message()} at ${m.sourceId()}:${m.lineNumber()}")
                }
                return super.onConsoleMessage(m)
            }
        }
    }

    private fun loadPlayerJsFromFile() {
        val sigFuncName = sigInfo?.name
        val nFuncName = nFuncInfo?.name
        val nArrayIdx = nFuncInfo?.arrayIndex
        Timber.tag(TAG).d("Loading player JS from file (${playerJs.length} chars), exporting sig=$sigFuncName, nFunc=$nFuncName[$nArrayIdx]")

        val exports = buildList {
            if (sigFuncName != null) {
                add("window._cipherSigFunc = typeof $sigFuncName !== 'undefined' ? $sigFuncName : null;")
            }
            if (nFuncName != null) {
                val nExpr = if (nArrayIdx != null) {
                    "$nFuncName[$nArrayIdx]"
                } else {
                    nFuncName
                }
                add("window._nTransformFunc = typeof $nFuncName !== 'undefined' ? $nExpr : null;")
            }
        }

        val modifiedJs = if (exports.isNotEmpty()) {
            val exportCode = "; " + exports.joinToString(" ")
            playerJs.replace("})(_yt_player);", "$exportCode })(_yt_player);")
        } else {
            playerJs
        }

        val cacheDir = File(webView.context.cacheDir, "cipher")
        cacheDir.mkdirs()
        File(cacheDir, "player.js").writeText(modifiedJs)

        val html = """<!DOCTYPE html>
<html><head><script>
function deobfuscateSig(funcName, constantArg, obfuscatedSig) {
    try {
        var func = window._cipherSigFunc;
        if (typeof func !== 'function') {
            CipherBridge.onSigError("Sig func not found on window (type: " + typeof func + ")");
            return;
        }
        var result;
        if (constantArg !== null && constantArg !== undefined) {
            result = func(constantArg, obfuscatedSig);
        } else {
            result = func(obfuscatedSig);
        }
        if (result === undefined || result === null) {
            CipherBridge.onSigError("Function returned null/undefined");
            return;
        }
        CipherBridge.onSigResult(String(result));
    } catch (error) {
        CipherBridge.onSigError(error + "\n" + (error.stack || ""));
    }
}

function transformN(nValue) {
    try {
        var func = window._nTransformFunc;
        if (typeof func !== 'function') {
            CipherBridge.onNError("N-transform func not available (type: " + typeof func + ")");
            return;
        }
        var result = func(nValue);
        if (result === undefined || result === null) {
            CipherBridge.onNError("N-transform returned null/undefined");
            return;
        }
        CipherBridge.onNResult(String(result));
    } catch (error) {
        CipherBridge.onNError(error + "\n" + (error.stack || ""));
    }
}

function discoverAndInit() {
    var nFuncName = "";
    var info = "";
    if (typeof window._nTransformFunc === 'function') {
        try {
            var testResult = window._nTransformFunc("test_abc");
            if (typeof testResult === 'string' && testResult !== "test_abc") {
                nFuncName = "injected_from_iife";
                info = "exported_ok,test_result=" + testResult.substring(0, 30);
            } else {
                info = "exported_but_bad_result:" + typeof testResult;
                window._nTransformFunc = null;
            }
        } catch(e) {
            info = "exported_but_threw:" + e;
            window._nTransformFunc = null;
        }
    }

    if (!nFuncName) {
        try {
            var testInput = "T2Xw3pWQ_Wk0xbOg";
            var keys = Object.getOwnPropertyNames(window);
            var tested = 0;
            var candidates = [];
            for (var i = 0; i < keys.length; i++) {
                try {
                    var key = keys[i];
                    if (key.startsWith("webkit") || key === "CipherBridge" || key === "_cipherSigFunc" || key === "_nTransformFunc") continue;
                    var fn = window[key];
                    if (typeof fn !== 'function' || fn.length !== 1) continue;
                    tested++;
                    var result = fn(testInput);
                    if (typeof result === 'string' && result !== testInput && result.length > 5) {
                        candidates.push(key + ":" + result.substring(0, 50));
                        if (result.indexOf('_w8_') >= 0) {
                            window._nTransformFunc = fn;
                            nFuncName = key;
                            break;
                        }
                    }
                } catch(e) {}
            }
            info = "brute_force:tested=" + tested + "/" + keys.length;
            if (!nFuncName && candidates.length > 0) {
                info += ",near_misses=" + candidates.slice(0, 5).join("|");
            }
        } catch(e) {
            info = "brute_force_error:" + e;
        }
    }
    CipherBridge.onNDiscoveryDone(nFuncName, info);
    CipherBridge.onPlayerJsLoaded();
}
</script>
<script src="player.js"
    onload="discoverAndInit()"
    onerror="CipherBridge.onPlayerJsError('Failed to load player.js from file')">
</script>
</head><body></body></html>"""

        webView.loadDataWithBaseURL(
            "file://${cacheDir.absolutePath}/",
            html, "text/html", "utf-8", null
        )
    }

    @JavascriptInterface
    fun onNDiscoveryDone(funcName: String, info: String) {
        if (funcName.isNotEmpty()) {
            Timber.tag(TAG).d("N-function DISCOVERED: $funcName ($info)")
            discoveredNFuncName = funcName
            nFunctionAvailable = true
        } else {
            Timber.tag(TAG).e("N-function NOT found ($info)")
            nFunctionAvailable = false
        }
    }

    @JavascriptInterface
    fun onPlayerJsLoaded() {
        Timber.tag(TAG).d("Player JS loaded, n-func=${discoveredNFuncName ?: "none"}")
        initContinuation.resume(this)
    }

    @JavascriptInterface
    fun onPlayerJsError(error: String) {
        Timber.tag(TAG).e("Player JS load error: $error")
        initContinuation.resumeWithException(CipherException("Player JS load failed: $error"))
    }

    suspend fun deobfuscateSignature(obfuscatedSig: String): String {
        if (sigInfo == null) {
            throw CipherException("Signature function info not available")
        }

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                sigContinuation = cont
                val constArgJs = if (sigInfo.constantArg != null) "${sigInfo.constantArg}" else "null"
                webView.evaluateJavascript(
                    "deobfuscateSig('${sigInfo.name}', $constArgJs, '${escapeJsString(obfuscatedSig)}')",
                    null
                )
            }
        }
    }

    @JavascriptInterface
    fun onSigResult(result: String) {
        Timber.tag(TAG).d("Signature deobfuscated: ${result.take(30)}...")
        sigContinuation?.resume(result)
        sigContinuation = null
    }

    @JavascriptInterface
    fun onSigError(error: String) {
        Timber.tag(TAG).e("Signature deobfuscation error: $error")
        sigContinuation?.resumeWithException(CipherException("Sig deobfuscation failed: $error"))
        sigContinuation = null
    }

    suspend fun transformN(nValue: String): String {
        if (!nFunctionAvailable) {
            throw CipherException("N-transform function not discovered")
        }

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                nContinuation = cont
                webView.evaluateJavascript(
                    "transformN('${escapeJsString(nValue)}')",
                    null
                )
            }
        }
    }

    @JavascriptInterface
    fun onNResult(result: String) {
        Timber.tag(TAG).d("N-transform result: ${result.take(50)}...")
        nContinuation?.resume(result)
        nContinuation = null
    }

    @JavascriptInterface
    fun onNError(error: String) {
        Timber.tag(TAG).e("N-transform error: $error")
        nContinuation?.resumeWithException(CipherException("N-transform failed: $error"))
        nContinuation = null
    }

    fun close() {
        webView.clearHistory()
        webView.clearCache(true)
        webView.loadUrl("about:blank")
        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
        Timber.tag(TAG).d("CipherWebView closed")
    }

    private fun escapeJsString(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    companion object {
        private const val TAG = "Metrolist_CipherWebView"
        private const val JS_INTERFACE = "CipherBridge"

        suspend fun create(
            context: Context,
            playerJs: String,
            sigInfo: FunctionNameExtractor.SigFunctionInfo?,
            nFuncInfo: FunctionNameExtractor.NFunctionInfo? = null,
        ): CipherWebView {
            return withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val wv = CipherWebView(context, playerJs, sigInfo, nFuncInfo, cont)
                    wv.loadPlayerJsFromFile()
                }
            }
        }
    }
}

class CipherException(message: String) : Exception(message)
