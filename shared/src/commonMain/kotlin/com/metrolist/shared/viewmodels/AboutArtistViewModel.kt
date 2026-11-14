package com.metrolist.shared.viewmodels

import com.metrolist.shared.api.lastfm.LASTFM_PLACEHOLDER_HASH
import com.metrolist.shared.state.GlobalInnerTube
import com.metrolist.shared.utils.Logger
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class ArtistPhoto(
    val url: String,
    val pageUrl: String? = null,
)

@Serializable
data class LastFmArtistData(
    val photos: List<ArtistPhoto> = emptyList(),
    val tags: List<String> = emptyList(),
    val summary: String? = null,
    val listeners: String? = null,
    val scrobbles: String? = null,
    val similarArtists: List<String> = emptyList(),
)

internal fun mergeLastFmArtistPhotos(vararg sources: List<String>): List<String> =
    sources
        .asSequence()
        .flatten()
        .filter { it.isNotBlank() && !it.contains(LASTFM_PLACEHOLDER_HASH) }
        .distinct()
        .toList()

class AboutArtistViewModel(
    private val artistId: String,
    private val artistName: String,
    private val scope: CoroutineScope,
    private val client: HttpClient = GlobalInnerTube.client,
    private val cache: LastFmArtistCache = LastFmArtistCache.shared,
) {
    private val _uiState = MutableStateFlow(cache.get(artistId).let { AboutArtistUiState(lastFmData = it, isLoading = it == null) })
    val uiState: StateFlow<AboutArtistUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var loadLanguage: String? = null
    private var loadCatalogTitles: List<String>? = null

    init {
        scope.launch {
            cache.artist(artistId).filterNotNull().collect {
                _uiState.update { it.copy(lastFmData = cache.get(artistId), isLoading = false, hasError = false) }
            }
        }
    }

    fun load(catalogTitles: List<String>) {
        val language = cache.currentLanguage()
        if (loadJob?.isActive == true && loadLanguage == language && loadCatalogTitles == catalogTitles) return
        loadJob?.cancel()
        loadLanguage = language
        loadCatalogTitles = catalogTitles
        _uiState.update { AboutArtistUiState(lastFmData = cache.get(artistId), isLoading = true) }
        loadJob =
            scope.launch {
                Logger.d(TAG, "load start artistId=$artistId cached=${_uiState.value.lastFmData != null}")
                try {
                    cache.refresh(artistId, artistName, catalogTitles, client)
                    _uiState.update { it.copy(lastFmData = cache.get(artistId) ?: LastFmArtistData(), isLoading = false, hasError = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _uiState.update { it.copy(isLoading = false, hasError = it.lastFmData == null) }
                    Logger.w(TAG, "load failed artistId=$artistId type=${e::class.simpleName ?: "unknown"}", e)
                }
            }
    }

    data class AboutArtistUiState(
        val lastFmData: LastFmArtistData? = null,
        val isLoading: Boolean = true,
        val hasError: Boolean = false,
    )

    private companion object {
        const val TAG = "AboutArtistViewModel"
    }
}
