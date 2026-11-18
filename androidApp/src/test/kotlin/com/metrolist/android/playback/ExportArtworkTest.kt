package com.metrolist.android.playback

import okio.ByteString.Companion.decodeBase64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class ExportArtworkTest {
    private val artwork = AudioExportArtwork(ByteArray(100_000) { it.toByte() }, 544, 544)
    private val metadata = AudioExportMetadata("Song", "Artist", "Album", artwork)

    @Test
    fun opusCommentsContainTheCoverPictureAndTextTags() {
        val packet = ByteBuffer.wrap(opusTagsPacket(metadata)).order(ByteOrder.LITTLE_ENDIAN)
        packet.position(8)
        packet.position(packet.position() + 4 + packet.int)
        val comments = List(packet.int) { ByteArray(packet.int).also(packet::get).decodeToString() }
        assertEquals(listOf("TITLE=Song", "ARTIST=Artist", "ALBUM=Album"), comments.take(3))
        val picture =
            comments
                .last()
                .removePrefix("METADATA_BLOCK_PICTURE=")
                .decodeBase64()!!
                .toByteArray()
        DataInputStream(ByteArrayInputStream(picture)).use { input ->
            assertEquals(3, input.readInt())
            assertEquals("image/png", ByteArray(input.readInt()).also(input::readFully).decodeToString())
            assertEquals(0, input.readInt())
            assertEquals(544, input.readInt())
            assertEquals(544, input.readInt())
            assertEquals(32, input.readInt())
            assertEquals(0, input.readInt())
            assertArrayEquals(artwork.png, ByteArray(input.readInt()).also(input::readFully))
            assertEquals(-1, input.read())
        }
    }

    @Test
    fun largeCommentPacketsSpanValidOggPagesIncludingExactPageBoundaries() {
        for (size in listOf(0, 254, 255, 65_024, 65_025, opusTagsPacket(metadata).size)) {
            val packet = ByteArray(size) { it.toByte() }
            val output = ByteArrayOutputStream()
            OggPageWriter(output, serialNumber = 17).writePacket(packet, 42, beginningOfStream = true, endOfStream = true)
            val bytes = output.toByteArray()
            val restored = ByteArrayOutputStream()
            var offset = 0
            var sequence = 0
            while (offset < bytes.size) {
                val segments = bytes[offset + 26].toInt() and 255
                val payloadSize = (0 until segments).sumOf { bytes[offset + 27 + it].toInt() and 255 }
                val pageSize = 27 + segments + payloadSize
                val page = bytes.copyOfRange(offset, offset + pageSize)
                val header = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
                val last = offset + pageSize == bytes.size
                assertEquals("OggS", page.copyOfRange(0, 4).decodeToString())
                assertEquals((if (sequence == 0) 2 else 1) or (if (last) 4 else 0), page[5].toInt())
                assertEquals(if (last) 42L else -1L, header.getLong(6))
                assertEquals(17, header.getInt(14))
                assertEquals(sequence++, header.getInt(18))
                val crc = header.getInt(22)
                header.putInt(22, 0)
                assertEquals(crc, oggCrc(page))
                restored.write(bytes, offset + 27 + segments, payloadSize)
                offset += pageSize
            }
            assertArrayEquals(packet, restored.toByteArray())
        }
    }

    @Test
    fun m4aArtworkUsesAPngCoverAtomAndPreservesAudioBytes() {
        val file = Files.createTempFile("m4a-artwork", ".m4a").toFile()

        fun box(
            type: String,
            payload: ByteArray,
        ) = ByteBuffer
            .allocate(8 + payload.size)
            .apply {
                putInt(8 + payload.size)
                put(type.encodeToByteArray())
                put(payload)
            }.array()
        val audio = box("mdat", byteArrayOf(1, 2, 3))
        try {
            file.writeBytes(audio + box("moov", byteArrayOf()))
            tagMuxedM4a(file, metadata)
            val bytes = file.readBytes()
            assertArrayEquals(audio, bytes.copyOfRange(0, audio.size))
            val cover = box("covr", box("data", byteArrayOf(0, 0, 0, 14, 0, 0, 0, 0) + artwork.png))
            assertArrayEquals(cover, bytes.copyOfRange(bytes.size - cover.size, bytes.size))
        } finally {
            file.delete()
        }
    }
}
