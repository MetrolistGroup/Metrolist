package com.metrolist.android.recognition

import com.metrolist.shared.recognition.RecognitionResult
import com.metrolist.shared.recognition.ShazamRecognitionClient as SharedShazamRecognitionClient

object ShazamRecognitionClient {
    suspend fun recognize(
        signature: String,
        sampleDurationMs: Long,
    ): Result<RecognitionResult> = SharedShazamRecognitionClient.recognize(signature, sampleDurationMs)

    internal fun parseResult(responseText: String): RecognitionResult? = SharedShazamRecognitionClient.parseResult(responseText)
}
