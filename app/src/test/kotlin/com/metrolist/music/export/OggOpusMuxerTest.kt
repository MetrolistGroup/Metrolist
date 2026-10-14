/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

class OggOpusMuxerTest {
    @Test
    fun `packets of every lacing shape survive a round trip`() {
        // 0, <255, 254, 255 and 510 (need a terminating zero segment), and one that spans several pages.
        val sizes = listOf(0, 1, 254, 255, 256, 510, 511, 70_000, 3)
        val packets = sizes.mapIndexed { i, size -> ByteArray(size) { (it + i).toByte() } }
        val out = ByteArrayOutputStream()
        val writer = OggStreamWriter(out, serial = 42)
        packets.forEachIndexed { i, packet ->
            writer.addPacket(packet, granuleAfter = (i + 1) * 960L)
        }
        writer.finish()

        val parsed = OggTestParser.parse(out.toByteArray())
        assertEquals(packets.size, parsed.packets.size)
        packets.forEachIndexed { i, packet -> assertArrayEquals("packet $i", packet, parsed.packets[i]) }
        assertTrue(parsed.pages.all { it.crcValid })
        assertTrue(parsed.pages.all { it.serial == 42 })
        assertEquals(parsed.pages.indices.toList(), parsed.pages.map { it.sequence })
        assertTrue(parsed.pages.first().headerType and 0x02 != 0)
        assertTrue(parsed.pages.last().headerType and 0x04 != 0)
        assertFalse(parsed.pages.dropLast(1).any { it.headerType and 0x04 != 0 })
        assertTrue(parsed.pages.all { it.segmentCount <= 255 })
        assertEquals(packets.size * 960L, parsed.pages.last().granule)
    }

    @Test
    fun `a packet spanning pages marks continuation and unknown granule`() {
        val out = ByteArrayOutputStream()
        val writer = OggStreamWriter(out, serial = 1)
        writer.addPacket(ByteArray(70_000), granuleAfter = 960)
        writer.finish()
        val pages = OggTestParser.parse(out.toByteArray()).pages
        assertTrue(pages.size >= 2)
        assertEquals(-1L, pages.first().granule)
        assertTrue(pages[1].headerType and 0x01 != 0)
        assertEquals(960L, pages.last().granule)
    }

    @Test
    fun `opus packet duration follows the TOC byte`() {
        // CELT FB 20 ms, one frame
        assertEquals(960, OpusPacket.samples(byteArrayOf((31 shl 3).toByte(), 0)))
        // CELT FB 20 ms, two frames
        assertEquals(1920, OpusPacket.samples(byteArrayOf(((31 shl 3) or 1).toByte(), 0)))
        // SILK NB 10 ms
        assertEquals(480, OpusPacket.samples(byteArrayOf(0, 0)))
        // CELT NB 2.5 ms
        assertEquals(120, OpusPacket.samples(byteArrayOf((16 shl 3).toByte(), 0)))
        // code 3 with 3 frames of hybrid FB 20 ms
        assertEquals(2880, OpusPacket.samples(byteArrayOf(((15 shl 3) or 3).toByte(), 3)))
        assertEquals(0, OpusPacket.samples(ByteArray(0)))
    }

    @Test
    fun `tags are written as Vorbis comments`() {
        val packet =
            OpusTagsBuilder.build(
                ExportTags(title = "Хочешь", artist = "Земфира", album = "Vendetta", trackNumber = 3, year = 2002),
            )
        val text = String(packet, Charsets.UTF_8)
        assertTrue(text.startsWith("OpusTags"))
        listOf("TITLE=Хочешь", "ARTIST=Земфира", "ALBUM=Vendetta", "TRACKNUMBER=3", "DATE=2002").forEach {
            assertTrue(it, text.contains(it))
        }
    }

