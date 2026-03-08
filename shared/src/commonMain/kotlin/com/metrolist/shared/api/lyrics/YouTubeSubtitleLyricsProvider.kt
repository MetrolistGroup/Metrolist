/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.state.GlobalYouTube
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class YouTubeSubtitleLyricsProvider(
    private val youtubeLyricsApi: YouTubeLyricsApi,
) {
    val name: String = "YouTube Subtitle"

    suspend fun getLyrics(videoId: String): Result<String> =
        runCatchingNonCancellation {
            getLyricsOrNull(videoId) ?: error("No transcript lyrics returned")
        }

    internal suspend fun getLyricsOrNull(videoId: String): String? {
        val captions = runCatchingNonCancellation { getCaptionTrackLyrics(videoId) }
        captions.getOrNull()?.let { return it }
        val transcript = runCatchingNonCancellation { getInnertubeTranscriptLyrics(videoId) }
        transcript.getOrNull()?.let { return it }
        captions.exceptionOrNull()?.let { throw it }
        transcript.exceptionOrNull()?.let { throw it }
        return null
    }

    suspend fun getAllTranscriptCueTracks(videoId: String): List<List<YouTubeTranscriptCue>> {
        val tracks = mutableListOf<List<YouTubeTranscriptCue>>()

        val captionTracks =
            runCatchingNonCancellation {
                val playerResponse = youtubeLyricsApi.player(videoId)
                playerResponse["captions"]
                    ?.jsonObject
                    ?.get("playerCaptionsTracklistRenderer")
                    ?.jsonObject
                    ?.get("captionTracks")
                    ?.jsonArray
                    .orEmpty()
            }.getOrNull()
                .orEmpty()

        val sortedTracks =
            captionTracks
                .take(MAX_ADVERTISED_TRACKS)
                .sortedBy { trackElement ->
                    val trackObj = trackElement.jsonObject
                    val kind = trackObj["kind"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val vssId = trackObj["vssId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val isAsr = kind.equals("asr", ignoreCase = true) || vssId.startsWith("a.", ignoreCase = true)
                    if (isAsr) 1 else 0
                }.take(MAX_CAPTION_TRACKS)

        var totalCues = 0
        for (trackElement in sortedTracks) {
            val trackObj = trackElement.jsonObject
            val baseUrl = trackObj["baseUrl"]?.jsonPrimitive?.contentOrNull ?: continue
            val cues = runCatchingNonCancellation { parseCaptionTrackUrl(baseUrl) }.getOrNull()
            if (!cues.isNullOrEmpty()) {
                val remaining = MAX_TOTAL_CUES - totalCues
                if (remaining <= 0) break
                tracks.add(cues.take(remaining))
                totalCues += minOf(cues.size, remaining)
            }
        }

        val defaultCues =
            runCatchingNonCancellation {
                youtubeLyricsApi.transcript(videoId)
            }.getOrNull()

        if (!defaultCues.isNullOrEmpty() && totalCues < MAX_TOTAL_CUES) {
            tracks.add(
                defaultCues
                    .asSequence()
                    .filter { it.text.length <= MAX_CUE_TEXT_LENGTH }
                    .take(minOf(MAX_CUES_PER_TRACK, MAX_TOTAL_CUES - totalCues))
                    .toList(),
            )
        }

        return tracks
    }

    private suspend fun parseCaptionTrackUrl(baseUrl: String): List<YouTubeTranscriptCue>? =
        runCatchingNonCancellation {
            val captionUrl =
                if (Regex("[?&]fmt=").containsMatchIn(baseUrl)) {
                    baseUrl.replace(Regex("([?&])fmt=[^&]*"), "$1fmt=json3")
                } else {
                    "$baseUrl&fmt=json3"
                }
            val captions = youtubeLyricsApi.getText(captionUrl)
            val response =
                Json
                    .parseToJsonElement(captions)
                    .jsonObject
            val events = response["events"]?.jsonArray.orEmpty()
            var totalTextLength = 0
            var totalWords = 0
            events.take(MAX_CUES_PER_TRACK).mapNotNull { event ->
                val obj = event.jsonObject
                val startMs = obj["tStartMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
                val durationMs = obj["dDurationMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val segs = obj["segs"]?.jsonArray
                if (segs.isNullOrEmpty() || segs.size > MAX_SEGMENTS_PER_CUE) return@mapNotNull null

                val hasWordOffsets =
                    segs.size > 1 &&
                        segs.any {
                            (
                                it.jsonObject["tOffsetMs"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    ?.toLongOrNull() ?: 0L
                            ) > 0L
                        }

                val words = if (hasWordOffsets) mutableListOf<YouTubeTranscriptWord>() else null
                val textBuilder = StringBuilder()

                for (segElement in segs) {
                    val segObj = segElement.jsonObject
                    val segUtf8 = segObj["utf8"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (textBuilder.length + segUtf8.length > MAX_CUE_TEXT_LENGTH ||
                        totalTextLength + textBuilder.length + segUtf8.length > MAX_TEXT_CHARS_PER_TRACK
                    ) {
                        return@mapNotNull null
                    }
                    textBuilder.append(segUtf8)

                    if (words != null) {
                        val tOffsetMs = segObj["tOffsetMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                        val wordStartMs = startMs + tOffsetMs
                        val cleanSeg = segUtf8.replace('\n', ' ').trim()
                        if (cleanSeg.isNotBlank() && cleanSeg != "♪" && cleanSeg != "♫") {
                            val splitWords = cleanSeg.split(whitespaceRegex).filter { it.isNotBlank() }
                            if (splitWords.isNotEmpty()) {
                                val remainingWords =
                                    minOf(MAX_WORDS_PER_CUE - words.size, MAX_WORDS_PER_TRACK - totalWords)
                                splitWords.take(remainingWords).forEach { w ->
                                    words.add(
                                        YouTubeTranscriptWord(
                                            text = w,
                                            startMs = wordStartMs,
                                            durationMs = 0L,
                                        ),
                                    )
                                    totalWords++
                                }
                            }
                        }
                    }
                }

                val text =
                    textBuilder
                        .toString()
                        .replace('\n', ' ')
                        .trim('♪')
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null

                totalTextLength += text.length
                YouTubeTranscriptCue(
                    text = text,
                    startMs = startMs,
                    durationMs = durationMs,
                    words = words?.takeIf { it.isNotEmpty() },
                )
            }
        }.getOrThrow()

    private val whitespaceRegex = Regex("""\s+""")

    private companion object {
        const val MAX_ADVERTISED_TRACKS = 64
        const val MAX_CAPTION_TRACKS = 8
        const val MAX_CUES_PER_TRACK = 5_000
        const val MAX_TOTAL_CUES = 20_000
        const val MAX_SEGMENTS_PER_CUE = 256
        const val MAX_WORDS_PER_CUE = 512
        const val MAX_WORDS_PER_TRACK = 50_000
        const val MAX_CUE_TEXT_LENGTH = 4_096
        const val MAX_TEXT_CHARS_PER_TRACK = 1024 * 1024
    }

    private suspend fun getInnertubeTranscriptLyrics(videoId: String): String? =
        runCatchingNonCancellation {
            val cues = youtubeLyricsApi.transcript(videoId)
            buildMlxFromCues(videoId, cues)
        }.getOrThrow()

    private suspend fun getCaptionTrackLyrics(videoId: String): String? =
        runCatchingNonCancellation {
            val playerResponse = youtubeLyricsApi.player(videoId)
            val captionTracks =
                playerResponse["captions"]
                    ?.jsonObject
                    ?.get("playerCaptionsTracklistRenderer")
                    ?.jsonObject
                    ?.get("captionTracks")
                    ?.jsonArray
                    .orEmpty()
            val preferredLang = GlobalYouTube.innerTube.locale.hl
            val track =
                captionTracks.firstOrNull { track ->
                    track.jsonObject["languageCode"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.startsWith(preferredLang) == true &&
                        track.jsonObject["kind"]?.jsonPrimitive?.contentOrNull != "asr"
                } ?: captionTracks.firstOrNull { track ->
                    track.jsonObject["kind"]?.jsonPrimitive?.contentOrNull != "asr"
                } ?: captionTracks.firstOrNull { track ->
                    track.jsonObject["languageCode"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.startsWith(preferredLang) == true
                } ?: captionTracks.firstOrNull()

            val baseUrl =
                track
                    ?.jsonObject
                    ?.get("baseUrl")
                    ?.jsonPrimitive
                    ?.contentOrNull ?: return@runCatchingNonCancellation null

            val cues = parseCaptionTrackUrl(baseUrl) ?: return@runCatchingNonCancellation null
            buildMlxFromCues(videoId, cues)
        }.getOrThrow()

    private fun buildMlxFromCues(
        videoId: String,
        cues: List<YouTubeTranscriptCue>,
    ): String? {
        val builder =
            MlxBuilder()
                .meta(source = name)
                .song(youtubeId = videoId)
        var hasLyrics = false
        var totalTextLength = 0
        for (cue in cues.take(MAX_CUES_PER_TRACK)) {
            if (cue.text.length > MAX_CUE_TEXT_LENGTH || totalTextLength + cue.text.length > MAX_TEXT_CHARS_PER_TRACK) break
            val cueEndMs = (cue.startMs + cue.durationMs).coerceAtLeast(cue.startMs + 1L)
            val timedWords = cue.words?.takeIf { it.isNotEmpty() }?.toMlxWords(cue.startMs, cueEndMs)
            if (!timedWords.isNullOrEmpty()) {
                builder.line(startMs = cue.startMs, endMs = cueEndMs, words = timedWords)
            } else if (cue.durationMs > 0L) {
                builder.line(cue.startMs, cueEndMs, cue.text)
            } else {
                builder.line(cue.startMs, cue.text)
            }
            hasLyrics = true
            totalTextLength += cue.text.length
        }
        return builder.build().takeIf { hasLyrics }
    }

    private fun List<YouTubeTranscriptWord>.toMlxWords(
        cueStartMs: Long,
        cueEndMs: Long,
    ): List<MlxWord> {
        val words =
            flatMap { word ->
                word.text.trim().split(whitespaceRegex).filter(String::isNotBlank).map { text ->
                    text to word.startMs.coerceIn(cueStartMs, (cueEndMs - 1L).coerceAtLeast(cueStartMs))
                }
            }
        if (words.isEmpty()) return emptyList()

        return buildList(words.size) {
            var groupStartIndex = 0
            while (groupStartIndex < words.size) {
                val groupStartMs = words[groupStartIndex].second
                var groupEndIndex = groupStartIndex + 1
                while (groupEndIndex < words.size && words[groupEndIndex].second == groupStartMs) groupEndIndex++
                val nextGroupStartMs = words.getOrNull(groupEndIndex)?.second ?: cueEndMs
                val groupEndMs = nextGroupStartMs.coerceAtLeast(groupStartMs + 1L).coerceAtMost(cueEndMs)
                val groupSize = groupEndIndex - groupStartIndex
                val groupDurationMs = (groupEndMs - groupStartMs).coerceAtLeast(groupSize.toLong())

                for (index in groupStartIndex until groupEndIndex) {
                    val offset = index - groupStartIndex
                    val startMs = groupStartMs + groupDurationMs * offset / groupSize
                    val endMs = (groupStartMs + groupDurationMs * (offset + 1) / groupSize).coerceAtLeast(startMs + 1L)
                    add(
                        MlxWord(
                            text = words[index].first,
                            startMs = startMs,
                            endMs = endMs,
                            hasTrailingSpace = index < words.lastIndex,
                        ),
                    )
                }
                groupStartIndex = groupEndIndex
            }
        }
    }

    private inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> =
        runCatching(block).onFailure { error ->
            if (error is CancellationException) throw error
        }
}
