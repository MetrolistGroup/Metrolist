package com.metrolist.shared.playback.ffmpeg

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioTempoProcessorTest {
    @Test
    fun tempoChangesDurationWithoutChangingPitchAndFlushesTail() {
        for (speed in listOf(0.5f, 1f, 1.25f, 2f)) {
            for (pitch in listOf(0.5f, 1f, 1.5f, 2f)) {
                val output = ByteArrayOutputStream()
                AudioTempoProcessor(SAMPLE_RATE, 1, speed, pitch).use { processor ->
                    tone().asList().chunked(2048).forEach { processor.process(it.toByteArray(), output::write) }
                    val beforeFlush = output.size()
                    processor.process(null, output::write)
                    assertTrue(output.size() > beforeFlush, "Filter tail was not flushed at $speed")
                }
                val samples = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val expectedSamples = (SAMPLE_RATE * 2 / speed).toInt()
                assertTrue(abs(samples.remaining() - expectedSamples) < SAMPLE_RATE / 10, "Wrong duration at $speed")
                var crossings = 0
                var previous = samples.get().toInt()
                val count = samples.remaining() + 1
                while (samples.hasRemaining()) {
                    val current = samples.get().toInt()
                    if (previous <= 0 && current > 0) crossings++
                    previous = current
                }
                val frequency = crossings * SAMPLE_RATE.toDouble() / count
                assertTrue(abs(frequency - 440 * pitch) < 8, "Wrong pitch $frequency Hz at speed $speed, pitch $pitch")
            }
        }
    }

    @Test
    fun speedCanChangeWithinAnExistingStream() {
        val output = ByteArrayOutputStream()
        val data = tone()
        AudioTempoProcessor(SAMPLE_RATE, 1, 1f).use { processor ->
            processor.process(data, output::write)
            processor.setSpeed(1.25f)
            processor.process(data, output::write)
            processor.process(null, output::write)
        }
        val seconds = output.size().toDouble() / (SAMPLE_RATE * Short.SIZE_BYTES)
        assertTrue(seconds in 3.4..3.8, "Expected about 3.6 seconds after speed change, got $seconds")
    }

    @Test
    fun drainWaitsForAudioAndStopsOnCancellation() {
        for (cancel in listOf(false, true)) {
            var active = true
            var checks = 0
            val drained =
                awaitAudioDrain({ active }) {
                    if (++checks == 3) {
                        if (cancel) active = false
                        cancel
                    } else {
                        true
                    }
                }
            assertEquals(3, checks)
            assertEquals(!cancel, drained)
        }
    }

    private fun tone(): ByteArray =
        ByteBuffer
            .allocate(SAMPLE_RATE * 2 * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                repeat(SAMPLE_RATE * 2) { index ->
                    putShort((sin(2 * PI * 440 * index / SAMPLE_RATE) * 16_000).toInt().toShort())
                }
            }.array()

    companion object {
        private const val SAMPLE_RATE = 48_000
    }
}
