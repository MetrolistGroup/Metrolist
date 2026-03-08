package com.metrolist.android.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Files

class M4aMetadataTest {
    @Test
    fun tagsPreserveMediaOffsetsAndMoviePayloadForFrontAndTrailingMovieBoxes() {
        val file = Files.createTempFile("m4a-tags", ".m4a").toFile()
        val metadata = AudioExportMetadata("夜の歌 🎵", "Björk", "Álbum")
        val moviePayload =
            box(
                "trak",
                box(
                    "stco",
                    ByteBuffer
                        .allocate(12)
                        .putInt(0)
                        .putInt(1)
                        .putInt(512)
                        .array(),
                ),
            )
        val media = box("mdat", ByteArray(128) { it.toByte() })
        try {
            for (front in listOf(false, true)) {
                for (extended in listOf(false, true)) {
                    val movie = box("moov", moviePayload, extended)
                    val original = if (front) movie + media else media + movie
                    file.writeBytes(original)
                    tagMuxedM4a(file, metadata)
                    val result = file.readBytes()
                    val mediaOffset = if (front) movie.size else 0
                    assertArrayEquals(media, result.copyOfRange(mediaOffset, mediaOffset + media.size))
                    val top = boxes(result)
                    assertEquals(1, top.count { it.first == "moov" })
                    val tagged = top.single { it.first == "moov" }.second
                    assertArrayEquals(moviePayload, tagged.copyOfRange(0, moviePayload.size))
                    assertEquals(mapOf("©nam" to metadata.title, "©ART" to metadata.artist, "©alb" to metadata.album), tags(tagged))
                    if (front || extended) assertEquals(1, top.count { it.first == "free" })
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun trailingZeroSizeBoxesStayBoundedWhenMetadataIsAppended() {
        val file = Files.createTempFile("m4a-tags", ".m4a").toFile()
        try {
            for (movieLast in listOf(false, true)) {
                val movie = box("moov", box("trak", byteArrayOf()))
                val media = box("mdat", byteArrayOf(1, 2, 3, 4))
                val last = (if (movieLast) movie else media).copyOf().apply { fill(0, 0, 4) }
                file.writeBytes((if (movieLast) media else movie) + last)
                tagMuxedM4a(file, AudioExportMetadata("Title", "Artist", " "))
                val top = boxes(file.readBytes())
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), top.single { it.first == "mdat" }.second)
                assertEquals(mapOf("©nam" to "Title", "©ART" to "Artist"), tags(top.single { it.first == "moov" }.second))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun malformedAndFragmentedFilesCannotBeReportedAsTagged() {
        val file = Files.createTempFile("m4a-invalid", ".m4a").toFile()
        val movie = box("moov", byteArrayOf())
        try {
            val invalid =
                listOf(
                    byteArrayOf(),
                    movie.copyOf(7),
                    movie.copyOf().apply { this[3] = 7 },
                    movie.copyOf().apply { this[3] = 32 },
                    box("moov", byteArrayOf(), extended = true).copyOf(12),
                    movie + movie,
                    box("mdat", byteArrayOf(1)),
                    movie + box("moof", byteArrayOf()),
                )
            for (bytes in invalid) {
                file.writeBytes(bytes)
                assertThrows(IllegalStateException::class.java) {
                    tagMuxedM4a(file, AudioExportMetadata("Title", "Artist", null))
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun tags(movie: ByteArray): Map<String, String> {
        val udta = boxes(movie).single { it.first == "udta" }.second
        val meta = boxes(udta).single { it.first == "meta" }.second
        assertEquals(0, ByteBuffer.wrap(meta).int)
        val children = boxes(meta.copyOfRange(4, meta.size))
        val handler = children.single { it.first == "hdlr" }.second
        assertEquals("mdir", handler.copyOfRange(8, 12).toString(Charsets.ISO_8859_1))
        return boxes(children.single { it.first == "ilst" }.second).associate { (type, payload) ->
            val data = boxes(payload).single()
            assertEquals("data", data.first)
            val buffer = ByteBuffer.wrap(data.second)
            assertEquals(1, buffer.int)
            assertEquals(0, buffer.int)
            type to ByteArray(buffer.remaining()).also(buffer::get).decodeToString()
        }
    }

    private fun boxes(bytes: ByteArray): List<Pair<String, ByteArray>> =
        buildList {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                val size = buffer.int
                val type = ByteArray(4).also(buffer::get).toString(Charsets.ISO_8859_1)
                val payloadSize = if (size == 1) buffer.long.toInt() - 16 else size - 8
                add(type to ByteArray(payloadSize).also(buffer::get))
            }
        }

    private fun box(
        type: String,
        payload: ByteArray,
        extended: Boolean = false,
    ): ByteArray {
        val size = payload.size + if (extended) 16 else 8
        return ByteBuffer
            .allocate(size)
            .apply {
                putInt(if (extended) 1 else size)
                put(type.toByteArray(Charsets.ISO_8859_1))
                if (extended) putLong(size.toLong())
                put(payload)
            }.array()
    }
}
