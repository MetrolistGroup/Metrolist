package com.metrolist.shared.playback.opus

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebmOpusTest {
    @Test
    fun readsRealWebmAndPreservesPacketsAndTrimmingInMp4() {
        val input = opusWebmFixture()
        val audio = WebmOpusDemuxer.demux(input)
        assertEquals(2, audio.channels)
        assertEquals(312, audio.preSkip)
        assertEquals(48_000L, audio.inputSampleRate)
        assertEquals(101, audio.packets.size)
        assertTrue(audio.packets.all { it.samples == 960 })
        assertEquals(648, audio.discardSamples)
        assertEquals(96_000L, audio.durationSamples)
        val mp4 = OpusMp4Remuxer.remux(input)
        val mdat = typeOffset(mp4, "mdat")
        assertContentEquals(audio.packets.flatMap { it.bytes.asIterable() }.toByteArray(), mp4.copyOfRange(mdat + 4, mp4.size))
        val dops = typeOffset(mp4, "dOps")
        assertContentEquals(byteArrayOf(0, 2, 1, 56, 0, 0, -69, -128, 0, 0, 0), mp4.copyOfRange(dops + 4, dops + 15))
        val edit = typeOffset(mp4, "elst")
        assertEquals(96_000L, uint(mp4, edit + 12))
        assertEquals(312L, uint(mp4, edit + 16))
        val stts = typeOffset(mp4, "stts")
        assertEquals(101L, uint(mp4, stts + 8))
        assertEquals(312L, uint(mp4, stts + 16 + 100 * 8))
        val stco = typeOffset(mp4, "stco")
        assertEquals((mdat + 4).toLong(), uint(mp4, stco + 12))
    }

    @Test
    fun supportsAllWebmLacingModesAndUnknownClusterBoundaries() {
        val packets =
            WebmOpusDemuxer
                .demux(opusWebmFixture())
                .packets
                .take(3)
                .map { it.bytes }
        val xiph =
            packets
                .dropLast(1)
                .flatMap { bytes ->
                    List(bytes.size / 255) { 255.toByte() } + (bytes.size % 255).toByte()
                }.toByteArray()
        val ebml = vint(packets[0].size) + signedLace(packets[1].size - packets[0].size)
        for ((mode, sizes, expected) in listOf(
            Triple(2, xiph, packets),
            Triple(4, byteArrayOf(), List(3) { packets.first() }),
            Triple(6, ebml, packets),
        )) {
            val block = byteArrayOf(-127, 0, 0, mode.toByte(), 2) + sizes + expected.fold(byteArrayOf()) { a, b -> a + b }
            val input = file(element(0x1F43B675, element(0xE7, byteArrayOf(0)) + element(0xA3, block), unknown = true))
            val parsed = WebmOpusDemuxer.demux(input)
            assertEquals(3, parsed.packets.size)
            expected.zip(parsed.packets).forEach { (bytes, packet) -> assertContentEquals(bytes, packet.bytes) }
        }
        val packet = packets.first()
        val first = element(0x1F43B675, element(0xE7, byteArrayOf(0)) + block(packet), unknown = true)
        val second = element(0x1F43B675, element(0xE7, byteArrayOf(20)) + block(packet), unknown = true)
        assertEquals(2, WebmOpusDemuxer.demux(file(first + second)).packets.size)
    }

    @Test
    fun rejectsTruncatedMalformedAndUnsupportedWebm() {
        val real = opusWebmFixture()
        for (size in listOf(0, 1, 16, real.size / 2, real.size - 1)) {
            assertFailsWith<IllegalArgumentException> { WebmOpusDemuxer.demux(real.copyOf(size)) }
        }
        val packet =
            WebmOpusDemuxer
                .demux(real)
                .packets
                .first()
                .bytes
        val cluster = element(0x1F43B675, element(0xE7, byteArrayOf(0)) + block(packet))
        assertFailsWith<IllegalArgumentException> { WebmOpusDemuxer.demux(file(cluster, mapping = 1)) }
        assertFailsWith<IllegalArgumentException> { WebmOpusDemuxer.demux(file(cluster, channels = 6)) }
        assertFailsWith<IllegalArgumentException> { WebmOpusDemuxer.demux(file(cluster, encoded = true)) }
        val gap = element(0x1F43B675, element(0xE7, byteArrayOf(100)) + block(packet))
        assertFailsWith<IllegalArgumentException> { WebmOpusDemuxer.demux(file(cluster + gap)) }
        val badLace = element(0x1F43B675, element(0xE7, byteArrayOf(0)) + element(0xA3, byteArrayOf(-127, 0, 0, 4, 2, -8)))
        assertFailsWith<IllegalArgumentException> { WebmOpusDemuxer.demux(file(badLace)) }
        assertFailsWith<IllegalArgumentException> { WebmOpusDemuxer.demux(byteArrayOf(0)) }
    }

    @Test
    fun computesPacketDurationsAt48KhzAndRejectsInvalidFrameCounts() {
        assertEquals(120, opusPacketSamples(byteArrayOf(-128)))
        assertEquals(960, opusPacketSamples(byteArrayOf(-8)))
        assertEquals(2880, opusPacketSamples(byteArrayOf(24)))
        assertEquals(5760, opusPacketSamples(byteArrayOf(-5, 6)))
        for (packet in listOf(byteArrayOf(), byteArrayOf(3), byteArrayOf(3, 0), byteArrayOf(-5, 7))) {
            assertFailsWith<IllegalArgumentException> { opusPacketSamples(packet) }
        }
    }

    private fun block(packet: ByteArray) = element(0xA3, byteArrayOf(-127, 0, 0, -128) + packet)

    private fun file(
        clusters: ByteArray,
        mapping: Byte = 0,
        channels: Byte = 2,
        encoded: Boolean = false,
    ): ByteArray {
        val head = "OpusHead".encodeToByteArray() + byteArrayOf(1, channels, 56, 1, -128, -69, 0, 0, 0, 0, mapping)
        val track =
            element(0xD7, byteArrayOf(1)) + element(0x83, byteArrayOf(2)) + element(0x86, "A_OPUS".encodeToByteArray()) +
                element(0x63A2, head) + element(0xE1, element(0x9F, byteArrayOf(channels))) +
                if (encoded) element(0x6D80, byteArrayOf()) else byteArrayOf()
        return element(0x1A45DFA3, element(0x4282, "webm".encodeToByteArray())) +
            element(0x18538067, element(0x1654AE6B, element(0xAE, track)) + clusters, unknown = true)
    }

    private fun element(
        id: Int,
        bytes: ByteArray,
        unknown: Boolean = false,
    ): ByteArray {
        val width =
            when {
                id ushr 24 != 0 -> 4
                id ushr 16 != 0 -> 3
                id ushr 8 != 0 -> 2
                else -> 1
            }
        val key = ByteArray(width) { (id ushr (8 * (width - it - 1))).toByte() }
        return key + (if (unknown) byteArrayOf(-1) else vint(bytes.size)) + bytes
    }

    private fun vint(value: Int): ByteArray =
        if (value <
            127
        ) {
            byteArrayOf((value or 0x80).toByte())
        } else {
            byteArrayOf(((value shr 8) or 0x40).toByte(), value.toByte())
        }

    private fun signedLace(delta: Int): ByteArray = vint(delta + 8191)

    private fun typeOffset(
        bytes: ByteArray,
        type: String,
    ): Int =
        bytes.indices.first { index ->
            index + 4 <= bytes.size && bytes.copyOfRange(index, index + 4).decodeToString() == type
        }

    private fun uint(
        bytes: ByteArray,
        start: Int,
    ): Long =
        (start until start + 4).fold(0L) { value, index ->
            (value shl 8) or
                (bytes[index].toLong() and 0xff)
        }
}
