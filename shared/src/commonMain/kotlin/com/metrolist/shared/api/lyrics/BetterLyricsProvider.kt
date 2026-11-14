/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.AgentType
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class BetterLyricsResponse(
    val ttml: String,
)

private const val MAX_LYRICS_RESPONSE_BYTES = 1024 * 1024

class BetterLyricsProvider(
    private val httpClient: HttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
    ): String? {
        val trimmedAlbum = album?.trim()?.takeIf { it.isNotEmpty() }
        fetchTtml(title, artist, duration, trimmedAlbum)?.let { ttml ->
            return parseTtmlToMlx(ttml, title, artist, "BetterLyrics").also { check(it.isNotBlank()) { "Invalid TTML lyrics" } }
        }
        if (trimmedAlbum != null) {
            fetchTtml(title, artist, duration, album = null)?.let { ttml ->
                return parseTtmlToMlx(ttml, title, artist, "BetterLyrics").also { check(it.isNotBlank()) { "Invalid TTML lyrics" } }
            }
        }
        return null
    }

    private suspend fun fetchTtml(
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
    ): String? {
        val response =
            httpClient.prepareGet("https://lyrics-api.boidu.dev/getLyrics") {
                expectMissingLyrics()
                parameter("s", title)
                parameter("a", artist)
                if (duration != null && duration > 0) parameter("d", duration / 1000)
                if (album != null) parameter("al", album)
                header("Accept", "application/json")
            }
        val body = response.readLyricsText(MAX_LYRICS_RESPONSE_BYTES) ?: return null
        return json.decodeFromString<BetterLyricsResponse>(body).ttml.takeIf { it.isNotBlank() }
    }
}

private const val TTML_PARAMETER_NS = "http://www.w3.org/ns/ttml#parameter"

private data class TtmlWord(
    val text: String,
    val startSec: Double,
    val endSec: Double,
    val hasTrailingSpace: Boolean = true,
)

private data class TtmlSongPartInfo(
    val name: String,
    val startSec: Double? = null,
    val endSec: Double? = null,
)

private data class TtmlLine(
    val text: String,
    val startTime: Double,
    val endTime: Double? = null,
    val words: List<TtmlWord>,
    val agent: String?,
    val isBackground: Boolean,
    val key: String? = null,
    val songPart: TtmlSongPartInfo? = null,
)

private data class AgentEntry(
    val mlxId: String,
    val type: AgentType,
)

