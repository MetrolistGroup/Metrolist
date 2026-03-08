package com.metrolist.shared.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil3.request.SuccessResult
import com.metrolist.shared.ui.utils.toImageBitmap
import com.metrolist.shared.utils.bucketedArtworkUrl
import com.metrolist.shared.utils.videoArtworkFallbackUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

internal class PlayerArtworkHandoff {
    var url by mutableStateOf<String?>(null)
        private set
    val artwork = MutableStateFlow<PlayerTrackArtwork?>(null)

    fun expect(url: String?) {
        if (this.url != url) {
            this.url = url
            artwork.value = null
        }
    }

    fun publish(artwork: PlayerTrackArtwork) {
        if (artwork.url == url) this.artwork.value = artwork
    }
}

internal val LocalPlayerArtworkHandoff = compositionLocalOf<PlayerArtworkHandoff?> { null }

@Composable
internal fun ShareMiniPlayerArtwork(
    url: String?,
    result: SuccessResult?,
) {
    val handoff = LocalPlayerArtworkHandoff.current ?: return
    val expectedUrl = handoff.url
    LaunchedEffect(handoff, url, result, expectedUrl) {
        if (url != null && url == expectedUrl && result != null && miniPlayerArtworkMatches(url, result.request.data as? String)) {
            val image = result.image
            val artwork = withContext(Dispatchers.IO) { PlayerTrackArtwork(url, image, image.toImageBitmap()) }
            handoff.publish(artwork)
        }
    }
}

internal fun miniPlayerArtworkMatches(
    url: String?,
    loadedUrl: String?,
): Boolean =
    url != null && loadedUrl != null &&
        bucketedArtworkUrl(videoArtworkFallbackUrl(url) ?: url, 1) == bucketedArtworkUrl(videoArtworkFallbackUrl(loadedUrl) ?: loadedUrl, 1)

internal fun Flow<PlayerTrackPresentation>.withMiniPlayerArtwork(previews: Flow<PlayerTrackArtwork?>): Flow<PlayerTrackPresentation> =
    combine(previews) { presentation, preview ->
        if (presentation.artwork.bitmap == null && presentation.artwork.image == null &&
            preview != null && preview.url == presentation.song?.displayThumbnail
        ) {
            presentation.copy(artwork = preview)
        } else {
            presentation
        }
    }
