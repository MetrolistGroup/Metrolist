/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

class Mp4TagWriterTest {
    private class Box(val type: String, val start: Int, val size: Int, val headerSize: Int = 8) {
        val payloadStart get() = start + headerSize
        val end get() = start + size
    }

    private fun boxes(
        data: ByteArray,
        from: Int = 0,
        to: Int = data.size,
    ): List<Box> {
        val result = mutableListOf<Box>()
        var pos = from
        while (pos + 8 <= to) {
            val size = ByteBuffer.wrap(data, pos, 4).int
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            result += Box(type, pos, size)
            pos += size
        }
        assertEquals("boxes must tile the range exactly", to, pos)
        return result
    }

    private fun child(
        data: ByteArray,
        parent: Box,
        type: String,
        skip: Int = 0,
    ): Box? = boxes(data, parent.payloadStart + skip, parent.end).firstOrNull { it.type == type }

    private fun path(
        data: ByteArray,
        vararg types: String,
    ): Box? {
        var current = boxes(data).firstOrNull { it.type == types[0] } ?: return null
        for (type in types.drop(1)) {
            // meta is a full box: 4 bytes of version/flags precede its children.
            current = child(data, current, type, skip = if (current.type == "meta") 4 else 0) ?: return null
        }
        return current
    }

    private fun firstChunkOffset(data: ByteArray): Long {
        val stco = path(data, "moov", "trak", "mdia", "minf", "stbl", "stco")
        return ByteBuffer.wrap(data).getInt(stco!!.payloadStart + 8).toLong() and 0xFFFFFFFFL
    }

    private fun write(
        fixture: String,
        tags: ExportTags,
    ): Pair<ByteArray, ByteArray> {
        val input = resource(fixture)
        val out = ByteArrayOutputStream()
        Mp4TagWriter.write(input, out, tags)
        File("build/export-test-output").mkdirs()
        File("build/export-test-output/tagged_$fixture").writeBytes(out.toByteArray())
        return input.readBytes() to out.toByteArray()
    }

    private val cover = ExportTags.Cover(resource("cover.jpg").readBytes(), "image/jpeg", 96, 96)
    private val tags =
        ExportTags(
            title = "Хочешь — тест",
            artist = "Земфира",
            album = "Vendetta",
            trackNumber = 7,
            year = 2002,
            cover = cover,
        )

    private fun assertTagsPresent(data: ByteArray) {
        val ilst = path(data, "moov", "udta", "meta", "ilst")
        assertNotNull("ilst", ilst)
        val items = boxes(data, ilst!!.payloadStart, ilst.end).associateBy { it.type }
        fun text(type: String): String {
            val dataBox = child(data, items.getValue(type), "data")!!
            return String(data, dataBox.payloadStart + 8, dataBox.end - dataBox.payloadStart - 8, Charsets.UTF_8)
        }
        assertEquals("Хочешь — тест", text("©nam"))
        assertEquals("Земфира", text("©ART"))
        assertEquals("Земфира", text("aART"))
        assertEquals("Vendetta", text("©alb"))
        assertEquals("2002", text("©day"))

        val trkn = child(data, items.getValue("trkn"), "data")!!
        assertEquals(7, ByteBuffer.wrap(data).getShort(trkn.payloadStart + 8 + 2).toInt())

        val covr = child(data, items.getValue("covr"), "data")!!
        assertEquals(13, ByteBuffer.wrap(data).getInt(covr.payloadStart))
        assertArrayEquals(cover.data, data.copyOfRange(covr.payloadStart + 8, covr.end))
    }

    @Test
    fun `tags are added when moov precedes mdat and chunk offsets stay correct`() {
        val (original, tagged) = write("sample_faststart.m4a", tags)
        assertTagsPresent(tagged)

        val delta = tagged.size - original.size
        assertTrue(delta > cover.data.size)
        assertEquals(firstChunkOffset(original) + delta, firstChunkOffset(tagged))
        assertArrayEquals(
            original.copyOfRange(firstChunkOffset(original).toInt(), firstChunkOffset(original).toInt() + 512),
            tagged.copyOfRange(firstChunkOffset(tagged).toInt(), firstChunkOffset(tagged).toInt() + 512),
        )
        val mdatOld = boxes(original).first { it.type == "mdat" }
        val mdatNew = boxes(tagged).first { it.type == "mdat" }
        assertArrayEquals(
            original.copyOfRange(mdatOld.start, mdatOld.end),
            tagged.copyOfRange(mdatNew.start, mdatNew.end),
        )
    }

    @Test
    fun `offsets are untouched when moov follows mdat`() {
        val (original, tagged) = write("sample_moov_last.m4a", tags)
        assertTagsPresent(tagged)
        assertEquals(firstChunkOffset(original), firstChunkOffset(tagged))
        val mdatOld = boxes(original).first { it.type == "mdat" }
        val mdatNew = boxes(tagged).first { it.type == "mdat" }
        assertEquals(mdatOld.start, mdatNew.start)
        assertArrayEquals(
            original.copyOfRange(mdatOld.start, mdatOld.end),
            tagged.copyOfRange(mdatNew.start, mdatNew.end),
        )
    }

    @Test
    fun `fragmented files keep their movie fragments`() {
        val (original, tagged) = write("sample_fragmented.m4a", tags)
        assertTagsPresent(tagged)
        listOf("moof", "mdat", "mfra").forEach { type ->
            val old = boxes(original).first { it.type == type }
            val new = boxes(tagged).first { it.type == type }
            assertArrayEquals(type, original.copyOfRange(old.start, old.end), tagged.copyOfRange(new.start, new.end))
        }
    }

    @Test
    fun `existing tags are replaced and other udta content is kept`() {
        val (_, once) = write("sample_faststart.m4a", tags)
        val twice = File.createTempFile("tagged", ".m4a")
        try {
            twice.writeBytes(once)
            val out = ByteArrayOutputStream()
            Mp4TagWriter.write(twice, out, ExportTags(title = "Second", artist = null, album = null))
            val result = out.toByteArray()
            val udta = path(result, "moov", "udta")!!
            assertEquals(1, boxes(result, udta.payloadStart, udta.end).count { it.type == "meta" })
            val ilst = path(result, "moov", "udta", "meta", "ilst")!!
            val types = boxes(result, ilst.payloadStart, ilst.end).map { it.type }
            assertEquals(listOf("©nam"), types)
        } finally {
            twice.delete()
        }
    }

    @Test
    fun `png covers use the png type code and tag-less fields are omitted`() {
        val png = ExportTags.Cover(ByteArray(10), "image/png", 1, 1)
        val (_, tagged) = write("sample_faststart.m4a", ExportTags(title = "T", artist = "", album = null, cover = png))
        val ilst = path(tagged, "moov", "udta", "meta", "ilst")!!
        val items = boxes(tagged, ilst.payloadStart, ilst.end).associateBy { it.type }
        assertTrue("©ART" !in items)
        assertTrue("trkn" !in items)
        assertEquals(14, ByteBuffer.wrap(tagged).getInt(child(tagged, items.getValue("covr"), "data")!!.payloadStart))
    }

    private fun resource(name: String): File =
        File(requireNotNull(javaClass.classLoader.getResource("export/$name")) { "missing $name" }.toURI())
}
