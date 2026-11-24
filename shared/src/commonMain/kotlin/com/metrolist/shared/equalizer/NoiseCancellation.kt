/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.equalizer

import com.metrolist.shared.settings.Settings
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

/** Microphone samples never leave the device: implementations may only hand them to [NoiseCancellation]. */
interface NoiseMicrophone {
    val isSupported: Boolean

    fun hasPermission(): Boolean

    fun requestPermission()

    /** [onSamples] receives the buffer, the frame count and the actual capture rate. */
    fun start(
        sampleRate: Int,
        onSamples: (ShortArray, Int, Int) -> Unit,
    ): Boolean

    fun stop()
}

enum class NoiseCancellationStatus {
    OFF,
    STARTING,
    ACTIVE,
    PERMISSION_REQUIRED,
    UNAVAILABLE,
    ERROR,
}

private const val MICROPHONE_RING_FRAMES = 16_384

/** Single writer, many readers. Lagging readers skip old frames instead of blocking the microphone thread. */
internal class MicrophoneRing {
    private val samples = ShortArray(MICROPHONE_RING_FRAMES)
    private val writeSequence = atomic(0L)

    @Volatile var sampleRate = NoiseCancellation.MICROPHONE_SAMPLE_RATE
        private set

    fun publish(
        block: ShortArray,
        count: Int,
        rate: Int,
    ) {
        if (count <= 0 || rate <= 0) return
        sampleRate = rate
        var offset = 0
        while (offset < count) {
            val sequence = writeSequence.value
            val chunk = min(count - offset, MICROPHONE_RING_FRAMES)
            for (index in 0 until chunk) {
                samples[(sequence + index).toInt() and (MICROPHONE_RING_FRAMES - 1)] = block[offset + index]
            }
            writeSequence.value = sequence + chunk
            offset += chunk
        }
    }

    fun sampleAt(sequence: Long): Short = samples[sequence.toInt() and (MICROPHONE_RING_FRAMES - 1)]

    val latestSequence: Long get() = writeSequence.value
}

object NoiseCancellation {
    const val MICROPHONE_SAMPLE_RATE: Int = 48_000

    private val ring = MicrophoneRing()
    private val generation = atomic(0)
    private val _status = MutableStateFlow(NoiseCancellationStatus.OFF)
    val status = _status.asStateFlow()

    @Volatile private var microphone: NoiseMicrophone? = null

    @Volatile var isEnabled: Boolean = Settings.shared.noiseCancellationEnabled
        private set

    /** Normalised 0..1 mix of the anti-noise signal. */
    @Volatile var strength: Float = Settings.shared.noiseCancellationStrength
        private set

    val isActive: Boolean
        get() = isEnabled && _status.value == NoiseCancellationStatus.ACTIVE

    /** A platform registers its microphone implementation once; samples stay inside this process. */
    fun register(microphone: NoiseMicrophone) {
        this.microphone = microphone
        refreshStatus()
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabled) return
        isEnabled = enabled
        Settings.shared.noiseCancellationEnabled = enabled
        if (enabled) {
            startCapture()
        } else {
            stopCapture()
        }
    }

    fun setStrength(value: Float) {
        val bounded = value.coerceIn(0f, 1f)
        strength = bounded
        Settings.shared.noiseCancellationStrength = bounded
    }

    fun requestPermission() {
        microphone?.requestPermission()
    }

    /** Called when a permission result arrives or the app resumes. */
    fun refreshPermission() {
        refreshStatus()
    }

    fun refreshStatus() {
        val source = microphone
        when {
            !isEnabled -> {
                ensureCaptureStopped()
                _status.value = NoiseCancellationStatus.OFF
            }

            source == null || !source.isSupported -> {
                ensureCaptureStopped()
                _status.value = NoiseCancellationStatus.UNAVAILABLE
            }

            !source.hasPermission() -> {
                ensureCaptureStopped()
                _status.value = NoiseCancellationStatus.PERMISSION_REQUIRED
            }

            !isActive -> {
                startCapture()
            }
        }
    }

    private fun startCapture() {
        val source = microphone
        when {
            !isEnabled -> {
                _status.value = NoiseCancellationStatus.OFF
            }

            source == null || !source.isSupported -> {
                _status.value = NoiseCancellationStatus.UNAVAILABLE
            }

            !source.hasPermission() -> {
                stopCapture()
                _status.value = NoiseCancellationStatus.PERMISSION_REQUIRED
            }

            else -> {
                _status.value = NoiseCancellationStatus.STARTING
                val started =
                    source.start(MICROPHONE_SAMPLE_RATE) { samples, count, rate ->
                        ring.publish(samples, count, rate)
                    }
                generation.incrementAndGet()
                _status.value = if (started) NoiseCancellationStatus.ACTIVE else NoiseCancellationStatus.ERROR
            }
        }
    }

    private fun stopCapture() {
        ensureCaptureStopped()
        _status.value = NoiseCancellationStatus.OFF
    }

    private fun ensureCaptureStopped() {
        if (_status.value == NoiseCancellationStatus.OFF) return
        microphone?.stop()
        generation.incrementAndGet()
        _status.value = NoiseCancellationStatus.OFF
    }

    /** Each player owns a cursor so crossfading players can read the same local microphone stream. */
    fun newReferenceReader(): NoiseReferenceReader = NoiseReferenceReader(ring, generation.value)

    internal fun currentGeneration(): Int = generation.value
}

/** Converts the shared microphone stream to a player's rate without allocating in the audio callback. */
class NoiseReferenceReader internal constructor(
    private val ring: MicrophoneRing,
    private var generation: Int,
) {
    private var sourceFrame = 0.0
    private var rate = NoiseCancellation.MICROPHONE_SAMPLE_RATE

    /** Writes [frames] mono samples at [sampleRate] into [target], using silence until the mic catches up. */
    fun read(
        target: ShortArray,
        frames: Int,
        sampleRate: Int,
    ) {
        if (frames <= 0 || sampleRate <= 0) return
        val currentGeneration = NoiseCancellation.currentGeneration()
        if (generation != currentGeneration || rate != ring.sampleRate) {
            generation = currentGeneration
            rate = ring.sampleRate
            sourceFrame = 0.0
        }
        val latest = ring.latestSequence
        val oldest = max(0L, latest - MICROPHONE_RING_FRAMES)
        if (sourceFrame < oldest) sourceFrame = oldest.toDouble()
        if (sourceFrame > latest) sourceFrame = latest.toDouble()
        val step = ring.sampleRate.toDouble() / sampleRate
        for (index in 0 until frames) {
            val position = sourceFrame.toLong()
            val fraction = (sourceFrame - position).toFloat()
            if (position >= oldest && position + 1 < latest) {
                val first = ring.sampleAt(position).toInt()
                val second = ring.sampleAt(position + 1).toInt()
                target[index] = (first + (second - first) * fraction).toInt().toShort()
            } else {
                target[index] = 0
            }
            sourceFrame += step
        }
    }
}
