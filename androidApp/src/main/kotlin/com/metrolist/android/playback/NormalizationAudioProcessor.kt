package com.metrolist.android.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.metrolist.shared.playback.normalizationBoostPcm16
import com.metrolist.shared.youtube.playback.PlaybackResource
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
internal class NormalizationAudioProcessor(
    private val gain: (PlaybackResource?) -> Float,
) : BaseAudioProcessor() {
    var resource: PlaybackResource? = null
        @Synchronized set(value) {
            if (field === value) return
            field = value
            refreshGain()
        }

    @Volatile
    var normalizationGain: Float = 1f
        private set

    private var streamResource: PlaybackResource? = null

    @Volatile private var streamGain = 1f

    @Synchronized
    fun refreshGain() {
        normalizationGain = gain(resource)
        streamGain = if (streamResource == null || streamResource === resource) normalizationGain else gain(streamResource)
    }

    @Synchronized
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // The next item's PCM can reach the sink before the playlist transition reaches the app.
        val nextStreamResource =
            streamMetadata.periodUid?.takeUnless { streamMetadata.timeline.isEmpty }?.let { uid ->
                val windowIndex = streamMetadata.timeline.getPeriodByUid(uid, Timeline.Period()).windowIndex
                streamMetadata.timeline
                    .getWindow(windowIndex, Timeline.Window())
                    .mediaItem.localConfiguration
                    ?.tag as? PlaybackResource
            }
        if (streamResource !== nextStreamResource) {
            streamResource = nextStreamResource
            refreshGain()
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Bypass non-PCM16 output instead of failing sink configuration.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // An empty replaceOutputBuffer() can return the shared EMPTY_BUFFER that is also our input.
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        val gain = streamGain
        if (gain == 1f) {
            output.put(inputBuffer)
        } else {
            while (inputBuffer.hasRemaining()) {
                val sample = inputBuffer.short
                output.putShort(if (gain < 1f) (sample * gain).toInt().toShort() else normalizationBoostPcm16(sample, gain))
            }
        }
        output.flip()
    }
}
