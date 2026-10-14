/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A tiny Ogg demuxer used to check what the muxer produced. */
internal object OggTestParser {
    class Page(
        val headerType: Int,
        val granule: Long,
        val serial: Int,
        val sequence: Int,
        val crcValid: Boolean,
        val segmentCount: Int,
    )

    class Result(val pages: List<Page>, val packets: List<ByteArray>)

    fun parse(data: ByteArray): Result {
        val pages = mutableListOf<Page>()
        val packets = mutableListOf<ByteArray>()
        var current = ByteArrayOutputStream()
        var pos = 0
        while (pos < data.size) {
            require(String(data, pos, 4, Charsets.ISO_8859_1) == "OggS") { "No capture pattern at $pos" }
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val type = data[pos + 5].toInt() and 0xFF
            val granule = buffer.getLong(pos + 6)
            val serial = buffer.getInt(pos + 14)
            val sequence = buffer.getInt(pos + 18)
            val storedCrc = buffer.getInt(pos + 22)
            val segments = data[pos + 26].toInt() and 0xFF
            var bodySize = 0
            for (i in 0 until segments) bodySize += data[pos + 27 + i].toInt() and 0xFF
            val pageSize = 27 + segments + bodySize

            val copy = data.copyOfRange(pos, pos + pageSize)
            copy[22] = 0
            copy[23] = 0
            copy[24] = 0
            copy[25] = 0
            pages += Page(type, granule, serial, sequence, crc(copy) == storedCrc, segments)

            var bodyPos = pos + 27 + segments
            for (i in 0 until segments) {
                val size = data[pos + 27 + i].toInt() and 0xFF
                current.write(data, bodyPos, size)
                bodyPos += size
                if (size < 255) {
                    packets += current.toByteArray()
                    current = ByteArrayOutputStream()
                }
            }
            pos += pageSize
        }
        require(current.size() == 0) { "Stream ends inside a packet" }
        return Result(pages, packets)
    }

    private fun crc(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc shl 8 xor TABLE[(crc ushr 24 xor (b.toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }

    private val TABLE =
        IntArray(256) { index ->
            var r = index shl 24
            repeat(8) { r = if (r and Int.MIN_VALUE != 0) r shl 1 xor 0x04C11DB7 else r shl 1 }
            r
        }
}
