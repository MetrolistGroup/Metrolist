/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.MlxDocument
import com.metrolist.shared.model.WordTimestamp
import kotlin.math.abs
import kotlin.math.roundToLong

object MusicVideoLyricsRetimer {
    private const val MAX_ALIGNMENT_TOKENS = 10_000
    private const val MAX_ALIGNMENT_CELLS = 4_000_000L

    data class OffsetBlock(
        val audioStartMs: Long,
        val audioEndMs: Long,
        val offsetMs: Long,
        val matchCount: Int,
    )

    data class RetimedLyricsResult(
        val document: MlxDocument,
        val score: Float,
        val blocks: List<OffsetBlock>,
    )

    fun retimeLyricsWithPositionMap(
        lyrics: MlxDocument,
        positionMap: CounterpartPositionMap,
    ): MlxDocument {
        if (lyrics.meta.ignoreRetiming || positionMap.segments.isEmpty() || lyricsTimingKind(lyrics.lines) == LyricsTimingKind.UNSYNCED) {
            return lyrics
        }
        val retimedLines =
            lyrics.lines.map { entry ->
                val newTime = positionMap.mapPosition(entry.time)
                val newEndTime = entry.endTime?.let(positionMap::mapPosition)
                val newWords = entry.words?.map { it.retime(positionMap) }
                val newRomanWords = entry.romanizedWords?.map { it.retime(positionMap) }
                entry.copy(
                    time = newTime.coerceAtLeast(0L),
                    endTime = newEndTime?.coerceAtLeast(newTime.coerceAtLeast(0L) + 1L),
                    words = newWords,
                    romanizedWords = newRomanWords,
                )
            }
        val retimedSongParts =
            lyrics.songParts.map { part ->
                val newStart = positionMap.mapPosition(part.startMs).coerceAtLeast(0L)
                val newEnd = part.endMs?.let { positionMap.mapPosition(it).coerceAtLeast(newStart + 1L) }
                part.copy(startMs = newStart, endMs = newEnd)
            }
        return lyrics.copy(lines = retimedLines, songParts = retimedSongParts)
    }

    fun retimeLyrics(
        lyrics: MlxDocument,
        cues: List<YouTubeTranscriptCue>,
    ): RetimedLyricsResult? {
        if (lyrics.meta.ignoreRetiming || cues.isEmpty()) {
            return null
        }

        val lyricTokens = tokenizeLyrics(lyrics.lines)
        val subtitleTokens = tokenizeSubtitles(cues)

        if (
            lyricTokens.isEmpty() || subtitleTokens.isEmpty() ||
            lyricTokens.size > MAX_ALIGNMENT_TOKENS || subtitleTokens.size > MAX_ALIGNMENT_TOKENS ||
            lyricTokens.size.toLong() * subtitleTokens.size > MAX_ALIGNMENT_CELLS
        ) {
            return null
        }

        val matches = align(lyricTokens, subtitleTokens)
        if (matches.isEmpty()) {
            return null
        }

        val blocks = detectBlocks(matches)
        if (blocks.isEmpty()) {
            return null
        }

        val score = matches.size.toFloat() / lyricTokens.size.coerceAtLeast(1).toFloat()
        val retimed = applyOffsets(lyrics, matches, blocks)

        return RetimedLyricsResult(
            document = retimed,
            score = score,
            blocks = blocks,
        )
    }

    private data class LyricToken(
        val normalized: String,
        val lineIdx: Int,
        val wordIdx: Int,
        val audioStartMs: Long,
        val audioEndMs: Long,
        val isLineStart: Boolean = false,
    )

    private data class SubtitleToken(
        val normalized: String,
        val videoStartMs: Long,
        val videoEndMs: Long,
        val isCueStart: Boolean = false,
    )

    private data class MatchedPair(
        val audioStartMs: Long,
        val videoStartMs: Long,
        val isAnchor: Boolean = false,
    )

