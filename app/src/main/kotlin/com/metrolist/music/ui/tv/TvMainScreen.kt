/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import android.app.Activity
import android.view.KeyEvent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import coil3.compose.AsyncImage
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.YtmSyncKey
import com.metrolist.music.extensions.togglePlayPause
import com.metrolist.music.ui.component.PlayingIndicator
import com.metrolist.music.ui.screens.Screens
import com.metrolist.music.ui.screens.navigationBuilder
import com.metrolist.music.ui.screens.settings.NavigationTab
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.AccountSettingsViewModel
import com.metrolist.music.viewmodels.HomeViewModel
import kotlinx.coroutines.launch

/**
 * Root TV layout: Spotify TV Top Navigation Bar + Main content viewport + Spotify TV Now-Playing Bottom Dock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvMainScreen(
    navController: NavHostController,
    navigationItems: List<Screens>,
    pureBlack: Boolean,
    defaultOpenTab: NavigationTab,
    tabOpenedFromShortcut: NavigationTab?,
    latestVersionName: String,
    snackbarHostState: SnackbarHostState,
) {
    val activity = LocalContext.current as Activity
    val currentBackStack by navController.currentBackStack.collectAsState()
    val currentRoute = currentBackStack.lastOrNull()?.destination?.route

    val onNavItemClick: (Screens, Boolean) -> Unit = remember(navController) {
        { screen, isSelected ->
            if (!isSelected) {
                navController.navigate(screen.route) {
                    popUpTo(navController.graph.startDestinationId) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    val isFullScreenPlayer = currentRoute == "tv_player"
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (pureBlack) TvColors.BackgroundPureBlack else TvColors.BackgroundDark)
    ) {
        // ── Spotify TV Style Top Horizontal Navigation Bar (Hidden in Full Player) ──
        if (!isFullScreenPlayer) {
            TvTopNavigationBar(
                navigationItems = navigationItems,
                currentRoute = currentRoute,
                onNavItemClick = onNavItemClick,
                pureBlack = pureBlack,
                onSettingsClick = {
                    navController.navigate("tv_settings") { launchSingleTop = true }
                },
                onPlayerClick = {
                    navController.navigate("tv_player") { launchSingleTop = true }
                },
            )
        }

        // ── Main Content Area ────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f)) {
            NavHost(
                navController = navController,
                startDestination = when (tabOpenedFromShortcut ?: defaultOpenTab) {
                    NavigationTab.HOME -> Screens.Home
                    NavigationTab.LIBRARY -> Screens.Library
                    else -> Screens.Home
                }.route,
                enterTransition = { fadeIn(tween(200)) },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(200)) },
                popExitTransition = { fadeOut(tween(200)) },
                modifier = Modifier.fillMaxSize(),
            ) {
                // TV-Native Playlist & Album Detail Screens
                composable(
                    route = "local_playlist/{playlistId}",
                    arguments = listOf(navArgument("playlistId") { type = NavType.LongType })
                ) { backStackEntry ->
                    val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: 0L
                    TvPlaylistScreen(
                        playlistId = playlistId,
                        navController = navController
                    )
                }

                composable(
                    route = "auto_playlist/{playlistType}",
                    arguments = listOf(navArgument("playlistType") { type = NavType.StringType })
                ) { backStackEntry ->
                    val playlistType = backStackEntry.arguments?.getString("playlistType") ?: "liked"
                    TvPlaylistScreen(
                        autoPlaylistType = playlistType,
                        navController = navController
                    )
                }

                composable(
                    route = "online_playlist/{playlistId}",
                    arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
                ) { backStackEntry ->
                    val playlistId = backStackEntry.arguments?.getString("playlistId") ?: ""
                    TvPlaylistScreen(
                        onlineBrowseId = playlistId,
                        navController = navController
                    )
                }

                composable(
                    route = "album/{albumId}",
                    arguments = listOf(navArgument("albumId") { type = NavType.StringType })
                ) { backStackEntry ->
                    val albumId = backStackEntry.arguments?.getString("albumId") ?: ""
                    TvPlaylistScreen(
                        albumId = albumId,
                        navController = navController
                    )
                }

                // Mobile navigation graph components for nested sub-routes
                navigationBuilder(
                    navController = navController,
                    scrollBehavior = scrollBehavior,
                    latestVersionName = latestVersionName,
                    activity = activity,
                    snackbarHostState = snackbarHostState,
                )

                // Spotify TV style primary screens
                composable(Screens.Home.route) {
                    TvHomeScreen(navController = navController)
                }

                composable(Screens.Search.route) {
                    TvSearchScreen(navController = navController)
                }

                composable(Screens.Library.route) {
                    TvLibraryScreen(navController = navController)
                }

                // Spotify TV style full-screen player
                composable("tv_player") {
                    TvPlayerScreen(navController = navController)
                }

                // TV Queue Screen
                composable("tv_queue") {
                    TvQueueScreen(navController = navController)
                }

                // TV Settings / Account screen
                composable("tv_settings") {
                    TvAccountMenu(navController = navController, latestVersionName = latestVersionName)
                }
            }
        }

        // ── Spotify TV Floating/Docked Now-Playing Bar (Hidden in Full Player) ──
        if (!isFullScreenPlayer) {
            TvNowPlayingBar(
                onOpenPlayer = {
                    navController.navigate("tv_player") {
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}

/**
 * Spotify TV style horizontal top navigation bar.
 */
