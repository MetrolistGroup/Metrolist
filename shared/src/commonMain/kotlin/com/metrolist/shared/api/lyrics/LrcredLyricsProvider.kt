/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

@Serializable
data class LrcredSearchResponse(
    val results: List<LrcredResult> = emptyList(),
    val source: String? = null,
    val total: Int? = null,
)

@Serializable
data class LrcredResult(
    val id: String? = null,
    val isrc: String? = null,
    @SerialName("track_name")
    val trackName: String? = null,
    @SerialName("artist_name")
    val artistName: String? = null,
    @SerialName("album_name")
    val albumName: String? = null,
    val duration: Double? = null,
    val lyricsUrl: String? = null,
    @SerialName("timing_type")
    val timingType: String? = null,
)

private const val LRCRED_BASE_URL = "https://lrc.red/api/v1"
private const val MAX_LYRICS_RESPONSE_BYTES = 1024 * 1024

private val NON_ALPHANUMERIC_OR_SPACE = Regex("""[^\p{L}\p{N}\s]""")
private val MULTIPLE_SPACES = Regex("""\s+""")
private val BRACKETED_FEAT =
    Regex("""[\(\[](?:feat\.?|ft\.?|with|prod\.?|bonus track|remaster(?:ed)?)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE)
private val TRAILING_FEAT =
    Regex("""\b(?:feat\.?|ft\.?|with)\s+.*$""", RegexOption.IGNORE_CASE)
private val BRACKETED_MEDIA_TAGS =
    Regex(
        """[\(\[](?:official|music video|video|audio|lyrics?|visualizer|album version|explicit)[^\)\]]*[\)\]]""",
        RegexOption.IGNORE_CASE,
    )
private val ARTIST_DELIMITER =
    Regex("""[,&/]|(?:\b(?:feat\.?|ft\.?|with|x)\b)""", RegexOption.IGNORE_CASE)
private val VIDEO_TAG_REMOVAL =
    Regex(
        """\s*[\(\[](?:Official\s*(?:Music\s*)?Video|Music\s*Video|Official\s*Audio|Audio|Lyric\s*Video|Official\s*Lyric\s*Video)[\)\]]""",
        RegexOption.IGNORE_CASE,
    )

private val MODIFIER_KEYWORDS =
    listOf(
        "slowed",
        "reverb",
        "remix",
        "mixed",
        "dj mix",
        "mashup",
        "cover",
        "tribute",
        "karaoke",
        "instrumental",
        "acoustic",
        "sped up",
        "speed up",
        "live",
        "edit",
        "versión",
        "version",
        "bpm",
        "guitar",
        "piano",
        "lofi",
        "lo-fi",
        "nightcore",
        "club mix",
        "extended mix",
        "radio edit",
    )

class LrcredLyricsProvider(
    private val httpClient: HttpClient,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
        videoId: String? = null,
        isrc: String? = null,
    ): String? {
        try {
            val lyrics = fetchFromApi(title, artist, duration, album, isrc)
            if (lyrics != null) return lyrics

            val cleanTitle = cleanVideoTitle(title)
            if (cleanTitle != title || (duration != null && duration > 0)) {
                val fallback = fetchFromApi(cleanTitle, artist, duration = null, album = album, isrc = isrc)
                if (fallback != null) return fallback
            }

            return null
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            throw error
        }
    }

    private suspend fun fetchFromApi(
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
        isrc: String?,
    ): String? {
        val query = "$title $artist".trim()
        if (query.isEmpty()) return null

        val response =
            httpClient.prepareGet(LRCRED_BASE_URL) {
                expectMissingLyrics()
                parameter("q", query)
            }
        val body = response.readLyricsText(MAX_LYRICS_RESPONSE_BYTES) ?: return null
        val data = json.decodeFromString<LrcredSearchResponse>(body)
        if (data.results.isEmpty()) return null

        val match =
            findBestMatch(
                results = data.results,
                title = title,
                artist = artist,
                durationSec = duration?.let { it / 1000L } ?: 0L,
                album = album,
                isrc = isrc,
            ) ?: return null

        val lyricsUrl = match.lyricsUrl?.takeIf { it.isNotBlank() } ?: return null
        val fullLyricsUrl = if (lyricsUrl.startsWith("/")) "https://lrc.red$lyricsUrl" else lyricsUrl

        val lyricsResponse = httpClient.prepareGet(fullLyricsUrl) { expectMissingLyrics() }
        val ttmlText = lyricsResponse.readLyricsText(MAX_LYRICS_RESPONSE_BYTES) ?: return null
        if (ttmlText.isBlank()) return null

        return parseTtmlToMlx(
            ttml = ttmlText,
            title = match.trackName?.takeIf { it.isNotBlank() } ?: title,
            artist = match.artistName?.takeIf { it.isNotBlank() } ?: artist,
            source = "lrc.red",
            sourceId = match.isrc?.takeIf { it.isNotBlank() },
        ).also { check(it.isNotBlank()) { "Invalid TTML lyrics" } }
    }

    private fun cleanVideoTitle(title: String): String = title.replace(VIDEO_TAG_REMOVAL, "").trim()

    internal companion object {
        fun normalize(str: String?): String {
            if (str.isNullOrBlank()) return ""
            return str
                .lowercase()
                .replace(NON_ALPHANUMERIC_OR_SPACE, " ")
                .replace(MULTIPLE_SPACES, " ")
                .trim()
        }

        fun cleanBaseTitle(str: String?): String {
            if (str.isNullOrBlank()) return ""
            var s = str.lowercase()
            s = s.replace(BRACKETED_FEAT, "")
            s = s.replace(TRAILING_FEAT, "")
            s = s.replace(BRACKETED_MEDIA_TAGS, "")
            return normalize(s)
        }

        fun extractArtists(str: String?): List<String> {
            if (str.isNullOrBlank()) return emptyList()
            return str
                .split(ARTIST_DELIMITER)
                .map { normalize(it) }
                .filter { it.isNotEmpty() }
        }

        fun findBestMatch(
            results: List<LrcredResult>,
            title: String,
            artist: String,
            durationSec: Long,
            album: String?,
            isrc: String?,
        ): LrcredResult? {
            if (results.isEmpty()) return null

            val targetDuration = durationSec.toDouble()

            if (!isrc.isNullOrBlank()) {
                val targetIsrc = isrc.trim().uppercase()
                val isrcMatch = results.firstOrNull { it.isrc?.trim()?.uppercase() == targetIsrc }
                if (isrcMatch != null) return isrcMatch
            }

            val targetFullTitle = normalize(title)
            val targetBaseTitle = cleanBaseTitle(title)
            val targetArtist = normalize(artist)
            val targetAlbum = normalize(album)

            val targetTitleLower = title.lowercase()
            val targetAlbumLower = album?.lowercase().orEmpty()
            val targetModifiers =
                MODIFIER_KEYWORDS.filter { kw ->
                    targetTitleLower.contains(kw) || targetAlbumLower.contains(kw)
                }

            var bestItem: LrcredResult? = null
            var maxScore = Double.NEGATIVE_INFINITY

            for (item in results) {
                val itemDuration = item.duration ?: 0.0
                if (targetDuration > 0 && itemDuration > 0) {
                    if (abs(itemDuration - targetDuration) > 2.0) {
                        continue
                    }
                }

                val itemFullTitle = normalize(item.trackName)
                val itemBaseTitle = cleanBaseTitle(item.trackName)
                val itemArtist = normalize(item.artistName)
                val itemAlbum = normalize(item.albumName)

                var score = 0.0

                if (targetFullTitle.isNotEmpty() && itemFullTitle.isNotEmpty() && targetFullTitle == itemFullTitle) {
                    score += 50.0
                } else if (targetBaseTitle.isNotEmpty() && itemBaseTitle.isNotEmpty()) {
                    if (targetBaseTitle == itemBaseTitle) {
                        score += 35.0
                    } else if (itemBaseTitle.contains(targetBaseTitle) || targetBaseTitle.contains(itemBaseTitle)) {
                        score += 15.0
                    } else {
                        score -= 40.0
                    }
                }

                if (targetArtist.isNotEmpty() && itemArtist.isNotEmpty()) {
                    if (targetArtist == itemArtist) {
                        score += 30.0
                    } else if (itemArtist.contains(targetArtist) || targetArtist.contains(itemArtist)) {
                        score += 15.0
                    } else {
                        val targetArtists = extractArtists(artist)
                        val itemArtists = extractArtists(item.artistName)
                        val hasOverlap =
                            targetArtists.any { ta ->
                                itemArtists.any { ia -> ia == ta || ia.contains(ta) || ta.contains(ia) }
                            }
                        if (hasOverlap) {
                            score += 10.0
                        } else {
                            continue
                        }
                    }
                }

                if (targetAlbum.isNotEmpty() && itemAlbum.isNotEmpty()) {
                    if (targetAlbum == itemAlbum) {
                        score += 35.0
                    } else if (itemAlbum.contains(targetAlbum) || targetAlbum.contains(itemAlbum)) {
                        score += 20.0
                    }

                    val targetIsSingle = targetAlbum.contains("single")
                    val itemIsSingle = itemAlbum.contains("single")
                    if (!targetIsSingle && itemIsSingle) {
                        score -= 15.0
                    } else if (targetIsSingle && itemIsSingle) {
                        score += 10.0
                    }
                }

                if (targetDuration > 0 && itemDuration > 0) {
                    val diff = abs(itemDuration - targetDuration)
                    if (diff == 0.0) {
                        score += 25.0
                    } else if (diff <= 1.0) {
                        score += 20.0
                    } else if (diff <= 2.0) {
                        score += 15.0
                    }
                }

                when (item.timingType?.lowercase()) {
                    "word" -> score += 15.0
                    "line" -> score += 8.0
                    else -> score -= 10.0
                }

                val itemTrackLower = item.trackName?.lowercase().orEmpty()
                val itemAlbumLower = item.albumName?.lowercase().orEmpty()
                val itemModifiers =
                    MODIFIER_KEYWORDS.filter { kw ->
                        itemTrackLower.contains(kw) || itemAlbumLower.contains(kw)
                    }
                for (kw in itemModifiers) {
                    if (kw !in targetModifiers) {
                        score -= 30.0
                    }
                }

                if (score > maxScore) {
                    maxScore = score
                    bestItem = item
                }
            }

            if (bestItem != null && maxScore > 0.0) {
                return bestItem
            }
            return null
        }
    }
}
