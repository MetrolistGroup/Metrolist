/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.innertubex.models.YouTubeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class TimedLyricLine(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

class YouTubeLyricsProvider(
    private val youtubeLyricsApi: YouTubeLyricsApi,
) {
    suspend fun getLyrics(videoId: String): String? {
        return try {
            val nextResponse = youtubeLyricsApi.watchNext(videoId)
            val lyricsEndpointId = extractLyricsEndpointId(nextResponse) ?: return null

            val (androidResult, songwriters) =
                coroutineScope {
                    val androidDeferred =
                        async {
                            runCatching {
                                youtubeLyricsApi.browse(ANDROID_MUSIC_LYRICS_CLIENT, lyricsEndpointId)
                            }.onFailure { if (it is CancellationException) throw it }
                        }
                    val songwritersDeferred = async { getSongwriters(videoId) }
                    androidDeferred.await() to songwritersDeferred.await()
                }
            val androidResponse = androidResult.getOrNull()

            val timedLines = androidResponse?.let(::extractTimedLyrics).orEmpty()
            val hasTimings = timedLines.any { it.startMs > 0L || it.endMs > 0L }

            if (hasTimings) {
                MlxBuilder()
                    .meta(source = "YouTube")
                    .apply {
                        if (songwriters.isNotEmpty()) {
                            song(songwriters = songwriters)
                        }
                        timedLines.forEach { line ->
                            if (line.text.isNotBlank()) {
                                if (line.endMs > line.startMs) {
                                    this.line(line.startMs, line.endMs, line.text)
                                } else {
                                    this.line(line.startMs, line.text)
                                }
                            }
                        }
                    }.build()
            } else {
                val plainText =
                    androidResponse?.let(::extractLyricsText)
                        ?: extractLyricsText(youtubeLyricsApi.browse(lyricsEndpointId))
                        ?: run {
                            androidResult.exceptionOrNull()?.let { throw it }
                            return null
                        }

                MlxBuilder()
                    .meta(source = "YouTube")
                    .apply {
                        if (songwriters.isNotEmpty()) {
                            song(songwriters = songwriters)
                        }
                        plainText.lines().forEach { line -> if (line.isNotBlank()) unsyncedLine(line.trim()) }
                    }.build()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw error
        }
    }

    suspend fun getSongwriters(videoId: String): List<String> =
        try {
            val browseResponse = youtubeLyricsApi.browse("MPTC$videoId")
            extractSongwriters(browseResponse)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }

    private fun extractLyricsEndpointId(nextResponse: JsonObject): String? {
        val tabs =
            nextResponse["contents"]
                ?.jsonObject
                ?.get("singleColumnMusicWatchNextResultsRenderer")
                ?.jsonObject
                ?.get("tabbedRenderer")
                ?.jsonObject
                ?.get("watchNextTabbedResultsRenderer")
                ?.jsonObject
                ?.get("tabs")
                ?.jsonArray

        tabs?.forEach { tab ->
            val tabRenderer = tab.jsonObject["tabRenderer"]?.jsonObject ?: return@forEach
            val browseId =
                tabRenderer["endpoint"]
                    ?.jsonObject
                    ?.get("browseEndpoint")
                    ?.jsonObject
                    ?.get("browseId")
                    ?.jsonPrimitive
                    ?.contentOrNull
            if (browseId != null && (browseId.startsWith("MPLYt") || tabRenderer["title"]?.jsonPrimitive?.contentOrNull == "Lyrics")) {
                return browseId
            }
        }
        return findBrowseId(nextResponse)
    }

    private fun findBrowseId(element: JsonElement): String? {
        when (element) {
            is JsonObject -> {
                val browseId = element["browseId"]?.jsonPrimitive?.contentOrNull
                if (browseId != null && browseId.startsWith("MPLYt")) {
                    return browseId
                }
                for (child in element.values) {
                    val found = findBrowseId(child)
                    if (found != null) return found
                }
            }

            is JsonArray -> {
                for (child in element) {
                    val found = findBrowseId(child)
                    if (found != null) return found
                }
            }

            else -> {}
        }
        return null
    }

    companion object {
        val ANDROID_MUSIC_LYRICS_CLIENT: YouTubeClient =
            YouTubeClient.ANDROID_MUSIC.copy(
                clientVersion = "8.25.52",
                userAgent = "com.google.android.apps.youtube.music/8.25.52 (Linux; U; Android 14) gzip",
                androidSdkVersion = "34",
            )

        fun extractTimedLyrics(browseResponse: JsonObject): List<TimedLyricLine> {
            val timedLyricsArray = findTimedLyricsArray(browseResponse)
            if (timedLyricsArray != null) {
                val lines = mutableListOf<TimedLyricLine>()
                for (element in timedLyricsArray) {
                    val obj = (element as? JsonObject) ?: continue
                    val text = obj["lyricLine"]?.jsonPrimitive?.contentOrNull ?: continue
                    val cueRange = obj["cueRange"]?.jsonObject
                    val startMs =
                        cueRange
                            ?.get("startTimeMilliseconds")
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.toLongOrNull() ?: 0L
                    val endMs =
                        cueRange
                            ?.get("endTimeMilliseconds")
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.toLongOrNull() ?: 0L
                    lines.add(TimedLyricLine(text = text, startMs = startMs, endMs = endMs))
                }
                if (lines.isNotEmpty()) return lines
            }

            val lines = mutableListOf<TimedLyricLine>()
            collectTimedLyricLines(browseResponse, lines)
            return lines
        }

        private fun findTimedLyricsArray(element: JsonElement): JsonArray? {
            when (element) {
                is JsonObject -> {
                    element["timedLyricsData"]?.jsonArray?.let { return it }
                    for (child in element.values) {
                        val found = findTimedLyricsArray(child)
                        if (found != null) return found
                    }
                }

                is JsonArray -> {
                    for (child in element) {
                        val found = findTimedLyricsArray(child)
                        if (found != null) return found
                    }
                }

                else -> {}
            }
            return null
        }

        private fun collectTimedLyricLines(
            element: JsonElement,
            result: MutableList<TimedLyricLine>,
        ) {
            when (element) {
                is JsonObject -> {
                    val text = element["lyricLine"]?.jsonPrimitive?.contentOrNull
                    val cueRange = element["cueRange"]?.jsonObject
                    if (text != null && cueRange != null) {
                        val startMs = cueRange["startTimeMilliseconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                        val endMs = cueRange["endTimeMilliseconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                        result.add(TimedLyricLine(text = text, startMs = startMs, endMs = endMs))
                    }
                    for (child in element.values) {
                        collectTimedLyricLines(child, result)
                    }
                }

                is JsonArray -> {
                    for (child in element) {
                        collectTimedLyricLines(child, result)
                    }
                }

                else -> {}
            }
        }

        fun extractLyricsText(browseResponse: JsonObject): String? {
            val description =
                browseResponse["contents"]
                    ?.jsonObject
                    ?.get("sectionListRenderer")
                    ?.jsonObject
                    ?.get("contents")
                    ?.jsonArray
                    ?.getOrNull(0)
                    ?.jsonObject
                    ?.get("musicDescriptionShelfRenderer")
                    ?.jsonObject
                    ?.get("description")
                    ?.jsonObject
                    ?.get("runs")
                    ?.jsonArray
                    ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }
            if (!description.isNullOrBlank()) return description

            return findDescriptionRunsText(browseResponse)
        }

        private fun findDescriptionRunsText(element: JsonElement): String? {
            when (element) {
                is JsonObject -> {
                    val shelf = element["musicDescriptionShelfRenderer"]?.jsonObject
                    val runs =
                        shelf
                            ?.get("description")
                            ?.jsonObject
                            ?.get("runs")
                            ?.jsonArray
                    if (runs != null) {
                        val text = runs.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }
                        if (text.isNotBlank()) return text
                    }
                    for (child in element.values) {
                        val found = findDescriptionRunsText(child)
                        if (found != null) return found
                    }
                }

                is JsonArray -> {
                    for (child in element) {
                        val found = findDescriptionRunsText(child)
                        if (found != null) return found
                    }
                }

                else -> {}
            }
            return null
        }

        fun extractSongwriters(browseResponse: JsonObject): List<String> {
            val sections = findSections(browseResponse) ?: return emptyList()
            val writers = mutableListOf<String>()
            for (sectionElement in sections) {
                val sectionObj = (sectionElement as? JsonObject) ?: continue
                val contentSection = sectionObj["dismissableDialogContentSectionRenderer"]?.jsonObject ?: sectionObj
                val titleObj = contentSection["title"]?.jsonObject
                val titleText =
                    titleObj
                        ?.get("runs")
                        ?.jsonArray
                        ?.joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: "" }
                        ?: titleObj?.get("simpleText")?.jsonPrimitive?.contentOrNull
                        ?: contentSection["title"]?.jsonPrimitive?.contentOrNull
                        ?: ""

                if (!isSongwritersTitle(titleText)) continue

                val subtitleObj = contentSection["subtitle"]?.jsonObject
                val runs = subtitleObj?.get("runs")?.jsonArray
                if (runs != null) {
                    for (run in runs) {
                        val text = (run as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: continue
                        text.split('\n', ',').forEach { part ->
                            val trimmed = part.trim()
                            if (trimmed.isNotBlank()) {
                                writers.add(trimmed)
                            }
                        }
                    }
                } else {
                    val simpleText =
                        subtitleObj?.get("simpleText")?.jsonPrimitive?.contentOrNull
                            ?: contentSection["subtitle"]?.jsonPrimitive?.contentOrNull
                    simpleText?.split('\n', ',')?.forEach { part ->
                        val trimmed = part.trim()
                        if (trimmed.isNotBlank()) {
                            writers.add(trimmed)
                        }
                    }
                }
            }
            return writers.distinct()
        }

        private fun findSections(browseResponse: JsonObject): JsonArray? {
            val actions = browseResponse["onResponseReceivedActions"]?.jsonArray
            if (actions != null) {
                for (action in actions) {
                    val popup =
                        (action as? JsonObject)
                            ?.get("openPopupAction")
                            ?.jsonObject
                            ?.get("popup")
                            ?.jsonObject ?: continue
                    val dialog =
                        popup["dismissableDialogRenderer"]?.jsonObject
                            ?: popup.values.filterIsInstance<JsonObject>().firstOrNull { it.containsKey("sections") }
                    val sections = dialog?.get("sections")?.jsonArray
                    if (sections != null) return sections
                }
            }
            val popup = browseResponse["popup"]?.jsonObject
            if (popup != null) {
                val dialog =
                    popup["dismissableDialogRenderer"]?.jsonObject
                        ?: popup.values.filterIsInstance<JsonObject>().firstOrNull { it.containsKey("sections") }
                val sections = dialog?.get("sections")?.jsonArray
                if (sections != null) return sections
            }
            return null
        }

        private fun isSongwritersTitle(titleText: String): Boolean {
            val lower = titleText.trim().lowercase()
            if (lower.isEmpty()) return false
            if (lower.contains("perform") ||
                lower.contains("produc") ||
                lower.contains("metadata") ||
                lower.contains("provid") ||
                lower.contains("dibawakan") ||
                lower.contains("diproduksi") ||
                lower.contains("interpret")
            ) {
                return false
            }
            return lower.contains("written") ||
                lower.contains("writer") ||
                lower.contains("ditulis") ||
                lower.contains("geschrieben") ||
                lower.contains("écrit") ||
                lower.contains("ecrit") ||
                lower.contains("escrito") ||
                lower.contains("scritto") ||
                lower.contains("schrijver") ||
                lower.contains("geschreven") ||
                lower.contains("auteur") ||
                lower.contains("autor") ||
                lower.contains("autori") ||
                lower.contains("twórcy") ||
                lower.contains("napisane") ||
                lower.contains("yazan") ||
                lower.contains("söz yazarı") ||
                lower.contains("soz yazari") ||
                lower.contains("композитор") ||
                lower.contains("автор") ||
                lower.contains("تأليف") ||
                lower.contains("作詞") ||
                lower.contains("作曲") ||
                lower.contains("작사") ||
                lower.contains("composer")
        }
    }
}