@Composable
private fun TvTopNavigationBar(
    navigationItems: List<Screens>,
    currentRoute: String?,
    onNavItemClick: (Screens, Boolean) -> Unit,
    pureBlack: Boolean,
    onSettingsClick: () -> Unit,
    onPlayerClick: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current
    val isPlaying by (playerConnection?.isPlaying?.collectAsState() ?: remember { mutableStateOf(false) })
    val mediaMetadata by (playerConnection?.mediaMetadata?.collectAsState() ?: remember { mutableStateOf(null) })

    val isSettingsSelected = currentRoute == "tv_settings" ||
        currentRoute?.startsWith("settings") == true ||
        currentRoute?.startsWith("account") == true
    val isPlayerSelected = currentRoute == "tv_player"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(if (pureBlack) Color.Black else TvColors.BackgroundDark)
            .padding(horizontal = 40.dp)
    ) {
        // App Logo
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(TvColors.SpotifyGreen.copy(alpha = 0.15f))
        ) {
            Icon(
                painter = painterResource(R.drawable.music_note),
                contentDescription = null,
                tint = TvColors.SpotifyGreenBright,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(Modifier.width(28.dp))

        // Navigation Tabs (Home, Search, Library)
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navigationItems.forEach { screen ->
                val isSelected = currentRoute == screen.route || currentRoute?.startsWith("${screen.route}/") == true
                TvTopNavItem(
                    title = stringResource(screen.titleId),
                    iconRes = if (isSelected) screen.iconIdActive else screen.iconIdInactive,
                    isSelected = isSelected,
                    onClick = { onNavItemClick(screen, isSelected) }
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // Now Playing shortcut in Top Bar
        if (mediaMetadata != null) {
            TvTopNavItem(
                title = stringResource(R.string.player),
                iconRes = R.drawable.play,
                isSelected = isPlayerSelected,
                isPlayingIndicator = isPlaying,
                onClick = onPlayerClick
            )
            Spacer(Modifier.width(16.dp))
        }

        // Settings / Account
        TvTopNavItem(
            title = stringResource(R.string.settings),
            iconRes = R.drawable.settings,
            isSelected = isSettingsSelected,
            onClick = onSettingsClick
        )
    }
}

/**
 * Focus-aware Top Nav Item chip
 */
@Composable
private fun TvTopNavItem(
    title: String,
    iconRes: Int,
    isSelected: Boolean,
    isPlayingIndicator: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    isFocused -> Color.White
                    isSelected -> TvColors.CardBackgroundElevated
                    else -> Color.Transparent
                }
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(20.dp)
            )
            .focusable(interactionSource = interactionSource)
            .onKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onClick()
                    true
                } else false
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (isPlayingIndicator) {
            PlayingIndicator(
                color = if (isFocused) Color.Black else TvColors.SpotifyGreenBright,
                modifier = Modifier.height(16.dp),
                bars = 3,
                barWidth = 3.dp,
                cornerRadius = 2.dp
            )
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = title,
                tint = when {
                    isFocused -> Color.Black
                    isSelected -> TvColors.SpotifyGreenBright
                    else -> TvColors.TextSecondary
                },
                modifier = Modifier.size(20.dp)
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
            color = when {
                isFocused -> Color.Black
                isSelected -> Color.White
                else -> TvColors.TextSecondary
            }
        )
    }
}

