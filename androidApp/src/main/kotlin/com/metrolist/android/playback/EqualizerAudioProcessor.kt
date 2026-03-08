package com.metrolist.android.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.metrolist.shared.equalizer.AudioSpectrumMeter
import com.metrolist.shared.equalizer.EqualizerConfiguration
import com.metrolist.shared.equalizer.EqualizerProfile
import com.metrolist.shared.equalizer.EqualizerProfiles
import com.metrolist.shared.equalizer.MAX_AUDIO_BOOST_DB
import com.metrolist.shared.equalizer.MIN_AUDIO_PREAMP_DB
import com.metrolist.shared.equalizer.ParametricEqualizer
import java.nio.ByteBuffer

/** Bypasses non-16-bit PCM to avoid failing playback on unsupported formats. */
@OptIn(UnstableApi::class)
internal class EqualizerAudioProcessor(
    private val configuration: () -> EqualizerConfiguration = { EqualizerProfiles.configuration },
) : BaseAudioProcessor() {
    constructor(outputBoostDb: () -> Float, activeProfile: () -> EqualizerProfile?) :
        this({ EqualizerConfiguration(activeProfile(), outputBoostDb().toDouble()) })

    private var equalizer: ParametricEqualizer? = null
    private var samples = ShortArray(0)

    @Volatile private var configuredFormat = AudioProcessor.AudioFormat.NOT_SET

    @Volatile private var gainReductionDb = 0f

    fun diagnosticDetails(): Map<String, String> =
        mapOf(
            "eqSampleRate" to configuredFormat.sampleRate.toString(),
            "eqChannels" to configuredFormat.channelCount.toString(),
            "eqEncoding" to configuredFormat.encoding.toString(),
            "eqGainReductionDb" to gainReductionDb.toString(),
        )

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        configuredFormat = inputAudioFormat
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        equalizer = ParametricEqualizer(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val eq = equalizer
        val current = configuration()
        eq?.update(current.profile)
        val boostDb = current.preampDb.coerceIn(MIN_AUDIO_PREAMP_DB, MAX_AUDIO_BOOST_DB)
        val count = size / 2
        if (samples.size < count) samples = ShortArray(count)
        // Media3 PCM buffers use native byte order, which the short views inherit.
        inputBuffer.asShortBuffer().get(samples, 0, count)
        inputBuffer.position(inputBuffer.limit())
        if (eq != null) {
            eq.processPcm16(samples, count, boostDb)
        }
        gainReductionDb = eq?.gainReductionDb?.toFloat() ?: 0f
        AudioSpectrumMeter.accept(
            samples,
            count,
            inputAudioFormat.channelCount,
            inputAudioFormat.sampleRate,
            eq?.gainReductionDb?.toFloat() ?: 0f,
        )
        output.asShortBuffer().put(samples, 0, count)
        output.position(count * 2)
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        equalizer?.reset()
    }

    override fun onReset() {
        equalizer = null
        configuredFormat = AudioProcessor.AudioFormat.NOT_SET
        gainReductionDb = 0f
    }
}
