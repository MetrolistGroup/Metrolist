/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.state.content

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.app.DesktopNavigation
import com.metrolist.desktop.state.playback.DiscordRpcState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.api.artwork.ArtistArtworkFetchResult
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.ArtistSource
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalInnerTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.components.CollectionPreviews
import com.metrolist.shared.ui.localizedPageLoadError
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.viewmodels.ArtistSubscriptionState
import com.metrolist.shared.viewmodels.ArtistTracksLoader
import com.metrolist.shared.viewmodels.ArtistViewModel.ArtistUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.getString

object ArtistState {
    private var artistLoadJob: Job? = null
    private var artistTracksJob: Job? = null
    private var artistTracksLoader: ArtistTracksLoader? = null
    var artistTracksState by mutableStateOf(ArtistUiState())
        private set

    fun loadItemMetadata(items: List<YTItem>) {
        artistTracksLoader?.loadItemMetadata(items)
    }

    fun loadSection(title: String) {
        artistTracksLoader?.loadSection(title)
    }

    private var artistMetadataLoadJob: Job? = null
    private var artistArtworkLoadJob: Job? = null
    private var artistArtworkRequestKey: String? = null
    private var artistSubscriptionJob: Job? = null

    private val customBanners: MutableMap<String, String> =
        run {
            val raw = Settings.shared.artistCustomBannersJson
            runCatching {
                kotlinx.serialization.json.Json
                    .decodeFromString<Map<String, String>>(raw)
            }.getOrDefault(emptyMap()).toMutableMap()
        }

    fun getCustomArtistBanner(artistId: String): String? = customBanners[artistId]

    val artistBannerCache = mutableStateMapOf<String, String>()

    var selectedArtistId by mutableStateOf<String?>(null)
    val loadedArtistId: String? get() = (artistData["header"]?.firstOrNull() as? ArtistItem)?.id
    private val retainedArtistId: String? get() = selectedArtistId ?: loadedArtistId
    var artistData by mutableStateOf<Map<String, List<YTItem>>>(emptyMap())
    private var unfilteredArtistData: Map<String, List<YTItem>> = emptyMap()
    var artistSectionBrowseIds by mutableStateOf<Map<String, Pair<String?, String?>>>(emptyMap())
    var isArtistLoading by mutableStateOf(false)
    var artistLoadError by mutableStateOf<String?>(null)
        private set
    private var artistSubscription by mutableStateOf(ArtistSubscriptionState())
    val artistIsSubscribed get() = artistSubscription.isSubscribed
    val artistSubscribeSuccessCount get() = artistSubscription.subscribeSuccessCount
    var topBarScrollOffset by mutableStateOf(Int.MAX_VALUE)

    var artistGenres by mutableStateOf<List<String>>(emptyList())
    var artistLastFmListeners by mutableStateOf<String?>(null)
    var appleMusicArtworkUrl by mutableStateOf<String?>(null)
    var appleMusicVideoUrl by mutableStateOf<String?>(null)
    var isAppleMusicImageLookupComplete by mutableStateOf(false)
    var lastFmImageUrl by mutableStateOf<String?>(null)

    var artistBannerSource by mutableStateOf(
        runCatching {
            ArtistSource.valueOf(Settings.shared.artistBannerSource)
        }.getOrDefault(ArtistSource.YOUTUBE),
    )
    var artistIconSource by mutableStateOf(
        runCatching {
            ArtistSource.valueOf(Settings.shared.artistIconSource)
        }.getOrDefault(ArtistSource.YOUTUBE),
    )

    init {
        AppState.scope.launch {
            BlockedContent.version.drop(1).collect {
                applyBlockedContentFilter()
            }
        }
    }

    private fun applyBlockedContentFilter() {
        artistData = markedBlockedHeader(BlockedContent.filterItemSections(unfilteredArtistData))
    }

    private fun markedBlockedHeader(data: Map<String, List<YTItem>>): Map<String, List<YTItem>> =
        data.mapValues { (title, items) ->
            if (title == "header") {
                items.map { item ->
                    (item as? ArtistItem)?.copy(isBlocked = BlockedContent.isArtistBlocked(item.id)) ?: item
                }
            } else {
                items
            }
        }

    fun fetchArtistData(artist: ArtistItem) {
        CollectionPreviews.record(artist)
        fetchArtistData(artist.id)
    }

