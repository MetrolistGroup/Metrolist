/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.api.lyrics.romanizer.containsJapaneseKanji
import com.metrolist.shared.model.AgentInfo
import com.metrolist.shared.model.AgentType
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.MlxColors
import com.metrolist.shared.model.MlxDocument
import com.metrolist.shared.model.MlxMeta
import com.metrolist.shared.model.MlxSong
import com.metrolist.shared.model.MlxSongPart
import com.metrolist.shared.model.WordTimestamp

object MlxParser {
    private const val MAX_DOCUMENT_CHARS = 8 * 1024 * 1024
    internal const val MAX_LINES = 5_000
    internal const val MAX_LINE_CHARS = 16 * 1024
    internal const val MAX_WORD_MARKERS = 20_000

    private val SECTION_HEADER = """^\[([a-zA-Z_]+)\]$""".toRegex()
    private val SECTION_NAMES =
        setOf(
            "meta",
            "song",
            "agents",
            "agentcolors",
            "lyrics",
            "romanlyrics",
            "roman_lyrics",
            "translatedlyrics",
            "translated_lyrics",
            "colors",
            "songparts",
            "song_parts",
        )
    private val LINE_HEADER =
        """\[(\d+:\d{2}(?:[.:]\d{1,3})?)(?:~(\d+:\d{2}(?:[.:]\d{1,3})?))?(?:\|([^\]|]+))?\]""".toRegex()
    private val PART_TAG =
        """^\[(?:part|songpart):?(?:(\d+:\d{2}[.:]\d{2,3})(?:~(\d+:\d{2}[.:]\d{2,3}))?)?\]\s*(.*)$""".toRegex(
            RegexOption.IGNORE_CASE,
        )

    private val WORD_TS = """<(\d+):(\d{2})(?:[.:](\d{1,3}))?>""".toRegex()
    private val INLINE_BG = """\s*\[bg:(.*)\]$""".toRegex()
    private val LEGACY_AGENT = """\{agent:([^}]+)\}""".toRegex()
    private val LEGACY_PREFIX = """^(v\d+):\s*""".toRegex()
    private val LRC_METADATA = """^\[(?:ar|al|ti|au|by|re|ve|length|offset):.*\]$""".toRegex(RegexOption.IGNORE_CASE)

    fun isMlxFormat(text: String): Boolean {
        if (text.length > MAX_DOCUMENT_CHARS) return false
        val hasMlxHeader =
            text.trimStart().startsWith('{') ||
                text.trimStart().startsWith("[meta]") ||
                text.lineSequence().any { it.trim().equals("[lyrics]", ignoreCase = true) }
        return hasMlxHeader && parse(text).lines.any { it.text.isNotBlank() }
    }

