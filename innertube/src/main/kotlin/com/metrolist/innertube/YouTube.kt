package com.metrolist.innertube

import com.metrolist.innertube.models.AccountInfo
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.GridRenderer
import com.metrolist.innertube.models.MediaInfo
import com.metrolist.innertube.models.MusicCarouselShelfRenderer
import com.metrolist.innertube.models.MusicMultiRowListItemRenderer
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.MusicShelfRenderer
import com.metrolist.innertube.models.MusicTwoRowItemRenderer
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.Run
import com.metrolist.innertube.models.Runs
import com.metrolist.innertube.models.SearchSuggestions
import com.metrolist.innertube.models.SectionListRenderer
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.TasteArtist
import com.metrolist.innertube.models.TasteProfile
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertube.models.getContinuation
import com.metrolist.innertube.models.getItems
import com.metrolist.innertube.models.oddElements
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.utils.parseTime
import com.metrolist.innertube.models.response.AccountMenuResponse
import com.metrolist.innertube.models.response.BrowseResponse
import com.metrolist.innertube.models.response.CreatePlaylistResponse
import com.metrolist.innertube.models.response.EditPlaylistResponse
import com.metrolist.innertube.models.response.FeedbackResponse
import com.metrolist.innertube.models.response.GetQueueResponse
import com.metrolist.innertube.models.response.GetSearchSuggestionsResponse
import com.metrolist.innertube.models.response.GetTranscriptResponse
import com.metrolist.innertube.models.response.ImageUploadResponse
import com.metrolist.innertube.models.response.NextResponse
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.innertube.models.response.SearchResponse
import com.metrolist.innertube.pages.AlbumPage
import com.metrolist.innertube.pages.ArtistItemsContinuationPage
import com.metrolist.innertube.pages.ArtistItemsPage
import com.metrolist.innertube.pages.ArtistPage
import com.metrolist.innertube.pages.BrowseResult
import com.metrolist.innertube.pages.ChartsPage
import com.metrolist.innertube.pages.ExplorePage
import com.metrolist.innertube.pages.HistoryPage
import com.metrolist.innertube.pages.HomePage
import com.metrolist.innertube.pages.LibraryContinuationPage
import com.metrolist.innertube.pages.LibraryPage
import com.metrolist.innertube.pages.MoodAndGenres
import com.metrolist.innertube.pages.NewReleaseAlbumPage
import com.metrolist.innertube.pages.NextPage
import com.metrolist.innertube.pages.NextResult
import com.metrolist.innertube.pages.PageHelper
import com.metrolist.innertube.pages.PlaylistContinuationPage
import com.metrolist.innertube.pages.PlaylistPage
import com.metrolist.innertube.pages.PodcastPage
import com.metrolist.innertube.pages.RelatedPage
import com.metrolist.innertube.pages.SearchPage
import com.metrolist.innertube.pages.SearchResult
import com.metrolist.innertube.pages.SearchSuggestionPage
import com.metrolist.innertube.pages.SearchSummary
import com.metrolist.innertube.pages.SearchSummaryPage
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.net.Proxy
import kotlin.random.Random

/**
 * Parse useful data with [InnerTube] sending requests.
 * Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic)
 */
object YouTube {
    private val innerTube = InnerTube()
    private const val ENABLE_NEWPIPE_STREAM_INFO_EXTRACTOR = false

    var locale: YouTubeLocale
        get() = innerTube.locale
        set(value) {
            innerTube.locale = value
        }
    var visitorData: String?
        get() = innerTube.visitorData
        set(value) {
            innerTube.visitorData = value
        }
    var dataSyncId: String?
        get() = innerTube.dataSyncId
        set(value) {
            innerTube.dataSyncId = value
        }
    var cookie: String?
        get() = innerTube.cookie
        set(value) {
            innerTube.cookie = value
        }
    var proxy: Proxy?
        get() = innerTube.proxy
        set(value) {
            innerTube.proxy = value
        }

    var proxyAuth: String?
        get() = innerTube.proxyAuth
        set(value) {
            innerTube.proxyAuth = value
        }
    var useLoginForBrowse: Boolean
        get() = innerTube.useLoginForBrowse
        set(value) {
            innerTube.useLoginForBrowse = value
        }

    suspend fun searchSuggestions(query: String): Result<SearchSuggestions> =
        runCatching {
            val response = innerTube.getSearchSuggestions(WEB_REMIX, query).body<GetSearchSuggestionsResponse>()
            SearchSuggestions(
                queries =
                    response.contents
                        ?.getOrNull(0)
                        ?.searchSuggestionsSectionRenderer
                        ?.contents
                        ?.mapNotNull { content ->
                            content.searchSuggestionRenderer
                                ?.suggestion
                                ?.runs
                                ?.joinToString(separator = "") { it.text }
                        }.orEmpty(),
                recommendedItems =
                    response.contents
                        ?.getOrNull(1)
                        ?.searchSuggestionsSectionRenderer
                        ?.contents
                        ?.mapNotNull {
                            it.musicResponsiveListItemRenderer?.let { renderer ->
                                SearchSuggestionPage.fromMusicResponsiveListItemRenderer(renderer)
                            }
                        }.orEmpty(),
            )
        }

    suspend fun searchSummary(query: String): Result<SearchSummaryPage> =
        runCatching {
            val response = innerTube.search(WEB_REMIX, query).body<SearchResponse>()
            val allSummaries = mutableListOf<SearchSummary>()

            response.contents
                ?.tabbedSearchResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.forEach { section ->
                    if (section.musicCardShelfRenderer != null) {
                        // Top result card - keep as single section
                        val cardRenderer = section.musicCardShelfRenderer
                        val cardArtists = if (cardRenderer.onTap.browseEndpoint?.isArtistEndpoint == true) {
                            listOfNotNull(
                                cardRenderer.title.runs?.firstOrNull()?.text?.let { name ->
                                    Artist(name, cardRenderer.onTap.browseEndpoint.browseId)
                                },
                            )
                        } else {
                            PageHelper.extractArtists(cardRenderer.subtitle.runs)
                        }
                        val items =
                            listOfNotNull(SearchSummaryPage.fromMusicCardShelfRenderer(cardRenderer))
                                .plus(
                                    cardRenderer.contents
                                        ?.mapNotNull { it.musicResponsiveListItemRenderer }
                                        ?.mapNotNull { renderer ->
                                            SearchSummaryPage.fromMusicResponsiveListItemRenderer(renderer, cardArtists)
                                        }
                                        .orEmpty(),
                                ).distinctBy { it.id }

                        if (items.isNotEmpty()) {
                            allSummaries.add(
                                SearchSummary(
                                    title =
                                        cardRenderer.header
                                            ?.musicCardShelfHeaderBasicRenderer
                                            ?.title
                                            ?.runs
                                            ?.firstOrNull()
                                            ?.text
                                            ?: YouTubeConstants.DEFAULT_TOP_RESULT,
                                    items = items,
                                ),
                            )
                        }
                    } else if (section.musicShelfRenderer != null) {
                        val items =
                            section.musicShelfRenderer.contents
                                ?.getItems()
                                ?.mapNotNull { SearchSummaryPage.fromMusicResponsiveListItemRenderer(it) }
                                ?.distinctBy { it.id }
                                ?: emptyList()

                        if (items.isEmpty()) return@forEach

                        val apiTitle =
                            section.musicShelfRenderer.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text

                        if (apiTitle != null) {
                            // API provided a title, use single section
                            allSummaries.add(SearchSummary(title = apiTitle, items = items))
                        } else {
                            allSummaries.addAll(groupItemsByType(items))
                        }
                    } else if (section.itemSectionRenderer != null) {
                        val items =
                            section.itemSectionRenderer.contents
                                ?.mapNotNull { it.musicResponsiveListItemRenderer }
                                ?.mapNotNull { SearchSummaryPage.fromMusicResponsiveListItemRenderer(it) }
                                ?.distinctBy { it.id }
                                ?: emptyList()

                        if (items.isNotEmpty()) {
                            allSummaries.addAll(groupItemsByType(items))
                        }
                    }
                }

            // Merge sections with the same title
            val mergedSummaries =
                allSummaries
                    .groupBy { it.title }
                    .map { (title, sections) ->
                        SearchSummary(
                            title = title,
                            items = sections.flatMap { it.items }.distinctBy { it.id },
                        )
                    }
                    // Reorder to maintain logical order
                    .sortedBy { summary ->
                        when (summary.title) {
                            YouTubeConstants.DEFAULT_TOP_RESULT -> 0
                            "Songs" -> 1
                            "Videos" -> 2
                            "Albums" -> 3
                            "Artists" -> 4
                            "Playlists" -> 5
                            "Podcasts" -> 6
                            "Episodes" -> 7
                            "Profiles" -> 8
                            else -> 9
                        }
                    }

            SearchSummaryPage(summaries = mergedSummaries)
        }

    private fun groupItemsByType(items: List<YTItem>): List<SearchSummary> {
        val grouped =
            items.groupBy { item ->
                when (item) {
                    is EpisodeItem -> "Episodes"
                    is PodcastItem -> "Podcasts"
                    is AlbumItem -> "Albums"
                    is ArtistItem -> if (item.isProfile) "Profiles" else "Artists"
                    is PlaylistItem -> "Playlists"
                    is SongItem -> when {
                        item.isEpisode -> "Episodes"
                        item.isVideoSong -> "Videos"
                        else -> "Songs"
                    }
                }
            }

        val sectionOrder =
            listOf(
                "Songs", "Videos", "Albums", "Artists", "Playlists",
                "Podcasts", "Episodes", "Profiles", YouTubeConstants.DEFAULT_OTHER_RESULTS,
            )

        return sectionOrder.mapNotNull { sectionName ->
            grouped[sectionName]?.takeIf { it.isNotEmpty() }?.let { groupItems ->
                SearchSummary(title = sectionName, items = groupItems)
            }
        }
    }

    suspend fun search(
        query: String,
        filter: SearchFilter,
    ): Result<SearchResult> =
        runCatching {
            val response = innerTube.search(WEB_REMIX, query, filter.value).body<SearchResponse>()
            val searchItems = mutableListOf<YTItem>()
            var searchContinuation: String? = null

            response.contents
                ?.tabbedSearchResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.forEach { section ->
                    if (section.musicShelfRenderer != null) {
                        val shelf = section.musicShelfRenderer
                        val items = shelf.contents?.getItems()?.mapNotNull { SearchPage.toYTItem(it) } ?: emptyList()
                        searchItems.addAll(items)
                        if (searchContinuation == null) {
                            searchContinuation = shelf.continuations?.getContinuation()
                        }
                    } else if (section.itemSectionRenderer != null) {
                        val items =
                            section.itemSectionRenderer.contents
                                ?.mapNotNull { it.musicResponsiveListItemRenderer }
                                ?.mapNotNull { SearchPage.toYTItem(it) }
                                ?: emptyList()
                        searchItems.addAll(items)
                    }
                }

            SearchResult(
                items = searchItems.distinctBy { it.id },
                continuation = searchContinuation,
            )
        }

    suspend fun searchContinuation(continuation: String): Result<SearchResult> =
        runCatching {
            val response = innerTube.search(WEB_REMIX, continuation = continuation).body<SearchResponse>()
            val items =
                response.continuationContents
                    ?.musicShelfContinuation
                    ?.contents
                    ?.mapNotNull {
                        SearchPage.toYTItem(it.musicResponsiveListItemRenderer)
                    } ?: emptyList()
            SearchResult(
                items = items,
                continuation =
                    if (items.isEmpty()) {
                        null
                    } else {
                        response.continuationContents
                            ?.musicShelfContinuation
                            ?.continuations
                            ?.getContinuation()
                    },
            )
        }

