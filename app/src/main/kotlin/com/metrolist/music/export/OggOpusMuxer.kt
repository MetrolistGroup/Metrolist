/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.export

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Minimal Ogg page writer (RFC 3533) for a single logical stream. */
internal class OggStreamWriter(
    private val out: OutputStream,
    private val serial: Int,
) {
    private val segmentTable = ByteArray(255)
    private var segmentCount = 0
    private val body = ByteArrayOutputStream()
    private var sequence = 0
    private var started = false
    private var continuation = false
    private var packetOpen = false
    private var lastCompletedGranule = 0L

    /** Appends one packet. [granuleAfter] is the stream position once this packet has been decoded. */
    fun addPacket(
        data: ByteArray,
        granuleAfter: Long,
    ) {
        if (segmentCount > 0 && !packetOpen && body.size() >= PAGE_TARGET_BYTES) flush(eos = false)
        var offset = 0
        var remaining = data.size
        while (true) {
            if (segmentCount == 255) flush(eos = false)
            val segment = if (remaining >= 255) 255 else remaining
            segmentTable[segmentCount++] = segment.toByte()
            body.write(data, offset, segment)
            offset += segment
            remaining -= segment
            if (segment < 255) {
                packetOpen = false
                break
            }
            packetOpen = true
            if (remaining == 0) {
                // A packet whose size is a multiple of 255 ends with a zero-length segment.
                if (segmentCount == 255) flush(eos = false)
                segmentTable[segmentCount++] = 0
                packetOpen = false
                break
            }
        }
        lastCompletedGranule = granuleAfter
    }

    /** Ends the current page (used to keep header packets on pages of their own). */
    fun flushPage() = flush(eos = false)

    fun finish() = flush(eos = true)

    private fun flush(eos: Boolean) {
        if (segmentCount == 0 && !eos) return
        var type = 0
        if (continuation) type = type or 0x01
        if (!started) type = type or 0x02
        if (eos) type = type or 0x04
        val granule = if (packetOpen) -1L else lastCompletedGranule

        val header = ByteBuffer.allocate(27 + segmentCount).order(ByteOrder.LITTLE_ENDIAN)
        header.put("OggS".toByteArray(Charsets.ISO_8859_1))
        header.put(0)
        header.put(type.toByte())
        header.putLong(granule)
        header.putInt(serial)
        header.putInt(sequence)
        header.putInt(0) // crc placeholder
        header.put(segmentCount.toByte())
        header.put(segmentTable, 0, segmentCount)
        val headerBytes = header.array()
        val bodyBytes = body.toByteArray()

        var crc = 0
        crc = crcUpdate(crc, headerBytes, headerBytes.size)
        crc = crcUpdate(crc, bodyBytes, bodyBytes.size)
        ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc)

        out.write(headerBytes)
        out.write(bodyBytes)

        started = true
        sequence++
        continuation = packetOpen
        segmentCount = 0
        body.reset()
    }

    private companion object {
        const val PAGE_TARGET_BYTES = 4096

        val crcTable =
            IntArray(256) { index ->
                var r = index shl 24
                repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
                r
            }

        fun crcUpdate(
            start: Int,
            data: ByteArray,
            length: Int,
        ): Int {
            var crc = start
            for (i in 0 until length) {
                crc = (crc shl 8) xor crcTable[((crc ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF]
            }
            return crc
        }
    }
}

/** Builds the OpusTags packet (Vorbis comment syntax, RFC 7845). */
internal object OpusTagsBuilder {
    fun build(tags: ExportTags?): ByteArray {
        val comments = mutableListOf<String>()
        if (tags != null) {
            comments += "TITLE=${tags.title}"
            tags.artist?.takeIf { it.isNotBlank() }?.let { comments += "ARTIST=$it" }
            tags.album?.takeIf { it.isNotBlank() }?.let { comments += "ALBUM=$it" }
            tags.trackNumber?.takeIf { it > 0 }?.let { comments += "TRACKNUMBER=$it" }
            tags.year?.let { comments += "DATE=$it" }
            tags.cover?.let { comments += "METADATA_BLOCK_PICTURE=${Base64.getEncoder().encodeToString(flacPicture(it))}" }
        }
        val vendor = "Metrolist".toByteArray(Charsets.UTF_8)
        val buffer = ByteArrayOutputStream()
        buffer.write("OpusTags".toByteArray(Charsets.ISO_8859_1))
        buffer.write(le32(vendor.size))
        buffer.write(vendor)
        buffer.write(le32(comments.size))
        comments.forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            buffer.write(le32(bytes.size))
            buffer.write(bytes)
        }
        return buffer.toByteArray()
    }

    /** FLAC METADATA_BLOCK_PICTURE body: front cover. */
    private fun flacPicture(cover: ExportTags.Cover): ByteArray {
        val mime = cover.mimeType.toByteArray(Charsets.ISO_8859_1)
        return ByteBuffer.allocate(32 + mime.size + cover.data.size)
            .putInt(3) // front cover
            .putInt(mime.size)
            .put(mime)
            .putInt(0) // description length
            .putInt(cover.width)
            .putInt(cover.height)
            .putInt(24)
            .putInt(0)
            .putInt(cover.data.size)
            .put(cover.data)
            .array()
    }

    private fun le32(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}

internal object OpusPacket {
    /** Number of 48 kHz samples encoded in [packet], or 0 if it cannot be parsed. */
    fun samples(packet: ByteArray): Int {
        if (packet.isEmpty()) return 0
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frameSamples =
            when {
                config < 12 -> intArrayOf(480, 960, 1920, 2880)[config % 4]
                config < 16 -> if (config % 2 == 0) 480 else 960
                else -> intArrayOf(120, 240, 480, 960)[config % 4]
            }
        val frames =
            when (toc and 0x03) {
                0 -> 1
                1, 2 -> 2
                else -> {
                    if (packet.size < 2) return 0
                    packet[1].toInt() and 0x3F
                }
            }
        return frames * frameSamples
    }
}

class UnsupportedContainerException(message: String) : IOException(message)