    fun parse(text: String): MlxDocument {
        if (text.length > MAX_DOCUMENT_CHARS) return emptyDocument()
        if (text.trimStart().startsWith('{')) {
            return MlxJsonParser.parse(text)?.withValidatedRomanization() ?: emptyDocument()
        }
        if (!isWithinBudget(text)) return emptyDocument()
        val cleanedText = text.replace("&#x27;", "'")
        val sections = mutableMapOf("lyrics" to mutableListOf<String>())
        var current = "lyrics"

        for (rawLine in cleanedText.lines()) {
            val trimmed = rawLine.trim()
            val section =
                SECTION_HEADER
                    .matchEntire(trimmed)
                    ?.groupValues
                    ?.get(1)
                    ?.lowercase()
            if (section != null && section in SECTION_NAMES) {
                current = section
                sections.getOrPut(current) { mutableListOf() }
            } else if (current.isNotEmpty()) {
                sections[current]?.add(rawLine)
            }
        }

        val agents = parseAgents(sections["agents"] ?: emptyList(), parseKeyValue(sections["agentcolors"] ?: emptyList()))
        val parsedSongParts = mutableListOf<MlxSongPart>()
        val mainLines = parseLyricsSection(sections["lyrics"] ?: emptyList(), agents, parsedSongParts)
        val externalSongParts = parseSongParts(sections["songparts"] ?: sections["song_parts"] ?: emptyList())
        val combinedSongParts = if (parsedSongParts.isNotEmpty()) parsedSongParts else externalSongParts

        val linesWithSongParts =
            if (combinedSongParts.isNotEmpty() && mainLines.any { it.songPart == null }) {
                mainLines.map { entry ->
                    if (entry.songPart == null) {
                        val matching =
                            combinedSongParts.firstOrNull { p ->
                                if (p.endMs != null) {
                                    entry.time >= p.startMs && entry.time <= p.endMs
                                } else {
                                    false
                                }
                            } ?: combinedSongParts.lastOrNull { it.startMs <= entry.time }
                        entry.copy(songPart = matching?.name)
                    } else {
                        entry
                    }
                }
            } else {
                mainLines
            }

        val resolvedSongParts =
            combinedSongParts.map { part ->
                if (part.startMs == 0L && part.endMs == null) {
                    val matchingLines = linesWithSongParts.filter { it.songPart == part.name }
                    if (matchingLines.isNotEmpty()) {
                        val minTime = matchingLines.minOf { it.time }
                        val maxTime = matchingLines.map { it.endTime ?: it.time }.maxOrNull()
                        part.copy(startMs = minTime, endMs = maxTime)
                    } else {
                        part
                    }
                } else {
                    part
                }
            }

        val romanSection = sections["romanlyrics"] ?: sections["roman_lyrics"]
        val romanizedLines =
            if (!romanSection.isNullOrEmpty()) {
                val romanLines = parseLyricsSection(romanSection, agents)
                val romanMap = romanLines.groupBy { it.time }.mapValues { (_, lines) -> ArrayDeque(lines) }
                linesWithSongParts.mapIndexed { index, mainEntry ->
                    val matchingRoman =
                        romanMap[mainEntry.time]?.removeFirstOrNull() ?: romanLines.getOrNull(index).takeIf { mainEntry.time !in romanMap }
                    if (matchingRoman != null) {
                        mainEntry.copy(
                            romanizedText = matchingRoman.text.takeIf { it.isNotBlank() },
                            romanizedWords = matchingRoman.words,
                        )
                    } else {
                        mainEntry
                    }
                }
            } else {
                linesWithSongParts
            }
        val translatedSection = sections["translatedlyrics"] ?: sections["translated_lyrics"]
        val finalLines =
            if (!translatedSection.isNullOrEmpty()) {
                val translatedLines = parseLyricsSection(translatedSection, agents)
                val translatedMap = translatedLines.groupBy { it.time }.mapValues { (_, lines) -> ArrayDeque(lines) }
                romanizedLines.mapIndexed { index, mainEntry ->
                    val matchingTranslation =
                        translatedMap[mainEntry.time]?.removeFirstOrNull()
                            ?: translatedLines.getOrNull(index).takeIf { mainEntry.time !in translatedMap }
                    mainEntry.copy(translatedText = matchingTranslation?.text?.takeIf { it.isNotBlank() })
                }
            } else {
                romanizedLines
            }

        return MlxDocument(
            meta = parseMeta(sections["meta"] ?: emptyList()),
            song = parseSong(sections["song"] ?: emptyList()),
            agents =
                finalLines.mapNotNull { it.agent }.distinct().associateWith { id ->
                    agents[id] ?: AgentInfo(
                        id = id,
                        type =
                            when {
                                id == "v1000" || id.startsWith("g") -> AgentType.GROUP
                                id == "v2000" || id.startsWith("o") -> AgentType.OTHER
                                else -> AgentType.PERSON
                            },
                    )
                } + agents,
            lines = finalLines,
            songParts = resolvedSongParts,
            colors = parseColors(sections["colors"] ?: emptyList()),
        ).withLrcOffset(lrcOffsetMs(text)).withValidatedRomanization()
    }

    private fun MlxDocument.withValidatedRomanization(): MlxDocument {
        if (!meta.provider.equals("LYPRIO", ignoreCase = true) || !meta.subprovider.equals("KUGOU", ignoreCase = true)) return this
        return copy(
            lines =
                lines.map { line ->
                    if (containsJapaneseKanji(line.romanizedText.orEmpty()) ||
                        line.romanizedWords.orEmpty().any { containsJapaneseKanji(it.text) }
                    ) {
                        line.copy(romanizedText = null, romanizedWords = null)
                    } else {
                        line
                    }
                },
        )
    }

