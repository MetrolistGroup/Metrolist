/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class KuGouLyricsProvider(
    private val httpClient: HttpClient,
) {
    companion object {
        private const val DURATION_TOLERANCE = 8
        private val json = Json { ignoreUnknownKeys = true }
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): String? {
        val keyword = generateKeyword(title, artist, album)
        val searchResult =
            searchSongs(keyword, duration)
                ?: keyword.album
                    ?.takeIf { it.isNotBlank() }
                    ?.let { searchSongs(keyword.copy(album = null), duration) }
                ?: return null
        val lrc = downloadLyrics(searchResult.first, searchResult.second) ?: return null
        return lrcToMlx(lrc.normalize(), title, artist)
    }

    private suspend fun searchSongs(
        keyword: Keyword,
        duration: Int,
    ): Pair<Long, String>? {
        val query =
            buildString {
                append(keyword.title)
                if (keyword.artist.isNotBlank()) {
                    append(" - ")
                    append(keyword.artist)
                }
                if (!keyword.album.isNullOrBlank()) {
                    append(' ')
                    append(keyword.album)
                }
            }.trim()
        if (query.isEmpty()) return null
        val response: String =
            httpClient
                .prepareGet("https://lyrics.kugou.com/search") {
                    expectMissingLyrics()
                    parameter("ver", 1)
                    parameter("man", "yes")
                    parameter("client", "pc")
                    parameter("keyword", query)
                    if (duration > 0) parameter("duration", duration * 1_000L)
                }.readLyricsText() ?: return null
        val root = json.parseToJsonElement(response).jsonObject
        val candidates = checkNotNull(root["candidates"]?.jsonArray) { "Missing lyrics candidates" }
        val parsedCandidates =
            candidates.mapNotNull { el ->
                val obj = el.jsonObject
                Triple(
                    obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    obj["accesskey"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    obj["duration"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                )
            }
        if (duration <= 0) {
            return parsedCandidates.firstOrNull()?.let { Pair(it.first, it.second) }
        }
        val best = parsedCandidates.minByOrNull { (_, _, d) -> durationDiffSeconds(d, duration) } ?: return null
        if (durationDiffSeconds(best.third, duration) > DURATION_TOLERANCE) return null
        return Pair(best.first, best.second)
    }

    // KuGou reports milliseconds; fixtures use seconds.
    private fun durationDiffSeconds(
        candidateDuration: Long,
        targetSeconds: Int,
    ): Double {
        val secDiff = kotlin.math.abs(candidateDuration - targetSeconds).toDouble()
        val msDiff = kotlin.math.abs(candidateDuration - targetSeconds * 1_000L) / 1_000.0
        return minOf(secDiff, msDiff)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadLyrics(
        id: Long,
        accessKey: String,
    ): String? {
        val response: String =
            httpClient
                .prepareGet("https://lyrics.kugou.com/download") {
                    expectMissingLyrics()
                    parameter("fmt", "lrc")
                    parameter("charset", "utf8")
                    parameter("client", "pc")
                    parameter("ver", 1)
                    parameter("id", id)
                    parameter("accesskey", accessKey)
                }.readLyricsText() ?: return null
        val root = json.parseToJsonElement(response).jsonObject
        val content = checkNotNull(root["content"]?.jsonPrimitive?.content) { "Missing lyrics content" }
        return Base64.decode(content).decodeToString()
    }

    private fun generateKeyword(
        title: String,
        artist: String,
        album: String?,
    ) = Keyword(normalizeTitle(title), normalizeArtist(artist), album)

    private fun normalizeTitle(title: String) =
        title
            .replace(Regex("\\(.*\\)"), "")
            .replace(Regex("（.*）"), "")
            .replace(Regex("「.*」"), "")
            .replace(Regex("『.*』"), "")
            .replace(Regex("<.*>"), "")
            .replace(Regex("《.*》"), "")
            .replace(Regex("〈.*〉"), "")
            .replace(Regex("＜.*＞"), "")
            .trim()

    private fun normalizeArtist(artist: String) =
        artist
            .replace(", ", "、")
            .replace(" & ", "、")
            .replace(".", "")
            .replace("和", "、")
            .replace(Regex("\\(.*\\)"), "")
            .replace(Regex("（.*）"), "")
            .trim()

    private fun String.normalize(): String {
        val credits =
            Regex(
                "^(?:作词|作曲|词|曲|编曲|制作人|词曲|填词|混音|录音|监制|Lyrics|Composer|Composed by|Written by|Arranger|Producer)\\s*[:：]",
                RegexOption.IGNORE_CASE,
            )
        return lineSequence()
            .filter { it.contains(lrcTimestampRegex) }
            .filterNot { credits.containsMatchIn(it.replace(lrcTimestampRegex, "").trim()) }
            .joinToString("\n")
    }
}

data class Keyword(
    val title: String,
    val artist: String,
    val album: String? = null,
)

private val lrcTimestampRegex = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?\]""")

internal fun lrcToMlx(
    lrc: String,
    title: String?,
    artist: String?,
    source: String = "LRC",
    sourceId: String? = null,
): String? {
    val builder = MlxBuilder().meta(source, sourceId = sourceId).song(title = title, artist = artist)
    val sourceOffsetMs = lrcOffsetMs(lrc)
    if (sourceOffsetMs != 0L) builder.unsyncedLine("[offset:$sourceOffsetMs]")
    val timed = mutableListOf<Pair<Long, String>>()
    val plain = mutableListOf<String>()
    for (rawLine in lrc.lines()) {
        val line = rawLine.trim()
        var offset = 0
        val times = mutableListOf<Long>()
        while (true) {
            val match = lrcTimestampRegex.matchAt(line, offset) ?: break
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            val millis = match.groupValues[3].padEnd(3, '0').toLong()
            times += minutes * 60_000L + seconds * 1_000L + millis
            offset = match.range.last + 1
        }
        val text = line.substring(offset).trim()
        if (text.isBlank()) continue
        if (times.isNotEmpty()) {
            times.forEach { timed += it to text }
        } else if (!line.startsWith("[")) {
            plain += line
        }
    }
    timed.sortedBy { it.first }.forEach { (time, text) -> builder.line(time, text) }
    plain.forEach(builder::unsyncedLine)
    return if (timed.isEmpty() && plain.isEmpty()) null else builder.build()
}
