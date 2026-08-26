package com.metrolist.music.recognition

/**
 * Audio fingerprint generator for Shazam-compatible signatures.
 *
 * Pure Kotlin implementation — no native C++ or FFTW3 dependency required.
 * Uses [ShazamSignatureGenerator] which ports the vibra algorithm to JVM.
 */
object VibraSignature {

    const val REQUIRED_SAMPLE_RATE = 16_000

    /**
     * Generates a Shazam signature from raw PCM audio data.
     *
     * @param samples Raw PCM audio data (mono, 16-bit signed little-endian, 16kHz)
     * @return The encoded signature URI string suitable for the Shazam API
     * @throws IllegalArgumentException if samples is empty or has odd length
     */
    @JvmStatic
    fun fromI16(samples: ByteArray): String = ShazamSignatureGenerator.fromI16(samples)
}
