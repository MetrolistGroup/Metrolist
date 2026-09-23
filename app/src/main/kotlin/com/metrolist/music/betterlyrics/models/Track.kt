package com.metrolist.music.betterlyrics.models

import kotlinx.serialization.Serializable

@Serializable
data class TTMLResponse(
    val ttml: String? = null,
)

@Serializable
data class UnisonResponse(
    val success: Boolean = false,
    val data: UnisonData? = null,
)

@Serializable
data class UnisonData(
    val lyrics: String? = null,
    val format: String? = null,
    val syncType: String? = null,
    val song: String? = null,
    val artist: String? = null,
)

@Serializable
data class SearchResponse(
    val results: List<Track>,
)

@Serializable
data class Track(
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Double,
    val lyrics: Lyrics? = null,
)

@Serializable
data class Lyrics(
    val lines: List<Line>,
)

@Serializable
data class Line(
    val text: String,
    val startTime: Double,
    val words: List<Word>? = null,
)

@Serializable
data class Word(
    val text: String,
    val startTime: Double,
    val endTime: Double,
)