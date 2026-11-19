package com.metrolist.android.utils

import java.io.OutputStream

internal class LyricsGifWriter(private val output: OutputStream, private val width: Int, private val height: Int, private val maxBytes: Long = 16L*1024*1024, private val checkCancelled: () -> Unit = {}) {
    private var written = 0L
    private var finished = false
    private var frames = 0
    init {
        require(width in 1..720 && height in 1..720 && maxBytes > 0)
        bytes("GIF89a".toByteArray(Charsets.US_ASCII)); short(width); short(height); bytes(byteArrayOf(0xf7.toByte(),0,0))
        for (i in 0..255) {
            val rgb = lyricsGifPaletteColor(i)
            bytes(byteArrayOf((rgb ushr 16).toByte(),(rgb ushr 8).toByte(),rgb.toByte()))
        }
        bytes(byteArrayOf(0x21,0xff.toByte(),11)); bytes("NETSCAPE2.0".toByteArray(Charsets.US_ASCII)); bytes(byteArrayOf(3,1,0,0,0))
    }
    fun frame(pixels: IntArray, delayCentiseconds: Int) {
        checkCancelled(); check(!finished && frames < 300)
        require(pixels.size == width*height && delayCentiseconds in 1..65535)
        bytes(byteArrayOf(0x21,0xf9.toByte(),4,4)); short(delayCentiseconds); bytes(byteArrayOf(0,0))
        bytes(byteArrayOf(0x2c)); short(0); short(0); short(width); short(height); bytes(byteArrayOf(0,8))
        val indexed = ByteArray(pixels.size)
        for (i in pixels.indices) { if (i%1024 == 0) checkCancelled(); indexed[i] = lyricsGifPaletteIndex(pixels[i]).toByte() }
        val bits = Codes()
        val dictionary = HashMap<Int,Int>(4096)
        var next = 258; var widthBits = 9
        bits.code(256,widthBits)
        var prefix = indexed[0].toInt() and 255
        for (i in 1 until indexed.size) {
            if (i%1024 == 0) checkCancelled()
            val symbol = indexed[i].toInt() and 255
            val key = (prefix shl 8) or symbol
            val match = dictionary[key]
            if (match != null) prefix = match
            else {
                bits.code(prefix,widthBits)
                if (next < 4096) {
                    dictionary[key] = next++
                    if (next > (1 shl widthBits) && widthBits < 12) widthBits++
                } else {
                    bits.code(256,widthBits); dictionary.clear(); next = 258; widthBits = 9
                }
                prefix = symbol
            }
        }
        bits.code(prefix,widthBits)
        if (next == (1 shl widthBits) && widthBits < 12) widthBits++
        bits.code(257,widthBits); bits.finish(); bytes(byteArrayOf(0)); frames++
    }
    fun finish() { check(!finished && frames > 0); checkCancelled(); bytes(byteArrayOf(0x3b)); output.flush(); finished = true }
    private fun short(value: Int) = bytes(byteArrayOf(value.toByte(),(value ushr 8).toByte()))
    private fun bytes(value: ByteArray) { check(value.size <= maxBytes-written) { "GIF exceeds export limit" }; output.write(value); written += value.size }
    private inner class Codes {
        private var pending = 0
        private var count = 0
        private val block = ByteArray(255)
        private var used = 0
        fun code(value: Int, width: Int) {
            pending = pending or (value shl count); count += width
            while (count >= 8) { append(pending and 255); pending = pending ushr 8; count -= 8 }
        }
        private fun append(value: Int) { block[used++] = value.toByte(); if (used == 255) flush() }
        private fun flush() { if (used > 0) { bytes(byteArrayOf(used.toByte())); bytes(block.copyOf(used)); used = 0 } }
        fun finish() { if (count > 0) append(pending and 255); flush() }
    }
}

internal fun lyricsGifPaletteColor(index: Int): Int {
    require(index in 0..255)
    if (index >= 216) { val c = (index-216)*255/39; return (c shl 16) or (c shl 8) or c }
    return ((index/36*51) shl 16) or ((index/6%6*51) shl 8) or (index%6*51)
}

internal fun lyricsGifPaletteIndex(rgb: Int): Int {
    val r = rgb ushr 16 and 255; val g = rgb ushr 8 and 255; val b = rgb and 255
    val cube = (r*5+127)/255*36+(g*5+127)/255*6+(b*5+127)/255
    val gray = ((r+g+b)*39+382)/765
    fun distance(color: Int): Int {
        val dr = r-(color ushr 16 and 255); val dg = g-(color ushr 8 and 255); val db = b-(color and 255)
        return dr*dr+dg*dg+db*db
    }
    return if (distance(lyricsGifPaletteColor(gray+216)) < distance(lyricsGifPaletteColor(cube))) gray+216 else cube
}
