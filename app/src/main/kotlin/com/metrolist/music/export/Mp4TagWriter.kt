/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.export

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Adds iTunes-style tags (moov/udta/meta/ilst) to an MP4/M4A file without touching the audio samples.
 *
 * Only the `moov` box is rewritten. Absolute chunk offsets (`stco`/`co64`) are shifted by the size
 * difference so progressive files keep working; fragmented files use offsets relative to `moof`/`sidx`
 * and are unaffected.
 */
object Mp4TagWriter {
    private const val MAX_MOOV_BYTES = 64L * 1024 * 1024
    private val containers = setOf("trak", "mdia", "minf", "stbl")

    private class TopBox(val type: String, val offset: Long, val size: Long)

    fun write(
        input: File,
        output: OutputStream,
        tags: ExportTags,
    ) {
        RandomAccessFile(input, "r").use { raf ->
            val moov =
                topLevelBoxes(raf).firstOrNull { it.type == "moov" }
                    ?: throw IOException("No moov box")
            if (moov.size > MAX_MOOV_BYTES) throw IOException("moov box is too large")

            val headerSize = headerSizeAt(raf, moov.offset)
            val payload = ByteArray((moov.size - headerSize).toInt())
            raf.seek(moov.offset + headerSize)
            raf.readFully(payload)

            val children = parseBoxes(payload).toMutableList()
            val oldUdta = children.firstOrNull { it.type == "udta" }
            children.removeAll { it.type == "udta" }
            val keptUdtaChildren =
                oldUdta?.let { parseBoxes(it.payload).filter { child -> child.type != "meta" } }.orEmpty()
            val newUdta = box("udta", keptUdtaChildren.joinToBytes() + buildMeta(tags))

            // moov is always rewritten with a 32-bit size header.
            val newMoovSize = 8L + children.sumOf { it.totalSize } + newUdta.size
            val delta = newMoovSize - moov.size
            val oldMoovEnd = moov.offset + moov.size

            val patchedChildren =
                children.map { child ->
                    if (child.type == "trak" && delta != 0L) {
                        Child("trak", patchChunkOffsets(child.payload, oldMoovEnd, delta))
                    } else {
                        child
                    }
                }
            val newMoovPayload = patchedChildren.joinToBytes() + newUdta
            if (newMoovPayload.size + 8L > Int.MAX_VALUE) throw IOException("moov box is too large")

            copyRange(raf, 0, moov.offset, output)
            output.write(box("moov", newMoovPayload))
            copyRange(raf, oldMoovEnd, raf.length(), output)
        }
    }

    private class Child(val type: String, val payload: ByteArray) {
        val totalSize: Long get() = 8L + payload.size
    }

