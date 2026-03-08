/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope
import com.metrolist.desktop.deviceconnect.DesktopDeviceConnect
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.auth.AuthState
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.BrowseState
import com.metrolist.desktop.state.content.LibraryState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.desktop.state.content.PodcastState
import com.metrolist.desktop.state.content.SearchState
import com.metrolist.desktop.state.playback.ListenTogetherState
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.storage.DownloadManager
import com.metrolist.desktop.storage.DownloadState
import com.metrolist.desktop.storage.downloadQueueSummary
import com.metrolist.desktop.ui.layouts.AccountMenu
import com.metrolist.desktop.utils.WindowDragHandle
import com.metrolist.desktop.window.jna.structure.isWindows10OrLater
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cd_account_menu
import com.metrolist.shared.generated.resources.cd_devtools
import com.metrolist.shared.generated.resources.clear_search
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.completed_downloads
import com.metrolist.shared.generated.resources.device_connect_title
import com.metrolist.shared.generated.resources.dismiss
import com.metrolist.shared.generated.resources.download_error_generic
import com.metrolist.shared.generated.resources.downloading
import com.metrolist.shared.generated.resources.downloading_count
import com.metrolist.shared.generated.resources.downloads
import com.metrolist.shared.generated.resources.edit_sidebar
import com.metrolist.shared.generated.resources.errors
import com.metrolist.shared.generated.resources.exit_fullscreen
import com.metrolist.shared.generated.resources.expand
import com.metrolist.shared.generated.resources.fullscreen
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.lyrics
import com.metrolist.shared.generated.resources.menu_repeat_all
import com.metrolist.shared.generated.resources.menu_repeat_off
import com.metrolist.shared.generated.resources.menu_repeat_one
import com.metrolist.shared.generated.resources.menu_shuffle_off
import com.metrolist.shared.generated.resources.menu_shuffle_on
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.generated.resources.queue
import com.metrolist.shared.generated.resources.queued
import com.metrolist.shared.generated.resources.refresh
import com.metrolist.shared.generated.resources.remove_from_search_history
import com.metrolist.shared.generated.resources.repeat
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.generated.resources.search_history_keyboard_hint
import com.metrolist.shared.generated.resources.search_music
import com.metrolist.shared.generated.resources.search_placeholder
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.generated.resources.stop_downloading
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.playlistimport.PlaylistImportProgressIndicator
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.theme.uiEnhanced
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import com.metrolist.shared.utils.OsUtil
import org.jetbrains.compose.resources.stringResource

val LocalCaptionBarInsets = staticCompositionLocalOf { WindowInsets(0) }
internal val DesktopTitleBarHeight = 48.dp

