package com.metrolist.shared.playback.ffmpeg

import com.metrolist.shared.equalizer.AudioSpectrumMeter
import com.metrolist.shared.equalizer.EqualizerProfile
import com.metrolist.shared.equalizer.ParametricEqualizer
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.russhwolf.settings.MapSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AudioRendererTest {
    @Test
    fun lyricsPositionExcludes150MsOfWrittenButUnplayedAudio() {
        val line = TestSourceDataLine(autoPlayWrites = false).apply { availableBytes = 300 }
        val renderer = AudioRenderer(1_000, 1, line.line)
        try {
            renderer.resetPosition(45_000L)
            renderer.writePcmData(ByteArray(300))
            assertEquals(45_000L, renderer.getPlayedPositionMs())
            line.framePosition = 80L
            assertEquals(45_080L, renderer.getPlayedPositionMs())
            renderer.pause()
            assertEquals(45_080L, renderer.getPlayedPositionMs())
            renderer.resume()
            line.framePosition = 150L
            assertEquals(45_150L, renderer.getPlayedPositionMs())
            renderer.flush()
            renderer.resetPosition(20_000L)
            assertEquals(20_000L, renderer.getPlayedPositionMs())
        } finally {
            renderer.close()
        }
    }

    @Test
    fun queuedTempoBoundariesUsePlayedFramesWithoutSubtractingTheBufferTwice() {
        val clock = AudioPositionClock(1_000f)
        clock.reset(45_000L, 0L, 1f)
        clock.changeSpeed(150L, 2f)
        assertEquals(45_000L, clock.position(0L))
        assertEquals(45_149L, clock.position(149L))
        assertEquals(45_150L, clock.position(150L))
        assertEquals(45_250L, clock.position(200L))
        clock.changeSpeed(300L, 0.5f)
        assertEquals(45_448L, clock.position(299L))
        assertEquals(45_450L, clock.position(300L))
        assertEquals(45_500L, clock.position(400L))
    }

    @Test
    fun deviceWriteFailureStopsPlaybackAndReportsTheRendererFailure() {
        val failure = IllegalStateException("Audio device disconnected")
        val audioLine = TestSourceDataLine(writeFailure = failure).apply { availableBytes = 4_096 }
        val failed = CountDownLatch(1)
        var reported: Throwable? = null
        val player =
            AudioPlayer(onPlaybackFailed = { _, error ->
                reported = error
                failed.countDown()
            })
        try {
            val fixture = assertNotNull(javaClass.getResource("/ffmpeg/opus-251.webm"))
            assertTrue(player.prepareFile(File(fixture.toURI()).path, startPaused = false))
            AudioPlayer::class.java
                .getDeclaredField("renderer")
                .apply { isAccessible = true }
                .set(player, AudioRenderer(48_000, 2, audioLine.line))
            player.play()
            assertTrue(failed.await(5, TimeUnit.SECONDS), "Device failure must reach the playback handler")
            assertSame(failure, reported)
            assertEquals(AudioPlayerFailureSource.RENDERER, player.failureSource)
            assertFalse(player.isPlaying())
            assertTrue(audioLine.writtenBytes().isEmpty())
        } finally {
            player.close()
        }
    }

    @Test
    fun outputStarvationLogsTheDeviceBufferAndWriteGapWithoutChangingPlayback() {
        val line = TestSourceDataLine().apply { availableBytes = Int.MAX_VALUE }
        val renderer = AudioRenderer(48_000, 2, line.line)
        PlaybackEventLog.clear()
        try {
            val pcm = byteArrayOf(1, 2, 3, 4)
            renderer.writePcmData(pcm)
            assertTrue(PlaybackEventLog.events.value.isEmpty())
            renderer.writePcmData(pcm)
            val event = PlaybackEventLog.events.value.single()
            assertEquals("Desktop audio output buffer emptied between writes.", event.message)
            assertEquals("150", event.details["audioBufferMs"])
            assertEquals("1", event.details["underrunCount"])
            assertEquals("48000", event.details["sampleRate"])
            assertTrue(event.details.getValue("lastAudioWriteMs").toLong() >= 0L)
            assertTrue(renderer.isPlaying())
            assertContentEquals(pcm + pcm, line.writtenBytes())
            repeat(5) { renderer.writePcmData(pcm) }
            assertEquals(1, PlaybackEventLog.events.value.size)
        } finally {
            renderer.close()
            PlaybackEventLog.clear()
        }
    }

    @Test
    fun startupPauseSeekAndEndDrainDoNotReportOutputStarvation() {
        val line = TestSourceDataLine().apply { availableBytes = Int.MAX_VALUE }
        val renderer = AudioRenderer(48_000, 2, line.line)
        PlaybackEventLog.clear()
        try {
            val pcm = byteArrayOf(1, 2, 3, 4)
            renderer.writePcmData(pcm)
            renderer.pause()
            renderer.resume()
            renderer.writePcmData(pcm)
            renderer.flush()
            renderer.writePcmData(pcm)
            renderer.drain { true }
            renderer.writePcmData(pcm)
            assertTrue(PlaybackEventLog.events.value.isEmpty())
        } finally {
            renderer.close()
            PlaybackEventLog.clear()
        }
    }

    @Test
    fun returningToUnityDrainsTempoThenWritesPcmDirectly() {
        val line = TestSourceDataLine().apply { availableBytes = Int.MAX_VALUE }
        val renderer = AudioRenderer(48_000, 1, line.line)
        val filteredInput = ByteArray(48_000 * 2)
        val directInput = byteArrayOf(1, 2, 3, 4)
        val expected = ByteArrayOutputStream()
        try {
            renderer.setPlaybackSpeed(1.25f)
            renderer.writePcmData(filteredInput)
            renderer.setPlaybackSpeed(1f)
            renderer.writePcmData(directInput)
            AudioTempoProcessor(48_000, 1, 1.25f, 1f).use {
                it.process(filteredInput, expected::write)
                it.setSpeed(1f)
                it.process(null, expected::write)
            }
            expected.write(directInput)
            assertContentEquals(expected.toByteArray(), line.writtenBytes())
            renderer.writePcmData(directInput)
            expected.write(directInput)
            assertContentEquals(expected.toByteArray(), line.writtenBytes())
            assertTrue(renderer.drain { true })
            assertContentEquals(expected.toByteArray(), line.writtenBytes())
        } finally {
            renderer.close()
        }
    }

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun equalizerReusesBuffersWithoutAppendingStaleSamplesOrChangingInput() {
        val audioLine = TestSourceDataLine().apply { availableBytes = Int.MAX_VALUE }
        val profile = EqualizerProfile("half", "half", emptyList(), preamp = -6.0206)
        val renderer = AudioRenderer(48_000, 1, audioLine.line) { profile }
        val pcm = byteArrayOf(0xE8.toByte(), 0x03)
        try {
            renderer.writePcmData(pcm + pcm + pcm)
            renderer.writePcmData(pcm)
            renderer.writePcmData(pcm)
            assertContentEquals(byteArrayOf(0xE8.toByte(), 0x03), pcm)
            assertContentEquals(ByteArray(10) { if (it % 2 == 0) 0xF4.toByte() else 0x01 }, audioLine.writtenBytes())
        } finally {
            renderer.close()
        }
    }

    @Test
    fun closeWakesWriterBlockedOnFullDeviceBuffer() {
        val audioLine = TestSourceDataLine()
        val renderer = AudioRenderer(48_000, 2, audioLine.line)
        val writer = thread(isDaemon = true) { renderer.writePcmData(byteArrayOf(1, 2, 3, 4)) }
        try {
            assertTrue(audioLine.outputBlocked.await(5, TimeUnit.SECONDS))
            renderer.close()
            writer.join(1_000)
            assertFalse(writer.isAlive)
            assertTrue(audioLine.writtenBytes().isEmpty())
        } finally {
            renderer.close()
            writer.join(5_000)
        }
    }

    @Test
    fun muteAndFailedHardwareGainUsePcmAttenuation() {
        for (fails in listOf(false, true)) {
            val control =
                object : FloatControl(FloatControl.Type.MASTER_GAIN, -20f, 0f, 0.1f, 1, 0f, "dB") {
                    override fun setValue(value: Float) {
                        if (fails) error("Mixer rejected gain")
                        super.setValue(value)
                    }
                }
            val line = TestSourceDataLine(control).apply { availableBytes = Int.MAX_VALUE }
            val renderer = AudioRenderer(48_000, 1, line.line)
            try {
                renderer.setVolume(0f)
                renderer.writePcmData(byteArrayOf(0xE8.toByte(), 0x03))
                assertContentEquals(byteArrayOf(0, 0), line.writtenBytes())
                if (fails) {
                    renderer.setVolume(0.5f)
                    renderer.writePcmData(byteArrayOf(0xE8.toByte(), 0x03))
                    assertEquals(500.toShort(), ByteBuffer.wrap(line.writes.last()).order(ByteOrder.LITTLE_ENDIAN).short)
                }
            } finally {
                renderer.close()
            }
        }
    }

    @Test
    fun frameInvalidatedWhileWaitingForOutputCannotEnterNewGeneration() {
        val line = TestSourceDataLine()
        val renderer = AudioRenderer(48_000, 1, line.line)
        val current = AtomicBoolean(true)
        val writer = thread(isDaemon = true) { renderer.writePcmData(byteArrayOf(1, 2)) { current.get() } }
        try {
            assertTrue(line.outputBlocked.await(5, TimeUnit.SECONDS))
            current.set(false)
            renderer.flush()
            line.availableBytes = 4096
            writer.join(5_000)
            assertFalse(writer.isAlive)
            assertTrue(line.writtenBytes().isEmpty())
            renderer.writePcmData(byteArrayOf(3, 4)) { current.get() }
            assertTrue(line.writtenBytes().isEmpty())
            renderer.writePcmData(byteArrayOf(5, 6))
            assertContentEquals(byteArrayOf(5, 6), line.writtenBytes())
        } finally {
            renderer.close()
            writer.join(5_000)
        }
    }

    @Test
    fun hardwareVolumeDoesNotReplaceOrDuplicateThePcmBoost() {
        val control = object : FloatControl(FloatControl.Type.MASTER_GAIN, -80f, 0f, 0.1f, 1, 0f, "dB") {}
        val audioLine = TestSourceDataLine(control).apply { availableBytes = Int.MAX_VALUE }
        val renderer = AudioRenderer(48_000, 1, audioLine.line)
        try {
            renderer.normalizationGain = 1.4125376f
            renderer.setVolume(0.5f)
            renderer.writePcmData(byteArrayOf(0xE8.toByte(), 0x03))
            assertEquals(1412.toShort(), ByteBuffer.wrap(audioLine.writtenBytes()).order(ByteOrder.LITTLE_ENDIAN).short)
            assertEquals(-6.0206f, control.value, 0.0001f)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun audioBoostRaisesDesktopDeviceOutput() {
        val audioLine = TestSourceDataLine().apply { availableBytes = Int.MAX_VALUE }
        val renderer = AudioRenderer(48_000, 1, audioLine.line)
        try {
            Settings.shared.audioBoostDb = 6f
            renderer.writePcmData(byteArrayOf(0xE8.toByte(), 0x03))

            assertEquals(1_995.toShort(), ByteBuffer.wrap(audioLine.writtenBytes()).order(ByteOrder.LITTLE_ENDIAN).short)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun audioBoostLimitsNearFullScaleDeviceOutput() {
        val audioLine = TestSourceDataLine().apply { availableBytes = Int.MAX_VALUE }
        val renderer = AudioRenderer(48_000, 1, audioLine.line)
        AudioSpectrumMeter.addConsumer()
        try {
            Settings.shared.audioBoostDb = 6f
            renderer.writePcmData(byteArrayOf(0x30, 0x75))

            assertEquals(32_113.toShort(), ByteBuffer.wrap(audioLine.writtenBytes()).order(ByteOrder.LITTLE_ENDIAN).short)
            assertTrue(AudioSpectrumMeter.gainReductionDb > 5f)
        } finally {
            renderer.close()
            AudioSpectrumMeter.removeConsumer()
        }
    }

    @Test
    fun boostChangesTakeEffectWithoutReconfiguringAudioPipeline() {
        val audioLine = TestSourceDataLine().apply { availableBytes = Int.MAX_VALUE }
        val renderer = AudioRenderer(48_000, 1, audioLine.line)
        val reference = ParametricEqualizer(48_000, 1)
        val expected = mutableListOf<Short>()

        fun process(sample: Short) {
            val samples = shortArrayOf(sample)
            reference.processPcm16(samples, outputGainDb = Settings.shared.audioBoostDb.toDouble())
            expected += samples.single()
            renderer.writePcmData(
                ByteBuffer
                    .allocate(2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(sample)
                    .array(),
            )
        }
        try {
            Settings.shared.audioBoostDb = 6f
            process(30000)
            Settings.shared.audioBoostDb = 0f
            repeat(8) { process(1000) }
            Settings.shared.audioBoostDb = -6f
            process(5000)
            Settings.shared.audioBoostDb = 12f
            process(5000)
            reference.reset()
            renderer.flush()
            process(1000)
            val actual = ByteBuffer.wrap(audioLine.writtenBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            assertEquals(expected, List(actual.remaining()) { actual.get() })
        } finally {
            renderer.close()
        }
    }

    @Test
    fun normalizationBoostSoftLimitsPcmBeforeVolumeAndFades() {
        val audioLine = TestSourceDataLine().apply { availableBytes = Int.MAX_VALUE }
        val renderer = AudioRenderer(48_000, 1, audioLine.line)

        fun pcm(vararg samples: Short): ByteArray =
            ByteBuffer
                .allocate(samples.size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    samples.forEach(::putShort)
                }.array()
        try {
            renderer.normalizationGain = 1.4125376f
            renderer.setVolume(0.5f)
            renderer.writePcmData(pcm(1000, Short.MAX_VALUE, Short.MIN_VALUE))
            renderer.normalizationGain = 1f
            renderer.writePcmData(pcm(1000))
            val output = ByteBuffer.wrap(audioLine.writtenBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            assertEquals(706.toShort(), output.get())
            val peak = output.get()
            assertTrue(peak in 16_000 until 16_384)
            assertEquals((-peak).toShort(), output.get())
            assertEquals(490.toShort(), output.get())
        } finally {
            renderer.close()
        }
    }

    @Test
    fun trailingSilenceReachesTheMediaEndWithTempoRounding() {
        val line = TestSourceDataLine().apply { availableBytes = Int.MAX_VALUE }
        val renderer = AudioRenderer(48_000, 1, line.line, silenceSettings = { true to false })
        try {
            renderer.setPlaybackSpeed(2f)
            repeat(200) { renderer.writePcmData(ByteArray(4_800)) }
            assertTrue(renderer.drain { true })
            assertTrue(renderer.getPlayedPositionMs() in 9_990L..10_010L)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun silenceCompressionKeepsMediaTimeWithTempoAndLiveSettings() {
        val line = TestSourceDataLine().apply { availableBytes = Int.MAX_VALUE }
        var enabled = true
        val renderer = AudioRenderer(48_000, 1, line.line, silenceSettings = { enabled to false })
        try {
            renderer.setPlaybackSpeed(2f)
            renderer.writePcmData(ByteArray(48_000 * 2))
            assertTrue(line.writtenBytes().size < 48_000)
            enabled = false
            renderer.writePcmData(ByteArray(48_000 * 2))
            assertTrue(renderer.drain { true })
            assertTrue(renderer.getPlayedPositionMs() in 1_990L..2_010L)
            renderer.flush()
            renderer.setPlaybackSpeed(1f)
            renderer.resetPosition(500)
            renderer.writePcmData(ByteArray(9_600))
            assertTrue(renderer.drain { true })
            assertTrue(renderer.getPlayedPositionMs() in 590L..610L)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun activeEqualizerProfileShapesWrittenPcm() {
        val audioLine = TestSourceDataLine().apply { availableBytes = Int.MAX_VALUE }
        var profile: EqualizerProfile? = EqualizerProfile("half", "half", emptyList(), preamp = -6.0206)
        val renderer = AudioRenderer(48_000, 1, audioLine.line) { profile }
        val pcm = byteArrayOf(0xE8.toByte(), 0x03)
        try {
            renderer.writePcmData(pcm)
            profile = null
            renderer.writePcmData(pcm)
            assertContentEquals(byteArrayOf(0xF4.toByte(), 0x01) + pcm, audioLine.writtenBytes())
        } finally {
            renderer.close()
        }
    }

    @Test
    fun changingPitchDrainsOldFilterAndKeepsEveryPcmSegment() {
        val sampleRate = 48_000
        val data = ByteArray(sampleRate * 2)
        val audioLine = TestSourceDataLine().apply { availableBytes = Int.MAX_VALUE }
        val renderer = AudioRenderer(sampleRate, 1, audioLine.line)
        val expected = ByteArrayOutputStream()
        try {
            for (pitch in listOf(0.5f, 2f, 1f)) {
                renderer.setPlaybackPitch(pitch)
                renderer.writePcmData(data)
                if (pitch == 1f) {
                    expected.write(data)
                } else {
                    AudioTempoProcessor(sampleRate, 1, 1f, pitch).use {
                        it.process(data, expected::write)
                        it.process(null, expected::write)
                    }
                }
            }
            assertTrue(renderer.drain { true })
            assertContentEquals(expected.toByteArray(), audioLine.writtenBytes())
            assertTrue(renderer.getPlayedPositionMs() in 2_900..3_100)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun clockReadsDoNotWaitForBlockingDeviceWrites() {
        val audioLine = TestSourceDataLine()
        val renderer = AudioRenderer(48_000, 2, audioLine.line)
        val writing = CountDownLatch(1)
        val clockRead = CountDownLatch(1)
        val writer =
            thread(isDaemon = true) {
                writing.countDown()
                renderer.writePcmData(ByteArray(48_000 * 2 * 2 * 2))
            }
        try {
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            assertTrue(audioLine.outputBlocked.await(5, TimeUnit.SECONDS))
            assertTrue(writer.isAlive)
            thread(isDaemon = true) {
                renderer.getPlayedPositionMs()
                clockRead.countDown()
            }
            assertTrue(clockRead.await(5, TimeUnit.SECONDS), "Audio/video clock blocked behind PCM output")
        } finally {
            renderer.close()
            writer.join(5_000)
        }
    }

    @Test
    fun controlsDoNotWaitForBlockingDeviceWrites() {
        val audioLine = TestSourceDataLine()
        val renderer = AudioRenderer(48_000, 2, audioLine.line)
        val writing = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val writer =
            thread(isDaemon = true) {
                writing.countDown()
                renderer.writePcmData(ByteArray(48_000 * 2 * 2 * 2))
            }
        try {
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            assertTrue(audioLine.outputBlocked.await(5, TimeUnit.SECONDS))
            assertTrue(writer.isAlive)
            thread(isDaemon = true) {
                renderer.pause()
                paused.countDown()
            }
            assertTrue(paused.await(5, TimeUnit.SECONDS), "Pause blocked behind PCM output")
            renderer.flush()
            renderer.resume()
        } finally {
            renderer.close()
            writer.join(5_000)
        }
    }

    @Test
    fun pauseResumePreservesBlockedPcmAtFullAndPartialCapacity() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        for (initialCapacity in listOf(0, data.size / 2)) {
            val audioLine = TestSourceDataLine().apply { availableBytes = initialCapacity }
            val renderer = AudioRenderer(sampleRate = 1_000, channelCount = 1, line = audioLine.line)
            val writer = thread(isDaemon = true) { renderer.writePcmData(data) }
            try {
                assertTrue(audioLine.outputBlocked.await(5, TimeUnit.SECONDS))
                val writtenBeforePause = audioLine.writtenBytes()

                renderer.pause()
                audioLine.availableBytes = data.size

                assertTrue(writer.isAlive, "Paused PCM writer discarded its remaining output")
                assertContentEquals(writtenBeforePause, audioLine.writtenBytes())
                renderer.resume()
                writer.join(5_000)

                assertFalse(writer.isAlive, "PCM writer did not resume")
                assertContentEquals(data, audioLine.writtenBytes())
            } finally {
                renderer.close()
                writer.join(5_000)
            }
        }
    }

    @Test
    fun speedChangePreservesBlockedPcmAndItsPositionMapping() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        for (initialCapacity in listOf(0, data.size / 2)) {
            val audioLine = TestSourceDataLine().apply { availableBytes = initialCapacity }
            val renderer = AudioRenderer(sampleRate = 1_000, channelCount = 1, line = audioLine.line)
            val writer = thread(isDaemon = true) { renderer.writePcmData(data) }
            try {
                assertTrue(audioLine.outputBlocked.await(5, TimeUnit.SECONDS))

                renderer.setPlaybackSpeed(2f)
                audioLine.availableBytes = data.size
                writer.join(5_000)

                assertFalse(writer.isAlive, "Speed change discarded or blocked PCM output")
                assertContentEquals(data, audioLine.writtenBytes())
                assertEquals(4L, renderer.getPlayedPositionMs())
            } finally {
                renderer.close()
                writer.join(5_000)
            }
        }
    }

    @Test
    fun flushAbortsBlockedPcmBeforeReplacementWrite() {
        val audioLine = TestSourceDataLine()
        val renderer = AudioRenderer(sampleRate = 48_000, channelCount = 2, line = audioLine.line)
        val stale = byteArrayOf(1, 2, 3, 4)
        val replacement = byteArrayOf(5, 6, 7, 8)
        val writer = thread(isDaemon = true) { renderer.writePcmData(stale) }
        try {
            assertTrue(audioLine.availabilityChecked.await(5, TimeUnit.SECONDS))

            renderer.flush()
            audioLine.availableBytes = replacement.size
            writer.join(5_000)

            assertFalse(writer.isAlive, "Flushed PCM writer did not stop")
            renderer.writePcmData(replacement)
            assertContentEquals(replacement, audioLine.writes.single())
        } finally {
            renderer.close()
        }
    }
}

private class TestSourceDataLine(
    private val masterGain: FloatControl? = null,
    private val writeFailure: RuntimeException? = null,
    private val autoPlayWrites: Boolean = true,
) {
    val availabilityChecks = AtomicInteger()
    val availabilityChecked = CountDownLatch(1)
    val outputBlocked = CountDownLatch(1)
    val writes = Collections.synchronizedList(mutableListOf<ByteArray>())

    @Volatile
    var availableBytes = 0

    private var open = false
    private var active = false
    private var bufferSize = 0
    private var format = AudioFormat(48_000f, 16, 2, true, false)
    var framePosition = 0L

    fun writtenBytes(): ByteArray =
        synchronized(writes) {
            ByteArray(writes.sumOf { it.size }).also { output ->
                var offset = 0
                writes.forEach { bytes ->
                    bytes.copyInto(output, offset)
                    offset += bytes.size
                }
            }
        }

    val line =
        Proxy.newProxyInstance(
            SourceDataLine::class.java.classLoader,
            arrayOf(SourceDataLine::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "open" -> {
                    arguments?.filterIsInstance<AudioFormat>()?.firstOrNull()?.let { format = it }
                    arguments?.filterIsInstance<Int>()?.firstOrNull()?.let { bufferSize = it }
                    open = true
                    null
                }

                "close" -> {
                    open = false
                    active = false
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

                "getFormat" -> {
                    format
                }

                "getBufferSize" -> {
                    bufferSize
                }

                "available" -> {
                    availabilityChecks.incrementAndGet()
                    availabilityChecked.countDown()
                    availableBytes.also { if (it == 0) outputBlocked.countDown() }
                }

                "write" -> {
                    writeFailure?.let { throw it }
                    val data = arguments!![0] as ByteArray
                    val offset = arguments[1] as Int
                    val length = arguments[2] as Int
                    writes += data.copyOfRange(offset, offset + length)
                    availableBytes = (availableBytes - length).coerceAtLeast(0)
                    if (autoPlayWrites) framePosition += length / format.frameSize
                    length
                }

                "getFramePosition" -> {
                    framePosition.toInt()
                }

                "getLongFramePosition" -> {
                    framePosition
                }

                "getMicrosecondPosition" -> {
                    0L
                }

                "getLevel" -> {
                    0f
                }

                "isControlSupported" -> {
                    masterGain != null && arguments?.get(0) == FloatControl.Type.MASTER_GAIN
                }

                "getControl" -> {
                    requireNotNull(masterGain)
                }

                "getControls" -> {
                    listOfNotNull<Control>(masterGain).toTypedArray()
                }

                "getLineInfo" -> {
                    DataLine.Info(SourceDataLine::class.java, format)
                }

                "flush", "drain", "addLineListener", "removeLineListener" -> {
                    null
                }

                "toString" -> {
                    "TestSourceDataLine"
                }

                "hashCode" -> {
                    System.identityHashCode(this)
                }

                "equals" -> {
                    false
                }

                else -> {
                    error("Unexpected SourceDataLine call: ${method.name}")
                }
            }
        } as SourceDataLine
}
