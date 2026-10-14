/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class Id3AndSnifferTest {
    private class Frame(val id: String, val payload: ByteArray)

    private fun parse(tag: ByteArray): List<Frame> {
        assertEquals("ID3", String(tag, 0, 3))
        assertEquals(3, tag[3].toInt())
        val size = (tag[6].toInt() shl 21) or (tag[7].toInt() shl 14) or (tag[8].toInt() shl 7) or tag[9].toInt()
        assertEquals(tag.size - 10, size)
        assertTrue(tag.drop(6).take(4).all { it.toInt() and 0x80 == 0 })
        val frames = mutableListOf<Frame>()
        var pos = 10
        while (pos < tag.size) {
            val id = String(tag, pos, 4, Charsets.ISO_8859_1)
            val length = ByteBuffer.wrap(tag, pos + 4, 4).int
            frames += Frame(id, tag.copyOfRange(pos + 10, pos + 10 + length))
            pos += 10 + length
        }
        assertEquals(tag.size, pos)
        return frames
    }

    private fun text(frame: Frame): String {
        assertEquals(1, frame.payload[0].toInt())
        assertEquals(0xFF, frame.payload[1].toInt() and 0xFF)
        assertEquals(0xFE, frame.payload[2].toInt() and 0xFF)
        return String(frame.payload, 3, frame.payload.size - 3 - 2, Charsets.UTF_16LE)
    }

    @Test
    fun `text frames are UTF-16 with BOM`() {
        val tag = Id3v2Writer.build(ExportTags("Хочешь", "Земфира", "Vendetta", trackNumber = 4, year = 2002))
        val frames = parse(tag).associateBy { it.id }
        assertEquals("Хочешь", text(frames.getValue("TIT2")))
        assertEquals("Земфира", text(frames.getValue("TPE1")))
        assertEquals("Vendetta", text(frames.getValue("TALB")))
        assertEquals("4", text(frames.getValue("TRCK")))
        assertEquals("2002", text(frames.getValue("TYER")))
    }

    @Test
    fun `picture frame carries mime type and bytes`() {
        val cover = ByteArray(5000) { it.toByte() }
        val tag = Id3v2Writer.build(ExportTags("t", null, null, cover = ExportTags.Cover(cover, "image/jpeg", 10, 10)))
        val apic = parse(tag).first { it.id == "APIC" }.payload
        assertEquals(0, apic[0].toInt())
        val mimeEnd = (1 until apic.size).first { apic[it].toInt() == 0 }
        assertEquals("image/jpeg", String(apic, 1, mimeEnd - 1, Charsets.ISO_8859_1))
        assertEquals(3, apic[mimeEnd + 1].toInt())
        assertArrayEquals(cover, apic.copyOfRange(mimeEnd + 3, apic.size))
    }

    @Test
    fun `tag in front of mp3 frames is written for external validation`() {
        val cover = ExportTags.Cover(resource("cover.jpg").readBytes(), "image/jpeg", 96, 96)
        val tag = Id3v2Writer.build(ExportTags("Хочешь", "Земфира", "Vendetta", trackNumber = 4, year = 2002, cover = cover))
        File("build/export-test-output").mkdirs()
        File("build/export-test-output/tagged.mp3").writeBytes(tag + resource("sample_frames.mp3").readBytes())
    }

    @Test
    fun `optional fields are left out`() {
        val ids = parse(Id3v2Writer.build(ExportTags("t", null, null))).map { it.id }
        assertEquals(listOf("TIT2"), ids)
    }

    @Test
    fun `sniffer identifies containers by content`() {
        assertEquals(SourceContainer.MP4, AudioSniffer.detect(resource("sample_faststart.m4a")))
        assertEquals(SourceContainer.MP4, AudioSniffer.detect(resource("sample_fragmented.m4a")))
        assertEquals(SourceContainer.WEBM, AudioSniffer.detect(resource("sample.webm")))
        assertEquals(SourceContainer.UNKNOWN, AudioSniffer.detect(resource("sample_ffmpeg_ref.opus")))
        val html = File.createTempFile("sniff", ".bin").apply { writeText("<html>403 Forbidden</html>") }
        try {
            assertEquals(SourceContainer.UNKNOWN, AudioSniffer.detect(html))
        } finally {
            html.delete()
        }
        assertEquals(SourceContainer.UNKNOWN, AudioSniffer.detect(File("does-not-exist")))
    }

    @Test
    fun `extensions match the real content`() {
        assertEquals("m4a", AudioSniffer.originalExtension(SourceContainer.MP4, "mp4a.40.2"))
        assertEquals("opus", AudioSniffer.originalExtension(SourceContainer.WEBM, "opus"))
        assertEquals("webm", AudioSniffer.originalExtension(SourceContainer.WEBM, "vorbis"))
        assertEquals(SourceContainer.WEBM, AudioSniffer.fromMimeType("audio/webm; codecs=\"opus\""))
        assertEquals(SourceContainer.MP4, AudioSniffer.fromMimeType("audio/mp4"))
        assertEquals(SourceContainer.UNKNOWN, AudioSniffer.fromMimeType(null))
    }

    private fun resource(name: String): File =
        File(requireNotNull(javaClass.classLoader.getResource("export/$name")) { "missing $name" }.toURI())
}
