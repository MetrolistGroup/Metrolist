/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.export

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lossless WebM -> Ogg remux for Opus audio.
 *
 * YouTube serves Opus inside WebM, which has no sensible way to carry tags or cover art. The Opus packets are
 * copied unchanged into an Ogg stream (`.opus`), which every player understands and which has proper tags.
 */
object WebmOpusRemuxer {
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_CODEC_PRIVATE = 0x63A2L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_SIMPLE_BLOCK = 0xA3L

    /** Elements that are entered rather than skipped. Entering without tracking their end handles unknown sizes. */
    private val masters = setOf(ID_SEGMENT, ID_TRACKS, ID_TRACK_ENTRY, ID_CLUSTER, ID_BLOCK_GROUP)

    private const val MAX_ELEMENT_BYTES = 8L * 1024 * 1024

    private class Track(val number: Long, val codecId: String, val codecPrivate: ByteArray?)

    /** True when [file] holds exactly the Opus-in-WebM layout this class can remux. */
    fun canRemux(file: File): Boolean =
        try {
            FileInputStream(file).use { findOpusTrack(EbmlReader(BufferedInputStream(it, 64 * 1024))) != null }
        } catch (_: IOException) {
            false
        }

    fun remux(
        input: File,
        output: OutputStream,
        tags: ExportTags?,
        onProgress: (Float) -> Unit = {},
    ) {
        val total = input.length().coerceAtLeast(1L)
        FileInputStream(input).use { fis ->
            val reader = EbmlReader(BufferedInputStream(fis, 64 * 1024))
            val track = findOpusTrack(reader) ?: throw UnsupportedContainerException("No Opus track")
            val head = track.codecPrivate ?: throw UnsupportedContainerException("Missing OpusHead")
            if (head.size < 19 || String(head, 0, 8, Charsets.ISO_8859_1) != "OpusHead") {
                throw UnsupportedContainerException("Bad OpusHead")
            }
            val preSkip = ByteBuffer.wrap(head, 10, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

            val ogg = OggStreamWriter(output, serial = System.nanoTime().toInt())
            ogg.addPacket(head, 0)
            ogg.flushPage()
            ogg.addPacket(OpusTagsBuilder.build(tags), 0)
            ogg.flushPage()

            var granule = preSkip.toLong()
            var packets = 0
            // The reader is positioned right after the element header of the first cluster's first child.
            reader.pendingBlockElement?.let { (id, data) ->
                granule = writeBlock(ogg, id, data, track.number, granule)?.also { packets++ } ?: granule
            }
            while (true) {
                val element = reader.nextElement() ?: break
                when (element.id) {
                    ID_SIMPLE_BLOCK, ID_BLOCK -> {
                        val data = reader.readPayload(element)
                        granule = writeBlock(ogg, element.id, data, track.number, granule)?.also { packets++ } ?: granule
                        if (packets % 64 == 0) onProgress((reader.position.toFloat() / total).coerceIn(0f, 1f))
                    }
                    in masters -> Unit
                    else -> reader.skip(element)
                }
            }
            if (packets == 0) throw UnsupportedContainerException("No audio packets")
            ogg.finish()
            output.flush()
            onProgress(1f)
        }
    }

    /** Returns the new granule position, or null if the block belongs to another track. */
    private fun writeBlock(
        ogg: OggStreamWriter,
        id: Long,
        data: ByteArray,
        trackNumber: Long,
        granule: Long,
    ): Long? {
        val (number, numberLength) = readVint(data, 0) ?: throw IOException("Bad block header")
        if (number != trackNumber) return null
        val flagsIndex = numberLength + 2
        if (flagsIndex >= data.size) throw IOException("Truncated block")
        val lacing = (data[flagsIndex].toInt() shr 1) and 0x03
        if (lacing != 0) throw UnsupportedContainerException("Laced Opus blocks are not supported")
        val packet = data.copyOfRange(flagsIndex + 1, data.size)
        if (packet.isEmpty()) return null
        val newGranule = granule + OpusPacket.samples(packet)
        ogg.addPacket(packet, newGranule)
        return newGranule
    }

    /** Reads the header up to the first cluster. Leaves [reader] positioned on the first audio block. */
    private fun findOpusTrack(reader: EbmlReader): Track? {
        val tracks = mutableListOf<Track>()
        var number = -1L
        var codec = ""
        var codecPrivate: ByteArray? = null
        var inEntry = false

        fun closeEntry() {
            if (inEntry && number >= 0) tracks += Track(number, codec, codecPrivate)
            number = -1
            codec = ""
            codecPrivate = null
            inEntry = false
        }

        while (true) {
            val element = reader.nextElement() ?: break
            when (element.id) {
                ID_TRACK_ENTRY -> {
                    closeEntry()
                    inEntry = true
                }
                ID_TRACK_NUMBER -> number = reader.readUnsigned(element)
                ID_CODEC_ID -> codec = String(reader.readPayload(element), Charsets.ISO_8859_1).trimEnd('\u0000')
                ID_CODEC_PRIVATE -> codecPrivate = reader.readPayload(element)
                ID_CLUSTER, ID_BLOCK_GROUP -> {
                    closeEntry()
                    if (element.id == ID_CLUSTER) continue
                }
                ID_SIMPLE_BLOCK, ID_BLOCK -> {
                    closeEntry()
                    val opus = tracks.firstOrNull { it.codecId == "A_OPUS" }
                    if (opus != null) reader.pendingBlockElement = element.id to reader.readPayload(element)
                    return opus
                }
                in masters -> Unit
                else -> reader.skip(element)
            }
        }
        closeEntry()
        return null
    }

    private fun readVint(
        data: ByteArray,
        offset: Int,
    ): Pair<Long, Int>? {
        if (offset >= data.size) return null
        val first = data[offset].toInt() and 0xFF
        if (first == 0) return null
        val length = Integer.numberOfLeadingZeros(first) - 24 + 1
        if (offset + length > data.size) return null
        var value = (first and (0xFF shr length)).toLong()
        for (i in 1 until length) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return value to length
    }

    private class Element(val id: Long, val size: Long)

    private class EbmlReader(private val stream: InputStream) {
        var position = 0L
            private set

        var pendingBlockElement: Pair<Long, ByteArray>? = null

        fun nextElement(): Element? {
            val first = stream.read()
            if (first < 0) return null
            position++
            if (first == 0) throw IOException("Invalid element id")
            val idLength = Integer.numberOfLeadingZeros(first) - 24 + 1
            if (idLength > 4) throw IOException("Invalid element id")
            var id = first.toLong()
            repeat(idLength - 1) { id = (id shl 8) or readByte().toLong() }

            val sizeFirst = readByte()
            if (sizeFirst == 0) throw IOException("Invalid element size")
            val sizeLength = Integer.numberOfLeadingZeros(sizeFirst) - 24 + 1
            var size = (sizeFirst and (0xFF shr sizeLength)).toLong()
            var allOnes = size == (0xFF shr sizeLength).toLong()
            repeat(sizeLength - 1) {
                val b = readByte()
                if (b != 0xFF) allOnes = false
                size = (size shl 8) or b.toLong()
            }
            return Element(id, if (allOnes) -1L else size)
        }

        fun readPayload(element: Element): ByteArray {
            if (element.size < 0 || element.size > MAX_ELEMENT_BYTES) throw IOException("Unsupported element size")
            val data = ByteArray(element.size.toInt())
            var read = 0
            while (read < data.size) {
                val n = stream.read(data, read, data.size - read)
                if (n < 0) throw EOFException()
                read += n
            }
            position += data.size
            return data
        }

        fun readUnsigned(element: Element): Long {
            var value = 0L
            for (b in readPayload(element)) value = (value shl 8) or (b.toLong() and 0xFF)
            return value
        }

        fun skip(element: Element) {
            if (element.size < 0) throw IOException("Unknown-size element cannot be skipped")
            var remaining = element.size
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped <= 0) {
                    if (stream.read() < 0) throw EOFException()
                    remaining--
                    position++
                } else {
                    remaining -= skipped
                    position += skipped
                }
            }
        }

        private fun readByte(): Int {
            val b = stream.read()
            if (b < 0) throw EOFException()
            position++
            return b
        }
    }
}
