package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * An audio processor that applies volume normalization (gain adjustment) to audio samples.
 *
 * This processor supports multiple PCM audio formats (16-bit, 24-bit, 32-bit, and float) and
 * applies a linear gain multiplier to normalize audio levels. The gain can be toggled on/off
 * and adjusted dynamically via [setTargetGain].
 *
 * Gain values are specified in millibels (mB) and converted to linear multipliers using the
 * formula: linearGain = 10^(gainMb / 2000).
 *
 * Thread-safety: The [enabled] flag and [currentGain] are volatile for safe cross-thread access,
 * while [setTargetGain] is synchronized to ensure atomic updates.
 */
@UnstableApi
@Suppress("DEPRECATION")
class VolumeNormalizationAudioProcessor : AudioProcessor {

    // Audio format configuration properties
    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0
    private var bytesPerFrame = 0
    private var active = false

    /**
     * Flag to control whether gain normalization is currently applied.
     * When true, the target gain is applied to audio samples; when false, samples pass through unchanged.
     */
    @Volatile
    var enabled = false
        set(value) {
            if (field != value) {
                field = value
                Timber.tag(TAG).d("Normalization processor enabled: $value")
            }
        }

    // Buffer management
    private var buffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    /**
     * Encapsulates the current gain state.
     *
     * @param targetGainMb The target gain in millibels (mB)
     * @param linearGain The pre-calculated linear gain multiplier for efficient sample processing
     */
    private data class GainState(val targetGainMb: Int, val linearGain: Double)

    /**
     * The current gain state. Marked volatile for safe concurrent access from multiple threads.
     */
    @Volatile
    private var currentGain: GainState = GainState(0, 1.0)

    companion object {
        private const val TAG = "VolumeNormalizationProcessor"
        private val EMPTY_BUFFER: ByteBuffer =
            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    /**
     * Sets the target gain for normalization.
     *
     * The gain value is specified in millibels (mB). For example:
     * - 0 mB = no change (linear gain of 1.0)
     * - 1000 mB = 10x amplification
     * - -1000 mB = 0.1x attenuation (10% of original)
     *
     * The method only updates the gain if the new value differs from the current target,
     * minimizing unnecessary recalculations.
     *
     * @param gainMb The target gain in millibels. The linear gain is calculated as 10^(gainMb / 2000)
     */
    @Synchronized
    fun setTargetGain(gainMb: Int) {
        if (currentGain.targetGainMb != gainMb) {
            val linearGain = 10.0.pow(gainMb / 2000.0)
            currentGain = GainState(gainMb, linearGain)
            Timber.tag(TAG).d("Target gain set to $gainMb mB (Linear multiplier: $linearGain)")
        }
    }

    /**
     * Configures the processor for a specific audio format.
     *
     * This method is called before any audio processing begins and sets up the processor
     * with the sample rate, channel count, and encoding format of the input audio.
     * The bytes-per-sample and bytes-per-frame are calculated based on the encoding.
     *
     * @param inputAudioFormat The input audio format configuration
     * @return The output audio format (typically unchanged from input, as only gain is applied)
     * @throws AudioProcessor.UnhandledAudioFormatException if the encoding is not supported
     */
    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding

        // Determine bytes per sample based on encoding format
        bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT -> 4
            C.ENCODING_PCM_FLOAT -> 4
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        // Calculate total bytes needed for a single audio frame (all channels)
        bytesPerFrame = bytesPerSample * channelCount
        active = true

        Timber.tag(TAG).d(
            "Configured: sampleRate=$sampleRate, channels=$channelCount, encoding=$encoding"
        )

        return AudioProcessor.AudioFormat(sampleRate, channelCount, encoding)
    }

    /**
     * Returns whether this processor is currently active.
     *
     * @return true if the processor has been configured and is active, false otherwise
     */
    override fun isActive(): Boolean = active

    /**
     * Processes a buffer of audio samples, applying gain normalization if enabled.
     *
     * This method handles multiple PCM audio formats by reading samples, applying the gain
     * multiplier (if enabled), and clipping to the valid range for the format, then writing
     * to the output buffer. Each format has its own processing logic due to different sample sizes
     * and value ranges.
     *
     * @param inputBuffer The input buffer containing raw audio samples
     */
    override fun queueInput(inputBuffer: ByteBuffer) {
        val gain = currentGain
        val applyGain = enabled && gain.targetGainMb != 0

        val inputSize = inputBuffer.remaining()
        if (inputSize == 0) {
            outputBuffer = EMPTY_BUFFER
            return
        }

        val outputSize = inputSize
        val out = replaceOutputBuffer(outputSize)

        // Ensure consistent byte ordering for sample processing
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)