/**
 * Spotify TV style bottom playback dock bar.
 */
@Composable
private fun TvNowPlayingBar(
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()

    // Hide bar if nothing is queued/loaded
    if (mediaMetadata == null) return

    val trackInteractionSource = remember { MutableInteractionSource() }
    val isTrackFocused by trackInteractionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(82.dp)
            .background(TvColors.CardBackground)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.08f)
            )
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Interactive Track Info Card (Focusable)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isTrackFocused) TvColors.CardBackgroundElevated else Color.Transparent)
                .border(
                    width = if (isTrackFocused) 2.dp else 0.dp,
                    color = if (isTrackFocused) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(10.dp)
                )
                .focusable(interactionSource = trackInteractionSource)
                .onKeyEvent {
                    if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                        (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)) {
                        onOpenPlayer()
                        true
                    } else false
                }
                .clickable(onClick = onOpenPlayer)
                .padding(6.dp)
        ) {
            // Thumbnail with playing overlay
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                AsyncImage(
                    model = mediaMetadata?.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        PlayingIndicator(
                            color = TvColors.SpotifyGreenBright,
                            modifier = Modifier.height(16.dp),
                            bars = 3,
                            barWidth = 3.dp,
                            cornerRadius = 2.dp
                        )
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = mediaMetadata?.title ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isTrackFocused) TvColors.SpotifyGreenBright else TvColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(),
                )
                Spacer(Modifier.height(2.dp))
                mediaMetadata?.artists?.joinToString { it.name }?.let { artists ->
                    Text(
                        text = artists,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TvColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.width(16.dp))

        // Transport Controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TvDockIconButton(
                iconRes = R.drawable.skip_previous,
                contentDescription = "Previous",
                enabled = canSkipPrevious,
                onClick = { playerConnection.player.seekToPreviousMediaItem() }
            )

            // Play / Pause Circle
            TvDockPlayPauseButton(
                isPlaying = isPlaying,
                onClick = { playerConnection.player.togglePlayPause() }
            )

            TvDockIconButton(
                iconRes = R.drawable.skip_next,
                contentDescription = "Next",
                enabled = canSkipNext,
                onClick = { playerConnection.player.seekToNextMediaItem() }
            )

            Spacer(Modifier.width(8.dp))

            // Expand to Full Player
            TvDockIconButton(
                iconRes = R.drawable.fullscreen,
                contentDescription = "Expand Full Player",
                tint = TvColors.SpotifyGreenBright,
                onClick = onOpenPlayer
            )
        }
    }
}

@Composable
private fun TvDockPlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (isFocused) TvColors.SpotifyGreenBright else Color.White)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = CircleShape
            )
            .focusable(interactionSource = interactionSource)
            .onKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onClick()
                    true
                } else false
            }
            .clickable(onClick = onClick)
    ) {
        Icon(
            painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = Color.Black,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun TvDockIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Color.White
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (isFocused) Color.White else TvColors.OverlayLight)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = CircleShape
            )
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .onKeyEvent {
                if (enabled && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onClick()
                    true
                } else false
            }
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = when {
                !enabled -> TvColors.TextTertiary
                isFocused -> Color.Black
                else -> tint
            },
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * TV-native account/settings menu with TV focus control.
 */
