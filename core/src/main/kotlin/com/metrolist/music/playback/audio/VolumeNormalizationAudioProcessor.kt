package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

@UnstableApi
@Suppress("DEPRECATION")
class VolumeNormalizationAudioProcessor : AudioProcessor {

    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0
    private var isActive = false

    @Volatile
    var enabled = false
        set(value) {
            if (field != value) {
                field = value
                Timber.tag(TAG).d("Normalization processor enabled: $value")
            }
        }

    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    private data class GainState(val targetGainMb: Int, val linearGain: Double)

    @Volatile
    private var currentGain: GainState = GainState(0, 1.0)

    companion object {
        private const val TAG = "VolumeNormalizationProcessor"
        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    @Synchronized
    fun setTargetGain(gainMb: Int) {
        if (currentGain.targetGainMb != gainMb) {
            val linearGain = 10.0.pow(gainMb / 2000.0)
            currentGain = GainState(gainMb, linearGain)
            Timber.tag(TAG).d("Target gain set to $gainMb mB (Linear multiplier: $linearGain)")
        }
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding

        bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT -> 4
            C.ENCODING_PCM_FLOAT -> 4
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        Timber.tag(TAG).d("Configured: sampleRate=$sampleRate, channels=$channelCount, encoding=$encoding")

        isActive = true
        return AudioProcessor.AudioFormat(sampleRate, channelCount, encoding)
    }

    override fun isActive(): Boolean = isActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        val gain = currentGain
        val applyGain = enabled && gain.targetGainMb != 0

        val inputSize = inputBuffer.remaining()
        if (inputSize == 0) return

        val sampleCount = inputSize / bytesPerSample
        val out = replaceOutputBuffer(sampleCount * bytesPerSample)

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)

        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                repeat(sampleCount) {
                    val sample = inputBuffer.getShort()
                    val processed = if (applyGain) {
                        (sample * gain.linearGain)
                            .coerceIn(-32768.0, 32767.0)
                            .toInt()
                            .toShort()
                    } else {
                        sample
                    }
                    out.putShort(processed)
                }
            }

            C.ENCODING_PCM_24BIT -> {
                repeat(sampleCount) {
                    val b0 = inputBuffer.get().toInt() and 0xFF
                    val b1 = inputBuffer.get().toInt() and 0xFF
                    val b2 = inputBuffer.get().toInt()
                    val sample = (b2 shl 16) or (b1 shl 8) or b0

                    val processed = if (applyGain) {
                        (sample * gain.linearGain)
                            .coerceIn(-8388608.0, 8388607.0)
                            .toInt()
                    } else {
                        sample
                    }
                    out.put((processed and 0xFF).toByte())
                    out.put(((processed shr 8) and 0xFF).toByte())
                    out.put(((processed shr 16) and 0xFF).toByte())
                }
            }

            C.ENCODING_PCM_32BIT -> {
                repeat(sampleCount) {
                    val sample = inputBuffer.getInt()
                    val processed = if (applyGain) {
                        (sample * gain.linearGain)
                            .coerceIn(-2147483648.0, 2147483647.0)
                            .toLong()
                            .toInt()
                    } else {
                        sample
                    }
                    out.putInt(processed)
                }
            }

            C.ENCODING_PCM_FLOAT -> {
                repeat(sampleCount) {
                    val sample = inputBuffer.getFloat()
                    val processed = if (applyGain) {
                        (sample * gain.linearGain.toFloat()).coerceIn(-1.0f, 1.0f)
                    } else {
                        sample
                    }
                    out.putFloat(processed)
                }
            }
        }

        out.flip()
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val buffer = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return buffer
    }

    override fun isEnded(): Boolean {
        return inputEnded && outputBuffer === EMPTY_BUFFER
    }

    @Deprecated("Deprecated in AudioProcessor")
    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
    }

    @Deprecated("Deprecated in AudioProcessor")
    override fun reset() {
        flush()
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_INVALID
        bytesPerSample = 0
        isActive = false
        // DO NOT reset enabled or currentGain, as they are controlled by the service
    }

    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        return outputBuffer
    }

    private fun read24Bit(buffer: ByteBuffer): Int {
        val b0 = buffer.get().toInt() and 0xFF
        val b1 = buffer.get().toInt() and 0xFF
        val b2 = buffer.get().toInt()
        return (b2 shl 16) or (b1 shl 8) or b0
    }
}
