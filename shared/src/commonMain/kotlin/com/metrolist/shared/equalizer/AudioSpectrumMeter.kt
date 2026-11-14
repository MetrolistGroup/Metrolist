package com.metrolist.shared.equalizer

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

object AudioSpectrumMeter {
    private const val FRAME_SIZE = 2048
    private const val MAX_CHANNELS = 8
    private val centers =
        doubleArrayOf(
            45.0,
            63.0,
            90.0,
            125.0,
            180.0,
            250.0,
            355.0,
            500.0,
            710.0,
            1000.0,
            1400.0,
            2000.0,
            2800.0,
            4000.0,
            5600.0,
            8000.0,
            11000.0,
            15000.0,
        )
    val frequencies: List<Double> = centers.toList()
    private val frame = DoubleArray(FRAME_SIZE * MAX_CHANNELS)
    private val window = DoubleArray(FRAME_SIZE) { n -> 0.5 - 0.5 * cos(2.0 * PI * n / (FRAME_SIZE - 1)) }
    private val lock = SynchronizedObject()
    private var consumerCount = 0
    private val _hasConsumers = MutableStateFlow(false)
    val hasConsumers = _hasConsumers.asStateFlow()
    private var framePosition = 0
    private var frameReady = false

    @Volatile
    var sampleRate = 48_000
        private set
    private var frameChannels = 1

    @Volatile private var currentLevels = FloatArray(centers.size)

    @Volatile private var collecting = false

    @Volatile
    var gainReductionDb = 0f
        private set

    val bandCount: Int get() = centers.size

    fun addConsumer() =
        synchronized(lock) {
            consumerCount++
            collecting = true
            _hasConsumers.value = true
        }

    fun removeConsumer() =
        synchronized(lock) {
            check(consumerCount > 0)
            consumerCount--
            _hasConsumers.value = consumerCount > 0
            collecting = consumerCount > 0
            if (!collecting) reset()
        }

    /** Consumes interleaved signed PCM16 samples after app audio processing. */
    fun accept(
        samples: ShortArray,
        count: Int,
        channels: Int,
        rate: Int,
        gainReductionDb: Float = 0f,
    ) {
        if (!collecting) return
        synchronized(lock) {
            if (!collecting) return@synchronized
            if (channels <= 0 || rate <= 0) return@synchronized
            this.gainReductionDb = gainReductionDb
            val capturedChannels = if (channels > MAX_CHANNELS) 1 else channels
            if (sampleRate != rate || frameChannels != capturedChannels) {
                sampleRate = rate
                frameChannels = capturedChannels
                framePosition = 0
                frameReady = false
            }
            var i = 0
            while (i + channels <= count && !frameReady) {
                if (channels <= MAX_CHANNELS) {
                    for (channel in 0 until channels) {
                        frame[channel * FRAME_SIZE + framePosition] = samples[i + channel] / 32768.0
                    }
                } else {
                    var mono = 0.0
                    for (channel in 0 until channels) mono += samples[i + channel] / 32768.0
                    frame[framePosition] = mono / channels
                }
                framePosition++
                i += channels
                if (framePosition == FRAME_SIZE) frameReady = true
            }
        }
    }

    /** Consumes interleaved float PCM from native audio backends. */
    fun accept(
        samples: FloatArray,
        count: Int,
        channels: Int,
        rate: Int,
        gainReductionDb: Float = 0f,
    ) {
        if (!collecting) return
        synchronized(lock) {
            if (!collecting) return@synchronized
            if (channels <= 0 || rate <= 0) return@synchronized
            this.gainReductionDb = gainReductionDb
            val capturedChannels = if (channels > MAX_CHANNELS) 1 else channels
            if (sampleRate != rate || frameChannels != capturedChannels) {
                sampleRate = rate
                frameChannels = capturedChannels
                framePosition = 0
                frameReady = false
            }
            var i = 0
            while (i + channels <= count && !frameReady) {
                if (channels <= MAX_CHANNELS) {
                    for (channel in 0 until channels) {
                        frame[channel * FRAME_SIZE + framePosition] = samples[i + channel].toDouble()
                    }
                } else {
                    var mono = 0.0
                    for (channel in 0 until channels) mono += samples[i + channel].toDouble()
                    frame[framePosition] = mono / channels
                }
                framePosition++
                i += channels
                if (framePosition == FRAME_SIZE) frameReady = true
            }
        }
    }

    fun snapshotLevels(): FloatArray = currentLevels.copyOf()

    fun levels(): FloatArray {
        val snapshot =
            synchronized(lock) {
                if (!frameReady) {
                    currentLevels = FloatArray(centers.size) { index -> currentLevels[index] * 0.98f }
                    return@synchronized null
                }
                val frameCopy = frame.copyOf(FRAME_SIZE * frameChannels)
                framePosition = 0
                frameReady = false
                Triple(frameCopy, sampleRate, frameChannels)
            }
        snapshot?.let { (samples, rate, channels) -> analyzeFrame(samples, rate, channels) }
        return currentLevels.copyOf()
    }

    fun reset() =
        synchronized(lock) {
            framePosition = 0
            frameReady = false
            gainReductionDb = 0f
            frameChannels = 1
            currentLevels = FloatArray(centers.size)
        }

    private fun analyzeFrame(
        samples: DoubleArray,
        rate: Int,
        channels: Int,
    ) {
        val next = FloatArray(centers.size)
        for (band in centers.indices) {
            val frequency = centers[band]
            if (frequency >= rate / 2.0) continue
            val coefficient = 2.0 * cos(2.0 * PI * frequency / rate)
            var power = 0.0
            for (channel in 0 until channels) {
                var previous = 0.0
                var previous2 = 0.0
                val channelOffset = channel * FRAME_SIZE
                for (n in 0 until FRAME_SIZE) {
                    val value = samples[channelOffset + n] * window[n] + coefficient * previous - previous2
                    previous2 = previous
                    previous = value
                }
                power += max(0.0, previous * previous + previous2 * previous2 - coefficient * previous * previous2)
            }
            val magnitude = 2.0 * sqrt(power / channels) / FRAME_SIZE
            val level = ((20.0 * ln(max(magnitude, 0.000001)) / ln(10.0) + 60.0) / 60.0).coerceIn(0.0, 1.0)
            next[band] = level.toFloat()
        }
        val previous = currentLevels
        currentLevels =
            FloatArray(centers.size) { index ->
                val smoothing = if (next[index] > previous[index]) 0.65f else 0.88f
                previous[index] * smoothing + next[index] * (1f - smoothing)
            }
    }
}