    private fun tokenizeLyrics(lines: List<LyricsEntry>): List<LyricToken> {
        val tokens = mutableListOf<LyricToken>()
        val hasTiming = lines.any { it.time > 0L || (it.endTime ?: 0L) > 0L }
        lines.forEachIndexed { lineIdx, entry ->
            val words = entry.words
            val lineStartTokenCount = tokens.size
            if (!words.isNullOrEmpty()) {
                val mainWords = words.filter { !it.isBackground }
                if (mainWords.isNotEmpty()) {
                    val hasAnyTrailingSpace = mainWords.any { it.hasTrailingSpace || it.text.endsWith(' ') }
                    if (hasAnyTrailingSpace) {
                        var currentWord = StringBuilder()
                        var wordStartMs = 0L
                        var wordEndMs: Long
                        var firstWordIdx = 0

                        mainWords.forEachIndexed { wordIdx, w ->
                            if (currentWord.isEmpty()) {
                                wordStartMs = (w.startTime * 1000).toLong()
                                firstWordIdx = wordIdx
                            }
                            currentWord.append(w.text)
                            wordEndMs = (w.endTime * 1000).toLong()

                            val isEndOfWord = w.hasTrailingSpace || w.text.endsWith(' ') || wordIdx == mainWords.lastIndex
                            if (isEndOfWord) {
                                val cleanWord = currentWord.toString().trim()
                                if (cleanWord.isNotEmpty()) {
                                    val subWords = cleanWord.split(WHITESPACE_REGEX)
                                    val subWordStep = if (subWords.size > 1) (wordEndMs - wordStartMs) / subWords.size else 0L
                                    subWords.forEachIndexed { subIdx, subWord ->
                                        val norm = normalizeWord(subWord)
                                        if (norm.isNotBlank()) {
                                            val sStart = if (subWords.size > 1) wordStartMs + subIdx * subWordStep else wordStartMs
                                            val sEnd = if (subWords.size > 1) sStart + subWordStep else wordEndMs
                                            val isFirst = tokens.size == lineStartTokenCount
                                            tokens.add(
                                                LyricToken(
                                                    normalized = norm,
                                                    lineIdx = lineIdx,
                                                    wordIdx = firstWordIdx,
                                                    audioStartMs = sStart,
                                                    audioEndMs = sEnd,
                                                    isLineStart = isFirst,
                                                ),
                                            )
                                        }
                                    }
                                }
                                currentWord.clear()
                            }
                        }
                    } else {
                        mainWords.forEachIndexed { wordIdx, w ->
                            val norm = normalizeWord(w.text)
                            if (norm.isNotBlank()) {
                                val isFirst = tokens.size == lineStartTokenCount
                                tokens.add(
                                    LyricToken(
                                        normalized = norm,
                                        lineIdx = lineIdx,
                                        wordIdx = wordIdx,
                                        audioStartMs = (w.startTime * 1000).toLong(),
                                        audioEndMs = (w.endTime * 1000).toLong(),
                                        isLineStart = isFirst,
                                    ),
                                )
                            }
                        }
                    }
                }
            } else if (entry.time >= 0L && hasTiming && entry.text.isNotBlank()) {
                val trimmed = entry.text.trim()
                val isParenthetical = trimmed.startsWith("(") && trimmed.endsWith(")")
                if (!isParenthetical) {
                    val lineEnd = entry.endTime ?: (entry.time + 5000L)
                    val lineWords =
                        trimmed
                            .split(WHITESPACE_REGEX)
                            .map { normalizeWord(it) }
                            .filter { it.isNotBlank() }
                    val lineDuration = (lineEnd - entry.time).coerceAtLeast(1L)
                    val wordStep = lineDuration / lineWords.size.coerceAtLeast(1)
                    lineWords.forEachIndexed { wi, norm ->
                        val wordStart = entry.time + wi * wordStep
                        tokens.add(
                            LyricToken(
                                normalized = norm,
                                lineIdx = lineIdx,
                                wordIdx = -1,
                                audioStartMs = wordStart,
                                audioEndMs = wordStart + wordStep,
                                isLineStart = wi == 0,
                            ),
                        )
                    }
                }
            }
        }
        tokens.sortBy { it.audioStartMs }
        return tokens
    }