    private fun List<Child>.joinToBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        forEach { out.write(box(it.type, it.payload)) }
        return out.toByteArray()
    }

    private fun parseBoxes(data: ByteArray): List<Child> {
        val result = mutableListOf<Child>()
        var pos = 0
        while (pos + 8 <= data.size) {
            val size = ByteBuffer.wrap(data, pos, 4).int.toLong() and 0xFFFFFFFFL
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            val header = if (size == 1L) 16 else 8
            if (header == 16 && pos + 16 > data.size) throw IOException("Corrupt box '$type'")
            val boxSize =
                when (size) {
                    0L -> data.size - pos.toLong()
                    1L -> ByteBuffer.wrap(data, pos + 8, 8).long
                    else -> size
                }
            val boxEnd = pos + boxSize
            if (boxEnd > data.size || boxEnd < pos + header) throw IOException("Corrupt box '$type'")
            result += Child(type, data.copyOfRange(pos + header, boxEnd.toInt()))
            pos = boxEnd.toInt()
        }
        return result
    }

    private fun patchChunkOffsets(
        trak: ByteArray,
        threshold: Long,
        delta: Long,
    ): ByteArray {
        val copy = trak.copyOf()
        patchContainer(copy, 0, copy.size, threshold, delta)
        return copy
    }

    private fun patchContainer(
        data: ByteArray,
        start: Int,
        end: Int,
        threshold: Long,
        delta: Long,
    ) {
        var pos = start
        while (pos + 8 <= end) {
            val size = ByteBuffer.wrap(data, pos, 4).int.toLong() and 0xFFFFFFFFL
            if (size < 8 || pos + size > end) return
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            val bodyStart = pos + 8
            val boxEnd = (pos + size).toInt()
            when {
                type in containers -> patchContainer(data, bodyStart, boxEnd, threshold, delta)
                type == "stco" -> patchTable(data, bodyStart, wide = false, threshold, delta)
                type == "co64" -> patchTable(data, bodyStart, wide = true, threshold, delta)
            }
            pos = boxEnd
        }
    }

    private fun patchTable(
        data: ByteArray,
        bodyStart: Int,
        wide: Boolean,
        threshold: Long,
        delta: Long,
    ) {
        val buffer = ByteBuffer.wrap(data)
        val count = buffer.getInt(bodyStart + 4)
        var pos = bodyStart + 8
        repeat(count) {
            if (wide) {
                val value = buffer.getLong(pos)
                if (value >= threshold) buffer.putLong(pos, value + delta)
                pos += 8
            } else {
                val value = buffer.getInt(pos).toLong() and 0xFFFFFFFFL
                if (value >= threshold) {
                    val shifted = value + delta
                    if (shifted > 0xFFFFFFFFL) throw IOException("Chunk offset overflow")
                    buffer.putInt(pos, shifted.toInt())
                }
                pos += 4
            }
        }
    }

    private fun buildMeta(tags: ExportTags): ByteArray {
        val items = ByteArrayOutputStream()
        items.write(textItem(byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()), tags.title))
        tags.artist?.takeIf { it.isNotBlank() }?.let {
            items.write(textItem(byteArrayOf(0xA9.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte()), it))
            items.write(textItem("aART".toByteArray(Charsets.ISO_8859_1), it))
        }
        tags.album?.takeIf { it.isNotBlank() }?.let {
            items.write(textItem(byteArrayOf(0xA9.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 'b'.code.toByte()), it))
        }
        tags.year?.let {
            items.write(textItem(byteArrayOf(0xA9.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 'y'.code.toByte()), it.toString()))
        }
        tags.trackNumber?.takeIf { it > 0 }?.let { track ->
            val payload = ByteBuffer.allocate(8).putShort(0).putShort(track.toShort()).putShort(0).putShort(0).array()
            items.write(itemWithData("trkn".toByteArray(Charsets.ISO_8859_1), 0, payload))
        }
        tags.cover?.let { cover ->
            val code = if (cover.mimeType.equals("image/png", ignoreCase = true)) 14 else 13
            items.write(itemWithData("covr".toByteArray(Charsets.ISO_8859_1), code, cover.data))
        }

        val hdlrPayload =
            ByteBuffer.allocate(25)
                .putInt(0) // version + flags
                .putInt(0) // pre_defined
                .put("mdir".toByteArray(Charsets.ISO_8859_1))
                .put("appl".toByteArray(Charsets.ISO_8859_1))
                .put(ByteArray(8)) // reserved
                .put(0) // empty name
                .array()
        val metaPayload = ByteArrayOutputStream()
        metaPayload.write(ByteArray(4)) // meta is a full box
        metaPayload.write(box("hdlr", hdlrPayload))
        metaPayload.write(box("ilst", items.toByteArray()))
        return box("meta", metaPayload.toByteArray())
    }

    private fun textItem(
        type: ByteArray,
        value: String,
    ) = itemWithData(type, 1, value.toByteArray(Charsets.UTF_8))

    private fun itemWithData(
        type: ByteArray,
        dataType: Int,
        payload: ByteArray,
    ): ByteArray {
        val data = ByteBuffer.allocate(8 + payload.size).putInt(dataType).putInt(0).put(payload).array()
        return box(type, box("data", data))
    }

    private fun box(
        type: String,
        payload: ByteArray,
    ) = box(type.toByteArray(Charsets.ISO_8859_1), payload)

    private fun box(
        type: ByteArray,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 8)
        DataOutputStream(out).apply {
            writeInt(payload.size + 8)
            write(type)
            write(payload)
        }
        return out.toByteArray()
    }

    private fun topLevelBoxes(raf: RandomAccessFile): List<TopBox> {
        val result = mutableListOf<TopBox>()
        var pos = 0L
        val length = raf.length()
        val header = ByteArray(16)
        while (pos + 8 <= length) {
            raf.seek(pos)
            raf.readFully(header, 0, 8)
            val size32 = ByteBuffer.wrap(header, 0, 4).int.toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            val size =
                when (size32) {
                    0L -> length - pos
                    1L -> {
                        raf.readFully(header, 8, 8)
                        ByteBuffer.wrap(header, 8, 8).long
                    }
                    else -> size32
                }
            if (size < 8 || pos + size > length) throw IOException("Corrupt top-level box '$type'")
            result += TopBox(type, pos, size)
            pos += size
        }
        return result
    }

    private fun headerSizeAt(
        raf: RandomAccessFile,
        offset: Long,
    ): Int {
        raf.seek(offset)
        return if (raf.readInt() == 1) 16 else 8
    }

    private fun copyRange(
        raf: RandomAccessFile,
        from: Long,
        to: Long,
        output: OutputStream,
    ) {
        raf.seek(from)
        val buffer = ByteArray(64 * 1024)
        var remaining = to - from
        while (remaining > 0) {
            val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("Unexpected end of file")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }
}
