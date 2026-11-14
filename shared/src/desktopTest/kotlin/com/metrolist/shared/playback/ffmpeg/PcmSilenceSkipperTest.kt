package com.metrolist.shared.playback.ffmpeg

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PcmSilenceSkipperTest {
    @Test
    fun batchedNoisePreservesPcmAndSkipClockAcrossChunkBoundaries() {
        val noise = ByteArray(1_000 * 4) { if (it % 2 == 1) 16 else 7 }
        val input = noise + ByteArray(550 * 4) + noise + ByteArray(1_250 * 4) + noise

        fun process(chunkBytes: Int): Pair<ByteArray, List<Pair<Long, Long>>> {
            val skipper = PcmSilenceSkipper(1_000, 2)
            val skips = mutableListOf<Pair<Long, Long>>()
            val output = mutableListOf<Byte>()
            for (offset in input.indices step chunkBytes) {
                output +=
                    skipper
                        .process(input.copyOfRange(offset, minOf(input.size, offset + chunkBytes)), true, true) { at, count ->
                            skips += at to count
                        }.toList()
            }
            output += skipper.drain().toList()
            return output.toByteArray() to skips
        }
        val whole = process(input.size)
        val split = process(124)
        assertContentEquals(whole.first, split.first)
        assertEquals(whole.second, split.second)
        assertEquals(listOf(1_100L to 400L, 2_250L to 400L, 2_350L to 400L), whole.second)
    }

    @Test fun preservesShortGapsAndNoiseInEitherChannelAcrossBuffers() {
        val skipper = PcmSilenceSkipper(1_000, 2)
        val silence = ByteArray(499 * 4)
        val rightNoise = byteArrayOf(0, 0, 0, 8)
        assertEquals(0, skipper.process(silence, true, true) { _, _ -> error("Short silence skipped") }.size)
        assertContentEquals(silence + rightNoise, skipper.process(rightNoise, true, true) { _, _ -> error("Noise skipped") })
        assertFalse(skipper.isLongSilence)
    }

    @Test
    fun preservesSustainedQuietMusicInEitherChannel() {
        for (channel in 0..1) {
            val skipper = PcmSilenceSkipper(1_000, 2)
            val quiet = ByteArray(2_000 * 4)
            repeat(2_000) { frame ->
                val sample = if (frame % 2 == 0) 512 else -512
                val offset = frame * 4 + channel * 2
                quiet[offset] = sample.toByte()
                quiet[offset + 1] = (sample shr 8).toByte()
            }
            assertContentEquals(quiet, skipper.process(quiet, true, false) { _, _ -> error("Quiet music skipped") })
            assertEquals(2_000L, skipper.outputFrames)
        }
    }

    @Test fun compressesAcrossBuffersAndCapsRetainedSilenceAtTwoSeconds() {
        val skipper = PcmSilenceSkipper(1_000, 1)
        var removed = 0L
        var written = 0
        repeat(120) {
            written += skipper.process(ByteArray(200), true, true) { _, frames -> removed += frames }.size
        }
        assertEquals(4_000, written)
        assertEquals(10_000L, removed)
        assertTrue(skipper.isLongSilence)
        skipper.reset()
        assertFalse(skipper.isLongSilence)
        assertEquals(200, skipper.process(ByteArray(1_000), true, false) { _, _ -> }.size)
    }

    @Test fun disableAndEofKeepBufferedAudioAndDoNotDetectInstantWhenDisabled() {
        val skipper = PcmSilenceSkipper(1_000, 1)
        val pending = ByteArray(100)
        skipper.process(pending, true, true) { _, _ -> }
        assertContentEquals(pending + pending, skipper.process(pending, false, true) { _, _ -> error("Disabled") })
        skipper.process(pending, true, false) { _, _ -> }
        assertContentEquals(pending, skipper.drain())
        assertEquals(0, skipper.drain().size)
        assertFalse(skipper.isLongSilence)
    }
}
