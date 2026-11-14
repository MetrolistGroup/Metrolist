/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.AgentType
import com.metrolist.shared.network.ExpectedHttpFailureStatusCodes
import com.metrolist.shared.state.GlobalYouTube
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

private const val MAX_MATCH_INPUT_CHARS = 1_024
private const val MAX_LYRICS_RESPONSE_BYTES = 1024 * 1024
private const val MAX_APPLE_SCRIPT_BYTES = 8 * 1024 * 1024

class PaxsenixLyricsProvider(
    private val httpClient: HttpClient,
) {
    private val tokenManager = AppleTokenManager(httpClient)

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
    ): String? {
        val durationMs = duration ?: 0L
        val queries =
            buildList {
                val cleanTitle = cleanTitle(title)
                val cleanArtist = cleanArtist(artist)
                add("$cleanTitle $cleanArtist")
                add(cleanTitle)
                if (!album.isNullOrBlank()) add("$cleanTitle $cleanArtist $album")
            }

        val resultsById = LinkedHashMap<String, Pair<PaxsenixSearchResult, Double>>()
        for (query in queries) {
            val scored = scoreAndFilterResults(search(query), title, artist, durationMs, album)
            for ((result, score) in scored) {
                val existing = resultsById[result.id]
                if (existing == null || score > existing.second) {
                    resultsById[result.id] = result to score
                }
            }
        }
        val scoredResults = resultsById.values.sortedByDescending { it.second }

        var bestLyrics: String? = null
        var bestQuality = 0
        var bestScore = Double.NEGATIVE_INFINITY
        val topScore = scoredResults.firstOrNull()?.second ?: 0.0

        for ((result, score) in scoredResults.take(10)) {
            if (topScore - score > 15.0 || (bestLyrics != null && score < bestScore)) break
            val lyrics = fetchLyricsForTrack(result.id, title, artist) ?: continue
            val quality = getQuality(lyrics)
            if (bestLyrics == null || score > bestScore || (score == bestScore && quality > bestQuality)) {
                bestQuality = quality
                bestScore = score
                bestLyrics = lyrics
            }
            if (bestQuality == 3 && bestScore == topScore) break
        }

        return bestLyrics
    }

    private suspend fun search(query: String): List<PaxsenixSearchResult> =
        try {
            searchWithToken(tokenManager.getToken(), query, allowUnauthorized = true)
        } catch (e: ClientRequestException) {
            if (e.response.status.value == 401) {
                tokenManager.clearToken()
                searchWithToken(tokenManager.getToken(), query, allowUnauthorized = false)
            } else {
                throw e
            }
        } catch (error: Exception) {
            throw error
        }

    private suspend fun searchWithToken(
        token: String,
        query: String,
        allowUnauthorized: Boolean,
    ): List<PaxsenixSearchResult> {
        val response =
            httpClient.prepareGet("$APPLE_MUSIC_API_BASE/${GlobalYouTube.innerTube.locale.gl.lowercase()}/search") {
                expectMissingLyrics()
                if (allowUnauthorized) {
                    val expectedStatusCodes = attributes.getOrNull(ExpectedHttpFailureStatusCodes).orEmpty()
                    attributes.put(
                        ExpectedHttpFailureStatusCodes,
                        expectedStatusCodes + HttpStatusCode.Unauthorized.value,
                    )
                }
                expectSuccess = true
                parameter("term", query)
                parameter("types", "songs")
                parameter("limit", 25)
                parameter("l", "${GlobalYouTube.innerTube.locale.hl}-${GlobalYouTube.innerTube.locale.gl}")
                parameter("platform", "web")
                parameter("format[resources]", "map")
                parameter("include[songs]", "artists")
                parameter("extend", "artistUrl")
                header("Authorization", "Bearer $token")
                header("Origin", "https://music.apple.com")
                header("Referer", "https://music.apple.com/")
                header("User-Agent", APPLE_USER_AGENT)
                header("Accept", "application/json")
                header("x-apple-renewal", "true")
            }
        val responseBody = response.readLyricsText(MAX_LYRICS_RESPONSE_BYTES) ?: return emptyList()
        val body = appleJson.decodeFromString<AppleMusicSearchResponse>(responseBody)
        val songs = body.results.songs?.data ?: return emptyList()
        return songs.mapNotNull { songData ->
            val detail = body.resources?.songs?.get(songData.id) ?: return@mapNotNull null
            val attr = detail.attributes
            PaxsenixSearchResult(
                id = songData.id,
                trackName = attr.name,
                artistName = attr.artistName,
                albumName = attr.albumName,
                durationMs = attr.durationInMillis,
            )
        }
    }

    private suspend fun fetchLyricsForTrack(
        id: String,
        title: String,
        artist: String,
    ): String? {
        val responseJson =
            httpClient
                .prepareGet("https://lyrics.paxsenix.org/apple-music/lyrics") {
                    expectMissingLyrics()
                    parameter("id", id)
                    header("User-Agent", "Metrolist")
                }.readLyricsText(MAX_LYRICS_RESPONSE_BYTES)
                ?: return null

        return paxsenixLyricsJsonToMlx(responseJson, title, artist)
    }

    private fun scoreAndFilterResults(
        results: List<PaxsenixSearchResult>,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): List<Pair<PaxsenixSearchResult, Double>> {
        val targetTitle = normalizeForMatch(stripVariantTags(title))
        val targetVariantTags = extractVariantTags(title)
        val targetArtists = splitArtistTokens(artist)
        val targetAlbum = album?.takeIf { it.isNotBlank() }?.let { normalizeForMatch(it) }
        val targetIsMixed = mixedRegex.containsMatchIn(title.lowercase())
        val targetIsRemix = remixRegex.containsMatchIn(title.lowercase())

        return results
            .map { result ->
                var score = 0.0

                if (durationMs > 0 && result.durationMs != null) {
                    val delta = abs(result.durationMs - durationMs)
                    score +=
                        when {
                            delta <= 2_000 -> 30.0
                            delta <= 5_000 -> 20.0
                            delta <= 10_000 -> 5.0
                            else -> -30.0
                        }
                }

                val resultTitle = normalizeForMatch(stripVariantTags(result.trackName))
                val titleSim = similarity(targetTitle, resultTitle)
                score +=
                    when {
                        resultTitle == targetTitle -> 90.0
                        resultTitle.contains(targetTitle) || targetTitle.contains(resultTitle) -> 60.0 + titleSim * 20.0
                        titleSim >= 0.75 -> titleSim * 70.0
                        else -> titleSim * 30.0
                    }

                val resultVariantTags = extractVariantTags(result.trackName)
                score +=
                    when {
                        targetVariantTags.isEmpty() && resultVariantTags.isEmpty() -> {
                            0.0
                        }

                        targetVariantTags.isNotEmpty() && resultVariantTags.isNotEmpty() -> {
                            val overlap = targetVariantTags.any { t -> resultVariantTags.any { r -> tokensMatch(t, r) } }
                            if (overlap) 25.0 else -70.0
                        }

                        targetVariantTags.isNotEmpty() -> {
                            -50.0
                        }

                        else -> {
                            -35.0
                        }
                    }

                val resultTitleLower = result.trackName.lowercase()
                if (mixedRegex.containsMatchIn(resultTitleLower) && !targetIsMixed) score -= 60.0
                if (remixRegex.containsMatchIn(resultTitleLower) && !targetIsRemix) score -= 40.0

                val resultArtists = splitArtistTokens(result.artistName)
                val artistOverlap = artistMatchScore(targetArtists, resultArtists)
                score += artistOverlap * 60.0
                if (artistOverlap == 0.0) score -= 40.0

                if (targetAlbum != null && !result.albumName.isNullOrBlank()) {
                    if (similarity(targetAlbum, normalizeForMatch(result.albumName)) >= 0.8) score += 15.0
                }

                result to score
            }.filter { it.second >= 40.0 }
            .sortedByDescending { it.second }
            .take(10)
    }

    private fun extractVariantTags(raw: String): Set<String> =
        variantTagRegex
            .findAll(raw)
            .map { normalizeForMatch(it.value.trim('(', ')', '[', ']')) }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun stripVariantTags(raw: String): String = raw.replace(variantTagRegex, " ")

    private fun normalizeForMatch(input: String): String {
        var text = input.take(MAX_MATCH_INPUT_CHARS).lowercase()
        text = text.replace(bracketRegex, " ")
        text = text.replace(featuredRegex, " ")
        text = text.replace(nonAlphanumericRegex, " ")
        return text.trim().replace(whitespaceRegex, " ")
    }

    private fun splitArtistTokens(artist: String): List<String> =
        artist
            .split(artistSplitRegex)
            .map { normalizeForMatch(it) }
            .filter { it.isNotEmpty() }

    private fun tokensMatch(
        a: String,
        b: String,
    ): Boolean = a == b || a.contains(b) || b.contains(a) || similarity(a, b) >= 0.82

    private fun artistMatchScore(
        target: List<String>,
        result: List<String>,
    ): Double {
        if (target.isEmpty() || result.isEmpty()) return 0.0
        val matchedTarget = target.count { t -> result.any { r -> tokensMatch(t, r) } }
        val matchedResult = result.count { r -> target.any { t -> tokensMatch(t, r) } }
        return maxOf(matchedTarget.toDouble() / target.size, matchedResult.toDouble() / result.size)
    }

    private fun similarity(
        a: String,
        b: String,
    ): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val distance = levenshtein(a, b)
        val maxLen = maxOf(a.length, b.length)
        return 1.0 - distance.toDouble() / maxLen
    }

    private fun levenshtein(
        a: String,
        b: String,
    ): Int {
        val (longer, shorter) = if (a.length >= b.length) a to b else b to a
        var previous = IntArray(shorter.length + 1) { it }
        var current = IntArray(shorter.length + 1)
        for (i in longer.indices) {
            current[0] = i + 1
            for (j in shorter.indices) {
                current[j + 1] =
                    if (longer[i] == shorter[j]) {
                        previous[j]
                    } else {
                        1 + minOf(previous[j], previous[j + 1], current[j])
                    }
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[shorter.length]
    }

    private fun getQuality(lyrics: String): Int {
        val parsed = LyricsUtils.parseLyrics(lyrics)
        return when {
            parsed.any { !it.words.isNullOrEmpty() } -> 3
            parsed.any { it.time > 0L } -> 2
            parsed.isNotEmpty() -> 1
            else -> 0
        }
    }

    private fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        for (pattern in titleCleanupPatterns) cleaned = cleaned.replace(pattern, "")
        return cleaned.trim()
    }

    private fun cleanArtist(artist: String): String {
        var cleaned = artist.trim()
        for (separator in artistSeparators) {
            if (cleaned.contains(separator, ignoreCase = true)) {
                cleaned = cleaned.split(separator, ignoreCase = true, limit = 2)[0]
                break
            }
        }
        return cleaned.trim()
    }

    private class AppleTokenManager(
        private val httpClient: HttpClient,
    ) {
        private var cachedToken: String? = null
        private val mutex = Mutex()

        suspend fun getToken(): String =
            mutex.withLock {
                cachedToken?.let { return it }
                val mainPage =
                    httpClient
                        .prepareGet("https://beta.music.apple.com") { expectMissingLyrics() }
                        .readLyricsText(MAX_LYRICS_RESPONSE_BYTES)
                        ?: error("Apple Music page is too large")
                val indexJs =
                    Regex("""/assets/index~[^/]+\.js""").find(mainPage)?.value
                        ?: error("Could not find Apple Music index JS")
                val js =
                    httpClient
                        .prepareGet("https://beta.music.apple.com$indexJs") { expectMissingLyrics() }
                        .readLyricsText(MAX_APPLE_SCRIPT_BYTES)
                        ?: error("Apple Music script is too large")
                val token =
                    Regex("""eyJ[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+""").find(js)?.value
                        ?: error("Could not find Apple Music token")
                cachedToken = token
                token
            }

        fun clearToken() {
            cachedToken = null
        }
    }

    private companion object {
        val mixedRegex = Regex("""\bmixed\b""")
        val remixRegex = Regex("""\bremix\b""")
        val bracketRegex = Regex("""\(.*?\)|\[.*?]|【.*?】""")
        val featuredRegex = Regex("""\bfeat\.?.*$|\bft\.?.*$""")
        val nonAlphanumericRegex = Regex("""[^a-z0-9]+""")
        val whitespaceRegex = Regex("""\s+""")
        val artistSplitRegex = Regex("""(?i),|&|\+|/|\bx\b|\bvs\.?\b|\band\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b""")
        const val APPLE_MUSIC_API_BASE = "https://amp-api.music.apple.com/v1/catalog"
        const val APPLE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:95.0) Gecko/20100101 Firefox/95.0"

        val titleCleanupPatterns =
            listOf(
                Regex(
                    """\s*\(.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\)""",
                    RegexOption.IGNORE_CASE,
                ),
                Regex(
                    """\s*\[.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?]""",
                    RegexOption.IGNORE_CASE,
                ),
                Regex("""\s*【.*?】"""),
                Regex("""\s*\|.*$"""),
                Regex("""\s*-\s*(official|video|audio|lyrics|lyric|visualizer).*$""", RegexOption.IGNORE_CASE),
                Regex("""\s*\(feat\..*?\)""", RegexOption.IGNORE_CASE),
                Regex("""\s*\(ft\..*?\)""", RegexOption.IGNORE_CASE),
                Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE),
                Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE),
                Regex("""\s*\([^)]*\d{4}[^)]*\)""", RegexOption.IGNORE_CASE),
            )

        val artistSeparators = listOf(" & ", " and ", ", ", " x ", " X ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with ")

        @Suppress("ktlint:standard:max-line-length")
        val variantTagRegex =
            Regex(
                """\((?:[^()]*\b(?:ver\.?|version|remix|acoustic|live|instrumental|extended|radio\s*edit|remaster(?:ed)?|clean|explicit|english|japanese|korean|chinese|mandarin|cantonese|spanish|thai|vietnamese|german|french|italian|portuguese|russian|hindi|indonesian|malay|arabic|filipino|tagalog)\b[^()]*)\)""" +
                    """|\[(?:[^\[\]]*\b(?:ver\.?|version|remix|acoustic|live|instrumental|extended|radio\s*edit|remaster(?:ed)?|clean|explicit|english|japanese|korean|chinese|mandarin|cantonese|spanish|thai|vietnamese|german|french|italian|portuguese|russian|hindi|indonesian|malay|arabic|filipino|tagalog)\b[^\[\]]*)]""",
                RegexOption.IGNORE_CASE,
            )
    }
}

