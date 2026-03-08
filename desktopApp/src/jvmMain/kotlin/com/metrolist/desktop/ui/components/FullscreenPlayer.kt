@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.close_fullscreen_player
import com.metrolist.shared.generated.resources.exit_expanded_mode
import com.metrolist.shared.generated.resources.lock_queue
import com.metrolist.shared.generated.resources.n_song
import com.metrolist.shared.generated.resources.playback_reconnecting
import com.metrolist.shared.generated.resources.queue
import com.metrolist.shared.generated.resources.recommended
import com.metrolist.shared.generated.resources.removed_from_queue
import com.metrolist.shared.generated.resources.repeat
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.generated.resources.sleep_timer
import com.metrolist.shared.generated.resources.undo
import com.metrolist.shared.generated.resources.unlock_queue
import com.metrolist.shared.generated.resources.up_next
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.settings.PlayerBackgroundStyle
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.LiquidBackground
import com.metrolist.shared.ui.components.lyrics.LyricsContrastScrim
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.player.PlayerGradientBackground
import com.metrolist.shared.ui.theme.getGradientColors
import com.metrolist.shared.ui.utils.ArtworkBlurStyle
import com.metrolist.shared.utils.OsUtil
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun FullscreenPlayerView(
    seedColor: Color,
    toggleFullscreen: () -> Unit,
    onFocus: (() -> Unit)? = null,
    onBackgroundFocusChanged: (Boolean) -> Unit = {},
) {
    val playerBackgroundStyle = Settings.shared.playerBackgroundStyle
    val currentTrack = PlayerState.currentTrack
    val isPlaying = PlayerState.isPlaying
    val fullscreenFocusRequester = remember { FocusRequester() }
    LaunchedEffect(PlayerState.isExpanded) {
        if (PlayerState.isExpanded) fullscreenFocusRequester.requestFocus()
    }

    val colorScheme = MaterialTheme.colorScheme

    fun closeExpandedPlayer() {
        PlayerState.exitExpanded()
    }
    val isSolid = playerBackgroundStyle == PlayerBackgroundStyle.SOLID
    val baseBackgroundColor = if (isSolid) colorScheme.surfaceContainer else Color.Black
    val iconTint = if (isSolid) colorScheme.onSurface.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.8f)
    val captionInsets =
        when {
            PlayerState.isFullscreen || UiState.isNativeFullscreen -> WindowInsets(0)
            OsUtil.isWindows -> LocalCaptionBarInsets.current.only(WindowInsetsSides.Top)
            !Settings.shared.enableSystemWindowFrame -> WindowInsets(top = 32.dp)
            else -> WindowInsets(0)
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(baseBackgroundColor)
                .focusRequester(fullscreenFocusRequester)
                .onFocusChanged { onBackgroundFocusChanged(it.isFocused) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
    ) {
        when (playerBackgroundStyle) {
            PlayerBackgroundStyle.GRADIENT,
            PlayerBackgroundStyle.ANIMATED_GRADIENT,
            -> {
                PlayerGradientBackground(
                    getGradientColors(seedColor),
                    animate = playerBackgroundStyle == PlayerBackgroundStyle.ANIMATED_GRADIENT,
                    active = PlayerState.isPlaying && !UiState.videoFullscreenObscuresPlayer,
                )
            }

            PlayerBackgroundStyle.BLURRED_ARTWORK -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        url = PlayerState.currentArtworkUrl ?: "",
                        artworkPreview = PlayerState.artworkPreview,
                        modifier = Modifier.fillMaxSize(),
                        extractColor = true,
                        contentScale = ContentScale.Crop,
                        saturation = 1.25f,
                        blur =
                            ArtworkBlurStyle(
                                radius = 64.dp,
                                backgroundColor = Color.Black,
                                scale = 1.6f,
                                edgeTreatment = BlurredEdgeTreatment.Unbounded,
                                scrimColor = Color.Black.copy(alpha = 0.5f),
                            ),
                    )
                }
            }

            PlayerBackgroundStyle.LIQUID -> {
                LiquidBackground(
                    artworkUrl = PlayerState.currentArtworkUrl,
                    animate = PlayerState.isPlaying && !UiState.videoFullscreenObscuresPlayer,
                )
            }

            PlayerBackgroundStyle.SOLID -> {
            }
        }
        if (!isSolid) {
            val hasArtwork =
                playerBackgroundStyle == PlayerBackgroundStyle.BLURRED_ARTWORK ||
                    playerBackgroundStyle == PlayerBackgroundStyle.LIQUID
            LyricsContrastScrim(
                backgroundColors =
                    when (playerBackgroundStyle) {
                        PlayerBackgroundStyle.BLURRED_ARTWORK -> listOf(colorScheme.surfaceContainerHigh)
                        PlayerBackgroundStyle.LIQUID -> listOf(baseBackgroundColor)
                        else -> getGradientColors(seedColor)
                    },
                artworkUrl = PlayerState.currentArtworkUrl.takeIf { hasArtwork },
                artworkScrimAlpha = 0.5f,
                artworkSaturation = 1.25f,
                isLiquid = playerBackgroundStyle == PlayerBackgroundStyle.LIQUID,
                visible = UiState.showLyricsDrawer,
            )
        }
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(captionInsets),
        ) {
            val availableWidth = maxWidth
            val widthClass = currentWindowAdaptiveInfoV2().windowSizeClass.minWidthDp
            val preferredWidth =
                if (widthClass < 600) {
                    320.dp
                } else if (widthClass < 840) {
                    360.dp
                } else {
                    420.dp
                }
            val queueDrawerOpen = UiState.showPersistentPlayerDrawer
            SideEffect {
                if (PlayerState.isExpanded) {
                    UiState.playerPanels.canShowBoth = availableWidth - preferredWidth > 900.dp
                }
            }
            val dockQueue = availableWidth >= 760.dp
            val queueDrawerWidth =
                if (queueDrawerOpen) {
                    minOf(preferredWidth, if (dockQueue) availableWidth - 480.dp else availableWidth)
                } else {
                    0.dp
                }
            val animatedQueueWidth by animateDpAsState(
                targetValue = queueDrawerWidth,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                label = "queueWidth",
            )
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().padding(end = if (dockQueue) animatedQueueWidth.coerceAtLeast(0.dp) else 0.dp),
            ) {
                val isWide = maxWidth >= 760.dp

                val content = @Composable {
                    FullscreenContentLayout(
                        currentTrack = currentTrack,
                        isPlaying = isPlaying,
                        availableHeight = maxHeight,
                        isWide = isWide,
                        toggleWindowFullscreen = toggleFullscreen,
                        closeExpandedPlayer = ::closeExpandedPlayer,
                    )
                }

                val closeButton = @Composable {
                    if (isWide) {
                        IconButton(
                            onClick = ::closeExpandedPlayer,
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(24.dp)
                                    .size(48.dp),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                imageVector = MetrolistIcons.Default.Close,
                                contentDescription = stringResource(Res.string.close_fullscreen_player),
                                tint = iconTint,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }

                val backButton = @Composable {
                    if (isWide) {
                        IconButton(
                            onClick = {
                                PlayerState.toggleExpandedMode()
                            },
                            modifier =
                                Modifier
                                    .align(Alignment.TopStart)
                                    .padding(24.dp)
                                    .size(48.dp),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                imageVector = MetrolistIcons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(Res.string.exit_expanded_mode),
                                tint = iconTint,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }

                content()
                if (!OsUtil.isWindows) {
                    if (PlayerState.isFullscreen) {
                        closeButton()
                    } else {
                        backButton()
                    }
                }
            }
            if (queueDrawerOpen && !dockQueue) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colorScheme.scrim.copy(alpha = 0.4f))
                        .clickable { UiState.showPersistentPlayerDrawer = false },
                )
            }
            Row(
                modifier = Modifier.fillMaxHeight().align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedVisibility(
                    visible = queueDrawerOpen,
                    modifier = Modifier.fillMaxHeight(),
                    enter =
                        expandHorizontally(
                            expandFrom = Alignment.End,
                            animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                        ) +
                            slideInHorizontally(
                                initialOffsetX = { it },
                                animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                            ) + fadeIn(),
                    exit =
                        shrinkHorizontally(
                            shrinkTowards = Alignment.End,
                            animationSpec = spring(dampingRatio = 0.88f, stiffness = 450f),
                        ) +
                            slideOutHorizontally(
                                targetOffsetX = { it },
                                animationSpec = spring(dampingRatio = 0.88f, stiffness = 450f),
                            ) + fadeOut(),
                ) {
                    DesktopQueueContent(
                        onDismiss = { UiState.showPersistentPlayerDrawer = false },
                        colorScheme = MaterialTheme.colorScheme,
                        modifier = Modifier.width(animatedQueueWidth.coerceAtLeast(0.dp)).padding(top = 8.dp, end = 8.dp, bottom = 8.dp),
                    )
                }
            }
        }
        if (PlayerState.isReconnecting) {
            Text(
                stringResource(Res.string.playback_reconnecting),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
            )
        }
    }
}
