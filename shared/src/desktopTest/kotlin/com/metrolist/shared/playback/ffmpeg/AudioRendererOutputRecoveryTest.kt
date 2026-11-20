package com.metrolist.shared.playback.ffmpeg

import com.metrolist.shared.equalizer.EqualizerConfiguration
import java.lang.reflect.Proxy
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AudioRendererOutputRecoveryTest {
    @Test
    fun replacementReplaysUnplayedPcmWithTheCurrentGainAndContinuousPosition() {
        val original = TestOutputLine(autoAdvance = false)
        val replacement = TestOutputLine(autoAdvance = false)
        val lines = ArrayDeque(listOf(original, replacement))
        val renderer = renderer(lines)
        val firstPcm = pcm16(1_000, 2_000)

        try {
            renderer.resetPosition(10_000L)
            renderer.setVolume(0.5f)
            renderer.writePcmData(firstPcm)
            original.framePosition = 1L
            assertEquals(10_001L, renderer.getPlayedPositionMs())
            original.close()

            renderer.writePcmData(pcm16(4_000))
            assertContentEquals(pcm16(1_000), replacement.writes[0])
            assertContentEquals(pcm16(2_000), replacement.writes[1])

            replacement.framePosition = 1L
            assertEquals(10_002L, renderer.getPlayedPositionMs())
        } finally {
            renderer.close()
        }
    }

    @Test
    fun secondClosureReplaysOnlyFramesNotYetRenderedByTheFirstReplacement() {
        val original = TestOutputLine(autoAdvance = false)
        val firstReplacement = TestOutputLine(autoAdvance = true).apply { availableBytes = 2 }
        val secondReplacement = TestOutputLine(autoAdvance = true)
        val lines = ArrayDeque(listOf(original, firstReplacement, secondReplacement))
        val renderer = renderer(lines)
        val calls = intArrayOf(0)

        try {
            renderer.setVolume(0.5f)
            renderer.writePcmData(pcm16(1_000, 2_000))
            original.close()

            renderer.writePcmData(pcm16(4_000)) {
                if (++calls[0] == 3) firstReplacement.close()
                true
            }

            assertContentEquals(pcm16(500), firstReplacement.writes.single())
            assertContentEquals(pcm16(1_000), secondReplacement.writes.first())
            assertContentEquals(pcm16(2_000), secondReplacement.writes[1])
        } finally {
            renderer.close()
        }
    }

    private fun renderer(lines: ArrayDeque<TestOutputLine>): AudioRenderer =
        AudioRenderer(
            sampleRate = 1_000,
            channelCount = 1,
            lineFactory = { _, _ -> lines.removeFirst().line },
            configuration = { EqualizerConfiguration(profile = null, preampDb = 0.0) },
            silenceSettings = { false to false },
        )

    private fun pcm16(vararg samples: Int): ByteArray =
        ByteArray(samples.size * Short.SIZE_BYTES).also { bytes ->
            samples.forEachIndexed { index, sample ->
                val value = sample.toShort()
                bytes[index * 2] = value.toByte()
                bytes[index * 2 + 1] = (value.toInt() shr 8).toByte()
            }
        }

    private class TestOutputLine(
        private val autoAdvance: Boolean,
    ) {
        private var open = false
        private var active = false
        private var frameSize = 2
        var availableBytes = Int.MAX_VALUE
        var framePosition = 0L
        val writes = mutableListOf<ByteArray>()

        val line =
            Proxy.newProxyInstance(
                SourceDataLine::class.java.classLoader,
                arrayOf(SourceDataLine::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "open" -> {
                        (args?.firstOrNull() as? AudioFormat)?.let { frameSize = it.frameSize }
                        open = true
                        null
                    }

                    "close" -> {
                        close()
                        null
                    }

                    "start" -> {
                        active = true
                        null
                    }

                    "stop" -> {
                        active = false
                        null
                    }

                    "isOpen" -> {
                        open
                    }

                    "isActive", "isRunning" -> {
                        active
                    }

                    "getLongFramePosition" -> {
                        framePosition
                    }

                    "getFramePosition" -> {
                        framePosition.toInt()
                    }

                    "getBufferSize" -> {
                        1_024
                    }

                    "available" -> {
                        availableBytes
                    }

                    "isControlSupported" -> {
                        false
                    }

                    "getControls" -> {
                        emptyArray<Control>()
                    }

                    "getLineInfo" -> {
                        DataLine.Info(SourceDataLine::class.java, AudioFormat(1_000f, 16, 1, true, false))
                    }

                    "write" -> {
                        val data = args!![0] as ByteArray
                        val offset = args[1] as Int
                        val length = args[2] as Int
                        writes += data.copyOfRange(offset, offset + length)
                        availableBytes = (availableBytes - length).coerceAtLeast(0)
                        if (autoAdvance) framePosition += length / frameSize
                        length
                    }

                    "flush", "drain", "addLineListener", "removeLineListener" -> {
                        null
                    }

                    "toString" -> {
                        "TestOutputLine"
                    }

                    "hashCode" -> {
                        System.identityHashCode(this)
                    }

                    "equals" -> {
                        this === args?.firstOrNull()
                    }

                    else -> {
                        error("Unexpected SourceDataLine call: ${method.name}")
                    }
                }
            } as SourceDataLine

        fun close() {
            open = false
            active = false
        }
    }
}
