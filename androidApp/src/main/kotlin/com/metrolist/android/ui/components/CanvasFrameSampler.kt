package com.metrolist.android.ui.components

import android.graphics.Bitmap

internal class CanvasFrameSampler(
    private val intervalMs: Long = 0L,
    private val maxSide: Int = 48,
) {
    private var lastSampleMs: Long? = null

    fun reset() {
        lastSampleMs = null
    }

    fun sampleSize(
        nowMs: Long,
        width: Int,
        height: Int,
    ): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        if (lastSampleMs?.let { nowMs - it < intervalMs } == true) return null
        lastSampleMs = nowMs
        val longestSide = maxOf(width, height)
        val sampleSide = minOf(maxSide, longestSide).toLong()
        return (sampleSide * width / longestSide).toInt().coerceAtLeast(1) to
            (sampleSide * height / longestSide).toInt().coerceAtLeast(1)
    }

    fun detectLetterboxZoom(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h < 6) return 1f

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return detectLetterboxZoom(pixels, w, h)
    }

    internal fun detectLetterboxZoom(
        pixels: IntArray,
        w: Int,
        h: Int,
    ): Float {
        if (w <= 0 || h < 6 || pixels.size < w * h) return 1f

        fun isRowBlack(y: Int): Boolean {
            var sumLum = 0
            val rowOffset = y * w
            val step = maxOf(1, w / 16)
            var count = 0
            for (x in 0 until w step step) {
                val color = pixels[rowOffset + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                sumLum += (r * 77 + g * 150 + b * 29) shr 8
                count++
            }
            return if (count > 0) (sumLum / count) < 24 else true
        }

        if (isRowBlack(h / 2)) return 1f

        var top = 0
        while (top < h / 3 && isRowBlack(top)) {
            top++
        }

        var bottom = h - 1
        while (bottom > (h * 2) / 3 && isRowBlack(bottom)) {
            bottom--
        }

        val minBlackRows = maxOf(1, h / 16)
        if (top >= minBlackRows && (h - 1 - bottom) >= minBlackRows) {
            val activeHeight = bottom - top + 1
            if (activeHeight in (h / 2) until h) {
                return (h.toFloat() / activeHeight.toFloat()).coerceIn(1.0f, 1.6f)
            }
        }
        return 1f
    }
}
