package com.metrolist.shared.soulseek

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SoulseekCodecTest {
    private fun file(
        name: String,
        size: Long,
        attributes: Map<Int, Int>,
    ) = MessageWriter()
        .u8(1)
        .string(name)
        .u64(size)
        .string(name.substringAfterLast('.'))
        .u32(attributes.size)
        .also { writer -> attributes.forEach { (key, value) -> writer.u32(key).u32(value) } }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater().apply { setInput(bytes) }
        deflater.finish()
        val out = ByteArray(bytes.size + 64)
        return out.copyOf(deflater.deflate(out)).also { deflater.end() }
    }

    @Test
    fun framesCarryLengthAndCode() {
        val frame = MessageWriter().u32(7).string("hi").frame(Codes.FILE_SEARCH)
        val body = SoulseekCodec.readFrame(DataInputStream(ByteArrayInputStream(frame)))!!
        val reader = MessageReader(body)
        assertEquals(Codes.FILE_SEARCH.toLong(), reader.u32())
        assertEquals(7L, reader.u32())
        assertEquals("hi", reader.string())
        assertEquals(0, reader.remaining)
    }

    @Test
    fun peerInitFramesUseAOneByteCode() {
        val frame = MessageWriter().u32(42).frameInit(Codes.PIERCE_FIREWALL)
        assertEquals(listOf(5, 0, 0, 0, 0, 42, 0, 0, 0), frame.map { it.toInt() })
    }

    @Test
    fun ipAddressesAreByteReversed() {
        assertEquals("1.2.3.4", MessageReader(byteArrayOf(4, 3, 2, 1)).ip())
    }

    @Test
    fun stringsFallBackToWindows1252() {
        val latin = byteArrayOf(3, 0, 0, 0, 'S'.code.toByte(), 0xE9.toByte(), 'n'.code.toByte())
        assertEquals("Sén", MessageReader(latin).string())
        assertEquals("Sén", MessageReader(MessageWriter().string("Sén").bytes()).string())
    }

    @Test
    fun searchRepliesDecompressAndParse() {
        val payload =
            MessageWriter()
                .string("peer")
                .u32(99)
                .u32(2)
                .bytes() +
                file("Music\\Artist\\01 Song.flac", 30_000_000, mapOf(Codes.ATTR_DURATION to 200, Codes.ATTR_BIT_DEPTH to 24)).bytes() +
                file("Music\\Artist\\01 Song.mp3", 8_000_000, mapOf(Codes.ATTR_BITRATE to 320)).bytes() +
                MessageWriter()
                    .bool(true)
                    .u32(1500)
                    .u32(3)
                    .bytes()
        val reply = SoulseekCodec.parseSearchReply(deflate(payload))
        assertEquals("peer", reply.username)
        assertEquals(99L, reply.ticket)
        assertEquals(listOf(30_000_000L, 8_000_000L), reply.files.map { it.size })
        assertEquals(24, reply.files[0].attributes[Codes.ATTR_BIT_DEPTH])
        assertEquals(true, reply.hasFreeSlots)
        assertEquals(3L, reply.queueLength)
    }

    @Test
    fun malformedPeerDataIsRejected() {
        // An array count larger than the message could hold.
        val bogusCount =
            MessageWriter()
                .string("peer")
                .u32(1)
                .u32(1_000_000)
                .bytes()
        assertFailsWith<SoulseekProtocolException> { SoulseekCodec.parseSearchReply(deflate(bogusCount)) }
        // A string longer than the limit, and a truncated string.
        assertFailsWith<SoulseekProtocolException> { MessageReader(MessageWriter().u32(1_000_000).bytes()).string() }
        assertFailsWith<SoulseekProtocolException> { MessageReader(byteArrayOf(10, 0, 0, 0, 1)).string() }
        assertFailsWith<SoulseekProtocolException> { SoulseekCodec.parseSearchReply(byteArrayOf(1, 2, 3)) }
        // A frame claiming more than the size limit.
        val huge = MessageWriter().u32(Int.MAX_VALUE).bytes()
        assertFailsWith<SoulseekProtocolException> { SoulseekCodec.readFrame(DataInputStream(ByteArrayInputStream(huge))) }
        // A frame cut short ends the stream instead of hanging.
        assertNull(SoulseekCodec.readFrame(DataInputStream(ByteArrayInputStream(byteArrayOf(10, 0, 0, 0, 1)))))
    }
}
