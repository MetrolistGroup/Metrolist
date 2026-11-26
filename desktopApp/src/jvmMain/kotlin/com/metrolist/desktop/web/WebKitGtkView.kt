package com.metrolist.desktop.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import dev.nucleusframework.window.tao.NativeView
import dev.nucleusframework.window.tao.nucleusGtkPlatformView
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File

/**
 * A WebKitGTK view for native Wayland windows, where wry cannot attach to an X11 parent.
 * Every call must run on the GTK thread, which is Tao's main thread under native Wayland.
 */
internal class WebKitGtkView(
    dataDirectory: File?,
    userAgent: String?,
    initScript: String?,
    allowNavigation: ((String) -> Boolean)? = null,
) {
    /** The URL of the last page that finished loading, or null while a page loads. */
    var finishedUrl by mutableStateOf<String?>(null)
        private set
    var currentUrl by mutableStateOf<String?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set

    private val cookieManager: Pointer
    val widget: Pointer

    // JNA callbacks must stay reachable for as long as GLib may call them.
    private val loadChanged =
        LoadChangedHandler { event, uri ->
            currentUrl = uri ?: currentUrl
            isLoading = event != LOAD_FINISHED
            finishedUrl = if (event == LOAD_FINISHED) uri else null
        }
    private val decidePolicy = allowNavigation?.let(::NavigationPolicyHandler)
    private val pending = mutableSetOf<AsyncResultHandler>()
    private val handlerIds = mutableListOf<Long>()

    init {
        val manager =
            if (dataDirectory == null) {
                WebKit.lib.webkit_website_data_manager_new_ephemeral()
            } else {
                WebKit.lib.webkit_website_data_manager_new(
                    "base-data-directory",
                    dataDirectory.absolutePath,
                    "base-cache-directory",
                    File(dataDirectory, "cache").absolutePath,
                    null,
                )
            }
        val context = WebKit.lib.webkit_web_context_new_with_website_data_manager(manager)
        cookieManager = WebKit.lib.webkit_web_context_get_cookie_manager(context)
        if (dataDirectory != null) {
            WebKit.lib.webkit_cookie_manager_set_persistent_storage(
                cookieManager,
                File(dataDirectory, "cookies.sqlite").absolutePath,
                COOKIE_STORAGE_SQLITE,
            )
        }
        widget = WebKit.lib.webkit_web_view_new_with_context(context)
        GObject.lib.g_object_ref_sink(widget)
        // The context keeps the data manager and the view keeps the context, so drop the creation references.
        GObject.lib.g_object_unref(manager)
        GObject.lib.g_object_unref(context)
        userAgent?.let { WebKit.lib.webkit_settings_set_user_agent(WebKit.lib.webkit_web_view_get_settings(widget), it) }
        initScript?.let {
            val script = WebKit.lib.webkit_user_script_new(it, FRAMES_TOP, INJECT_AT_START, null, null)
            WebKit.lib.webkit_user_content_manager_add_script(WebKit.lib.webkit_web_view_get_user_content_manager(widget), script)
            WebKit.lib.webkit_user_script_unref(script)
        }
        handlerIds += GObject.lib.g_signal_connect_data(widget, "load-changed", loadChanged, null, null, 0)
        decidePolicy?.let { handlerIds += GObject.lib.g_signal_connect_data(widget, "decide-policy", it, null, null, 0) }
        Gtk.lib.gtk_widget_show(widget)
    }

    fun loadUrl(url: String) {
        finishedUrl = null
        isLoading = true
        WebKit.lib.webkit_web_view_load_uri(widget, url)
    }

    /** Returns the result serialized as JSON, matching wry, or an empty string when the script fails. */
    suspend fun evaluateJavaScript(script: String): String =
        awaitResult(
            start = { callback -> WebKit.lib.webkit_web_view_evaluate_javascript(widget, script, -1, null, null, null, callback, null) },
            finish = { result ->
                val value = WebKit.lib.webkit_web_view_evaluate_javascript_finish(widget, result, PointerByReference())
                value
                    ?.let {
                        try {
                            JavaScriptCore.lib.jsc_value_to_json(it, 0)?.let { json ->
                                try {
                                    json.getString(0, "UTF-8")
                                } finally {
                                    GLib.lib.g_free(json)
                                }
                            }
                        } finally {
                            GObject.lib.g_object_unref(value)
                        }
                    }.orEmpty()
            },
        )

    /** Returns every cookie WebKit would send to [uri], including HttpOnly ones that page scripts cannot read. */
    suspend fun cookies(uri: String): List<Pair<String, String>> =
        awaitResult(
            start = { callback -> WebKit.lib.webkit_cookie_manager_get_cookies(cookieManager, uri, null, callback, null) },
            finish = { result ->
                val list = WebKit.lib.webkit_cookie_manager_get_cookies_finish(cookieManager, result, PointerByReference())
                val cookies = mutableListOf<Pair<String, String>>()
                var node = list
                while (node != null) {
                    node.getPointer(0)?.let { cookie ->
                        val name = Soup.lib.soup_cookie_get_name(cookie)
                        val value = Soup.lib.soup_cookie_get_value(cookie)
                        if (name != null && value != null) cookies += name to value
                        Soup.lib.soup_cookie_free(cookie)
                    }
                    node = node.getPointer(Native.POINTER_SIZE.toLong())
                }
                list?.let(GLib.lib::g_list_free)
                cookies
            },
        )

    private suspend fun <T> awaitResult(
        start: (AsyncReadyCallback) -> Unit,
        finish: (Pointer) -> T,
    ): T =
        suspendCancellableCoroutine { continuation ->
            lateinit var handler: AsyncResultHandler
            handler =
                AsyncResultHandler { result ->
                    pending.remove(handler)
                    // JNA swallows exceptions thrown in callbacks, so hand them to the caller instead.
                    val value = runCatching { finish(result) }
                    if (continuation.isActive) continuation.resumeWith(value)
                }
            pending += handler
            try {
                start(handler)
            } catch (error: Throwable) {
                pending.remove(handler)
                throw error
            }
        }

    /** Releases this view's reference. Tao owns removing the widget from its window, so it must not be destroyed here. */
    fun dispose() {
        handlerIds.forEach { GObject.lib.g_signal_handler_disconnect(widget, it) }
        handlerIds.clear()
        WebKit.lib.webkit_web_view_stop_loading(widget)
        GObject.lib.g_object_unref(widget)
    }

    private class LoadChangedHandler(
        private val onChanged: (event: Int, uri: String?) -> Unit,
    ) : LoadChangedCallback {
        override fun invoke(
            webView: Pointer,
            loadEvent: Int,
            userData: Pointer?,
        ) = onChanged(loadEvent, WebKit.lib.webkit_web_view_get_uri(webView))
    }

    private class NavigationPolicyHandler(
        private val allow: (String) -> Boolean,
    ) : DecidePolicyCallback {
        override fun invoke(
            webView: Pointer,
            decision: Pointer,
            decisionType: Int,
            userData: Pointer?,
        ): Boolean {
            if (decisionType != POLICY_NAVIGATION_ACTION && decisionType != POLICY_NEW_WINDOW_ACTION) return false
            val action = WebKit.lib.webkit_navigation_policy_decision_get_navigation_action(decision)
            val uri = WebKit.lib.webkit_uri_request_get_uri(WebKit.lib.webkit_navigation_action_get_request(action)) ?: return false
            if (allow(uri)) return false
            WebKit.lib.webkit_policy_decision_ignore(decision)
            return true
        }
    }

    private class AsyncResultHandler(
        private val onResult: (Pointer) -> Unit,
    ) : AsyncReadyCallback {
        override fun invoke(
            source: Pointer?,
            result: Pointer,
            userData: Pointer?,
        ) = onResult(result)
    }

    interface LoadChangedCallback : Callback {
        fun invoke(
            webView: Pointer,
            loadEvent: Int,
            userData: Pointer?,
        )
    }

    interface DecidePolicyCallback : Callback {
        fun invoke(
            webView: Pointer,
            decision: Pointer,
            decisionType: Int,
            userData: Pointer?,
        ): Boolean
    }

    interface AsyncReadyCallback : Callback {
        fun invoke(
            source: Pointer?,
            result: Pointer,
            userData: Pointer?,
        )
    }

    @Suppress("FunctionName")
    private interface WebKit : Library {
        fun webkit_website_data_manager_new(vararg properties: Any?): Pointer

        fun webkit_website_data_manager_new_ephemeral(): Pointer

        fun webkit_web_context_new_with_website_data_manager(manager: Pointer): Pointer

        fun webkit_navigation_policy_decision_get_navigation_action(decision: Pointer): Pointer

        fun webkit_navigation_action_get_request(action: Pointer): Pointer

        fun webkit_uri_request_get_uri(request: Pointer): String?

        fun webkit_policy_decision_ignore(decision: Pointer)

        fun webkit_web_context_get_cookie_manager(context: Pointer): Pointer

        fun webkit_cookie_manager_set_persistent_storage(
            manager: Pointer,
            filename: String,
            storage: Int,
        )

        fun webkit_web_view_new_with_context(context: Pointer): Pointer

        fun webkit_web_view_get_settings(webView: Pointer): Pointer

        fun webkit_settings_set_user_agent(
            settings: Pointer,
            userAgent: String,
        )

        fun webkit_web_view_get_user_content_manager(webView: Pointer): Pointer

        fun webkit_user_script_new(
            source: String,
            injectedFrames: Int,
            injectionTime: Int,
            allowList: Pointer?,
            blockList: Pointer?,
        ): Pointer

        fun webkit_user_script_unref(script: Pointer)

        fun webkit_user_content_manager_add_script(
            manager: Pointer,
            script: Pointer,
        )

        fun webkit_web_view_load_uri(
            webView: Pointer,
            uri: String,
        )

        fun webkit_web_view_get_uri(webView: Pointer): String?

        fun webkit_web_view_stop_loading(webView: Pointer)

        fun webkit_web_view_evaluate_javascript(
            webView: Pointer,
            script: String,
            length: Long,
            worldName: String?,
            sourceUri: String?,
            cancellable: Pointer?,
            callback: AsyncReadyCallback,
            userData: Pointer?,
        )

        fun webkit_web_view_evaluate_javascript_finish(
            webView: Pointer,
            result: Pointer,
            error: PointerByReference,
        ): Pointer?

        fun webkit_cookie_manager_get_cookies(
            manager: Pointer,
            uri: String,
            cancellable: Pointer?,
            callback: AsyncReadyCallback,
            userData: Pointer?,
        )

        fun webkit_cookie_manager_get_cookies_finish(
            manager: Pointer,
            result: Pointer,
            error: PointerByReference,
        ): Pointer?

        companion object {
            val lib: WebKit by lazy { Native.load("webkit2gtk-4.1", WebKit::class.java) }
        }
    }

    @Suppress("FunctionName")
    private interface JavaScriptCore : Library {
        fun jsc_value_to_json(
            value: Pointer,
            indent: Int,
        ): Pointer?

        companion object {
            val lib: JavaScriptCore by lazy { Native.load("javascriptcoregtk-4.1", JavaScriptCore::class.java) }
        }
    }

    @Suppress("FunctionName")
    private interface Soup : Library {
        fun soup_cookie_get_name(cookie: Pointer): String?

        fun soup_cookie_get_value(cookie: Pointer): String?

        fun soup_cookie_free(cookie: Pointer)

        companion object {
            val lib: Soup by lazy { Native.load("soup-3.0", Soup::class.java) }
        }
    }

    @Suppress("FunctionName")
    private interface GObject : Library {
        fun g_object_ref_sink(instance: Pointer): Pointer

        fun g_object_unref(instance: Pointer)

        fun g_signal_handler_disconnect(
            instance: Pointer,
            handlerId: Long,
        )

        fun g_signal_connect_data(
            instance: Pointer,
            signal: String,
            handler: Callback,
            data: Pointer?,
            destroyData: Pointer?,
            flags: Int,
        ): Long

        companion object {
            val lib: GObject by lazy { Native.load("gobject-2.0", GObject::class.java) }
        }
    }

    @Suppress("FunctionName")
    private interface GLib : Library {
        fun g_list_free(list: Pointer)

        fun g_free(memory: Pointer)

        companion object {
            val lib: GLib by lazy { Native.load("glib-2.0", GLib::class.java) }
        }
    }

    @Suppress("FunctionName")
    private interface Gtk : Library {
        fun gtk_widget_show(widget: Pointer)

        companion object {
            val lib: Gtk by lazy { Native.load("gtk-3", Gtk::class.java) }
        }
    }

    companion object {
        private const val LOAD_FINISHED = 3
        private const val POLICY_NAVIGATION_ACTION = 0
        private const val POLICY_NEW_WINDOW_ACTION = 1
        private const val COOKIE_STORAGE_SQLITE = 1
        private const val FRAMES_TOP = 1
        private const val INJECT_AT_START = 0
    }
}

/** Places [view] in the current Tao window. Native views draw above Compose, so keep overlays outside its bounds. */
@Composable
internal fun WebKitGtkViewHost(
    view: WebKitGtkView,
    modifier: Modifier = Modifier,
) {
    var attached by remember(view) { mutableStateOf(true) }
    DisposableEffect(view) {
        onDispose { attached = false }
    }
    if (attached) {
        NativeView(
            factory = { nucleusGtkPlatformView(handle = { Pointer.nativeValue(view.widget) }) },
            modifier = modifier,
        )
    }
}
