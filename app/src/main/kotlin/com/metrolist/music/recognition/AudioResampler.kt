package com.metrolist.music.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Data class representing decoded audio data with its properties.
 */
data class DecodedAudio(
    val data: ByteArray,
    val channelCount: Int,
    val sampleRate: Int,
    val pcmEncoding: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DecodedAudio
        return data.contentEquals(other.data) &&
                channelCount == other.channelCount &&
                sampleRate == other.sampleRate &&
                pcmEncoding == other.pcmEncoding
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + channelCount
        result = 31 * result + sampleRate
        result = 31 * result + pcmEncoding
        return result
    }
}

/**
 * Audio resampler using linear interpolation.
 * Resamples mono 16-bit PCM audio to the required sample rate for fingerprinting.
 */
object AudioResampler {
    private const val TAG = "AudioResampler"

    suspend fun resample(
        decodedAudio: DecodedAudio,
        outputSampleRate: Int
    ): Result<DecodedAudio> = withContext(Dispatchers.Default) {
        if (decodedAudio.sampleRate == outputSampleRate) {
            Timber.tag(TAG).d("Sample rate already matches (%dHz), skipping resample", outputSampleRate)
            return@withContext Result.success(decodedAudio)
        }

        try {
            Timber.tag(TAG).d("Resampling: %dHz → %dHz, %d bytes input", decodedAudio.sampleRate, outputSampleRate, decodedAudio.data.size)

            val inputSamples = shortArrayFromByteArray(decodedAudio.data)
            val ratio = outputSampleRate.toDouble() / decodedAudio.sampleRate
            val outputLength = (inputSamples.size * ratio).toInt()
            val outputSamples = ShortArray(outputLength)

            for (i in 0 until outputLength) {
                ensureActive()
                val srcPos = i / ratio
                val srcIndex = srcPos.toInt()
                val fraction = srcPos - srcIndex
                val sample = if (srcIndex + 1 < inputSamples.size) {
                    (inputSamples[srcIndex] * (1.0 - fraction) + inputSamples[srcIndex + 1] * fraction).toInt().toShort()
                } else {
                    inputSamples[srcIndex]
                }
                outputSamples[i] = sample
            }

            val resampledData = byteArrayFromShortArray(outputSamples)
            Timber.tag(TAG).d("Resampling complete: %d bytes output, %dHz, %d channels", resampledData.size, outputSampleRate, decodedAudio.channelCount)

            Result.success(DecodedAudio(
                data = resampledData,
                channelCount = decodedAudio.channelCount,
                sampleRate = outputSampleRate,
                pcmEncoding = decodedAudio.pcmEncoding,
            ))
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Audio resampling failed")
            ensureActive()
            Result.failure(e)
        }
    }

    private fun shortArrayFromByteArray(data: ByteArray): ShortArray {
        val shorts = ShortArray(data.size / 2)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private fun byteArrayFromShortArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts)
        return bytes
    }
}
