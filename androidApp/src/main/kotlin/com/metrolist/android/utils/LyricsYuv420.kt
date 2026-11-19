package com.metrolist.android.utils

import java.nio.ByteBuffer

internal data class LyricsYuvPlane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int) {
    fun put(offset: Int, value: Int) { buffer.put(buffer.position() + offset, value.coerceIn(0, 255).toByte()) }
}

internal fun writeLyricsYuv420(pixels: IntArray, width: Int, height: Int, planes: List<LyricsYuvPlane>) {
    require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
    require(pixels.size.toLong() == width.toLong() * height && planes.size == 3)
    val yPlane = planes[0]
    for (y in 0 until height) for (x in 0 until width) {
        val p = pixels[y * width + x]
        val r = p ushr 16 and 255; val g = p ushr 8 and 255; val b = p and 255
        yPlane.put(y * yPlane.rowStride + x * yPlane.pixelStride, ((66*r + 129*g + 25*b + 128) shr 8) + 16)
    }
    for (y in 0 until height step 2) for (x in 0 until width step 2) {
        var r = 0; var g = 0; var b = 0
        for (dy in 0..1) for (dx in 0..1) {
            val p = pixels[(y+dy)*width+x+dx]
            r += p ushr 16 and 255; g += p ushr 8 and 255; b += p and 255
        }
        r /= 4; g /= 4; b /= 4
        planes[1].let { it.put(y/2*it.rowStride+x/2*it.pixelStride, ((-38*r-74*g+112*b+128) shr 8)+128) }
        planes[2].let { it.put(y/2*it.rowStride+x/2*it.pixelStride, ((112*r-94*g-18*b+128) shr 8)+128) }
    }
}