@Composable
fun WindowScope.CustomTitleBar(
    title: String,
    colorScheme: ColorScheme,
    searchText: String,
    onSearchChange: (String) -> Unit,
    onSearchFocusChange: (Boolean) -> Unit,
    onMaximize: () -> Unit,
    requestBackgroundFocus: () -> Unit,
    selectedNavIndex: Int = 0,
    hideSearch: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    var showAccountPanel by remember { mutableStateOf(false) }

    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val isWindowTight = adaptiveInfo.windowSizeClass.minWidthDp < 960
    val searchWidth =
        when {
            isWindowTight -> 280.dp
            else -> 420.dp
        }

    val isMacOS = OsUtil.isMac

    val navItems: @Composable () -> Unit = {
        TopBarRefreshButton(onRefresh)
    }
    val titlebarContent: @Composable () -> Unit = {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(DesktopTitleBarHeight)
                    .background(colorScheme.surfaceContainer)
                    .windowInsetsPadding(LocalCaptionBarInsets.current.only(WindowInsetsSides.Right))
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                if (isMacOS) {
                    Spacer(Modifier.width(12.dp))
                }
                AccountMenuButton(
                    showAccountMenu = showAccountPanel,
                    onToggleAccountMenu = { showAccountPanel = !showAccountPanel },
                    onDismissAccountMenu = { showAccountPanel = false },
                    colorScheme = colorScheme,
                )
                navItems()
                DevtoolsButton(colorScheme)
            }

            Box(
                modifier = Modifier.weight(2f),
                contentAlignment = Alignment.Center,
            ) {
                if (hideSearch) {
                    IconButton(onClick = { UiState.showFloatingSearch = true }, shapes = IconButtonDefaults.shapes()) {
                        Icon(MetrolistIcons.Default.Search, contentDescription = stringResource(Res.string.search))
                    }
                } else {
                    M3ExpressiveSearchBar(
                        searchText = searchText,
                        onSearchChange = onSearchChange,
                        onSearchFocusChange = onSearchFocusChange,
                        requestBackgroundFocus = requestBackgroundFocus,
                        colorScheme = colorScheme,
                        searchWidth = searchWidth,
                    )
                }
            }

            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                PlaylistImportProgressIndicator()
                ExpressiveDownloadIndicatorPill(colorScheme)
            }
        }
    }

    if (isMacOS && !Settings.shared.enableSystemWindowFrame) {
        val trafficLightOffset = if (UiState.isNativeFullscreen) 0.dp else 78.dp
        Row(
            modifier = Modifier.fillMaxWidth().height(DesktopTitleBarHeight).background(colorScheme.surfaceContainer),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (trafficLightOffset > 0.dp) Spacer(Modifier.width(trafficLightOffset))
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                titlebarContent()
            }
        }
    } else if (OsUtil.isLinux && !Settings.shared.enableSystemWindowFrame) {
        WindowDragHandle(
            isMaximized = UiState.isMaximized,
            onRestoreFromMaximized = onMaximize,
            content = titlebarContent,
        )
    } else {
        titlebarContent()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WindowScope.TopBarWithPlayer(
    colorScheme: ColorScheme,
    searchText: String,
    onSearchChange: (String) -> Unit,
    onSearchFocusChange: (Boolean) -> Unit,
    onMaximized: () -> Unit,
    toggleWindowFullscreen: () -> Unit,
    showSearchInTopBar: Boolean = true,
    onRefresh: (() -> Unit)? = null,
) {
    val currentTrack = PlayerState.currentTrack
    val isPlaying = PlayerState.isPlaying
    val deviceConnectState by DesktopDeviceConnect.manager.remote.state
        .collectAsState()
    val swapControls = PlayerState.swapPlayerControls
    val isListenTogetherGuest = ListenTogetherState.isListenTogetherGuest()
    val isMacOS = OsUtil.isMac
    var showAccountPanel by remember { mutableStateOf(false) }
    val navItems: @Composable () -> Unit = {
        TopBarRefreshButton(onRefresh)
    }
    val backInteractionSource = remember { MutableInteractionSource() }
    val nextInteractionSource = remember { MutableInteractionSource() }
    val playPauseInteractionSource = remember { MutableInteractionSource() }

    val adaptiveInfo =
        currentWindowAdaptiveInfoV2()
    val isWindowTight = adaptiveInfo.windowSizeClass.minWidthDp < 960

    val shuffleStateDescription =
        stringResource(if (PlayerState.shuffleEnabled) Res.string.menu_shuffle_on else Res.string.menu_shuffle_off)
    val repeatStateDescription =
        stringResource(
            when (PlayerState.repeatMode) {
                RepeatMode.OFF -> Res.string.menu_repeat_off
                RepeatMode.ALL -> Res.string.menu_repeat_all
                RepeatMode.ONE -> Res.string.menu_repeat_one
            },
        )
    val titlebarContent2: @Composable () -> Unit = {
        val bgColor = colorScheme.surfaceContainer
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(DesktopTitleBarHeight)
                    .background(bgColor)
                    .windowInsetsPadding(LocalCaptionBarInsets.current.only(WindowInsetsSides.Right))
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccountMenuButton(
                    showAccountMenu = showAccountPanel,
                    onToggleAccountMenu = { showAccountPanel = !showAccountPanel },
                    onDismissAccountMenu = { showAccountPanel = false },
                    colorScheme = colorScheme,
                )
                navItems()
                DevtoolsButton(colorScheme)
            }

            if (currentTrack != null) {
                Row(
                    modifier = Modifier.weight(2f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val controlsRow: @Composable () -> Unit = {
                        Row(
                            modifier = Modifier.width(if (isWindowTight) 128.dp else 280.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            if (!isWindowTight) {
                                IconButton(
                                    onClick = { PlayerState.toggleShuffle() },
                                    modifier = Modifier.size(40.dp).semantics { stateDescription = shuffleStateDescription },
                                    shapes = IconButtonDefaults.shapes(),
                                ) {
                                    Icon(
                                        MetrolistIcons.Rounded.Shuffle,
                                        contentDescription = stringResource(Res.string.shuffle),
                                        tint = if (PlayerState.shuffleEnabled) colorScheme.primary else colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }

                                Spacer(Modifier.width(4.dp))
                            }

                            FilledTonalButton(
                                onClick = { PlayerState.skipPrevious() },
                                shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(18.dp)),
                                interactionSource = backInteractionSource,
                                colors =
                                    ButtonDefaults.filledTonalButtonColors(
                                        containerColor = colorScheme.surfaceContainerHighest,
                                        contentColor = colorScheme.onSurface,
                                    ),
                                contentPadding = PaddingValues(horizontal = 0.dp),
                                modifier = Modifier.height(36.dp).width(36.dp),
                            ) {
                                Icon(
                                    MetrolistIcons.Rounded.SkipPrevious,
                                    stringResource(Res.string.previous),
                                    modifier = Modifier.size(18.dp),
                                )
                            }

                            Spacer(Modifier.width(4.dp))

                            FilledTonalButton(
                                onClick = { PlayerState.togglePlay() },
                                shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(18.dp)),
                                interactionSource = playPauseInteractionSource,
                                colors =
                                    ButtonDefaults.filledTonalButtonColors(
                                        containerColor = colorScheme.primary,
                                        contentColor = colorScheme.onPrimary,
                                    ),
                                contentPadding = PaddingValues(horizontal = if (isWindowTight) 8.dp else 16.dp),
                                modifier = Modifier.height(36.dp).weight(1f),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val loadingText = stringResource(Res.string.loading)
                                    if (PlayerState.isPlaybackLoading) {
                                        LoadingIndicator(
                                            modifier =
                                                Modifier.size(18.dp).semantics {
                                                    contentDescription = loadingText
                                                },
                                            color = LocalContentColor.current,
                                        )
                                    } else {
                                        Icon(
                                            if (isPlaying) MetrolistIcons.Rounded.Pause else MetrolistIcons.Rounded.PlayArrow,
                                            if (isWindowTight) {
                                                stringResource(
                                                    if (isPlaying) Res.string.pause else Res.string.play,
                                                )
                                            } else {
                                                null
                                            },
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    if (!isWindowTight && !PlayerState.isPlaybackLoading) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (isPlaying) {
                                                stringResource(Res.string.pause)
                                            } else {
                                                stringResource(Res.string.play)
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.width(4.dp))

                            FilledTonalButton(
                                onClick = { PlayerState.skipNext() },
                                shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(18.dp)),
                                interactionSource = nextInteractionSource,
                                colors =
                                    ButtonDefaults.filledTonalButtonColors(
                                        containerColor = colorScheme.surfaceContainerHighest,
                                        contentColor = colorScheme.onSurface,
                                    ),
                                contentPadding = PaddingValues(horizontal = 0.dp),
                                modifier = Modifier.height(36.dp).width(36.dp),
                            ) {
                                Icon(MetrolistIcons.Rounded.SkipNext, stringResource(Res.string.next), modifier = Modifier.size(18.dp))
                            }

                            if (!isWindowTight) {
                                Spacer(Modifier.width(4.dp))

                                IconButton(
                                    onClick = { PlayerState.cycleRepeatMode() },
                                    modifier = Modifier.size(40.dp).semantics { stateDescription = repeatStateDescription },
                                    shapes = IconButtonDefaults.shapes(),
                                ) {
                                    Icon(
                                        if (PlayerState.repeatMode == RepeatMode.ONE) {
                                            MetrolistIcons.Rounded.RepeatOne
                                        } else {
                                            MetrolistIcons.Rounded.Repeat
                                        },
                                        contentDescription = stringResource(Res.string.repeat),
                                        tint =
                                            if (PlayerState.repeatMode != RepeatMode.OFF) {
                                                colorScheme.primary
                                            } else {
                                                colorScheme.onSurfaceVariant
                                            },
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }

                    val songCard: @Composable () -> Unit = {
                        val expandLabel = stringResource(Res.string.expand)
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = colorScheme.primaryContainer.copy(alpha = 0.9f),
                                ),
                            modifier = Modifier.weight(1.5f),
                        ) {
                            Column {
                                Row(
                                    modifier =
                                        Modifier.padding(
                                            start = 8.dp,
                                            end = 8.dp,
                                            top = 8.dp,
                                            bottom = 4.dp,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    DesktopPlayerVisibility(visible = !PlayerState.isExpanded) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .songThumbSize(
                                                        currentTrack,
                                                        32.dp,
                                                    ).clip(RoundedCornerShape(uiEnhanced(4.dp, 8.dp)))
                                                    .clickable(role = Role.Button, onClick = PlayerState::enterExpanded)
                                                    .semantics { contentDescription = expandLabel },
                                        ) {
                                            AsyncImage(
                                                url = PlayerState.currentArtworkUrl ?: "",
                                                artworkPreview = PlayerState.artworkPreview,
                                                modifier =
                                                    Modifier
                                                        .fillMaxSize()
                                                        .desktopPlayerArtwork(currentTrack.id),
                                            )
                                        }
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(
                                            currentTrack.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = colorScheme.onPrimaryContainer,
                                            modifier =
                                                Modifier.basicMarquee(
                                                    iterations = if (UiState.videoFullscreenObscuresPlayer) 0 else 1,
                                                ),
                                        )
                                        Text(
                                            currentTrack.artists.joinToString { it.displayName },
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                            modifier =
                                                Modifier.basicMarquee(
                                                    iterations = if (UiState.videoFullscreenObscuresPlayer) 0 else 1,
                                                ),
                                        )
                                    }
                                }
                                TopBarPlaybackProgress(colorScheme.onPrimaryContainer)
                            }
                        }
                    }

                    val actionButtons: @Composable () -> Unit = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val isLyricsOpen = UiState.showLyricsDrawer
                            IconButton(
                                onClick = {
                                    LyricsState.toggleLyricsDrawer()
                                },
                                modifier = Modifier.size(40.dp),
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    MetrolistIcons.Outlined.FormatAlignCenter,
                                    contentDescription = stringResource(Res.string.lyrics),
                                    tint = if (isLyricsOpen) colorScheme.primary else colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }

                            val isQueueOpen = UiState.showPersistentPlayerDrawer
                            IconButton(
                                onClick = UiState::togglePersistentPlayerDrawer,
                                modifier = Modifier.size(40.dp),
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    MetrolistIcons.AutoMirrored.Outlined.QueueMusic,
                                    contentDescription = stringResource(Res.string.queue),
                                    tint = if (isQueueOpen) colorScheme.primary else colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }

                            val isDeviceConnectActive =
                                UiState.showDeviceConnectPanel || deviceConnectState.sessionId != null
                            IconButton(
                                onClick = UiState::toggleDeviceConnectPanel,
                                modifier = Modifier.size(40.dp),
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    imageVector =
                                        if (isDeviceConnectActive) {
                                            MetrolistIcons.Filled.Devices
                                        } else {
                                            MetrolistIcons.Outlined.Devices
                                        },
                                    contentDescription = stringResource(Res.string.device_connect_title),
                                    tint =
                                        if (isDeviceConnectActive) {
                                            colorScheme.primary
                                        } else {
                                            colorScheme.onSurfaceVariant
                                        },
                                    modifier = Modifier.size(18.dp),
                                )
                            }

                            IconButton(
                                onClick = toggleWindowFullscreen,
                                modifier = Modifier.size(40.dp),
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    imageVector =
                                        if (UiState.isPlayerExpanded) {
                                            MetrolistIcons.Outlined.FullscreenExit
                                        } else {
                                            MetrolistIcons.Outlined.Fullscreen
                                        },
                                    contentDescription =
                                        if (UiState.isPlayerExpanded) {
                                            stringResource(
                                                Res.string.exit_fullscreen,
                                            )
                                        } else {
                                            stringResource(Res.string.fullscreen)
                                        },
                                    tint = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }

                    if (swapControls) {
                        songCard()
                        if (!isListenTogetherGuest) {
                            controlsRow()
                        }
                        actionButtons()
                    } else {
                        if (!isListenTogetherGuest) {
                            controlsRow()
                        }
                        songCard()
                        Spacer(Modifier.width(4.dp))
                        actionButtons()
                    }
                }
            } else {
                Spacer(Modifier.weight(2f))
            }

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlaylistImportProgressIndicator()
                ExpressiveDownloadIndicatorPill(colorScheme)
            }
        }
    }

    if (isMacOS && !Settings.shared.enableSystemWindowFrame) {
        val trafficLightOffset = if (UiState.isNativeFullscreen) 0.dp else 78.dp
        Row(
            modifier = Modifier.fillMaxWidth().height(DesktopTitleBarHeight).background(colorScheme.surfaceContainer),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (trafficLightOffset > 0.dp) Spacer(Modifier.width(trafficLightOffset))
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                titlebarContent2()
            }
        }
    } else if (OsUtil.isLinux && !Settings.shared.enableSystemWindowFrame) {
        WindowDragHandle(
            isMaximized = UiState.isMaximized,
            onRestoreFromMaximized = onMaximized,
            content = titlebarContent2,
        )
    } else if (OsUtil.isWindows) {
        titlebarContent2()
    } else {
        WindowDraggableArea(content = titlebarContent2)
    }
}

