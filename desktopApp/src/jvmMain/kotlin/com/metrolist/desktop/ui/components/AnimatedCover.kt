package com.metrolist.desktop.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import com.metrolist.shared.playback.VideoFrame
import com.metrolist.shared.playback.VideoPlayer
import com.metrolist.shared.ui.components.LocalVisualWorkActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

@Composable
fun AnimatedCover(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    active: Boolean = true,
    onFrameAvailabilityChanged: (Boolean) -> Unit = {},
) {
    val visible = LocalVisualWorkActive.current
    val latestActive by rememberUpdatedState(active)
    val latestOnFrameAvailabilityChanged by rememberUpdatedState(onFrameAvailabilityChanged)
    var viewport by remember(url) { mutableStateOf(IntSize.Zero) }
    var frame by remember(url) { mutableStateOf<VideoFrame?>(null, neverEqualPolicy()) }
    val player =
        remember(url) {
            VideoPlayer(
                onFrame = {
                    val firstFrame = frame == null
                    frame = it
                    if (firstFrame) latestOnFrameAvailabilityChanged(true)
                },
                onEnded = {
                    frame = null
                    latestOnFrameAvailabilityChanged(false)
                },
                looping = true,
            )
        }

    LaunchedEffect(player, url, visible) {
        val loaded =
            withContext(Dispatchers.IO) {
                player.load(
                    url = url,
                    positionProvider = { 0L },
                )
            }
        if (loaded) {
            player.setVolume(0f)
            combine(snapshotFlow { latestActive }, visible) { playing, shown -> playing && shown }
                .distinctUntilChanged()
                .collect { playing ->
                    if (playing) player.resume() else player.pause()
                }
        }
    }

    LaunchedEffect(player, viewport, contentScale) {
        if (viewport.width > 0 && viewport.height > 0) {
            delay(320)
            player.setTargetSize(viewport.width, viewport.height, fillViewport = contentScale == ContentScale.Crop)
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.dispose()
        }
    }

    VideoRenderer(
        modifier = modifier,
        contentScale = contentScale,
        frameProvider = { frame },
        onViewportSizeChanged = { width, height ->
            viewport = IntSize(width, height)
        },
    )
}