    private fun tokenizeSubtitles(cues: List<YouTubeTranscriptCue>): List<SubtitleToken> {
        val tokens = mutableListOf<SubtitleToken>()
        cues.forEach { cue ->
            val cueWords = cue.words
            if (!cueWords.isNullOrEmpty()) {
                cueWords.forEachIndexed { wi, w ->
                    val cleanText = NOISE_TAG_REGEX.replace(w.text, "")
                    val norm = normalizeWord(cleanText)
                    if (norm.isNotBlank() && !isNoiseWord(norm)) {
                        val dur = if (w.durationMs > 0L) w.durationMs else 300L
                        tokens.add(
                            SubtitleToken(
                                normalized = norm,
                                videoStartMs = w.startMs,
                                videoEndMs = w.startMs + dur,
                                isCueStart = wi == 0,
                            ),
                        )
                    }
                }
            } else {
                var text = cue.text
                text = NOISE_TAG_REGEX.replace(text, "")
                text = text.replace('\n', ' ')
                val cueDurationMs = cue.durationMs.coerceAtLeast(1L)
                val cueEnd = cue.startMs + cueDurationMs
                val words =
                    text
                        .split(WHITESPACE_REGEX)
                        .map { normalizeWord(it) }
                        .filter { it.isNotBlank() && !isNoiseWord(it) }
                if (words.isNotEmpty()) {
                    val wordStep = cueDurationMs / words.size.coerceAtLeast(1)
                    words.forEachIndexed { wi, norm ->
                        val wordStart = cue.startMs + wi * wordStep
                        tokens.add(
                            SubtitleToken(
                                normalized = norm,
                                videoStartMs = wordStart,
                                videoEndMs = (wordStart + wordStep).coerceAtMost(cueEnd),
                                isCueStart = wi == 0,
                            ),
                        )
                    }
                }
            }
        }
        tokens.sortBy { it.videoStartMs }
        return deduplicateSubtitleTokens(tokens)
    }

    private fun deduplicateSubtitleTokens(tokens: List<SubtitleToken>): List<SubtitleToken> {
        if (tokens.isEmpty()) return emptyList()
        val result = mutableListOf<SubtitleToken>()
        for (token in tokens) {
            val prev = result.lastOrNull()
            if (prev != null && prev.normalized == token.normalized && abs(token.videoStartMs - prev.videoStartMs) <= DUP_TOKEN_WINDOW_MS) {
                continue
            }
            result.add(token)
        }
        return result
    }

    private fun align(
        lyricTokens: List<LyricToken>,
        subtitleTokens: List<SubtitleToken>,
    ): List<MatchedPair> {
        val n = lyricTokens.size
        val m = subtitleTokens.size

        val match = 2
        val mismatch = -1
        val gapLyric = -1
        val gapSubInterior = -1

        val dp = Array(n + 1) { IntArray(m + 1) { 0 } }
        val trace = Array(n + 1) { ByteArray(m + 1) { 0 } }

        for (i in 1..n) {
            dp[i][0] = dp[i - 1][0] + gapLyric
            trace[i][0] = 1
        }

        for (i in 1..n) {
            val lTok = lyricTokens[i - 1]
            for (j in 1..m) {
                val sTok = subtitleTokens[j - 1]

                val matchScore = dp[i - 1][j - 1] + if (wordsMatch(lTok.normalized, sTok.normalized)) match else mismatch
                val gapInSub = dp[i - 1][j] + gapLyric
                val gapSubCost = if (j == 1 || j == m) 0 else gapSubInterior
                val gapInLyric = dp[i][j - 1] + gapSubCost

                val best = maxOf(matchScore, gapInSub, gapInLyric)
                dp[i][j] = best
                trace[i][j] =
                    when (best) {
                        matchScore -> 0
                        gapInSub -> 1
                        else -> 2
                    }
            }
        }

        var j = (0..m).maxByOrNull { dp[n][it] } ?: m
        var i = n

        val pairs = mutableListOf<MatchedPair>()
        while (i > 0 && j > 0) {
            when (trace[i][j].toInt()) {
                0 -> {
                    val lTok = lyricTokens[i - 1]
                    val sTok = subtitleTokens[j - 1]
                    if (wordsMatch(lTok.normalized, sTok.normalized)) {
                        pairs.add(
                            MatchedPair(
                                audioStartMs = lTok.audioStartMs,
                                videoStartMs = sTok.videoStartMs,
                                isAnchor = lTok.isLineStart && sTok.isCueStart,
                            ),
                        )
                    }
                    i--
                    j--
                }

                1 -> {
                    i--
                }

                else -> {
                    j--
                }
            }
        }

        return pairs.reversed()
    }