internal fun parseTtmlToMlx(
    ttml: String,
    title: String,
    artist: String,
    source: String,
    ignoreRetiming: Boolean = false,
    sourceId: String? = null,
    lyricsCurator: String? = null,
    lyricsCuratorId: String? = null,
): String {
    if (!isTtmlWithinLimits(ttml)) return ""
    val lines = mutableListOf<TtmlLine>()
    val agentTypes = mutableMapOf<String, String>()

    try {
        val root = parseXml(ttml)

        var globalOffset = 0.0
        val songwriters = mutableListOf<String>()
        findChild(root, "head")?.let { head ->
            findChild(head, "metadata")?.let { meta ->
                findChild(meta, "audio")?.let { globalOffset = it.getAttribute("lyricOffset").toDoubleOrNull() ?: 0.0 }
                for (child in meta.children) {
                    val name = child.localName
                    if (name == "agent") {
                        val id = child.getAttribute("xml:id")
                        if (id.isNotEmpty()) agentTypes[id] = getAttr(child, "type").lowercase()
                    } else if (name == "iTunesMetadata" || name == "itunesmetadata" || name == "songwriters" || name == "sourceMetadata" ||
                        name == "sourcemetadata"
                    ) {
                        extractSongwriters(child, songwriters)
                    }
                }
            }
        }

        findChild(root, "body")?.let { walk(it, lines, globalOffset, null) }

        val romanLinesList = parseTransliterations(root, globalOffset).take(MAX_TTML_LINES)
        val romanKeyMap = romanLinesList.filter { !it.key.isNullOrBlank() }.associateBy { it.key!! }
        val romanTimeMap = romanLinesList.groupBy { (it.startTime * 4).toLong() }

        val sortedLines = lines.sortedBy { it.startTime }

        if (sortedLines.isEmpty()) {
            val plainLines = mutableListOf<String>()

            fun collectPlain(element: XmlElement) {
                if (element.localName == "p") {
                    val text = element.textContent.trim()
                    if (text.isNotEmpty()) plainLines.add(text)
                    return
                }
                for (child in element.children) {
                    collectPlain(child)
                }
            }
            findChild(root, "body")?.let { collectPlain(it) }
            if (plainLines.isNotEmpty()) {
                val builder =
                    MlxBuilder()
                        .meta(
                            source = source,
                            ignoreRetiming = ignoreRetiming,
                            sourceId = sourceId,
                            lyricsCurator = lyricsCurator,
                            lyricsCuratorId = lyricsCuratorId,
                        ).song(title = title, artist = artist, songwriters = songwriters.takeIf { it.isNotEmpty() })
                plainLines.forEach { builder.unsyncedLine(it) }
                return builder.build()
            }
            return ""
        }

        val agentMap = mutableMapOf<String, AgentEntry>()
        var personCount = 0
        var groupCount = 0
        var otherCount = 0
        agentTypes.forEach { (id, type) ->
            val agentType =
                when (type) {
                    "group" -> AgentType.GROUP
                    "other" -> AgentType.OTHER
                    else -> AgentType.PERSON
                }
            val mlxId =
                when (agentType) {
                    AgentType.GROUP -> "v${1000 + ++groupCount}"
                    AgentType.OTHER -> "v${2000 + ++otherCount}"
                    AgentType.PERSON -> "v${++personCount}"
                }
            agentMap[id] = AgentEntry(mlxId, agentType)
        }

        val isMultiAgent = agentMap.size > 1
        val builder =
            MlxBuilder()
                .meta(
                    source = source,
                    ignoreRetiming = ignoreRetiming,
                    sourceId = sourceId,
                    lyricsCurator = lyricsCurator,
                    lyricsCuratorId = lyricsCuratorId,
                ).song(title = title, artist = artist, songwriters = songwriters.takeIf { it.isNotEmpty() })
        if (isMultiAgent) agentMap.forEach { (_, e) -> builder.agent(e.mlxId, e.type) }

        val consumed = mutableSetOf<Int>()
        var mainLineCount = 0
        var lastEmittedPart: TtmlSongPartInfo? = null
        var lastEmittedRomanPart: TtmlSongPartInfo? = null

        sortedLines.forEachIndexed { idx, line ->
            if (idx in consumed) return@forEachIndexed

            if (line.songPart != null && line.songPart != lastEmittedPart) {
                val part = line.songPart
                val partStartMs = part.startSec?.let { (it * 1000).toLong() } ?: (line.startTime * 1000).toLong()
                val partEndMs = part.endSec?.let { (it * 1000).toLong() }
                builder.songPart(part.name, partStartMs, partEndMs)
                lastEmittedPart = part
            }

            val startMs = (line.startTime * 1000).toLong()
            val endMs = line.endTime?.let { (it * 1000).toLong() }
            val agentId = if (isMultiAgent) line.agent?.let { agentMap[it]?.mlxId } else null

            val nextBg = sortedLines.getOrNull(idx + 1)?.takeIf { it.isBackground }
            val bgWords =
                if (nextBg != null && nextBg.words.isNotEmpty() && !line.isBackground && endMs != null) {
                    consumed.add(idx + 1)
                    nextBg.words
                } else {
                    null
                }

            if (line.isBackground && line.words.isEmpty()) {
                if (endMs != null) {
                    builder.line(startMs, endMs, "{bg}${line.text}", agentId)
                } else {
                    builder.line(startMs, "{bg}${line.text}")
                }
            } else if (line.words.isNotEmpty() && endMs != null) {
                val mainWords =
                    line.words.map {
                        MlxWord(
                            it.text,
                            (it.startSec * 1000).toLong(),
                            (it.endSec * 1000).toLong(),
                            it.hasTrailingSpace,
                        )
                    }
                val bg = bgWords?.map { MlxWord(it.text, (it.startSec * 1000).toLong(), (it.endSec * 1000).toLong(), it.hasTrailingSpace) }
                builder.line(startMs, endMs, agentId, mainWords, bg)
            } else if (endMs != null) {
                val bgText = bgWords?.joinToString("") { it.text + if (it.hasTrailingSpace) " " else "" }?.trim()
                builder.line(startMs, endMs, line.text, agentId, bgText)
            } else {
                builder.line(startMs, line.text)
            }

            if (!line.isBackground) {
                val currentIdx = mainLineCount++
                val romanMatch =
                    (if (!line.key.isNullOrBlank()) romanKeyMap[line.key] else null)
                        ?: (-1L..1L)
                            .asSequence()
                            .flatMap { romanTimeMap[(line.startTime * 4).toLong() + it].orEmpty().asSequence() }
                            .minByOrNull { kotlin.math.abs(it.startTime - line.startTime) }
                            ?.takeIf { kotlin.math.abs(it.startTime - line.startTime) < 0.25 }
                        ?: romanLinesList.getOrNull(currentIdx)

                if (romanMatch != null) {
                    if (line.songPart != null && line.songPart != lastEmittedRomanPart) {
                        val part = line.songPart
                        val rStartMs = part.startSec?.let { (it * 1000).toLong() } ?: (line.startTime * 1000).toLong()
                        val rEndMs = part.endSec?.let { (it * 1000).toLong() }
                        builder.romanSongPart(part.name, rStartMs, rEndMs)
                        lastEmittedRomanPart = part
                    }
                    val rEndMs = romanMatch.endTime?.let { (it * 1000).toLong() } ?: endMs
                    if (romanMatch.words.isNotEmpty() && rEndMs != null) {
                        val rMainWords =
                            romanMatch.words.map {
                                MlxWord(
                                    it.text,
                                    (it.startSec * 1000).toLong(),
                                    (it.endSec * 1000).toLong(),
                                    it.hasTrailingSpace,
                                )
                            }
                        builder.romanLine(startMs, rEndMs, agentId, rMainWords, null)
                    } else if (rEndMs != null) {
                        builder.romanLine(startMs, rEndMs, romanMatch.text, agentId, null)
                    } else {
                        builder.romanLine(startMs, romanMatch.text)
                    }
                }
            }
        }

        return builder.build()
    } catch (_: Exception) {
        return ""
    }
}

