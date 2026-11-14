package com.metrolist.android.playback

import android.app.Application
import android.media.AudioTimestamp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink.AudioSinkConfig
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class MusicAudioSinkTest {
    @Test(timeout = 10_000)
    fun pitchChangesAndBackwardFlushKeepWritingAudiblePcm() = verifyPitchChanges(false)

    @Test(timeout = 10_000)
    fun hardwareSpeedFallsBackToSoftwareForPitchAndReset() = verifyPitchChanges(true)

    @Test(timeout = 10_000)
    fun rapidExtremePitchChangesDoNotReconfigureOutputRepeatedlyOrReachHardware() = verifyPitchChanges(true, rapid = true)

    @Test
    fun presentationFramesKeepProcessorSpeedTrimmingAndSkippedSilenceMapping() {
        for (hardware in listOf(false, true)) {
            for (skipSilence in listOf(false, true)) {
                verifyTimestampMapping(hardware, skipSilence)
            }
        }
    }

    private fun verifyTimestampMapping(
        hardware: Boolean,
        skipSilence: Boolean,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val processors = musicAudioProcessorChain(NormalizationAudioProcessor { 1f })
        var nowNs = 1_000_000_000L
        var presentedFrames = 0L
        var writtenBytes = 0L
        var parameters = PlaybackParameters.DEFAULT
        val platform =
            Proxy.newProxyInstance(AudioOutput::class.java.classLoader, arrayOf(AudioOutput::class.java)) { _, method, args ->
                when (method.name) {
                    "getSampleRate" -> {
                        48_000
                    }

                    "getPositionUs" -> {
                        0L
                    }

                    "getAudioSessionId" -> {
                        1
                    }

                    "getBufferSizeInFrames" -> {
                        96_000L
                    }

                    "getPlaybackParameters" -> {
                        parameters
                    }

                    "setPlaybackParameters" -> {
                        parameters = args!![0] as PlaybackParameters
                        null
                    }

                    "isOffloadedPlayback", "isStalled" -> {
                        false
                    }

                    "write" -> {
                        val buffer = args!![0] as ByteBuffer
                        writtenBytes += buffer.remaining()
                        buffer.position(buffer.limit())
                        true
                    }

                    else -> {
                        null
                    }
                }
            } as AudioOutput
        val sink =
            pitchSafeAudioSink(context, hardware, processors) {
                object : ForwardingAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()) {
                    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput =
                        TimestampAudioOutput(
                            platform,
                            4,
                            { timestamp: AudioTimestamp ->
                                timestamp.framePosition = presentedFrames
                                timestamp.nanoTime = nowNs
                                true
                            },
                            { 1 },
                            { nowNs },
                        )
                }
            }
        try {
            sink.configure(
                AudioSinkConfig
                    .Builder(
                        Format
                            .Builder()
                            .setSampleMimeType(MimeTypes.AUDIO_RAW)
                            .setSampleRate(48_000)
                            .setChannelCount(2)
                            .setPcmEncoding(C.ENCODING_PCM_16BIT)
                            .setEncoderDelay(312)
                            .setEncoderPadding(960)
                            .build(),
                    ).build(),
            )
            sink.setPlaybackParameters(PlaybackParameters(2f, if (hardware) 1f else 0.75f))
            sink.skipSilenceEnabled = skipSilence
            sink.play()
            val input =
                ByteBuffer.allocateDirect(96_000 * 4).order(ByteOrder.nativeOrder()).apply {
                    repeat(96_000) { frame ->
                        val sample: Short = if (frame in 20_000..60_000) 0 else 2_000
                        putShort(sample)
                        putShort(sample)
                    }
                    flip()
                }
            assertTrue(sink.handleBuffer(input, 30_000_000L, 1))
            sink.getCurrentPositionUs(false)
            presentedFrames = if (hardware) 4_800 else 2_400
            assertTrue(writtenBytes / 4 > presentedFrames)
            nowNs += 50_000_000L
            val outputUs = Util.sampleCountToDurationUs(presentedFrames, 48_000)
            val skippedFrames = processors.skippedOutputFrameCount
            assertEquals(skipSilence, skippedFrames > 0L)
            assertEquals(
                30_000_000L + processors.getMediaDuration(outputUs) + Util.sampleCountToDurationUs(skippedFrames, 48_000),
                sink.getCurrentPositionUs(false),
            )
        } finally {
            sink.reset()
            sink.release()
        }
    }

    private fun verifyPitchChanges(
        hardware: Boolean,
        rapid: Boolean = false,
    ) {
        val context = RuntimeEnvironment.getApplication()
        var samplesWritten = 0L
        var audibleSamples = 0L
        var hardwareParameters = PlaybackParameters.DEFAULT
        val outputModes = mutableListOf<Boolean>()
        var providerCreations = 0
        var blockedWrites = 0
        val output =
            Proxy.newProxyInstance(AudioOutput::class.java.classLoader, arrayOf(AudioOutput::class.java)) { _, method, args ->
                when (method.name) {
                    "write" -> {
                        val buffer = args!![0] as ByteBuffer
                        if (blockedWrites > 0) {
                            blockedWrites--
                            false
                        } else {
                            repeat(minOf(buffer.remaining() / 2, 256)) {
                                if (buffer.short.toInt() != 0) audibleSamples++
                                samplesWritten++
                            }
                            !buffer.hasRemaining()
                        }
                    }

                    "getPositionUs" -> {
                        samplesWritten * 1_000_000L / 96_000L
                    }

                    "getSampleRate" -> {
                        48_000
                    }

                    "getAudioSessionId" -> {
                        1
                    }

                    "getBufferSizeInFrames" -> {
                        4_096L
                    }

                    "getPlaybackParameters" -> {
                        hardwareParameters
                    }

                    "setPlaybackParameters" -> {
                        hardwareParameters = args!![0] as PlaybackParameters
                        require(hardwareParameters.pitch == 1f) { "Simulated AudioTrack rejects hardware pitch" }
                        null
                    }

                    "isOffloadedPlayback", "isStalled" -> {
                        false
                    }

                    else -> {
                        null
                    }
                }
            } as AudioOutput
        val sink =
            pitchSafeAudioSink(
                context,
                hardware,
                musicAudioProcessorChain(
                    EqualizerAudioProcessor({ 0f }, { null }),
                    NormalizationAudioProcessor { 1f },
                    SilenceDetectorAudioProcessor(),
                ),
                outputProvider = {
                    providerCreations++
                    object : ForwardingAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()) {
                        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                            outputModes += config.usePlaybackParameters
                            hardwareParameters = PlaybackParameters.DEFAULT
                            return DiagnosticAudioOutput(output, config, { _, _ -> })
                        }
                    }
                },
            )
        try {
            sink.configure(
                AudioSinkConfig
                    .Builder(
                        Format
                            .Builder()
                            .setSampleMimeType(MimeTypes.AUDIO_RAW)
                            .setSampleRate(48_000)
                            .setChannelCount(2)
                            .setPcmEncoding(C.ENCODING_PCM_16BIT)
                            .setEncoderDelay(312)
                            .setEncoderPadding(960)
                            .build(),
                    ).build(),
            )
            sink.play()
            var frame = 0L
            for (seekBeforeChange in listOf(false, true)) {
                for (pitch in listOf(1f, 0.5f, 2f, 1f, 1.1f, 1f)) {
                    if (seekBeforeChange) {
                        sink.flush()
                        frame = 0L
                    }
                    val speed = if (seekBeforeChange) 1f else 0.95f
                    val rapidChanges = rapid && outputModes.isNotEmpty()
                    if (rapidChanges) {
                        repeat(100) { change ->
                            sink.setPlaybackParameters(PlaybackParameters(speed, if (change % 2 == 0) 2f else 0.5f))
                        }
                        blockedWrites = 5
                    }
                    sink.setPlaybackParameters(PlaybackParameters(speed, pitch))
                    val before = audibleSamples
                    repeat(20) {
                        val input =
                            ByteBuffer.allocateDirect(4_096).order(ByteOrder.nativeOrder()).apply {
                                repeat(2_048) { putShort(if (it % 32 < 16) 2_000 else -2_000) }
                                flip()
                            }
                        var handled = false
                        repeat(100) { attempt ->
                            if (!handled) {
                                if (rapidChanges && attempt < 10) {
                                    sink.setPlaybackParameters(PlaybackParameters(speed, if (attempt % 2 == 0) 0.5f else 2f))
                                } else if (rapidChanges && attempt == 10) {
                                    sink.setPlaybackParameters(PlaybackParameters(speed, pitch))
                                }
                                handled = sink.handleBuffer(input, frame * 1_000_000L / 48_000L, 1)
                            }
                        }
                        assertTrue("Sink stuck at pitch $pitch", handled)
                        frame += 1_024
                    }
                    assertTrue("Silent sink at pitch $pitch", audibleSamples > before + 10_000)
                }
            }
            repeat(100) { sink.playToEndOfStream() }
            assertTrue("Sink did not drain at end of stream", sink.isEnded)
            assertEquals(hardware, outputModes.first())
            assertEquals("Only the first pitch change may replace the provider", if (hardware) 2 else 1, providerCreations)
            assertTrue("Pitch fallback must survive Reset and seeks", outputModes.drop(1).none { it })
        } finally {
            sink.reset()
            sink.release()
        }
    }
}
