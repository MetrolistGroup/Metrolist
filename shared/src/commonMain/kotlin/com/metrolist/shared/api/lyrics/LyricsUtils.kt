/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import androidx.compose.ui.text.style.TextAlign
import com.metrolist.shared.api.musicvideo.MusicVideoMatcher
import com.metrolist.shared.model.AgentInfo
import com.metrolist.shared.model.AgentType
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.MlxDocument

object LyricsUtils {
    const val FINAL_DYNAMIC_LINE_DURATION_MS = 5_000L

    fun parseLyrics(lyrics: String): List<LyricsEntry> {
        if (lyrics.isBlank()) return emptyList()
        return try {
            MlxParser.parse(lyrics).lines
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseMlxDocument(lyrics: String): MlxDocument? {
        if (lyrics.isBlank()) return null
        return try {
            MlxParser.parse(lyrics)
        } catch (_: Exception) {
            null
        }
    }

    fun lyricsForEditing(
        rawLyrics: String?,
        entries: List<LyricsEntry>,
    ): String {
        val raw = rawLyrics.orEmpty()
        val original = parseLyrics(raw)
        if (original.size != entries.size || original.indices.any { original[it].text != entries[it].text }) return raw
        val translations =
            original.mapIndexed { index, line ->
                line.translatedText?.takeIf { it.isNotBlank() } ?: entries[index].translatedText?.takeIf { it.isNotBlank() }
            }
        if (original.indices.none { translations[it] != original[it].translatedText && translations[it] != null }) return raw
        return if (raw.trimStart().startsWith('{')) {
            MlxJsonParser.withTranslations(raw, translations)
        } else {
            MlxParser.withTranslations(raw, original, translations)
        }
    }

    fun toPlainText(lines: List<LyricsEntry>): String =
        lines.joinToString("\n") { line ->
            listOfNotNull(line.text, line.backgroundText?.takeIf { it.isNotBlank() }).joinToString("\n")
        }

    fun isCustomLyricsQuery(
        queryTitle: String,
        queryArtist: String,
        songTitle: String,
        songArtist: String,
    ): Boolean =
        MusicVideoMatcher.normalizeTitle(queryTitle) != MusicVideoMatcher.normalizeTitle(songTitle) ||
            MusicVideoMatcher.normalizeName(queryArtist) != MusicVideoMatcher.normalizeName(songArtist)

    fun lyricsArtistsMatch(
        requestedArtist: String,
        resultArtist: String,
    ): Boolean {
        val requested = lyricsArtistNames(requestedArtist)
        val result = lyricsArtistNames(resultArtist)
        if (requested.isEmpty() || result.isEmpty()) return true
        // Romanized and native-script spellings of one artist cannot be compared by spelling.
        if ((requested + result).any { name -> name.any { it.isLetter() && it.code > LAST_LATIN_LETTER } }) return true
        return requested.any { name -> result.any { MusicVideoMatcher.levenshteinSimilarity(name, it) >= ARTIST_NAME_SIMILARITY } }
    }

    private fun lyricsArtistNames(artist: String): Set<String> =
        artist
            .split(ARTIST_SEPARATOR)
            .map { MusicVideoMatcher.normalizeName(it).removePrefix("the ").trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private val ARTIST_SEPARATOR =
        Regex("""\s*(?:,|&|\+|/|;|\bx\b|\band\b|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|\bvs\.?)\s*""", RegexOption.IGNORE_CASE)
    private const val LAST_LATIN_LETTER = 0x24F
    private const val ARTIST_NAME_SIMILARITY = 0.8

    fun combinedLyricsOffsetMs(
        songOffsetMs: Int,
        globalOffsetMs: Int,
    ): Int = (songOffsetMs.toLong() + globalOffsetMs).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    fun lyricsPositionForOffset(
        playbackPositionMs: Long,
        offsetMs: Int,
    ): Long = playbackPositionMs + offsetMs

    fun lyricsSeekPositionForOffset(
        lyricPositionMs: Long,
        offsetMs: Int,
    ): Long = (lyricPositionMs - offsetMs).coerceAtLeast(0L)

    fun hasPlaybackTiming(lines: List<LyricsEntry>): Boolean = lyricsTimingKind(lines) != LyricsTimingKind.UNSYNCED

    internal const val LINE_SELECTION_LOOK_AHEAD_MS = 100L

    fun findCurrentLineIndex(
        lines: List<LyricsEntry>,
        position: Long,
    ): Int {
        for (index in lines.indices) {
            if (lines[index].time >= position + LINE_SELECTION_LOOK_AHEAD_MS) return index - 1
        }
        return lines.lastIndex
    }

    fun getLineEndTime(
        line: LyricsEntry,
        nextLine: LyricsEntry?,
        finalLineDurationMs: Long? = null,
    ): Long {
        val words = line.words
        val wordsEnd = words?.maxOfOrNull { (it.endTime * 1000).toLong() }
        return when {
            line.endTime != null || wordsEnd != null -> {
                maxOf(line.endTime ?: Long.MIN_VALUE, wordsEnd ?: Long.MIN_VALUE)
            }

            nextLine != null -> {
                nextLine.time
            }

            finalLineDurationMs != null -> {
                if (line.time > Long.MAX_VALUE - finalLineDurationMs.coerceAtLeast(0L)) {
                    Long.MAX_VALUE - 2_000L
                } else {
                    (line.time + finalLineDurationMs.coerceAtLeast(0L)).coerceAtMost(Long.MAX_VALUE - 2_000L)
                }
            }

            else -> {
                Long.MAX_VALUE
            }
        }.coerceAtMost(Long.MAX_VALUE - 2_000L)
    }

    fun textAlignment(lyricsTextPosition: String): TextAlign =
        when (lyricsTextPosition.uppercase()) {
            "LEFT" -> TextAlign.Start
            "RIGHT" -> TextAlign.End
            else -> TextAlign.Center
        }

    fun assignLineAlignments(
        lyrics: List<LyricsEntry>,
        agents: Map<String, AgentInfo> = emptyMap(),
        respectAgentPositioning: Boolean = true,
        lyricsTextPosition: String = "CENTER",
    ): Map<Int, TextAlign> {
        val base = textAlignment(lyricsTextPosition)

        val assignments = mutableMapOf<Int, TextAlign>()
        var currentSideIsLeft = true
        var lastPersonId: String? = null
        var rightCount = 0
        var totalCount = 0

        lyrics.forEachIndexed { index, line ->
            val singerId = line.agent
            var textAlign: TextAlign

            if (singerId != null) {
                val agentInfo = agents[singerId]
                val type =
                    when {
                        agentInfo?.type == AgentType.GROUP -> "group"
                        agentInfo?.type == AgentType.OTHER -> "other"
                        agentInfo != null -> "person"
                        singerId == "v1000" || singerId.startsWith("g") -> "group"
                        singerId == "v2000" || singerId.startsWith("o") -> "other"
                        else -> "person"
                    }

                textAlign =
                    when {
                        type == "group" -> {
                            TextAlign.Center
                        }

                        !respectAgentPositioning -> {
                            base
                        }

                        else -> {
                            if (lastPersonId == null) {
                                currentSideIsLeft = type != "other"
                            } else if (singerId != lastPersonId) {
                                currentSideIsLeft = !currentSideIsLeft
                            }
                            lastPersonId = singerId
                            if (currentSideIsLeft) TextAlign.Start else TextAlign.End
                        }
                    }
            } else {
                textAlign = base
            }

            if (textAlign != TextAlign.Center) {
                totalCount++
                if (textAlign == TextAlign.End) rightCount++
            }
            assignments[index] = textAlign
        }

        if (respectAgentPositioning && totalCount > 0 &&
            (rightCount.toFloat() / totalCount) >= 0.85f
        ) {
            assignments.keys.forEach { idx ->
                assignments[idx] =
                    when (assignments[idx]) {
                        TextAlign.Start -> TextAlign.End
                        TextAlign.End -> TextAlign.Start
                        else -> assignments[idx]!!
                    }
            }
        }

        return assignments
    }
}

internal fun lyricsTimingKind(entries: List<LyricsEntry>): LyricsTimingKind {
    if (entries.any { entry -> entry.words.orEmpty().any { !it.isLineTimed && it.endTime > it.startTime } }) {
        return LyricsTimingKind.WORD_SYNCED
    }
    val lines = entries.filter { it.text.isNotBlank() || !it.backgroundText.isNullOrBlank() || !it.words.isNullOrEmpty() }
    val hasLineTiming =
        if (lines.size == 1) {
            lines.single().let { it.time > 0L || (it.endTime ?: 0L) > 0L }
        } else {
            lines.any { it.time != lines.first().time }
        }
    return if (hasLineTiming) LyricsTimingKind.LINE_SYNCED else LyricsTimingKind.UNSYNCED
}