    private fun detectBlocks(matches: List<MatchedPair>): List<OffsetBlock> {
        if (matches.isEmpty()) return emptyList()

        val rawDeltas = matches.map { it.videoStartMs - it.audioStartMs }
        val globalMedian = median(rawDeltas)

        val cleanPairs = mutableListOf<MatchedPair>()
        val cleanDeltas = mutableListOf<Long>()

        for (i in matches.indices) {
            val d = rawDeltas[i]
            val start = (i - 3).coerceAtLeast(0)
            val end = (i + 3).coerceAtMost(matches.size - 1)
            val localMed = median(rawDeltas.subList(start, end + 1))

            if (abs(d - localMed) <= OUTLIER_TOLERANCE_MS || abs(d - globalMedian) <= OUTLIER_TOLERANCE_MS) {
                cleanPairs.add(matches[i])
                cleanDeltas.add(d)
            }
        }

        if (cleanPairs.isEmpty()) return emptyList()

        val smoothedDeltas =
            cleanDeltas.indices.map { i ->
                val start = (i - 3).coerceAtLeast(0)
                val end = (i + 3).coerceAtMost(cleanDeltas.size - 1)
                median(cleanDeltas.subList(start, end + 1))
            }

        data class RawBlock(
            val pairs: MutableList<MatchedPair> = mutableListOf(),
            val deltas: MutableList<Long> = mutableListOf(),
        )

        val rawBlocks = mutableListOf(RawBlock())
        rawBlocks.last().pairs.add(cleanPairs[0])
        rawBlocks.last().deltas.add(cleanDeltas[0])

        for (k in 1 until cleanPairs.size) {
            val currentSmoothed = smoothedDeltas[k]
            val lastBlockMedian = median(rawBlocks.last().deltas)
            if (abs(currentSmoothed - lastBlockMedian) > BLOCK_SPLIT_THRESHOLD_MS &&
                abs(cleanDeltas[k] - lastBlockMedian) > BLOCK_SPLIT_THRESHOLD_MS
            ) {
                rawBlocks.add(RawBlock())
            }
            rawBlocks.last().pairs.add(cleanPairs[k])
            rawBlocks.last().deltas.add(cleanDeltas[k])
        }

        val consolidated = mutableListOf<RawBlock>()
        for (block in rawBlocks) {
            if (consolidated.isEmpty()) {
                consolidated.add(block)
            } else {
                val prev = consolidated.last()
                val prevMed = median(prev.deltas)
                val curMed = median(block.deltas)
                val hasEnoughEvidence =
                    block.pairs.size >= MIN_BLOCK_MATCHES || block.pairs.count { it.isAnchor } >= MIN_BLOCK_ANCHORS
                if (!hasEnoughEvidence || abs(curMed - prevMed) <= BLOCK_SPLIT_THRESHOLD_MS) {
                    prev.pairs.addAll(block.pairs)
                    prev.deltas.addAll(block.deltas)
                } else {
                    consolidated.add(block)
                }
            }
        }

        return consolidated.map { block ->
            val anchorDeltas = block.pairs.filter { it.isAnchor }.map { it.videoStartMs - it.audioStartMs }
            val blockOffset =
                if (anchorDeltas.size >= 2) {
                    median(anchorDeltas)
                } else {
                    median(block.deltas)
                }
            OffsetBlock(
                audioStartMs = block.pairs.first().audioStartMs,
                audioEndMs = block.pairs.last().audioStartMs,
                offsetMs = blockOffset,
                matchCount = block.pairs.size,
            )
        }
    }

    private fun applyOffsets(
        lyrics: MlxDocument,
        matches: List<MatchedPair>,
        blocks: List<OffsetBlock>,
    ): MlxDocument {
        if (matches.isEmpty()) return lyrics
        val anchorMatches = matches.filter(MatchedPair::isAnchor)
        val offsetPoints =
            if (anchorMatches.isNotEmpty()) {
                val rawPoints =
                    anchorMatches
                        .groupBy(MatchedPair::audioStartMs)
                        .map { (time, pairs) -> time to median(pairs.map { it.videoStartMs - it.audioStartMs }) }
                        .sortedBy { it.first }
                smoothAnchorOffsets(rawPoints, blocks)
            } else {
                blocks.map { block ->
                    val midpoint = block.audioStartMs + (block.audioEndMs - block.audioStartMs) / 2L
                    midpoint to block.offsetMs
                }
            }
        val offsetAt: (Long) -> Long = { audioTimeMs -> interpolatedOffset(audioTimeMs, offsetPoints) }
        val retimedLines =
            lyrics.lines.map { entry ->
                entry.retime(offsetAt)
            }
        val retimedSongParts =
            lyrics.songParts.map { part ->
                val newStart = (part.startMs + offsetAt(part.startMs)).coerceAtLeast(0L)
                val newEnd =
                    part.endMs?.let {
                        (it + offsetAt(it)).coerceAtLeast(newStart + 1L)
                    }
                part.copy(startMs = newStart, endMs = newEnd)
            }
        return lyrics.copy(lines = retimedLines, songParts = retimedSongParts)
    }