private const val MAX_TTML_CHARS = 512 * 1_024
internal const val MAX_TTML_DEPTH = 64
internal const val MAX_TTML_NODES = 10_000
private const val MAX_TTML_LINES = 5_000

internal fun isTtmlWithinLimits(text: String): Boolean {
    if (text.length > MAX_TTML_CHARS) return false
    var index = 0
    var depth = 0
    var nodes = 0
    while (index < text.length) {
        if (text[index] != '<') {
            index++
            continue
        }
        val marker = text.getOrNull(index + 1)
        var cursor = index + 1
        var quote: Char? = null
        while (cursor < text.length) {
            val char = text[cursor]
            if (quote == null && (char == '\'' || char == '"')) {
                quote = char
            } else if (char == quote) {
                quote = null
            }
            if (char == '>' && quote == null) break
            cursor++
        }
        if (cursor >= text.length) return false
        if (marker == '/') {
            if (--depth < 0) return false
        } else if (marker != '!' && marker != '?') {
            nodes++
            if (nodes > MAX_TTML_NODES) return false
            if (text
                    .substring(index, cursor)
                    .trimEnd()
                    .endsWith('/')
                    .not() && ++depth > MAX_TTML_DEPTH
            ) {
                return false
            }
        }
        index = cursor + 1
    }
    return depth == 0
}

private fun localName(el: XmlElement) = el.localName

private fun findChild(
    parent: XmlElement,
    name: String,
): XmlElement? = parent.children.firstOrNull { it.localName == name }

private fun getAttr(
    el: XmlElement,
    localName: String,
): String {
    val itunes = el.getAttribute("itunes:$localName")
    if (itunes.isNotEmpty()) return itunes
    val itunesNs = el.getAttributeNS("http://music.apple.com/lyric-ttml-internal", localName)
    if (itunesNs.isNotEmpty()) return itunesNs
    val lrc = el.getAttribute("lrc:$localName")
    if (lrc.isNotEmpty()) return lrc
    val lrcNs = el.getAttributeNS("http://lrc.red/lyric-ttml-internal", localName)
    if (lrcNs.isNotEmpty()) return lrcNs
    val ttm = el.getAttribute("ttm:$localName")
    if (ttm.isNotEmpty()) return ttm
    val direct = el.getAttribute(localName)
    if (direct.isNotEmpty()) return direct
    return el.getAttributeNS("http://www.w3.org/ns/ttml#metadata", localName)
}

