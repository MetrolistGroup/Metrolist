/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.AgentInfo
import com.metrolist.shared.model.AgentType
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.MlxColors
import com.metrolist.shared.model.MlxDocument
import com.metrolist.shared.model.MlxMeta
import com.metrolist.shared.model.MlxSong
import com.metrolist.shared.model.MlxSongPart
import com.metrolist.shared.model.WordTimestamp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal object MlxJsonParser {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    fun parse(text: String): MlxDocument? =
        runCatching {
            val source = json.decodeFromString<JsonMlx>(text)
            if (source.version != 2 || source.lines.isEmpty()) return null
            if (source.lines.size > MlxParser.MAX_LINES || source.parts.size > MlxParser.MAX_LINES ||
                source.agents.size > MlxParser.MAX_LINES
            ) {
                return null
            }
            var words = 0
            for (line in source.lines) {
                words += line.words.size + line.backgroundWords.size + line.romanizedWords.size
                if (line.backgroundWords.isEmpty() && !line.backgroundText.isNullOrBlank()) words++
                if (words > MlxParser.MAX_WORD_MARKERS) return null
                val chars =
                    line.text.length.toLong() + line.backgroundText.orEmpty().length +
                        line.romanizedText.orEmpty().length + line.translatedText.orEmpty().length +
                        line.words.sumOf { it.text.length.toLong() } + line.backgroundWords.sumOf { it.text.length.toLong() } +
                        line.romanizedWords.sumOf { it.text.length.toLong() }
                if (chars > MlxParser.MAX_LINE_CHARS) return null
            }
            val parts =
                source.parts.map {
                    MlxSongPart(
                        name = it.name,
                        startMs = it.startMs ?: 0,
                        endMs = it.endMs,
                    )
                }
            val lineStarts =
                source.lines
                    .mapNotNull { it.startMs }
                    .distinct()
                    .sorted()
            MlxDocument(
                meta =
                    MlxMeta(
                        version = source.version.toString(),
                        ignoreRetiming = source.ignoreRetiming,
                        lyricSource = source.source?.name,
                        lastFetched = source.source?.lastFetched,
                        provider = source.provider,
                        subprovider = source.subprovider,
                        sourceFormat = source.sourceFormat,
                        syncType = source.syncType,
                        sourceId = source.source?.id,
                        curator = source.source?.curator,
                        lyricsCurator = source.source?.lyricsCurator,
                        lyricsCuratorId = source.source?.lyricsCuratorId,
                        copyright = source.source?.copyright,
                    ),
                song =
                    MlxSong(
                        title = source.song.title,
                        artist = source.song.artist,
                        youtubeId = source.song.youtubeId,
                        songwriters = source.song.songwriters,
                        album = source.song.album,
                        durationMs = source.song.durationMs,
                        isrc = source.song.isrc,
                        platformId = source.song.platformId,
                        language = source.song.language,
                        romanizationLanguage = source.song.romanizationLanguage,
                        translationLanguage = source.song.translationLanguage,
                    ),
                agents =
                    source.agents.mapValues { (id, agent) ->
                        AgentInfo(id, AgentType.fromString(agent.type), agent.name, agent.color)
                    },
                lines =
                    source.lines.map { line ->
                        // Untimed TTML paragraphs can inherit the song's end without a line start.
                        val lineEndMs = line.endMs?.takeIf { line.startMs != null }
                        val backgroundWords = line.backgroundWords.map { it.timestamp(background = true) }.toMutableList()
                        if (backgroundWords.isEmpty() && !line.backgroundText.isNullOrBlank()) {
                            backgroundWords +=
                                WordTimestamp(
                                    text = line.backgroundText,
                                    startTime = (line.startMs ?: 0) / 1000.0,
                                    endTime = (lineEndMs ?: line.startMs ?: 0) / 1000.0,
                                    hasTrailingSpace = false,
                                    isBackground = true,
                                    isLineTimed = true,
                                )
                        }
                        val words = (line.words.map { it.timestamp() } + backgroundWords).takeIf { it.isNotEmpty() }
                        LyricsEntry(
                            time = line.startMs ?: 0,
                            text =
                                line.text.ifBlank {
                                    line.words.joinToString("") { it.text }.ifBlank {
                                        line.backgroundText?.takeIf { it.isNotBlank() }
                                            ?: line.backgroundWords.joinToString("") { it.text }
                                    }
                                },
                            words = words,
                            agent = line.agent,
                            endTime =
                                lineEndMs ?: words?.maxOfOrNull { (it.endTime * 1000).toLong() } ?: line.startMs?.let { start ->
                                    lineStarts.getOrNull(lineStarts.binarySearch(start) + 1)
                                        ?: source.song.durationMs?.takeIf { it > start }
                                },
                            isBackground = line.background,
                            translatedText = line.translatedText,
                            romanizedText = line.romanizedText,
                            romanizedWords = line.romanizedWords.map { it.timestamp() }.takeIf { it.isNotEmpty() },
                            songPart = line.part?.let(parts::getOrNull)?.name,
                            backgroundText = line.backgroundText,
                            backgroundTranslatedText = line.backgroundTranslatedText,
                        )
                    },
                songParts = parts,
                colors = source.colors,
            )
        }.getOrNull()

    internal fun withTranslations(
        text: String,
        translations: List<String?>,
    ): String {
        val document = json.parseToJsonElement(text).jsonObject
        val lines =
            document.getValue("lines").jsonArray.mapIndexed { index, line ->
                translations[index]?.let { translation ->
                    JsonObject(line.jsonObject + ("translatedText" to JsonPrimitive(translation)))
                } ?: line
            }
        return json.encodeToString(JsonObject(document + ("lines" to JsonArray(lines))))
    }

    private fun JsonWord.timestamp(background: Boolean = false): WordTimestamp =
        WordTimestamp(
            text = text.removeSuffix(" "),
            startTime = startMs / 1000.0,
            endTime = endMs / 1000.0,
            hasTrailingSpace = text.endsWith(' '),
            isBackground = background,
            isLineTimed = isLineTimed,
        )
}

