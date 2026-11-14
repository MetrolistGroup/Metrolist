package com.metrolist.desktop.window

import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate

internal class MonotonicRenderTime {
    private var previous: Long? = null

    fun next(timeNanos: Long): Long = maxOf(previous ?: timeNanos, timeNanos).also { previous = it }
}

internal fun guardRenderTime(layer: SkiaLayer): AutoCloseable {
    val delegate = layer.renderDelegate ?: return AutoCloseable { }
    val time = MonotonicRenderTime()
    val guarded =
        object : SkikoRenderDelegate {
            override fun onRender(
                canvas: Canvas,
                width: Int,
                height: Int,
                nanoTime: Long,
            ) {
                delegate.onRender(canvas, width, height, time.next(nanoTime))
            }
        }
    layer.renderDelegate = guarded
    return AutoCloseable {
        if (layer.renderDelegate === guarded) layer.renderDelegate = delegate
    }
}
