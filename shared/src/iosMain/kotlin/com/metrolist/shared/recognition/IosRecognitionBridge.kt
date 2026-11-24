package com.metrolist.shared.recognition

import com.metrolist.shared.db.DatabaseDao
import com.metrolist.shared.db.entities.RecognitionHistory
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.mp.KoinPlatform
import kotlin.time.Clock

object IosRecognitionBridge {
    @Throws(Exception::class)
    suspend fun recognize(
        signature: String,
        sampleDurationMs: Long,
    ): RecognitionResult = ShazamRecognitionClient.recognize(signature, sampleDurationMs).getOrThrow()

    fun createRecognitionHistory(
        result: RecognitionResult,
        recognizedAtEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): RecognitionHistory =
        RecognitionHistory(
            trackId = result.trackId.ifBlank { "${result.title}:${result.artist}" },
            title = result.title,
            artist = result.artist,
            album = result.album,
            coverArtUrl = result.coverArtUrl,
            coverArtHqUrl = result.coverArtHqUrl,
            genre = result.genre,
            releaseDate = result.releaseDate,
            label = result.label,
            shazamUrl = result.shazamUrl,
            appleMusicUrl = result.appleMusicUrl,
            spotifyUrl = result.spotifyUrl,
            isrc = result.isrc,
            youtubeVideoId = result.youtubeVideoId,
            recognizedAt = Instant.fromEpochMilliseconds(recognizedAtEpochMillis).toLocalDateTime(TimeZone.UTC),
            liked = result.youtubeVideoId?.let { SongLikeState.isLiked(it) } == true,
        )

    @Throws(Exception::class)
    suspend fun saveRecognitionHistory(history: RecognitionHistory): Boolean =
        try {
            KoinPlatform.getKoin().get<DatabaseDao>().insert(history)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A failed history insert must not hide a valid match from the caller.
            Logger.w("IosRecognitionBridge", "Failed to save recognized song type=${error::class.simpleName}", error)
            false
        }

    @Throws(Exception::class)
    suspend fun updateRecognitionVideoId(
        trackId: String,
        youtubeVideoId: String,
    ) {
        val dao = KoinPlatform.getKoin().get<DatabaseDao>()
        dao.updateRecognitionHistoryVideoId(trackId, youtubeVideoId)
        dao.updateRecognitionHistoryLikedByTrack(trackId, SongLikeState.isLiked(youtubeVideoId))
    }

    suspend fun resolveYoutubeVideoId(result: RecognitionResult): String? {
        // Shazam's own video ID is authoritative; a search result must not replace it.
        result.youtubeVideoId?.takeIf { it.isNotBlank() }?.let { return it }
        val query = listOf(result.title, result.artist).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null
        return try {
            for (filter in listOf(SearchFilter.SONG, SearchFilter.VIDEO)) {
                GlobalYouTubeRepository.instance
                    .searchFiltered(query, filter)
                    .items
                    .filterIsInstance<SongItem>()
                    .firstOrNull { it.matchesRecognition(result) }
                    ?.let { return it.id }
            }
            GlobalYouTubeRepository.instance
                .search(query, recordSearchHistory = false)
                .filterIsInstance<SongItem>()
                .firstOrNull { it.matchesRecognition(result) }
                ?.id
        } catch (_: Exception) {
            null
        }
    }
}