    fun fetchArtistData(
        id: String,
        addToHistory: Boolean = true,
    ) {
        PlayerState.currentTrack
            ?.artists
            ?.firstOrNull { it.id == id }
            ?.let(CollectionPreviews::record)
        PlayerState.exitExpanded()
        if (addToHistory && selectedArtistId == id &&
            DesktopNavigation.navigator.state.currentRoute == Screen.Artist(id)
        ) {
            return
        }
        PodcastState.clear()
        if (addToHistory) DesktopNavigation.navigate(Screen.Artist(id))
        val artistChanged = (selectedArtistId ?: loadedArtistId) != id
        selectedArtistId = id
        PlaylistAlbumState.selectedPlaylistId = null
        PlaylistAlbumState.selectedAlbumId = null
        isArtistLoading = true
        artistLoadError = null
        if (artistChanged) {
            artistData = emptyMap()
            unfilteredArtistData = emptyMap()
            artistSectionBrowseIds = emptyMap()
            appleMusicArtworkUrl = null
            appleMusicVideoUrl = null
            isAppleMusicImageLookupComplete = false
        }
        if (artistChanged) artistSubscription = ArtistSubscriptionState()
        val subscription = artistSubscription
        val subscriptionRevision = subscription.revision
        artistGenres = emptyList()
        artistLastFmListeners = null
        lastFmImageUrl = null
        val metadataToReload = if (artistChanged) emptySet() else artistTracksLoader?.reset().orEmpty()
        artistTracksJob?.cancel()
        artistTracksLoader = null
        artistTracksState = ArtistUiState()
        artistLoadJob?.cancel()
        artistMetadataLoadJob?.cancel()
        artistArtworkLoadJob?.cancel()
        artistArtworkRequestKey = null
        if (artistChanged) artistSubscriptionJob?.cancel()
        artistLoadJob =
            AppState.scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { GlobalYouTubeRepository.instance.getArtist(id) }
                    if (retainedArtistId != id) return@launch
                    unfilteredArtistData = result.sections
                    artistData = markedBlockedHeader(BlockedContent.filterItemSections(unfilteredArtistData))
                    artistSectionBrowseIds = result.sectionBrowseIds
                    val tracksState =
                        MutableStateFlow(
                            ArtistUiState(
                                unfilteredSections = result.sections,
                                sections = artistData,
                                sectionBrowseIds = result.sectionBrowseIds,
                            ),
                        )
                    val tracksJob = Job(AppState.scope.coroutineContext[Job]).also { artistTracksJob = it }
                    val tracksScope = CoroutineScope(AppState.scope.coroutineContext + tracksJob)
                    artistTracksLoader =
                        ArtistTracksLoader(id, tracksScope, tracksState).also { loader ->
                            loader.loadItemMetadata(
                                result.sections.values
                                    .flatten()
                                    .filter { it.id in metadataToReload },
                            )
                        }
                    tracksScope.launch {
                        tracksState.collect { state ->
                            if (retainedArtistId == id) {
                                artistTracksState = state
                                unfilteredArtistData = state.unfilteredSections
                                applyBlockedContentFilter()
                            }
                        }
                    }
                    val header = (artistData["header"]?.firstOrNull() as? ArtistItem)
                    subscription.applyBrowse(header?.isSubscribed ?: false, subscriptionRevision)

                    header?.title?.let { name ->
                        loadArtistLastFmMetadata(name, header.id)
                        loadAppleMusicArtwork(id, name)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message = error.localizedPageLoadError()
                    if (retainedArtistId == id) artistLoadError = message
                } finally {
                    if (retainedArtistId == id) isArtistLoading = false
                }
            }
    }

    fun refreshAppleMusicArtwork() {
        val artistId = selectedArtistId ?: return
        val artistName = (artistData["header"]?.firstOrNull() as? ArtistItem)?.title ?: return
        loadAppleMusicArtwork(artistId, artistName)
    }

    private fun loadAppleMusicArtwork(
        artistId: String,
        artistName: String,
    ) {
        val requestKey =
            listOf(
                artistId,
                artistName,
                Settings.shared.enableAppleMusicArtistImages,
                Settings.shared.enableAppleMusicArtistVideos,
                Settings.shared.appleMusicArtistVideoResolution,
                Settings.shared.lastResolvedContentCountry,
                Settings.shared.lastResolvedContentLanguage,
            ).joinToString("|")
        if (artistArtworkRequestKey == requestKey) return
        artistArtworkRequestKey = requestKey
        artistArtworkLoadJob?.cancel()
        val includeImage = Settings.shared.enableAppleMusicArtistImages
        appleMusicArtworkUrl = appleMusicArtworkUrl.takeIf { includeImage }
        appleMusicVideoUrl = null
        isAppleMusicImageLookupComplete = !includeImage || appleMusicArtworkUrl != null
        updateAppleMusicBannerCache(artistId)
        artistArtworkLoadJob =
            AppState.scope.launch {
                when (val result = AppState.animatedArtworkService.fetchArtistArtwork(artistName)) {
                    is ArtistArtworkFetchResult.Found -> {
                        if (retainedArtistId != artistId || artistArtworkRequestKey != requestKey) return@launch
                        appleMusicArtworkUrl = result.artwork.imageUrl
                        appleMusicVideoUrl = result.artwork.videoUrl
                        isAppleMusicImageLookupComplete = true
                        updateAppleMusicBannerCache(artistId)
                    }

                    ArtistArtworkFetchResult.Missing -> {
                        if (retainedArtistId != artistId || artistArtworkRequestKey != requestKey) return@launch
                        appleMusicArtworkUrl = null
                        appleMusicVideoUrl = null
                        isAppleMusicImageLookupComplete = true
                        updateAppleMusicBannerCache(artistId)
                    }

                    ArtistArtworkFetchResult.Unavailable -> {
                        if (retainedArtistId == artistId && artistArtworkRequestKey == requestKey) {
                            isAppleMusicImageLookupComplete = true
                            artistArtworkRequestKey = null
                            updateAppleMusicBannerCache(artistId)
                        }
                    }
                }
            }
    }

    private fun updateAppleMusicBannerCache(artistId: String) {
        if (retainedArtistId != artistId || artistBannerSource != ArtistSource.ITUNES) return
        appleMusicArtworkUrl?.let { artistBannerCache[artistId] = it } ?: artistBannerCache.remove(artistId)
    }

    fun loadArtistLastFmMetadata(
        name: String,
        artistId: String? = null,
    ) {
        artistMetadataLoadJob?.cancel()
        artistMetadataLoadJob =
            AppState.scope.launch(Dispatchers.IO) {
                try {
                    val info = LastFM.getArtistInfo(GlobalInnerTube.client, name)
                    val scraped = if (info == null) LastFM.scrapeArtistPage(GlobalInnerTube.client, name) else null
                    val audience = LastFM.parseArtistAudience(info, scraped)
                    val imageUrl = lastFmImageFromInfo(info) ?: scraped?.avatarUrl

                    withContext(Dispatchers.Main) {
                        if (artistId != null && retainedArtistId != artistId) return@withContext
                        artistGenres = audience.genres
                        artistLastFmListeners = audience.listeners
                        lastFmImageUrl = imageUrl

                        if (artistId != null && artistIconSource != ArtistSource.YOUTUBE) {
                            val iconUrl =
                                when (artistIconSource) {
                                    ArtistSource.ITUNES -> appleMusicArtworkUrl
                                    ArtistSource.LASTFM -> imageUrl
                                    ArtistSource.CUSTOM -> getCustomArtistBanner(artistId)
                                    else -> null
                                }
                            iconUrl?.let { DiscordRpcState.artistIconCache[artistId] = it }
                        }
                        if (artistId != null) {
                            val bannerUrl =
                                when (artistBannerSource) {
                                    ArtistSource.ITUNES -> appleMusicArtworkUrl
                                    ArtistSource.LASTFM -> imageUrl
                                    ArtistSource.CUSTOM -> getCustomArtistBanner(artistId)
                                    else -> null
                                }
                            bannerUrl?.let { artistBannerCache[artistId] = it }
                        }
                    }
                } catch (_: Exception) {
                }
            }
    }

    fun updateArtistBannerSource(source: ArtistSource) {
        artistBannerSource = source
        Settings.shared.artistBannerSource = source.name
        selectedArtistId?.let { artistId ->
            val bannerUrl =
                when (source) {
                    ArtistSource.ITUNES -> appleMusicArtworkUrl
                    ArtistSource.LASTFM -> lastFmImageUrl
                    ArtistSource.CUSTOM -> getCustomArtistBanner(artistId)
                    else -> null
                }
            if (bannerUrl != null) {
                artistBannerCache[artistId] = bannerUrl
            } else {
                artistBannerCache.remove(artistId)
            }
        }
    }

    fun updateArtistIconSource(source: ArtistSource) {
        artistIconSource = source
        Settings.shared.artistIconSource = source.name
    }

    fun toggleArtistSubscription() {
        val artistInfo = artistData["header"]?.firstOrNull() as? ArtistItem ?: return
        artistSubscription
            .toggle(
                AppState.scope,
                request = { GlobalYouTubeRepository.instance.subscribeChannel(artistInfo.id, it) },
                onError = { AppState.snackbarHostState.showSnackbar(getString(Res.string.error_occurred)) },
            )?.let { artistSubscriptionJob = it }
    }

    fun startArtistRadio() {
        val artistInfo = artistData["header"]?.firstOrNull() as? ArtistItem ?: return
        AppState.scope.launch {
            try {
                val radioSongs = withContext(Dispatchers.IO) { GlobalYouTubeRepository.instance.startArtistRadio(artistInfo.id) }
                if (radioSongs.isNotEmpty()) {
                    PlayerState.playTrack(radioSongs[0], radioSongs)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun shuffleArtistSongs() = playArtistSongs(shuffle = true)

    fun playAllArtistSongs() = playArtistSongs(shuffle = false)

    private fun playArtistSongs(shuffle: Boolean) {
        if (PlayerState.isListenTogetherGuest()) return
        val artist = artistData["header"]?.firstOrNull() as? ArtistItem ?: return
        val page = ArtistPageResult(artistData.toMap(), artistSectionBrowseIds.toMap())
        AppState.scope.launch {
            val songs =
                GlobalYouTubeRepository.instance.getArtistSongs(artist.id, page) {
                    AppState.snackbarHostState.showSnackbar(getString(Res.string.error_occurred))
                }
            val queue = if (shuffle) songs.shuffled() else songs
            queue.firstOrNull()?.let {
                if (!shuffle && PlayerState.shuffleEnabled) PlayerState.toggleShuffle()
                PlayerState.playTrack(it, queue, songsOnly = true)
            }
        }
    }

    fun fetchArtistIconOnDemand(
        artistId: String,
        artistName: String,
        source: ArtistSource,
    ) {
        AppState.scope.launch(Dispatchers.IO) {
            try {
                val photoUrl =
                    when (source) {
                        ArtistSource.ITUNES -> {
                            fetchAppleMusicArtistImage(artistName)
                        }

                        ArtistSource.LASTFM -> {
                            fetchLastFmIcon(artistName)
                        }

                        ArtistSource.CUSTOM -> {
                            getCustomArtistBanner(artistId) ?: fetchLastFmIcon(artistName)
                        }

                        else -> {
                            null
                        }
                    }
                if (photoUrl != null) {
                    withContext(Dispatchers.Main) {
                        DiscordRpcState.artistIconCache[artistId] = photoUrl
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    suspend fun fetchArtistBannerOnDemand(
        artistId: String,
        artistName: String,
        source: ArtistSource,
    ): String? =
        try {
            when (source) {
                ArtistSource.ITUNES -> {
                    fetchAppleMusicArtistImage(artistName)
                }

                ArtistSource.LASTFM -> {
                    fetchLastFmIcon(artistName)
                }

                ArtistSource.CUSTOM -> {
                    getCustomArtistBanner(artistId) ?: fetchLastFmIcon(artistName)
                }

                else -> {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }

    private suspend fun fetchAppleMusicArtistImage(name: String): String? =
        (AppState.animatedArtworkService.fetchArtistArtwork(name) as? ArtistArtworkFetchResult.Found)?.artwork?.imageUrl

    internal suspend fun fetchLastFmIcon(name: String): String? =
        try {
            lastFmImageFromInfo(LastFM.getArtistInfo(GlobalInnerTube.client, name))
        } catch (e: Exception) {
            Logger.w("ArtistState", "Last.fm icon fetch failed", e)
            null
        }

    private fun lastFmImageFromInfo(artistInfo: JsonObject?): String? {
        val images = artistInfo?.get("image")?.jsonArray
        val largeImage =
            images
                ?.lastOrNull()
                ?.jsonObject
                ?.get("#text")
                ?.jsonPrimitive
                ?.contentOrNull
        if (largeImage?.isNotBlank() == true) {
            return largeImage
        }
        images?.forEach { imageElement ->
            val url = imageElement.jsonObject["#text"]?.jsonPrimitive?.contentOrNull
            if (url?.isNotBlank() == true &&
                url != "https://lastfm.freetls.fastly.net/i/u/300x300/2a96cbd8b46e442fc41c2b86b821562f.png"
            ) {
                return url
            }
        }
        return null
    }
}