@Serializable
private data class JsonMlx(
    val version: Int,
    val ignoreRetiming: Boolean = false,
    val provider: String? = null,
    val subprovider: String? = null,
    val sourceFormat: String? = null,
    val syncType: String? = null,
    val source: JsonSource? = null,
    val song: JsonSong = JsonSong(),
    val colors: MlxColors? = null,
    val agents: Map<String, JsonAgent> = emptyMap(),
    val parts: List<JsonPart> = emptyList(),
    val lines: List<JsonLine> = emptyList(),
)

@Serializable
private data class JsonSource(
    val name: String? = null,
    val id: String? = null,
    val curator: String? = null,
    val lyricsCurator: String? = null,
    val lyricsCuratorId: String? = null,
    val copyright: String? = null,
    val lastFetched: String? = null,
)

@Serializable
private data class JsonSong(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val youtubeId: String? = null,
    val isrc: String? = null,
    val platformId: String? = null,
    val language: String? = null,
    val songwriters: List<String> = emptyList(),
    val romanizationLanguage: String? = null,
    val translationLanguage: String? = null,
)

@Serializable
private data class JsonAgent(
    val type: String = "person",
    val name: String? = null,
    val color: String? = null,
)

@Serializable
private data class JsonPart(
    val name: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
)

@Serializable
private data class JsonLine(
    val startMs: Long? = null,
    val endMs: Long? = null,
    val text: String = "",
    val background: Boolean = false,
    val agent: String? = null,
    val part: Int? = null,
    val words: List<JsonWord> = emptyList(),
    val backgroundWords: List<JsonWord> = emptyList(),
    val backgroundText: String? = null,
    val backgroundTranslatedText: String? = null,
    val romanizedText: String? = null,
    val romanizedWords: List<JsonWord> = emptyList(),
    val translatedText: String? = null,
)

@Serializable
private data class JsonWord(
    val text: String,
    val isLineTimed: Boolean = false,
    val startMs: Long,
    val endMs: Long,
)