    internal fun withTranslations(
        text: String,
        lines: List<LyricsEntry>,
        translations: List<String?>,
    ): String =
        buildString {
            var inTranslation = false
            for (line in text.lineSequence()) {
                val section =
                    SECTION_HEADER
                        .matchEntire(line.trim())
                        ?.groupValues
                        ?.get(1)
                        ?.lowercase()
                if (section in SECTION_NAMES) {
                    inTranslation = section == "translatedlyrics" || section == "translated_lyrics"
                }
                if (!inTranslation) appendLine(line)
            }
            appendLine()
            appendLine("[translatedLyrics]")
            lines.forEachIndexed { index, line ->
                append('[')
                append(MlxBuilder.formatMs(line.time))
                append(']')
                appendLine(translations[index].orEmpty())
            }
        }

    private fun parseMeta(lines: List<String>): MlxMeta {
        val kv = parseKeyValue(lines)
        return MlxMeta(
            version = kv["mlx_version"] ?: "1.0",
            lyricSource = kv["lyric_source"]?.takeIf { it.isNotBlank() },
            lastFetched = kv["last_fetched"]?.takeIf { it.isNotBlank() },
            ignoreRetiming = kv["ignore_retiming"]?.trim()?.equals("true", ignoreCase = true) == true,
            provider = kv["provider"]?.takeIf { it.isNotBlank() },
            subprovider = kv["subprovider"]?.takeIf { it.isNotBlank() },
            sourceFormat = kv["source_format"]?.takeIf { it.isNotBlank() },
            syncType = kv["sync_type"]?.takeIf { it.isNotBlank() },
            sourceId = kv["source_id"]?.takeIf { it.isNotBlank() },
            curator = kv["curator"]?.takeIf { it.isNotBlank() },
            lyricsCurator = kv["lyrics_curator"]?.takeIf { it.isNotBlank() },
            lyricsCuratorId = kv["lyrics_curator_id"]?.takeIf { it.isNotBlank() },
            copyright = kv["copyright"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseSong(lines: List<String>): MlxSong {
        val kv = parseKeyValue(lines)
        val songwriters = mutableListOf<String>()
        val songwritersVal = kv["songwriters"] ?: kv["song_writers"]
        if (!songwritersVal.isNullOrBlank()) {
            songwriters.addAll(songwritersVal.split(",").map { it.trim() }.filter { it.isNotBlank() })
        }
        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("songwriter=", ignoreCase = true) || trimmed.startsWith("song_writer=", ignoreCase = true)) {
                val value = trimmed.substringAfter('=').trim()
                if (value.isNotBlank() && value !in songwriters) {
                    songwriters.add(value)
                }
            }
        }
        return MlxSong(
            title = kv["title"]?.takeIf { it.isNotBlank() },
            artist = kv["artist"]?.takeIf { it.isNotBlank() },
            youtubeId = kv["youtube_id"]?.takeIf { it.isNotBlank() },
            songwriters = songwriters,
            album = kv["album"]?.takeIf { it.isNotBlank() },
            durationMs = kv["duration_ms"]?.toLongOrNull(),
            isrc = kv["isrc"]?.takeIf { it.isNotBlank() },
            platformId = kv["platform_id"]?.takeIf { it.isNotBlank() },
            language = kv["language"]?.takeIf { it.isNotBlank() },
            romanizationLanguage = kv["romanization_language"]?.takeIf { it.isNotBlank() },
            translationLanguage = kv["translation_language"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseColors(lines: List<String>): MlxColors? {
        val kv = parseKeyValue(lines)
        return MlxColors(
            background = kv["background"]?.takeIf { it.isNotBlank() },
            text = kv["text"]?.takeIf { it.isNotBlank() },
            highlight = kv["highlight"]?.takeIf { it.isNotBlank() },
        ).takeUnless { it == MlxColors() }
    }

    private fun parseSongParts(lines: List<String>): List<MlxSongPart> {
        val parts = mutableListOf<MlxSongPart>()
        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isBlank() || trimmed.startsWith("//")) continue
            val headerMatch = LINE_HEADER.find(trimmed)
            if (headerMatch != null) {
                val startMs = tsToMs(headerMatch.groupValues[1])
                val endMs = headerMatch.groupValues[2].takeIf { it.isNotBlank() }?.let { tsToMs(it) }
                val name = trimmed.substring(headerMatch.range.last + 1).trim()
                if (name.isNotEmpty()) {
                    parts.add(MlxSongPart(name = name, startMs = startMs, endMs = endMs))
                }
            } else if (trimmed.contains('=')) {
                val name = trimmed.substringBefore('=').trim()
                val timing = trimmed.substringAfter('=').trim()
                if (timing.contains('~')) {
                    val startStr = timing.substringBefore('~').trim()
                    val endStr = timing.substringAfter('~').trim()
                    parts.add(MlxSongPart(name = name, startMs = tsToMs(startStr), endMs = tsToMs(endStr)))
                } else {
                    parts.add(MlxSongPart(name = name, startMs = tsToMs(timing)))
                }
            }
        }
        return parts
    }

    private fun parseAgents(
        lines: List<String>,
        colors: Map<String, String> = emptyMap(),
    ): Map<String, AgentInfo> {
        val result = mutableMapOf<String, AgentInfo>()
        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isBlank() || trimmed.startsWith("//")) continue
            val eq = trimmed.indexOf('=').takeIf { it > 0 } ?: continue
            val id = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            val parts = value.split(",", limit = 2)
            result[id] =
                AgentInfo(
                    id = id,
                    type = AgentType.fromString(parts[0]),
                    name = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() },
                    color = colors[id]?.takeIf { it.isNotBlank() },
                )
        }
        return result
    }

    private fun parseLyricsSection(
        lines: List<String>,
        agents: Map<String, AgentInfo>,
        outSongParts: MutableList<MlxSongPart>? = null,
    ): List<LyricsEntry> {
        val result = mutableListOf<LyricsEntry>()
        val lineStarts =
            lines
                .flatMap {
                    LINE_HEADER
                        .findAll(it)
                        .map { match ->
                            tsToMs(match.groupValues[1])
                        }.toList()
                }.distinct()
                .sorted()
        var currentSongPart: String? = null
        var lastAgent: String? = null
        var precedingLines = emptyList<Int>()
        var hasLineTimestamps = false

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isBlank() || trimmed.startsWith("//")) continue
            if (LRC_METADATA.matches(trimmed)) {
                precedingLines = emptyList()
                continue
            }
            if (trimmed.startsWith('<') && trimmed.endsWith('>') && !WORD_TS.containsMatchIn(trimmed)) {
                val words = parseLegacyWords(trimmed.removeSurrounding("<", ">"))
                if (words.isNotEmpty()) {
                    for (index in precedingLines) {
                        val entry = result[index]
                        result[index] =
                            entry.copy(
                                words = words.map { it.copy(isBackground = entry.isBackground) },
                                endTime = (words.maxOf { it.endTime } * 1000).toLong(),
                            )
                    }
                }
                precedingLines = emptyList()
                continue
            }
            precedingLines = emptyList()

            val partMatch = PART_TAG.matchEntire(trimmed)
            if (partMatch != null) {
                val startStr = partMatch.groupValues[1]
                val endStr = partMatch.groupValues[2]
                val name = partMatch.groupValues[3].trim()
                if (name.isNotEmpty()) {
                    currentSongPart = name
                    val startMs = if (startStr.isNotBlank()) tsToMs(startStr) else 0L
                    val endMs = if (endStr.isNotBlank()) tsToMs(endStr) else null
                    outSongParts?.add(MlxSongPart(name = name, startMs = startMs, endMs = endMs))
                }
                continue
            }

            val headers = mutableListOf<MatchResult>()
            var contentStart = 0
            while (true) {
                val header = LINE_HEADER.matchAt(trimmed, contentStart) ?: break
                headers.add(header)
                contentStart = header.range.last + 1
                while (contentStart < trimmed.length && trimmed[contentStart].isWhitespace()) contentStart++
            }
            val headerMatch = headers.firstOrNull()
            if (headerMatch != null) hasLineTimestamps = true
            val startMs = headerMatch?.groupValues?.get(1)?.let(::tsToMs) ?: 0L
            val endMs =
                headerMatch
                    ?.groupValues
                    ?.get(2)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::tsToMs)
            var afterHeader = trimmed.substring(contentStart).trimStart()
            val legacyAgent = LEGACY_AGENT.find(afterHeader)?.groupValues?.get(1)
            afterHeader = LEGACY_AGENT.replace(afterHeader, "")
            val prefix = LEGACY_PREFIX.find(afterHeader)
            if (prefix != null) afterHeader = afterHeader.substring(prefix.range.last + 1)
            val standaloneBackground = headers.isEmpty() && afterHeader.startsWith("[bg:") && afterHeader.endsWith(']')
            val isBackground = afterHeader.contains("{bg}") || legacyAgent == "bg" || standaloneBackground
            afterHeader = afterHeader.replace("{bg}", "")
            if (standaloneBackground) afterHeader = afterHeader.removePrefix("[bg:").removeSuffix("]")
            val agentId =
                headerMatch?.groupValues?.get(3)?.takeIf { it.isNotBlank() }
                    ?: legacyAgent?.takeUnless { it == "bg" }
                    ?: prefix?.groupValues?.get(1)
                    ?: lastAgent.takeIf { isBackground }
            if (!isBackground && agentId != null) lastAgent = agentId

            val bgMatch = INLINE_BG.find(afterHeader)
            val mainContent = if (bgMatch != null) afterHeader.substring(0, bgMatch.range.first) else afterHeader
            val bgContent = bgMatch?.groupValues?.get(1)
            val nextStart = lineStarts.getOrNull(lineStarts.binarySearch(startMs).let { if (it >= 0) it + 1 else -it - 1 })
            val wordEnd = endMs ?: nextStart
            val (mainText, mainWords) = parseContent(mainContent, startMs, wordEnd)

            val bgWords: List<WordTimestamp>? =
                if (!bgContent.isNullOrBlank()) {
                    val (_, parsed) = parseContent(bgContent, startMs, wordEnd)
                    parsed?.map { it.copy(isBackground = true) }
                } else {
                    null
                }

            val allWords: List<WordTimestamp>? =
                when {
                    mainWords != null && bgWords != null -> mainWords + bgWords
                    mainWords != null -> mainWords
                    bgWords != null -> bgWords
                    else -> null
                }

            val firstIndex = result.size
            for (header in headers.ifEmpty { listOf(null) }) {
                result.add(
                    LyricsEntry(
                        time =
                            header?.groupValues?.get(1)?.let(::tsToMs)
                                ?: mainWords?.firstOrNull()?.startTime?.let { (it * 1000).toLong() } ?: startMs,
                        text = mainText,
                        words = if (isBackground) allWords?.map { it.copy(isBackground = true) } else allWords,
                        agent = agentId,
                        endTime = endMs,
                        isBackground = isBackground,
                        backgroundText = bgContent?.let { parseContent(it, startMs, endMs).first },
                        songPart = currentSongPart,
                    ),
                )
            }
            precedingLines = (firstIndex until result.size).toList()
        }

        val sorted = result.sortedBy { it.time }
        val isDocSynced = hasLineTimestamps || sorted.any { it.time > 0L }
        val completed = sorted.toMutableList()
        var nextGreaterTime: Long? = null
        var lastTime: Long? = null
        for (index in sorted.lastIndex downTo 0) {
            val entry = sorted[index]
            if (lastTime != null && entry.time < lastTime) nextGreaterTime = lastTime
            if (entry.endTime == null && (entry.time > 0L || (entry.time == 0L && isDocSynced))) {
                completed[index] =
                    entry.copy(
                        endTime = entry.words?.maxOfOrNull { (it.endTime * 1000).toLong() } ?: nextGreaterTime ?: (entry.time + 10_000L),
                    )
            }
            lastTime = entry.time
        }
        return completed
    }

    private fun parseLegacyWords(content: String): List<WordTimestamp> {
        val segments = content.split('|')
        return segments.mapIndexedNotNull { index, segment ->
            val endSeparator = segment.lastIndexOf(':')
            val startSeparator = segment.lastIndexOf(':', endSeparator - 1)
            if (startSeparator < 0 || endSeparator <= startSeparator) return@mapIndexedNotNull null
            val start = segment.substring(startSeparator + 1, endSeparator).toDoubleOrNull() ?: return@mapIndexedNotNull null
            val end = segment.substring(endSeparator + 1).toDoubleOrNull() ?: return@mapIndexedNotNull null
            if (!start.isFinite() || !end.isFinite() || start < 0 || end < start || end >= Long.MAX_VALUE / 1000.0) {
                return@mapIndexedNotNull null
            }
            WordTimestamp(segment.substring(0, startSeparator), start, end, hasTrailingSpace = index < segments.lastIndex)
        }
    }

    /** Preserves syllable spacing; only trailing spaces move into WordTimestamp.hasTrailingSpace. */
    private fun parseContent(
        content: String,
        lineStartMs: Long,
        lineEndMs: Long?,
    ): Pair<String, List<WordTimestamp>?> {
        val markers = WORD_TS.findAll(content).toList()

        if (markers.isEmpty()) {
            return content.trim() to null
        }

        val words = mutableListOf<WordTimestamp>()
        val textBuf = StringBuilder()

        var pendingText: String? = null
        var pendingStartMs = 0L

        fun flushPending(endMs: Long) {
            val raw = pendingText ?: return
            pendingText = null
            if (raw.isEmpty()) return
            val hasTrailingSpace = raw.endsWith(' ')
            val syllableText = if (hasTrailingSpace) raw.dropLast(1) else raw
            words.add(
                WordTimestamp(
                    text = syllableText,
                    startTime = pendingStartMs / 1000.0,
                    endTime = endMs / 1000.0,
                    hasTrailingSpace = hasTrailingSpace,
                ),
            )
        }

        for (i in markers.indices) {
            val marker = markers[i]
            val markerMs = groupsToMs(marker.groupValues[1], marker.groupValues[2], marker.groupValues[3])

            val segStart = marker.range.last + 1
            val segEnd = if (i + 1 < markers.size) markers[i + 1].range.first else content.length
            val rawSeg = content.substring(segStart, segEnd)
            textBuf.append(rawSeg)

            if (rawSeg.isEmpty()) {
                flushPending(markerMs)
                continue
            }

            flushPending(markerMs)

            pendingText = rawSeg
            pendingStartMs = markerMs
        }

        val fallbackEnd = lineEndMs?.takeIf { it > pendingStartMs } ?: (pendingStartMs + 500L)
        flushPending(fallbackEnd)

        val plainText = textBuf.toString().trim()
        return plainText to words.takeIf { it.isNotEmpty() }
    }

    fun tsToMs(ts: String): Long {
        val normalized = ts.trim()
        val colonIdx = normalized.indexOf(':')
        if (colonIdx < 0) return 0L
        val min = normalized.substring(0, colonIdx).toLongOrNull() ?: 0L

        val rest = normalized.substring(colonIdx + 1)
        val msSepDot = rest.lastIndexOf('.')
        val msSepColon = rest.lastIndexOf(':')
        val msSep = maxOf(msSepDot, msSepColon)

        val sec = if (msSep > 0) rest.substring(0, msSep).toLongOrNull() ?: 0L else rest.toLongOrNull() ?: 0L
        val msStr = if (msSep >= 0 && msSep + 1 < rest.length) rest.substring(msSep + 1) else "0"
        val ms = msStr.toLongOrNull() ?: 0L
        val msNorm =
            when (msStr.length) {
                1 -> ms * 100L
                2 -> ms * 10L
                else -> ms
            }

        return min * 60_000L + sec * 1_000L + msNorm
    }

    private fun groupsToMs(
        min: String,
        sec: String,
        ms: String,
    ): Long {
        val m = min.toLongOrNull() ?: 0L
        val s = sec.toLongOrNull() ?: 0L
        val raw = ms.toLongOrNull() ?: 0L
        val msNorm =
            when (ms.length) {
                1 -> raw * 100L
                2 -> raw * 10L
                else -> raw
            }
        return m * 60_000L + s * 1_000L + msNorm
    }

    private fun isWithinBudget(text: String): Boolean {
        var lines = 1
        var lineChars = 0
        var wordMarkers = 0
        for (character in text) {
            when (character) {
                '\n' -> {
                    lines++
                    lineChars = 0
                    if (lines > MAX_LINES) return false
                }

                '<' -> {
                    wordMarkers++
                    lineChars++
                    if (wordMarkers > MAX_WORD_MARKERS || lineChars > MAX_LINE_CHARS) return false
                }

                else -> {
                    lineChars++
                    if (lineChars > MAX_LINE_CHARS) return false
                }
            }
        }
        return true
    }

    private fun emptyDocument() =
        MlxDocument(
            meta = MlxMeta(),
            song = MlxSong(),
            agents = emptyMap(),
            lines = emptyList(),
        )

    private fun parseKeyValue(lines: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isBlank() || trimmed.startsWith("//")) continue
            val eq = trimmed.indexOf('=').takeIf { it > 0 } ?: continue
            result[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1)
        }
        return result
    }
}