private fun timingAttr(
    el: XmlElement,
    localName: String,
): String {
    val direct = el.getAttribute(localName)
    if (direct.isNotEmpty()) return direct
    return el.getAttributeNS(TTML_PARAMETER_NS, localName)
}

private fun findFirstSpanBegin(p: XmlElement): String? {
    var best: String? = null
    var bestSeconds = Double.POSITIVE_INFINITY
    for (child in p.children) {
        if (child.localName == "span") {
            val b = timingAttr(child, "begin")
            if (b.isNotEmpty()) {
                val s = parseTime(b)
                if (s < bestSeconds) {
                    bestSeconds = s
                    best = b
                }
            }
        }
    }
    return best
}

private data class WalkItem(
    val element: XmlElement,
    val agent: String?,
    val songPart: TtmlSongPartInfo?,
)

private fun walk(
    element: XmlElement,
    lines: MutableList<TtmlLine>,
    offset: Double,
    parentAgent: String?,
) {
    val pending = ArrayDeque<WalkItem>()
    pending.addLast(WalkItem(element, parentAgent, null))
    while (pending.isNotEmpty() && lines.size < MAX_TTML_LINES) {
        val (current, inheritedAgent, inheritedSongPart) = pending.removeLast()
        var currentAgent = inheritedAgent
        var currentSongPart = inheritedSongPart
        when (current.localName) {
            "div" -> {
                getAttr(current, "agent").let { if (it.isNotEmpty()) currentAgent = it }
                val partName = getAttr(current, "songPart").takeIf { it.isNotEmpty() }
                if (partName != null) {
                    val b = timingAttr(current, "begin").takeIf { it.isNotEmpty() }?.let { parseTime(it) + offset }
                    val e = timingAttr(current, "end").takeIf { it.isNotEmpty() }?.let { parseTime(it) + offset }
                    currentSongPart = TtmlSongPartInfo(name = partName, startSec = b, endSec = e)
                }
            }

            "p" -> {
                parseP(current, lines, offset, currentAgent, currentSongPart)
                continue
            }
        }
        val children = current.children
        for (index in children.lastIndex downTo 0) {
            pending.addLast(WalkItem(children[index], currentAgent, currentSongPart))
        }
    }
}

private fun parseP(
    p: XmlElement,
    lines: MutableList<TtmlLine>,
    offset: Double,
    divAgent: String?,
    divSongPart: TtmlSongPartInfo?,
) {
    val begin = timingAttr(p, "begin").ifEmpty { findFirstSpanBegin(p) } ?: return
    val startTime = parseTime(begin) + offset
    val end = timingAttr(p, "end")
    val endTime = if (end.isNotEmpty()) parseTime(end) + offset else null

    val agent = getAttr(p, "agent").ifEmpty { divAgent }
    val isPBackground = getAttr(p, "role") == "x-bg"
    val pKey = getAttr(p, "key").ifEmpty { getAttr(p, "for") }.ifEmpty { getAttr(p, "id") }.takeIf { it.isNotEmpty() }
    val pSongPartName = getAttr(p, "songPart").takeIf { it.isNotEmpty() }
    val pSongPart =
        if (pSongPartName != null) {
            TtmlSongPartInfo(name = pSongPartName, startSec = startTime, endSec = endTime)
        } else {
            divSongPart
        }

    val spanInfos = mutableListOf<TtmlWord>()
    val backgroundLines = mutableListOf<TtmlLine>()

    for (child in p.children) {
        if (child.localName == "span") {
            when (getAttr(child, "role")) {
                "x-bg" -> {
                    if (isPBackground) {
                        parseWordSpan(child, offset, spanInfos)
                    } else {
                        parseBackgroundSpan(child, startTime, offset, pSongPart)?.let { backgroundLines.add(it) }
                    }
                }

                "x-translation", "x-roman" -> {}

                else -> {
                    parseWordSpan(child, offset, spanInfos)
                }
            }
        }
    }

    val words = spanInfos.filter { it.text.isNotEmpty() }
    val lineText = if (words.isEmpty()) getDirectText(p).trim() else buildLineText(words)

    if (lineText.isNotEmpty()) {
        lines.add(TtmlLine(lineText, startTime, endTime, words, agent, isPBackground, pKey, pSongPart))
        lines.addAll(backgroundLines)
    } else if (backgroundLines.isNotEmpty()) {
        lines.addAll(backgroundLines)
    }
}