    private fun smoothAnchorOffsets(
        points: List<Pair<Long, Long>>,
        blocks: List<OffsetBlock>,
    ): List<Pair<Long, Long>> =
        points.map { (time, offset) ->
            val block = blocks.lastOrNull { time in it.audioStartMs..it.audioEndMs }
            val blockPoints =
                if (block == null) {
                    points
                } else {
                    points.filter { it.first in block.audioStartMs..block.audioEndMs }
                }
            if (blockPoints.size < MIN_ANCHORS_FOR_SMOOTHING) {
                time to offset
            } else {
                val blockIndex = blockPoints.indexOfFirst { it.first == time }
                val start = (blockIndex - 1).coerceAtLeast(0)
                val end = (blockIndex + 1).coerceAtMost(blockPoints.lastIndex)
                time to median(blockPoints.subList(start, end + 1).map { it.second })
            }
        }

    private fun interpolatedOffset(
        audioTimeMs: Long,
        points: List<Pair<Long, Long>>,
    ): Long {
        if (points.size == 1 || audioTimeMs <= points.first().first) return points.first().second
        if (audioTimeMs >= points.last().first) return points.last().second

        val rightIndex = points.indexOfFirst { it.first >= audioTimeMs }
        val left = points[rightIndex - 1]
        val right = points[rightIndex]
        val span = (right.first - left.first).coerceAtLeast(1L)
        val progress = (audioTimeMs - left.first).toDouble() / span
        return (left.second + (right.second - left.second) * progress).roundToLong()
    }

    private fun LyricsEntry.retime(offsetAt: (Long) -> Long): LyricsEntry {
        val newTime = (time + offsetAt(time)).coerceAtLeast(0L)
        val newEndTime = endTime?.let { (it + offsetAt(it)).coerceAtLeast(newTime + 1L) }
        val newWords = words?.retimeSyllables(offsetAt)
        val newRomanWords = romanizedWords?.retimeSyllables(offsetAt)
        return copy(
            time = newTime,
            endTime = newEndTime,
            words = newWords,
            romanizedWords = newRomanWords,
        )
    }

    private fun List<WordTimestamp>.retimeSyllables(offsetAt: (Long) -> Long): List<WordTimestamp> {
        var wordOffsetSec = 0.0
        var startsWord = true
        return map { syllable ->
            if (startsWord) {
                wordOffsetSec = offsetAt((syllable.startTime * 1000.0).roundToLong()) / 1000.0
            }
            startsWord = syllable.hasTrailingSpace || syllable.text.endsWith(' ')
            syllable.retime(wordOffsetSec)
        }
    }

    private fun WordTimestamp.retime(
        startOffsetSec: Double,
        endOffsetSec: Double = startOffsetSec,
    ): WordTimestamp =
        copy(
            startTime = (startTime + startOffsetSec).coerceAtLeast(0.0),
            endTime = (endTime + endOffsetSec).coerceAtLeast(0.0),
        )

    private fun WordTimestamp.retime(positionMap: CounterpartPositionMap): WordTimestamp {
        val mappedStart = positionMap.mapPosition((startTime * 1000.0).roundToLong())
        val mappedEnd = positionMap.mapPosition((endTime * 1000.0).roundToLong())
        return copy(
            startTime = mappedStart.coerceAtLeast(0L) / 1000.0,
            endTime = mappedEnd.coerceAtLeast(mappedStart + 1L) / 1000.0,
        )
    }

