/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.api.musicvideo.MusicVideoMatcher
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

@Serializable
data class LrcLibResponse(
    val id: Long,
    val name: String,
    val artistName: String,
    val albumName: String? = null,
    val duration: Float,
    val instrumental: Boolean,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

class LrcLibLyricsProvider(
    private val httpClient: HttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://lrclib.net/api"

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
    ): String? {
        val knownDuration = duration?.takeIf { it > 0 }
        val trimmedAlbum = album?.trim()?.takeIf { it.isNotEmpty() }
        val primary = searchBestCandidate(title, artist, knownDuration, trimmedAlbum)
        val best =
            primary
                ?: run {
                    val fallbackTitle = stripFeatAndDecorators(title)
                    if (trimmedAlbum != null || fallbackTitle != title) {
                        searchBestCandidate(fallbackTitle, artist, knownDuration, album = null)
                    } else {
                        null
                    }
                }
                ?: return null

        val synced = best.syncedLyrics?.takeIf { it.isNotBlank() }
        return if (synced != null) {
            lrcToMlx(synced, title, artist, source = "LrcLib", sourceId = best.id.toString())
        } else {
            val plain = best.plainLyrics?.takeIf { it.isNotBlank() } ?: return null
            MlxBuilder()
                .meta("LrcLib", sourceId = best.id.toString())
                .song(title = title, artist = artist)
                .apply { plain.lines().forEach { line -> if (line.isNotBlank()) unsyncedLine(line.trim()) } }
                .build()
        }
    }

    private suspend fun searchBestCandidate(
        queryTitle: String,
        artist: String,
        knownDuration: Long?,
        album: String?,
    ): LrcLibResponse? {
        val responseText =
            httpClient
                .prepareGet("$baseUrl/search") {
                    expectMissingLyrics()
                    parameter("track_name", queryTitle)
                    if (artist.isNotBlank()) parameter("artist_name", artist)
                    if (album != null) parameter("album_name", album)
                    if (knownDuration != null) parameter("duration", knownDuration / 1000)
                }.readLyricsText() ?: return null

        val response = json.decodeFromString<List<LrcLibResponse>>(responseText)
        val normTitle = MusicVideoMatcher.normalizeTitle(queryTitle)
        val coreTitle = MusicVideoMatcher.cleanCoreTitle(stripFeatAndDecorators(queryTitle))
        val normArtist = MusicVideoMatcher.normalizeName(artist)
        val artistTokens = splitArtistTokens(artist)
        val normAlbum = album?.let(MusicVideoMatcher::normalizeTitle).orEmpty()

        return response
            .filter { candidate ->
                !candidate.instrumental &&
                    titleMatchesCandidate(queryTitle, normTitle, coreTitle, candidate.name) &&
                    artistMatchesCandidate(normArtist, artistTokens, candidate.artistName) &&
                    (knownDuration == null || abs(candidate.duration * 1000 - knownDuration) <= 10_000) &&
                    (!candidate.syncedLyrics.isNullOrBlank() || !candidate.plainLyrics.isNullOrBlank())
            }.sortedWith(
                compareByDescending<LrcLibResponse> { candidate ->
                    MusicVideoMatcher.normalizeTitle(candidate.name) == normTitle
                }.thenByDescending { candidate ->
                    normArtist.isEmpty() || MusicVideoMatcher.normalizeName(candidate.artistName) == normArtist
                }.thenByDescending { candidate ->
                    normAlbum.isNotEmpty() &&
                        MusicVideoMatcher.normalizeTitle(candidate.albumName.orEmpty()) == normAlbum
                }.thenBy { candidate -> knownDuration?.let { abs(candidate.duration * 1000 - it) } ?: 0f }
                    .thenByDescending { !it.syncedLyrics.isNullOrBlank() },
            ).firstOrNull()
    }

    private fun titleMatchesCandidate(
        rawTitle: String,
        normTitle: String,
        coreTitle: String,
        candidateTitle: String,
    ): Boolean {
        val normCandidate = MusicVideoMatcher.normalizeTitle(candidateTitle)
        if (normCandidate.isEmpty()) return false
        if (normCandidate == normTitle) return true
        if (MusicVideoMatcher.isDisqualifiedVariant(rawTitle, candidateTitle)) return false
        val coreCandidate = MusicVideoMatcher.cleanCoreTitle(stripFeatAndDecorators(candidateTitle))
        return coreTitle.isNotEmpty() && coreCandidate == coreTitle
    }

    private fun artistMatchesCandidate(
        normArtist: String,
        artistTokens: List<String>,
        candidateArtist: String,
    ): Boolean {
        if (normArtist.isEmpty()) return true
        val normCandidate = MusicVideoMatcher.normalizeName(candidateArtist)
        if (normCandidate == normArtist) return true
        val candidateTokens = splitArtistTokens(candidateArtist)
        if (artistTokens.isEmpty() || candidateTokens.isEmpty()) return false
        return artistTokens.any { target ->
            candidateTokens.any { candidate ->
                target == candidate ||
                    (target.length >= 4 && candidate.length >= 4 && (target.contains(candidate) || candidate.contains(target)))
            }
        }
    }

    private fun splitArtistTokens(raw: String): List<String> =
        raw
            .split(ARTIST_DELIMITER_REGEX)
            .map(MusicVideoMatcher::normalizeName)
            .filter { it.isNotEmpty() }

    private fun stripFeatAndDecorators(raw: String): String =
        raw
            .replace(BRACKETED_FEAT_REGEX, " ")
            .replace(TRAILING_FEAT_REGEX, " ")
            .trim()
            .ifEmpty { raw }

    private companion object {
        val ARTIST_DELIMITER_REGEX = Regex("""(?i)[,&/+]|\b(?:feat\.?|ft\.?|featuring|with|and|x)\b""")
        val BRACKETED_FEAT_REGEX = Regex("""[\(\[](?:feat\.?|ft\.?|featuring|with)\b[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE)
        val TRAILING_FEAT_REGEX = Regex("""\s+\b(?:feat\.?|ft\.?|featuring)\b.*$""", RegexOption.IGNORE_CASE)
    }
}
