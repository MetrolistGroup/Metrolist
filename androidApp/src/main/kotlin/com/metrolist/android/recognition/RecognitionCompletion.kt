package com.metrolist.android.recognition

import com.metrolist.shared.recognition.RecognitionResult
import com.metrolist.shared.recognition.RecognitionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

internal suspend fun completeRecognition(
    result: RecognitionResult,
    save: suspend () -> Unit,
    resolve: suspend () -> String?,
    updateVideoId: suspend (String) -> Unit,
    publish: (RecognitionStatus.Success) -> Unit,
    enrichmentScope: CoroutineScope,
): RecognitionStatus.Success {
    var success = RecognitionStatus.Success(result)
    try {
        save()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Timber.e(error, "Failed to save recognized song")
        success = success.copy(historySaved = false)
    }
    publish(success)
    if (result.youtubeVideoId == null) {
        enrichmentScope.launch {
            try {
                val videoId = resolve() ?: return@launch
                val enriched = success.copy(result = result.copy(youtubeVideoId = videoId))
                publish(enriched)
                if (success.historySaved) updateVideoId(videoId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(error, "Failed to resolve recognized song playback")
            }
        }
    }
    return success
}
