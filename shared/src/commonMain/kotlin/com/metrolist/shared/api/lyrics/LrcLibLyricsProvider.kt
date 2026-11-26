/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.metrolist.shared.api.musicvideo.MusicVideoMatcher
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import kotlinx.serialization.SerialName
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
    val hasWordSync: Boolean = false,
    val lyricsfile: String? = null,
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

        if (best.hasWordSync) {
            best.lyricsfile
                ?.let { runCatching { lyricsfileToMlx(it, title, artist, sourceId = best.id.toString()) }.getOrNull() }
                ?.takeIf(MlxParser::isMlxFormat)
                ?.let { return it }
        }
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
                    .thenByDescending { it.hasWordSync }
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

@Serializable
private data class Lyricsfile(
    val version: String? = null,
    val metadata: LyricsfileMetadata? = null,
    val lines: List<LyricsfileLine>? = null,
)

@Serializable
private data class LyricsfileMetadata(
    val instrumental: Boolean = false,
)

@Serializable
private data class LyricsfileLine(
    val text: String? = null,
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    val words: List<LyricsfileWord>? = null,
)

@Serializable
private data class LyricsfileWord(
    val text: String? = null,
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
)

private val lyricsfileYaml = Yaml(configuration = YamlConfiguration(strictMode = false))

/** Converts LRCLIB's YAML Lyricsfile; words without `end_ms` end where the next word or the line ends. */
internal fun lyricsfileToMlx(
    lyricsfile: String,
    title: String?,
    artist: String?,
    sourceId: String? = null,
): String? {
    val document = lyricsfileYaml.decodeFromString(Lyricsfile.serializer(), lyricsfile)
    // The draft spec forbids reading unknown versions as 1.0; returning null lets the caller use legacy LRC.
    if (document.version != "1.0" || document.metadata?.instrumental == true) return null
    val lines =
        document.lines
            .orEmpty()
            .filter { it.startMs != null }
            .sortedBy { it.startMs }
    val builder = MlxBuilder().meta("LrcLib", sourceId = sourceId).song(title = title, artist = artist)
    var hasLyrics = false
    lines.forEach { line ->
        val start = line.startMs ?: return@forEach
        // The spec allows overlapping lines, so a missing end comes from this line's words, never the next line.
        val lineEnd = line.endMs?.takeIf { it >= start }
        val timedWords = line.words.orEmpty().filter { !it.text.isNullOrEmpty() && it.startMs != null }
        val words = mutableListOf<MlxWord>()
        timedWords.forEachIndexed { wordIndex, word ->
            val raw = word.text!!
            // Spacing may be its own segment or sit before a word; MLX carries it as the previous word's trailing space.
            if (raw.first().isWhitespace() && words.isNotEmpty()) words[words.lastIndex] = words.last().copy(hasTrailingSpace = true)
            if (raw.isBlank()) return@forEachIndexed
            val wordStart = word.startMs!!
            val end =
                word.endMs?.takeIf { it >= wordStart }
                    ?: timedWords.getOrNull(wordIndex + 1)?.startMs?.takeIf { it > wordStart }
                    ?: lineEnd?.takeIf { it > wordStart }
                    ?: (wordStart + 500L)
            words += MlxWord(raw.trim(), wordStart, end, hasTrailingSpace = raw.last().isWhitespace())
        }
        val text = line.text?.trim().orEmpty()
        when {
            words.isNotEmpty() -> {
                builder.line(
                    minOf(start, words.minOf { it.startMs }),
                    maxOf(lineEnd ?: 0L, words.maxOf { it.endMs }),
                    words = words,
                )
            }

            text.isEmpty() -> {
                return@forEach
            }

            lineEnd != null -> {
                builder.line(start, lineEnd, text)
            }

            else -> {
                builder.line(start, text)
            }
        }
        hasLyrics = true
    }
    return if (hasLyrics) builder.build() else null
}
