package com.metrolist.shared.viewmodels

import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.api.artistSectionsWithLatestRelease
import com.metrolist.shared.api.artwork.AnimatedArtworkService
import com.metrolist.shared.api.artwork.ArtistArtworkFetchResult
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.localizedPageLoadError
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun visibleArtistSections(
    sections: Map<String, List<YTItem>>,
    artistId: String?,
): Map<String, List<YTItem>> {
    val artistInfo =
        sections["header"]?.filterIsInstance<ArtistItem>()?.firstOrNull()?.copy(
            isBlocked = BlockedContent.isArtistBlocked(artistId),
        )
    return artistSectionsWithLatestRelease(BlockedContent.filterItemSections(sections)).toMutableMap().apply {
        artistInfo?.let { put("header", listOf(it)) }
    }
}

fun artistCatalogTitles(sections: Map<String, List<YTItem>>): List<String> =
    sections.values
        .flatten()
        .filter { it is SongItem || it is AlbumItem }
        .map(YTItem::title)

class ArtistViewModel(
    private val artistId: String?,
    private val scope: CoroutineScope,
    private val animatedArtworkService: AnimatedArtworkService,
    private val mutationScope: CoroutineScope = scope,
) {
    val subscription = ArtistSubscriptionState()
    private val _uiState = MutableStateFlow(ArtistUiState(isLoading = artistId != null))
    private var loadGeneration = 0L
    private val tracksLoader = ArtistTracksLoader(artistId, scope, _uiState)
    private var appleMusicArtworkRequestKey: String? = null
    private var lastFmCacheJob: Job? = null
    private var lastFmLanguage: String? = null
    private var appleMusicArtworkJob: Job? = null
    val uiState: StateFlow<ArtistUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            BlockedContent.version.drop(1).collect {
                applyBlockedContentFilter()
            }
        }
        combine(
            PlaybackNetworkStateStore.state,
            snapshotFlow { Settings.shared.disableAnimatedArtworkOnMobileData },
        ) { network, _ -> Settings.shared.isAnimatedArtworkAllowedOnNetwork(network) }
            .distinctUntilChanged()
            .drop(1)
            .onEach { refreshAppleMusicArtwork() }
            .launchIn(scope)
        loadArtist()
    }

    fun loadArtist(onComplete: () -> Unit = {}) {
        if (artistId == null) {
            onComplete()
            return
        }

        val subscriptionRevision = subscription.revision
        val generation = ++loadGeneration
        val metadataToReload = tracksLoader.reset()
        scope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, loadingSections = emptySet(), failedSections = emptySet()) }
            try {
                val artistPage = withContext(Dispatchers.Default) { GlobalYouTubeRepository.instance.getArtist(artistId) }
                if (generation != loadGeneration) return@launch
                val filteredSections = visibleArtistSections(artistPage.sections, artistId)
                val artistInfo = filteredSections["header"]?.filterIsInstance<ArtistItem>()?.firstOrNull()
                subscription.applyBrowse(artistInfo?.isSubscribed == true, subscriptionRevision)
                _uiState.update { state ->
                    state.copy(
                        unfilteredSections = artistPage.sections,
                        sections = filteredSections,
                        sectionBrowseIds = artistPage.sectionBrowseIds,
                        sectionContinuations = emptyMap(),
                        artistInfo = artistInfo,
                        isLoading = false,
                    )
                }
                loadItemMetadata(filteredSections.values.flatten().filter { it.id in metadataToReload })
                val artistName = artistInfo?.title
                if (artistName != null) {
                    fetchLastFmMetadata(artistInfo.id, artistName, artistCatalogTitles(artistPage.sections))
                    fetchAppleMusicArtwork(artistName)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val message = t.localizedPageLoadError()
                if (generation == loadGeneration) _uiState.update { it.copy(errorMessage = message, isLoading = false) }
            } finally {
                onComplete()
            }
        }
    }

    fun loadSection(title: String) = tracksLoader.loadSection(title)

    fun loadItemMetadata(items: List<YTItem>) = tracksLoader.loadItemMetadata(items)

    fun toggleSubscription(onError: suspend () -> Unit) {
        val channelId = _uiState.value.artistInfo?.id ?: return
        val requested = subscription.beginToggle() ?: return
        val result =
            mutationScope.async(Dispatchers.Main.immediate) {
                subscription.requestToggle(requested) { GlobalYouTubeRepository.instance.subscribeChannel(channelId, it) }
            }
        scope.launch { if (!result.await()) onError() }
    }

    fun refresh(onComplete: () -> Unit) = loadArtist(onComplete)

    fun refreshAppleMusicArtwork() {
        _uiState.value.artistInfo
            ?.title
            ?.let(::fetchAppleMusicArtwork)
    }

    fun refreshLastFmForLanguage() {
        val state = _uiState.value
        val artist = state.artistInfo ?: return
        if (lastFmLanguage != LastFmArtistCache.shared.currentLanguage()) {
            fetchLastFmMetadata(artist.id, artist.title, artistCatalogTitles(state.unfilteredSections))
        }
    }

    private fun applyBlockedContentFilter() {
        _uiState.update { state ->
            val sections = visibleArtistSections(state.unfilteredSections, artistId)
            state.copy(
                sections = sections,
                artistInfo = sections["header"]?.filterIsInstance<ArtistItem>()?.firstOrNull(),
            )
        }
    }

    private fun fetchAppleMusicArtwork(artistName: String) {
        val requestKey =
            listOf(
                artistId,
                artistName,
                Settings.shared.enableAppleMusicArtistImages,
                Settings.shared.enableAppleMusicArtistVideos,
                Settings.shared.isAnimatedArtworkAllowedOnNetwork(),
                Settings.shared.appleMusicArtistVideoResolution,
                Settings.shared.lastResolvedContentCountry,
                Settings.shared.lastResolvedContentLanguage,
            ).joinToString("|")
        if (appleMusicArtworkRequestKey == requestKey) return
        appleMusicArtworkRequestKey = requestKey
        appleMusicArtworkJob?.cancel()
        val includeImage = Settings.shared.enableAppleMusicArtistImages
        _uiState.update {
            it.copy(
                appleMusicArtworkUrl = it.appleMusicArtworkUrl.takeIf { includeImage },
                appleMusicVideoUrl = null,
                isAppleMusicImageLookupComplete = !includeImage || it.appleMusicArtworkUrl != null,
            )
        }
        appleMusicArtworkJob =
            scope.launch {
                when (val result = animatedArtworkService.fetchArtistArtwork(artistName)) {
                    is ArtistArtworkFetchResult.Found -> {
                        if (appleMusicArtworkRequestKey != requestKey) return@launch
                        _uiState.update {
                            it.copy(
                                appleMusicArtworkUrl = result.artwork.imageUrl,
                                appleMusicVideoUrl = result.artwork.videoUrl,
                                isAppleMusicImageLookupComplete = true,
                            )
                        }
                    }

                    ArtistArtworkFetchResult.Missing -> {
                        if (appleMusicArtworkRequestKey != requestKey) return@launch
                        _uiState.update {
                            it.copy(
                                appleMusicArtworkUrl = null,
                                appleMusicVideoUrl = null,
                                isAppleMusicImageLookupComplete = true,
                            )
                        }
                    }

                    ArtistArtworkFetchResult.Unavailable -> {
                        if (appleMusicArtworkRequestKey == requestKey) {
                            appleMusicArtworkRequestKey = null
                            _uiState.update { it.copy(isAppleMusicImageLookupComplete = true) }
                        }
                    }
                }
            }
    }

    private fun fetchLastFmMetadata(
        cacheId: String,
        artistName: String,
        catalogTitles: List<String>,
    ) {
        val cache = LastFmArtistCache.shared
        lastFmLanguage = cache.currentLanguage()
        lastFmCacheJob?.cancel()
        lastFmCacheJob =
            scope.launch(Dispatchers.Default) {
                cache.artist(cacheId).filterNotNull().collect { data ->
                    _uiState.update {
                        it.copy(lastFmPhotoUrl = data.photos.firstOrNull()?.url ?: it.lastFmPhotoUrl, lastFmBio = data.summary)
                    }
                }
            }
        scope.launch(Dispatchers.Default) {
            Logger.d(TAG, "metadata load start artistId=$artistId")
            try {
                cache.refresh(cacheId, artistName, catalogTitles)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "metadata load failed artistId=$artistId type=${e::class.simpleName ?: "unknown"}", e)
            }
        }
    }

    data class ArtistUiState(
        val artistInfo: ArtistItem? = null,
        val unfilteredSections: Map<String, List<YTItem>> = emptyMap(),
        val sections: Map<String, List<YTItem>> = emptyMap(),
        val sectionBrowseIds: Map<String, Pair<String?, String?>> = emptyMap(),
        val sectionContinuations: Map<String, String?> = emptyMap(),
        val loadingSections: Set<String> = emptySet(),
        val failedSections: Set<String> = emptySet(),
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
        val lastFmPhotoUrl: String? = null,
        val lastFmBio: String? = null,
        val appleMusicArtworkUrl: String? = null,
        val appleMusicVideoUrl: String? = null,
        val isAppleMusicImageLookupComplete: Boolean = false,
    ) {
        val loadableSections: Set<String> =
            sectionBrowseIds.keys.filterTo(mutableSetOf()) { title ->
                sectionBrowseIds[title]?.first != null && (title !in sectionContinuations || sectionContinuations[title] != null)
            }
    }

    private companion object {
        const val TAG = "ArtistViewModel"
    }
}
