package com.metrolist.shared.ui.components

import kotlin.math.roundToInt
import kotlin.math.sqrt

internal fun blurArtworkPixels(
    pixels: IntArray,
    width: Int,
    height: Int,
    sigma: Int,
    sigmaX: Int = sigma,
) {
    require(width > 0 && height > 0 && pixels.size == width * height)
    if (sigma <= 0) return
    val radius = ((sqrt(4.0 * sigma * sigma + 1.0) - 1.0) / 2.0).roundToInt().coerceAtLeast(1)
    val radiusX = ((sqrt(4.0 * sigmaX * sigmaX + 1.0) - 1.0) / 2.0).roundToInt().coerceAtLeast(1)
    val channels = Array(4) { FloatArray(pixels.size) }
    pixels.forEachIndexed { i, color ->
        val alpha = (color ushr 24).toFloat()
        channels[0][i] = alpha
        channels[1][i] = ((color ushr 16) and 255) * alpha / 255f
        channels[2][i] = ((color ushr 8) and 255) * alpha / 255f
        channels[3][i] = (color and 255) * alpha / 255f
    }
    val scratch = FloatArray(pixels.size)
    val divisor = (radius * 2 + 1).toFloat()
    val divisorX = (radiusX * 2 + 1).toFloat()
    repeat(3) {
        for (channel in channels) {
            for (y in 0 until height) {
                val row = y * width
                var sum = 0f
                for (x in -radiusX..radiusX) sum += channel[row + x.coerceIn(0, width - 1)]
                for (x in 0 until width) {
                    scratch[row + x] = sum / divisorX
                    sum += channel[row + (x + radiusX + 1).coerceAtMost(width - 1)] - channel[row + (x - radiusX).coerceAtLeast(0)]
                }
            }
            for (x in 0 until width) {
                var sum = 0f
                for (y in -radius..radius) sum += scratch[y.coerceIn(0, height - 1) * width + x]
                for (y in 0 until height) {
                    channel[y * width + x] = sum / divisor
                    sum +=
                        scratch[(y + radius + 1).coerceAtMost(height - 1) * width + x] - scratch[(y - radius).coerceAtLeast(0) * width + x]
                }
            }
        }
    }
    for (i in pixels.indices) {
        val alpha = channels[0][i].roundToInt().coerceIn(0, 255)

        fun channel(index: Int): Int = if (alpha == 0) 0 else (channels[index][i] * 255f / channels[0][i]).roundToInt().coerceIn(0, 255)
        pixels[i] = (alpha shl 24) or (channel(1) shl 16) or (channel(2) shl 8) or channel(3)
    }
}
