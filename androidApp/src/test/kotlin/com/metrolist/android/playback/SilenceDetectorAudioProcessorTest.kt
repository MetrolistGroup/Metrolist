package com.metrolist.android.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class SilenceDetectorAudioProcessorTest {
    @Test
    fun requiresTwoSecondsThenConfirmationAndDoesNotRepeatSignalsDuringSilence() =
        runTest {
            val processor = processor()
            val seeks = mutableListOf<Long>()
            backgroundScope.launch {
                processor.skipWhenConfirmed({ true }, { 5_000L }, { 60_000L }, seeks::add)
            }
            processor.process(pcm(1_999, 255))
            runCurrent()
            advanceTimeBy(1_000)
            assertTrue(seeks.isEmpty())
            processor.process(pcm(1, 255))
            runCurrent()
            advanceTimeBy(199)
            processor.process(pcm(2_000))
            runCurrent()
            assertTrue(seeks.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(20_000L), seeks)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, seeks.size)
        }

    @Test
    fun audibleMusicDoesNotWakeTheCollector() =
        runTest {
            val processor = processor()
            var checks = 0
            backgroundScope.launch {
                processor.skipWhenConfirmed({
                    checks++
                    true
                }, { 0L }, { 60_000L }) {
                    error("Audible audio must not seek")
                }
            }
            repeat(100) {
                processor.process(pcm(1_000, 256))
                advanceTimeBy(1_000)
            }
            runCurrent()
            assertEquals(0, checks)
        }

    @Test
    fun noiseInEitherChannelInvalidatesConfirmationAndANewThresholdCanTrigger() =
        runTest {
            for (channel in 0..1) {
                val processor = processor()
                val seeks = mutableListOf<Long>()
                val job =
                    backgroundScope.launch {
                        processor.skipWhenConfirmed({ true }, { 0L }, { 60_000L }, seeks::add)
                    }
                processor.process(pcm(2_000))
                runCurrent()
                advanceTimeBy(100)
                processor.process(pcm(1).putShort(channel * 2, 256))
                processor.process(pcm(2_000))
                advanceTimeBy(100)
                runCurrent()
                assertTrue(seeks.isEmpty())
                advanceTimeBy(200)
                runCurrent()
                assertEquals(listOf(15_000L), seeks)
                job.cancel()
            }
        }

    @Test
    fun seeksFlushesResetAndSettingChangesInvalidatePendingThresholds() =
        runTest {
            val resets =
                listOf<(SilenceDetectorAudioProcessor) -> Unit>(
                    { it.resetTracking() },
                    { it.flush(AudioProcessor.StreamMetadata.DEFAULT) },
                    {
                        it.reset()
                        configure(it)
                    },
                    {
                        it.enabled = false
                        it.enabled = true
                    },
                )
            for (reset in resets) {
                val processor = processor()
                val seeks = mutableListOf<Long>()
                val job =
                    backgroundScope.launch {
                        processor.skipWhenConfirmed({ true }, { 0L }, { 60_000L }, seeks::add)
                    }
                processor.process(pcm(2_000))
                runCurrent()
                advanceTimeBy(100)
                reset(processor)
                advanceTimeBy(100)
                runCurrent()
                assertTrue(seeks.isEmpty())
                processor.process(pcm(2_000))
                runCurrent()
                advanceTimeBy(200)
                runCurrent()
                assertEquals(listOf(15_000L), seeks)
                job.cancel()
            }
        }

    @Test
    fun cancelledCollectorDoesNotConsumeFutureThresholds() =
        runTest {
            val processor = processor()
            val seeks = mutableListOf<Long>()
            val first =
                backgroundScope.launch {
                    processor.skipWhenConfirmed({ true }, { 0L }, { 60_000L }, seeks::add)
                }
            processor.process(pcm(2_000))
            runCurrent()
            advanceTimeBy(100)
            first.cancel()
            processor.resetTracking()
            runCurrent()
            backgroundScope.launch {
                processor.skipWhenConfirmed({ true }, { 0L }, { 60_000L }, seeks::add)
            }
            advanceTimeBy(1_000)
            assertTrue(seeks.isEmpty())
            processor.process(pcm(2_000))
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            assertEquals(listOf(15_000L), seeks)
        }

    @Test
    fun pauseDuringConfirmationPreventsSeek() =
        runTest {
            val processor = processor()
            var playing = true
            val seeks = mutableListOf<Long>()
            backgroundScope.launch {
                processor.skipWhenConfirmed({ playing }, { 0L }, { 60_000L }, seeks::add)
            }
            processor.process(pcm(2_000))
            runCurrent()
            advanceTimeBy(100)
            playing = false
            advanceTimeBy(100)
            runCurrent()
            assertTrue(seeks.isEmpty())
        }

    @Test
    fun newThresholdDuringCooldownWaitsBeforeStartingConfirmation() =
        runTest {
            val processor = processor()
            val seeks = mutableListOf<Long>()
            backgroundScope.launch {
                processor.skipWhenConfirmed({ true }, { 0L }, { 60_000L }, seeks::add)
            }
            processor.process(pcm(2_000))
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            assertEquals(1, seeks.size)
            processor.process(pcm(2_000))
            advanceTimeBy(549)
            runCurrent()
            assertEquals(1, seeks.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, seeks.size)
        }

    @Test
    fun preservesTrackEndAndRejectsUnknownOrShortDurations() =
        runTest {
            for (duration in listOf(-1L, 15_000L, 60_000L)) {
                val processor = processor()
                var position = 55_000L
                val seeks = mutableListOf<Long>()
                val job =
                    backgroundScope.launch {
                        processor.skipWhenConfirmed({ true }, { position }, { duration }) {
                            position = it
                            seeks += it
                        }
                    }
                processor.process(pcm(2_000))
                runCurrent()
                advanceTimeBy(200)
                runCurrent()
                assertEquals(if (duration == 60_000L) listOf(59_500L) else emptyList<Long>(), seeks)
                processor.process(pcm(2_000))
                advanceTimeBy(1_000)
                runCurrent()
                assertEquals(if (duration == 60_000L) 1 else 0, seeks.size)
                job.cancel()
            }
        }

    @Test
    fun timelineUpdateRetriesSilenceWhenDurationBecomesKnown() =
        runTest {
            val processor = processor()
            var duration = -1L
            val seeks = mutableListOf<Long>()
            backgroundScope.launch {
                processor.skipWhenConfirmed({ true }, { 0L }, { duration }, seeks::add)
            }
            processor.process(pcm(2_000))
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            assertTrue(seeks.isEmpty())
            duration = 60_000L
            processor.signalSilence()
            runCurrent()
            advanceTimeBy(199)
            assertTrue(seeks.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(15_000L), seeks)
        }

    @Test
    fun disabledProcessorSharesPcmAndCanEnableWithoutAFlush() =
        runTest {
            val processor = processor().apply { enabled = false }
            val seeks = mutableListOf<Long>()
            backgroundScope.launch {
                processor.skipWhenConfirmed({ true }, { 0L }, { 60_000L }, seeks::add)
            }
            val input = pcm(3_000)
            processor.queueInput(input)
            assertEquals(0, input.position())
            val output = processor.output
            input.putShort(0, 123)
            assertEquals(123.toShort(), output.getShort(0))
            output.position(output.limit())
            processor.queueInput(input)
            assertFalse(input.hasRemaining())
            runCurrent()
            advanceTimeBy(1_000)
            assertTrue(seeks.isEmpty())
            processor.enabled = true
            processor.process(pcm(1_999))
            runCurrent()
            advanceTimeBy(1_000)
            assertTrue(seeks.isEmpty())
            processor.process(pcm(1))
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            assertEquals(listOf(15_000L), seeks)
        }

    @Test
    fun pipelineBackpressurePreventsUpstreamReuseUntilPassThroughIsDrained() {
        val detector = processor().apply { enabled = false }
        val pipeline =
            AudioProcessingPipeline(
                ImmutableList.of(NormalizationAudioProcessor { 1f }, detector),
            )
        pipeline.configure(AudioProcessor.AudioFormat(1_000, 2, C.ENCODING_PCM_16BIT))
        pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val first = pcm(10, 123)
        pipeline.queueInput(first)
        val output = pipeline.output
        assertEquals(40, output.remaining())
        output.position(4)
        detector.enabled = true
        val second = pcm(10, 456)
        pipeline.queueInput(second)
        assertEquals(0, second.position())
        assertSame(output, pipeline.output)
        while (output.hasRemaining()) assertEquals(123.toShort(), output.short)
        pipeline.queueInput(second)
        pipeline.queueEndOfStream()
        assertFalse(pipeline.isEnded)
        val last = pipeline.output
        while (last.hasRemaining()) assertEquals(456.toShort(), last.short)
        pipeline.output
        assertTrue(pipeline.isEnded)
        pipeline.reset()
    }

    @Test
    fun upstreamBufferReuseStillRunsTheDetectorAfterEnabling() =
        runTest {
            val detector = processor().apply { enabled = false }
            val pipeline =
                AudioProcessingPipeline(
                    ImmutableList.of(NormalizationAudioProcessor { 1f }, detector),
                )
            pipeline.configure(AudioProcessor.AudioFormat(1_000, 2, C.ENCODING_PCM_16BIT))
            pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
            val seeks = mutableListOf<Long>()
            backgroundScope.launch {
                detector.skipWhenConfirmed({ true }, { 0L }, { 60_000L }, seeks::add)
            }
            repeat(3) { index ->
                if (index == 1) detector.enabled = true
                pipeline.queueInput(pcm(1_000))
                val output = pipeline.output
                assertEquals(4_000, output.remaining())
                output.position(output.limit())
            }
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            assertEquals(listOf(15_000L), seeks)
            pipeline.reset()
        }

    @Test
    fun flushAndResetDiscardPendingPassThroughAndEndOfStreamWaitsForOutput() {
        val processor = processor().apply { enabled = false }
        processor.queueInput(pcm(1))
        processor.queueEndOfStream()
        assertFalse(processor.isEnded)
        val output = processor.output
        assertEquals(4, output.remaining())
        assertFalse(processor.isEnded)
        output.position(output.limit())
        assertTrue(processor.isEnded)
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.queueInput(pcm(1))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertFalse(processor.output.hasRemaining())
        processor.queueInput(pcm(1))
        processor.reset()
        assertFalse(processor.output.hasRemaining())
        assertFalse(processor.isActive)
    }

    private fun processor() =
        SilenceDetectorAudioProcessor().apply {
            configure(this)
            enabled = true
        }

    private fun configure(processor: SilenceDetectorAudioProcessor) {
        processor.configure(AudioProcessor.AudioFormat(1_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    private fun pcm(
        frames: Int,
        sample: Int = 0,
    ): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(frames * 2) { putShort(sample.toShort()) }
            flip()
        }

    private fun SilenceDetectorAudioProcessor.process(input: ByteBuffer) {
        val expected = input.remaining()
        queueInput(input)
        val output = output
        assertEquals(expected, output.remaining())
        output.position(output.limit())
        queueInput(input)
        assertFalse(input.hasRemaining())
    }
}
