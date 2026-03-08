package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.YouTubeDataPage
import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.shared.api.YouTubeResponseParser.browseId
import com.metrolist.shared.api.YouTubeResponseParser.browseParams
import com.metrolist.shared.api.YouTubeResponseParser.continuationToken
import com.metrolist.shared.api.YouTubeResponseParser.pageTitle
import com.metrolist.shared.api.YouTubeResponseParser.parseItem
import com.metrolist.shared.api.YouTubeResponseParser.processShelfToSections
import com.metrolist.shared.api.YouTubeResponseParser.runText
import com.metrolist.shared.api.YouTubeResponseParser.sectionListRenderer
import com.metrolist.shared.api.YouTubeResponseParser.shelfNavigationEndpoint
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.BrowsePageData
import com.metrolist.shared.model.Chip
import com.metrolist.shared.model.HomePageData
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.youtube.logging.YtLogger
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class YouTubeBrowseOperations(
    private val innerTube: InnerTube,
) {
    private companion object {
        const val TAG = "YouTubeRepository"
        const val SHELF_CONCURRENCY = 3
    }

    suspend fun getHomePageData(
        params: String? = null,
        browseId: String = "FEmusic_home",
        setLogin: Boolean = innerTube.useLoginForBrowse,
    ): HomePageData =
        withContext(responseDecodeDispatcher) {
            val session = innerTube.sessionSnapshot()
            val revision = SongLikeState.voteObservationRevision()
            val httpResponse =
                innerTube.browse(
                    YouTubeClient.WEB_REMIX,
                    browseId = browseId,
                    params = params,
                    setLogin = setLogin,
                )
            check(httpResponse.status.isSuccess()) { "Home browse failed with HTTP ${httpResponse.status.value}" }
            val response =
                httpResponse.browseResponseWithVotes(
                    innerTube,
                    session,
                    revision,
                    setLogin || session.useLoginForBrowse,
                    allowVisitorBootstrap = true,
                )
            val sectionList = response.sectionListRenderer()

            val chips = mutableListOf<Chip>()
            sectionList
                ?.get("header")
                ?.jsonObject
                ?.get("chipCloudRenderer")
                ?.jsonObject
                ?.get("chips")
                ?.jsonArray
                ?.forEach { chipElement ->
                    val chipRenderer = chipElement.jsonObject["chipCloudChipRenderer"]?.jsonObject
                    val text =
                        chipRenderer
                            ?.get(
                                "text",
                            )?.jsonObject
                            ?.get("runs")
                            ?.jsonArray
                            ?.getOrNull(0)
                            ?.jsonObject
                            ?.get("text")
                            ?.jsonPrimitive
                            ?.contentOrNull
                    val endpoint =
                        chipRenderer
                            ?.get("navigationEndpoint")
                            ?.jsonObject
                            ?.get("browseEndpoint")
                            ?.jsonObject
                    val p =
                        endpoint
                            ?.get("params")
                            ?.jsonPrimitive
                            ?.contentOrNull
                    val chipBrowseId = endpoint?.get("browseId")?.jsonPrimitive?.contentOrNull
                    if (text != null) {
                        chips.add(Chip(text, p, chipBrowseId))
                    }
                }

            val sections = mutableListOf<HomeSection>()
            sectionList?.get("contents")?.jsonArray?.forEach { sectionElement ->
                val shelfObj = sectionElement.jsonObject
                processShelfToSections(shelfObj, sections, isPersonal = browseId == "FEmusic_home")
            }

            val continuationToken = sectionList?.continuationToken()

            val categoryChips =
                sections
                    .filter { it.isCategoryComponent }
                    .flatMap { it.items }
                    .filterIsInstance<BrowseItem>()
                    .mapNotNull { item ->
                        if (item.params == null && item.id.isBlank()) null else Chip(item.title, item.params, item.id)
                    }
            return@withContext HomePageData(
                chips = (chips + categoryChips).distinctBy { it.title to it.params },
                sections = BlockedContent.filterSections(sections.filterNot { it.isCategoryComponent }),
                continuation = continuationToken,
            )
        }

    suspend fun getBrowsePageData(
        browseId: String,
        params: String? = null,
        title: String? = null,
        loadAllPages: Boolean = true,
    ): BrowsePageData =
        withContext(responseDecodeDispatcher) {
            try {
                val session = innerTube.sessionSnapshot()
                val revision = SongLikeState.voteObservationRevision()
                val setLogin =
                    browseId.startsWith("FEmusic_library") || browseId.startsWith("FEmusic_moods_and_genres") || browseId == "VLSE" ||
                        browseId == "VLRDPN"
                val httpResponse =
                    innerTube.browse(
                        YouTubeClient.WEB_REMIX,
                        browseId = browseId,
                        params = params,
                        setLogin = setLogin,
                    )
                check(httpResponse.status.isSuccess()) { "Browse failed with HTTP ${httpResponse.status.value}" }
                val response = httpResponse.browseResponseWithVotes(innerTube, session, revision, setLogin || session.useLoginForBrowse)

                val contents = response.contents
                val primarySectionList =
                    contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.let { it["tabs"]?.jsonArray?.getOrNull(0)?.jsonObject }
                        ?.get("tabRenderer")
                        ?.jsonObject
                        ?.get("content")
                        ?.jsonObject
                        ?.get("sectionListRenderer")
                        ?.jsonObject
                        ?: contents
                            ?.twoColumnBrowseResultsRenderer
                            ?.let { it["tabs"]?.jsonArray?.getOrNull(0)?.jsonObject }
                            ?.get("tabRenderer")
                            ?.jsonObject
                            ?.get("content")
                            ?.jsonObject
                            ?.get("sectionListRenderer")
                            ?.jsonObject
                        ?: contents?.sectionListRenderer
                val secondarySectionList =
                    contents
                        ?.twoColumnBrowseResultsRenderer
                        ?.get("secondaryContents")
                        ?.jsonObject
                        ?.get("sectionListRenderer")
                        ?.jsonObject
                val sections =
                    processBrowseSections(
                        listOfNotNull(primarySectionList, secondarySectionList)
                            .distinct()
                            .flatMap { it["contents"]?.jsonArray.orEmpty() }
                            .map { it.jsonObject },
                        isPersonal = true,
                        setLogin = setLogin,
                        loadAllPages = loadAllPages,
                    )

                BrowsePageData(
                    title = title ?: response.pageTitle(),
                    sections = BlockedContent.filterSections(sections),
                    continuation = primarySectionList?.continuationToken() ?: secondarySectionList?.continuationToken(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                YtLogger.e(TAG, "getBrowsePageData failed type=${e::class.simpleName ?: "unknown"}")
                throw e
            }
        }

    private suspend fun processBrowseSections(
        elements: List<JsonObject>,
        isPersonal: Boolean,
        setLogin: Boolean,
        loadAllPages: Boolean,
        shelfRequests: Semaphore = Semaphore(SHELF_CONCURRENCY),
    ): List<HomeSection> =
        coroutineScope {
            elements
                .map { element ->
                    async {
                        val sections = mutableListOf<HomeSection>()
                        processBrowseSection(element, sections, isPersonal, setLogin, loadAllPages, shelfRequests)
                        sections
                    }
                }.awaitAll()
                .flatten()
        }

    /** List shelves can have empty first pages with tracks behind a continuation. */
    private suspend fun processBrowseSection(
        sectionElement: JsonObject,
        sections: MutableList<HomeSection>,
        isPersonal: Boolean = false,
        setLogin: Boolean = false,
        loadAllPages: Boolean,
        shelfRequests: Semaphore,
    ) {
        val itemSection = sectionElement["itemSectionRenderer"]?.jsonObject
        if (itemSection != null) {
            sections +=
                processBrowseSections(
                    itemSection["contents"]?.jsonArray.orEmpty().map { it.jsonObject },
                    isPersonal,
                    setLogin,
                    loadAllPages,
                    shelfRequests,
                )
            return
        }

        val isListStyle = sectionElement.containsKey("musicShelfRenderer") || sectionElement.containsKey("musicPlaylistShelfRenderer")
        if (!isListStyle) {
            processShelfToSections(sectionElement, sections, isPersonal)
            return
        }

        val shelf =
            sectionElement["musicShelfRenderer"]?.jsonObject
                ?: sectionElement["musicPlaylistShelfRenderer"]?.jsonObject
                ?: return

        val title = shelf["title"]?.jsonObject.runText()
        val subtitle =
            shelf["header"]
                ?.jsonObject
                ?.get("musicCarouselShelfBasicHeaderRenderer")
                ?.jsonObject
                ?.get("strapline")
                ?.jsonObject
                .runText()
        val endpoint = shelf.shelfNavigationEndpoint()
        val browseId = endpoint.browseId()
        val sectionParams = endpoint.browseParams()

        val initialSections = mutableListOf<HomeSection>()
        processShelfToSections(sectionElement, initialSections, isPersonal)
        val initialSection = initialSections.singleOrNull()
        val token = shelf.continuationToken() ?: (shelf["contents"] ?: shelf["items"]).findContinuationToken()
        val rest =
            shelfRequests.withPermit {
                shelfPager().collectPage(token, maxPages = if (loadAllPages) 50 else 0, setLogin = setLogin)
            }
        val items = initialSection?.items.orEmpty() + rest.items

        if (items.isNotEmpty() || rest.continuation != null) {
            sections.add(
                (
                    initialSection ?: HomeSection(
                        title.orEmpty(),
                        items,
                        subtitle,
                        browseId,
                        sectionParams,
                        isListStyle = true,
                        hasTitle = title != null,
                        isPersonal = isPersonal,
                        continuation = rest.continuation,
                    )
                ).copy(items = items, continuation = rest.continuation),
            )
        }
    }

    private fun shelfPager() =
        BrowseContinuationPager(
            pageSource = BrowseContinuationPageSource { token, setLogin -> browseContinuation(token, setLogin) },
            itemParser = ::parseItem,
        )

    suspend fun getBrowseShelfContinuation(
        token: String,
        setLogin: Boolean,
    ): YouTubeDataPage<YTItem> =
        withContext(responseDecodeDispatcher) {
            val page = shelfPager().loadPage(token, setLogin)
            return@withContext YouTubeDataPage(BlockedContent.filterItems(page.items), page.continuation)
        }

    private suspend fun browseContinuation(
        token: String,
        setLogin: Boolean,
    ): BrowseResponse {
        val session = innerTube.sessionSnapshot()
        val revision = SongLikeState.voteObservationRevision()
        val response = innerTube.browse(YouTubeClient.WEB_REMIX, continuation = token, setLogin = setLogin)
        check(response.status.isSuccess()) { "Browse continuation failed with HTTP ${response.status.value}" }
        return response.browseResponseWithVotes(innerTube, session, revision, setLogin || session.useLoginForBrowse)
    }

    suspend fun getHomeSectionsContinuation(
        token: String,
        isPersonal: Boolean = false,
        loadAllPages: Boolean = true,
    ): Pair<List<HomeSection>, String?> =
        withContext(responseDecodeDispatcher) {
            val setLogin = isPersonal || innerTube.useLoginForBrowse
            val response = browseContinuation(token, setLogin)
            val cont = response.continuationContents?.sectionListContinuation
            val sections =
                processBrowseSections(
                    cont
                        ?.get("contents")
                        ?.jsonArray
                        .orEmpty()
                        .map { it.jsonObject },
                    isPersonal,
                    setLogin,
                    loadAllPages = false,
                )
            val next =
                cont
                    ?.get("continuations")
                    ?.jsonArray
                    ?.getOrNull(0)
                    ?.jsonObject
                    ?.get("nextContinuationData")
                    ?.jsonObject
                    ?.get("continuation")
                    ?.jsonPrimitive
                    ?.contentOrNull

            val filledSections =
                coroutineScope {
                    val shelfRequests = Semaphore(SHELF_CONCURRENCY)
                    sections
                        .map { section ->
                            async {
                                if (section.continuation != null) {
                                    val extra =
                                        shelfRequests.withPermit {
                                            shelfPager().collectPage(
                                                section.continuation,
                                                maxPages = if (loadAllPages) 4 else 0,
                                                setLogin = setLogin,
                                            )
                                        }
                                    section.copy(items = section.items + extra.items, continuation = extra.continuation)
                                } else {
                                    section
                                }
                            }
                        }.awaitAll()
                }
            return@withContext BlockedContent.filterSections(filledSections) to next
        }
}