@Composable
private fun TvAccountMenu(navController: NavController, latestVersionName: String) {
    val (innerTubeCookie, onInnerTubeCookieChange) = rememberPreference(InnerTubeCookieKey, "")
    val (ytmSync, onYtmSyncChange) = rememberPreference(YtmSyncKey, true)

    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }

    val homeViewModel: HomeViewModel = hiltViewModel()
    val accountSettingsViewModel: AccountSettingsViewModel = hiltViewModel()
    val accountName by homeViewModel.accountName.collectAsState()
    val accountImageUrl by homeViewModel.accountImageUrl.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val focusRequesters = remember { List(4) { FocusRequester() } }
    var focusedIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        focusRequesters[0].requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.BackgroundDark)
            .padding(horizontal = 80.dp, vertical = 48.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TvColors.TextPrimary,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
            Spacer(Modifier.height(24.dp))

            // ── Login / Account row ────────────────────────────────────────────
            TvMenuRow(
                focusRequester = focusRequesters[0],
                isFocused = focusedIndex == 0,
                onFocused = { focusedIndex = 0 },
                onClick = {
                    if (isLoggedIn) navController.navigate("account")
                    else navController.navigate("login")
                },
            ) {
                if (isLoggedIn && accountImageUrl != null) {
                    AsyncImage(
                        model = accountImageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(CircleShape),
                    )
                    Spacer(Modifier.width(16.dp))
                } else {
                    Icon(
                        painter = painterResource(R.drawable.login),
                        contentDescription = null,
                        tint = TvColors.SpotifyGreenBright,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (isLoggedIn) (accountName.ifEmpty { stringResource(R.string.account) })
                               else stringResource(R.string.login),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TvColors.TextPrimary
                    )
                    if (!isLoggedIn) {
                        Text(
                            text = stringResource(R.string.login),
                            style = MaterialTheme.typography.bodySmall,
                            color = TvColors.TextSecondary,
                        )
                    }
                }
                if (isLoggedIn) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            scope.launch {
                                accountSettingsViewModel.logoutKeepData(context, onInnerTubeCookieChange)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.action_logout))
                    }
                }
            }

            // ── Sync row (only when logged in) ────────────────────────────────
            if (isLoggedIn) {
                Spacer(Modifier.height(12.dp))
                TvMenuRow(
                    focusRequester = focusRequesters[1],
                    isFocused = focusedIndex == 1,
                    onFocused = { focusedIndex = 1 },
                    onClick = { onYtmSyncChange(!ytmSync) },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.cached),
                        contentDescription = null,
                        tint = TvColors.SpotifyGreenBright,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.yt_sync),
                        style = MaterialTheme.typography.titleMedium,
                        color = TvColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = ytmSync,
                        onCheckedChange = onYtmSyncChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = TvColors.SpotifyGreenBright
                        )
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Integrations row ──────────────────────────────────────────────
            TvMenuRow(
                focusRequester = focusRequesters[2],
                isFocused = focusedIndex == 2,
                onFocused = { focusedIndex = 2 },
                onClick = { navController.navigate("settings/integrations") },
            ) {
                Icon(
                    painter = painterResource(R.drawable.integration),
                    contentDescription = null,
                    tint = TvColors.SpotifyGreenBright,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.integrations),
                    style = MaterialTheme.typography.titleMedium,
                    color = TvColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = painterResource(R.drawable.navigate_next),
                    contentDescription = null,
                    tint = TvColors.TextSecondary,
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Settings row ──────────────────────────────────────────────────
            TvMenuRow(
                focusRequester = focusRequesters[3],
                isFocused = focusedIndex == 3,
                onFocused = { focusedIndex = 3 },
                onClick = { navController.navigate("settings") },
            ) {
                Icon(
                    painter = painterResource(R.drawable.settings),
                    contentDescription = null,
                    tint = TvColors.SpotifyGreenBright,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.settings),
                    style = MaterialTheme.typography.titleMedium,
                    color = TvColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = painterResource(R.drawable.navigate_next),
                    contentDescription = null,
                    tint = TvColors.TextSecondary,
                )
            }
        }
    }
}

/** Single focusable row in the TV account menu with Spotify TV styling */
@Composable
private fun TvMenuRow(
    focusRequester: FocusRequester,
    isFocused: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isFocused) TvColors.OverlayFocused
                else TvColors.CardBackground
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White.copy(alpha = 0.8f) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        content = content,
    )
}
