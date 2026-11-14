package com.metrolist.shared.api

import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.shared.api.YouTubeResponseParser.asArray
import com.metrolist.shared.api.YouTubeResponseParser.asObject
import com.metrolist.shared.api.YouTubeResponseParser.feedbackToken
import com.metrolist.shared.api.YouTubeResponseParser.findAllObjects
import com.metrolist.shared.api.YouTubeResponseParser.parseItem
import com.metrolist.shared.api.YouTubeResponseParser.runText
import com.metrolist.shared.api.YouTubeResponseParser.sectionListRenderer
import com.metrolist.shared.model.SongItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object YouTubeHistoryParser {
    private fun JsonObject.historyFeedbackToken(): String? {
        val menuItems =
            this["menu"]
                ?.jsonObject
                ?.get("menuRenderer")
                ?.jsonObject
                ?.get("items")
                ?.jsonArray
                ?: return null

        for (item in menuItems) {
            val renderer =
                item.jsonObject["menuServiceItemRenderer"]?.jsonObject
                    ?: item.jsonObject["toggleMenuServiceItemRenderer"]?.jsonObject
                    ?: continue
            val iconType =
                renderer["icon"]
                    ?.jsonObject
                    ?.get("iconType")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: renderer["defaultIcon"]
                        ?.jsonObject
                        ?.get("iconType")
                        ?.jsonPrimitive
                        ?.contentOrNull
            if (iconType != "REMOVE_FROM_HISTORY") continue

            return renderer["serviceEndpoint"]?.jsonObject.feedbackToken()
                ?: renderer["defaultServiceEndpoint"]?.jsonObject.feedbackToken()
                ?: renderer["toggledServiceEndpoint"]?.jsonObject.feedbackToken()
        }
        return null
    }

    private fun JsonObject.historyShelfTitle(): String? =
        this["title"]?.jsonObject.runText()
            ?: this["header"]
                ?.jsonObject
                ?.get("musicShelfBasicHeaderRenderer")
                ?.jsonObject
                ?.get("title")
                ?.jsonObject
                .runText()
            ?: this["header"]
                ?.jsonObject
                ?.get("musicCarouselShelfBasicHeaderRenderer")
                ?.jsonObject
                ?.get("title")
                ?.jsonObject
                .runText()
            ?: this["header"]
                ?.jsonObject
                ?.get("musicHeaderRenderer")
                ?.jsonObject
                ?.get("title")
                ?.jsonObject
                .runText()

    internal fun parseHistoryResponse(
        response: BrowseResponse,
        inheritedPlayedText: String? = null,
    ): BranchingParsedPage<YouTubeHistoryEntry> {
        val sectionList = response.sectionListRenderer()
        val shelfContinuation = response.continuationContents?.musicShelfContinuation
        val continuations = linkedSetOf<ContinuationBranch>()
        val items = mutableListOf<YouTubeHistoryEntry>()

        fun addItem(
            item: JsonObject,
            playedText: String?,
        ) {
            val renderer =
                item["musicResponsiveListItemRenderer"].asObject()
                    ?: item["musicTwoRowItemRenderer"].asObject()
                    ?: item
            val song = parseItem(renderer) as? SongItem ?: return
            items +=
                YouTubeHistoryEntry(
                    song = song,
                    playedText = playedText.orEmpty(),
                    feedbackToken = renderer.historyFeedbackToken(),
                )
        }

        fun addShelf(
            shelf: JsonObject,
            fallbackPlayedText: String?,
        ): String? {
            val playedText = shelf.historyShelfTitle() ?: fallbackPlayedText
            shelf.directHistoryContinuationTokens().forEach { token ->
                continuations += ContinuationBranch(token, playedText)
            }
            shelf["contents"].asArray().orEmpty().forEach { item ->
                item.asObject()?.let { addItem(it, playedText) }
            }
            return playedText
        }

        sectionList?.directHistoryContinuationTokens()?.forEach { continuations += ContinuationBranch(it) }
        sectionList?.findAllObjects("musicShelfRenderer").orEmpty().forEach { addShelf(it, null) }
        shelfContinuation?.let { addShelf(it, inheritedPlayedText) }

        listOfNotNull(
            response.onResponseReceivedActions,
            response.onResponseReceivedCommands,
            response.onResponseReceivedEndpoints,
        ).forEach { actions ->
            actions.forEach { element ->
                val action = element.asObject() ?: return@forEach
                val continuationItems =
                    action["appendContinuationItemsAction"].asObject()?.get("continuationItems").asArray()
                        ?: action["reloadContinuationItemsCommand"].asObject()?.get("continuationItems").asArray()
                        ?: action["appendContinuationItemsCommand"].asObject()?.get("continuationItems").asArray()
                        ?: return@forEach
                var playedText = inheritedPlayedText
                continuationItems.forEach { item ->
                    val itemObject = item.asObject() ?: return@forEach
                    itemObject["musicShelfRenderer"].asObject()?.let { shelf ->
                        playedText = addShelf(shelf, playedText)
                    } ?: run {
                        itemObject.directHistoryContinuationTokens().forEach { token ->
                            continuations += ContinuationBranch(token, playedText)
                        }
                        addItem(itemObject, playedText)
                    }
                }
            }
        }

        return BranchingParsedPage(items, continuations)
    }

    private fun JsonObject.directHistoryContinuationTokens(): Set<String> =
        buildSet {
            this@directHistoryContinuationTokens["continuations"].findContinuationTokens().forEach(::add)
            this@directHistoryContinuationTokens["continuationItemRenderer"].findContinuationTokens().forEach(::add)
            this@directHistoryContinuationTokens["contents"]
                .asArray()
                .orEmpty()
                .mapNotNull { it.asObject() }
                .mapNotNull { it["continuationItemRenderer"] }
                .flatMapTo(this) { it.findContinuationTokens() }
        }
}
