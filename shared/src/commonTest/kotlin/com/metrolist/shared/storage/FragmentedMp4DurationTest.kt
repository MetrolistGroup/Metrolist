package com.metrolist.shared.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FragmentedMp4DurationTest {
    @Test
    fun clearsDurationsOfEmptyFragmentedMoov() {
        val file = mp4(fragmented = true, sttsEntries = 0)
        assertTrue(clearFragmentedMp4MovieDurations(file))
        assertEquals(0L, file.readField("mvhd", 16, 4))
        assertEquals(0L, file.readField("tkhd", 20, 4))
        assertEquals(0L, file.readField("mdhd", 16, 4))
        assertEquals(44_100L, file.readField("mdhd", 12, 4))
        assertFalse(clearFragmentedMp4MovieDurations(file))
    }

    @Test
    fun keepsDurationsWhenMoovDescribesSamples() {
        for (file in listOf(mp4(fragmented = false, sttsEntries = 0), mp4(fragmented = true, sttsEntries = 1))) {
            val original = file.copyOf()
            assertFalse(clearFragmentedMp4MovieDurations(file))
            assertContentEquals(original, file)
        }
    }

    @Test
    fun ignoresTruncatedAndNonMp4Data() {
        val file = mp4(fragmented = true, sttsEntries = 0)
        val truncated = file.copyOf(file.size - 40)
        assertFalse(clearFragmentedMp4MovieDurations(truncated))
        assertFalse(clearFragmentedMp4MovieDurations(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0)))
    }

    private fun mp4(
        fragmented: Boolean,
        sttsEntries: Int,
    ): ByteArray {
        val duration = 9_397_248
        val stbl = box("stbl", box("stts", int(0) + int(sttsEntries) + ByteArray(8 * sttsEntries)))
        val mdhd = box("mdhd", int(0) + int(0) + int(0) + int(44_100) + int(duration) + int(0))
        val tkhd = box("tkhd", int(0) + int(0) + int(0) + int(1) + int(0) + int(duration) + ByteArray(60))
        val trak = box("trak", tkhd + box("mdia", mdhd + box("minf", stbl)))
        val mvhd = box("mvhd", int(0) + int(0) + int(0) + int(44_100) + int(duration) + ByteArray(80))
        val mvex = if (fragmented) box("mvex", box("trex", ByteArray(24))) else ByteArray(0)
        return box("ftyp", "dash".encodeToByteArray() + int(0)) + box("moov", mvhd + mvex + trak) + box("moof", ByteArray(16))
    }

    private fun box(
        type: String,
        body: ByteArray,
    ): ByteArray = int(body.size + 8) + type.encodeToByteArray() + body

    private fun int(value: Int): ByteArray = ByteArray(4) { (value shr (24 - it * 8)).toByte() }

    private fun ByteArray.readField(
        type: String,
        offset: Int,
        width: Int,
    ): Long {
        val body = indexOfType(type) + 4
        return (0 until width).fold(0L) { value, index -> (value shl 8) or (this[body + offset + index].toLong() and 0xFF) }
    }

    private fun ByteArray.indexOfType(type: String): Int {
        val target = type.encodeToByteArray()
        return (0..size - 4).first { start -> target.indices.all { this[start + it] == target[it] } }
    }
}
