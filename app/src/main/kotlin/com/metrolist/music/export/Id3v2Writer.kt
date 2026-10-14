/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Builds an ID3v2.3 tag. Text is UTF-16 with BOM, which every common player and Windows Explorer read. */
object Id3v2Writer {
    fun build(tags: ExportTags): ByteArray {
        val frames = ByteArrayOutputStream()
        frames.write(textFrame("TIT2", tags.title))
        tags.artist?.takeIf { it.isNotBlank() }?.let {
            frames.write(textFrame("TPE1", it))
            frames.write(textFrame("TPE2", it))
        }
        tags.album?.takeIf { it.isNotBlank() }?.let { frames.write(textFrame("TALB", it)) }
        tags.trackNumber?.takeIf { it > 0 }?.let { frames.write(textFrame("TRCK", it.toString())) }
        tags.year?.let { frames.write(textFrame("TYER", it.toString())) }
        tags.cover?.let { frames.write(pictureFrame(it)) }

        val body = frames.toByteArray()
        val out = ByteArrayOutputStream(body.size + 10)
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
        out.write(synchsafe(body.size))
        out.write(body)
        return out.toByteArray()
    }

    private fun textFrame(
        id: String,
        value: String,
    ): ByteArray {
        val text = value.toByteArray(Charsets.UTF_16LE)
        val payload = ByteBuffer.allocate(1 + 2 + text.size + 2)
        payload.put(1) // UTF-16 with BOM
        payload.put(0xFF.toByte()).put(0xFE.toByte())
        payload.put(text)
        payload.put(0).put(0)
        return frame(id, payload.array())
    }

    private fun pictureFrame(cover: ExportTags.Cover): ByteArray {
        val mime = cover.mimeType.toByteArray(Charsets.ISO_8859_1)
        val payload = ByteBuffer.allocate(1 + mime.size + 1 + 1 + 1 + cover.data.size)
        payload.put(0) // ISO-8859-1 for the (empty) description
        payload.put(mime).put(0)
        payload.put(3) // front cover
        payload.put(0) // empty description
        payload.put(cover.data)
        return frame("APIC", payload.array())
    }

    private fun frame(
        id: String,
        payload: ByteArray,
    ): ByteArray =
        ByteBuffer.allocate(10 + payload.size)
            .put(id.toByteArray(Charsets.ISO_8859_1))
            .putInt(payload.size)
            .putShort(0)
            .put(payload)
            .array()

    private fun synchsafe(value: Int) =
        byteArrayOf(
            ((value shr 21) and 0x7F).toByte(),
            ((value shr 14) and 0x7F).toByte(),
            ((value shr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        )
}
