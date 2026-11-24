package com.metrolist.shared.recognition

object RecognitionAudio {
    const val FINGERPRINT_SAMPLE_RATE = 16_000
    const val RECORDING_DURATION_MS = 12_000L

    fun resample16BitMono(
        input: ByteArray,
        inputRate: Int,
        outputRate: Int,
    ): ByteArray {
        if (inputRate == outputRate) return input
        val inputSamples =
            ShortArray(input.size / 2) { index ->
                val offset = index * 2
                ((input[offset].toInt() and 0xff) or (input[offset + 1].toInt() shl 8)).toShort()
            }
        val ratio = outputRate.toDouble() / inputRate.toDouble()
        val outputSamples = ShortArray((inputSamples.size * ratio).toInt())
        for (index in outputSamples.indices) {
            val source = index / ratio
            val base = source.toInt().coerceIn(0, inputSamples.lastIndex)
            val next = (base + 1).coerceAtMost(inputSamples.lastIndex)
            val fraction = source - base
            outputSamples[index] = (inputSamples[base] * (1.0 - fraction) + inputSamples[next] * fraction).toInt().toShort()
        }
        return ByteArray(outputSamples.size * 2) { index ->
            val sampleIndex = index / 2
            val shift = if (index % 2 == 0) 0 else 8
            ((outputSamples[sampleIndex].toInt() ushr shift) and 0xff).toByte()
        }
    }
}
