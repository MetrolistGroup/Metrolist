@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.fullscreen
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import org.jetbrains.compose.resources.stringResource

@Composable
fun VideoMiniViewer(
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier,
) {
    val visible =
        UiState.showVideoViewer &&
            PlayerState.isVideoAvailable &&
            PlayerState.currentTrack != null &&
            !PlayerState.isExpanded &&
            !UiState.showVideoFullscreen

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 2 },
    ) {
        VideoOnlyCard(colorScheme)
    }
}

@Composable
private fun VideoOnlyCard(colorScheme: ColorScheme) {
    val shape = RoundedCornerShape(12.dp)

    Surface(
        modifier = Modifier.width(360.dp),
        shape = shape,
        color = colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Box(
            modifier =
                Modifier
                    .width(360.dp)
                    .aspectRatio(16f / 9f)
                    .clip(shape)
                    .background(Color.Black),
        ) {
            VideoPreview(
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun VideoPreview(modifier: Modifier = Modifier) {
    var showContextMenu by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current
    val fullscreenLabel = stringResource(Res.string.fullscreen)
    var windowOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier =
            modifier
                .onGloballyPositioned { coordinates ->
                    windowOrigin = coordinates.localToWindow(Offset.Zero)
                }.semantics { contentDescription = fullscreenLabel }
                .clickable(onClickLabel = fullscreenLabel, role = Role.Button) {
                    UiState.showVideoFullscreen = true
                    PlayerState.enterExpanded()
                }.pointerInput(density, windowOrigin) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press) {
                                val change = event.changes.firstOrNull() ?: continue
                                if (event.buttons.isSecondaryPressed) {
                                    menuOffset =
                                        with(density) {
                                            DpOffset(
                                                (windowOrigin.x + change.position.x).toDp(),
                                                (windowOrigin.y + change.position.y).toDp(),
                                            )
                                        }
                                    showContextMenu = true
                                }
                            }
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        VideoRenderer(
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onViewportSizeChanged = PlayerState::setVideoCropTargetSize,
        )

        if (PlayerState.isVideoResolving) {
            LoadingIndicator(
                modifier = Modifier.size(28.dp),
                color = Color.White,
            )
        }

        Row(Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))) {
            IconButton(onClick = {
                menuOffset = DpOffset.Zero
                showContextMenu = true
            }, modifier = Modifier.size(32.dp).navigationPopupSource(), shapes = IconButtonDefaults.shapes()) {
                Icon(MetrolistIcons.Default.MoreVert, stringResource(Res.string.more_options), tint = Color.White)
            }
            IconButton(
                onClick = { UiState.showVideoViewer = false },
                modifier = Modifier.size(32.dp),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(MetrolistIcons.Default.Close, stringResource(Res.string.close), tint = Color.White)
            }
        }

        VideoContextMenu(
            expanded = showContextMenu,
            offset = menuOffset,
            onDismiss = { showContextMenu = false },
        )
    }
}