    suspend fun album(
        browseId: String,
        withSongs: Boolean = true,
    ): Result<AlbumPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId).body<BrowseResponse>()
            if (browseId.contains("FEmusic_library_privately_owned_release_detail")) {
                val playlistId =
                    response.header
                        ?.musicDetailHeaderRenderer
                        ?.menu
                        ?.menuRenderer
                        ?.topLevelButtons
                        ?.firstOrNull()
                        ?.buttonRenderer
                        ?.navigationEndpoint
                        ?.watchPlaylistEndpoint
                        ?.playlistId!!
                val albumItem =
                    AlbumItem(
                        browseId = browseId,
                        playlistId = playlistId,
                        title =
                            response.header.musicDetailHeaderRenderer.title.runs
                                ?.firstOrNull()
                                ?.text!!,
                        artists =
                            response.header.musicDetailHeaderRenderer.subtitle.runs?.filter { it.navigationEndpoint != null }?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            },
                        year =
                            response.header.musicDetailHeaderRenderer.subtitle.runs
                                ?.lastOrNull()
                                ?.text
                                ?.toIntOrNull(),
                        thumbnail =
                            response.header.musicDetailHeaderRenderer.thumbnail.croppedSquareThumbnailRenderer
                                ?.thumbnail
                                ?.thumbnails
                                ?.lastOrNull()!!
                                .url,
                        explicit = false, // TODO: Extract explicit badge for albums from YouTube response
                    )
                return@runCatching AlbumPage(
                    album = albumItem,
                    songs =
                        response.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.musicShelfRenderer
                            ?.contents
                            ?.getItems()
                            ?.mapNotNull {
                                AlbumPage.getSong(it, albumItem)
                            }!!
                            .toMutableList(),
                    otherVersions = emptyList(),
                )
            } else {
                val playlistId =
                    response.microformat
                        ?.microformatDataRenderer
                        ?.urlCanonical
                        ?.substringAfterLast('=')!!
                val albumItem =
                    AlbumItem(
                        browseId = browseId,
                        playlistId = playlistId,
                        title =
                            response.contents
                                ?.twoColumnBrowseResultsRenderer
                                ?.tabs
                                ?.firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text!!,
                        artists =
                            response.contents.twoColumnBrowseResultsRenderer.tabs
                                .firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveHeaderRenderer
                                ?.straplineTextOne
                                ?.runs
                                ?.oddElements()
                                ?.map {
                                    Artist(
                                        name = it.text,
                                        id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                    )
                                }!!,
                        year =
                            response.contents.twoColumnBrowseResultsRenderer.tabs
                                .firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveHeaderRenderer
                                ?.subtitle
                                ?.runs
                                ?.lastOrNull()
                                ?.text
                                ?.toIntOrNull(),
                        thumbnail =
                            response.contents.twoColumnBrowseResultsRenderer.tabs
                                .firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveHeaderRenderer
                                ?.thumbnail
                                ?.musicThumbnailRenderer
                                ?.thumbnail
                                ?.thumbnails
                                ?.lastOrNull()
                                ?.url!!,
                        explicit = false, // TODO: Extract explicit badge for albums from YouTube response
                    )
                val albumSongsList =
                    if (withSongs) {
                        albumSongs(
                            playlistId,
                            albumItem,
                        ).getOrThrow()
                    } else {
                        emptyList()
                    }
                // When YouTube credits the album to a label/distributor channel (the header
                // strapline) but every track names the same performing artist, surface the
                // performer as the album artist instead of the label.
                val performer =
                    albumSongsList.firstOrNull()?.artists?.firstOrNull()?.takeIf { first ->
                        first.name.isNotBlank() &&
                            albumSongsList.all { it.artists.firstOrNull()?.name == first.name }
                    }
                val resolvedAlbum =
                    if (performer != null && albumItem.artists?.any { it.name == performer.name } != true) {
                        albumItem.copy(artists = listOf(performer))
                    } else {
                        albumItem
                    }
                return@runCatching AlbumPage(
                    album = resolvedAlbum,
                    songs = albumSongsList,
                    otherVersions =
                        response.contents.twoColumnBrowseResultsRenderer.secondaryContents
                            ?.sectionListRenderer
                            ?.contents
                            ?.getOrNull(
                                1,
                            )?.musicCarouselShelfRenderer
                            ?.contents
                            ?.mapNotNull { it.musicTwoRowItemRenderer }
                            ?.mapNotNull(NewReleaseAlbumPage::fromMusicTwoRowItemRenderer)
                            .orEmpty(),
                )
            }
        }

    suspend fun albumSongs(
        playlistId: String,
        album: AlbumItem? = null,
    ): Result<List<SongItem>> =
        runCatching {
            var response = innerTube.browse(WEB_REMIX, "VL$playlistId").body<BrowseResponse>()
            val shelf =
                response.contents
                    ?.twoColumnBrowseResultsRenderer
                    ?.secondaryContents
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()
            val shelfContents =
                shelf?.musicPlaylistShelfRenderer?.contents
                    ?: shelf?.musicShelfRenderer?.contents
                    ?: emptyList()
            val songs =
                shelfContents
                    .getItems()
                    .mapNotNull {
                        AlbumPage.getSong(it, album)
                    }
                    .toMutableList()
            var continuation =
                shelfContents
                    .getContinuation()
            val seenContinuations = mutableSetOf<String>()
            var requestCount = 0
            val maxRequests = 50 // Prevent excessive API calls

            while (continuation != null && requestCount < maxRequests) {
                // Prevent infinite loops by tracking seen continuations
                if (continuation in seenContinuations) {
                    break
                }
                seenContinuations.add(continuation)
                requestCount++

                response =
                    innerTube
                        .browse(
                            client = WEB_REMIX,
                            continuation = continuation,
                        ).body<BrowseResponse>()
                songs +=
                    response.onResponseReceivedActions
                        ?.firstOrNull()
                        ?.appendContinuationItemsAction
                        ?.continuationItems
                        ?.getItems()
                        ?.mapNotNull {
                            AlbumPage.getSong(it, album)
                        }.orEmpty()
                continuation =
                    response.continuationContents
                        ?.musicPlaylistShelfContinuation
                        ?.continuations
                        ?.getContinuation()
            }
            songs
        }

    suspend fun artist(browseId: String): Result<ArtistPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId).body<BrowseResponse>()

            fun mapRuns(runs: List<Run>?): List<Run>? =
                runs?.map { run ->
                    Run(
                        text = run.text,
                        navigationEndpoint = run.navigationEndpoint,
                    )
                }

            val descriptionRuns =
                response.contents
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull { it.musicDescriptionShelfRenderer != null }
                    ?.musicDescriptionShelfRenderer
                    ?.description
                    ?.runs
                    ?.let(::mapRuns)
                    ?: response.header
                        ?.musicImmersiveHeaderRenderer
                        ?.description
                        ?.runs
                        ?.let(::mapRuns)

            // Check subscription state from multiple locations:
            // 1. musicImmersiveHeaderRenderer.subscriptionButton (regular artists)
            // 2. musicVisualHeaderRenderer.subscriptionButton (podcast channels)
            val immersiveSubscribed =
                response.header
                    ?.musicImmersiveHeaderRenderer
                    ?.subscriptionButton
                    ?.subscribeButtonRenderer
                    ?.subscribed
            val visualSubscribed =
                response.header
                    ?.musicVisualHeaderRenderer
                    ?.subscriptionButton
                    ?.subscribeButtonRenderer
                    ?.subscribed
            val isSubscribed = immersiveSubscribed ?: visualSubscribed ?: false

            // Also extract channelId from visual header if not in immersive header
            val channelIdFromVisual =
                response.header
                    ?.musicVisualHeaderRenderer
                    ?.subscriptionButton
                    ?.subscribeButtonRenderer
                    ?.channelId

            ArtistPage(
                artist =
                    ArtistItem(
                        id = browseId,
                        title =
                            response.header
                                ?.musicImmersiveHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?: response.header
                                    ?.musicVisualHeaderRenderer
                                    ?.title
                                    ?.runs
                                    ?.firstOrNull()
                                    ?.text
                                ?: response.header
                                    ?.musicHeaderRenderer
                                    ?.title
                                    ?.runs
                                    ?.firstOrNull()
                                    ?.text!!,
                        thumbnail =
                            response.header
                                ?.musicImmersiveHeaderRenderer
                                ?.thumbnail
                                ?.musicThumbnailRenderer
                                ?.getThumbnailUrl()
                                ?: response.header
                                    ?.musicVisualHeaderRenderer
                                    ?.foregroundThumbnail
                                    ?.musicThumbnailRenderer
                                    ?.getThumbnailUrl()
                                ?: response.header
                                    ?.musicDetailHeaderRenderer
                                    ?.thumbnail
                                    ?.musicThumbnailRenderer
                                    ?.getThumbnailUrl(),
                        channelId =
                            response.header
                                ?.musicImmersiveHeaderRenderer
                                ?.subscriptionButton
                                ?.subscribeButtonRenderer
                                ?.channelId
                                ?: channelIdFromVisual,
                        playEndpoint =
                            response.contents
                                ?.singleColumnBrowseResultsRenderer
                                ?.tabs
                                ?.firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicShelfRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveListItemRenderer
                                ?.overlay
                                ?.musicItemThumbnailOverlayRenderer
                                ?.content
                                ?.musicPlayButtonRenderer
                                ?.playNavigationEndpoint
                                ?.watchEndpoint,
                        shuffleEndpoint =
                            response.header
                                ?.musicImmersiveHeaderRenderer
                                ?.playButton
                                ?.buttonRenderer
                                ?.navigationEndpoint
                                ?.watchEndpoint
                                ?: response.contents
                                    ?.singleColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.firstOrNull()
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.firstOrNull()
                                    ?.musicShelfRenderer
                                    ?.contents
                                    ?.firstOrNull()
                                    ?.musicResponsiveListItemRenderer
                                    ?.navigationEndpoint
                                    ?.watchPlaylistEndpoint,
                        radioEndpoint =
                            response.header
                                ?.musicImmersiveHeaderRenderer
                                ?.startRadioButton
                                ?.buttonRenderer
                                ?.navigationEndpoint
                                ?.watchEndpoint,
                    ),
                sections =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.mapNotNull(ArtistPage::fromSectionListRendererContent)!!,
                description = descriptionRuns?.joinToString(separator = "") { it.text },
                subscriberCountText =
                    response.header
                        ?.musicImmersiveHeaderRenderer
                        ?.subscriptionButton2
                        ?.subscribeButtonRenderer
                        ?.subscriberCountWithSubscribeText
                        ?.runs
                        ?.firstOrNull()
                        ?.text
                        ?: response.header
                            ?.musicImmersiveHeaderRenderer
                            ?.subscriptionButton
                            ?.subscribeButtonRenderer
                            ?.longSubscriberCountText
                            ?.runs
                            ?.firstOrNull()
                            ?.text
                        ?: response.header
                            ?.musicImmersiveHeaderRenderer
                            ?.subscriptionButton
                            ?.subscribeButtonRenderer
                            ?.shortSubscriberCountText
                            ?.runs
                            ?.firstOrNull()
                            ?.text,
                monthlyListenerCount =
                    response.header
                        ?.musicImmersiveHeaderRenderer
                        ?.monthlyListenerCount
                        ?.runs
                        ?.firstOrNull()
                        ?.text,
                descriptionRuns = descriptionRuns,
                isSubscribed = isSubscribed,
            )
        }

    suspend fun artistItems(endpoint: BrowseEndpoint): Result<ArtistItemsPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, endpoint.browseId, endpoint.params).body<BrowseResponse>()
            val sectionContent =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()

            val gridRenderer = sectionContent?.gridRenderer
            val musicCarouselShelfRenderer = sectionContent?.musicCarouselShelfRenderer
            val musicPlaylistShelfRenderer = sectionContent?.musicPlaylistShelfRenderer
            val musicShelfRenderer = sectionContent?.musicShelfRenderer

            when {
                gridRenderer != null -> {
                    ArtistItemsPage(
                        title =
                            gridRenderer.header
                                ?.gridHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                .orEmpty(),
                        items =
                            gridRenderer.items.mapNotNull {
                                it.musicTwoRowItemRenderer?.let { renderer ->
                                    ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer)
                                }
                            },
                        continuation = gridRenderer.continuations?.getContinuation(),
                    )
                }

                musicCarouselShelfRenderer != null -> {
                    ArtistItemsPage(
                        title =
                            musicCarouselShelfRenderer.header
                                ?.musicCarouselShelfBasicHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                .orEmpty(),
                        items =
                            musicCarouselShelfRenderer.contents.mapNotNull { content ->
                                content.musicTwoRowItemRenderer?.let { renderer ->
                                    ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer)
                                } ?: content.musicResponsiveListItemRenderer?.let { renderer ->
                                    ArtistItemsPage.fromMusicResponsiveListItemRenderer(renderer)
                                }
                            },
                        continuation = null,
                    )
                }

                musicShelfRenderer != null -> {
                    ArtistItemsPage(
                        title =
                            musicShelfRenderer.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?: response.header
                                    ?.musicHeaderRenderer
                                    ?.title
                                    ?.runs
                                    ?.firstOrNull()
                                    ?.text
                                ?: "",
                        items =
                            musicShelfRenderer.contents?.getItems()?.mapNotNull {
                                ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                            } ?: emptyList(),
                        continuation = musicShelfRenderer.continuations?.getContinuation(),
                    )
                }

                else -> {
                    ArtistItemsPage(
                        title =
                            response.header
                                ?.musicHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text ?: "",
                        items =
                            musicPlaylistShelfRenderer?.contents?.getItems()?.mapNotNull {
                                ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                            } ?: emptyList(),
                        continuation = musicPlaylistShelfRenderer?.contents?.getContinuation(),
                    )
                }
            }
        }

    suspend fun artistItemsContinuation(continuation: String): Result<ArtistItemsContinuationPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, continuation = continuation).body<BrowseResponse>()

            when {
                response.continuationContents?.gridContinuation != null -> {
                    val gridContinuation = response.continuationContents.gridContinuation
                    val items =
                        gridContinuation.items.mapNotNull {
                            it.musicTwoRowItemRenderer?.let { renderer ->
                                ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer)
                            }
                        }
                    ArtistItemsContinuationPage(
                        items = items,
                        continuation = if (items.isEmpty()) null else gridContinuation.continuations?.getContinuation(),
                    )
                }

                response.continuationContents?.musicPlaylistShelfContinuation != null -> {
                    val musicPlaylistShelfContinuation = response.continuationContents.musicPlaylistShelfContinuation
                    val items =
                        musicPlaylistShelfContinuation.contents.getItems().mapNotNull {
                            ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                        }
                    ArtistItemsContinuationPage(
                        items = items,
                        continuation = if (items.isEmpty()) null else musicPlaylistShelfContinuation.continuations?.getContinuation(),
                    )
                }

                else -> {
                    val continuationItems =
                        response.onResponseReceivedActions
                            ?.firstOrNull()
                            ?.appendContinuationItemsAction
                            ?.continuationItems
                    val items =
                        continuationItems?.getItems()?.mapNotNull {
                            ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                        } ?: emptyList()
                    ArtistItemsContinuationPage(
                        items = items,
                        continuation = if (items.isEmpty()) null else continuationItems?.getContinuation(),
                    )
                }
            }
        }

    suspend fun playlist(playlistId: String): Result<PlaylistPage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "VL$playlistId",
                        setLogin = true,
                    ).body<BrowseResponse>()
            val base =
                response.contents
                    ?.twoColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()
            val header =
                base?.musicResponsiveHeaderRenderer
                    ?: base?.musicEditablePlaylistDetailHeaderRenderer?.header?.musicResponsiveHeaderRenderer

            val editable = base?.musicEditablePlaylistDetailHeaderRenderer != null

            val description: String? =
                header?.description?.musicDescriptionShelfRenderer?.description?.runs?.joinToString("") { it.text }
                    ?: base?.musicEditablePlaylistDetailHeaderRenderer
                        ?.header?.musicDetailHeaderRenderer
                        ?.description?.runs?.joinToString("") { it.text }
                    ?: response.header?.musicDetailHeaderRenderer
                        ?.description?.runs?.joinToString("") { it.text }

            val author: Artist? = run {
                val fromStrapline = header?.straplineTextOne?.runs
                    ?.firstOrNull()
                    ?.let { Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId) }
                if (fromStrapline != null) return@run fromStrapline

                val detailSubtitle = base?.musicEditablePlaylistDetailHeaderRenderer
                    ?.header?.musicDetailHeaderRenderer?.subtitle?.runs
                    ?: response.header?.musicDetailHeaderRenderer?.subtitle?.runs
                if (detailSubtitle != null) {
                    val segments = detailSubtitle.splitBySeparator()
                    val run = segments.getOrNull(1)?.firstOrNull()
                        ?: segments.firstOrNull()?.firstOrNull()
                    val fromDetail = run?.let {
                        Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId)
                    }
                    if (fromDetail != null) return@run fromDetail
                }

                val fromHeaderSubtitle = header?.subtitle?.runs
                    ?.firstOrNull { it.navigationEndpoint != null }
                    ?.let { Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId) }
                if (fromHeaderSubtitle != null) return@run fromHeaderSubtitle

                val facepile = header?.facepile?.avatarStackViewModel
                if (facepile != null) {
                    val name = facepile.text?.content
                    val browseId = facepile.rendererContext?.commandContext?.onTap?.innertubeCommand?.browseEndpoint?.browseId
                    if (name != null) return@run Artist(name = name, id = browseId)
                }

                val fromMusicHeaderStrapline = response.header?.musicHeaderRenderer
                    ?.straplineTextOne?.runs?.firstOrNull()
                    ?.let { Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId) }
                if (fromMusicHeaderStrapline != null) return@run fromMusicHeaderStrapline

                null
            }

            val authorAvatarUrl: String? = header?.facepile
                ?.avatarStackViewModel?.avatars?.firstOrNull()
                ?.avatarViewModel?.image?.sources?.firstOrNull()
                ?.url

            PlaylistPage(
                playlist =
                    PlaylistItem(
                        id = playlistId,
                        title =
                            header?.title?.runs?.firstOrNull()?.text
                                ?: response.header?.musicHeaderRenderer?.title?.runs?.firstOrNull()?.text
                                ?: "",
                        author = author,
                        songCountText =
                            (header?.secondSubtitle
                                ?: response.header?.musicHeaderRenderer?.secondSubtitle)
                                ?.runs
                                ?.findLast {
                                    it.text.any { c -> c.isDigit() } &&
                                        !it.text.contains("view", ignoreCase = true) &&
                                        !it.text.contains("hour", ignoreCase = true) &&
                                        !it.text.contains("minute", ignoreCase = true)
                                }?.text,
                        thumbnail =
                            header?.thumbnail
                                ?.musicThumbnailRenderer
                                ?.thumbnail
                                ?.thumbnails
                                ?.lastOrNull()
                                ?.url
                                ?: response.header
                                    ?.musicHeaderRenderer
                                    ?.thumbnail
                                    ?.musicThumbnailRenderer
                                    ?.thumbnails
                                    ?.lastOrNull()
                                    ?.url
                                ?: "",
                        playEndpoint = null,
                        shuffleEndpoint =
                            header?.buttons?.lastOrNull()
                                ?.menuRenderer?.items?.firstOrNull()
                                ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint
                                ?: response.header?.musicHeaderRenderer?.buttons?.lastOrNull()
                                    ?.menuRenderer?.items?.firstOrNull()
                                    ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                        radioEndpoint =
                            header?.buttons?.getOrNull(2)
                                ?.menuRenderer?.items?.find {
                                    it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                                }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint
                                ?: response.header?.musicHeaderRenderer?.buttons?.getOrNull(2)
                                    ?.menuRenderer?.items?.find {
                                        it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                        isEditable = editable,
                        description = description,
                        authorAvatarUrl = authorAvatarUrl,
                    ),
                songs = run {
                    val twoColShelf =
                        response.contents
                            ?.twoColumnBrowseResultsRenderer
                            ?.secondaryContents
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                    val twoColContents =
                        twoColShelf?.musicPlaylistShelfRenderer?.contents
                            ?: twoColShelf?.musicShelfRenderer?.contents
                    val singleColShelf =
                        response.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                    val singleColContents =
                        singleColShelf?.musicPlaylistShelfRenderer?.contents
                            ?: singleColShelf?.musicShelfRenderer?.contents
                    (twoColContents ?: singleColContents)
                        ?.getItems()
                        ?.mapNotNull { PlaylistPage.fromMusicResponsiveListItemRenderer(it) }
                        ?: emptyList()
                },
                songsContinuation = run {
                    val twoColShelf =
                        response.contents
                            ?.twoColumnBrowseResultsRenderer
                            ?.secondaryContents
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                    val twoColContents =
                        twoColShelf?.musicPlaylistShelfRenderer?.contents
                            ?: twoColShelf?.musicShelfRenderer?.contents
                    val twoColContinuations =
                        twoColShelf?.musicPlaylistShelfRenderer?.continuations
                            ?: twoColShelf?.musicShelfRenderer?.continuations
                    val singleColShelf =
                        response.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                    val singleColContents =
                        singleColShelf?.musicPlaylistShelfRenderer?.contents
                            ?: singleColShelf?.musicShelfRenderer?.contents
                    val singleColContinuations =
                        singleColShelf?.musicPlaylistShelfRenderer?.continuations
                            ?: singleColShelf?.musicShelfRenderer?.continuations
                    val mergedContents = twoColContents ?: singleColContents
                    val mergedContinuations = twoColContinuations ?: singleColContinuations
                    mergedContents?.getContinuation() ?: mergedContinuations?.getContinuation()
                },
                continuation =
                    response.contents
                        ?.twoColumnBrowseResultsRenderer
                        ?.secondaryContents
                        ?.sectionListRenderer
                        ?.continuations
                        ?.getContinuation(),
            )
        }

    suspend fun playlistContinuation(continuation: String): Result<PlaylistContinuationPage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        continuation = continuation,
                        setLogin = true,
                    ).body<BrowseResponse>()

            val mainContents: List<MusicShelfRenderer.Content> =
                response.continuationContents
                    ?.sectionListContinuation
                    ?.contents
                    ?.mapNotNull { content: SectionListRenderer.Content ->
                        content.musicPlaylistShelfRenderer?.contents
                            ?: content.musicShelfRenderer?.contents
                    }
                    ?.flatten()
                    ?: emptyList()

            val shelfContents: List<MusicShelfRenderer.Content> =
                response.continuationContents?.musicPlaylistShelfContinuation?.contents ?: emptyList()

            val musicShelfContinuationContents: List<MusicShelfRenderer.Content> =
                response.continuationContents?.musicShelfContinuation?.contents ?: emptyList()

            val appendedContents: List<MusicShelfRenderer.Content> =
                response.onResponseReceivedActions
                    ?.firstOrNull()
                    ?.appendContinuationItemsAction
                    ?.continuationItems
                    .orEmpty()

            val allContents = mainContents + shelfContents + musicShelfContinuationContents + appendedContents

            val songs =
                allContents
                    .mapNotNull { content: MusicShelfRenderer.Content -> content.musicResponsiveListItemRenderer }
                    .mapNotNull { renderer -> PlaylistPage.fromMusicResponsiveListItemRenderer(renderer) }

            val nextContinuation =
                if (songs.isEmpty()) {
                    null
                } else {
                    response.continuationContents
                        ?.sectionListContinuation
                        ?.continuations
                        ?.getContinuation()
                        ?: response.continuationContents
                            ?.musicPlaylistShelfContinuation
                            ?.continuations
                            ?.getContinuation()
                        ?: response.continuationContents
                            ?.musicShelfContinuation
                            ?.continuations
                            ?.getContinuation()
                        ?: response.onResponseReceivedActions
                            ?.firstOrNull()
                            ?.appendContinuationItemsAction
                            ?.continuationItems
                            ?.getContinuation()
                }

            PlaylistContinuationPage(
                songs = songs,
                continuation = nextContinuation,
            )
        }

    suspend fun podcast(podcastId: String): Result<PodcastPage> = podcastWithDebug(podcastId) { }

    suspend fun podcastWithDebug(
        podcastId: String,
        log: (String) -> Unit,
    ): Result<PodcastPage> =
        runCatching {
            Timber.d("Fetching podcast with ID: $podcastId")
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = podcastId,
                        setLogin = true,
                    ).body<BrowseResponse>()

            Timber.d("Response received, twoColumnBrowseResultsRenderer: ${response.contents?.twoColumnBrowseResultsRenderer != null}")
            Timber.d("singleColumnBrowseResultsRenderer: ${response.contents?.singleColumnBrowseResultsRenderer != null}")

            // Try twoColumn first (standard layout)
            var header =
                response.contents
                    ?.twoColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()
                    ?.musicResponsiveHeaderRenderer

            // Fallback to singleColumn layout
            if (header == null) {
                header =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicResponsiveHeaderRenderer
                Timber.d("Using singleColumn layout, header found: ${header != null}")
            }

            Timber.d("Header title: ${header?.title?.runs?.firstOrNull()?.text}")

            // Debug: Log button structure
            header?.buttons?.forEachIndexed { i, button ->
                Timber.d(
                    "[PODCAST] Button[$i]: menuRenderer=${button.menuRenderer != null}, toggleButtonRenderer=${button.toggleButtonRenderer != null}, playButtonRenderer=${button.musicPlayButtonRenderer != null}",
                )
                button.menuRenderer?.items?.forEachIndexed { j, item ->
                    Timber.d(
                        "[PODCAST] Button[$i].menuItems[$j]: toggle=${item.toggleMenuServiceItemRenderer?.defaultIcon?.iconType}, nav=${item.menuNavigationItemRenderer?.icon?.iconType}",
                    )
                    // Check for SUBSCRIBE button (like artists have)
                    if (item.toggleMenuServiceItemRenderer?.defaultIcon?.iconType == "SUBSCRIBE") {
                        val channelIds =
                            item.toggleMenuServiceItemRenderer.defaultServiceEndpoint.subscribeEndpoint
                                ?.channelIds
                        Timber.d("[PODCAST] Found SUBSCRIBE button! channelIds=$channelIds")
                    }
                }
                button.toggleButtonRenderer?.let { toggle ->
                    Timber.d(
                        "[PODCAST] Button[$i].toggleButtonRenderer: defaultIcon=${toggle.defaultIcon?.iconType}, defaultToken=${toggle.defaultServiceEndpoint?.feedbackEndpoint?.feedbackToken?.take(
                            30,
                        )}, subscribeChannelIds=${toggle.defaultServiceEndpoint?.subscribeEndpoint?.channelIds}",
                    )
                }
            }

            // Extract channelId and subscription state for subscription (like artists)
            val subscribeToggle =
                header
                    ?.buttons
                    ?.flatMap { button ->
                        button.menuRenderer?.items ?: emptyList()
                    }?.find {
                        it.toggleMenuServiceItemRenderer?.defaultIcon?.iconType == "SUBSCRIBE"
                    }?.toggleMenuServiceItemRenderer
            val channelId =
                subscribeToggle
                    ?.defaultServiceEndpoint
                    ?.subscribeEndpoint
                    ?.channelIds
                    ?.firstOrNull()
            // isSelected indicates user is currently subscribed (toggle is in "toggled" state)
            val isChannelSubscribed = subscribeToggle?.isSelected == true
            Timber.d("[PODCAST] Extracted channelId for subscription: $channelId, isSubscribed: $isChannelSubscribed")

            // Extract library tokens from the header's menu buttons OR toggle buttons
            var libraryTokens =
                header
                    ?.buttons
                    ?.flatMap { button ->
                        button.menuRenderer?.items ?: emptyList()
                    }?.let { menuItems ->
                        PageHelper.extractLibraryTokensFromMenuItems(menuItems)
                    }

            // Also check for standalone toggle buttons (used by some podcasts)
            if (libraryTokens?.addToken == null && libraryTokens?.removeToken == null) {
                header?.buttons?.forEach { button ->
                    button.toggleButtonRenderer?.let { toggle ->
                        val iconType = toggle.defaultIcon?.iconType
                        if (iconType != null && PageHelper.isLibraryIcon(iconType)) {
                            val defaultToken = toggle.defaultServiceEndpoint?.feedbackEndpoint?.feedbackToken
                            val toggledToken = toggle.toggledServiceEndpoint?.feedbackEndpoint?.feedbackToken
                            libraryTokens =
                                if (PageHelper.isAddLibraryIcon(iconType)) {
                                    // BOOKMARK_BORDER: default=add, toggled=remove
                                    PageHelper.LibraryFeedbackTokens(defaultToken, toggledToken)
                                } else {
                                    // BOOKMARK: default=remove, toggled=add
                                    PageHelper.LibraryFeedbackTokens(toggledToken, defaultToken)
                                }
                            Timber.d(
                                "[PODCAST] Found toggle button with library tokens - add: ${libraryTokens.addToken != null}, remove: ${libraryTokens.removeToken != null}",
                            )
                        }
                    }
                }
            }
            Timber.d("[PODCAST] Library tokens - add: ${libraryTokens?.addToken != null}, remove: ${libraryTokens?.removeToken != null}")

            val podcastItem =
                PodcastItem(
                    id = podcastId,
                    title =
                        header
                            ?.title
                            ?.runs
                            ?.firstOrNull()
                            ?.text ?: "",
                    author =
                        header?.straplineTextOne?.runs?.firstOrNull()?.let {
                            Artist(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId,
                            )
                        },
                    episodeCountText =
                        header
                            ?.secondSubtitle
                            ?.runs
                            ?.firstOrNull()
                            ?.text,
                    thumbnail =
                        header
                            ?.thumbnail
                            ?.musicThumbnailRenderer
                            ?.thumbnail
                            ?.thumbnails
                            ?.lastOrNull()
                            ?.url,
                    playEndpoint =
                        header
                            ?.buttons
                            ?.find {
                                it.menuRenderer
                                    ?.items
                                    ?.firstOrNull()
                                    ?.menuNavigationItemRenderer
                                    ?.icon
                                    ?.iconType == "PLAY_ARROW"
                            }?.menuRenderer
                            ?.items
                            ?.firstOrNull()
                            ?.menuNavigationItemRenderer
                            ?.navigationEndpoint
                            ?.watchPlaylistEndpoint,
                    shuffleEndpoint =
                        header
                            ?.buttons
                            ?.find {
                                it.menuRenderer?.items?.any { item ->
                                    item.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                                } ==
                                    true
                            }?.menuRenderer
                            ?.items
                            ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                            ?.menuNavigationItemRenderer
                            ?.navigationEndpoint
                            ?.watchPlaylistEndpoint,
                    libraryAddToken = libraryTokens?.addToken,
                    libraryRemoveToken = libraryTokens?.removeToken,
                    channelId = channelId,
                )

            // Try twoColumn for episodes
            val secondaryContents = response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents
            Timber.d("secondaryContents null: ${secondaryContents == null}")
            Timber.d("secondaryContents.sectionListRenderer null: ${secondaryContents?.sectionListRenderer == null}")
            Timber.d("sectionListRenderer.contents size: ${secondaryContents?.sectionListRenderer?.contents?.size ?: 0}")

            secondaryContents?.sectionListRenderer?.contents?.forEachIndexed { index, content ->
                Timber.d(
                    "Content[$index]: musicShelfRenderer=${content.musicShelfRenderer != null}, musicPlaylistShelfRenderer=${content.musicPlaylistShelfRenderer != null}, gridRenderer=${content.gridRenderer != null}",
                )
                content.musicShelfRenderer?.let { shelf ->
                    Timber.d("musicShelfRenderer.contents size: ${shelf.contents?.size ?: 0}")
                }
                content.musicPlaylistShelfRenderer?.let { shelf ->
                    Timber.d("musicPlaylistShelfRenderer.contents size: ${shelf.contents.size}")
                }
            }

            var episodeContents =
                secondaryContents
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()
                    ?.musicShelfRenderer
                    ?.contents

            // Try musicPlaylistShelfRenderer
            if (episodeContents == null) {
                episodeContents =
                    secondaryContents
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicPlaylistShelfRenderer
                        ?.contents
                Timber.d("Trying musicPlaylistShelfRenderer: ${episodeContents?.size ?: 0}")
            }

            // Fallback to singleColumn
            if (episodeContents == null) {
                episodeContents =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.find { it.musicShelfRenderer != null }
                        ?.musicShelfRenderer
                        ?.contents
                Timber.d("Using singleColumn for episodes, found: ${episodeContents?.size ?: 0}")
            }

            Timber.d("Episode contents count: ${episodeContents?.size ?: 0}")

            // Get episodes from musicMultiRowListItemRenderer (used for podcasts)
            val multiRowItems = episodeContents?.mapNotNull { it.musicMultiRowListItemRenderer } ?: emptyList()
            Timber.d("multiRowItems count: ${multiRowItems.size}")

            multiRowItems.take(2).forEachIndexed { idx, renderer ->
                Timber.d("Episode[$idx] title: ${renderer.title?.runs?.firstOrNull()?.text}")
                Timber.d("Episode[$idx] subtitle: ${renderer.subtitle?.runs?.map { it.text }}")
                Timber.d("Episode[$idx] videoId: ${renderer.onTap?.watchEndpoint?.videoId}")
                Timber.d("Episode[$idx] thumbnail: ${renderer.thumbnail?.getThumbnailUrl()}")
            }

            val episodes =
                multiRowItems.mapNotNull { renderer ->
                    PodcastPage.fromMusicMultiRowListItemRenderer(renderer, podcastItem)
                }

            Timber.d("Parsed episodes: ${episodes.size}")

            PodcastPage(
                podcast = podcastItem,
                episodes = episodes,
                continuation =
                    response.contents
                        ?.twoColumnBrowseResultsRenderer
                        ?.secondaryContents
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicShelfRenderer
                        ?.continuations
                        ?.getContinuation()
                        ?: response.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.find { it.musicShelfRenderer != null }
                            ?.musicShelfRenderer
                            ?.continuations
                            ?.getContinuation(),
                isChannelSubscribed = isChannelSubscribed,
            )
        }

    suspend fun home(
        continuation: String? = null,
        params: String? = null,
    ): Result<HomePage> =
        runCatching {
            Timber.d("home() called with continuation=$continuation, params=$params")
            if (continuation != null) {
                return@runCatching homeContinuation(continuation).getOrThrow()
            }

            val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_home", params = params).body<BrowseResponse>()
            Timber.d("home() response received")
            val continuation =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.continuations
                    ?.getContinuation()
            val sectionListRender =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
            Timber.d("home() sectionListRender contents size: ${sectionListRender?.contents?.size ?: 0}")
            val carousels = sectionListRender?.contents?.mapNotNull { it.musicCarouselShelfRenderer } ?: emptyList()
            Timber.d("home() carousels count: ${carousels.size}")
            val sections =
                carousels
                    .mapNotNull {
                        HomePage.Section.fromMusicCarouselShelfRenderer(it)
                    }.toMutableList()
            Timber.d("home() sections parsed: ${sections.size}")
            val chips =
                sectionListRender
                    ?.header
                    ?.chipCloudRenderer
                    ?.chips
                    ?.mapNotNull { HomePage.Chip.fromChipCloudChipRenderer(it) }
            Timber.d("home() chips: ${chips?.size ?: 0}")
            HomePage(chips, sections, continuation)
        }

    private suspend fun homeContinuation(continuation: String): Result<HomePage> =
        runCatching {
            val response =
                innerTube.browse(WEB_REMIX, continuation = continuation).body<BrowseResponse>()
            val continuation =
                response.continuationContents
                    ?.sectionListContinuation
                    ?.continuations
                    ?.getContinuation()
            HomePage(
                null,
                response.continuationContents
                    ?.sectionListContinuation
                    ?.contents
                    ?.mapNotNull { it.musicCarouselShelfRenderer }
                    ?.mapNotNull {
                        HomePage.Section.fromMusicCarouselShelfRenderer(it)
                    }.orEmpty(),
                continuation,
            )
        }

    suspend fun explore(): Result<ExplorePage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_explore").body<BrowseResponse>()
            ExplorePage(
                newReleaseAlbums =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.find {
                            it.musicCarouselShelfRenderer
                                ?.header
                                ?.musicCarouselShelfBasicHeaderRenderer
                                ?.moreContentButton
                                ?.buttonRenderer
                                ?.navigationEndpoint
                                ?.browseEndpoint
                                ?.browseId ==
                                "FEmusic_new_releases_albums"
                        }?.musicCarouselShelfRenderer
                        ?.contents
                        ?.mapNotNull { it.musicTwoRowItemRenderer }
                        ?.mapNotNull(NewReleaseAlbumPage::fromMusicTwoRowItemRenderer)
                        .orEmpty(),
                moodAndGenres =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.find {
                            it.musicCarouselShelfRenderer
                                ?.header
                                ?.musicCarouselShelfBasicHeaderRenderer
                                ?.moreContentButton
                                ?.buttonRenderer
                                ?.navigationEndpoint
                                ?.browseEndpoint
                                ?.browseId ==
                                "FEmusic_moods_and_genres"
                        }?.musicCarouselShelfRenderer
                        ?.contents
                        ?.mapNotNull { it.musicNavigationButtonRenderer }
                        ?.mapNotNull(MoodAndGenres.Companion::fromMusicNavigationButtonRenderer)
                        .orEmpty(),
            )
        }

    suspend fun newReleaseAlbums(): Result<List<AlbumItem>> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_new_releases_albums").body<BrowseResponse>()
            response.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.firstOrNull()
                ?.gridRenderer
                ?.items
                ?.mapNotNull { it.musicTwoRowItemRenderer }
                ?.mapNotNull(NewReleaseAlbumPage::fromMusicTwoRowItemRenderer)
                .orEmpty()
        }

    suspend fun moodAndGenres(): Result<List<MoodAndGenres>> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_moods_and_genres").body<BrowseResponse>()
            response.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents!!
                .mapNotNull(MoodAndGenres.Companion::fromSectionListRendererContent)
        }

    suspend fun browse(
        browseId: String,
        params: String?,
    ): Result<BrowseResult> =
        runCatching {
            // Use authentication for library endpoints
            val needsLogin = browseId.startsWith("FEmusic_library") || browseId == "VLSE" || browseId == "VLRDPN"
            val response = innerTube.browse(WEB_REMIX, browseId = browseId, params = params, setLogin = needsLogin).body<BrowseResponse>()
            val sectionContents =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
            BrowseResult(
                title =
                    response.header
                        ?.musicHeaderRenderer
                        ?.title
                        ?.runs
                        ?.firstOrNull()
                        ?.text,
                items =
                    sectionContents
                        ?.mapNotNull { content ->
                            when {
                                content.gridRenderer != null -> {
                                    BrowseResult.Item(
                                        title =
                                            content.gridRenderer.header
                                                ?.gridHeaderRenderer
                                                ?.title
                                                ?.runs
                                                ?.firstOrNull()
                                                ?.text,
                                        items =
                                            content.gridRenderer.items
                                                .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                                                .mapNotNull { renderer ->
                                                    // Try LibraryPage first (more lenient for library endpoints), fall back to RelatedPage
                                                    LibraryPage.fromMusicTwoRowItemRenderer(renderer)
                                                        ?: RelatedPage.fromMusicTwoRowItemRenderer(renderer)
                                                },
                                    )
                                }

                                content.musicCarouselShelfRenderer != null -> {
                                    val carouselItems = mutableListOf<YTItem>()
                                    for (carouselContent in content.musicCarouselShelfRenderer.contents) {
                                        val item = carouselContent.musicTwoRowItemRenderer?.let { renderer ->
                                            LibraryPage.fromMusicTwoRowItemRenderer(renderer)
                                                ?: RelatedPage.fromMusicTwoRowItemRenderer(renderer)
                                        } ?: carouselContent.musicMultiRowListItemRenderer?.let { renderer ->
                                            PodcastPage.fromMusicMultiRowListItemRenderer(renderer)
                                        } ?: carouselContent.musicResponsiveListItemRenderer?.let { renderer ->
                                            LibraryPage.fromMusicResponsiveListItemRenderer(renderer)
                                                ?: RelatedPage.fromMusicResponsiveListItemRenderer(renderer)
                                        }
                                        if (item != null) {
                                            carouselItems.add(item)
                                        }
                                    }
                                    BrowseResult.Item(
                                        title =
                                            content.musicCarouselShelfRenderer.header
                                                ?.musicCarouselShelfBasicHeaderRenderer
                                                ?.title
                                                ?.runs
                                                ?.firstOrNull()
                                                ?.text,
                                        items =
                                            content.musicCarouselShelfRenderer.contents.mapNotNull { content ->
                                                content.musicTwoRowItemRenderer?.let { renderer ->
                                                    LibraryPage.fromMusicTwoRowItemRenderer(renderer)
                                                        ?: RelatedPage.fromMusicTwoRowItemRenderer(renderer)
                                                } ?: content.musicMultiRowListItemRenderer?.let { renderer ->
                                                    PodcastPage.fromMusicMultiRowListItemRenderer(renderer)
                                                } ?: content.musicResponsiveListItemRenderer?.let { renderer ->
                                                    LibraryPage.fromMusicResponsiveListItemRenderer(renderer)
                                                        ?: RelatedPage.fromMusicResponsiveListItemRenderer(renderer)
                                                }
                                            },
                                    )
                                }

                                content.musicShelfRenderer != null -> {
                                    BrowseResult.Item(
                                        title =
                                            content.musicShelfRenderer.title
                                                ?.runs
                                                ?.firstOrNull()
                                                ?.text,
                                        items =
                                            content.musicShelfRenderer.contents
                                                ?.mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                                                ?.mapNotNull(LibraryPage.Companion::fromMusicResponsiveListItemRenderer)
                                                ?: emptyList(),
                                    )
                                }

                                content.musicPlaylistShelfRenderer != null -> {
                                    BrowseResult.Item(
                                        title = null, // MusicPlaylistShelfRenderer doesn't have a title
                                        items =
                                            content.musicPlaylistShelfRenderer.contents
                                                .getItems()
                                                .mapNotNull(LibraryPage.Companion::fromMusicResponsiveListItemRenderer),
                                    )
                                }

                                else -> {
                                    null
                                }
                            }
                        }.orEmpty(),
            )
        }

    suspend fun library(
        browseId: String,
        tabIndex: Int = 0,
    ): Result<LibraryPage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = browseId,
                        setLogin = true,
                    ).body<BrowseResponse>()

            val tab = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.getOrNull(tabIndex)
                ?: response.contents?.twoColumnBrowseResultsRenderer?.tabs?.getOrNull(tabIndex)
            val contents = tab
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.firstOrNull {
                    it.gridRenderer != null ||
                        it.musicShelfRenderer != null ||
                        it.musicPlaylistShelfRenderer != null ||
                        it.itemSectionRenderer?.contents.orEmpty().any { child ->
                            child.gridRenderer != null ||
                                child.musicShelfRenderer != null ||
                                child.musicPlaylistShelfRenderer != null
                        }
                }
            val nestedContents = contents?.itemSectionRenderer?.contents.orEmpty()
            val gridRenderer = contents?.gridRenderer
                ?: nestedContents.firstNotNullOfOrNull { it.gridRenderer }
            val playlistShelfRenderer = contents?.musicPlaylistShelfRenderer
                ?: nestedContents.firstNotNullOfOrNull { it.musicPlaylistShelfRenderer }
            val musicShelfRenderer = contents?.musicShelfRenderer
                ?: nestedContents.firstNotNullOfOrNull { it.musicShelfRenderer }

            when {
                gridRenderer != null -> {
                    val gridItems = gridRenderer.items
                    val parsedItems =
                        gridItems
                            .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                            .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) }
                    LibraryPage(
                        items = parsedItems,
                        continuation = gridRenderer.continuations?.getContinuation(),
                    )
                }

                playlistShelfRenderer != null -> {
                    LibraryPage(
                        items = playlistShelfRenderer.contents
                            .getItems()
                            .mapNotNull(LibraryPage.Companion::fromMusicResponsiveListItemRenderer),
                        continuation = playlistShelfRenderer.continuations?.getContinuation(),
                    )
                }

                musicShelfRenderer != null -> {
                    val listItemRenderers = musicShelfRenderer.contents.orEmpty()
                        .mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                    val parsedItems =
                        listItemRenderers.mapNotNull { renderer ->
                            LibraryPage.fromMusicResponsiveListItemRenderer(renderer)
                        }
                    LibraryPage(
                        items = parsedItems,
                        continuation = musicShelfRenderer.continuations?.getContinuation(),
                    )
                }

                else -> LibraryPage(items = emptyList(), continuation = null)
            }
        }

    suspend fun libraryContinuation(continuation: String) =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        continuation = continuation,
                        setLogin = true,
                    ).body<BrowseResponse>()

            val contents = response.continuationContents

            when {
                contents?.gridContinuation != null -> {
                    LibraryContinuationPage(
                        items =
                            contents.gridContinuation.items
                                .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                                .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) },
                        continuation = contents.gridContinuation.continuations?.getContinuation(),
                    )
                }

                else -> { // contents?.musicShelfContinuation != null
                    LibraryContinuationPage(
                        items =
                            contents
                                ?.musicShelfContinuation
                                ?.contents!!
                                .mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                                .mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) },
                        continuation = contents.musicShelfContinuation.continuations?.getContinuation(),
                    )
                }
            }
        }

    suspend fun libraryRecentActivity(): Result<LibraryPage> =
        runCatching {
            val continuation = LibraryFilter.FILTER_RECENT_ACTIVITY.value

            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        continuation = continuation,
                        setLogin = true,
                    ).body<BrowseResponse>()

            val gridItems =
                response.continuationContents
                    ?.sectionListContinuation
                    ?.contents
                    ?.firstOrNull()
                    ?.gridRenderer
                    ?.items

            if (gridItems == null) {
                return@runCatching LibraryPage(
                    items = emptyList(),
                    continuation = null,
                )
            }

            val items =
                gridItems
                    .mapNotNull {
                        it.musicTwoRowItemRenderer?.let { renderer ->
                            LibraryPage.fromMusicTwoRowItemRenderer(renderer)
                        }
                    }.toMutableList()

        /*
         * We need to fetch the artist page when accessing the library because it allows to have
         * a proper playEndpoint, which is needed to correctly report the playing indicator in
         * the home page.
         *
         * Despite this, we need to use the old thumbnail because it's the proper format for a
         * square picture, which is what we need.
         */
            items.forEachIndexed { index, item ->
                if (item is ArtistItem) {
                    artist(item.id).getOrNull()?.artist?.let { fetchedArtist ->
                        items[index] = fetchedArtist.copy(thumbnail = item.thumbnail)
                    }
                }
            }

            LibraryPage(
                items = items,
                continuation = null,
            )
        }

    suspend fun getChartsPage(continuation: String? = null): Result<ChartsPage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "FEmusic_charts",
                        params = "ggMGCgQIgAQ%3D",
                        continuation = continuation,
                    ).body<BrowseResponse>()

            val sections = mutableListOf<ChartsPage.ChartSection>()

            response.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.forEach { content ->

                    content.musicCarouselShelfRenderer?.let { renderer ->
                        val title =
                            renderer.header
                                ?.musicCarouselShelfBasicHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?: return@forEach

                        val items =
                            renderer.contents
                                .mapNotNull { item ->
                                    when {
                                        item.musicResponsiveListItemRenderer != null -> {
                                            convertToChartItem(item.musicResponsiveListItemRenderer)
                                        }

                                        item.musicTwoRowItemRenderer != null -> {
                                            convertMusicTwoRowItem(item.musicTwoRowItemRenderer)
                                        }

                                        else -> {
                                            null
                                        }
                                    }
                                }.filterNotNull()

                        if (items.isNotEmpty()) {
                            sections.add(
                                ChartsPage.ChartSection(
                                    title = title,
                                    items = items,
                                    chartType = determineChartType(title),
                                ),
                            )
                        }
                    }

                    content.gridRenderer?.let { renderer ->
                        val title =
                            renderer.header
                                ?.gridHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?: return@let

                        val items =
                            renderer.items
                                .mapNotNull { item ->
                                    item.musicTwoRowItemRenderer?.let { renderer ->
                                        convertMusicTwoRowItem(renderer)
                                    }
                                }.filterNotNull()

                        if (items.isNotEmpty()) {
                            sections.add(
                                ChartsPage.ChartSection(
                                    title = title,
                                    items = items,
                                    chartType = ChartsPage.ChartType.NEW_RELEASES,
                                ),
                            )
                        }
                    }
                }

            ChartsPage(
                sections = sections,
                continuation =
                    response.continuationContents
                        ?.sectionListContinuation
                        ?.continuations
                        ?.getContinuation(),
            )
        }

    private fun determineChartType(title: String): ChartsPage.ChartType =
        when {
            title.contains("Trending", ignoreCase = true) -> ChartsPage.ChartType.TRENDING
            title.contains("Top", ignoreCase = true) -> ChartsPage.ChartType.TOP
            else -> ChartsPage.ChartType.GENRE
        }

    private fun convertToChartItem(renderer: MusicResponsiveListItemRenderer): YTItem? {
        return try {
            when {
                renderer.flexColumns.size >= 3 && renderer.videoId != null -> {
                    val firstColumn =
                        renderer.flexColumns
                            .getOrNull(0)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text ?: return null

                    val secondColumn =
                        renderer.flexColumns
                            .getOrNull(1)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text ?: return null

                    val titleRun = firstColumn.runs?.firstOrNull() ?: return null
                    val title = titleRun.text.takeIf { it.isNotBlank() } ?: return null

                    val artists = PageHelper.extractArtists(secondColumn.runs)
                    
                    if (artists.isEmpty()) {
                        Timber.w("convertMusicResponsiveListItemRenderer: Song '$title' (id=${renderer.videoId}) has EMPTY artists list")
                    }

                    val thirdColumn =
                        renderer.flexColumns
                            .getOrNull(2)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text

                    SongItem(
                        id = renderer.videoId!!,
                        title = title,
                        artists = artists,
                        thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: return null,
                        musicVideoType = renderer.musicVideoType,
                        explicit =
                            renderer.badges?.any {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } == true,
                        chartPosition =
                            thirdColumn
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?.toIntOrNull(),
                        chartChange = thirdColumn?.runs?.getOrNull(1)?.text,
                    )
                }

                else -> {
                    null
                }
            }
        } catch (e: Exception) {
            println("Error converting chart item: ${e.message}\n${Json.encodeToString(renderer)}")
            null
        }
    }

    private fun convertMusicTwoRowItem(renderer: MusicTwoRowItemRenderer): YTItem? {
        return try {
            when {
                renderer.isSong -> {
                    val subtitle = renderer.subtitle?.runs ?: return null
                    val title = renderer.title.runs?.firstOrNull()?.text ?: return null
                    val artists = PageHelper.extractArtists(subtitle)
                    val videoId = renderer.navigationEndpoint.watchEndpoint?.videoId ?: return null
                    
                    if (artists.isEmpty()) {
                        Timber.w("convertMusicTwoRowItem: Song '$title' (id=$videoId) has EMPTY artists list from ${subtitle.size} subtitle runs")
                    }
                    
                    SongItem(
                        id = videoId,
                        title = title,
                        artists = artists,
                        thumbnail = renderer.thumbnailRenderer.getThumbnailUrl() ?: return null,
                        musicVideoType = renderer.musicVideoType,
                        explicit =
                            renderer.subtitleBadges?.any {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } == true,
                    )
                }

                renderer.isAlbum -> {
                    AlbumItem(
                        browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                        playlistId =
                            renderer.thumbnailOverlay
                                ?.musicItemThumbnailOverlayRenderer
                                ?.content
                                ?.musicPlayButtonRenderer
                                ?.playNavigationEndpoint
                                ?.watchPlaylistEndpoint
                                ?.playlistId ?: return null,
                        title =
                            renderer.title.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists =
                            renderer.subtitle?.runs?.oddElements()?.drop(1)?.mapNotNull {
                                it.navigationEndpoint?.browseEndpoint?.browseId?.let { id ->
                                    Artist(name = it.text, id = id)
                                }
                            },
                        year =
                            renderer.subtitle
                                ?.runs
                                ?.lastOrNull()
                                ?.text
                                ?.toIntOrNull(),
                        thumbnail = renderer.thumbnailRenderer.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.subtitleBadges?.any {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } == true,
                    )
                }

                else -> {
                    null
                }
            }
        } catch (e: Exception) {
            println("Error converting two row item: ${e.message}\n${Json.encodeToString(renderer)}")
            null
        }
    }

    suspend fun musicHistory() =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "FEmusic_history",
                        setLogin = true,
                    ).body<BrowseResponse>()

            HistoryPage(
                sections =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.mapNotNull {
                            it.musicShelfRenderer?.let { musicShelfRenderer ->
                                HistoryPage.fromMusicShelfRenderer(musicShelfRenderer)
                            }
                        },
            )
        }

    /**
     * Fetch podcast discovery/recommendations page.
     * Returns sections like "Popular shows", "Popular episodes", category sections.
     */
    suspend fun podcastDiscover(): Result<HomePage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "FEmusic_non_music_audio",
                        setLogin = true,
                    ).body<BrowseResponse>()

            val sectionListRenderer =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
            val carousels = sectionListRenderer?.contents?.mapNotNull { it.musicCarouselShelfRenderer } ?: emptyList()
            val sections =
                carousels.mapNotNull {
                    HomePage.Section.fromMusicCarouselShelfRenderer(it)
                }
            val chips =
                sectionListRenderer?.header?.chipCloudRenderer?.chips?.mapNotNull {
                    HomePage.Chip.fromChipCloudChipRenderer(it)
                }
            val continuation = sectionListRenderer?.continuations?.getContinuation()

            HomePage(chips, sections, continuation)
        }

    suspend fun likeVideo(
        videoId: String,
        like: Boolean,
    ) = runCatching {
        if (like) {
            innerTube.likeVideo(WEB_REMIX, videoId)
        } else {
            innerTube.unlikeVideo(WEB_REMIX, videoId)
        }
    }

    suspend fun likePlaylist(
        playlistId: String,
        like: Boolean,
    ) = runCatching {
        if (like) {
            innerTube.likePlaylist(WEB_REMIX, playlistId)
        } else {
            innerTube.unlikePlaylist(WEB_REMIX, playlistId)
        }
    }

    suspend fun subscribeChannel(
        channelId: String,
        subscribe: Boolean,
        params: String? = null,
    ) = runCatching {
        // Default params from YouTube Music API - required for subscription to work
        val subscribeParams = params ?: "EgIIAhgA"
        if (subscribe) {
            innerTube.subscribeChannel(WEB_REMIX, channelId, subscribeParams)
        } else {
            innerTube.unsubscribeChannel(WEB_REMIX, channelId, subscribeParams)
        }
    }

    /**
     * Save a podcast show to library.
     * Uses likePlaylist API. Podcast IDs are "MPSP<playlistId>".
     */
    suspend fun savePodcast(
        podcastId: String,
        save: Boolean,
    ) = runCatching {
        val playlistId = podcastId.removePrefix("MPSP")
        Timber.d("[PODCAST_API] savePodcast: podcastId=$podcastId, playlistId=$playlistId, save=$save")
        if (save) {
            innerTube.likePlaylist(WEB_REMIX, playlistId)
        } else {
            innerTube.unlikePlaylist(WEB_REMIX, playlistId)
        }
    }

    /**
     * Add episode to "Episodes for Later" playlist (SE).
     */
    suspend fun addEpisodeToSavedEpisodes(videoId: String) =
        runCatching {
            innerTube.addToPlaylist(WEB_REMIX, "SE", videoId)
        }

    /**
     * Remove episode from "Episodes for Later" playlist (SE).
     * Note: setVideoId is required for removal and must be obtained from the playlist response.
     */
    suspend fun removeEpisodeFromSavedEpisodes(
        videoId: String,
        setVideoId: String,
    ) = runCatching {
        innerTube.removeFromPlaylist(WEB_REMIX, "SE", videoId, setVideoId)
    }

    suspend fun libraryPodcastChannels(): Result<LibraryPage> {
        Timber.d("[PODCAST_API] libraryPodcastChannels: calling browse with FEmusic_library_non_music_audio_channels_list")
        return runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "FEmusic_library_non_music_audio_channels_list",
                        setLogin = true,
                    ).body<BrowseResponse>()

            val contentList =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents ?: emptyList()

            val items =
                contentList.flatMap { content ->
                    when {
                        content.gridRenderer != null -> {
                            content.gridRenderer.items
                                .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                                .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) }
                        }

                        content.musicShelfRenderer != null -> {
                            content.musicShelfRenderer.contents
                                ?.mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                                ?.mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) }
                                ?: emptyList()
                        }

                        content.musicCarouselShelfRenderer != null -> {
                            content.musicCarouselShelfRenderer.contents.mapNotNull { content ->
                                content.musicTwoRowItemRenderer?.let { renderer ->
                                    LibraryPage.fromMusicTwoRowItemRenderer(renderer)
                                } ?: content.musicMultiRowListItemRenderer?.let { renderer ->
                                    PodcastPage.fromMusicMultiRowListItemRenderer(renderer)
                                } ?: content.musicResponsiveListItemRenderer?.let { renderer ->
                                    LibraryPage.fromMusicResponsiveListItemRenderer(renderer)
                                }
                            }
                        }

                        else -> {
                            emptyList()
                        }
                    }
                }

            LibraryPage(
                items = items,
                continuation = null,
            )
        }.also { result ->
            result.onFailure { e -> Timber.e(e, "[PODCAST_API] libraryPodcastChannels FAILED") }
            result.onSuccess { Timber.d("[PODCAST_API] libraryPodcastChannels SUCCESS: ${it.items.size} items") }
        }
    }

    suspend fun libraryPodcastEpisodes(): Result<LibraryPage> {
        Timber.d("[PODCAST_API] libraryPodcastEpisodes: calling browse with FEmusic_library_non_music_audio_list")
        return runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "FEmusic_library_non_music_audio_list",
                        setLogin = true,
                    ).body<BrowseResponse>()

            val contents =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()

            val items =
                when {
                    contents?.gridRenderer != null -> {
                        contents.gridRenderer.items
                            .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                            .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) }
                    }

                    contents?.musicShelfRenderer != null -> {
                        contents.musicShelfRenderer.contents
                            ?.mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                            ?.mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) }
                            ?: emptyList()
                    }

                    else -> {
                        emptyList()
                    }
                }

            LibraryPage(
                items = items,
                continuation = null,
            )
        }.also { result ->
            result.onFailure { e -> Timber.e(e, "[PODCAST_API] libraryPodcastEpisodes FAILED") }
            result.onSuccess { Timber.d("[PODCAST_API] libraryPodcastEpisodes SUCCESS: ${it.items.size} items") }
        }
    }

    /**
     * Fetch saved podcast shows from library.
     * Uses FEmusic_library_non_music_audio_list and filters to only PodcastItem.
     */
    suspend fun savedPodcastShows(): Result<List<PodcastItem>> =
        runCatching {
            val libraryPage = libraryPodcastEpisodes().getOrThrow()
            libraryPage.items.filterIsInstance<PodcastItem>()
        }

    /**
     * Fetch "New Episodes" auto-playlist (VLRDPN).
     * Returns new episodes from saved/subscribed podcasts.
     */
    suspend fun newEpisodes(): Result<List<SongItem>> {
        Timber.d("[PODCAST_API] newEpisodes: calling browse with VLRDPN")
        return runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "VLRDPN",
                        setLogin = true,
                    ).body<BrowseResponse>()

            val twoColumn = response.contents?.twoColumnBrowseResultsRenderer

            // RDPN may have content in either tabs (first tab) or secondaryContents
            val sections = mutableListOf<SectionListRenderer.Content>()

            // Check tabs path
            twoColumn?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.let { sections.addAll(it) }

            // Check secondaryContents path (original working path for RDPN)
            // Use TwoColumnBrowseResultsRenderer's inner SectionListRenderer types
            twoColumn?.secondaryContents?.sectionListRenderer?.contents?.let { secContents ->
                sections.addAll(secContents.map { secContent ->
                    SectionListRenderer.Content(
                        musicCarouselShelfRenderer = null,
                        musicShelfRenderer = secContent.musicShelfRenderer,
                        musicCardShelfRenderer = null,
                        musicPlaylistShelfRenderer = secContent.musicPlaylistShelfRenderer,
                        musicDescriptionShelfRenderer = null,
                        musicResponsiveHeaderRenderer = null,
                        musicEditablePlaylistDetailHeaderRenderer = null,
                        gridRenderer = null,
                        itemSectionRenderer = null,
                    )
                })
            }

            Timber.d("[PODCAST_API] newEpisodes: ${sections.size} section(s) found (${twoColumn?.tabs?.size ?: 0} tab(s), secondary has ${twoColumn?.secondaryContents?.sectionListRenderer?.contents?.size ?: 0})")

            // Log all section types
            sections.forEachIndexed { idx, section ->
                Timber.d("[PODCAST_API] section[$idx]: hasCarousel=${section.musicCarouselShelfRenderer != null} hasShelf=${section.musicShelfRenderer != null} hasPlaylistShelf=${section.musicPlaylistShelfRenderer != null} hasCardShelf=${section.musicCardShelfRenderer != null} hasGrid=${section.gridRenderer != null} hasItemSection=${section.itemSectionRenderer != null}")
                section.musicCarouselShelfRenderer?.let { carousel ->
                    val carouselTitle = carousel.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.joinToString("") { it.text }
                    Timber.d("[PODCAST_API]   carousel title=$carouselTitle, items=${carousel.contents?.size}")
                }
                section.musicShelfRenderer?.let { shelf ->
                    Timber.d("[PODCAST_API]   shelf title=${shelf.title?.runs?.joinToString("") { it.text }}, items=${shelf.contents?.size}")
                    // Log first item's subtitle fields
                    shelf.contents?.firstOrNull()?.musicMultiRowListItemRenderer?.let { r ->
                        val subtitleText = r.subtitle?.runs?.joinToString("") { it.text }
                        val secondSubtitleText = r.secondSubtitle?.runs?.joinToString("") { it.text }
                        val secondarySubtitleText = r.secondarySubtitle?.runs?.joinToString("") { it.text }
                        Timber.d("[PODCAST_API]   first item: subtitle='$subtitleText' secondSubtitle='$secondSubtitleText' secondarySubtitle='$secondarySubtitleText'")
                        val subRuns = r.subtitle?.runs
                        Timber.d("[PODCAST_API]   subtitle runs: ${subRuns?.map { "text='${it.text}' nav=${it.navigationEndpoint?.browseEndpoint?.browseId}" }}")
                        val secRuns = r.secondSubtitle?.runs
                        Timber.d("[PODCAST_API]   secondSubtitle runs: ${secRuns?.map { "text='${it.text}' nav=${it.navigationEndpoint?.browseEndpoint?.browseId}" }}")
                        val menuItems = r.menu?.menuRenderer?.items?.mapIndexed { idx, item ->
                            val navText = item.menuNavigationItemRenderer?.text?.runs?.joinToString("") { it.text }
                            val navId = item.menuNavigationItemRenderer?.navigationEndpoint?.browseEndpoint?.browseId
                            val navPageType = item.menuNavigationItemRenderer?.navigationEndpoint?.browseEndpoint
                                ?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
                            val svcText = item.menuServiceItemRenderer?.text?.runs?.joinToString("") { it.text }
                            val toggleIcon = item.toggleMenuServiceItemRenderer?.defaultIcon?.iconType
                            "[$idx](navText='$navText' navId=$navId pageType=$navPageType svcText='$svcText' toggleIcon=$toggleIcon)"
                        }
                        Timber.d("[PODCAST_API]   first item detailed menu: $menuItems")
                    }
                }
                section.musicPlaylistShelfRenderer?.let { ps ->
                    Timber.d("[PODCAST_API]   playlistShelf playlistId=${ps.playlistId}, items=${ps.contents?.size}")
                }
            }

            // Check singleColumnBrowseResultsRenderer too
            val singleColumn = response.contents?.singleColumnBrowseResultsRenderer
            Timber.d("[PODCAST_API] singleColumn is null=${singleColumn == null}")
            if (singleColumn?.tabs != null) {
                singleColumn.tabs.forEachIndexed { idx, tab ->
                    val tabTitle = tab.tabRenderer.title
                    val tabContent = tab.tabRenderer.content
                    val tabSectionList = tabContent?.sectionListRenderer
                    val tabSectionsSize = tabSectionList?.contents?.size ?: 0
                    Timber.d("[PODCAST_API] singleColumn tab[$idx]: title=$tabTitle, sections=$tabSectionsSize")
                    tabSectionList?.contents?.forEachIndexed { sIdx, section ->
                        val shelf = section.musicShelfRenderer
                        val carousel = section.musicCarouselShelfRenderer
                        if (shelf != null) {
                            Timber.d("[PODCAST_API]   singleCol section[$sIdx] shelf title=${shelf.title?.runs?.joinToString("") { it.text }}, items=${shelf.contents?.size}")
                        }
                        if (carousel != null) {
                            val carTitle = carousel.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.joinToString("") { it.text }
                            Timber.d("[PODCAST_API]   singleCol section[$sIdx] carousel title=$carTitle, items=${carousel.contents?.size}")
                        }
                    }
                }
            }

            // Extract episodes from all section types
            val episodesList = mutableListOf<SongItem>()

            // Helper to process musicShelf
            fun processShelf(shelf: MusicShelfRenderer, sectionPodcastName: String? = null) {
                val podcastName = sectionPodcastName ?: shelf.title?.runs?.joinToString("") { it.text }
                shelf.contents
                    ?.mapNotNull { it.musicMultiRowListItemRenderer }
                    ?.forEach { renderer ->
                        if (renderer.onTap?.watchEndpoint?.videoId == null) return@forEach
                        val title = renderer.title?.runs?.firstOrNull()?.text ?: return@forEach

                        val subtitleGroups = renderer.subtitle?.runs?.splitBySeparator()
                        val duration = subtitleGroups
                            ?.lastOrNull { group ->
                                group.firstOrNull()?.text?.parseTime() != null
                            }
                            ?.firstOrNull()
                            ?.text
                            ?.parseTime()

                        // Strategy 1: secondSubtitle / secondarySubtitle often has the podcast name
                        var artistName: String? = renderer.secondSubtitle?.runs?.joinToString("") { it.text }
                        if (artistName.isNullOrBlank()) {
                            artistName = renderer.secondarySubtitle?.runs?.joinToString("") { it.text }
                        }

                        // Strategy 2: Extract browseId from menu items with browse endpoints
                        var browseId: String? = null
                        val actionLabels = setOf("Save to playlist", "Share", "Remove from library",
                            "Add to library", "Don't recommend this episode", "Start radio",
                            "Go to podcast", "Go to artist", "Go to album")
                        renderer.menu?.menuRenderer?.items?.forEach { item ->
                            val text = item.menuNavigationItemRenderer?.text?.runs?.joinToString("") { it.text }
                            val navEp = item.menuNavigationItemRenderer?.navigationEndpoint?.browseEndpoint
                            if (navEp != null) {
                                if (browseId == null) browseId = navEp.browseId
                                if (text != null && text !in actionLabels && artistName == null) {
                                    artistName = text
                                }
                            }
                        }

                        // Strategy 3: Fallback to section header's podcast name
                        if (artistName.isNullOrBlank()) artistName = podcastName

                        val artists = if (!artistName.isNullOrBlank()) {
                            listOf(Artist(name = artistName, id = browseId))
                        } else emptyList()

                        episodesList.add(
                            SongItem(
                                id = renderer.onTap.watchEndpoint.videoId,
                                title = title,
                                artists = artists,
                                album = null,
                                duration = duration,
                                thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: "",
                                isEpisode = true,
                            )
                        )
                    }
            }

            // Process both sections and carousels
            sections.forEach { section ->
                // Process musicShelfRenderer
                section.musicShelfRenderer?.let { shelf ->
                    processShelf(shelf)
                }
                // Process musicCarouselShelfRenderer - each carousel is a podcast group
                section.musicCarouselShelfRenderer?.let { carousel ->
                    val carouselTitle = carousel.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.joinToString("") { it.text }
                    carousel.contents?.forEach { carouselContent ->
                        carouselContent.musicMultiRowListItemRenderer?.let { renderer ->
                            if (renderer.onTap?.watchEndpoint?.videoId == null) return@let
                            val title = renderer.title?.runs?.firstOrNull()?.text ?: return@let

                            val subtitleGroups = renderer.subtitle?.runs?.splitBySeparator()
                            val duration = subtitleGroups
                                ?.lastOrNull { group ->
                                    group.firstOrNull()?.text?.parseTime() != null
                                }
                                ?.firstOrNull()
                                ?.text
                                ?.parseTime()

                            // Try secondSubtitle first, then carousel title
                            var artistName: String? = renderer.secondSubtitle?.runs?.joinToString("") { it.text }
                            if (artistName.isNullOrBlank()) {
                                artistName = renderer.secondarySubtitle?.runs?.joinToString("") { it.text }
                            }
                            if (artistName.isNullOrBlank()) artistName = carouselTitle

                            val artists = if (!artistName.isNullOrBlank()) {
                                listOf(Artist(name = artistName, id = null))
                            } else emptyList()

                            episodesList.add(
                                SongItem(
                                    id = renderer.onTap.watchEndpoint.videoId,
                                    title = title,
                                    artists = artists,
                                    album = null,
                                    duration = duration,
                                    thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: "",
                                    isEpisode = true,
                                )
                            )
                        }
                    }
                }
            }

            // Enrich items that have no artist name by calling getMediaInfo (next endpoint)
            val itemsToEnrich = episodesList.filter { it.artists.isEmpty() }
            if (itemsToEnrich.isNotEmpty()) {
                Timber.d("[PODCAST_API] Enriching ${itemsToEnrich.size} items via getMediaInfo")
                coroutineScope {
                    itemsToEnrich
                        .map { episode ->
                            async {
                                val mediaInfo = innerTube.getMediaInfo(episode.id).getOrNull()
                                if (mediaInfo?.author != null) {
                                    episode.copy(
                                        artists = listOf(Artist(name = mediaInfo.author, id = mediaInfo.authorId)),
                                    )
                                } else episode
                            }
                        }
                        .awaitAll()
                        .forEach { enriched ->
                            val idx = episodesList.indexOfFirst { it.id == enriched.id }
                            if (idx >= 0) episodesList[idx] = enriched
                        }
                }
            }

            Timber.d("[PODCAST_API] newEpisodes SUCCESS: ${episodesList.size} items")
            episodesList
        }.also { result ->
            result.onFailure { e -> Timber.e(e, "[PODCAST_API] newEpisodes FAILED") }
        }
    }

    /**
     * Fetch the RDPN "New Episodes" playlist info (title + thumbnail).
     * Uses the same VLRDPN browse call as [newEpisodes] but parses the header instead.
     * Falls back to the first episode thumbnail if no header thumbnail is found.
     */
    suspend fun newEpisodesPlaylistInfo(): Result<PlaylistItem> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "VLRDPN",
                        setLogin = true,
                    ).body<BrowseResponse>()

            // Try all known header renderers in priority order
            val thumbnail: String? =
                response.header
                    ?.musicImmersiveHeaderRenderer
                    ?.thumbnail
                    ?.musicThumbnailRenderer
                    ?.getThumbnailUrl()
                    ?: response.header
                        ?.musicVisualHeaderRenderer
                        ?.thumbnail
                        ?.musicThumbnailRenderer
                        ?.getThumbnailUrl()
                    ?: response.header
                        ?.musicDetailHeaderRenderer
                        ?.thumbnail
                        ?.croppedSquareThumbnailRenderer
                        ?.thumbnail
                        ?.thumbnails
                        ?.lastOrNull()
                        ?.url
                    // Fall back: thumbnail of the first episode in the list
                    ?: response.contents
                        ?.twoColumnBrowseResultsRenderer
                        ?.secondaryContents
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicShelfRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicMultiRowListItemRenderer
                        ?.thumbnail
                        ?.musicThumbnailRenderer
                        ?.getThumbnailUrl()

            val title =
                response.header
                    ?.musicImmersiveHeaderRenderer
                    ?.title
                    ?.runs
                    ?.joinToString("") { it.text }
                    ?: response.header
                        ?.musicVisualHeaderRenderer
                        ?.title
                        ?.runs
                        ?.joinToString("") { it.text }
                    ?: "New Episodes"

            PlaylistItem(
                id = "RDPN",
                title = title,
                author = null,
                songCountText = null,
                thumbnail = thumbnail,
                playEndpoint = null,
                shuffleEndpoint = null,
                radioEndpoint = null,
            )
        }

    /**
     * Fetch "Episodes for Later" playlist (VLSE).
     * Returns manually saved episodes.
     */
    suspend fun episodesForLater(): Result<List<SongItem>> =
        runCatching {
            Timber.d("[PODCAST_API] episodesForLater: calling browse with VLSE")
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "VLSE",
                        setLogin = true,
                    ).body<BrowseResponse>()

            // VLSE uses musicPlaylistShelfRenderer, not musicShelfRenderer
            val contents =
                response.contents
                    ?.twoColumnBrowseResultsRenderer
                    ?.secondaryContents
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()

            val shelfContents =
                contents?.musicPlaylistShelfRenderer?.contents
                    ?: contents?.musicShelfRenderer?.contents

            // Parse musicResponsiveListItemRenderer (standard playlist format)
            shelfContents
                ?.mapNotNull { it.musicResponsiveListItemRenderer }
                ?.mapNotNull { renderer ->
                    val videoId = renderer.videoId ?: return@mapNotNull null
                    val setVideoId = renderer.playlistSetVideoId
                    val title =
                        renderer.flexColumns
                            .firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.firstOrNull()
                            ?.text
                            ?: return@mapNotNull null

                    // Subtitle is typically "Channel • Date" or "Channel • Date • Duration"
                    val subtitleGroups = renderer.flexColumns
                        .getOrNull(1)
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text
                        ?.runs
                        ?.splitBySeparator()

                    // Channel is the first group with a navigationEndpoint, or just the first group
                    val artistRun = subtitleGroups
                        ?.firstOrNull { group ->
                            group.firstOrNull()?.navigationEndpoint?.browseEndpoint != null
                        }
                        ?.firstOrNull()
                        ?: subtitleGroups?.firstOrNull()?.firstOrNull()

                    val duration = subtitleGroups
                        ?.drop(1)
                        ?.firstOrNull { group ->
                            group.firstOrNull()?.text?.parseTime() != null
                        }
                        ?.firstOrNull()
                        ?.text
                        ?.parseTime()
                        // Fallback to fixedColumns for duration
                        ?: renderer.fixedColumns?.firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer?.text
                            ?.runs?.firstOrNull()
                            ?.text?.parseTime()

                    SongItem(
                        id = videoId,
                        title = title,
                        artists =
                            artistRun?.let { listOf(Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId)) }
                                ?: emptyList(),
                        album = null,
                        duration = duration,
                        thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: "",
                        setVideoId = setVideoId,
                        isEpisode = true,
                    )
                } ?: emptyList()
        }

    /**
     * Fetch "Continue Listening" / Resume Playback.
     * Returns partially played episodes for resumption.
     */
    suspend fun continueListening(): Result<List<SongItem>> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "FEmusic_listening_review",
                        setLogin = true,
                    ).body<BrowseResponse>()

            response.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.flatMap { section ->
                    section.musicShelfRenderer?.contents?.mapNotNull { content ->
                        content.musicResponsiveListItemRenderer?.let { renderer ->
                            val videoId = renderer.videoId ?: return@mapNotNull null
                            val title =
                                renderer.flexColumns
                                    .firstOrNull()
                                    ?.musicResponsiveListItemFlexColumnRenderer
                                    ?.text
                                    ?.runs
                                    ?.firstOrNull()
                                    ?.text
                                    ?: return@mapNotNull null
                            val artistRun =
                                renderer.flexColumns
                                    .getOrNull(1)
                                    ?.musicResponsiveListItemFlexColumnRenderer
                                    ?.text
                                    ?.runs
                                    ?.firstOrNull()
                            SongItem(
                                id = videoId,
                                title = title,
                                artists =
                                    artistRun?.let { listOf(Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId)) }
                                        ?: emptyList(),
                                album = null,
                                duration = null,
                                thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: "",
                                isEpisode = true,
                            )
                        }
                    } ?: emptyList()
                } ?: emptyList()
        }

    suspend fun getChannelId(browseId: String): String {
        artist(browseId).onSuccess {
            return it.artist.channelId ?: ""
        }
        return ""
    }

    suspend fun addToPlaylist(
        playlistId: String,
        videoId: String,
    ) = runCatching {
        innerTube.addToPlaylist(WEB_REMIX, playlistId, videoId)
    }

    suspend fun addPlaylistToPlaylist(
        playlistId: String,
        addPlaylistId: String,
    ) = runCatching {
        innerTube.addPlaylistToPlaylist(WEB_REMIX, playlistId, addPlaylistId)
    }

    suspend fun removeFromPlaylist(
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ) = runCatching {
        innerTube.removeFromPlaylist(WEB_REMIX, playlistId, videoId, setVideoId)
    }

    suspend fun moveSongPlaylist(
        playlistId: String,
        setVideoId: String,
        successorSetVideoId: String?,
    ) = runCatching {
        innerTube.moveSongPlaylist(WEB_REMIX, playlistId, setVideoId, successorSetVideoId)
    }

    fun createPlaylist(title: String) =
        runBlocking {
            innerTube.createPlaylist(WEB_REMIX, title).body<CreatePlaylistResponse>().playlistId
        }

    suspend fun renamePlaylist(
        playlistId: String,
        name: String,
    ) = runCatching {
        innerTube.renamePlaylist(WEB_REMIX, playlistId, name)
    }

    suspend fun uploadCustomThumbnailLink(
        playlistId: String,
        image: ByteArray,
    ) = runCatching {
        val uploadUrl = innerTube.getUploadCustomThumbnailLink(WEB_REMIX, image.size).headers["x-guploader-uploadid"]
        val blobReq =
            innerTube.uploadCustomThumbnail(
                WEB_REMIX,
                uploadUrl!!,
                image,
            )
        val blobId = Json.decodeFromString<ImageUploadResponse>(blobReq.bodyAsText()).encryptedBlobId
        innerTube
            .setThumbnailPlaylist(
                WEB_REMIX,
                playlistId,
                blobId,
            ).body<EditPlaylistResponse>()
            .newHeader
            ?.musicEditablePlaylistDetailHeaderRenderer
            ?.header
            ?.musicResponsiveHeaderRenderer
            ?.thumbnail
            ?.musicThumbnailRenderer
            ?.getThumbnailUrl()
    }

    suspend fun removeThumbnailPlaylist(playlistId: String) =
        runCatching {
            innerTube
                .removeThumbnailPlaylist(
                    WEB_REMIX,
                    playlistId,
                ).body<EditPlaylistResponse>()
                .newHeader
                ?.musicEditablePlaylistDetailHeaderRenderer
                ?.header
                ?.musicResponsiveHeaderRenderer
                ?.thumbnail
                ?.musicThumbnailRenderer
                ?.getThumbnailUrl()
        }

    suspend fun deletePlaylist(playlistId: String) =
        runCatching {
            innerTube.deletePlaylist(WEB_REMIX, playlistId)
        }

    suspend fun player(
        videoId: String,
        playlistId: String? = null,
        client: YouTubeClient,
        signatureTimestamp: Int? = null,
        poToken: String? = null,
    ): Result<PlayerResponse> =
        runCatching {
            innerTube.player(client, videoId, playlistId, signatureTimestamp, poToken).body<PlayerResponse>()
        }

    suspend fun registerPlayback(
        playlistId: String? = null,
        playbackTracking: String,
    ) = runCatching {
        val cpn =
            (1..16)
                .map {
                    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"[
                        Random.Default.nextInt(
                            0,
                            64,
                        ),
                    ]
                }.joinToString("")

        innerTube.registerPlayback(
            url = playbackTracking,
            playlistId = playlistId,
            cpn = cpn,
        )
    }

    suspend fun next(
        endpoint: WatchEndpoint,
        continuation: String? = null,
    ): Result<NextResult> =
        runCatching {
            val response =
                innerTube
                    .next(
                        WEB_REMIX,
                        endpoint.videoId,
                        endpoint.playlistId,
                        endpoint.playlistSetVideoId,
                        endpoint.index,
                        endpoint.params,
                        continuation,
                    ).body<NextResponse>()
            val playlistPanelRenderer =
                response.continuationContents?.playlistPanelContinuation
                    ?: response.contents.singleColumnMusicWatchNextResultsRenderer
                        ?.tabbedRenderer
                        ?.watchNextTabbedResultsRenderer
                        ?.tabs
                        ?.get(0)
                        ?.tabRenderer
                        ?.content
                        ?.musicQueueRenderer
                        ?.content
                        ?.playlistPanelRenderer!!
            val title =
                response.contents.singleColumnMusicWatchNextResultsRenderer
                    ?.tabbedRenderer
                    ?.watchNextTabbedResultsRenderer
                    ?.tabs
                    ?.get(0)
                    ?.tabRenderer
                    ?.content
                    ?.musicQueueRenderer
                    ?.header
                    ?.musicQueueHeaderRenderer
                    ?.subtitle
                    ?.runs
                    ?.firstOrNull()
                    ?.text
            val items =
                playlistPanelRenderer.contents.mapNotNull { content ->
                    content.playlistPanelVideoRenderer
                        ?.let(NextPage::fromPlaylistPanelVideoRenderer)
                        ?.let { it to content.playlistPanelVideoRenderer.selected }
                }
            val songs = items.map { it.first }
            val currentIndex = items.indexOfFirst { it.second }.takeIf { it != -1 }

            // load automix items
            playlistPanelRenderer.contents
                .lastOrNull()
                ?.automixPreviewVideoRenderer
                ?.content
                ?.automixPlaylistVideoRenderer
                ?.navigationEndpoint
                ?.watchPlaylistEndpoint
                ?.let { watchPlaylistEndpoint ->
                    return@runCatching next(watchPlaylistEndpoint).getOrThrow().let { result ->
                        result.copy(
                            title = title,
                            items = songs + result.items,
                            lyricsEndpoint =
                                response.contents.singleColumnMusicWatchNextResultsRenderer
                                    ?.tabbedRenderer
                                    ?.watchNextTabbedResultsRenderer
                                    ?.tabs
                                    ?.getOrNull(
                                        1,
                                    )?.tabRenderer
                                    ?.endpoint
                                    ?.browseEndpoint,
                            relatedEndpoint =
                                response.contents.singleColumnMusicWatchNextResultsRenderer
                                    ?.tabbedRenderer
                                    ?.watchNextTabbedResultsRenderer
                                    ?.tabs
                                    ?.getOrNull(
                                        2,
                                    )?.tabRenderer
                                    ?.endpoint
                                    ?.browseEndpoint,
                            currentIndex = currentIndex,
                            endpoint = watchPlaylistEndpoint,
                        )
                    }
                }
            NextResult(
                title = title,
                items = songs,
                currentIndex = currentIndex,
                lyricsEndpoint =
                    response.contents.singleColumnMusicWatchNextResultsRenderer
                        ?.tabbedRenderer
                        ?.watchNextTabbedResultsRenderer
                        ?.tabs
                        ?.getOrNull(
                            1,
                        )?.tabRenderer
                        ?.endpoint
                        ?.browseEndpoint,
                relatedEndpoint =
                    response.contents.singleColumnMusicWatchNextResultsRenderer
                        ?.tabbedRenderer
                        ?.watchNextTabbedResultsRenderer
                        ?.tabs
                        ?.getOrNull(
                            2,
                        )?.tabRenderer
                        ?.endpoint
                        ?.browseEndpoint,
                continuation = playlistPanelRenderer.continuations?.getContinuation(),
                endpoint = endpoint,
            )
        }

    suspend fun lyrics(endpoint: BrowseEndpoint): Result<String?> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, endpoint.browseId, endpoint.params).body<BrowseResponse>()
            response.contents
                ?.sectionListRenderer
                ?.contents
                ?.firstOrNull { it.musicDescriptionShelfRenderer != null }
                ?.musicDescriptionShelfRenderer
                ?.description
                ?.runs
                ?.joinToString(separator = "") { it.text }
        }

    suspend fun related(endpoint: BrowseEndpoint): Result<RelatedPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, endpoint.browseId).body<BrowseResponse>()
            val songs = mutableListOf<SongItem>()
            val albums = mutableListOf<AlbumItem>()
            val artists = mutableListOf<ArtistItem>()
            val playlists = mutableListOf<PlaylistItem>()
            response.contents?.sectionListRenderer?.contents?.forEach { sectionContent ->
                sectionContent.musicCarouselShelfRenderer?.contents?.forEach { content ->
                    when (
                        val item =
                            content.musicResponsiveListItemRenderer?.let(RelatedPage.Companion::fromMusicResponsiveListItemRenderer)
                                ?: content.musicTwoRowItemRenderer?.let(RelatedPage.Companion::fromMusicTwoRowItemRenderer)
                    ) {
                        is SongItem -> {
                            if (content.musicResponsiveListItemRenderer
                                    ?.overlay
                                    ?.musicItemThumbnailOverlayRenderer
                                    ?.content
                                    ?.musicPlayButtonRenderer
                                    ?.playNavigationEndpoint
                                    ?.watchEndpoint
                                    ?.watchEndpointMusicSupportedConfigs
                                    ?.watchEndpointMusicConfig
                                    ?.musicVideoType == MUSIC_VIDEO_TYPE_ATV
                            ) {
                                songs.add(item)
                            }
                        }

                        is AlbumItem -> {
                            albums.add(item)
                        }

                        is ArtistItem -> {
                            artists.add(item)
                        }

                        is PlaylistItem -> {
                            playlists.add(item)
                        }

                        is PodcastItem, is EpisodeItem -> {}

                        null -> {}
                    }
                }
            }
            RelatedPage(songs, albums, artists, playlists)
        }

    suspend fun queue(
        videoIds: List<String>? = null,
        playlistId: String? = null,
    ): Result<List<SongItem>> =
        runCatching {
            if (videoIds != null) {
                assert(videoIds.size <= MAX_GET_QUEUE_SIZE) // Max video limit
            }
            innerTube
                .getQueue(WEB_REMIX, videoIds, playlistId)
                .body<GetQueueResponse>()
                .queueDatas
                .mapNotNull {
                    it.content.playlistPanelVideoRenderer?.let { renderer ->
                        NextPage.fromPlaylistPanelVideoRenderer(renderer)
                    }
                }
        }

    suspend fun transcript(videoId: String): Result<String> =
        runCatching {
            val response = innerTube.getTranscript(WEB, videoId).body<GetTranscriptResponse>()
            response.actions
                ?.firstOrNull()
                ?.updateEngagementPanelAction
                ?.content
                ?.transcriptRenderer
                ?.body
                ?.transcriptBodyRenderer
                ?.cueGroups
                ?.joinToString(
                    separator = "\n",
                ) { group ->
                    val time =
                        group.transcriptCueGroupRenderer.cues[0]
                            .transcriptCueRenderer.startOffsetMs
                    val text =
                        group.transcriptCueGroupRenderer.cues[0]
                            .transcriptCueRenderer.cue.simpleText
                            .trim('♪')
                            .trim(' ')
                    "[%02d:%02d.%03d]$text".format(time / 60000, (time / 1000) % 60, time % 1000)
                }!!
        }

    suspend fun visitorData(): Result<String> =
        runCatching {
            Json
                .parseToJsonElement(innerTube.getSwJsData().bodyAsText().substring(5))
                .jsonArray[0]
                .jsonArray[2]
                .jsonArray
                .first {
                    (it as? JsonPrimitive)?.contentOrNull?.let { candidate ->
                        VISITOR_DATA_REGEX.containsMatchIn(candidate)
                    } ?: false
                }.jsonPrimitive.content
        }

    suspend fun accountInfo(): Result<AccountInfo> =
        runCatching {
            innerTube
                .accountMenu(WEB_REMIX)
                .body<AccountMenuResponse>()
                .actions[0]
                .openPopupAction.popup.multiPageMenuRenderer
                .header
                ?.activeAccountHeaderRenderer
                ?.toAccountInfo()!!
        }

    suspend fun feedback(tokens: List<String>): Result<Boolean> =
        runCatching {
            innerTube
                .feedback(WEB_REMIX, tokens)
                .body<FeedbackResponse>()
                .feedbackResponses
                .all { it.isProcessed }
        }

    /**
     * Add a song to library by fetching fresh feedback tokens from the next endpoint
     * This is more reliable than using cached tokens which might be stale
     */
    suspend fun addSongToLibrary(videoId: String): Result<Boolean> =
        runCatching {
            // Get fresh song data with menu tokens using next endpoint
            val nextResult = next(WatchEndpoint(videoId = videoId)).getOrThrow()
            val song =
                nextResult.items.find { it.id == videoId }
                    ?: throw Exception("Song not found in next response")

            val addToken =
                song.libraryAddToken
                    ?: throw Exception("Add to library token not available")

            feedback(listOf(addToken)).getOrThrow()
        }

    /**
     * Remove a song from library by fetching fresh feedback tokens from the next endpoint
     */
    suspend fun removeSongFromLibrary(videoId: String): Result<Boolean> =
        runCatching {
            // Get fresh song data with menu tokens using next endpoint
            val nextResult = next(WatchEndpoint(videoId = videoId)).getOrThrow()
            val song =
                nextResult.items.find { it.id == videoId }
                    ?: throw Exception("Song not found in next response")

            val removeToken =
                song.libraryRemoveToken
                    ?: throw Exception("Remove from library token not available")

            feedback(listOf(removeToken)).getOrThrow()
        }

    /**
     * Toggle song library status - adds if not in library, removes if in library
     * Uses fresh tokens fetched from the API for reliability
     */
    suspend fun toggleSongLibrary(
        videoId: String,
        addToLibrary: Boolean,
    ): Result<Boolean> =
        runCatching {
            if (addToLibrary) {
                addSongToLibrary(videoId).getOrThrow()
            } else {
                removeSongFromLibrary(videoId).getOrThrow()
            }
        }

    suspend fun getMediaInfo(videoId: String): Result<MediaInfo> =
        runCatching {
            return innerTube.getMediaInfo(videoId)
        }

    suspend fun getTasteProfile(): Result<TasteProfile> =
        runCatching {
            // Browse the taste builder page
            // Note: Full parsing requires additional model support for musicTastebuilderShelfRenderer
            // This returns an empty profile for now - can be enhanced when models are added
            innerTube
                .browse(
                    client = WEB_REMIX,
                    browseId = "FEmusic_tastebuilder",
                    setLogin = true,
                ).body<BrowseResponse>()

            TasteProfile(artists = emptyMap())
        }

    suspend fun setTasteProfile(
        selectedArtists: List<String>,
        allArtists: Map<String, TasteArtist>,
    ): Result<Unit> =
        runCatching {
            val selectedValues = selectedArtists.mapNotNull { allArtists[it]?.selectionValue }
            val impressionValues = allArtists.values.map { it.impressionValue }

            if (selectedValues.isNotEmpty()) {
                feedback(selectedValues + impressionValues).getOrThrow()
            }
        }

    suspend fun removeHistoryItems(feedbackTokens: List<String>): Result<Boolean> =
        runCatching {
            feedback(feedbackTokens).getOrThrow()
        }

    @JvmInline
    value class SearchFilter(
        val value: String,
    ) {
        companion object {
            val FILTER_SONG = SearchFilter("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
            val FILTER_VIDEO = SearchFilter("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ALBUM = SearchFilter("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ARTIST = SearchFilter("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_FEATURED_PLAYLIST = SearchFilter("EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D")
            val FILTER_COMMUNITY_PLAYLIST = SearchFilter("EgeKAQQoAEABagoQAxAEEAoQCRAF")
            val FILTER_PODCAST = SearchFilter("EgWKAQJQAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_EPISODE = SearchFilter("EgWKAQJYAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_PROFILE = SearchFilter("EgWKAQJYAWoSEAUQCRADEAQQEBAVEAoQDhAR")
        }
    }

    @JvmInline
    value class LibraryFilter(
        val value: String,
    ) {
        companion object {
            val FILTER_RECENT_ACTIVITY = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpYnJhcnlfbGFuZGluZxoQZ2dNR0tnUUlCaEFCb0FZQg%3D%3D")
            val FILTER_RECENTLY_PLAYED = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpYnJhcnlfbGFuZGluZxoQZ2dNR0tnUUlCUkFCb0FZQg%3D%3D")
            val FILTER_PLAYLISTS_ALPHABETICAL = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBUkFBb0FZQg%3D%3D")
            val FILTER_PLAYLISTS_RECENTLY_SAVED = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBQkFCb0FZQg%3D%3D")
        }
    }

    const val MAX_GET_QUEUE_SIZE = 1000

    private val VISITOR_DATA_REGEX = Regex("^Cg[t|s]")

    fun getNewPipeStreamUrls(videoId: String): List<Pair<Int, String>> =
        if (ENABLE_NEWPIPE_STREAM_INFO_EXTRACTOR) {
            NewPipeExtractor.newPipePlayer(videoId)
        } else {
            emptyList()
        }

    suspend fun newPipePlayer(
        videoId: String,
        tempRes: PlayerResponse,
    ): PlayerResponse? {
        if (tempRes.playabilityStatus.status != "OK") {
            return null
        }

        val streamsList = getNewPipeStreamUrls(videoId)
        if (streamsList.isEmpty()) return null

        val decodedSigResponse =
            tempRes.copy(
                streamingData =
                    tempRes.streamingData?.copy(
                        formats =
                            tempRes.streamingData.formats?.map { format ->
                                format.copy(
                                    url = streamsList.find { it.first == format.itag }?.second ?: format.url,
                                )
                            },
                        adaptiveFormats =
                            tempRes.streamingData.adaptiveFormats.map { adaptiveFormat ->
                                adaptiveFormat.copy(
                                    url = streamsList.find { it.first == adaptiveFormat.itag }?.second ?: adaptiveFormat.url,
                                )
                            },
                    ),
            )

        val urlList =
            (
                decodedSigResponse.streamingData
                    ?.adaptiveFormats
                    ?.mapNotNull { it.url }
                    ?.toMutableList() ?: mutableListOf()
            ).apply {
                decodedSigResponse.streamingData
                    ?.formats
                    ?.mapNotNull { it.url }
                    ?.let { addAll(it) }
            }

        return if (urlList.isNotEmpty()) {
            decodedSigResponse
        } else {
            null
        }
    }

    /**
     * Upload a song to YouTube Music.
     * @param filename The name of the file
     * @param data The file data as ByteArray
     * @param onProgress Callback for upload progress (0.0 to 1.0)
     * @return true if upload succeeded
     */
    suspend fun uploadSong(
        filename: String,
        data: ByteArray,
        onProgress: ((Float) -> Unit)? = null,
    ): Result<Boolean> =
        runCatching {
            onProgress?.invoke(0f)

            // Step 1: Initialize upload (5% of progress)
            val initResponse = innerTube.initSongUpload(filename, data.size.toLong())
            val uploadUrl =
                initResponse.headers["X-Goog-Upload-URL"]
                    ?: throw Exception("Failed to get upload URL")

            onProgress?.invoke(0.05f)

            // Step 2: Upload file data (5% to 100% of progress)
            val uploadResponse =
                innerTube.uploadSongData(
                    uploadUrl = uploadUrl,
                    data = data,
                    onProgress = { uploadProgress ->
                        // Map upload progress (0-1) to overall progress (0.05-1.0)
                        onProgress?.invoke(0.05f + uploadProgress * 0.95f)
                    },
                )

            val status = uploadResponse.headers["X-Goog-Upload-Status"]
            status == "final"
        }

    /**
     * Delete an uploaded song from YouTube Music library.
     * @param entityId The entity ID of the uploaded song (typically the video ID)
     * @return true if deletion succeeded
     */
    suspend fun deleteUploadedSong(entityId: String): Result<Boolean> =
        runCatching {
            innerTube.deletePrivatelyOwnedEntity(entityId)
            true
        }

    /**
     * Supported file types for upload
     */
    val SUPPORTED_UPLOAD_TYPES = listOf("mp3", "m4a", "wma", "flac", "ogg")

    /**
     * Maximum file size for upload (300MB)
     */
    const val MAX_UPLOAD_SIZE = 314572800L

    suspend fun resolveArtistIds(items: List<YTItem>): List<YTItem> {
        val missingNames = mutableSetOf<String>()
        for (item in items) {
            when (item) {
                is SongItem -> item.artists.filter { it.id == null }.forEach { missingNames.add(it.name) }
                is AlbumItem -> item.artists?.filter { it.id == null }?.forEach { missingNames.add(it.name) }
                is PlaylistItem -> item.author?.let { if (it.id == null) missingNames.add(it.name) }
                is EpisodeItem -> item.author?.let { if (it.id == null) missingNames.add(it.name) }
                is PodcastItem -> item.author?.let { if (it.id == null) missingNames.add(it.name) }
                else -> {}
            }
        }

        if (missingNames.isEmpty()) return items

        val resolved = coroutineScope {
            val semaphore = kotlinx.coroutines.sync.Semaphore(8)
            missingNames.map { name ->
                async {
                    semaphore.acquire()
                    try {
                        val searchResult = search(name, SearchFilter.FILTER_ARTIST).getOrNull()
                        val normalizedName = name.trim()
                        val artistId = searchResult?.items
                            ?.filterIsInstance<ArtistItem>()
                            ?.firstOrNull { candidate ->
                                candidate.title.trim().equals(normalizedName, ignoreCase = true)
                            }?.id
                        if (artistId != null) name to artistId else null
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }

        fun Artist.resolve() = if (id == null) resolved[name]?.let { copy(id = it) } ?: this else this
        return items.map { item ->
            when (item) {
                is SongItem -> item.copy(artists = item.artists.map { it.resolve() })
                is AlbumItem -> item.copy(artists = item.artists?.map { it.resolve() })
                is PlaylistItem -> item.copy(author = item.author?.resolve())
                is EpisodeItem -> item.copy(author = item.author?.resolve())
                is PodcastItem -> item.copy(author = item.author?.resolve())
                else -> item
            }
        }
    }

    /**
     * Collects all unique missing artist names from [items], resolves them in parallel,
     * and returns a name -> id map. Use this when resolving across multiple sections
     * to avoid duplicate searches for the same artist.
     */
    suspend fun resolveArtistIdMap(items: List<YTItem>): Map<String, String> {
        val missingNames = mutableSetOf<String>()
        for (item in items) {
            when (item) {
                is SongItem -> item.artists.filter { it.id == null }.forEach { missingNames.add(it.name) }
                is AlbumItem -> item.artists?.filter { it.id == null }?.forEach { missingNames.add(it.name) }
                is PlaylistItem -> item.author?.let { if (it.id == null) missingNames.add(it.name) }
                is EpisodeItem -> item.author?.let { if (it.id == null) missingNames.add(it.name) }
                is PodcastItem -> item.author?.let { if (it.id == null) missingNames.add(it.name) }
                else -> {}
            }
        }

        if (missingNames.isEmpty()) return emptyMap()

        return coroutineScope {
            val semaphore = kotlinx.coroutines.sync.Semaphore(8)
            missingNames.map { name ->
                async {
                    semaphore.acquire()
                    try {
                        val searchResult = search(name, SearchFilter.FILTER_ARTIST).getOrNull()
                        val normalizedName = name.trim()
                        val artistId = searchResult?.items
                            ?.filterIsInstance<ArtistItem>()
                            ?.firstOrNull { candidate ->
                                candidate.title.trim().equals(normalizedName, ignoreCase = true)
                            }?.id
                        if (artistId != null) name to artistId else null
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }
}
