package com.metrolist.shared.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.metrolist.shared.viewmodels.PlayerViewModel.PlayerUiState
import kotlinx.coroutines.launch

internal val LocalPlayerArtworkPrefetchUrls = compositionLocalOf { emptyList<String>() }

internal fun PlayerUiState.adjacentArtworkUrls(): List<String> {
    if (currentQueueIndex !in queueItems.indices) return emptyList()
    val nextIndex = if (currentQueueIndex == queueItems.lastIndex && repeatMode == 2) 0 else currentQueueIndex + 1
    val previousIndex = if (currentQueueIndex == 0 && repeatMode == 2) queueItems.lastIndex else currentQueueIndex - 1
    return listOf(nextIndex, previousIndex)
        .filter { it != currentQueueIndex }
        .mapNotNull { queueItems.getOrNull(it)?.thumbnail?.takeIf(String::isNotBlank) }
        .filter { it != currentSong?.displayThumbnail }
        .distinct()
}

internal fun artworkPrefetchRequests(
    request: ImageRequest,
    size: IntSize,
    urls: List<String>,
): List<ImageRequest> {
    if (size.width <= 0 || size.height <= 0) return emptyList()
    return urls.filter { it.isNotBlank() && it != request.data }.distinct().map { url ->
        request
            .newBuilder()
            .data(url)
            .size(size.width, size.height)
            .build()
    }
}

@Composable
internal fun PrefetchPlayerArtwork(
    request: ImageRequest,
    size: IntSize,
    urls: List<String> = LocalPlayerArtworkPrefetchUrls.current,
) {
    if (urls.isEmpty()) return
    val context = LocalPlatformContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(request, size, urls, context, active) {
        if (active) {
            val loader = SingletonImageLoader.get(context)
            artworkPrefetchRequests(request, size, urls).forEach { prefetch ->
                launch { loader.execute(prefetch) }
            }
        }
    }
}
