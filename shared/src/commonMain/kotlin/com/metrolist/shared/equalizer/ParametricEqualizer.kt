/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.equalizer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

const val MIN_AUDIO_PREAMP_DB = -12.0
const val MAX_AUDIO_BOOST_DB = 12.0

/** RBJ biquad chain; use one instance per audio pipeline because filter state is not thread-safe. */
class ParametricEqualizer(
    private val sampleRate: Int,
    private val channelCount: Int,
) {
    private var profile: EqualizerProfile? = null

    // Per filter: b0, b1, b2, a1, a2 (normalized by a0).
    private var coefficients = emptyArray<DoubleArray>()
    private var appliedBands = emptyList<ParametricEqBand>()

    init {
        require(sampleRate > 0 && channelCount > 0)
    }

    private var activeFilters = IntArray(0)

    // Per filter: x1, x2, y1, y2 for each channel.
    private var state = emptyArray<DoubleArray>()
    private var preampGain = 1.0
    private var peakEnvelope = 0.0
    private var peakHoldFrames = 0
    private val peakHoldDuration = (sampleRate * PEAK_HOLD_SECONDS).toInt()
    private val releaseDecay = exp(-1.0 / (sampleRate * LIMITER_RELEASE_SECONDS))
    var gainReductionDb = 0.0
        private set
    private var processingBuffer = DoubleArray((sampleRate / 10 * channelCount).coerceAtLeast(1))

    fun update(profile: EqualizerProfile?): Boolean {
        if (profile !== this.profile) {
            this.profile = profile
            val bands =
                profile?.bands.orEmpty().filter {
                    it.enabled && it.frequency > 0.0 && it.frequency < sampleRate / 2.0 &&
                        it.gain.isFinite() && it.q.isFinite() && it.q > 0.0
                }
            val previousBands = appliedBands
            val previousCoefficients = coefficients
            val sameTopology =
                bands.size == appliedBands.size &&
                    bands.indices.all { index ->
                        val before = appliedBands[index]
                        val after = bands[index]
                        before.filterType == after.filterType
                    }
            val active = bands.indices.filter { !bands[it].isIdentity() }.toIntArray()
            if (!sameTopology) {
                state = Array(bands.size) { DoubleArray(channelCount * 4) }
            } else {
                // A skipped band's state stopped advancing; restart it rather than replay stale history.
                for (index in active) if (index !in activeFilters) state[index].fill(0.0)
            }
            activeFilters = active
            coefficients =
                bands
                    .mapIndexed { index, band ->
                        if (previousBands.getOrNull(index) == band) previousCoefficients[index] else coefficientsFor(band)
                    }.toTypedArray()
            appliedBands = bands
            preampGain = 10.0.pow((profile?.preamp ?: 0.0) / 20.0)
        }
        return profile != null
    }

    fun process(
        sample: Double,
        channel: Int,
    ): Double {
        var x = sample
        for (i in activeFilters) {
            val c = coefficients[i]
            val s = state[i]
            val o = channel * 4
            val y = c[0] * x + c[1] * s[o] + c[2] * s[o + 1] - c[3] * s[o + 2] - c[4] * s[o + 3]
            s[o + 1] = s[o]
            s[o] = x
            s[o + 3] = s[o + 2]
            s[o + 2] = y
            x = y
        }
        return x * preampGain
    }

    fun responseDb(frequency: Double): Double {
        require(frequency in 0.0..sampleRate / 2.0)
        val w = 2.0 * PI * frequency / sampleRate
        var db = 20.0 * log10(preampGain)
        for (c in coefficients) {
            val realNumerator = c[0] + c[1] * cos(w) + c[2] * cos(2 * w)
            val imaginaryNumerator = c[1] * sin(w) + c[2] * sin(2 * w)
            val realDenominator = 1.0 + c[3] * cos(w) + c[4] * cos(2 * w)
            val imaginaryDenominator = c[3] * sin(w) + c[4] * sin(2 * w)
            db += 10.0 *
                log10(
                    (realNumerator * realNumerator + imaginaryNumerator * imaginaryNumerator) /
                        (realDenominator * realDenominator + imaginaryDenominator * imaginaryDenominator),
                )
        }
        return db
    }

    fun processPcm16(
        samples: ShortArray,
        count: Int = samples.size,
        outputGainDb: Double = 0.0,
    ) {
        val outputGain = 10.0.pow(outputGainDb.coerceIn(MIN_AUDIO_PREAMP_DB, MAX_AUDIO_BOOST_DB) / 20.0)
        if (activeFilters.isEmpty() && preampGain == 1.0 && outputGain == 1.0) {
            var frameStart = 0
            var lastGain = 1.0
            while (frameStart < count) {
                val frameEnd = minOf(frameStart + channelCount, count)
                var peak = 0
                for (i in frameStart until frameEnd) peak = maxOf(peak, abs(samples[i].toInt()))
                val gain = limiterGain(peak / 32768.0, 1.0)
                lastGain = gain
                if (gain != 1.0) {
                    for (i in frameStart until frameEnd) samples[i] = (samples[i] * gain).roundToInt().toShort()
                }
                frameStart = frameEnd
            }
            gainReductionDb = -20.0 * log10(lastGain)
            return
        }
        if (processingBuffer.size < count) processingBuffer = DoubleArray(count)
        for (i in 0 until count) processingBuffer[i] = samples[i] / 32768.0
        filterBuffer(count)
        limitFrames(count, outputGain)
        for (i in 0 until count) {
            val y = processingBuffer[i] * 32768.0
            samples[i] = y.coerceIn(-32768.0, 32767.0).roundToInt().toShort()
        }
    }

    fun processPcmFloat(
        samples: FloatArray,
        count: Int = samples.size,
        outputGainDb: Double = 0.0,
    ) {
        val outputGain = 10.0.pow(outputGainDb.coerceIn(MIN_AUDIO_PREAMP_DB, MAX_AUDIO_BOOST_DB) / 20.0)
        if (activeFilters.isEmpty() && preampGain == 1.0 && outputGain == 1.0) {
            var frameStart = 0
            var lastGain = 1.0
            while (frameStart < count) {
                val frameEnd = minOf(frameStart + channelCount, count)
                var peak = 0.0
                for (i in frameStart until frameEnd) peak = maxOf(peak, abs(samples[i].toDouble()))
                val gain = limiterGain(peak, 1.0)
                lastGain = gain
                if (gain != 1.0 || !(peak <= 1.0)) {
                    for (i in frameStart until frameEnd) samples[i] = (samples[i] * gain).coerceIn(-1.0, 1.0).toFloat()
                }
                frameStart = frameEnd
            }
            gainReductionDb = -20.0 * log10(lastGain)
            return
        }
        if (processingBuffer.size < count) processingBuffer = DoubleArray(count)
        for (i in 0 until count) processingBuffer[i] = samples[i].toDouble()
        filterBuffer(count)
        limitFrames(count, outputGain)
        for (i in 0 until count) {
            val y = processingBuffer[i]
            samples[i] = y.coerceIn(-1.0, 1.0).toFloat()
        }
    }

    private fun filterBuffer(count: Int) {
        for (index in activeFilters) {
            val c = coefficients[index]
            val b0 = c[0]
            val b1 = c[1]
            val b2 = c[2]
            val a1 = c[3]
            val a2 = c[4]
            val s = state[index]
            for (channel in 0 until channelCount) {
                val o = channel * 4
                var x1 = s[o]
                var x2 = s[o + 1]
                var y1 = s[o + 2]
                var y2 = s[o + 3]
                var i = channel
                while (i < count) {
                    val x = processingBuffer[i]
                    val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                    x2 = x1
                    x1 = x
                    y2 = y1
                    y1 = y
                    processingBuffer[i] = y
                    i += channelCount
                }
                s[o] = x1
                s[o + 1] = x2
                s[o + 2] = y1
                s[o + 3] = y2
            }
        }
        if (preampGain != 1.0) for (i in 0 until count) processingBuffer[i] *= preampGain
    }

    private fun limitFrames(
        count: Int,
        outputGain: Double,
    ) {
        var frameStart = 0
        var lastGain = outputGain
        while (frameStart < count) {
            val frameEnd = minOf(frameStart + channelCount, count)
            var peak = 0.0
            for (i in frameStart until frameEnd) peak = maxOf(peak, abs(processingBuffer[i]))
            val gain = limiterGain(peak, outputGain)
            lastGain = gain
            for (i in frameStart until frameEnd) processingBuffer[i] *= gain
            frameStart = frameEnd
        }
        gainReductionDb = -20.0 * log10(lastGain / outputGain)
    }

    private fun limiterGain(
        peak: Double,
        outputGain: Double,
    ): Double {
        if (peak >= peakEnvelope) {
            peakEnvelope = peak
            peakHoldFrames = peakHoldDuration
        } else if (peakHoldFrames > 0) {
            peakHoldFrames--
        } else {
            peakEnvelope = peak + (peakEnvelope - peak) * releaseDecay
        }
        // Hold the detected peak across waveform cycles instead of reshaping each sample.
        return if (peakEnvelope > 0.0) minOf(outputGain, LIMITER_CEILING / peakEnvelope) else outputGain
    }

    fun reset() {
        state.forEach { it.fill(0.0) }
        peakEnvelope = 0.0
        peakHoldFrames = 0
        gainReductionDb = 0.0
    }

    private fun coefficientsFor(band: ParametricEqBand): DoubleArray {
        val a = 10.0.pow(band.gain / 40.0)
        val w = 2.0 * PI * band.frequency / sampleRate
        val cosW = cos(w)
        val alpha = sin(w) / (2.0 * band.q)
        val shelf = 2.0 * sqrt(a) * alpha
        val (b, aCoeffs) =
            when (band.filterType) {
                FilterType.PK -> {
                    doubleArrayOf(1 + alpha * a, -2 * cosW, 1 - alpha * a) to
                        doubleArrayOf(1 + alpha / a, -2 * cosW, 1 - alpha / a)
                }

                FilterType.LSC -> {
                    doubleArrayOf(
                        a * ((a + 1) - (a - 1) * cosW + shelf),
                        2 * a * ((a - 1) - (a + 1) * cosW),
                        a * ((a + 1) - (a - 1) * cosW - shelf),
                    ) to doubleArrayOf((a + 1) + (a - 1) * cosW + shelf, -2 * ((a - 1) + (a + 1) * cosW), (a + 1) + (a - 1) * cosW - shelf)
                }

                FilterType.HSC -> {
                    doubleArrayOf(
                        a * ((a + 1) + (a - 1) * cosW + shelf),
                        -2 * a * ((a - 1) + (a + 1) * cosW),
                        a * ((a + 1) + (a - 1) * cosW - shelf),
                    ) to doubleArrayOf((a + 1) - (a - 1) * cosW + shelf, 2 * ((a - 1) - (a + 1) * cosW), (a + 1) - (a - 1) * cosW - shelf)
                }

                FilterType.LPQ -> {
                    doubleArrayOf((1 - cosW) / 2, 1 - cosW, (1 - cosW) / 2) to doubleArrayOf(1 + alpha, -2 * cosW, 1 - alpha)
                }

                FilterType.HPQ -> {
                    doubleArrayOf((1 + cosW) / 2, -(1 + cosW), (1 + cosW) / 2) to doubleArrayOf(1 + alpha, -2 * cosW, 1 - alpha)
                }
            }
        val a0 = aCoeffs[0]
        return doubleArrayOf(b[0] / a0, b[1] / a0, b[2] / a0, aCoeffs[1] / a0, aCoeffs[2] / a0)
    }

    private companion object {
        const val LIMITER_CEILING = 0.98
        const val PEAK_HOLD_SECONDS = 0.05
        const val LIMITER_RELEASE_SECONDS = 0.15
    }
}
