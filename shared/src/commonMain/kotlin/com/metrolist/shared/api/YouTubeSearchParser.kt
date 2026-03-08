package com.metrolist.shared.api

import com.metrolist.shared.api.YouTubeResponseParser.findFirstObject
import com.metrolist.shared.api.YouTubeResponseParser.parseItem
import com.metrolist.shared.model.YTItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object YouTubeSearchParser {
    internal fun parseCardShelfItem(cardShelf: JsonObject): YTItem? =
        parseItem(
            buildJsonObject {
                cardShelf["title"]?.jsonObject?.let { put("title", it) }
                val nav =
                    cardShelf["onTap"]?.jsonObject
                        ?: cardShelf["title"]
                            ?.jsonObject
                            ?.get("runs")
                            ?.jsonArray
                            ?.getOrNull(0)
                            ?.jsonObject
                            ?.get("navigationEndpoint")
                            ?.jsonObject
                if (nav != null) put("navigationEndpoint", nav)
                cardShelf["thumbnail"]?.jsonObject?.let { put("thumbnail", it) }
                cardShelf["subtitle"]?.jsonObject?.let {
                    put("subtitle", if (nav?.findFirstObject("watchEndpoint") != null) it.withoutCardTypeLabel() else it)
                }
                cardShelf["badges"]?.let { put("badges", it) }
                cardShelf["subtitleBadges"]?.let { put("subtitleBadges", it) }
            },
        )

    private fun JsonObject.withoutCardTypeLabel(): JsonObject {
        val runs = get("runs")?.jsonArray ?: return this
        val separator =
            runs.indexOfFirst {
                it.jsonObject["text"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.contains('•') == true
            }
        val typeRuns = if (separator < 0) runs else runs.take(separator + 1)
        if (typeRuns.any { it.jsonObject["navigationEndpoint"] != null }) return this
        if (separator < 0) return JsonObject(this + ("runs" to JsonArray(emptyList())))
        // Card subtitles start with a localized row type before the first bullet.
        val boundary = runs[separator].jsonObject
        val remainingText =
            boundary["text"]
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
                .substringAfter('•')
                .trimStart()
        val metadataRuns =
            buildList {
                if (remainingText.isNotBlank()) add(JsonObject(boundary + ("text" to JsonPrimitive(remainingText))))
                addAll(runs.drop(separator + 1))
            }
        return JsonObject(this + ("runs" to JsonArray(metadataRuns)))
    }

    internal fun parseSearchResponse(response: JsonObject): List<YTItem> {
        val items = mutableListOf<YTItem>()
        val contents =
            response["contents"]
                ?.jsonObject
                ?.get("tabbedSearchResultsRenderer")
                ?.jsonObject
                ?.get("tabs")
                ?.jsonArray
                ?.getOrNull(0)
                ?.jsonObject
                ?.get("tabRenderer")
                ?.jsonObject
                ?.get("content")
                ?.jsonObject
                ?.get("sectionListRenderer")
                ?.jsonObject
                ?.get("contents")
                ?.jsonArray
                ?: response["contents"]
                    ?.jsonObject
                    ?.get("sectionListRenderer")
                    ?.jsonObject
                    ?.get("contents")
                    ?.jsonArray

        contents?.forEach { section ->
            val sectionObj = section.jsonObject

            val cardShelf = sectionObj["musicCardShelfRenderer"]?.jsonObject
            if (cardShelf != null) {
                val cardItem = parseCardShelfItem(cardShelf)
                if (cardItem != null) items.add(cardItem)

                cardShelf["contents"]?.jsonArray?.forEach { item ->
                    val ytItem = parseItem(item.jsonObject)
                    if (ytItem != null) items.add(ytItem)
                }
                return@forEach
            }

            val shelf = sectionObj["musicShelfRenderer"]?.jsonObject
            if (shelf != null) {
                shelf["contents"]?.jsonArray?.forEach { item ->
                    val ytItem = parseItem(item.jsonObject)
                    if (ytItem != null) items.add(ytItem)
                }
            } else {
                val itemSection = sectionObj["itemSectionRenderer"]?.jsonObject
                itemSection?.get("contents")?.jsonArray?.forEach { item ->
                    val itemObj = item.jsonObject
                    val ytItem =
                        parseItem(
                            itemObj["musicResponsiveListItemRenderer"]?.jsonObject
                                ?: itemObj["musicTwoRowItemRenderer"]?.jsonObject
                                ?: itemObj["musicNavigationButtonRenderer"]?.jsonObject
                                ?: itemObj,
                        )
                    if (ytItem != null) items.add(ytItem)
                }
            }
        }
        return items
    }
}