@Serializable
private data class PaxsenixSearchResult(
    val id: String,
    val trackName: String = "",
    val artistName: String = "",
    val albumName: String? = null,
    val durationMs: Long? = null,
)

@Serializable
private data class PaxsenixLyricsResponse(
    val type: String? = null,
    val content: List<PaxsenixLyricsContent> = emptyList(),
    val elrc: String? = null,
    val elrcMultiPerson: String? = null,
    val ttmlContent: String? = null,
    val plain: String? = null,
)

@Serializable
private data class PaxsenixLyricsContent(
    val timestamp: Long = 0,
    val endtime: Long = 0,
    val duration: Long = 0,
    val structure: String? = null,
    val text: List<PaxsenixLyricText> = emptyList(),
    val background: Boolean = false,
    val backgroundText: List<PaxsenixLyricText> = emptyList(),
    val oppositeTurn: Boolean = false,
)

@Serializable
private data class PaxsenixLyricText(
    val text: String,
    val timestamp: Long,
    val endtime: Long,
    val duration: Long = 0,
    val part: Boolean = false,
)

@Serializable
private data class AppleMusicSearchResponse(
    val results: AppleMusicResults,
    val resources: AppleMusicResources? = null,
)

@Serializable
private data class AppleMusicResults(
    val songs: AppleMusicSongsResult? = null,
)

