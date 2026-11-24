package com.metrolist.shared.recognition

import com.metrolist.shared.utils.Logger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Clock

class RecognitionAlreadyRunningException : RuntimeException()

class IosRecognitionLease internal constructor() {
    private val released = atomic(false)

    fun release() {
        if (released.compareAndSet(false, true)) {
            IosRecognitionManagerLogic.releaseLease()
        }
    }
}

object IosRecognitionManagerLogic {
    private val recognitionMutex = Mutex()
    private val enrichmentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun tryAcquireLease(): IosRecognitionLease? = if (recognitionMutex.tryLock()) IosRecognitionLease() else null

    internal fun releaseLease() {
        recognitionMutex.unlock()
    }

    @Throws(Exception::class)
    suspend fun recognize(
        signature: String,
        sampleDurationMs: Long,
        entryPoint: RecognitionEntryPoint,
        lease: IosRecognitionLease,
    ): RecognitionResult {
        try {
            val result = IosRecognitionBridge.recognize(signature, sampleDurationMs)
            val recognizedAtEpochMillis = Clock.System.now().toEpochMilliseconds()
            val history = IosRecognitionBridge.createRecognitionHistory(result, recognizedAtEpochMillis)
            val historySaved = IosRecognitionBridge.saveRecognitionHistory(history)
            postCompleted(result, recognizedAtEpochMillis, entryPoint, historySaved)
            if (result.youtubeVideoId == null) {
                enrichmentScope.launch {
                    try {
                        val videoId = resolveVideoId(result) ?: return@launch
                        updateVideoId(history.trackId, videoId)
                        postCompleted(
                            result.copy(youtubeVideoId = videoId),
                            recognizedAtEpochMillis,
                            entryPoint,
                            historySaved,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Logger.w(
                            "IosRecognitionManagerLogic",
                            "Failed to enrich recognized song type=${error::class.simpleName}",
                            error,
                        )
                    }
                }
            }
            return result
        } finally {
            lease.release()
        }
    }

    private fun postCompleted(
        result: RecognitionResult,
        recognizedAtEpochMillis: Long,
        entryPoint: RecognitionEntryPoint,
        historySaved: Boolean,
    ) {
        RecognitionNotification.postCompleted(
            payload = RecognitionPayload.fromResult(result, recognizedAtEpochMillis),
            entryPoint = entryPoint,
            historySaved = historySaved,
        )
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
