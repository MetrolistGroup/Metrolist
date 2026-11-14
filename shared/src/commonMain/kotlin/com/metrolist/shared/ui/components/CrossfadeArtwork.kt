package com.metrolist.shared.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.rememberConstraintsSizeResolver
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.size.Scale
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.player.LocalPlayerTrackArtwork
import com.metrolist.shared.ui.player.ShareMiniPlayerArtwork

@Composable
fun CrossfadeArtwork(
    currentUrl: String?,
    nextUrl: String?,
    progress: () -> Float,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier,
    animate: Boolean = true,
) {
    val crossfadeProgress = { if (nextUrl == null) 0f else progress().coerceIn(0f, 1f) }
    Box(modifier, contentAlignment = Alignment.Center) {
        LoadingArtwork(
            url = currentUrl,
            shareMiniPlayerArtwork = true,
            animate = animate,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.matchParentSize().graphicsLayer { alpha = 1f - crossfadeProgress() },
        )
        if (nextUrl != null) {
            LoadingArtwork(
                url = nextUrl,
                animate = animate,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize().graphicsLayer { alpha = crossfadeProgress() },
            )
        }
    }
}

@Composable
private fun LoadingArtwork(
    url: String?,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier,
    animate: Boolean = true,
    shareMiniPlayerArtwork: Boolean = false,
) {
    val prepared = LocalPlayerTrackArtwork.current?.takeIf { it.url == url }
    if (prepared != null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            val bitmap = prepared.bitmap
            if (bitmap != null) {
                Image(
                    painter = remember(bitmap) { BitmapPainter(bitmap) },
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = MetrolistIcons.Outlined.MusicNote,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        return
    }
    val context = LocalPlatformContext.current
    val sizeResolver = rememberConstraintsSizeResolver()
    val decodeScale = if (contentScale == ContentScale.Fit || contentScale == ContentScale.Inside) Scale.FIT else Scale.FILL
    val request =
        remember(url, context, sizeResolver, decodeScale) {
            ImageRequest
                .Builder(context)
                .data(url)
                .size(sizeResolver)
                .scale(decodeScale)
                .build()
        }
    var artworkSize by remember { mutableStateOf(IntSize.Zero) }
    PrefetchPlayerArtwork(request, artworkSize)
    var preview by remember { mutableStateOf<Painter?>(null) }
    val painter =
        rememberAsyncImagePainter(
            model = request,
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Success -> preview = state.painter
                    is AsyncImagePainter.State.Error -> preview = null
                    else -> Unit
                }
            },
        )
    val state by painter.state.collectAsState()
    if (shareMiniPlayerArtwork) ShareMiniPlayerArtwork(url, (state as? AsyncImagePainter.State.Success)?.result)
    val loading = state is AsyncImagePainter.State.Empty || state is AsyncImagePainter.State.Loading
    val previewPainter = preview.takeUnless { url.isNullOrBlank() }
    val loaded = !loading || previewPainter != null || url.isNullOrBlank()
    val imageAlpha by animateFloatAsState(
        targetValue = if (loaded) 1f else 0f,
        animationSpec =
            if (!animate || (state as? AsyncImagePainter.State.Success)?.result?.dataSource ==
                DataSource.MEMORY_CACHE
            ) {
                snap()
            } else {
                tween(220)
            },
        label = "miniPlayerArtworkFade",
    )
    Box(modifier.then(sizeResolver).onSizeChanged { artworkSize = it }, contentAlignment = Alignment.Center) {
        Image(
            painter = previewPainter ?: painter,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha },
        )
        if (state is AsyncImagePainter.State.Error) {
            Icon(
                imageVector = MetrolistIcons.Outlined.MusicNote,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        if (!loaded && animate) ContainedLoadingIndicator(Modifier.size(32.dp).alpha(1f - imageAlpha))
    }
}