@Serializable
private data class AppleMusicSongsResult(
    val data: List<AppleMusicSongData> = emptyList(),
)

@Serializable
private data class AppleMusicSongData(
    val id: String,
)

@Serializable
private data class AppleMusicResources(
    val songs: Map<String, AppleMusicSongDetail>? = null,
)

@Serializable
private data class AppleMusicSongDetail(
    val attributes: AppleMusicSongAttributes,
)

@Serializable
private data class AppleMusicSongAttributes(
    val name: String,
    val artistName: String,
    val albumName: String? = null,
    val durationInMillis: Long? = null,
)

private val paxsenixLineRegex = Regex("""^\[(\d{1,2}:\d{2}[.:]\d{2,3})](?:(v\d+):\s*)?(.*)$""")
private val paxsenixBgRegex = Regex("""^\[bg:\s*(.*)]$""")
private val paxsenixPackedWordRegex = Regex("""<([^:|<>]+):([0-9.]+):([0-9.]+)>""")
private val appleJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

internal fun paxsenixLyricsJsonToMlx(
    jsonText: String,
    title: String,
    artist: String,
): String? {
    val response = appleJson.decodeFromString<PaxsenixLyricsResponse>(jsonText)

    if (response.type.equals("None", ignoreCase = true)) {
        response.plain?.takeIf { it.isNotBlank() }?.let { return plainToMlx(it, title, artist) }
    }
    response.ttmlContent?.takeIf { it.isNotBlank() }?.let { ttml ->
        parseTtmlToMlx(ttml, title, artist, "Paxsenix").takeIf { it.isNotBlank() }?.let { return it }
    }
    response.elrcMultiPerson?.takeIf { it.isNotBlank() }?.let { elrc ->
        enhancedLrcToMlx(elrc, title, artist, "Paxsenix")?.let { return it }
    }
    response.elrc?.takeIf { it.isNotBlank() }?.let { elrc ->
        lrcToMlx(elrc, title, artist)?.let { return it }
    }
    response.plain?.takeIf { it.isNotBlank() }?.let { plain ->
        return plainToMlx(plain, title, artist)
    }
    return contentToMlx(response.content, title, artist)
}

