package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.shared.api.YouTubeResponseParser.feedbackToken
import com.metrolist.shared.api.YouTubeResponseParser.findAllObjects
import com.metrolist.shared.api.YouTubeResponseParser.findFirstObject
import com.metrolist.shared.api.YouTubeResponseParser.parseItem
import com.metrolist.shared.api.YouTubeResponseParser.runText
import com.metrolist.shared.api.YouTubeSearchParser.parseCardShelfItem
import com.metrolist.shared.api.YouTubeSearchParser.parseSearchResponse
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SearchResultPage
import com.metrolist.shared.model.SearchSuggestions
import com.metrolist.shared.model.SearchSummary
import com.metrolist.shared.model.SearchSummaryPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.filterEpisodeSearchResults
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.SongBlockSnapshot
import com.metrolist.shared.utils.Logger
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.milliseconds

internal class YouTubeSearchOperations(
    private val innerTube: InnerTube,
) {
    private companion object {
        const val MAX_SEARCH_HISTORY_CLEAR_PAGES = 100
        const val MAX_STALE_SEARCH_HISTORY_READS = 5
    }

    suspend fun search(
        query: String,
        recordSearchHistory: Boolean,
    ): List<YTItem>? =
        withContext(responseDecodeDispatcher) {
            try {
                val response =
                    innerTube
                        .search(YouTubeClient.WEB_REMIX, query = query, setLogin = recordSearchHistory)
                        .also { check(it.status.isSuccess()) { "Search failed with HTTP ${it.status.value}" } }
                        .bodyLimited<JsonObject>(FEED_RESPONSE_LIMIT)
                BlockedContent.filterItems(parseSearchResponse(response))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    suspend fun getSearchSummary(
        query: String,
        recordSearchHistory: Boolean,
    ): SearchSummaryPage? =
        withContext(responseDecodeDispatcher) {
            try {
                val response =
                    innerTube
                        .search(YouTubeClient.WEB_REMIX, query = query, setLogin = recordSearchHistory)
                        .also { check(it.status.isSuccess()) { "Search failed with HTTP ${it.status.value}" } }
                        .bodyLimited<JsonObject>(FEED_RESPONSE_LIMIT)
                // Merge inferred and API shelves by title to avoid duplicate LazyColumn header keys.
                val blockSnapshot = lazy { BlockedContent.snapshot() }
                val summaries = LinkedHashMap<String, MutableList<YTItem>>()

                val sectionList =
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

                val allItems = mutableListOf<Pair<YTItem, String?>>()
                val shelfSummaries = mutableListOf<Pair<String, List<YTItem>>>()

                sectionList?.forEach { sectionElement ->
                    val sectionObj = sectionElement.jsonObject

                    val cardShelf = sectionObj["musicCardShelfRenderer"]?.jsonObject
                    if (cardShelf != null) {
                        val cardItem = parseCardShelfItem(cardShelf)
                        if (cardItem != null) {
                            allItems.add(cardItem to "Top result")
                        }
                        cardShelf["contents"]?.jsonArray?.forEach { item ->
                            val itemObj = item.jsonObject
                            val ytItem =
                                parseItem(
                                    itemObj["musicResponsiveListItemRenderer"]?.jsonObject
                                        ?: itemObj["musicTwoRowItemRenderer"]?.jsonObject
                                        ?: itemObj,
                                )
                            if (ytItem is SongItem && ytItem.artists.isEmpty() && cardItem is ArtistItem) {
                                allItems.add(
                                    ytItem.copy(
                                        artists =
                                            listOf(
                                                ArtistTiny(
                                                    id = cardItem.id,
                                                    name = cardItem.title,
                                                    thumbnail = cardItem.thumbnail,
                                                ),
                                            ),
                                    ) to "Top result related",
                                )
                            } else if (ytItem != null) {
                                allItems.add(ytItem to "Top result related")
                            }
                        }
                        return@forEach
                    }

                    val itemSection = sectionObj["itemSectionRenderer"]?.jsonObject
                    val actualContents =
                        itemSection?.get("contents")?.jsonArray
                            ?: JsonArray(listOf(sectionElement))

                    actualContents.forEach { contentElement ->
                        val contentObj = contentElement.jsonObject

                        val oldShelf = contentObj["musicShelfRenderer"]?.jsonObject
                        if (oldShelf != null) {
                            val title =
                                oldShelf["title"]
                                    ?.jsonObject
                                    ?.get("runs")
                                    ?.jsonArray
                                    ?.getOrNull(0)
                                    ?.jsonObject
                                    ?.get("text")
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    ?: ""
                            val items = mutableListOf<YTItem>()
                            oldShelf["contents"]?.jsonArray?.forEach { item ->
                                val itemObj = item.jsonObject
                                parseItem(
                                    itemObj["musicResponsiveListItemRenderer"]?.jsonObject
                                        ?: itemObj["musicTwoRowItemRenderer"]?.jsonObject
                                        ?: itemObj,
                                )?.let { items.add(it) }
                            }
                            if (items.isNotEmpty() && !title.equals("Profiles", ignoreCase = true)) {
                                shelfSummaries.add(title to items)
                            }
                            return@forEach
                        }

                        val typeLabel =
                            contentObj["musicResponsiveListItemRenderer"]
                                ?.jsonObject
                                ?.get("flexColumns")
                                ?.jsonArray
                                ?.getOrNull(1)
                                ?.jsonObject
                                ?.get("musicResponsiveListItemFlexColumnRenderer")
                                ?.jsonObject
                                ?.get("text")
                                ?.jsonObject
                                ?.get("runs")
                                ?.jsonArray
                                ?.getOrNull(0)
                                ?.jsonObject
                                ?.get("text")
                                ?.jsonPrimitive
                                ?.contentOrNull

                        val ytItem =
                            parseItem(
                                contentObj["musicResponsiveListItemRenderer"]?.jsonObject
                                    ?: contentObj["musicTwoRowItemRenderer"]?.jsonObject
                                    ?: contentObj,
                            )
                        if (ytItem != null) {
                            if (typeLabel != "Profile") {
                                allItems.add(ytItem to typeLabel)
                            }
                        }
                    }
                }

                val topItems = mutableListOf<YTItem>()
                val unlabeledItems = mutableListOf<YTItem>()
                val categoryItems = mutableMapOf<String, MutableList<YTItem>>()
                for ((item, label) in allItems) {
                    when (label) {
                        "Top result", "Top result related" -> topItems.add(item)
                        "Single" -> categoryItems.getOrPut("Album") { mutableListOf() }.add(item)
                        null -> unlabeledItems.add(item)
                        else -> categoryItems.getOrPut(label) { mutableListOf() }.add(item)
                    }
                }

                if (topItems.isNotEmpty()) {
                    mergeSummary(blockSnapshot, summaries, "", topItems)
                }

                shelfSummaries.forEach { (title, items) ->
                    mergeSummary(blockSnapshot, summaries, title, items)
                }

                if (unlabeledItems.isNotEmpty()) {
                    groupItemsByType(unlabeledItems).forEach { mergeSummary(blockSnapshot, summaries, it.title, it.items) }
                }
                val categoryOrder = listOf("Song", "Video", "Album", "Artist", "Playlist", "Podcast", "Episode")
                val categoryTitles =
                    mapOf(
                        "Song" to "Songs",
                        "Video" to "Videos",
                        "Album" to "Albums",
                        "Artist" to "Artists",
                        "Playlist" to "Playlists",
                        "Podcast" to "Podcasts",
                        "Episode" to "Episodes",
                    )
                for (type in categoryOrder) {
                    val items = categoryItems.remove(type)
                    if (items != null) {
                        mergeSummary(blockSnapshot, summaries, categoryTitles[type] ?: type, items)
                    }
                }
                categoryItems.forEach { (label, items) ->
                    mergeSummary(blockSnapshot, summaries, label, items)
                }

                SearchSummaryPage(
                    summaries.map { (title, items) ->
                        SearchSummary(title, items.toList(), isTopResult = title.isEmpty() && topItems.isNotEmpty())
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("YouTubeSearch", "Search summary failed", e)
                null
            }
        }

    private fun mergeSummary(
        blockSnapshot: Lazy<SongBlockSnapshot>,
        summaries: LinkedHashMap<String, MutableList<YTItem>>,
        title: String,
        items: List<YTItem>,
    ) {
        if (items.isEmpty()) return
        val visibleItems = BlockedContent.filterItems(items, blockSnapshot.value)
        if (visibleItems.isEmpty()) return
        val bucket = summaries.getOrPut(title) { mutableListOf() }
        val seen = HashSet<String>(bucket.size + visibleItems.size).apply { addAll(bucket.map { it.id }) }
        for (item in visibleItems) {
            if (seen.add(item.id)) bucket.add(item)
        }
    }

    private fun groupItemsByType(items: List<YTItem>): List<SearchSummary> {
        val grouped =
            items.groupBy { item ->
                when (item) {
                    is EpisodeItem -> {
                        "Episodes"
                    }

                    is PodcastItem -> {
                        "Podcasts"
                    }

                    is AlbumItem -> {
                        "Albums"
                    }

                    is ArtistItem -> {
                        if (item.subscribers != null && item.subscribers.contains("Verified")) "Verified Artists" else "Artists"
                    }

                    is PlaylistItem -> {
                        "Playlists"
                    }

                    is BrowseItem -> {
                        "Browse"
                    }

                    is SongItem -> {
                        when {
                            item.isVideoSong -> "Videos"
                            else -> "Songs"
                        }
                    }
                }
            }
        val sectionOrder =
            listOf(
                "Songs",
                "Videos",
                "Albums",
                "Artists",
                "Verified Artists",
                "Playlists",
                "Podcasts",
                "Episodes",
                "Browse",
            )
        return sectionOrder.mapNotNull { sectionName ->
            grouped[sectionName]?.takeIf { it.isNotEmpty() }?.let { groupItems ->
                SearchSummary(title = sectionName, items = groupItems)
            }
        }
    }

    suspend fun getSearchSuggestions(query: String): SearchSuggestions? =
        try {
            val response =
                innerTube
                    .getSearchSuggestions(YouTubeClient.WEB_REMIX, query, setLogin = false)
                    .bodyLimited<JsonObject>(SMALL_RESPONSE_LIMIT)
            val contents = response["contents"]?.jsonArray

            val queries =
                contents
                    ?.getOrNull(0)
                    ?.jsonObject
                    ?.get("searchSuggestionsSectionRenderer")
                    ?.jsonObject
                    ?.get("contents")
                    ?.jsonArray
                    ?.mapNotNull { content ->
                        content.jsonObject["searchSuggestionRenderer"]
                            ?.jsonObject
                            ?.get("suggestion")
                            ?.jsonObject
                            ?.get("runs")
                            ?.jsonArray
                            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }
                    }.orEmpty()

            val items =
                contents
                    ?.getOrNull(1)
                    ?.jsonObject
                    ?.get("searchSuggestionsSectionRenderer")
                    ?.jsonObject
                    ?.get("contents")
                    ?.jsonArray
                    ?.mapNotNull { content ->
                        parseItem(content.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject)
                    }.orEmpty()

            SearchSuggestions(queries = queries, recommendedItems = BlockedContent.filterItems(items))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    suspend fun getSearchHistory(): List<YouTubeSearchHistoryEntry>? {
        if (!innerTube.hasSapCookieAuth()) return null
        return try {
            val response =
                innerTube
                    .getSearchSuggestions(YouTubeClient.WEB_REMIX, input = "", setLogin = true)
                    .also { check(it.status.isSuccess()) { "Search history read failed with HTTP ${it.status.value}" } }
                    .bodyLimited<JsonObject>(SMALL_RESPONSE_LIMIT)
            val currentRenderers = response.findAllObjects("historySuggestionRenderer").map { it to true }
            val legacyRenderers = response.findAllObjects("searchSuggestionRenderer").map { it to false }
            (currentRenderers + legacyRenderers)
                .mapNotNull { (renderer, isHistoryRenderer) ->
                    val feedbackToken =
                        renderer["serviceEndpoint"]?.jsonObject.feedbackToken()
                            ?: renderer["defaultServiceEndpoint"]?.jsonObject.feedbackToken()
                            ?: renderer["toggledServiceEndpoint"]?.jsonObject.feedbackToken()
                            ?: renderer
                                .findFirstObject("feedbackEndpoint")
                                ?.get("feedbackToken")
                                ?.jsonPrimitive
                                ?.contentOrNull
                    val iconType =
                        renderer["icon"]
                            ?.jsonObject
                            ?.get("iconType")
                            ?.jsonPrimitive
                            ?.contentOrNull
                    if (!isHistoryRenderer && iconType != "HISTORY" && iconType != "SEARCH_HISTORY" && feedbackToken == null) {
                        return@mapNotNull null
                    }
                    val query =
                        renderer["suggestion"]?.jsonObject.runText()
                            ?: renderer["navigationEndpoint"]
                                ?.jsonObject
                                ?.get("searchEndpoint")
                                ?.jsonObject
                                ?.get("query")
                                ?.jsonPrimitive
                                ?.contentOrNull
                    query
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let { YouTubeSearchHistoryEntry(it, feedbackToken) }
                }.distinctBy { it.query.lowercase() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun removeSearchHistoryItems(feedbackTokens: List<String>) {
        val tokens = feedbackTokens.filter(String::isNotBlank).distinct()
        if (tokens.isEmpty()) return
        val response = innerTube.feedback(YouTubeClient.WEB_REMIX, tokens)
        check(response.status.isSuccess()) { "Search history removal failed with HTTP ${response.status.value}" }
    }

    suspend fun clearSearchHistory(initialFeedbackTokens: List<String>) {
        val session = innerTube.sessionSnapshot()
        if (!innerTube.hasSapCookieAuth(session)) return
        val requestTube = innerTube.createIsolatedSession(true)
        requestTube.replaceSession(session.cookie, session.visitorData, session.dataSyncId, session.authUser, session.useLoginForBrowse)

        fun ensureSession() {
            if (innerTube.sessionSnapshot() != session) throw CancellationException("InnerTube session changed")
        }
        try {
            val requestOperations = YouTubeSearchOperations(requestTube)
            var tokens = initialFeedbackTokens.filter(String::isNotBlank).distinct()
            val removedTokens = mutableSetOf<String>()
            var staleReads = 0
            repeat(MAX_SEARCH_HISTORY_CLEAR_PAGES) {
                ensureSession()
                if (tokens.isEmpty()) {
                    tokens =
                        checkNotNull(requestOperations.getSearchHistory().also { ensureSession() }) { "Could not load search history" }
                            .mapNotNull(YouTubeSearchHistoryEntry::feedbackToken)
                    if (tokens.isEmpty()) return
                }
                val pending = tokens.filterNot(removedTokens::contains)
                if (pending.isEmpty()) {
                    if (++staleReads >= MAX_STALE_SEARCH_HISTORY_READS) error("Search history removal made no progress")
                } else {
                    ensureSession()
                    requestOperations.removeSearchHistoryItems(pending)
                    ensureSession()
                    removedTokens += pending
                    staleReads = 0
                }
                tokens = emptyList()
                delay(200.milliseconds)
            }
            error("Search history exceeds the clear limit")
        } finally {
            requestTube.close()
        }
    }

    suspend fun searchFiltered(
        query: String,
        filter: SearchFilter,
        continuation: String?,
        recordSearchHistory: Boolean,
        throwOnFailure: Boolean = false,
    ): SearchResultPage? =
        withContext(responseDecodeDispatcher) {
            searchFilteredOnCaller(query, filter, continuation, recordSearchHistory, throwOnFailure)
        }

    private suspend fun searchFilteredOnCaller(
        query: String,
        filter: SearchFilter,
        continuation: String?,
        recordSearchHistory: Boolean,
        throwOnFailure: Boolean = false,
    ): SearchResultPage? =
        try {
            val response =
                if (continuation != null) {
                    innerTube
                        .search(
                            YouTubeClient.WEB_REMIX,
                            continuation = continuation,
                            setLogin = recordSearchHistory,
                        ).also { check(it.status.isSuccess()) { "Search failed with HTTP ${it.status.value}" } }
                        .bodyLimited<JsonObject>(FEED_RESPONSE_LIMIT)
                } else {
                    innerTube
                        .search(
                            YouTubeClient.WEB_REMIX,
                            query = query,
                            params = filter.value,
                            setLogin = recordSearchHistory,
                        ).also { check(it.status.isSuccess()) { "Search failed with HTTP ${it.status.value}" } }
                        .bodyLimited<JsonObject>(FEED_RESPONSE_LIMIT)
                }

            val items = mutableListOf<YTItem>()

            val continuationShelf = response.searchContinuationShelf()
            continuationShelf?.items?.forEach { item ->
                parseItem(item)?.let(items::add)
            }

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

            val shelves =
                contents
                    ?.flatMap { section ->
                        val sectionObj = section.jsonObject
                        buildList {
                            sectionObj["musicShelfRenderer"]?.jsonObject?.let(::add)
                            sectionObj["musicPlaylistShelfRenderer"]?.jsonObject?.let(::add)
                            sectionObj["musicCarouselShelfRenderer"]?.jsonObject?.let(::add)
                            sectionObj["itemSectionRenderer"]
                                ?.jsonObject
                                ?.get("contents")
                                ?.jsonArray
                                ?.forEach { nested ->
                                    val nestedObj = nested.jsonObject
                                    nestedObj["musicShelfRenderer"]?.jsonObject?.let(::add)
                                    nestedObj["musicPlaylistShelfRenderer"]?.jsonObject?.let(::add)
                                    nestedObj["musicCarouselShelfRenderer"]?.jsonObject?.let(::add)
                                }
                        }
                    }?.distinct()
                    .orEmpty()

            shelves.forEach { shelf ->
                shelf["contents"]?.jsonArray?.forEach { item ->
                    parseItem(item.jsonObject)?.let { items.add(it) }
                }
            }

            if (shelves.isEmpty()) {
                contents?.forEach { section ->
                    val sectionObj = section.jsonObject
                    sectionObj["itemSectionRenderer"]
                        ?.jsonObject
                        ?.get("contents")
                        ?.jsonArray
                        ?.forEach { item ->
                            parseItem(item.jsonObject)?.let(items::add)
                        }
                }
            }

            // Profiles and Podcasts can nest result renderers under extra itemSection or carousel wrappers.
            if (items.isEmpty() || filter in setOf(SearchFilter.PODCAST, SearchFilter.EPISODE, SearchFilter.PROFILE)) {
                fun collectNested(element: JsonElement) {
                    when (element) {
                        is JsonObject -> {
                            if (element.keys.any { it.endsWith("Renderer") }) {
                                parseItem(element)?.let(items::add)
                            }
                            element.values.forEach(::collectNested)
                        }

                        is JsonArray -> {
                            element.forEach(::collectNested)
                        }

                        else -> {}
                    }
                }
                collectNested(response)
            }

            val nextContinuation =
                continuationShelf?.continuation
                    ?: shelves
                        .lastOrNull()
                        ?.get("continuations")
                        ?.jsonArray
                        ?.getOrNull(0)
                        ?.jsonObject
                        ?.get("nextContinuationData")
                        ?.jsonObject
                        ?.get("continuation")
                        ?.jsonPrimitive
                        ?.contentOrNull
                    ?: response["continuationContents"]
                        ?.jsonObject
                        ?.get("musicShelfContinuation")
                        ?.jsonObject
                        ?.get("continuations")
                        ?.jsonArray
                        ?.getOrNull(0)
                        ?.jsonObject
                        ?.get("nextContinuationData")
                        ?.jsonObject
                        ?.get("continuation")
                        ?.jsonPrimitive
                        ?.contentOrNull

            SearchResultPage(
                BlockedContent.filterItems(items.distinctBy { it.id }.filterEpisodeSearchResults(filter)),
                nextContinuation,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            if (throwOnFailure) throw error
            null
        }
}
