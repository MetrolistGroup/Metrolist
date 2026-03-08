package com.metrolist.desktop.web

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import io.github.kdroidfilter.webview.wry.NavigationHandler
import io.github.kdroidfilter.webview.wry.Rgba
import io.github.kdroidfilter.webview.wry.createWebview
import io.github.kdroidfilter.webview.wry.destroyWebview
import io.github.kdroidfilter.webview.wry.getUrl
import io.github.kdroidfilter.webview.wry.setBounds
import java.awt.Canvas
import java.awt.EventQueue
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.ConcurrentHashMap

internal class LinuxWebViewCanvas(
    private val initialUrl: String,
    private val userAgent: String,
    private val dataDirectory: String,
    private val onNavigate: (String) -> Boolean,
    private val onFailure: () -> Unit,
) : Canvas() {
    @Volatile
    private var webViewId: ULong? = null
    private var lastBounds: LinuxWebViewBounds? = null
    private var boundsPending = false
    private val gdk by lazy { Native.load("gdk-3", Gdk::class.java) }

    @Suppress("FunctionName")
    private interface Gdk : Library {
        fun gdk_display_get_default(): Pointer?

        fun gdk_x11_window_foreign_new_for_display(
            display: Pointer,
            window: NativeLong,
        ): Pointer?

        fun gdk_display_get_monitor_at_window(
            display: Pointer,
            window: Pointer,
        ): Pointer?

        fun gdk_monitor_get_scale_factor(monitor: Pointer): Int

        fun g_object_unref(instance: Pointer)
    }

    val currentUrl: String?
        get() = webViewId?.let(::getUrl)

    init {
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) {
                    updateWebView()
                }

                override fun componentShown(event: ComponentEvent) {
                    updateWebView()
                }
            },
        )
    }

    override fun addNotify() {
        super.addNotify()
        EventQueue.invokeLater(::updateWebView)
    }

    override fun removeNotify() {
        val id = webViewId
        webViewId = null
        lastBounds = null
        try {
            id?.let(::destroyWebview)
        } finally {
            super.removeNotify()
        }
    }

    fun updateWebView() {
        if (!isDisplayable || !isShowing || width <= 0 || height <= 0) return
        try {
            val id = webViewId
            if (id != null) {
                updateBounds(id)
                return
            }
            webViewId =
                createWebview(
                    parentHandle = Native.getComponentID(this).toULong(),
                    width = width,
                    height = height,
                    url = initialUrl,
                    userAgent = userAgent,
                    dataDirectory = dataDirectory,
                    zoom = true,
                    transparent = false,
                    backgroundColor = Rgba(0u, 0u, 0u, 255u),
                    initScript = null,
                    clipboard = true,
                    devTools = false,
                    navigationGestures = true,
                    incognito = false,
                    autoplay = false,
                    focused = true,
                    navHandler =
                        object : NavigationHandler {
                            override fun handleNavigation(url: String): Boolean = onNavigate(url)
                        },
                )
            updateBounds(checkNotNull(webViewId))
        } catch (_: Exception) {
            onFailure()
        }
    }

    private fun updateBounds(id: ULong) {
        if (boundsPending) return
        val handle = Native.getComponentID(this)
        val transform = graphicsConfiguration.defaultTransform
        val awtWidth = width
        val awtHeight = height
        boundsPending = true
        // Wry owns the GTK main loop; AWT geometry is captured before crossing to that thread.
        dispatchToGtk {
            val result =
                runCatching {
                    if (webViewId != id) return@runCatching null
                    val display = checkNotNull(gdk.gdk_display_get_default())
                    val window = checkNotNull(gdk.gdk_x11_window_foreign_new_for_display(display, NativeLong(handle)))
                    val scale =
                        try {
                            val monitor = checkNotNull(gdk.gdk_display_get_monitor_at_window(display, window))
                            gdk.gdk_monitor_get_scale_factor(monitor)
                        } finally {
                            gdk.g_object_unref(window)
                        }
                    linuxWebViewBounds(awtWidth, awtHeight, transform.scaleX, transform.scaleY, scale)
                }
            EventQueue.invokeLater {
                boundsPending = false
                if (webViewId == id && isDisplayable) {
                    result
                        .onSuccess { bounds ->
                            if (bounds != null && lastBounds != bounds) {
                                runCatching { setBounds(id, 0, 0, bounds.width, bounds.height) }
                                    .onSuccess { lastBounds = bounds }
                                    .onFailure { onFailure() }
                            }
                        }.onFailure { onFailure() }
                }
            }
        }
    }
}

private fun interface GtkTask : Callback {
    fun invoke(data: Pointer?): Int
}

@Suppress("FunctionName")
private interface GLib : Library {
    fun g_idle_add_full(
        priority: Int,
        callback: GtkTask,
        data: Pointer?,
        destroy: Pointer?,
    ): Int
}

private val gtkTasks = ConcurrentHashMap.newKeySet<GtkTask>()
private val glib by lazy { Native.load("glib-2.0", GLib::class.java) }

private fun dispatchToGtk(block: () -> Unit) {
    lateinit var callback: GtkTask
    callback =
        GtkTask {
            try {
                block()
            } finally {
                gtkTasks.remove(callback)
            }
            0
        }
    gtkTasks.add(callback)
    glib.g_idle_add_full(0, callback, null, null)
}
