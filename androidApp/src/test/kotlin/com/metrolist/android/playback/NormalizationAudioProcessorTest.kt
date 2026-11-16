package com.metrolist.android.playback

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import com.metrolist.shared.playback.playbackNormalizationGain
import com.metrolist.shared.youtube.playback.PlaybackResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class NormalizationAudioProcessorTest {
    @Test
    fun queuedAttenuationFollowsTheStreamBeforeQueuePromotionWithoutApplyingItTwice() {
        val outgoing = PlaybackResource("outgoing", "file:///outgoing", emptyMap(), null, null)
        val incoming = outgoing.copy(videoId = "incoming", url = "file:///incoming")
        val processor = NormalizationAudioProcessor { if (it?.videoId == incoming.videoId) 0.5f else 0.25f }
        processor.resource = outgoing
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(streamMetadata(outgoing))
        processor.queueInput(
            ByteBuffer
                .allocateDirect(4)
                .order(ByteOrder.nativeOrder())
                .putShort(0, 1000)
                .putShort(2, -1000),
        )
        assertEquals(250.toShort(), processor.output.getShort(0))

        processor.flush(streamMetadata(incoming))
        processor.queueInput(
            ByteBuffer
                .allocateDirect(4)
                .order(ByteOrder.nativeOrder())
                .putShort(0, 1000)
                .putShort(2, -1000),
        )
        val output = processor.output
        assertEquals(500.toShort(), output.getShort(0))
        assertEquals((-500).toShort(), output.getShort(2))
        assertEquals(0.25f, processor.normalizationGain)

        processor.resource = incoming
        processor.queueInput(ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).putShort(0, 1000))
        assertEquals(500.toShort(), processor.output.getShort(0))
    }

    @Test(timeout = 15_000)
    fun disablingNormalizationCannotBeOverwrittenByAnInFlightPlaybackRefresh() {
        val enabled = AtomicBoolean(true)
        val blockNextGain = AtomicBoolean(false)
        val gainEntered = CountDownLatch(1)
        val releaseGain = CountDownLatch(1)
        val applicationRefreshStarted = CountDownLatch(1)
        val applicationThread = AtomicReference<Thread>()
        val processor =
            NormalizationAudioProcessor {
                val value = if (enabled.get()) 2f else 1f
                if (blockNextGain.getAndSet(false)) {
                    gainEntered.countDown()
                    check(releaseGain.await(5, TimeUnit.SECONDS))
                }
                value
            }
        processor.resource = PlaybackResource("outgoing", "file:///outgoing", emptyMap(), null, null)
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        val metadata = streamMetadata(processor.resource!!.copy(videoId = "incoming"))
        val workers = Executors.newFixedThreadPool(2)
        try {
            blockNextGain.set(true)
            val playbackRefresh = workers.submit { processor.flush(metadata) }
            assertTrue(gainEntered.await(5, TimeUnit.SECONDS))
            enabled.set(false)
            val applicationRefresh =
                workers.submit {
                    applicationThread.set(Thread.currentThread())
                    applicationRefreshStarted.countDown()
                    processor.refreshGain()
                }
            assertTrue(applicationRefreshStarted.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (applicationThread.get().state != Thread.State.BLOCKED && !applicationRefresh.isDone && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, applicationThread.get().state)
            releaseGain.countDown()
            playbackRefresh.get(5, TimeUnit.SECONDS)
            applicationRefresh.get(5, TimeUnit.SECONDS)

            processor.flush(metadata)
            processor.queueInput(ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).putShort(0, 1000))
            assertEquals(1f, processor.normalizationGain)
            assertEquals(1000.toShort(), processor.output.getShort(0))
        } finally {
            releaseGain.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun queuedPcmUsesItsOwnNormalizationBeforeTheCurrentSongChanges() {
        val outgoing = PlaybackResource("outgoing", "file:///outgoing", emptyMap(), null, null)
        val incoming = outgoing.copy(videoId = "incoming", url = "file:///incoming")
        val processor = NormalizationAudioProcessor { if (it?.videoId == incoming.videoId) 2f else 1f }
        processor.resource = outgoing
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        val timeline =
            SinglePeriodTimeline(
                20_000_000L,
                true,
                false,
                false,
                null,
                MediaItem
                    .Builder()
                    .setUri(incoming.url)
                    .setTag(incoming)
                    .build(),
            )
        processor.flush(
            AudioProcessor.StreamMetadata
                .Builder()
                .setTimeline(timeline)
                .setPeriodUid(timeline.getUidOfPeriod(0))
                .build(),
        )

        processor.queueInput(ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).putShort(0, 1000))
        assertEquals(2000.toShort(), processor.output.getShort(0))
        assertEquals(1f, processor.normalizationGain)

        processor.resource = incoming
        processor.queueInput(ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).putShort(0, 1000))
        assertEquals(2000.toShort(), processor.output.getShort(0))
    }

    @Test
    fun playersUseTheirOwnMetadataAndLivePreferencesWithoutApplyingAttenuationTwice() {
        var enabled = true
        var calculations = 0

        fun processor(lufs: Double) =
            NormalizationAudioProcessor {
                calculations++
                playbackNormalizationGain(it?.loudnessDb, enabled, perceptualLoudnessDb = it?.perceptualLoudnessDb)
            }.apply {
                resource = PlaybackResource("song", "file:///song", emptyMap(), 0.0, null, perceptualLoudnessDb = lufs)
                configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
                flush(AudioProcessor.StreamMetadata.DEFAULT)
            }

        fun process(
            processor: NormalizationAudioProcessor,
            sample: Short,
        ): Short {
            processor.queueInput(ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).putShort(0, sample))
            return processor.output.getShort(0)
        }
        val outgoing = processor(-8.0)
        val incoming = processor(-17.0)
        assertEquals(501.toShort(), process(outgoing, 1000))
        assertEquals(1412.toShort(), process(incoming, 1000))
        assertTrue(process(incoming, Short.MAX_VALUE) in 30_000..Short.MAX_VALUE)
        assertTrue(process(incoming, 30_000) < process(incoming, Short.MAX_VALUE))
        incoming.resource = incoming.resource
        assertEquals(2, calculations)
        enabled = false
        incoming.refreshGain()
        assertEquals(1000.toShort(), process(incoming, 1000))
        enabled = true
        incoming.resource = incoming.resource?.copy(perceptualLoudnessDb = -16.0)
        assertEquals(1258.toShort(), process(incoming, 1000))
        incoming.resource = null
        assertEquals(1000.toShort(), process(incoming, 1000))
    }

    @Test
    fun emptyInputBeforeAudioDoesNotCopyTheSharedEmptyBufferIntoItself() {
        val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
        val processors =
            listOf(
                NormalizationAudioProcessor { 2f },
                SilenceDetectorAudioProcessor().apply { enabled = true },
            )
        processors.forEach { processor ->
            processor.configure(format)
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertEquals(0, processor.output.remaining())
            processor.queueInput(ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()))
            assertEquals(4, processor.output.remaining())
        }
    }

    private fun streamMetadata(resource: PlaybackResource): AudioProcessor.StreamMetadata {
        val item =
            MediaItem
                .Builder()
                .setUri(resource.url)
                .setTag(resource)
                .build()
        val timeline = SinglePeriodTimeline(20_000_000L, true, false, false, null, item)
        return AudioProcessor.StreamMetadata
            .Builder()
            .setTimeline(timeline)
            .setPeriodUid(timeline.getUidOfPeriod(0))
            .build()
    }
}
