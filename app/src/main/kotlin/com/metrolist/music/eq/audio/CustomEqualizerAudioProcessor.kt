package com.metrolist.music.eq.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.metrolist.music.eq.data.ParametricEQ
import com.metrolist.music.eq.data.ParametricEQBand
import timber.log.Timber
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * Applies AutoEQ parametric profiles with biquad filters.
 */
@UnstableApi
class CustomEqualizerAudioProcessor : BaseAudioProcessor() {
    private var sampleRate = 0
    private var channelCount = 0
    private var equalizerEnabled = false
    private var filters: List<BiquadFilter> = emptyList()
    private var preampGain = 1.0
    private var pendingProfile: ParametricEQ? = null

    @Synchronized
    fun applyProfile(parametricEQ: ParametricEQ) {
        if (sampleRate == 0) {
            Timber.tag(TAG)
                .d("Audio processor not configured yet. Storing profile as pending with ${parametricEQ.bands.size} bands")
            pendingProfile = parametricEQ
            return
        }

        preampGain = 10.0.pow(parametricEQ.preamp / 20.0)
        createFilters(parametricEQ.bands)
        equalizerEnabled = true
        filters.forEach { it.reset() }

        Timber.tag(TAG)
            .d("Applied EQ profile with ${filters.size} bands and ${parametricEQ.preamp} dB preamp")
    }

    @Synchronized
    fun disable() {
        equalizerEnabled = false
        filters = emptyList()
        preampGain = 1.0
        pendingProfile = null
        Timber.tag(TAG).d("Equalizer disabled")
    }

    fun isEnabled(): Boolean = equalizerEnabled

    private fun createFilters(bands: List<ParametricEQBand>) {
        filters =
            bands
                .filter { it.enabled && it.frequency < sampleRate / 2.0 }
                .map { band ->
                    BiquadFilter(
                        sampleRate = sampleRate,
                        frequency = band.frequency,
                        gain = band.gain,
                        q = band.q,
                        filterType = band.filterType,
                    )
                }

        Timber.tag(TAG)
            .d("Created ${filters.size} biquad filters from ${bands.size} bands (PK/LSC/HSC)")
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount > 2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        Timber.tag(TAG).d(
            "Configured: sampleRate=$sampleRate, channels=$channelCount, encoding=${inputAudioFormat.encoding}",
        )

        pendingProfile?.let { profile ->
            preampGain = 10.0.pow(profile.preamp / 20.0)
            createFilters(profile.bands)
            equalizerEnabled = true
            pendingProfile = null
            Timber.tag(TAG)
                .d("Applied pending profile with ${filters.size} bands and ${profile.preamp} dB preamp")
        }

        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (!equalizerEnabled || filters.isEmpty()) {
            output.put(inputBuffer)
        } else {
            processAudioBuffer(inputBuffer, output)
        }
        output.flip()
    }

    private fun processAudioBuffer(input: ByteBuffer, output: ByteBuffer) {
        repeat(input.remaining() / 2 / channelCount) {
            when (channelCount) {
                1 -> {
                    var processed = input.getShort().toDouble() / 32768.0
                    filters.forEach { processed = it.processSample(processed) }
                    processed *= preampGain
                    output.putShort(
                        (processed * 32768.0)
                            .coerceIn(-32768.0, 32767.0)
                            .toInt()
                            .toShort(),
                    )
                }

                2 -> {
                    var left = input.getShort().toDouble() / 32768.0
                    var right = input.getShort().toDouble() / 32768.0
                    filters.forEach { filter ->
                        filter.processStereo(left, right).let {
                            left = it.first
                            right = it.second
                        }
                    }
                    output.putShort(
                        (left * preampGain * 32768.0)
                            .coerceIn(-32768.0, 32767.0)
                            .toInt()
                            .toShort(),
                    )
                    output.putShort(
                        (right * preampGain * 32768.0)
                            .coerceIn(-32768.0, 32767.0)
                            .toInt()
                            .toShort(),
                    )
                }
            }
        }
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        filters.forEach { it.reset() }
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        filters.forEach { it.reset() }
    }

    private companion object {
        const val TAG = "CustomEqualizerAudioProcessor"
    }
}