private fun parseWordSpan(
    span: XmlElement,
    offset: Double,
    out: MutableList<TtmlWord>,
) {
    val begin = timingAttr(span, "begin")
    val end = timingAttr(span, "end")
    if (begin.isEmpty() || end.isEmpty()) return
    val text = span.textContent
    val next = span.nextSibling
    val nextText = if (next is XmlText) next.value else ""
    val extraWhitespace =
        if (nextText.startsWith("\n") || nextText.startsWith("\r")) {
            if (nextText.trim().isEmpty()) "" else " "
        } else {
            nextText.takeWhile { it == ' ' || it == '\t' }
        }
    val fullText = text + extraWhitespace
    val hasTrailingSpace = fullText.isNotEmpty() && fullText.last().isWhitespace()
    val wordText = if (hasTrailingSpace && fullText.endsWith(' ')) fullText.dropLast(1) else fullText
    out.add(TtmlWord(wordText, parseTime(begin) + offset, parseTime(end) + offset, hasTrailingSpace))
}

private fun parseBackgroundSpan(
    span: XmlElement,
    parentStart: Double,
    offset: Double,
    songPart: TtmlSongPartInfo? = null,
): TtmlLine? {
    val begin = timingAttr(span, "begin")
    val end = timingAttr(span, "end")
    val start = if (begin.isNotEmpty()) parseTime(begin) + offset else parentStart
    val endTime = if (end.isNotEmpty()) parseTime(end) + offset else null

    val spanInfos = mutableListOf<TtmlWord>()
    var hasSpans = false
    for (child in span.children) {
        if (child.localName == "span") {
            hasSpans = true
            val role = getAttr(child, "role")
            if (role != "x-translation" && role != "x-roman") parseWordSpan(child, offset, spanInfos)
        }
    }

    if (!hasSpans) {
        val text = span.textContent.trim()
        return if (text.isEmpty()) null else TtmlLine(text, start, endTime, emptyList(), null, true, songPart = songPart)
    }

    val words = spanInfos.filter { it.text.isNotEmpty() }
    val text = if (words.isEmpty()) getDirectText(span).trim() else buildLineText(words)
    return if (text.isEmpty()) null else TtmlLine(text, start, endTime, words, null, true, songPart = songPart)
}

private fun extractSongwriters(
    element: XmlElement,
    out: MutableList<String>,
) {
    for (child in element.children) {
        val name = child.localName
        if (name == "songwriters" || name == "songWriters" || name == "iTunesMetadata" || name == "itunesmetadata" ||
            name == "sourceMetadata" ||
            name == "sourcemetadata"
        ) {
            extractSongwriters(child, out)
        } else if (name == "songwriter" || name == "songWriter") {
            val writer = child.textContent.trim()
            if (writer.isNotEmpty() && writer !in out) {
                out.add(writer)
            }
        }
    }
}

private fun getDirectText(el: XmlElement): String {
    val sb = StringBuilder()
    for (child in el.childNodes) {
        if (child is XmlText) {
            sb.append(child.value)
        } else if (child is XmlElement && child.localName == "span") {
            val role = getAttr(child, "role")
            if (role != "x-bg" && role != "x-translation" && role != "x-roman") sb.append(child.textContent)
        }
    }
    return sb.toString()
}

private fun buildLineText(words: List<TtmlWord>) =
    buildString {
        words.forEachIndexed { i, w ->
            append(w.text)
            if (w.hasTrailingSpace && !w.text.endsWith('-') && i < words.lastIndex) append(" ")
        }
    }.trim()

private fun parseTransliterations(
    root: XmlElement,
    globalOffset: Double,
): List<TtmlLine> {
    val result = mutableListOf<TtmlLine>()

    fun walkForTransliteration(node: XmlElement) {
        for (child in node.children) {
            val name = child.localName
            if (name == "transliteration" || name == "transliterations") {
                parseTransliterationElement(child, globalOffset, result)
            } else {
                walkForTransliteration(child)
            }
        }
    }
    walkForTransliteration(root)
    return result
}

