package com.metrolist.shared.recognition

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RecognitionPayload(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val coverArtUrl: String?,
    val coverArtHqUrl: String?,
    val genre: String?,
    val releaseDate: String?,
    val label: String?,
    val shazamUrl: String?,
    val appleMusicUrl: String?,
    val spotifyUrl: String?,
    val isrc: String?,
    val youtubeVideoId: String?,
    val recognizedAtEpochMillis: Long,
) {
    fun toResult(): RecognitionResult =
        RecognitionResult(
            trackId = trackId,
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
        )

    companion object {
        fun fromResult(
            result: RecognitionResult,
            recognizedAtEpochMillis: Long,
        ): RecognitionPayload =
            RecognitionPayload(
                trackId = result.trackId,
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
                recognizedAtEpochMillis = recognizedAtEpochMillis,
            )
    }
}

object RecognitionPayloadCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(payload: RecognitionPayload): String = json.encodeToString(payload)

    fun decode(value: String): RecognitionPayload = json.decodeFromString(value)

    fun encodeSnapshot(snapshot: RecognitionWidgetSnapshot): String = json.encodeToString(snapshot)
}
