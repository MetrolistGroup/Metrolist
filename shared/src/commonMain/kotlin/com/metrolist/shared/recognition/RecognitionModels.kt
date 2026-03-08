package com.metrolist.shared.recognition

import com.metrolist.shared.model.SongItem

data class RecognitionResult(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val coverArtUrl: String? = null,
    val coverArtHqUrl: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val label: String? = null,
    val shazamUrl: String? = null,
    val appleMusicUrl: String? = null,
    val spotifyUrl: String? = null,
    val isrc: String? = null,
    val youtubeVideoId: String? = null,
)

fun RecognitionResult.toSongItem(): SongItem? = recognitionSongItem(youtubeVideoId, title, artist, album, coverArtUrl, coverArtHqUrl, genre)

sealed interface RecognitionStatus {
    data object Ready : RecognitionStatus

    data object Listening : RecognitionStatus

    data object Processing : RecognitionStatus

    data class Success(
        val result: RecognitionResult,
        val historySaved: Boolean = true,
    ) : RecognitionStatus

    data class NoMatch(
        val message: String = "",
    ) : RecognitionStatus

    data class Error(
        val message: String,
    ) : RecognitionStatus
}
