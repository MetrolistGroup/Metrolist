package com.metrolist.shared.recognition

import kotlin.test.Test
import kotlin.test.assertEquals

class ShazamSignatureGeneratorTest {
    @Test
    fun reusingScratchBuffersPreservesTheFullCaptureFingerprint() {
        val samples = ByteArray(16_000 * 12 * 2)
        var noise = 12345
        for (index in 0 until samples.size / 2) {
            noise = noise * 1664525 + 1013904223
            val value = (noise ushr 16).toShort().toInt()
            samples[index * 2] = value.toByte()
            samples[index * 2 + 1] = (value shr 8).toByte()
        }
        repeat(2) {
            val signature = ShazamSignatureGenerator.fromI16(samples)
            assertEquals(13241, signature.length)
            assertEquals(-771824122, signature.hashCode())
        }
    }
}
