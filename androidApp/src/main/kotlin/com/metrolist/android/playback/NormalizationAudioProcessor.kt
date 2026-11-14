package com.metrolist.android.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
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
        set(value) {
            if (field === value) return
            field = value
            refreshGain()
        }

    @Volatile
    var normalizationGain: Float = 1f
        private set

    fun refreshGain() {
        normalizationGain = gain(resource)
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
        val boost = normalizationGain.coerceAtLeast(1f)
        if (boost == 1f) {
            output.put(inputBuffer)
        } else {
            while (inputBuffer.hasRemaining()) {
                output.putShort(normalizationBoostPcm16(inputBuffer.short, boost))
            }
        }
        output.flip()
    }
}
