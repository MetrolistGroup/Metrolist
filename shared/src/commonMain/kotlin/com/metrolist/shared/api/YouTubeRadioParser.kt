package com.metrolist.shared.api

import com.metrolist.shared.model.RadioQueueChip
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.SongItem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object YouTubeRadioParser {
    internal fun parseRadioQueueResponse(response: JsonObject): RadioQueuePage {
        val tabs =
            response["contents"]
                ?.jsonObject
                ?.get("singleColumnMusicWatchNextResultsRenderer")
                ?.jsonObject
                ?.get("tabbedRenderer")
                ?.jsonObject
                ?.get("watchNextTabbedResultsRenderer")
                ?.jsonObject
                ?.get("tabs")
                ?.jsonArray

        val queueRenderer =
            tabs
                ?.getOrNull(0)
                ?.jsonObject
                ?.get("tabRenderer")
                ?.jsonObject
                ?.get("content")
                ?.jsonObject
                ?.get("musicQueueRenderer")
                ?.jsonObject

        val panelRenderer =
            queueRenderer
                ?.get("content")
                ?.jsonObject
                ?.get("playlistPanelRenderer")
                ?.jsonObject

        val queuePanel =
            panelRenderer
                ?: response["continuationContents"]
                    ?.jsonObject
                    ?.get("playlistPanelContinuation")
                    ?.jsonObject
        val queueContents = queuePanel?.get("contents")?.jsonArray

        val songs =
            queueContents
                ?.mapNotNull { item -> runCatching { parsePlaylistPanelItem(item.jsonObject) }.getOrNull() }
                .orEmpty()
        val continuations = queuePanel?.get("continuations")
        val continuation =
            continuations
                ?.jsonArray
                ?.firstNotNullOfOrNull {
                    it.jsonObject["nextRadioContinuationData"]
                        ?.jsonObject
                        ?.get("continuation")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                }
                ?: continuations.findContinuationToken()
                ?: queueContents.findContinuationToken()
        val chips =
            queueRenderer
                ?.get("subHeaderChipCloud")
                ?.jsonObject
                ?.get("chipCloudRenderer")
                ?.jsonObject
                ?.get("chips")
                ?.jsonArray
                ?.mapNotNull(::parseRadioQueueChip)
                .orEmpty()
        return RadioQueuePage(songs = songs, chips = chips, continuation = continuation)
    }

    internal fun parseRadioResponse(response: JsonObject): List<SongItem> = parseRadioQueueResponse(response).songs

    private fun parseRadioQueueChip(element: JsonElement): RadioQueueChip? {
        val renderer = element.jsonObject["chipCloudChipRenderer"]?.jsonObject ?: return null
        val title =
            renderer["text"]
                ?.jsonObject
                ?.get("runs")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull ?: return null
        val watchEndpoint =
            renderer["navigationEndpoint"]
                ?.jsonObject
                ?.get("queueUpdateCommand")
                ?.jsonObject
                ?.get("fetchContentsCommand")
                ?.jsonObject
                ?.get("watchEndpoint")
                ?.jsonObject ?: return null
        val playlistId = watchEndpoint["playlistId"]?.jsonPrimitive?.contentOrNull ?: return null
        val params = watchEndpoint["params"]?.jsonPrimitive?.contentOrNull
        val id = renderer["uniqueId"]?.jsonPrimitive?.contentOrNull ?: "$playlistId:${params.orEmpty()}"
        return RadioQueueChip(
            id = id,
            title = title,
            playlistId = playlistId,
            params = params,
            isSelected = renderer["isSelected"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    internal fun parsePlaylistPanelItem(item: JsonObject): SongItem? {
        val renderer =
            item["playlistPanelVideoRenderer"]?.jsonObject
                ?: item["playlistPanelVideoWrapperRenderer"]
                    ?.jsonObject
                    ?.get("primaryRenderer")
                    ?.jsonObject
                    ?.get("playlistPanelVideoRenderer")
                    ?.jsonObject
                ?: return null
        val videoId = renderer["videoId"]?.jsonPrimitive?.contentOrNull ?: return null
        val title =
            renderer["title"]
                ?.jsonObject
                ?.get("runs")
                ?.jsonArray
                ?.getOrNull(0)
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull ?: return null
        val thumbnail =
            renderer["thumbnail"]
                ?.jsonObject
                ?.get("thumbnails")
                ?.jsonArray
                ?.lastOrNull()
                ?.jsonObject
                ?.get("url")
                ?.jsonPrimitive
                ?.contentOrNull
        val longBylineRuns =
            renderer["longBylineText"]
                ?.jsonObject
                ?.get("runs")
                ?.jsonArray
        val metadata = YouTubeItemParser.extractMetadata(longBylineRuns)
        val artists = metadata.artists.toMutableList()
        var album = metadata.album
        if (artists.isEmpty()) {
            val shortMetadata =
                renderer["shortBylineText"]
                    ?.jsonObject
                    ?.get("runs")
                    ?.jsonArray
                    ?.let { YouTubeItemParser.extractMetadata(it) }
            if (shortMetadata != null) {
                artists.addAll(shortMetadata.artists)
                if (album == null) album = shortMetadata.album
            }
        }
        val durationText =
            renderer["lengthText"]
                ?.jsonObject
                ?.get("runs")
                ?.jsonArray
                ?.getOrNull(0)
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
        val durationSec =
            durationText?.split(":")?.mapNotNull { it.trim().toLongOrNull() }?.let { parts ->
                when (parts.size) {
                    2 -> parts[0] * 60 + parts[1]
                    3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                    else -> null
                }
            }
        return SongItem(
            id = videoId,
            title = title,
            thumbnail = thumbnail,
            artists = artists,
            album = album,
            duration = durationSec,
            isExplicit = renderer.hasExplicitBadge(),
            musicVideoType =
                renderer.musicVideoType() ?: item.musicVideoType() ?: title.inferredMusicVideoType(renderer.hasVideoThumbnail()),
            explicitness = renderer.hasExplicitBadge(),
        )
    }
}