    private val NOISE_TAG_REGEX =
        Regex(
            """\[[^\]]*\]|\([^)]*(?:music|applause|laughter|audio|singing|cheering|sound|instrumental|beatbox|solo|guitar|piano|drum)[^)]*\)""",
            RegexOption.IGNORE_CASE,
        )
    private val PUNCT_REGEX = Regex("""[.,!?♪♫"'()\[\]{}:;…\-–—]""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val CONTRACTION_REGEX = Regex("""['’‘´`](t|s|m|re|ve|ll|d)\b""", RegexOption.IGNORE_CASE)
    private val CURLY_QUOTES_REGEX = Regex("""['’‘´`"]""")

    private val NOISE_WORDS =
        setOf(
            "music",
            "applause",
            "laughter",
            "cheering",
            "sound",
            "singing",
            "instrumental",
            "beatboxing",
            "whistling",
            "groaning",
            "screaming",
            "cheers",
            "chuckle",
            "snicker",
            "gasp",
            "sigh",
            "snort",
        )

    private fun isNoiseWord(word: String): Boolean = word in NOISE_WORDS

    internal fun normalizeWord(raw: String): String {
        var s = raw.trim().lowercase()
        s = CONTRACTION_REGEX.replace(s, "")
        s = CURLY_QUOTES_REGEX.replace(s, "")
        s = PUNCT_REGEX.replace(s, "")
        s = s.trim()
        if (s.length >= 5 && s.endsWith("in") && !s.endsWith("ain") && !s.endsWith("oin") && !s.endsWith("win")) {
            s += "g"
        }
        return canonicalWord(s)
    }

    private val SLANG_CANONICAL =
        mapOf(
            "gonna" to "going",
            "gunna" to "going",
            "wanna" to "want",
            "gotta" to "got",
            "kinda" to "kind",
            "sorta" to "sort",
            "outta" to "out",
            "tryna" to "trying",
            "cause" to "because",
            "cuz" to "because",
            "coz" to "because",
            "yea" to "yeah",
            "yah" to "yeah",
            "ya" to "you",
            "u" to "you",
            "r" to "are",
            "ur" to "your",
            "imma" to "going",
            "bout" to "about",
            "em" to "them",
            "til" to "until",
            "aint" to "isnt",
            "ooh" to "oh",
            "oooh" to "oh",
            "ohh" to "oh",
            "whoa" to "woah",
            "ahh" to "ah",
        )

    private fun canonicalWord(word: String): String = SLANG_CANONICAL[word] ?: word

    private fun wordsMatch(
        a: String,
        b: String,
    ): Boolean {
        if (a == b) return true
        if (a.isBlank() || b.isBlank()) return false
        if (containsCjk(a) && containsCjk(b)) {
            if (a.contains(b) || b.contains(a)) return true
            return levenshteinSimilarity(a, b) >= 0.50
        }
        return levenshteinSimilarity(a, b) >= MATCH_SIMILARITY_THRESHOLD
    }

    private fun containsCjk(s: String): Boolean =
        s.any { c ->
            c in '\u4e00'..'\u9fff' ||
                c in '\u3040'..'\u309f' ||
                c in '\u30a0'..'\u30ff' ||
                c in '\uac00'..'\ud7af' ||
                c in '\u1100'..'\u11ff'
        }

    internal fun levenshteinDistance(
        s1: String,
        s2: String,
    ): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        return dp[s2.length]
    }

    internal fun levenshteinSimilarity(
        s1: String,
        s2: String,
    ): Double {
        if (s1 == s2) return 1.0
        val maxLen = maxOf(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshteinDistance(s1, s2).toDouble() / maxLen
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2L else sorted[mid]
    }

    private const val BLOCK_SPLIT_THRESHOLD_MS = 1000L
    private const val OUTLIER_TOLERANCE_MS = 4000L
    private const val MIN_ANCHORS_FOR_SMOOTHING = 3
    private const val MIN_BLOCK_MATCHES = 6
    private const val MIN_BLOCK_ANCHORS = 2
    private const val MATCH_SIMILARITY_THRESHOLD = 0.75
    private const val DUP_TOKEN_WINDOW_MS = 300L

    const val SCORE_HIGH_CONFIDENCE = 0.50f
    const val SCORE_PRECISE_CONFIDENCE = 0.85f
    const val SCORE_MEDIUM_CONFIDENCE = 0.25f
}