    @Test
    fun `cover is embedded as a FLAC picture block`() {
        val cover = ByteArray(1000) { (it % 251).toByte() }
        val packet =
            OpusTagsBuilder.build(
                ExportTags("t", null, null, cover = ExportTags.Cover(cover, "image/jpeg", 500, 400)),
            )
        val text = String(packet, Charsets.ISO_8859_1)
        val encoded = text.substringAfter("METADATA_BLOCK_PICTURE=")
        val block = Base64.getDecoder().decode(encoded)
        val buffer = java.nio.ByteBuffer.wrap(block)
        assertEquals(3, buffer.int) // front cover
        val mime = ByteArray(buffer.int).also { buffer.get(it) }
        assertEquals("image/jpeg", String(mime))
        assertEquals(0, buffer.int)
        assertEquals(500, buffer.int)
        assertEquals(400, buffer.int)
        buffer.int // depth
        buffer.int // colors
        val data = ByteArray(buffer.int).also { buffer.get(it) }
        assertArrayEquals(cover, data)
    }

    @Test
    fun `webm opus is copied into ogg unchanged`() {
        val webm = resource("sample.webm")
        assertTrue(WebmOpusRemuxer.canRemux(webm))

        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Float>()
        WebmOpusRemuxer.remux(
            webm,
            out,
            ExportTags(title = "Тест", artist = "Артист", album = "Альбом"),
            onProgress = { progress += it },
        )
        val parsed = OggTestParser.parse(out.toByteArray())
        assertTrue(parsed.pages.all { it.crcValid })
        assertEquals(1f, progress.last(), 0f)

        val head = parsed.packets[0]
        assertEquals("OpusHead", String(head, 0, 8))
        assertEquals("OpusTags", String(parsed.packets[1], 0, 8))
        assertTrue(String(parsed.packets[1], Charsets.UTF_8).contains("TITLE=Тест"))

        // ffmpeg's own stream copy is the reference for the audio packets.
        val reference = OggTestParser.parse(resource("sample_ffmpeg_ref.opus").readBytes())
        assertEquals(reference.packets.size, parsed.packets.size)
        for (i in 2 until reference.packets.size) {
            assertArrayEquals("audio packet $i", reference.packets[i], parsed.packets[i])
        }

        val preSkip = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
        val samples = parsed.packets.drop(2).sumOf { OpusPacket.samples(it) }
        assertEquals(preSkip + samples.toLong(), parsed.pages.last().granule)
        assertEquals(0L, parsed.pages.first().granule)

        File("build/export-test-output").mkdirs()
        File("build/export-test-output/remuxed.opus").writeBytes(out.toByteArray())
    }

    @Test
    fun `remuxed file with a real cover is written for external validation`() {
        val cover = ExportTags.Cover(resource("cover.jpg").readBytes(), "image/jpeg", 96, 96)
        val out = ByteArrayOutputStream()
        WebmOpusRemuxer.remux(resource("sample.webm"), out, ExportTags("Тест", "Артист", "Альбом", trackNumber = 2, cover = cover))
        assertTrue(OggTestParser.parse(out.toByteArray()).pages.all { it.crcValid })
        File("build/export-test-output").mkdirs()
        File("build/export-test-output/remuxed_cover.opus").writeBytes(out.toByteArray())
    }

    @Test
    fun `a cover larger than one ogg page still produces valid pages`() {
        val cover = ByteArray(150_000) { (it * 7).toByte() }
        val out = ByteArrayOutputStream()
        WebmOpusRemuxer.remux(
            resource("sample.webm"),
            out,
            ExportTags("t", "a", "b", cover = ExportTags.Cover(cover, "image/png", 1000, 1000)),
        )
        val parsed = OggTestParser.parse(out.toByteArray())
        assertTrue(parsed.pages.all { it.crcValid })
        val tags = String(parsed.packets[1], Charsets.ISO_8859_1)
        val block = Base64.getDecoder().decode(tags.substringAfter("METADATA_BLOCK_PICTURE="))
        assertTrue(block.size > cover.size)
        // OpusHead page is alone on the first page; audio starts on a new page.
        assertEquals(1, parsed.pages.first().segmentCount)
    }

    @Test
    fun `non opus data is rejected`() {
        val junk = File.createTempFile("junk", ".webm").apply { writeBytes(ByteArray(2000) { it.toByte() }) }
        try {
            assertFalse(WebmOpusRemuxer.canRemux(junk))
            assertFalse(WebmOpusRemuxer.canRemux(resource("sample_faststart.m4a")))
        } finally {
            junk.delete()
        }
    }

    private fun resource(name: String): File =
        File(requireNotNull(javaClass.classLoader.getResource("export/$name")) { "missing $name" }.toURI())
}