        val frameCount = inputSize / bytesPerFrame

        // Process samples based on encoding format
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                repeat(frameCount * channelCount) {
                    val sample = inputBuffer.getShort().toInt()
                    // Apply gain and clamp to 16-bit signed range [-32768, 32767]
                    val processed = if (applyGain) {
                        (sample * gain.linearGain)
                            .coerceIn(-32768.0, 32767.0)
                            .toInt()
                    } else {
                        sample
                    }
                    out.putShort(processed.toShort())
                }
            }

            C.ENCODING_PCM_24BIT -> {
                repeat(frameCount * channelCount) {
                    // Read 24-bit sample as 3 individual bytes
                    val b0 = inputBuffer.get().toInt() and 0xFF
                    val b1 = inputBuffer.get().toInt() and 0xFF
                    val b2 = inputBuffer.get().toInt()
                    // Reconstruct 24-bit sample from bytes
                    val sample = (b2 shl 16) or (b1 shl 8) or b0
                    // Sign-extend from 24-bit to 32-bit
                    val signed = (sample shl 8) shr 8
                    // Apply gain and clamp to 24-bit signed range [-8388608, 8388607]
                    val processed = if (applyGain) {
                        (signed * gain.linearGain)
                            .coerceIn(-8388608.0, 8388607.0)
                            .toInt()
                    } else {
                        signed
                    }
                    // Write 24-bit sample back as 3 bytes
                    out.put((processed and 0xFF).toByte())
                    out.put(((processed shr 8) and 0xFF).toByte())
                    out.put(((processed shr 16) and 0xFF).toByte())
                }
            }

            C.ENCODING_PCM_32BIT -> {
                repeat(frameCount * channelCount) {
                    val sample = inputBuffer.getInt()
                    // Apply gain and clamp to 32-bit signed range [-2147483648, 2147483647]
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
                repeat(frameCount * channelCount) {
                    val sample = inputBuffer.getFloat()
                    // Apply gain and clamp to float range [-1.0f, 1.0f]
                    val processed = if (applyGain) {
                        (sample * gain.linearGain.toFloat()).coerceIn(-1.0f, 1.0f)
                    } else {
                        sample
                    }
                    out.putFloat(processed)
                }
            }

            else -> throw AudioProcessor.UnhandledAudioFormatException(
                AudioProcessor.AudioFormat(sampleRate, channelCount, encoding)
            )
        }

        // Mark input buffer as fully consumed
        inputBuffer.position(inputBuffer.limit())
        out.flip()
        outputBuffer = out
    }

    /**
     * Signals that no more input will be queued.
     *
     * This method is called when the audio stream has ended, allowing the processor
     * to finalize any pending operations.
     */
    override fun queueEndOfStream() {
        inputEnded = true
    }

    /**
     * Retrieves the processed output buffer.
     *
     * The output buffer is cleared after retrieval to prevent reuse of stale data.
     *
     * @return The buffer containing processed audio samples, or an empty buffer if no output is available
     */
    override fun getOutput(): ByteBuffer {
        val buffer = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return buffer
    }

    /**
     * Returns whether the processor has finished processing all input.
     *
     * @return true if all input has been processed and no output remains in the buffer, false otherwise
     */
    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

    /**
     * Flushes any pending output and resets the stream end flag.
     *
     * This method is deprecated in the AudioProcessor interface but is retained for compatibility.
     */
    @Deprecated("Deprecated in AudioProcessor")
    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
    }

    /**
     * Resets the processor to its initial state.
     *
     * Clears all buffers and resets configuration. This method is deprecated in the
     * AudioProcessor interface but is retained for compatibility. After reset, [configure]
     * must be called again before processing new audio.
     */
    @Deprecated("Deprecated in AudioProcessor")
    override fun reset() {
        flush()
        buffer = EMPTY_BUFFER
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_INVALID
        bytesPerSample = 0
        bytesPerFrame = 0
        active = false
    }

    /**
     * Ensures the output buffer has sufficient capacity, reusing or allocating as needed.
     *
     * This method optimizes memory allocation by reusing the internal buffer if it has
     * sufficient capacity, otherwise allocating a new direct buffer. This reduces garbage
     * collection pressure during continuous audio processing.
     *
     * @param size The required buffer size in bytes
     * @return A ByteBuffer with at least the specified capacity, ready for writing
     */
    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }
        return buffer
    }
}