private fun parseTransliterationElement(
    container: XmlElement,
    offset: Double,
    out: MutableList<TtmlLine>,
) {
    for (child in container.children) {
        val name = child.localName
        if (name == "transliteration") {
            parseTransliterationElement(child, offset, out)
        } else if (name == "text" || name == "p") {
            val lineKey =
                getAttr(
                    child,
                    "for",
                ).ifEmpty { getAttr(child, "key") }.ifEmpty { getAttr(child, "id") }.takeIf { it.isNotEmpty() }
            val spanInfos = mutableListOf<TtmlWord>()
            var firstBegin: String? = null
            var lastEnd: String? = null
            for (spanChild in child.children) {
                if (spanChild.localName == "span") {
                    val begin = timingAttr(spanChild, "begin")
                    val end = timingAttr(spanChild, "end")
                    if (firstBegin == null && begin.isNotEmpty()) firstBegin = begin
                    if (end.isNotEmpty()) lastEnd = end
                    parseWordSpan(spanChild, offset, spanInfos)
                }
            }
            val words = spanInfos.filter { it.text.isNotEmpty() }
            val lineText = if (words.isEmpty()) child.textContent.trim() else buildLineText(words)
            val startSec = (firstBegin?.let { parseTime(it) } ?: words.firstOrNull()?.startSec ?: 0.0) + offset
            val endSec = (lastEnd?.let { parseTime(it) } ?: words.lastOrNull()?.endSec)
            if (lineText.isNotEmpty()) {
                out.add(TtmlLine(lineText, startSec, endSec, words, null, false, lineKey))
            }
        }
    }
}

private fun parseTime(time: String): Double {
    val t = time.trim()
    val c1 = t.indexOf(':')
    if (c1 != -1) {
        val c2 = t.lastIndexOf(':')
        return if (c1 == c2) {
            (t.substring(0, c1).toIntOrNull() ?: 0) * 60.0 + (t.substring(c1 + 1).toDoubleOrNull() ?: 0.0)
        } else {
            (t.substring(0, c1).toIntOrNull() ?: 0) * 3600.0 + (t.substring(c1 + 1, c2).toIntOrNull() ?: 0) * 60.0 +
                (t.substring(c2 + 1).toDoubleOrNull() ?: 0.0)
        }
    }
    if (t.endsWith("ms")) return (t.substring(0, t.length - 2).toDoubleOrNull() ?: 0.0) / 1000.0
    val s = if (t.endsWith("s") || t.endsWith("m") || t.endsWith("h")) t.substring(0, t.length - 1) else t
    val v = s.toDoubleOrNull() ?: 0.0
    return when {
        t.endsWith("m") -> v * 60.0
        t.endsWith("h") -> v * 3600.0
        else -> v
    }
}

@Serializable
private data class UnisonResponse(
    val success: Boolean,
    val data: UnisonData? = null,
    val submitter: UnisonSubmitter? = null,
    val videoId: String? = null,
)

@Serializable
private data class UnisonData(
    val lyrics: String? = null,
    val format: String? = null,
    val submitter: UnisonSubmitter? = null,
    val videoId: String? = null,
)

@Serializable
private data class UnisonSubmitter(
    val displayName: String? = null,
    val keyId: String? = null,
)

