package com.metrolist.music.utils.cipher

import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CipherWebView private constructor(
    context: Context,
    private val sigInfo: FunctionNameExtractor.SigFunctionInfo?,
    private val nFuncInfo: FunctionNameExtractor.NFunctionInfo?,
    initContinuation: Continuation<CipherWebView>,
) {
    private val webView = WebView(context)

    // Single-shot continuation slots. All resumes go through takeAndNull-style helpers so a late
    // or duplicate JS-bridge callback (or a renderer-gone racing a normal resume) can never
    // double-resume and crash inside a @JavascriptInterface method. The sig/n slots additionally
    // carry a per-request id, echoed back by the JS bridge: a late result from a cancelled/
    // abandoned request must never resume the NEXT request's continuation with the wrong value.
    private var initContinuation: Continuation<CipherWebView>? = initContinuation
    private val sigSlot = RequestSlot<String>()
    private val nSlot = RequestSlot<String>()

    /**
     * Single-shot continuation slot with an id-checked take. arm() returns the request id the
     * JS call must echo back; takeIfCurrent(id) ignores late callbacks from superseded
     * requests (the stale-result guard); takeAny() is for renderer-gone/timeout paths, which
     * must clear whatever is pending. Synchronized because JS-bridge callbacks arrive on a
     * WebView-internal thread while onRenderProcessGone/timeouts run on the main thread.
     * The distinct method names are deliberate: same-name overloads made dropping the id a
     * silent, compile-clean way to reintroduce the race.
     */
    private class RequestSlot<T> {
        private var continuation: Continuation<T>? = null
        private var requestId = 0

        @Synchronized
        fun arm(cont: Continuation<T>): Int {
            continuation = cont
            return ++requestId
        }

        @Synchronized
        fun takeIfCurrent(id: Int): Continuation<T>? =
            if (id == requestId) continuation.also { continuation = null } else null

        @Synchronized
        fun takeAny(): Continuation<T>? = continuation.also { continuation = null }
    }

    /**
     * Set once the WebView's renderer process has died (or an evaluate timed out, which on a
     * wedged renderer is indistinguishable). A dead instance must be discarded and recreated —
     * per Android docs a WebView whose render process is gone cannot be reused.
     */
    @Volatile
    var isDead: Boolean = false
        private set

    @Volatile
    private var destroyed = false

    @Volatile
    var nFunctionAvailable: Boolean = false
        private set

    @Volatile
    var sigFunctionAvailable: Boolean = false
        private set

    @Volatile
    var discoveredNFuncName: String? = null
        private set

    @Volatile
    var usingHardcodedMode: Boolean = false
        private set

    init {
        Timber.tag(TAG).d("Initializing CipherWebView...")
        Timber.tag(TAG).d("  sigInfo: name=${sigInfo?.name}, constantArg=${sigInfo?.constantArg}, hardcoded=${sigInfo?.isHardcoded}")
        Timber.tag(TAG).d("  nFuncInfo: name=${nFuncInfo?.name}, arrayIdx=${nFuncInfo?.arrayIndex}, hardcoded=${nFuncInfo?.isHardcoded}")

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
                val msg = m.message()
                val src = "${m.sourceId()}:${m.lineNumber()}"

                when (m.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> {
                        if (!msg.contains("is not defined")) {
                            Timber.tag(TAG).e("JS ERROR: $msg at $src")
                        }
                    }
                    ConsoleMessage.MessageLevel.WARNING -> {
                        Timber.tag(TAG).w("JS WARN: $msg at $src")
                    }
                    else -> {
                        Timber.tag(TAG).v("JS LOG: $msg")
                    }
                }
                return super.onConsoleMessage(m)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            // API 26+ callback (never fires below 26; the withTimeout nets in create()/
            // deobfuscateSignature()/transformN() carry recovery on providers that don't
            // deliver it, e.g. Chromium-61-era WebViews).
            @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Timber.tag(TAG).e(
                    "=== RENDER PROCESS GONE === didCrash=${runCatching { detail.didCrash() }.getOrNull()}"
                )
                onRendererGone("WebView render process gone (didCrash=${runCatching { detail.didCrash() }.getOrNull()})")
                // Consume the event so the framework doesn't kill the app process.
                return true
            }
        }

        Timber.tag(TAG).d("WebView settings configured")
    }

    /**
     * The renderer died (or is treated as dead after a timeout): fail every pending continuation
     * with [CipherRendererGoneException] so create()/decipher fail fast instead of hanging forever
     * on JS-bridge callbacks that will never come (and so CipherDeobfuscator's mutex is released),
     * then destroy the WebView — it cannot be reused after a render-process crash.
     */
    private fun onRendererGone(reason: String) {
        isDead = true
        val e = CipherRendererGoneException(reason)
        takeInitContinuation()?.resumeSafely { it.resumeWithException(e) }
        sigSlot.takeAny()?.resumeSafely { it.resumeWithException(e) }
        nSlot.takeAny()?.resumeSafely { it.resumeWithException(e) }
        destroyWebView()
    }

    // Single-shot take — synchronized because JS-bridge callbacks arrive on a WebView-internal
    // thread while onRenderProcessGone/timeouts run on the main thread.
    @Synchronized
    private fun takeInitContinuation(): Continuation<CipherWebView>? =
        initContinuation.also { initContinuation = null }

    private inline fun <T> T.resumeSafely(block: (T) -> Unit) {
        // A continuation cancelled by withTimeout may already be completed; never let that
        // throw out of a WebView callback.
        runCatching { block(this) }
    }

    /**
     * Loads the already-prepared player.js (written by create() on an IO dispatcher via
     * [buildModifiedPlayerJsImpl]) into the WebView. Only the cheap WebView work happens here
     * on Main.
     */
    private fun loadPreparedPlayerJs(cacheDir: File) {
        usingHardcodedMode = sigInfo?.isHardcoded == true || nFuncInfo?.isHardcoded == true

        val html = buildDiscoveryHtml()
        Timber.tag(TAG).d("Discovery HTML built (${html.length} chars)")

        webView.loadDataWithBaseURL(
            "file://${cacheDir.absolutePath}/",
            html, "text/html", "utf-8", null
        )
        Timber.tag(TAG).d("WebView loading started...")
    }

    private fun buildDiscoveryHtml(): String = """<!DOCTYPE html>
<html><head><script>
function deobfuscateSig(funcName, constantArg, obfuscatedSig, reqId) {
    CipherBridge.logDebug("deobfuscateSig called: funcName=" + funcName + ", constantArg=" + constantArg + ", sigLen=" + obfuscatedSig.length + ", reqId=" + reqId);

    try {
        var func = window._cipherSigFunc;
        CipherBridge.logDebug("window._cipherSigFunc type: " + typeof func + ", length: " + (func ? func.length : "N/A"));

        if (typeof func !== 'function') {
            CipherBridge.onSigError(reqId, "Sig func not found on window (type: " + typeof func + ")");
            return;
        }

        var result;
        if (func.length === 1) {
            CipherBridge.logDebug("Calling wrapped sig func with just sig (func.length=1)");
            result = func(obfuscatedSig);
        } else if (constantArg !== null && constantArg !== undefined) {
            CipherBridge.logDebug("Calling sig func with constantArg: " + constantArg);
            result = func(constantArg, obfuscatedSig);
        } else {
            CipherBridge.logDebug("Calling sig func without constantArg");
            result = func(obfuscatedSig);
        }

        if (result === undefined || result === null) {
            CipherBridge.onSigError(reqId, "Function returned null/undefined");
            return;
        }

        CipherBridge.logDebug("Sig result type: " + typeof result + ", length: " + String(result).length);
        CipherBridge.onSigResult(reqId, String(result));
    } catch (error) {
        CipherBridge.onSigError(reqId, error + "\n" + (error.stack || ""));
    }
}

function transformN(nValue, reqId) {
    CipherBridge.logDebug("transformN called: nValue=" + nValue + ", reqId=" + reqId);

    try {
        var func = window._nTransformFunc;
        CipherBridge.logDebug("window._nTransformFunc type: " + typeof func);

        if (typeof func !== 'function') {
            CipherBridge.onNError(reqId, "N-transform func not available (type: " + typeof func + ")");
            return;
        }

        var result = func(nValue);
        CipherBridge.logDebug("N-transform raw result: " + (result ? String(result).substring(0, 50) : "null/undefined"));

        if (result === undefined || result === null) {
            CipherBridge.onNError(reqId, "N-transform returned null/undefined");
            return;
        }

        var resultStr = String(result);
        CipherBridge.logDebug("N-transform result: length=" + resultStr.length + ", value=" + resultStr.substring(0, 30));
        CipherBridge.onNResult(reqId, resultStr);
    } catch (error) {
        CipherBridge.onNError(reqId, error + "\n" + (error.stack || ""));
    }
}

function discoverAndInit() {
    CipherBridge.logDebug("========== DISCOVERY AND INIT ==========");

    var nFuncName = "";
    var sigFuncName = "";
    var info = "";

    if (typeof window._cipherSigFunc === 'function') {
        sigFuncName = "exported_sig_func";
        CipherBridge.logDebug("Signature function found on window._cipherSigFunc");
    } else {
        CipherBridge.logDebug("WARNING: window._cipherSigFunc not available (type=" + typeof window._cipherSigFunc + ")");
    }

    if (typeof window._nTransformFunc === 'function') {
        CipherBridge.logDebug("Testing exported window._nTransformFunc...");
        try {
            var testInput = "KdrqFlzJXl9EcCwlmEy";
            var testResult = window._nTransformFunc(testInput);

            CipherBridge.logDebug("N-func test input: " + testInput);
            CipherBridge.logDebug("N-func test result: " + (testResult ? String(testResult).substring(0, 50) : "null"));

            if (typeof testResult === 'string' && testResult !== testInput && testResult.length >= 5) {
                if (/^[a-zA-Z0-9_-]+$/.test(testResult)) {
                    nFuncName = "exported_n_func";
                    info = "export_valid,test=" + testResult.substring(0, 20);
                    CipherBridge.logDebug("N-function VALID: " + testResult);
                } else {
                    info = "export_bad_chars:" + testResult.substring(0, 20);
                    CipherBridge.logDebug("N-function has invalid characters");
                    window._nTransformFunc = null;
                }
            } else {
                info = "export_bad_result:type=" + typeof testResult + ",eq=" + (testResult === testInput);
                CipherBridge.logDebug("N-function test failed: " + info);
                window._nTransformFunc = null;
            }
        } catch(e) {
            info = "export_threw:" + e;
            CipherBridge.logDebug("N-function threw exception: " + e);
            window._nTransformFunc = null;
        }
    } else {
        CipherBridge.logDebug("window._nTransformFunc not exported, trying brute force discovery...");
    }

    if (!nFuncName) {
        try {
            var testInput = "T2Xw3pWQ_Wk0xbOg";
            var keys = Object.getOwnPropertyNames(window);
            var tested = 0;
            var candidates = [];
            var skipped = 0;

            CipherBridge.logDebug("Brute force: scanning " + keys.length + " window properties");

            for (var i = 0; i < keys.length; i++) {
                try {
                    var key = keys[i];
                    if (key.startsWith("webkit") || key.startsWith("on") ||
                        key === "CipherBridge" || key === "_cipherSigFunc" ||
                        key === "_nTransformFunc" || key === "window" || key === "self") {
                        skipped++;
                        continue;
                    }

                    var fn = window[key];
                    if (typeof fn !== 'function') continue;

                    if (fn.length !== 1) continue;

                    tested++;
                    var result = fn(testInput);

                    if (typeof result === 'string' && result !== testInput && result.length >= 5) {
                        if (/^[a-zA-Z0-9_-]+$/.test(result)) {
                            candidates.push({
                                name: key,
                                result: result.substring(0, 30),
                                len: result.length
                            });

                            if (!nFuncName) {
                                window._nTransformFunc = fn;
                                nFuncName = key;
                                CipherBridge.logDebug("N-function discovered: " + key + " -> " + result.substring(0, 30));
                            }
                        }
                    }
                } catch(e) {
                }
            }

            info = "brute_force:tested=" + tested + "/skipped=" + skipped + "/total=" + keys.length;
            if (candidates.length > 0) {
                info += ",candidates=" + candidates.length;
                CipherBridge.logDebug("Candidates found: " + JSON.stringify(candidates.slice(0, 5)));
            }
        } catch(e) {
            info = "brute_force_error:" + e;
            CipherBridge.logDebug("Brute force failed: " + e);
        }
    }

    CipherBridge.logDebug("Discovery complete:");
    CipherBridge.logDebug("  sigFuncName=" + sigFuncName);
    CipherBridge.logDebug("  nFuncName=" + nFuncName);
    CipherBridge.logDebug("  info=" + info);

    CipherBridge.onDiscoveryDone(sigFuncName, nFuncName, info);
    CipherBridge.onPlayerJsLoaded();
}
</script>
<script src="player.js"
    onload="discoverAndInit()"
    onerror="CipherBridge.onPlayerJsError('Failed to load player.js from file')">
</script>
</head><body></body></html>"""

    @JavascriptInterface
    fun logDebug(message: String) {
        Timber.tag(TAG).d("JS: $message")
    }

    @JavascriptInterface
    fun onDiscoveryDone(sigFuncName: String, nFuncName: String, info: String) {
        Timber.tag(TAG).d("=== DISCOVERY COMPLETE ===")
        Timber.tag(TAG).d("Sig function: ${sigFuncName.ifEmpty { "NOT FOUND" }}")
        Timber.tag(TAG).d("N function: ${nFuncName.ifEmpty { "NOT FOUND" }}")
        Timber.tag(TAG).d("Info: $info")

        sigFunctionAvailable = sigFuncName.isNotEmpty()
        if (nFuncName.isNotEmpty()) {
            discoveredNFuncName = nFuncName
            nFunctionAvailable = true
            Timber.tag(TAG).d("N-function AVAILABLE: $nFuncName")
        } else {
            Timber.tag(TAG).e("N-function NOT AVAILABLE")
            nFunctionAvailable = false
        }
    }

    @JavascriptInterface
    fun onNDiscoveryDone(funcName: String, info: String) {
        Timber.tag(TAG).d("Legacy onNDiscoveryDone: funcName=$funcName, info=$info")
        if (funcName.isNotEmpty()) {
            discoveredNFuncName = funcName
            nFunctionAvailable = true
        }
    }

    @JavascriptInterface
    fun onPlayerJsLoaded() {
        Timber.tag(TAG).d("=== PLAYER.JS LOAD COMPLETE ===")
        Timber.tag(TAG).d("sigFunctionAvailable=$sigFunctionAvailable")
        Timber.tag(TAG).d("nFunctionAvailable=$nFunctionAvailable")
        Timber.tag(TAG).d("discoveredNFuncName=$discoveredNFuncName")
        Timber.tag(TAG).d("usingHardcodedMode=$usingHardcodedMode")

        takeInitContinuation()?.resumeSafely { it.resume(this) }
    }

    @JavascriptInterface
    fun onPlayerJsError(error: String) {
        Timber.tag(TAG).e("=== PLAYER.JS LOAD FAILED ===")
        Timber.tag(TAG).e("Error: $error")
        takeInitContinuation()?.resumeSafely {
            it.resumeWithException(CipherException("Player JS load failed: $error"))
        }
    }

    suspend fun deobfuscateSignature(obfuscatedSig: String): String {
        Timber.tag(TAG).d("========== DEOBFUSCATE SIGNATURE ==========")
        Timber.tag(TAG).d("Input sig length: ${obfuscatedSig.length}")
        Timber.tag(TAG).d("Input sig preview: ${obfuscatedSig.take(50)}...")
        Timber.tag(TAG).d("sigInfo: name=${sigInfo?.name}, constantArg=${sigInfo?.constantArg}")

        if (sigInfo == null) {
            Timber.tag(TAG).e("Signature function info not available")
            throw CipherException("Signature function info not available")
        }
        throwIfDead()

        return try {
            withTimeout(EVAL_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        val requestId = sigSlot.arm(cont)
                        val constArgJs = if (sigInfo.constantArg != null) "${sigInfo.constantArg}" else "null"
                        val jsCall = "deobfuscateSig('${sigInfo.name}', $constArgJs, '${escapeJsString(obfuscatedSig)}', $requestId)"
                        Timber.tag(TAG).d("Evaluating JS: ${jsCall.take(100)}...")
                        webView.evaluateJavascript(jsCall, null)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // A renderer that never answers evaluateJavascript is wedged/dead — safety net for
            // providers where onRenderProcessGone doesn't fire.
            Timber.tag(TAG).e("Sig deobfuscation timed out after ${EVAL_TIMEOUT_MS}ms — treating renderer as gone")
            failAsRendererGone("Sig deobfuscation timed out after ${EVAL_TIMEOUT_MS}ms")
        }
    }

    @JavascriptInterface
    fun onSigResult(requestId: Int, result: String) {
        Timber.tag(TAG).d("========== SIGNATURE RESULT ==========")
        Timber.tag(TAG).d("Result length: ${result.length} (requestId=$requestId)")
        Timber.tag(TAG).d("Result preview: ${result.take(50)}...")
        sigSlot.takeIfCurrent(requestId)?.resumeSafely { it.resume(result) }
    }

    @JavascriptInterface
    fun onSigError(requestId: Int, error: String) {
        Timber.tag(TAG).e("========== SIGNATURE ERROR ==========")
        Timber.tag(TAG).e("Error: $error (requestId=$requestId)")
        sigSlot.takeIfCurrent(requestId)?.resumeSafely {
            it.resumeWithException(CipherException("Sig deobfuscation failed: $error"))
        }
    }

    suspend fun transformN(nValue: String): String {
        Timber.tag(TAG).d("========== N-TRANSFORM ==========")
        Timber.tag(TAG).d("Input n value: $nValue")
        Timber.tag(TAG).d("nFunctionAvailable: $nFunctionAvailable")
        Timber.tag(TAG).d("discoveredNFuncName: $discoveredNFuncName")

        if (!nFunctionAvailable) {
            Timber.tag(TAG).e("N-transform function not discovered")
            throw CipherException("N-transform function not discovered")
        }
        throwIfDead()

        return try {
            withTimeout(EVAL_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        val requestId = nSlot.arm(cont)
                        val jsCall = "transformN('${escapeJsString(nValue)}', $requestId)"
                        Timber.tag(TAG).d("Evaluating JS: $jsCall")
                        webView.evaluateJavascript(jsCall, null)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Timber.tag(TAG).e("N-transform timed out after ${EVAL_TIMEOUT_MS}ms — treating renderer as gone")
            failAsRendererGone("N-transform timed out after ${EVAL_TIMEOUT_MS}ms")
        }
    }

    @JavascriptInterface
    fun onNResult(requestId: Int, result: String) {
        Timber.tag(TAG).d("========== N-TRANSFORM RESULT ==========")
        Timber.tag(TAG).d("Result: $result (requestId=$requestId)")
        Timber.tag(TAG).d("Result length: ${result.length}")
        nSlot.takeIfCurrent(requestId)?.resumeSafely { it.resume(result) }
    }

    @JavascriptInterface
    fun onNError(requestId: Int, error: String) {
        Timber.tag(TAG).e("========== N-TRANSFORM ERROR ==========")
        Timber.tag(TAG).e("Error: $error (requestId=$requestId)")
        nSlot.takeIfCurrent(requestId)?.resumeSafely {
            it.resumeWithException(CipherException("N-transform failed: $error"))
        }
    }

    private fun throwIfDead() {
        if (isDead) {
            throw CipherRendererGoneException("CipherWebView renderer is gone — instance must be recreated")
        }
    }

    /** Timeout path: mark this instance dead, clear pending slots, throw renderer-gone. */
    private fun failAsRendererGone(reason: String): Nothing {
        isDead = true
        sigSlot.takeAny()
        nSlot.takeAny()
        throw CipherRendererGoneException(reason)
    }

    fun close() {
        Timber.tag(TAG).d("Closing CipherWebView...")
        destroyWebView()
        Timber.tag(TAG).d("CipherWebView closed")
    }

    private fun destroyWebView() {
        if (destroyed) return
        destroyed = true
        // After a render-process crash some WebView methods can throw — never let teardown crash.
        runCatching {
            webView.clearHistory()
            webView.clearCache(true)
            webView.loadUrl("about:blank")
            webView.onPause()
            webView.removeAllViews()
            webView.destroy()
        }.onFailure { Timber.tag(TAG).w("WebView teardown threw: $it") }
    }

    private fun escapeJsString(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    companion object {
        private const val TAG = "Metrolist_CipherWebView"
        private const val JS_INTERFACE = "CipherBridge"

        // Loading + parsing ~2.8 MB player.js on a slow device takes seconds; a renderer that
        // hasn't answered after this long is dead or wedged (observed OOM kills happen ~1.2 s in).
        private const val CREATE_TIMEOUT_MS = 30_000L

        // A live renderer answers sig/n evaluate calls in milliseconds; this only fires when the
        // renderer died without onRenderProcessGone being delivered (old providers).
        private const val EVAL_TIMEOUT_MS = 15_000L

        /**
         * Builds the export-injected player.js. This scans and copies a ~2.8 MB string — it MUST run
         * off the main thread (create() calls it on Dispatchers.IO before any WebView work), or every
         * WebView (re)build would freeze the UI thread for the duration.
         */
        private fun buildModifiedPlayerJsImpl(
            playerJs: String,
            sigInfo: FunctionNameExtractor.SigFunctionInfo?,
            nFuncInfo: FunctionNameExtractor.NFunctionInfo?,
        ): String {
            val sigFuncName = sigInfo?.name
            val nFuncName = nFuncInfo?.name
            val nArrayIdx = nFuncInfo?.arrayIndex
            val isHardcoded = sigInfo?.isHardcoded == true || nFuncInfo?.isHardcoded == true

            Timber.tag(TAG).d("=== PREPARING PLAYER.JS FOR WEBVIEW ===")
            Timber.tag(TAG).d("Player.js size: ${playerJs.length} chars")
            Timber.tag(TAG).d("Export mode: ${if (isHardcoded) "HARDCODED" else "EXTRACTED"}")
            Timber.tag(TAG).d("Sig function: $sigFuncName (constantArg=${sigInfo?.constantArg})")
            Timber.tag(TAG).d("N function: $nFuncName (arrayIdx=$nArrayIdx)")

            val exports = buildList {
                val sigJsExpr = sigInfo?.jsExpression
                if (sigJsExpr != null) {
                    // Expression-based sig decipher (VM-dispatch players like 9c249f6f).
                    // INPUT is replaced with the sig argument.
                    val expr = sigJsExpr.replace("INPUT", "sig")
                    Timber.tag(TAG).d("Sig: expression-based export: $expr")
                    add("window._cipherSigFunc = function(sig) { try { return $expr; } catch(e) { return null; } };")
                } else if (sigFuncName != null) {
                    val sigConstArgs = sigInfo.constantArgs
                    val preprocessFunc = sigInfo.preprocessFunc
                    val preprocessArgs = sigInfo.preprocessArgs

                    if (!sigConstArgs.isNullOrEmpty() && preprocessFunc != null && !preprocessArgs.isNullOrEmpty()) {
                        val mainArgsStr = sigConstArgs.joinToString(", ")
                        val prepArgsStr = preprocessArgs.joinToString(", ")
                        Timber.tag(TAG).d("Sig function needs full wrapper:")
                        Timber.tag(TAG).d("  $sigFuncName($mainArgsStr, $preprocessFunc($prepArgsStr, sig))")
                        add("window._cipherSigFunc = function(sig) { return $sigFuncName($mainArgsStr, $preprocessFunc($prepArgsStr, sig)); };")
                    } else if (!sigConstArgs.isNullOrEmpty()) {
                        val argsStr = sigConstArgs.joinToString(", ")
                        Timber.tag(TAG).d("Sig function needs wrapper with constant args: $argsStr")
                        add("window._cipherSigFunc = function(sig) { return $sigFuncName($argsStr, sig); };")
                    } else if (isHardcoded) {
                        Timber.tag(TAG).d("Will export sig function $sigFuncName in hardcoded mode (legacy)")
                        add("window._cipherSigFunc = typeof $sigFuncName !== 'undefined' ? $sigFuncName : null;")
                    } else {
                        add("window._cipherSigFunc = typeof $sigFuncName !== 'undefined' ? $sigFuncName : null;")
                    }
                }
                val nJsExpr = nFuncInfo?.jsExpression
                if (nJsExpr != null) {
                    // Expression-based n-transform (VM-dispatch players).
                    val expr = nJsExpr.replace("INPUT", "n")
                    Timber.tag(TAG).d("N: expression-based export: ${expr.take(80)}")
                    add("window._nTransformFunc = function(n) { try { return $expr; } catch(e) { return n; } };")
                } else if (nFuncName != null) {
                    val nConstArgs = nFuncInfo.constantArgs
                    if (!nConstArgs.isNullOrEmpty()) {
                        val argsStr = nConstArgs.joinToString(", ")
                        Timber.tag(TAG).d("N-function needs wrapper with constant args: $argsStr")
                        add("window._nTransformFunc = function(n) { return $nFuncName($argsStr, n); };")
                    } else {
                        val nExpr = if (nArrayIdx != null) {
                            "$nFuncName[$nArrayIdx]"
                        } else {
                            nFuncName
                        }
                        add("window._nTransformFunc = typeof $nFuncName !== 'undefined' ? $nExpr : null;")
                    }
                }
            }

            Timber.tag(TAG).d("Export statements: ${exports.size}")
            exports.forEachIndexed { idx, stmt ->
                Timber.tag(TAG).v("  Export[$idx]: ${stmt.take(80)}...")
            }

            return if (exports.isNotEmpty()) {
                val exportCode = "; " + exports.joinToString(" ")
                val modified = playerJs.replace("})(_yt_player);", "$exportCode })(_yt_player);")
                if (modified == playerJs) {
                    Timber.tag(TAG).w("Export injection point '})(_yt_player);' not found, appending exports")
                    playerJs + "\n" + exportCode
                } else {
                    Timber.tag(TAG).d("Exports injected into IIFE closure")
                    modified
                }
            } else {
                Timber.tag(TAG).w("No exports to inject")
                playerJs
            }
        }

        suspend fun create(
            context: Context,
            playerJs: String,
            sigInfo: FunctionNameExtractor.SigFunctionInfo?,
            nFuncInfo: FunctionNameExtractor.NFunctionInfo? = null,
        ): CipherWebView {
            Timber.tag(TAG).d("=== CREATING CIPHER WEBVIEW ===")
            Timber.tag(TAG).d("playerJs size: ${playerJs.length} chars")
            Timber.tag(TAG).d("sigInfo: $sigInfo")
            Timber.tag(TAG).d("nFuncInfo: $nFuncInfo")

            // Heavy prep (multi-MB string transform + disk write) runs on IO; only WebView
            // construction and the load call happen on the main thread below.
            val cacheDir = withContext(Dispatchers.IO) {
                val modifiedJs = buildModifiedPlayerJsImpl(playerJs, sigInfo, nFuncInfo)
                val dir = File(context.cacheDir, "cipher")
                dir.mkdirs()
                val playerJsFile = File(dir, "player.js")
                playerJsFile.writeText(modifiedJs)
                Timber.tag(TAG).d("Player.js written to cache: ${playerJsFile.absolutePath} (${modifiedJs.length} chars)")
                dir
            }

            var created: CipherWebView? = null
            try {
                return withTimeout(CREATE_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine { cont ->
                            val wv = CipherWebView(context, sigInfo, nFuncInfo, cont)
                            created = wv
                            wv.loadPreparedPlayerJs(cacheDir)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Timber.tag(TAG).e("CipherWebView init timed out after ${CREATE_TIMEOUT_MS}ms — treating renderer as gone")
                destroyQuietly(created)
                throw CipherRendererGoneException("CipherWebView init timed out after ${CREATE_TIMEOUT_MS}ms")
            } catch (e: Exception) {
                // Covers both caller cancellation (CancellationException is rethrown, never
                // swallowed) and init failure via an error resume (e.g. onPlayerJsError ->
                // CipherException): destroy the half-initialized WebView either way, or every
                // failed create() leaks a live renderer that the retry path then multiplies.
                destroyQuietly(created)
                throw e
            }
        }

        private suspend fun destroyQuietly(wv: CipherWebView?) {
            if (wv == null) return
            withContext(NonCancellable + Dispatchers.Main) {
                wv.isDead = true
                wv.takeInitContinuation() // never resume a cancelled continuation later
                wv.destroyWebView()
            }
        }
    }
}

class CipherException(message: String) : Exception(message)

/**
 * The cipher WebView's render process died (kernel OOM kill, crash) or stopped responding.
 * The instance is unusable; callers must drop it and decide whether recreating is worth it
 * (see [RendererRecoveryPolicy]).
 */
class CipherRendererGoneException(message: String) : Exception(message)