private fun plainToMlx(
    plain: String,
    title: String?,
    artist: String?,
): String =
    MlxBuilder()
        .meta("Paxsenix")
        .song(title = title, artist = artist)
        .apply {
            plain.lines().forEach { line -> if (line.isNotBlank()) unsyncedLine(line.trim()) }
        }.build()

private fun contentToMlx(
    content: List<PaxsenixLyricsContent>,
    title: String,
    artist: String,
): String? {
    if (content.isEmpty()) return null
    val builder =
        MlxBuilder()
            .meta("Paxsenix")
            .song(title = title, artist = artist)
            .agent("v1", AgentType.PERSON)
            .agent("v2", AgentType.PERSON)
    var hasLyrics = false
    var index = 0
    while (index < content.size) {
        val line = content[index]
        val next = content.getOrNull(index + 1)
        val words = line.text.toMlxWords()
        val lineEnd =
            line.endtime.takeIf { it > line.timestamp }
                ?: (line.timestamp + line.duration).takeIf { it > line.timestamp }
                ?: line.timestamp
        val nextWords = next?.text?.toMlxWords().orEmpty()
        val nextEnd =
            next?.let {
                it.endtime.takeIf { end -> end > it.timestamp }
                    ?: (it.timestamp + it.duration).takeIf { end -> end > it.timestamp }
                    ?: it.timestamp
            } ?: 0L
        val nextIsInlineBackground =
            next != null &&
                !line.background &&
                line.backgroundText.isEmpty() &&
                words.isNotEmpty() &&
                nextWords.isNotEmpty() &&
                (
                    next.background ||
                        (
                            next.backgroundText.isEmpty() &&
                                line.structure == next.structure &&
                                line.oppositeTurn == next.oppositeTurn &&
                                next.timestamp < lineEnd &&
                                nextEnd > lineEnd
                        )
                )

        val bgWords = if (nextIsInlineBackground) nextWords else line.backgroundText.toMlxWords()
        val text = words.toPlainText()
        val start = line.timestamp
        val end = maxOf(lineEnd, bgWords.maxOfOrNull { it.endMs } ?: lineEnd)
        val agent = if (line.oppositeTurn) "v2" else "v1"
        when {
            words.isNotEmpty() && end > start -> builder.line(start, end, agent, words, bgWords.takeIf { it.isNotEmpty() })
            text.isNotBlank() && end > start -> builder.line(start, end, text, agent)
            text.isNotBlank() -> builder.line(start, text)
        }
        hasLyrics = hasLyrics || text.isNotBlank() || words.isNotEmpty()
        index += if (nextIsInlineBackground) 2 else 1
    }
    return builder.build().takeIf { hasLyrics }
}

