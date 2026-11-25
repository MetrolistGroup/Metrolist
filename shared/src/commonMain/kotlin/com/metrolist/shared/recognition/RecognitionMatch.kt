package com.metrolist.shared.recognition

import com.metrolist.shared.db.entities.RecognitionHistory
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

val RecognitionResult.historyTrackId: String
    get() = trackId.ifBlank { "$title:$artist" }

fun RecognitionResult.toRecognitionHistory(): RecognitionHistory =
    RecognitionHistory(
        trackId = historyTrackId,
        title = title,
        artist = artist,
        album = album,
        coverArtUrl = coverArtUrl,
        coverArtHqUrl = coverArtHqUrl,
        genre = genre,
        releaseDate = releaseDate,
        label = label,
        shazamUrl = shazamUrl,
        appleMusicUrl = appleMusicUrl,
        spotifyUrl = spotifyUrl,
        isrc = isrc,
        youtubeVideoId = youtubeVideoId,
        recognizedAt = Clock.System.now().toLocalDateTime(TimeZone.UTC),
        liked = youtubeVideoId?.let { SongLikeState.isLiked(it) } == true,
    )

suspend fun resolveYoutubeVideoId(result: RecognitionResult): String? {
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
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Logger.w("MusicRecognition", "Recognized song playback lookup failed", error)
        null
    }
}

private fun SongItem.matchesRecognition(result: RecognitionResult): Boolean {
    fun String.normalized() = lowercase().filter { it.isLetterOrDigit() }

    val title = result.title.normalized()
    val artist = result.artist.normalized()
    return title.isNotEmpty() &&
        artist.isNotEmpty() &&
        this.title.normalized() == title &&
        (
            artists.any { it.name.normalized() == artist } ||
                artists.joinToString("") { it.name }.normalized() == artist
        )
}
