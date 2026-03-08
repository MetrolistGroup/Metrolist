package com.metrolist.shared.ui.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.viewmodels.ArtistSubscriptionState
import com.metrolist.shared.viewmodels.BrowseViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class MobileRouteEntry(
    route: MobileRoute,
) {
    var route by mutableStateOf(route)
}

internal class MobileDetailStateStore(
    private val scope: CoroutineScope,
    private val repository: YouTubeRepository = GlobalYouTubeRepository.instance,
) {
    private val entries = mutableMapOf<MobileRouteEntry, MobileDetailState>()
    private val compositions = mutableMapOf<MobileRouteEntry, Int>()
    private var liveEntries = emptySet<MobileRouteEntry>()

    fun entry(key: MobileRouteEntry): MobileDetailState = entries.getOrPut(key) { MobileDetailState(scope, repository) }

    fun retain(keys: List<MobileRouteEntry>) {
        liveEntries = keys.toSet()
        discardAbandoned()
    }

    fun attach(key: MobileRouteEntry) {
        compositions[key] = (compositions[key] ?: 0) + 1
    }

    fun detach(key: MobileRouteEntry) {
        val remaining = (compositions[key] ?: 1) - 1
        if (remaining == 0) compositions.remove(key) else compositions[key] = remaining
        discardAbandoned()
    }

    private fun discardAbandoned() {
        entries.keys.filterNot { it in liveEntries || it in compositions }.forEach { entries.remove(it)?.close() }
    }

    fun close() {
        entries.values.forEach { it.close() }
        entries.clear()
        compositions.clear()
    }
}

internal class MobileDetailState(
    parentScope: CoroutineScope,
    private val repository: YouTubeRepository,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    val subscription = ArtistSubscriptionState()
    private var albumLoader: MobileDetailLoader<AlbumPage>? = null
    private var artistLoader: MobileDetailLoader<ArtistPageResult>? = null
    private var browseLoader: BrowseViewModel? = null

    fun album(id: String): MobileDetailLoader<AlbumPage> =
        albumLoader ?: MobileDetailLoader(scope) {
            checkNotNull(repository.getAlbum(id))
        }.also { albumLoader = it }

    fun artist(id: String): MobileDetailLoader<ArtistPageResult> =
        artistLoader ?: MobileDetailLoader(scope) {
            val revision = subscription.revision
            repository.getArtist(id).also { page ->
                check(page.sections.isNotEmpty()) { "Artist load failed" }
                subscription.applyBrowse((page.sections["header"]?.firstOrNull() as? ArtistItem)?.isSubscribed == true, revision)
            }
        }.also { artistLoader = it }

    fun browse(destination: MobileBrowseDestination): BrowseViewModel =
        browseLoader ?: BrowseViewModel(
            destination.id,
            destination.params,
            destination.title,
            scope,
            repository,
        ).also { browseLoader = it }

    fun shelf(section: HomeSection): BrowseViewModel =
        browseLoader ?: BrowseViewModel(
            section.browseId,
            section.params,
            section.title,
            scope,
            repository,
            initialSection = section,
        ).also { browseLoader = it }

    fun close() = scope.cancel()
}

internal class MobileDetailLoader<T>(
    private val scope: CoroutineScope,
    private val fetch: suspend () -> T,
) {
    var page by mutableStateOf<T?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var job: Job? = null
    private var generation = 0L

    init {
        load()
    }

    fun load() {
        val requestGeneration = ++generation
        job?.cancel()
        isLoading = true
        failed = false
        job =
            scope.launch {
                try {
                    val loaded = fetch()
                    if (generation == requestGeneration) page = loaded
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (generation == requestGeneration) failed = true
                } finally {
                    if (generation == requestGeneration) isLoading = false
                }
            }
    }
}