private fun List<PaxsenixLyricText>.toMlxWords(): List<MlxWord> =
    mapIndexedNotNull { index, word ->
        val rawText = word.text.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
        val hasSpace = rawText.endsWith(' ')
        MlxWord(
            text = if (hasSpace) rawText.dropLast(1) else rawText,
            startMs = word.timestamp,
            endMs = word.endtime,
            hasTrailingSpace = hasSpace,
        )
    }

private fun List<MlxWord>.toPlainText(): String = joinToString("") { word -> word.text + if (word.hasTrailingSpace) " " else "" }.trim()

internal fun enhancedLrcToMlx(
    elrc: String,
    title: String,
    artist: String,
    source: String,
): String? {
    val lines = elrc.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return null
    val builder =
        MlxBuilder()
            .meta(source)
            .song(title = title, artist = artist)
            .agent("v1", AgentType.PERSON)
            .agent("v2", AgentType.PERSON)
    val sourceOffsetMs = lrcOffsetMs(elrc)
    if (sourceOffsetMs != 0L) builder.unsyncedLine("[offset:$sourceOffsetMs]")
    var hasLyrics = false
    var pendingLine: MatchResult? = null
    var pendingBackground: String? = null

    fun appendPendingLine(nextStartMs: Long? = null) {
        val match = pendingLine ?: return
        val start = MlxParser.tsToMs(match.groupValues[1])
        val agent = match.groupValues[2].takeIf { it.isNotBlank() }
        val content = match.groupValues[3]
        val mainWords = parsePaxsenixWords(content, nextStartMs)
        val bgWords = pendingBackground?.let { parsePaxsenixWords(it, nextStartMs) }
        val text = stripWordMarkers(content).trim()
        val end = listOfNotNull(mainWords.lastOrNull()?.endMs, bgWords?.lastOrNull()?.endMs).maxOrNull()
        if (mainWords.isNotEmpty() && end != null) {
            builder.line(start, end, agent, mainWords, bgWords?.takeIf { it.isNotEmpty() })
        } else if (text.isNotBlank()) {
            builder.line(start, text)
        }
        hasLyrics = hasLyrics || text.isNotBlank() || mainWords.isNotEmpty()
        pendingLine = null
        pendingBackground = null
    }

    lines.forEach { raw ->
        val trimmed = raw.trim()
        val bgMatch = paxsenixBgRegex.matchEntire(trimmed)
        if (bgMatch != null) {
            pendingBackground = bgMatch.groupValues[1]
        } else {
            val match = paxsenixLineRegex.matchEntire(trimmed) ?: return@forEach
            appendPendingLine(MlxParser.tsToMs(match.groupValues[1]))
            pendingLine = match
        }
    }
    appendPendingLine()

    return builder.build().takeIf { hasLyrics }
}