@Composable
fun TopBarRefreshButton(onRefresh: (() -> Unit)?) {
    if (onRefresh == null) return
    IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) {
        Icon(
            MetrolistIcons.Outlined.Refresh,
            contentDescription = stringResource(Res.string.refresh),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DevtoolsButton(colorScheme: ColorScheme) {
    if (!Settings.shared.devtoolsUnlocked) return

    IconButton(
        onClick = {
            UiState.showThemeSettings = false
            UiState.openDevtoolsSettingsRequested = true
            UiState.showSettings = true
        },
        modifier = Modifier.size(40.dp),
        shapes = IconButtonDefaults.shapes(),
    ) {
        Icon(
            MetrolistIcons.Outlined.Adb,
            contentDescription = stringResource(Res.string.cd_devtools),
            modifier = Modifier.size(20.dp),
            tint = colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccountMenuButton(
    showAccountMenu: Boolean,
    onToggleAccountMenu: () -> Unit,
    onDismissAccountMenu: () -> Unit,
    colorScheme: ColorScheme,
) {
    Box {
        IconButton(
            onClick = onToggleAccountMenu,
            modifier = Modifier.size(40.dp).navigationPopupSource(),
            shapes = IconButtonDefaults.shapes(),
        ) {
            val profilePicUrl = AuthState.profilePicUrl
            if (AuthState.isSignedIn && !profilePicUrl.isNullOrBlank()) {
                AsyncImage(
                    url = profilePicUrl,
                    modifier = Modifier.size(28.dp).clip(CircleShape),
                )
            } else {
                Icon(
                    MetrolistIcons.Outlined.AccountCircle,
                    contentDescription = stringResource(Res.string.cd_account_menu),
                    modifier = Modifier.size(24.dp),
                    tint = if (showAccountMenu) colorScheme.primary else colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showAccountMenu) {
            AccountMenu(
                onDismiss = onDismissAccountMenu,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun M3ExpressiveSearchBar(
    searchText: String,
    onSearchChange: (String) -> Unit,
    onSearchFocusChange: (Boolean) -> Unit,
    colorScheme: ColorScheme,
    requestBackgroundFocus: () -> Unit,
    modifier: Modifier = Modifier,
    searchWidth: Dp = 420.dp,
) {
    val focusRequester = remember { FocusRequester() }
    var isSearchFocused by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    val dropdownScrollState = rememberScrollState()
    var submittedQuery by remember { mutableStateOf<String?>(null) }
    val textFieldState = rememberTextFieldState(initialText = searchText)
    val latestSearchText by rememberUpdatedState(searchText)
    val latestOnSearchChange by rememberUpdatedState(onSearchChange)
    LaunchedEffect(searchText) {
        if (textFieldState.text.toString() != searchText) {
            textFieldState.setTextAndPlaceCursorAtEnd(searchText)
        }
    }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect { text ->
            if (text != latestSearchText) {
                submittedQuery = null
                latestOnSearchChange(text)
            }
        }
    }

    fun submitSearch(query: String) {
        if (query.isBlank()) return
        PlayerState.exitExpanded()
        submittedQuery = query
        onSearchChange(query)
        if (SearchState.search(query)) {
            submittedQuery = ""
            onSearchChange("")
        }
        isSearchFocused = false
        requestBackgroundFocus()
    }

    DisposableEffect(Unit) {
        UiState.isInlineSearchAvailable = true
        onDispose { UiState.isInlineSearchAvailable = false }
    }

    LaunchedEffect(UiState.focusInlineSearchTrigger) {
        if (UiState.focusInlineSearchTrigger > 0) {
            if (isSearchFocused) {
                isSearchFocused = false
                requestBackgroundFocus()
            } else {
                focusRequester.requestFocus()
            }
        }
    }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            val targetPx = (selectedIndex * 40).coerceAtMost(dropdownScrollState.maxValue)
            dropdownScrollState.animateScrollTo(targetPx)
        } else {
            dropdownScrollState.animateScrollTo(0)
        }
    }

    LaunchedEffect(searchText, submittedQuery) {
        selectedIndex = -1
        if (searchText == submittedQuery) return@LaunchedEffect
        SearchState.onQueryChange(searchText)
        if (searchText.isEmpty()) SearchState.reset()
    }

    LaunchedEffect(isSearchFocused) {
        if (isSearchFocused) {
            if (searchText.isEmpty()) SearchState.refreshSearchHistory()
        } else {
            selectedIndex = -1
        }
    }

    val animatedWidth by animateDpAsState(
        targetValue = if (isSearchFocused) searchWidth + 36.dp else searchWidth,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 350f),
    )
    val containerColor by animateColorAsState(
        targetValue = if (isSearchFocused) colorScheme.surfaceContainerHigh else colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
    )
    val borderColor by animateColorAsState(
        targetValue =
            if (isSearchFocused) {
                colorScheme.outlineVariant.copy(
                    alpha = 0.5f,
                )
            } else {
                colorScheme.outlineVariant.copy(alpha = 0.25f)
            },
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
    )

    val shortcutBadgeText = if (OsUtil.isMac) "⌘K" else "Ctrl+K"

    val searchMusicDesc = stringResource(Res.string.search_music)

    Box(
        modifier =
            modifier
                .widthIn(max = animatedWidth)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                        },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            state = textFieldState,
            enabled = !PlayerState.isExpanded,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colorScheme.onSurface),
            modifier =
                Modifier
                    .focusRequester(focusRequester)
                    .fillMaxWidth()
                    .height(36.dp)
                    .semantics { contentDescription = searchMusicDesc }
                    .background(containerColor, CircleShape)
                    .border(BorderStroke(1.dp, borderColor), CircleShape)
                    .onFocusChanged {
                        if (Settings.shared.floatingSearch && it.isFocused) {
                            // clearFocus() during dismissal can refocus this field before the overlay closes.
                            if (!UiState.showFloatingSearch) {
                                UiState.showFloatingSearch = true
                            }
                            requestBackgroundFocus()
                        } else {
                            isSearchFocused = it.isFocused
                            onSearchFocusChange(it.isFocused)
                            UiState.isSearchFocused = it.isFocused
                        }
                    }.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            val history = SearchState.searchHistory
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (isSearchFocused && searchText.isEmpty() && history.isNotEmpty()) {
                                        selectedIndex = (selectedIndex + 1).coerceAtMost(history.size - 1)
                                        true
                                    } else {
                                        false
                                    }
                                }

                                Key.DirectionUp -> {
                                    if (isSearchFocused && searchText.isEmpty() && history.isNotEmpty()) {
                                        selectedIndex = (selectedIndex - 1).coerceAtLeast(-1)
                                        true
                                    } else {
                                        false
                                    }
                                }

                                Key.Delete -> {
                                    if (isSearchFocused && searchText.isEmpty() && selectedIndex in history.indices) {
                                        SearchState.removeSearchQuery(history[selectedIndex])
                                        selectedIndex = (selectedIndex - 1).coerceAtLeast(-1)
                                        true
                                    } else {
                                        false
                                    }
                                }

                                Key.Enter -> {
                                    if (isSearchFocused) {
                                        val targetQuery =
                                            if (searchText.isEmpty() &&
                                                selectedIndex in history.indices
                                            ) {
                                                history[selectedIndex]
                                            } else {
                                                textFieldState.text.toString()
                                            }
                                        submitSearch(targetQuery)
                                        true
                                    } else {
                                        false
                                    }
                                }

                                Key.Escape -> {
                                    if (isSearchFocused) {
                                        isSearchFocused = false
                                        requestBackgroundFocus()
                                        true
                                    } else {
                                        false
                                    }
                                }

                                else -> {
                                    false
                                }
                            }
                        } else {
                            false
                        }
                    },
            lineLimits = TextFieldLineLimits.SingleLine,
            cursorBrush = SolidColor(colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            onKeyboardAction = { submitSearch(textFieldState.text.toString()) },
            decorator = { innerTextField ->
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        MetrolistIcons.Default.Search,
                        contentDescription = stringResource(Res.string.search),
                        modifier = Modifier.size(18.dp),
                        tint = if (isSearchFocused) colorScheme.primary else colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (searchText.isEmpty()) {
                            Text(
                                stringResource(Res.string.search_placeholder),
                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        innerTextField()
                    }
                    if (searchText.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                onSearchChange("")
                                SearchState.reset()
                            },
                            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                MetrolistIcons.Default.Close,
                                contentDescription = stringResource(Res.string.clear_search),
                                modifier = Modifier.size(16.dp),
                                tint = colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 6.dp),
                        ) {
                            Text(
                                text = shortcutBadgeText,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, fontSize = 11.sp),
                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            },
        )

        DropdownMenu(
            expanded = !PlayerState.isExpanded && isSearchFocused && SearchState.searchHistory.isNotEmpty() && searchText.isEmpty(),
            onDismissRequest = {
                isSearchFocused = false
                requestBackgroundFocus()
            },
            offset = DpOffset(0.dp, 4.dp),
            properties =
                androidx.compose.ui.window
                    .PopupProperties(focusable = false),
            modifier = Modifier.width(animatedWidth).background(colorScheme.surfaceContainerHigh),
        ) {
            Box(
                modifier =
                    Modifier
                        .heightIn(max = 200.dp)
                        .verticalScrollWithDrag(dropdownScrollState),
            ) {
                Column {
                    Text(
                        stringResource(Res.string.search_history_keyboard_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    SearchState.searchHistory.forEachIndexed { index, query ->
                        val isSelected = index == selectedIndex
                        val bg = if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent
                        val contentColor = if (isSelected) colorScheme.onPrimaryContainer else colorScheme.onSurface
                        val iconColor = if (isSelected) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant

                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bg)
                                    .clickable {
                                        submitSearch(query)
                                    }.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    MetrolistIcons.Default.History,
                                    null,
                                    modifier = Modifier.size(18.dp),
                                    tint = iconColor,
                                )
                                Text(
                                    query,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            IconButton(
                                onClick = {
                                    SearchState.removeSearchQuery(query)
                                },
                                modifier = Modifier.size(32.dp),
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    MetrolistIcons.Default.Close,
                                    stringResource(Res.string.remove_from_search_history, query),
                                    modifier = Modifier.size(16.dp),
                                    tint = iconColor.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExpressiveDownloadIndicatorPill(
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier,
) {
    val states = LibraryState.downloadStates
    val progresses = LibraryState.downloadProgress
    val pendingDownloads =
        remember(states) {
            states.filter { it.value == DownloadState.DOWNLOADING || it.value == DownloadState.QUEUED || it.value == DownloadState.FAILED }
        }
    val pendingDownloadEntries = remember(pendingDownloads) { pendingDownloads.entries.toList() }
    val isVisible = pendingDownloads.isNotEmpty()
    val summary = downloadQueueSummary(pendingDownloads, progresses, LibraryState.sessionCompletedCount)

    val animatedProgress by animateFloatAsState(
        targetValue = summary.progress,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
    )

    var showMenu by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = isVisible,
        enter =
            fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                expandHorizontally(
                    expandFrom = Alignment.End,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                ) +
                scaleIn(
                    initialScale = 0.8f,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                ),
        exit =
            fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                shrinkHorizontally(
                    shrinkTowards = Alignment.End,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                ) +
                scaleOut(
                    targetScale = 0.8f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                ),
    ) {
        Box(modifier = modifier) {
            Surface(
                onClick = { showMenu = !showMenu },
                shape = RoundedCornerShape(20.dp),
                color = colorScheme.primaryContainer.copy(alpha = 0.85f),
                tonalElevation = 2.dp,
                modifier = Modifier.height(32.dp).padding(horizontal = 4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 10.dp),
                ) {
                    Icon(
                        MetrolistIcons.Outlined.Download,
                        contentDescription = stringResource(Res.string.downloads),
                        tint = if (summary.failed > 0) colorScheme.error else colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(14.dp),
                    )
                    LinearWavyProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.width(48.dp),
                        color = colorScheme.primary,
                        trackColor = colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                    )
                    Text(
                        text = "${summary.completed}/${summary.total}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                        color = colorScheme.onPrimaryContainer,
                    )
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier =
                    Modifier
                        .width(300.dp)
                        .heightIn(max = 320.dp)
                        .background(colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                        .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(12.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(Res.string.downloads),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = colorScheme.onSurface,
                        )
                        IconButton(
                            onClick = { showMenu = false },
                            modifier = Modifier.size(24.dp),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                MetrolistIcons.Default.Close,
                                contentDescription = stringResource(Res.string.close),
                                tint = colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }

                    Text(
                        stringResource(Res.string.downloading_count, summary.active),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                    val counts = listOf(Res.string.completed_downloads to summary.completed, Res.string.errors to summary.failed)
                    counts.forEach { (label, count) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = colorScheme.onSurfaceVariant)
                            Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = colorScheme.onSurfaceVariant)
                        }
                    }

                    DragScrollableColumn(
                        // DropdownMenu queries intrinsic height while measuring width; lazy lists require an explicit height.
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(pendingDownloadEntries, key = { it.key }) { entry ->
                            val songId = entry.key
                            val state = entry.value
                            val songTitle = LibraryState.downloadTitles[songId] ?: songId
                            val progress = progresses[songId] ?: 0f
                            val animatedItemProgress by animateFloatAsState(
                                targetValue = progress,
                                animationSpec = spring(stiffness = Spring.StiffnessLow),
                            )
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colorScheme.surfaceContainer)
                                        .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        songTitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (state != DownloadState.FAILED) {
                                        Text(
                                            if (state == DownloadState.QUEUED) {
                                                stringResource(Res.string.queued)
                                            } else {
                                                "${(animatedItemProgress * 100).toInt()}%"
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = colorScheme.primary,
                                        )
                                    }
                                }
                                if (state == DownloadState.FAILED) {
                                    Text(
                                        stringResource(Res.string.download_error_generic),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colorScheme.error,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            onClick = { DownloadManager.retryFailedDownload(songId) },
                                            shapes = ButtonDefaults.shapes(),
                                        ) {
                                            Text(stringResource(Res.string.retry))
                                        }
                                        TextButton(
                                            onClick = { DownloadManager.dismissFailedDownload(songId) },
                                            shapes = ButtonDefaults.shapes(),
                                        ) {
                                            Text(stringResource(Res.string.dismiss))
                                        }
                                    }
                                }
                                if (state == DownloadState.DOWNLOADING) {
                                    LinearWavyProgressIndicator(
                                        progress = { animatedItemProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = colorScheme.primary,
                                        trackColor = colorScheme.primary.copy(alpha = 0.2f),
                                    )
                                }
                            }
                        }
                    }

                    if (summary.active > 0) {
                        Surface(
                            onClick = {
                                DownloadManager.stopActiveDownloads()
                                showMenu = false
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = colorScheme.errorContainer.copy(alpha = 0.85f),
                            modifier = Modifier.fillMaxWidth().height(34.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    MetrolistIcons.Default.Close,
                                    contentDescription = null,
                                    tint = colorScheme.onErrorContainer,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(Res.string.stop_downloading),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBarPlaybackProgress(color: Color) {
    if (UiState.videoFullscreenObscuresPlayer) return
    val duration = PlayerState.playbackDuration.collectAsState()
    var widthPx by remember { mutableIntStateOf(0) }
    // Throttled to one step per pixel instead of redrawing the window on every position sample.
    val position = rememberPlayerProgressPosition(duration.value, widthPx, isLive = false)
    Canvas(Modifier.fillMaxWidth().height(3.dp).onSizeChanged { widthPx = it.width }) {
        val fraction = if (duration.value > 0L) position.longValue.toFloat() / duration.value else 0f
        val progress = fraction.coerceIn(0f, if (PlayerState.isCrossfading) 0.995f else 1f)
        drawRect(color.copy(alpha = 0.2f))
        drawRect(
            color,
            topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width * (1f - progress) else 0f, 0f),
            size = Size(size.width * progress, size.height),
        )
    }
}
