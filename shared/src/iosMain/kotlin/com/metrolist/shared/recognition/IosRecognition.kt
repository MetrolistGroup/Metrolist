@file:OptIn(ExperimentalForeignApi::class)

package com.metrolist.shared.recognition

import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.recognition_failed
import com.metrolist.shared.generated.resources.recognition_microphone_permission_not_granted
import com.metrolist.shared.generated.resources.recognition_network_error
import com.metrolist.shared.generated.resources.recognition_no_match_try_clearer_section
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.utils.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import org.jetbrains.compose.resources.getString
import org.koin.mp.KoinPlatform
import platform.Foundation.NSData

private const val LOG_TAG = "MusicRecognition"

object IosRecognition {
    private val enrichmentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun microphoneDeniedMessage(): String = getString(Res.string.recognition_microphone_permission_not_granted)

    // Returns "title - artist" for a match, otherwise a localized error message.
    suspend fun recognize(
        pcm: NSData,
        inputRate: Int,
    ): String =
        try {
            val length = pcm.length.toInt()
            val bytes = pcm.bytes?.takeIf { length > 0 && inputRate > 0 }?.readBytes(length)
            if (bytes == null) {
                getString(Res.string.recognition_failed)
            } else {
                val (signature, sampleDurationMs) =
                    withContext(Dispatchers.Default) {
                        val resampled = RecognitionAudio.resample16BitMono(bytes, inputRate, RecognitionAudio.FINGERPRINT_SAMPLE_RATE)
                        ShazamSignatureGenerator.fromI16(resampled) to
                            (resampled.size / 2) * 1000L / RecognitionAudio.FINGERPRINT_SAMPLE_RATE
                    }
                val result = ShazamRecognitionClient.recognize(signature, sampleDurationMs).getOrThrow()
                save(result)
                "${result.title} - ${result.artist}"
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: NoRecognitionMatchException) {
            getString(Res.string.recognition_no_match_try_clearer_section)
        } catch (error: Exception) {
            Logger.e(LOG_TAG, "Music recognition failed", error)
            when (error) {
                is RecognitionUserException -> error.message?.takeIf { it.isNotBlank() } ?: getString(Res.string.recognition_failed)
                is IOException -> getString(Res.string.recognition_network_error)
                else -> getString(Res.string.recognition_failed)
            }
        }

    private suspend fun save(result: RecognitionResult) {
        val dao =
            try {
                KoinPlatform.getKoin().get<DatabaseDao>().also { it.insert(result.toRecognitionHistory()) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A failed history insert must not hide a valid match.
                Logger.e(LOG_TAG, "Failed to save recognized song", error)
                return
            }
        if (result.youtubeVideoId != null) return
        enrichmentScope.launch {
            val videoId = resolveYoutubeVideoId(result) ?: return@launch
            try {
                dao.updateRecognitionHistoryVideoId(result.historyTrackId, videoId)
                dao.updateRecognitionHistoryLikedByTrack(result.historyTrackId, SongLikeState.isLiked(videoId))
            } catch (error: Exception) {
                Logger.w(LOG_TAG, "Failed to update recognized song", error)
            }
        }
    }
}