class UnisonProvider(
    private val httpClient: HttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
        videoId: String? = null,
        isVideoContext: Boolean = false,
    ): String? {
        if (!videoId.isNullOrBlank()) {
            val byVideoId =
                fetchLyrics(title, artist, ignoreRetiming = isVideoContext, sourceVideoId = videoId) {
                    parameter("v", videoId)
                }
            if (byVideoId != null) return byVideoId
        }

        if (title.isNotBlank()) {
            val byMetadata =
                fetchLyrics(title, artist, ignoreRetiming = false, sourceVideoId = null) {
                    parameter("song", title)
                    if (artist.isNotBlank()) parameter("artist", artist)
                    if (!album.isNullOrBlank()) parameter("album", album)
                    if (duration != null && duration > 0) parameter("duration", duration / 1000)
                }
            if (byMetadata != null) return byMetadata
        }

        return null
    }

    private suspend fun fetchLyrics(
        title: String,
        artist: String,
        ignoreRetiming: Boolean = false,
        sourceVideoId: String?,
        builder: HttpRequestBuilder.() -> Unit,
    ): String? {
        val response =
            httpClient.prepareGet("https://unison.boidu.dev/lyrics") {
                expectMissingLyrics()
                builder()
                header("Accept", "application/json")
            }
        val responseBody = response.readLyricsText(MAX_LYRICS_RESPONSE_BYTES) ?: return null
        val body = json.decodeFromString<UnisonResponse>(responseBody)
        if (!body.success) return null
        val data = body.data ?: return null
        val lyrics = data.lyrics?.takeIf { it.isNotBlank() } ?: return null
        val submitter = data.submitter ?: body.submitter
        val lyricVideoId = data.videoId ?: body.videoId ?: sourceVideoId
        val lyricsCurator = submitter?.displayName?.takeIf { it.isNotBlank() }
        val lyricsCuratorId = submitter?.keyId?.takeIf { it.isNotBlank() }

        return when (data.format?.lowercase()) {
            "ttml" -> {
                parseTtmlToMlx(
                    lyrics,
                    title,
                    artist,
                    "Unison",
                    ignoreRetiming = ignoreRetiming,
                    sourceId = lyricVideoId,
                    lyricsCurator = lyricsCurator,
                    lyricsCuratorId = lyricsCuratorId,
                )
            }

            "lrc" -> {
                parseLrcToMlx(
                    lyrics,
                    title,
                    artist,
                    ignoreRetiming = ignoreRetiming,
                    sourceId = lyricVideoId,
                    lyricsCurator = lyricsCurator,
                    lyricsCuratorId = lyricsCuratorId,
                )
            }

            else -> {
                parsePlainToMlx(
                    lyrics,
                    title,
                    artist,
                    ignoreRetiming = ignoreRetiming,
                    sourceId = lyricVideoId,
                    lyricsCurator = lyricsCurator,
                    lyricsCuratorId = lyricsCuratorId,
                )
            }
        }.also { check(it.isNotBlank()) { "Invalid lyrics payload" } }
    }
}

private val lrcTimeTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

private fun parseLrcToMlx(
    lrc: String,
    title: String,
    artist: String,
    ignoreRetiming: Boolean = false,
    sourceId: String? = null,
    lyricsCurator: String? = null,
    lyricsCuratorId: String? = null,
): String {
    data class Entry(
        val ms: Long,
        val text: String,
    )
    val entries = mutableListOf<Entry>()

    lrc.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty()) return@forEach
        val matches = lrcTimeTag.findAll(line).toList()
        if (matches.isEmpty()) return@forEach
        val text = line.substring(matches.last().range.last + 1).trim()
        if (text.isEmpty()) return@forEach

        matches.forEach { m ->
            val min = m.groupValues[1].toLongOrNull() ?: return@forEach
            val sec = m.groupValues[2].toLongOrNull() ?: return@forEach
            val frac =
                when (m.groupValues[3].length) {
                    0 -> 0L
                    1 -> m.groupValues[3].toLong() * 100
                    2 -> m.groupValues[3].toLong() * 10
                    else -> m.groupValues[3].take(3).toLong()
                }
            entries.add(Entry(min * 60_000 + sec * 1_000 + frac, text))
        }
    }

    val builder =
        MlxBuilder()
            .meta(
                "Unison",
                ignoreRetiming = ignoreRetiming,
                sourceId = sourceId,
                lyricsCurator = lyricsCurator,
                lyricsCuratorId = lyricsCuratorId,
            ).song(title = title, artist = artist)
    val sourceOffsetMs = lrcOffsetMs(lrc)
    if (sourceOffsetMs != 0L) builder.unsyncedLine("[offset:$sourceOffsetMs]")
    entries.sortedBy { it.ms }.forEach { builder.line(it.ms, it.text) }
    return builder.build()
}

private fun parsePlainToMlx(
    text: String,
    title: String,
    artist: String,
    ignoreRetiming: Boolean = false,
    sourceId: String? = null,
    lyricsCurator: String? = null,
    lyricsCuratorId: String? = null,
): String {
    val builder =
        MlxBuilder()
            .meta(
                "Unison",
                ignoreRetiming = ignoreRetiming,
                sourceId = sourceId,
                lyricsCurator = lyricsCurator,
                lyricsCuratorId = lyricsCuratorId,
            ).song(title = title, artist = artist)
    text.lineSequence().forEach { builder.unsyncedLine(it.trimEnd()) }
    return builder.build()
}
