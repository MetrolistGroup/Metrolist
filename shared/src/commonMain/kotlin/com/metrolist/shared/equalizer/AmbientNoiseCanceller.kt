/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.equalizer

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private const val VOICE_BAND_LOW_HZ = 120.0
private const val VOICE_BAND_HIGH_HZ = 4_000.0
private const val ANTI_NOISE_LIMIT = 0.5f
private const val RAMP_SECONDS = 0.05f

/**
 * Feed-forward phase inversion. The microphone is a local noise reference: its voice-band component
 * is inverted and added to the music so ambient speech is attenuated acoustically. There is no error
 * microphone, so the path is a fixed filter rather than an adaptive one. The strength control lets
 * the user match it to their microphone and speaker placement. All work is local and allocation-free
 * after construction.
 */
class AmbientNoiseCanceller(
    private val sampleRate: Int,
    private val channels: Int,
) {
    private val reader = NoiseCancellation.newReferenceReader()
    private var microphoneSamples = ShortArray(0)
    private var antiNoise = FloatArray(0)
    private var dcEstimate = 0f
    private var lowPassState = 0f
    private var ramp = 0f

    private val dcDecay = exp(-2.0 * kotlin.math.PI * VOICE_BAND_LOW_HZ / sampleRate).toFloat()
    private val lowPassGain = (1.0 - exp(-2.0 * kotlin.math.PI * VOICE_BAND_HIGH_HZ / sampleRate)).toFloat()
    private val rampStep = 1f / (RAMP_SECONDS * sampleRate)

    fun processPcm16(
        samples: ShortArray,
        count: Int,
    ) {
        val frames = count / channels
        if (frames <= 0) return
        val anti = renderAntiNoise(frames)
        for (frame in 0 until frames) {
            val value = anti[frame]
            if (value == 0f) continue
            for (channel in 0 until channels) {
                val index = frame * channels + channel
                val mixed = samples[index] / 32768f + value
                samples[index] =
                    (mixed * 32768f)
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
            }
        }
    }

    fun processPcmFloat(
        samples: FloatArray,
        count: Int,
    ) {
        val frames = count / channels
        if (frames <= 0) return
        val anti = renderAntiNoise(frames)
        for (frame in 0 until frames) {
            val value = anti[frame]
            if (value == 0f) continue
            for (channel in 0 until channels) {
                samples[frame * channels + channel] += value
            }
        }
    }

    fun reset() {
        dcEstimate = 0f
        lowPassState = 0f
        ramp = 0f
    }

    private fun renderAntiNoise(frames: Int): FloatArray {
        if (microphoneSamples.size < frames) microphoneSamples = ShortArray(frames)
        if (antiNoise.size < frames) antiNoise = FloatArray(frames)
        reader.read(microphoneSamples, frames, sampleRate)
        val target = if (NoiseCancellation.isActive) NoiseCancellation.strength else 0f
        ramp = if (ramp < target) min(target, ramp + rampStep) else max(target, ramp - rampStep)
        for (frame in 0 until frames) {
            val raw = microphoneSamples[frame] / 32768f
            dcEstimate = raw + dcDecay * (dcEstimate - raw)
            val voiceBand = raw - dcEstimate
            lowPassState += lowPassGain * (voiceBand - lowPassState)
            antiNoise[frame] = (-lowPassState * ramp).coerceIn(-ANTI_NOISE_LIMIT, ANTI_NOISE_LIMIT)
        }
        return antiNoise
    }
}