private fun parsePaxsenixWords(
    content: String,
    nextStartMs: Long?,
): List<MlxWord> {
    val timestampMatches = Regex("""<(\d{1,2}:\d{2}[.:]\d{2,3})>""").findAll(content).toList()
    if (timestampMatches.isNotEmpty()) {
        return timestampMatches.mapIndexedNotNull { index, match ->
            val start = MlxParser.tsToMs(match.groupValues[1])
            val segmentStart = match.range.last + 1
            val segmentEnd = timestampMatches.getOrNull(index + 1)?.range?.first ?: content.length
            val raw = content.substring(segmentStart, segmentEnd)
            val hasSpace = raw.endsWith(' ')
            val text = if (hasSpace) raw.dropLast(1) else raw
            if (text.isEmpty() && !hasSpace) return@mapIndexedNotNull null
            val end =
                timestampMatches
                    .getOrNull(index + 1)
                    ?.groupValues
                    ?.get(1)
                    ?.let { MlxParser.tsToMs(it) } ?: nextStartMs?.takeIf { it > start } ?: (start + 500L)
            MlxWord(text, start, end, hasSpace)
        }
    }

    return paxsenixPackedWordRegex
        .findAll(content)
        .mapNotNull { match ->
            val text = match.groupValues[1]
            val start = (match.groupValues[2].toDoubleOrNull()?.times(1000))?.toLong() ?: return@mapNotNull null
            val end = (match.groupValues[3].toDoubleOrNull()?.times(1000))?.toLong() ?: return@mapNotNull null
            val hasSpace = text.endsWith(' ')
            MlxWord(if (hasSpace) text.dropLast(1) else text, start, end, hasSpace)
        }.toList()
}

private fun stripWordMarkers(content: String): String =
    content
        .replace(Regex("""<\d{1,2}:\d{2}[.:]\d{2,3}>"""), "")
        .replace(Regex("""<[^:|<>]+:[0-9.]+:[0-9.]+>"""), "")
