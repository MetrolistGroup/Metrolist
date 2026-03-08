package com.metrolist.shared.api

import com.metrolist.shared.api.YouTubeResponseParser.parseItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.toSongItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object YouTubePodcastEpisodeParser {
    internal fun parseNewPodcastEpisodes(response: JsonObject): List<SongItem> {
        val episodes = mutableListOf<SongItem>()

        fun runText(element: JsonElement?): String? =
            (element as? JsonObject)
                ?.get("runs")
                ?.let { runsElement ->
                    (runsElement as? JsonArray)
                        ?.joinToString("") { run ->
                            (run as? JsonObject)
                                ?.get("text")
                                ?.let { textElement ->
                                    (textElement as? JsonPrimitive)?.contentOrNull
                                }.orEmpty()
                        }?.takeIf { it.isNotBlank() }
                }
                ?: (element as? JsonObject)
                    ?.get("simpleText")
                    ?.let { (it as? JsonPrimitive)?.contentOrNull }

        fun stringValue(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

        fun watchEndpoint(renderer: JsonObject): JsonObject? {
            fun findEndpoint(candidate: JsonElement?): JsonObject? {
                val candidateObject = candidate as? JsonObject ?: return null
                val direct = candidateObject["watchEndpoint"]?.jsonObject
                if (direct?.get("videoId") != null) return direct
                val command = candidateObject["innertubeCommand"]?.jsonObject
                val commandEndpoint = command?.get("watchEndpoint")?.jsonObject
                if (commandEndpoint?.get("videoId") != null) return commandEndpoint
                val commandMetadata = candidateObject["commandMetadata"]?.jsonObject
                val nestedCommand = commandMetadata?.get("webCommandMetadata")?.jsonObject
                if (nestedCommand?.get("url") != null) {
                    val url = nestedCommand["url"]?.jsonPrimitive?.contentOrNull
                    val videoId = url?.substringAfter("watch?v=", "")?.substringBefore('&')
                    if (!videoId.isNullOrBlank()) {
                        return buildJsonObject { put("videoId", JsonPrimitive(videoId)) }
                    }
                }
                return null
            }

            listOf(
                renderer["onTap"],
                renderer["navigationEndpoint"],
                renderer["playNavigationEndpoint"],
                renderer["playlistItemData"],
            ).forEach { candidate ->
                findEndpoint(candidate)?.let { return it }
            }
            return null
        }

        fun thumbnailUrl(renderer: JsonObject): String? {
            val thumbnailContainer = renderer["thumbnailRenderer"]?.jsonObject ?: renderer["thumbnail"]?.jsonObject
            val thumbnails =
                thumbnailContainer
                    ?.get("musicThumbnailRenderer")
                    ?.jsonObject
                    ?.get("thumbnail")
                    ?.jsonObject
                    ?.get("thumbnails")
                    ?.jsonArray
                    ?: thumbnailContainer?.get("thumbnails")?.jsonArray
            return thumbnails
                ?.lastOrNull()
                ?.jsonObject
                ?.get("url")
                ?.let { stringValue(it) }
                ?.replace(Regex("=w\\d+-h\\d+.*$"), "=s0")
        }

        fun fallbackRdpnItem(element: JsonObject): SongItem? {
            val renderer =
                element["musicMultiRowListItemRenderer"]?.jsonObject
                    ?: element["musicResponsiveListItemRenderer"]?.jsonObject
                    ?: element["musicTwoRowItemRenderer"]?.jsonObject
                    ?: return null
            val playlistItemData = renderer["playlistItemData"]?.jsonObject
            val endpoint = watchEndpoint(renderer)
            val videoId =
                endpoint?.get("videoId")?.let(::stringValue)
                    ?: playlistItemData?.get("videoId")?.let(::stringValue)
                    ?: renderer["videoId"]?.let(::stringValue)
                    ?: return null
            val title =
                runText(renderer["title"])
                    ?: runText(renderer["secondTitle"])
                    ?: renderer["flexColumns"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("musicResponsiveListItemFlexColumnRenderer")
                        ?.jsonObject
                        ?.get("text")
                        ?.let(::runText)
                    ?: return null
            val subtitleRuns =
                renderer["secondSubtitle"]?.jsonObject?.get("runs")?.jsonArray
                    ?: renderer["secondarySubtitle"]?.jsonObject?.get("runs")?.jsonArray
                    ?: renderer["secondTitle"]?.jsonObject?.get("runs")?.jsonArray
            val artists =
                subtitleRuns
                    ?.mapNotNull { run ->
                        val runObject = run as? JsonObject ?: return@mapNotNull null
                        val name = runObject["text"]?.let(::stringValue)?.trim().orEmpty()
                        val isDate =
                            name.matches(
                                Regex(
                                    "(?i)^(?:today|yesterday|just now|\\d+\\s+(?:minute|minutes|hour|hours|day|days|week|weeks|month|months|year|years)\\s+ago|(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+\\d{1,2}(?:,\\s*\\d{4})?|\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})$",
                                ),
                            ) || name.matches(Regex("\\d{4}"))
                        if (name.isBlank() || name == "•" || name == "|" || isDate) return@mapNotNull null
                        ArtistTiny(
                            runObject["navigationEndpoint"]
                                ?.jsonObject
                                ?.get("browseEndpoint")
                                ?.jsonObject
                                ?.get("browseId")
                                ?.let(::stringValue),
                            name,
                        )
                    }.orEmpty()
            val durationText =
                renderer["fixedColumns"]
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("musicResponsiveListItemFixedColumnRenderer")
                    ?.jsonObject
                    ?.get("text")
                    ?.let(::runText)
                    ?: renderer["playbackProgress"]
                        ?.jsonObject
                        ?.get("musicPlaybackProgressRenderer")
                        ?.jsonObject
                        ?.get("durationText")
                        ?.let(::runText)
            val duration = parseDurationSeconds(durationText)
            return SongItem(
                id = videoId,
                title = title,
                thumbnail = thumbnailUrl(renderer),
                artists = artists,
                album = null,
                duration = duration,
                playlistSetVideoId =
                    endpoint?.get("playlistSetVideoId")?.let(::stringValue)
                        ?: playlistItemData?.get("playlistSetVideoId")?.let(::stringValue),
                musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
                isUnavailable = YouTubeItemParser.isUnavailable(renderer, title, duration),
                playbackPositionMs =
                    endpoint
                        ?.get("startTimeSeconds")
                        ?.jsonPrimitive
                        ?.longOrNull
                        ?.times(1000L),
                playbackProgressPercent =
                    renderer["playbackProgress"]
                        ?.jsonObject
                        ?.get("musicPlaybackProgressRenderer")
                        ?.jsonObject
                        ?.get("playbackProgressPercentage")
                        ?.jsonPrimitive
                        ?.intOrNull,
            )
        }

        fun processItem(element: JsonObject) {
            val parsed = parseItem(element)
            when (parsed) {
                is EpisodeItem -> episodes += parsed.toSongItem()
                is SongItem -> episodes += parsed.copy(musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE")
                else -> fallbackRdpnItem(element)?.let { episodes += it }
            }
        }

        fun processShelf(section: JsonObject) {
            listOf("musicShelfRenderer", "musicCarouselShelfRenderer", "musicPlaylistShelfRenderer")
                .forEach { rendererKey ->
                    section[rendererKey]
                        ?.jsonObject
                        ?.get("contents")
                        ?.jsonArray
                        ?.forEach { item ->
                            val itemObject = item as? JsonObject ?: return@forEach
                            processItem(itemObject)
                        }
                }
        }

        fun addSectionContents(
            contents: JsonArray?,
            target: MutableList<JsonObject>,
        ) {
            contents?.forEach { section ->
                (section as? JsonObject)?.let(target::add)
            }
        }

        val allSections = mutableListOf<JsonObject>()
        val contents = response["contents"] as? JsonObject
        val twoColumn = contents?.get("twoColumnBrowseResultsRenderer") as? JsonObject

        val tabSectionContents =
            (
                (twoColumn?.get("tabs") as? JsonArray)
                    ?.firstOrNull() as? JsonObject
            )?.get("tabRenderer")
                ?.let { it as? JsonObject }
                ?.get("content")
                ?.let { it as? JsonObject }
                ?.get("sectionListRenderer")
                ?.let { it as? JsonObject }
                ?.get("contents")
                ?.let { it as? JsonArray }
        addSectionContents(tabSectionContents, allSections)

        val secondarySectionContents =
            (twoColumn?.get("secondaryContents") as? JsonObject)
                ?.get("sectionListRenderer")
                ?.let { it as? JsonObject }
                ?.get("contents")
                ?.let { it as? JsonArray }
        addSectionContents(secondarySectionContents, allSections)

        val singleColumn = contents?.get("singleColumnBrowseResultsRenderer") as? JsonObject
        val singleColumnSectionContents =
            (
                (singleColumn?.get("tabs") as? JsonArray)
                    ?.firstOrNull() as? JsonObject
            )?.get("tabRenderer")
                ?.let { it as? JsonObject }
                ?.get("content")
                ?.let { it as? JsonObject }
                ?.get("sectionListRenderer")
                ?.let { it as? JsonObject }
                ?.get("contents")
                ?.let { it as? JsonArray }
        addSectionContents(singleColumnSectionContents, allSections)

        allSections.forEach(::processShelf)

        if (episodes.isEmpty()) {
            fun visit(element: JsonElement) {
                when (element) {
                    is JsonObject -> {
                        if (
                            element.containsKey("musicMultiRowListItemRenderer") ||
                            element.containsKey("musicResponsiveListItemRenderer") ||
                            element.containsKey("musicTwoRowItemRenderer")
                        ) {
                            processItem(element)
                        }
                        element.values.forEach(::visit)
                    }

                    is JsonArray -> {
                        element.forEach(::visit)
                    }

                    else -> {}
                }
            }
            visit(response)
        }
        return episodes.distinctBy { it.id }
    }
}
