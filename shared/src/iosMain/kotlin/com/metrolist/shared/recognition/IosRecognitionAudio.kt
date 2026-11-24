package com.metrolist.shared.recognition

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData

data class IosRecognitionAudioSignature(
    val signature: String,
    val sampleDurationMs: Long,
)

@OptIn(ExperimentalForeignApi::class)
object IosRecognitionAudio {
    @Throws(Exception::class)
    suspend fun signature(
        pcm: NSData,
        inputRate: Int,
    ): IosRecognitionAudioSignature =
        withContext(Dispatchers.Default) {
            val length = pcm.length.toInt()
            require(length > 0 && length % 2 == 0) { "pcm must be non-empty 16-bit little-endian data" }
            val source = checkNotNull(pcm.bytes) { "pcm has no bytes" }.reinterpret<ByteVar>()
            val bytes = ByteArray(length) { index -> source[index] }
            val resampled =
                RecognitionAudio.resample16BitMono(
                    input = bytes,
                    inputRate = inputRate,
                    outputRate = RecognitionAudio.FINGERPRINT_SAMPLE_RATE,
                )
            IosRecognitionAudioSignature(
                signature = ShazamSignatureGenerator.fromI16(resampled),
                sampleDurationMs = resampled.size.toLong() * 1000L / (2L * RecognitionAudio.FINGERPRINT_SAMPLE_RATE),
            )
        }
}
