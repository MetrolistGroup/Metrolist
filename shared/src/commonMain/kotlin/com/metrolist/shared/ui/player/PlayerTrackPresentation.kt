package com.metrolist.shared.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.Image
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Scale
import com.metrolist.shared.settings.PlayerThumbnailStyle
import com.metrolist.shared.ui.components.PrefetchPlayerArtwork
import com.metrolist.shared.ui.utils.configurePreparedArtwork
import com.metrolist.shared.ui.utils.toImageBitmap
import com.metrolist.shared.viewmodels.PlayerViewModel.SongUiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

internal data class PlayerTrackArtwork(
    val url: String?,
    val image: Image? = null,
    val bitmap: ImageBitmap? = null,
)

internal data class PlayerTrackPresentation(
    val song: SongUiModel? = null,
    val artwork: PlayerTrackArtwork = PlayerTrackArtwork(null),
    val isArtworkLoading: Boolean = false,
)

internal val LocalPlayerTrackArtwork = compositionLocalOf<PlayerTrackArtwork?> { null }

internal fun playerArtworkRequestSize(
    windowSize: IntSize,
    thumbnailStyle: PlayerThumbnailStyle,
    isFullscreen: Boolean,
): IntSize {
    if (isFullscreen || thumbnailStyle != PlayerThumbnailStyle.COMPACT) return windowSize
    val edge = minOf(windowSize.width, windowSize.height)
    return IntSize(edge, edge)
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<SongUiModel?>.withPlayerTrackArtwork(
    initialArtwork: PlayerTrackArtwork = PlayerTrackArtwork(null),
    load: suspend (String) -> PlayerTrackArtwork,
): Flow<PlayerTrackPresentation> {
    var fallback: PlayerTrackArtwork? = initialArtwork
    val artwork =
        map { it?.displayThumbnail }.distinctUntilChanged().mapLatest { url ->
            if (url.isNullOrBlank()) {
                PlayerTrackArtwork(url)
            } else {
                try {
                    load(url)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    PlayerTrackArtwork(url)
                }
            }
        }
    return combine(this, artwork.onStart { emit(PlayerTrackArtwork(fallback?.url)) }) { song, loaded ->
        val replacement = loaded.takeIf { it.url == song?.displayThumbnail && (it.image != null || it.bitmap != null) }
        if (replacement != null || fallback?.url != song?.displayThumbnail) fallback = null
        val matchingArtwork =
            replacement
                ?: fallback
                ?: PlayerTrackArtwork(song?.displayThumbnail)
        PlayerTrackPresentation(song, matchingArtwork, isArtworkLoading = loaded.url != song?.displayThumbnail)
    }.distinctUntilChanged()
}

internal fun Flow<PlayerTrackPresentation>.retainWhileArtworkLoads(
    initialPresentation: PlayerTrackPresentation = PlayerTrackPresentation(),
): Flow<PlayerTrackPresentation> {
    var previous = initialPresentation
    return map { incoming ->
        val hasPreviousArtwork = previous.artwork.image != null || previous.artwork.bitmap != null
        val hasIncomingArtwork = incoming.artwork.image != null || incoming.artwork.bitmap != null
        if (incoming.song != null && incoming.isArtworkLoading && !hasIncomingArtwork && hasPreviousArtwork) {
            previous
        } else {
            incoming.also { previous = it }
        }
    }.distinctUntilChanged()
}

@Composable
internal fun rememberPlayerTrackPresentation(
    songs: Flow<SongUiModel?>,
    size: IntSize,
    adjacentArtworkUrls: List<String>,
    isScreenVisible: Boolean,
): PlayerTrackPresentation {
    val context = LocalPlatformContext.current
    val handoff = LocalPlayerArtworkHandoff.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = isScreenVisible && lifecycleState == Lifecycle.State.RESUMED
    val presentation by produceState(PlayerTrackPresentation(), songs, context, size, active, handoff) {
        if (active) {
            songs
                .withPlayerTrackArtwork(initialArtwork = value.artwork) { url ->
                    withContext(Dispatchers.IO) {
                        val request =
                            ImageRequest
                                .Builder(context)
                                .data(url)
                                .size(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
                                .scale(Scale.FILL)
                                .configurePreparedArtwork()
                                .build()
                        val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image
                        PlayerTrackArtwork(url, image, image?.toImageBitmap())
                    }
                }.onEach { handoff?.expect(it.song?.displayThumbnail) }
                .let { presentations -> if (handoff != null) presentations.withMiniPlayerArtwork(handoff.artwork) else presentations }
                .retainWhileArtworkLoads(initialPresentation = value)
                .collect { value = it }
        }
    }
    val request =
        remember(context, presentation.artwork.url, size) {
            ImageRequest
                .Builder(context)
                .data(presentation.artwork.url)
                .size(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
                .scale(Scale.FILL)
                .configurePreparedArtwork()
                .build()
        }
    PrefetchPlayerArtwork(request, size, adjacentArtworkUrls.takeIf { active }.orEmpty())
    return presentation
}
