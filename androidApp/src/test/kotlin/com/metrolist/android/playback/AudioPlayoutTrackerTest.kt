package com.metrolist.android.playback

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink.AudioSinkConfig
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class AudioPlayoutTrackerTest {
    @Test
    fun tempoResetCannotPromoteWhileCatchUpAudioRemainsQueued() {
        for (hardware in listOf(false, true)) {
            for (skipSilence in listOf(false, true)) {
                for (tempo in listOf(PlaybackParameters(0.5f, 0.8f), PlaybackParameters.DEFAULT, PlaybackParameters(2f, 1.25f))) {
                    TestOutput(hardware, skipSilence).use { output ->
                        val sink = output.sink
                        sink.setPlaybackParameters(PlaybackParameters(warmReplacementCatchUpSpeed(tempo.speed), tempo.pitch))
                        output.queueSecond(30_000_000L)
                        sink.setPlaybackParameters(tempo)
                        val checkpoint = output.tracker.checkpoint(tempo)
                        assertEquals(tempo, sink.playbackParameters)
                        assertFalse(output.tracker.hasPlayed(checkpoint))

                        output.queueSecond(31_000_000L)
                        val boundaryUs = checkNotNull(checkpoint.endUs)
                        assertTrue(boundaryUs > 0L)
                        assertTrue(output.writtenDurationUs() > boundaryUs)
                        assertFalse(output.tracker.hasPlayed(checkpoint))
                        assertEquals(WarmReplacementAction.DRAIN, output.action(checkpoint))
                        assertEquals(WarmReplacementAction.DRAIN, output.action(checkpoint, playing = false))
                        assertEquals(WarmReplacementAction.DRAIN, output.action(checkpoint, sourceMs = 29_000L))
                        assertEquals(WarmReplacementAction.DRAIN, output.action(checkpoint, sourceMs = 32_000L))

                        output.positionUs = boundaryUs
                        sink.getCurrentPositionUs(false)
                        assertFalse(output.tracker.hasPlayed(checkpoint))
                        output.positionUs += 1_000L
                        sink.getCurrentPositionUs(false)
                        assertTrue(output.tracker.hasPlayed(checkpoint))
                        assertTrue(output.queuedProcessingSpeeds().all { it == if (output.hardwareActive) 1f else tempo.speed })
                        assertEquals(WarmReplacementAction.COMMIT, output.action(checkpoint))
                        assertEquals(WarmReplacementAction.WAIT_FOR_SOURCE, output.action(checkpoint, sourceMs = 29_000L))
                        assertEquals(WarmReplacementAction.CATCH_UP, output.action(checkpoint, sourceMs = 32_000L))
                    }
                }
            }
        }
    }

    @Test
    fun blockedProcessorTailsMustDrainBeforeTheCheckpointCanBePresented() {
        TestOutput(hardware = false, skipSilence = false).use { output ->
            output.sink.setPlaybackParameters(PlaybackParameters(2f))
            output.queueSecond(30_000_000L)
            output.sink.setPlaybackParameters(PlaybackParameters.DEFAULT)
            val checkpoint = output.tracker.checkpoint(PlaybackParameters.DEFAULT)
            val input = output.pcmSecond()
            output.writeLimit = 0
            assertFalse(output.sink.handleBuffer(input, 31_000_000L, 1))
            output.positionUs = output.writtenDurationUs() + 1_000L
            output.sink.getCurrentPositionUs(false)
            assertFalse(output.tracker.hasPlayed(checkpoint))

            output.writeLimit = 256 * 4
            output.queueBuffer(input, 31_000_000L)
            val boundaryUs = checkNotNull(checkpoint.endUs)
            assertTrue(boundaryUs > output.positionUs)
            output.positionUs = boundaryUs + 1_000L
            output.sink.getCurrentPositionUs(false)
            assertTrue(output.tracker.hasPlayed(checkpoint))
            assertTrue(output.queuedProcessingSpeeds().all { it == 1f })
        }
    }

    @Test
    fun userTempoChangeDuringDrainDoesNotLeaveTheCheckpointPending() {
        TestOutput(hardware = false, skipSilence = false).use { output ->
            output.sink.setPlaybackParameters(PlaybackParameters(2f))
            output.queueSecond(30_000_000L)
            output.sink.setPlaybackParameters(PlaybackParameters.DEFAULT)
            val checkpoint = output.tracker.checkpoint(PlaybackParameters.DEFAULT)
            val changedTempo = PlaybackParameters(1.25f, 0.8f)
            output.sink.setPlaybackParameters(changedTempo)
            output.queueSecond(31_000_000L)
            assertEquals(changedTempo, output.sink.playbackParameters)
            assertFalse(output.tracker.hasPlayed(checkpoint))
            output.positionUs = checkNotNull(checkpoint.endUs) + 1_000L
            output.sink.getCurrentPositionUs(false)
            assertTrue(output.tracker.hasPlayed(checkpoint))
            assertTrue(output.queuedProcessingSpeeds().all { it == changedTempo.speed })
        }
    }

    @Test
    fun seekFlushDiscardsQueuedCatchUpEvenWhenThePositionMovesBackwards() {
        TestOutput(hardware = false, skipSilence = false).use { output ->
            output.sink.setPlaybackParameters(PlaybackParameters(2f))
            output.queueSecond(30_000_000L)
            output.sink.setPlaybackParameters(PlaybackParameters.DEFAULT)
            val checkpoint = output.tracker.checkpoint(PlaybackParameters.DEFAULT)
            assertFalse(output.tracker.hasPlayed(checkpoint))
            output.sink.flush()
            assertTrue(output.tracker.hasPlayed(checkpoint))
            output.queueSecond(10_000_000L)
            assertFalse(output.tracker.hasPlayed(output.tracker.checkpoint(PlaybackParameters.DEFAULT)))
        }
    }

    private class TestOutput(
        hardware: Boolean,
        val skipSilence: Boolean,
    ) : AutoCloseable {
        val tracker = AudioPlayoutTracker()
        var positionUs = 0L
        var writeLimit = Int.MAX_VALUE
        var hardwareActive = false
        private var writtenBytes = 0L
        private var hardwareParameters = PlaybackParameters.DEFAULT
        private var processingSpeed = 1f
        private val buffers = ArrayDeque<Pair<Long, Float>>()
        private val musicProcessors = musicAudioProcessorChain()
        private val processors =
            tracker.wrap(
                object : AudioProcessorChain by musicProcessors {
                    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters =
                        musicProcessors.applyPlaybackParameters(playbackParameters).also { processingSpeed = it.speed }
                },
            )
        private val platform =
            Proxy.newProxyInstance(AudioOutput::class.java.classLoader, arrayOf(AudioOutput::class.java)) { _, method, args ->
                when (method.name) {
                    "getSampleRate" -> {
                        48_000
                    }

                    "getAudioSessionId" -> {
                        1
                    }

                    "getBufferSizeInFrames" -> {
                        96_000L
                    }

                    "getPositionUs" -> {
                        positionUs
                    }

                    "getPlaybackParameters" -> {
                        hardwareParameters
                    }

                    "setPlaybackParameters" -> {
                        hardwareParameters = args!![0] as PlaybackParameters
                        null
                    }

                    "isOffloadedPlayback", "isStalled" -> {
                        false
                    }

                    "write" -> {
                        val buffer = args!![0] as ByteBuffer
                        val accepted = minOf(buffer.remaining(), writeLimit)
                        buffer.position(buffer.position() + accepted)
                        writtenBytes += accepted
                        if (accepted > 0) buffers.addLast(writtenDurationUs() to processingSpeed)
                        !buffer.hasRemaining()
                    }

                    "flush" -> {
                        buffers.clear()
                        writtenBytes = 0L
                        positionUs = 0L
                        null
                    }

                    else -> {
                        null
                    }
                }
            } as AudioOutput
        val sink =
            pitchSafeAudioSink(RuntimeEnvironment.getApplication(), hardware, processors) {
                tracker.wrap(
                    object : ForwardingAudioOutputProvider(
                        AudioTrackAudioOutputProvider.Builder(RuntimeEnvironment.getApplication()).build(),
                    ) {
                        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                            hardwareActive = config.usePlaybackParameters
                            return platform
                        }
                    },
                )
            }

        init {
            sink.configure(
                AudioSinkConfig
                    .Builder(
                        Format
                            .Builder()
                            .setSampleMimeType(MimeTypes.AUDIO_RAW)
                            .setSampleRate(48_000)
                            .setChannelCount(2)
                            .setPcmEncoding(C.ENCODING_PCM_16BIT)
                            .build(),
                    ).build(),
            )
            sink.skipSilenceEnabled = skipSilence
            sink.play()
        }

        fun writtenDurationUs(): Long = Util.sampleCountToDurationUs(writtenBytes / 4, 48_000)

        fun queuedProcessingSpeeds(): List<Float> = buffers.filter { it.first >= positionUs }.map { it.second }

        fun pcmSecond(): ByteBuffer =
            ByteBuffer.allocateDirect(48_000 * 4).order(ByteOrder.nativeOrder()).apply {
                repeat(48_000) { frame ->
                    val sample: Short = if (skipSilence && frame in 4_800..43_200) 0 else 2_000
                    putShort(sample)
                    putShort(sample)
                }
                flip()
            }

        fun queueSecond(presentationTimeUs: Long) = queueBuffer(pcmSecond(), presentationTimeUs)

        fun queueBuffer(
            input: ByteBuffer,
            presentationTimeUs: Long,
        ) {
            repeat(1_000) {
                if (sink.handleBuffer(input, presentationTimeUs, 1)) return
            }
            error("Sink failed to accept the input")
        }

        fun action(
            checkpoint: AudioPlayoutTracker.Checkpoint,
            sourceMs: Long = 30_050L,
            playing: Boolean = true,
        ): WarmReplacementAction =
            warmReplacementAction(
                sourceMs,
                30_000L,
                playing,
                true,
                playing,
                true,
                1,
                catchUpAudioDrained = tracker.hasPlayed(checkpoint),
            )

        override fun close() {
            sink.reset()
            sink.release()
        }
    }
}
