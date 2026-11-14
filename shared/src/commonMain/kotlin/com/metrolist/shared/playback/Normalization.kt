package com.metrolist.shared.playback

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.tanh

fun normalizationBoostPcm16(
    sample: Short,
    gain: Float,
): Short {
    if (gain <= 1f) return sample
    val boosted = sample / 32768.0 * gain
    val magnitude = abs(boosted)
    val limited = if (magnitude <= 0.9) boosted else sign(boosted) * (0.9 + 0.1 * tanh((magnitude - 0.9) / 0.1))
    return (limited * 32768.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}
