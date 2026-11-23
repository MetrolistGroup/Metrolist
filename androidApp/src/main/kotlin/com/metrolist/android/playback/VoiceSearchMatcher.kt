package com.metrolist.android.playback

import com.metrolist.shared.model.SongItem
import java.text.Normalizer

internal object VoiceSearchMatcher {
    const val STRONG_MATCH_THRESHOLD = 0.60
    private val PUNCTUATION_REGEX = Regex("[^\\p{L}\\p{N}\\s]")
    const val FUZZY_THRESHOLD = 0.85
    private const val UNORDERED_MATCH_SCORE = 0.99
    private const val MAX_FUZZY_MATCH_SCORE = 0.98

    data class ScoredSong(
        val song: SongItem,
        val score: Double,
    )

    fun rankAll(
        query: String,
        candidates: List<SongItem>,
    ): List<ScoredSong> =
        candidates
            .map { ScoredSong(it, score(query, it.title, it.artists.map { artist -> artist.name })) }
            .sortedByDescending { it.score }

    fun score(
        query: String,
        title: String,
        artists: List<String> = emptyList(),
    ): Double {
        val queryLower = normalizedLowercase(query).trim()
        val queryWords = tokenize(queryLower)
        if (queryWords.isEmpty()) return 0.0
        val titleWords = tokenize(normalizedLowercase(title))
        if (titleWords == queryWords) return 1.0

        val queryTokens = queryWords.toSet()
        val titleTokens = titleWords.toSet()
        if (titleTokens == queryTokens) return UNORDERED_MATCH_SCORE
        val isTooGeneric = (queryTokens.size == 1 && queryTokens.first().length < 4) || queryLower.length < 5

        val remaining = stripArtistTokens(queryTokens, artists)

        if (remaining.isEmpty()) return 0.5
        if (titleTokens == remaining) return 0.95
        if (isTooGeneric) return 0.0

        return fuzzyScore(remaining, titleTokens).coerceAtMost(MAX_FUZZY_MATCH_SCORE)
    }

    private fun stripArtistTokens(
        queryTokens: Set<String>,
        artists: List<String>,
    ): Set<String> {
        val remaining = queryTokens.toMutableSet()
        artists.forEach { artist ->
            val artistTokens = tokenize(normalizedLowercase(artist))
            remaining.removeAll { queryToken ->
                if (queryToken.length < 4) {
                    artistTokens.contains(queryToken)
                } else {
                    artistTokens.any { artistToken -> jaroWinkler(queryToken, artistToken) >= 0.85 }
                }
            }
        }
        return remaining
    }

    private fun fuzzyScore(
        queryTokens: Set<String>,
        titleTokens: Set<String>,
    ): Double {
        if (queryTokens.isEmpty() || titleTokens.isEmpty()) return 0.0

        val matchedQuery =
            queryTokens.count { q ->
                titleTokens.any { t -> q == t || jaroWinkler(q, t) >= FUZZY_THRESHOLD }
            }
        val matchedTitle =
            titleTokens.count { t ->
                queryTokens.any { q -> q == t || jaroWinkler(q, t) >= FUZZY_THRESHOLD }
            }

        if (matchedQuery == 0 || matchedTitle == 0) return 0.0

        val titleCoverage = matchedTitle.toDouble() / titleTokens.size
        val queryCoverage = matchedQuery.toDouble() / queryTokens.size
        return if (titleCoverage + queryCoverage > 0) {
            (2.0 * titleCoverage * queryCoverage) / (titleCoverage + queryCoverage) // harmonic mean
        } else {
            0.0
        }
    }

    // Keep decomposed accents from being split off as punctuation.
    private fun normalizedLowercase(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()

    private fun tokenize(text: String): List<String> =
        PUNCTUATION_REGEX
            .replace(text, " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    /** Uses Jaro-Winkler distance for token similarity. */
    internal fun jaroWinkler(
        s1: String,
        s2: String,
    ): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val maxDist = (maxOf(s1.length, s2.length) / 2) - 1
        if (maxDist < 0) return 0.0

        var matches = 0
        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)

        for (i in s1.indices) {
            val start = maxOf(0, i - maxDist)
            val end = minOf(s2.length - 1, i + maxDist)
            for (j in start..end) {
                if (!s2Matches[j] && s1[i] == s2[j]) {
                    s1Matches[i] = true
                    s2Matches[j] = true
                    matches++
                    break
                }
            }
        }
        if (matches == 0) return 0.0

        var transpositions = 0
        var k = 0
        for (i in s1.indices) {
            if (s1Matches[i]) {
                while (!s2Matches[k]) k++
                if (s1[i] != s2[k]) transpositions++
                k++
            }
        }

        val jaro =
            (
                matches.toDouble() / s1.length +
                    matches.toDouble() / s2.length +
                    (matches - transpositions / 2.0) / matches
            ) / 3.0

        val prefix = s1.commonPrefixWith(s2).length.coerceAtMost(4)
        val p = 0.1
        val boostThreshold = 0.7
        return if (jaro > boostThreshold) jaro + (prefix * p * (1.0 - jaro)) else jaro
    }
}
