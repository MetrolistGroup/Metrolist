package com.metrolist.paxsenix.models

import kotlinx.serialization.Serializable

typealias SearchResponse = List<SearchResult>

@Serializable
data class SearchResult(
    val id: String,
    val songName: String? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Int? = null,
    val artwork: String? = null
) {
    val displayName: String get() = trackName ?: songName ?: ""
    val displayArtist: String get() = artistName ?: ""
}

@Serializable
data class LyricsContent(
    val timestamp: Long,
    val endtime: Long,
    val duration: Long,
    val structure: String? = null,
    val text: List<LyricText> = emptyList(),
    val background: Boolean = false,
    val backgroundText: List<LyricText> = emptyList(),
    val oppositeTurn: Boolean = false
)

@Serializable
data class LyricText(
    val text: String,
    val timestamp: Long,
    val endtime: Long,
    val duration: Long,
    val part: Boolean = false
)

@Serializable
data class LyricsMetadata(
    val songwriters: List<String> = emptyList()
)

@Serializable
data class LyricsResponse(
    val type: String? = null,
    val metadata: LyricsMetadata? = null,
    val content: List<LyricsContent> = emptyList(),
    val elrc: String? = null,
    val elrcMultiPerson: String? = null,
    val ttmlContent: String? = null,
    val plain: String? = null
)

// Apple Music API search response models

@Serializable
data class AppleMusicSearchResponse(
    val results: AppleMusicResults,
    val resources: AppleMusicResources? = null
)

@Serializable
data class AppleMusicResults(
    val songs: AppleMusicSongsResult? = null
)

@Serializable
data class AppleMusicSongsResult(
    val data: List<AppleMusicSongData> = emptyList()
)

@Serializable
data class AppleMusicSongData(
    val id: String,
    val type: String
)

@Serializable
data class AppleMusicResources(
    val songs: Map<String, AppleMusicSongDetail>? = null
)

@Serializable
data class AppleMusicSongDetail(
    val attributes: AppleMusicSongAttributes
)

@Serializable
data class AppleMusicSongAttributes(
    val name: String,
    val artistName: String,
    val albumName: String? = null,
    val artwork: AppleMusicArtwork? = null,
    val url: String? = null,
    val durationInMillis: Long? = null
)

@Serializable
data class AppleMusicArtwork(
    val url: String
)
