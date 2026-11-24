package com.metrolist.shared.recognition

import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Clock

object IosRecognitionManagerLogic {
    private val recognitionMutex = Mutex()

    @Throws(Exception::class)
    suspend fun recognize(
        signature: String,
        sampleDurationMs: Long,
        entryPoint: RecognitionEntryPoint,
    ): RecognitionResult {
        if (!recognitionMutex.tryLock()) {
            throw IllegalStateException("Recognition is already in progress.")
        }
        try {
            val result = IosRecognitionBridge.recognize(signature, sampleDurationMs)
            val recognizedAtEpochMillis = Clock.System.now().toEpochMilliseconds()
            val history = IosRecognitionBridge.createRecognitionHistory(result, recognizedAtEpochMillis)
            val historySaved = IosRecognitionBridge.saveRecognitionHistory(history)
            val resolvedVideoId = resolveVideoId(result)
            val enrichedResult =
                if (resolvedVideoId != null && resolvedVideoId != result.youtubeVideoId) {
                    updateVideoId(history.trackId, resolvedVideoId)
                    result.copy(youtubeVideoId = resolvedVideoId)
                } else {
                    result
                }
            RecognitionNotification.shared.postCompleted(
                payload = RecognitionPayload.fromResult(enrichedResult, recognizedAtEpochMillis),
                entryPoint = entryPoint,
                historySaved = historySaved,
            )
            return enrichedResult
        } finally {
            recognitionMutex.unlock()
        }
    }

    private suspend fun resolveVideoId(result: RecognitionResult): String? =
        try {
            IosRecognitionBridge
                .resolveYoutubeVideoId(result)
                ?.takeIf { it.isNotBlank() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    private suspend fun updateVideoId(
        trackId: String,
        youtubeVideoId: String,
    ) {
        try {
            IosRecognitionBridge.updateRecognitionVideoId(trackId, youtubeVideoId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.w(
                "IosRecognitionManagerLogic",
                "Failed to update recognized song video type=${error::class.simpleName}",
                error,
            )
        }
    }
}
