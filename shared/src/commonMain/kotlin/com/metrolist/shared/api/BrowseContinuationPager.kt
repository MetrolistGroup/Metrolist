package com.metrolist.shared.api

import com.metrolist.innertubex.models.response.BrowseResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun interface BrowseContinuationPageSource {
    suspend fun load(
        token: String,
        setLogin: Boolean,
    ): BrowseResponse
}

internal class BrowseContinuationPager<T>(
    private val pageSource: BrowseContinuationPageSource,
    private val itemParser: (JsonObject) -> T?,
) {
    suspend fun collect(
        initialToken: String?,
        maxPages: Int,
        setLogin: Boolean,
    ): List<T> = collectPage(initialToken, maxPages, setLogin).items

    suspend fun loadPage(
        token: String,
        setLogin: Boolean,
    ): ParsedPage<T> {
        val items = mutableListOf<T>()
        val next = parsePage(pageSource.load(token, setLogin), items).normalizedContinuation()
        return ParsedPage(items, next)
    }

    suspend fun collectPage(
        initialToken: String?,
        maxPages: Int,
        setLogin: Boolean,
    ): ParsedPage<T> {
        val items = mutableListOf<T>()
        var token = initialToken.normalizedContinuation()
        val seen = mutableSetOf<String>()
        var pages = 0
        while (token != null && pages < maxPages) {
            if (!seen.add(token)) return ParsedPage(items, null, continuationAmbiguous = true)
            val page =
                try {
                    loadPage(token, setLogin)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return ParsedPage(items, token)
                }
            items += page.items
            pages++
            token = page.continuation
        }
        if (token != null && token in seen) return ParsedPage(items, null, continuationAmbiguous = true)
        return ParsedPage(items, token)
    }

    private fun parsePage(
        response: BrowseResponse,
        items: MutableList<T>,
    ): String? {
        val pageItems = mutableListOf<T>()
        var next: String? = null
        response.continuationContents?.let { contents ->
            val shelf =
                contents.musicPlaylistShelfContinuation
                    ?: contents.musicShelfContinuation
                    ?: contents.gridContinuation
            if (shelf != null) {
                shelfItems(shelf).forEach { itemParser(it.jsonObject)?.let(pageItems::add) }
                next = shelf.nextContinuationToken()
            } else {
                next = contents.sectionListContinuation?.findContinuationToken()
            }
        }

        listOfNotNull(
            response.onResponseReceivedActions,
            response.onResponseReceivedCommands,
            response.onResponseReceivedEndpoints,
        ).forEach { actions ->
            actions.forEach { element ->
                val action = element.jsonObject
                val continuationItems =
                    action["appendContinuationItemsAction"]?.jsonObject?.get("continuationItems")?.jsonArray
                        ?: action["reloadContinuationItemsCommand"]?.jsonObject?.get("continuationItems")?.jsonArray
                        ?: action["appendContinuationItemsCommand"]?.jsonObject?.get("continuationItems")?.jsonArray
                if (continuationItems != null) {
                    continuationItems.forEach { itemParser(it.jsonObject)?.let(pageItems::add) }
                    next = continuationItems.findContinuationToken() ?: next
                }
            }
        }
        items += pageItems
        return next
    }

    private fun shelfItems(shelf: JsonObject): JsonArray =
        shelf["contents"]?.jsonArray ?: shelf["items"]?.jsonArray ?: JsonArray(emptyList())

    private fun JsonObject.nextContinuationToken(): String? =
        this["continuations"]
            ?.jsonArray
            ?.getOrNull(0)
            ?.jsonObject
            ?.get("nextContinuationData")
            ?.jsonObject
            ?.get("continuation")
            ?.jsonPrimitive
            ?.contentOrNull
}
