@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.HostDefaultKey
import androidx.compose.runtime.HostDefaultProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LocalHostDefaultProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.scene.NavigationBackHandler
import androidx.navigation3.scene.rememberNavigationEventState
import androidx.navigation3.scene.rememberSceneState
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationEventDispatcherOwnerHostDefaultKey
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.metrolist.android.App
import com.metrolist.android.BuildConfig
import com.metrolist.android.LocalListenTogetherManager
import com.metrolist.android.LocalMiniPlayerBottomInset
import com.metrolist.android.LocalPlayerExpanded
import com.metrolist.android.LocalPlayerExpandedOrExpanding
import com.metrolist.android.LocalPlaylistAdder
import com.metrolist.android.LocalStartupPresentation
import com.metrolist.android.MainActivity
import com.metrolist.android.deviceconnect.AndroidDeviceConnect
import com.metrolist.android.extensions.findActivity
import com.metrolist.android.listentogether.AndroidListenTogetherManager
import com.metrolist.android.locale.AppLocale
import com.metrolist.android.playback.AndroidPlaybackController
import com.metrolist.android.playback.ListenTogetherPlaybackRequest
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.playback.PlaybackSnackbarEvents
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.android.playback.SongDownloadManager
import com.metrolist.android.playback.isRunnable
import com.metrolist.android.playback.resolveIncomingPlaybackSong
import com.metrolist.android.playback.voiceSearchRequest
import com.metrolist.android.ui.component.BottomSheetMenu
import com.metrolist.android.ui.component.FloatingTopAppBar
import com.metrolist.android.ui.component.LocalMenuState
import com.metrolist.android.ui.component.MenuState
import com.metrolist.android.ui.component.TabTopBarHeight
import com.metrolist.android.ui.component.TopBarIconButton
import com.metrolist.android.ui.component.floatingBarCircle
import com.metrolist.android.ui.components.AccountMenu
import com.metrolist.android.ui.components.DesktopCaptionBar
import com.metrolist.android.ui.components.DesktopMiniPlayerHeight
import com.metrolist.android.ui.components.MiniPlayer
import com.metrolist.android.ui.components.calculateMiniPlayerInsets
import com.metrolist.android.ui.components.shouldUseDesktopCaption
import com.metrolist.android.ui.components.shouldUseDesktopMiniPlayer
import com.metrolist.android.ui.player.AndroidPlayerPlatform
import com.metrolist.android.ui.player.MusicVideoSurface
import com.metrolist.android.ui.player.PictureInPictureHelper
import com.metrolist.android.ui.screens.AboutArtistScreen
import com.metrolist.android.ui.screens.AlbumScreen
import com.metrolist.android.ui.screens.ArtistScreenHost
import com.metrolist.android.ui.screens.AutoPlaylistScreen
import com.metrolist.android.ui.screens.BrowseScreen
import com.metrolist.android.ui.screens.DownloadsScreen
import com.metrolist.android.ui.screens.HistoryScreen
import com.metrolist.android.ui.screens.HomeScreen
import com.metrolist.android.ui.screens.HomeSectionScreen
import com.metrolist.android.ui.screens.IntegrationsScreen
import com.metrolist.android.ui.screens.LibraryScreen
import com.metrolist.android.ui.screens.LoginScreen
import com.metrolist.android.ui.screens.PlaylistScreen
import com.metrolist.android.ui.screens.PodcastScreen
import com.metrolist.android.ui.screens.RecognitionHistoryScreen
import com.metrolist.android.ui.screens.RecognitionScreen
import com.metrolist.android.ui.screens.SearchScreen
import com.metrolist.android.ui.screens.SettingsScreen
import com.metrolist.android.ui.screens.settings.AndroidAboutScreen
import com.metrolist.android.ui.screens.settings.AndroidAutoScreen
import com.metrolist.android.ui.screens.settings.BackupRestoreScreen
import com.metrolist.android.ui.screens.settings.DevtoolsSettingsScreen
import com.metrolist.android.ui.screens.settings.ListenTogetherBlockedUsersScreen
import com.metrolist.android.ui.screens.settings.ListenTogetherLogsScreen
import com.metrolist.android.ui.screens.settings.ListenTogetherScreen
import com.metrolist.android.ui.screens.settings.ListenTogetherServerScreen
import com.metrolist.android.ui.screens.settings.ListenTogetherSettingsScreen
import com.metrolist.android.ui.screens.settings.PlaybackBenchmarkSettingsScreen
import com.metrolist.android.ui.screens.settings.PlaylistImportScreen
import com.metrolist.android.ui.screens.settings.ReleaseNotesSheet
import com.metrolist.android.ui.screens.settings.StatsDetailScreen
import com.metrolist.android.ui.screens.settings.StatsScreen
import com.metrolist.android.ui.screens.settings.StorageSettingsScreen
import com.metrolist.android.ui.screens.settings.UpdateDetailsScreen
import com.metrolist.android.ui.screens.settings.UpdatesScreen
import com.metrolist.android.ui.theme.MetrolistTheme
import com.metrolist.android.ui.utils.appBarScrollBehavior
import com.metrolist.android.ui.utils.resetHeightOffset
import com.metrolist.android.update.AppUpdateManager
import com.metrolist.android.update.AppUpdateState
import com.metrolist.android.update.GitHubRelease
import com.metrolist.android.utils.isAndroidAutomotive
import com.metrolist.android.utils.isAndroidTv
import com.metrolist.android.utils.startActivitySafely
import com.metrolist.android.viewmodels.LibraryViewModel
import com.metrolist.android.viewmodels.PlaybackViewModel
import com.metrolist.android.widget.PlaylistWidgetReceiver
import com.metrolist.innertubex.utils.YouTubeUrlParser
import com.metrolist.shared.auth.AuthManager
import com.metrolist.shared.auth.YouTubeSessionState
import com.metrolist.shared.constants.MiniPlayerHeight
import com.metrolist.shared.constants.ModernMiniPlayerBottomSpacing
import com.metrolist.shared.constants.WideNavigationRailCollapsedWidth
import com.metrolist.shared.constants.modernMiniPlayerHeight
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.isHistoryPlaylistId
import com.metrolist.shared.deviceconnect.DeviceConnectPairingHost
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_login
import com.metrolist.shared.generated.resources.action_not_now
import com.metrolist.shared.generated.resources.added_songs_to_playlist
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.continue_playback_message
import com.metrolist.shared.generated.resources.continue_playback_title
import com.metrolist.shared.generated.resources.devtools_title
import com.metrolist.shared.generated.resources.downloads
import com.metrolist.shared.generated.resources.error
import com.metrolist.shared.generated.resources.filter_library
import com.metrolist.shared.generated.resources.home
import com.metrolist.shared.generated.resources.listen_together
import com.metrolist.shared.generated.resources.listen_together_guest_choice_message
import com.metrolist.shared.generated.resources.listen_together_guest_choice_title
import com.metrolist.shared.generated.resources.listen_together_guest_queue
import com.metrolist.shared.generated.resources.listen_together_guest_solo
import com.metrolist.shared.generated.resources.menu_history
import com.metrolist.shared.generated.resources.menu_stop
import com.metrolist.shared.generated.resources.mysterious_fourth_tab
import com.metrolist.shared.generated.resources.mysterious_fourth_tab_description
import com.metrolist.shared.generated.resources.offline_mode
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.profile
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.generated.resources.update_pending
import com.metrolist.shared.generated.resources.updates_action
import com.metrolist.shared.generated.resources.updates_available_message
import com.metrolist.shared.generated.resources.updates_available_title
import com.metrolist.shared.generated.resources.updates_changelog_preview
import com.metrolist.shared.generated.resources.updates_pending_parenthetical
import com.metrolist.shared.generated.resources.youtube_music
import com.metrolist.shared.generated.resources.youtube_session_expired_message
import com.metrolist.shared.generated.resources.youtube_session_expired_title
import com.metrolist.shared.listentogether.parseListenTogetherInvitation
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.LocalPlayerSession
import com.metrolist.shared.playlistimport.PlaylistImportProgressCard
import com.metrolist.shared.playlistimport.PlaylistImportStatusManager
import com.metrolist.shared.settings.MiniPlayerType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.defaultOpenTabRoot
import com.metrolist.shared.settings.defaultOpenTabScreen
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.UploadNotificationState
import com.metrolist.shared.state.applyContentRegion
import com.metrolist.shared.ui.LocalIsInPipMode
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.TokenLoginScreen
import com.metrolist.shared.ui.YouTubeSyncSnackbar
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.BottomSheet
import com.metrolist.shared.ui.components.DISMISSED_ANCHOR
import com.metrolist.shared.ui.components.EXPANDED_ANCHOR
import com.metrolist.shared.ui.components.LocalDownloadedSongIds
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.lyrics.LyricsTranslationSnackbar
import com.metrolist.shared.ui.components.rememberBottomSheetState
import com.metrolist.shared.ui.design.expressive.components.ExpressiveMiniPlayerHeight
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.listentogether.ListenTogetherGlobalEvents
import com.metrolist.shared.ui.menu.HiddenHomeItemsSnackbar
import com.metrolist.shared.ui.menu.LocalContextMenuAnchor
import com.metrolist.shared.ui.menu.contextMenuAnchor
import com.metrolist.shared.ui.menu.rememberCursorContextMenuState
import com.metrolist.shared.ui.mobile.rememberSongsPlaylistAdder
import com.metrolist.shared.ui.navigation.AdaptiveFormFactor
import com.metrolist.shared.ui.navigation.AdaptiveWidthBreakpoint
import com.metrolist.shared.ui.navigation.AlbumSeed
import com.metrolist.shared.ui.navigation.DesktopContentMaxWidth
import com.metrolist.shared.ui.navigation.LocalAlbumArtworkSource
import com.metrolist.shared.ui.navigation.LocalContentObscured
import com.metrolist.shared.ui.navigation.LocalFloatingNavBar
import com.metrolist.shared.ui.navigation.LocalNavVisibilityScope
import com.metrolist.shared.ui.navigation.LocalNavigationContainerState
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.navigation.LocalUseDesktopUi
import com.metrolist.shared.ui.navigation.NavSharedTransitionLayout
import com.metrolist.shared.ui.navigation.NavigationDestination
import com.metrolist.shared.ui.navigation.Navigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.navigation.currentAdaptiveLayout
import com.metrolist.shared.ui.navigation.isNavigationBar
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.navigation.rememberNavigationMotion
import com.metrolist.shared.ui.navigation.rememberNavigationState
import com.metrolist.shared.ui.navigation.toEntries
import com.metrolist.shared.ui.navigation.withPointerInput
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.player.LocalPlayerPlatform
import com.metrolist.shared.ui.player.PlayerScreen
import com.metrolist.shared.ui.player.shouldShowMusicVideoSurface
import com.metrolist.shared.ui.screens.pageKey
import com.metrolist.shared.ui.screens.toHomeSection
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.theme.resolveThemeSeedColor
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.utils.LocalMouseScrollFactors
import com.metrolist.shared.utils.BlockScreenshots
import com.metrolist.shared.utils.extractListenTogetherRoomCode
import com.metrolist.shared.utils.parseYouTubePodcastId
import com.metrolist.shared.utils.parseYouTubeStartPositionMs
import com.metrolist.shared.utils.parseYouTubeUrl
import com.metrolist.shared.viewmodels.PlayerViewModel
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Duration.Companion.milliseconds
import coil3.compose.AsyncImage as CoilAsyncImage
import com.metrolist.android.ui.screens.settings.IntegrationsScreen as SettingsIntegrationsScreen

private const val NAVIGATION_ROUTE_METADATA_KEY = "metrolist:route"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun MetrolistApp(
    navigationSaveKey: String,
    incomingIntent: Intent? = null,
    incomingPlaybackSong: Deferred<SongItem>? = null,
    onExitApp: () -> Unit = {},
    onIncomingIntentConsumed: (Intent) -> Unit = {},
) {
    val hasDynamicColorSdk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current
    val directionToNavigationRail = if (LocalLayoutDirection.current == LayoutDirection.Ltr) FocusDirection.Left else FocusDirection.Right
    val stopPlaybackLabel = stringResource(Res.string.menu_stop)
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val authManager = remember { AuthManager() }
    val playbackNetworkState by PlaybackNetworkStateStore.state.collectAsState()
    LifecycleStartEffect(Unit) {
        App.instance.playbackNetworkMonitor.refresh()
        onStopOrDispose { }
    }

    val materialYouEnabled = Settings.shared.materialYouEnabled
    val songArtworkColorsEnabled = materialYouEnabled && Settings.shared.dynamicTheme

    Settings.shared.systemInDarkTheme = isSystemInDarkTheme()
    val darkTheme = Settings.shared.getCurrentThemeIsDark()
    val pureBlack = Settings.shared.isPureBlackTheme(darkTheme)

    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(authManager) {
        authManager.restorePersistedSession()
        authManager.restoreSession()
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { UploadNotificationState.refreshOnAppOpen(GlobalYouTubeRepository.instance) }
        }
    }

    val systemJvmLocale = AppLocale.systemLocale(context)

    GlobalYouTube.applyContentRegion(
        contentCountry = Settings.shared.contentCountry,
        contentLanguage = Settings.shared.contentLanguage,
        systemLocale = systemJvmLocale,
    )

    val playbackViewModel: PlaybackViewModel = koinViewModel()
    val libraryViewModel: LibraryViewModel = koinViewModel()
    LaunchedEffect(Settings.shared.dataSyncId, Settings.shared.accountEmail, Settings.shared.innerTubeAuthUser) {
        libraryViewModel.refresh(force = false)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    if (Settings.shared.blockScreenshots) {
        BlockScreenshots()
    }

    val artworkSeedColor =
        playbackViewModel.seedColor
            .collectAsState()
            .value
            .takeIf { songArtworkColorsEnabled }
    MetrolistTheme(
        darkTheme = darkTheme,
        dynamicColor = materialYouEnabled && hasDynamicColorSdk && artworkSeedColor == null,
        themeColor =
            resolveThemeSeedColor(
                materialYouEnabled = materialYouEnabled,
                songArtworkColorsEnabled = songArtworkColorsEnabled,
                selectedThemeColor = Settings.shared.selectedThemeColor,
                artworkSeedColor = artworkSeedColor,
            ),
        pureBlack = pureBlack,
    ) {
        val appBackground = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surface
        val horizontalSystemBars = WindowInsets.navigationBars.union(WindowInsets.displayCutout)
        val playerBackgroundOpaque = MaterialTheme.colorScheme.surfaceContainer.alpha == 1f
        val deviceConnectState by AndroidDeviceConnect.manager.state.collectAsState()
        DeviceConnectPairingHost(
            state = deviceConnectState,
            onApprovePairing = AndroidDeviceConnect.manager::approvePairing,
            onRejectPairing = AndroidDeviceConnect.manager::rejectPairing,
        )
        val initialStartRoute = remember { resolveAndPersistStartupScreen() }
        val topLevelRoutes = remember { setOf(Screen.Home, Screen.Search, Screen.Library, Screen.EasterEgg) }
        val navigationState =
            key(navigationSaveKey) {
                rememberNavigationState(
                    startRoute = initialStartRoute,
                    topLevelRoutes = topLevelRoutes,
                    initialDestination = remember { defaultOpenTabScreen(Settings.shared.defaultOpenTab) },
                )
            }

        val currentRoute = navigationState.backStacks[navigationState.topLevelRoute]?.last()
        val startupPresentation = LocalStartupPresentation.current
        SideEffect { startupPresentation?.homeRequired = currentRoute == Screen.Home }
        val navigator = remember(navigationState) { Navigator(navigationState) }
        val homeScrollToTopEvents =
            remember {
                MutableSharedFlow<Long>(
                    replay = 1,
                    extraBufferCapacity = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            }
        val libraryScrollToTopEvents =
            remember {
                MutableSharedFlow<Long>(
                    replay = 1,
                    extraBufferCapacity = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            }

        var scrollToTopSequence by rememberSaveable { mutableLongStateOf(0L) }

        fun requestScrollToTop(screen: Screen) {
            scrollToTopSequence += 1
            when (screen) {
                Screen.Home -> homeScrollToTopEvents.tryEmit(scrollToTopSequence)
                Screen.Library -> libraryScrollToTopEvents.tryEmit(scrollToTopSequence)
                else -> Unit
            }
        }
        PopupVisibility(visible = YouTubeSessionState.showExpiredSessionNotice) {
            AlertDialog(
                onDismissRequest = YouTubeSessionState::dismissExpiredSessionNotice,
                title = { Text(stringResource(Res.string.youtube_session_expired_title)) },
                text = { Text(stringResource(Res.string.youtube_session_expired_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            YouTubeSessionState.dismissExpiredSessionNotice()
                            navigator.navigate(Screen.Login)
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(Res.string.action_login))
                    }
                },
                dismissButton = {
                    TextButton(onClick = YouTubeSessionState::dismissExpiredSessionNotice, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(Res.string.action_not_now))
                    }
                },
            )
        }
        var pendingSearchQuery by remember { mutableStateOf<String?>(null) }
        var pendingRoomCode by rememberSaveable { mutableStateOf<String?>(null) }
        var continuePlaybackPromptHandled by rememberSaveable { mutableStateOf(false) }
        var showContinuePlaybackPrompt by rememberSaveable { mutableStateOf(false) }

        var updatePrompt by remember { mutableStateOf<GitHubRelease?>(null) }
        var installedReleaseNotes by remember { mutableStateOf<GitHubRelease?>(null) }
        val updateScope = rememberCoroutineScope()
        val appUpdateState by AppUpdateManager.state.collectAsState()
        val hasPendingUpdate =
            when (val state = appUpdateState) {
                is AppUpdateState.Available,
                is AppUpdateState.Downloading,
                is AppUpdateState.Downloaded,
                -> true

                is AppUpdateState.Error -> state.release != null

                else -> false
            }

        LaunchedEffect(Unit) {
            if (!App.claimStartupUpdateCheck()) return@LaunchedEffect
            val previousVersion = Settings.shared.lastRunAppVersion
            val wasUpdated = previousVersion.isNotBlank() && previousVersion != BuildConfig.VERSION_NAME
            val available =
                AppUpdateManager.checkAtStartup(
                    context = context,
                    networkEnabled = Settings.shared.checkForUpdates,
                )
            if (available == null) {
                Settings.shared.updatePromptVersion = ""
                Settings.shared.launchesSinceUpdatePrompt = 0
            } else if (Settings.shared.updatePromptVersion != available.version) {
                Settings.shared.updatePromptVersion = available.version
                Settings.shared.launchesSinceUpdatePrompt = 0
                updatePrompt = available
            } else {
                val launches = Settings.shared.launchesSinceUpdatePrompt + 1
                if (launches >= 2) {
                    updatePrompt = available
                    Settings.shared.launchesSinceUpdatePrompt = 0
                } else {
                    Settings.shared.launchesSinceUpdatePrompt = launches
                }
            }
            if (wasUpdated) {
                installedReleaseNotes =
                    AppUpdateManager.pendingReleaseForInstalledVersion()
                        ?: AppUpdateManager.releaseForInstalledVersion()
            }
            if (!wasUpdated || installedReleaseNotes != null || AppUpdateManager.releases.value.isNotEmpty()) {
                Settings.shared.lastRunAppVersion = BuildConfig.VERSION_NAME
            }
        }

        PopupContent(updatePrompt) { release ->
            AlertDialog(
                onDismissRequest = { updatePrompt = null },
                title = { Text(stringResource(Res.string.updates_available_title, release.version)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(Res.string.updates_available_message))
                        Text(
                            text = stringResource(Res.string.updates_changelog_preview),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = release.changelogSnippet(),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 7,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            updatePrompt = null
                            navigator.navigate(Screen.UpdateDetails)
                            updateScope.launch { AppUpdateManager.download(context, release) }
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(Res.string.updates_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { updatePrompt = null }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(Res.string.action_not_now))
                    }
                },
            )
        }

        val playerConnection = playbackViewModel.playerConnection
        val playbackController = remember(playerConnection) { AndroidPlaybackController(playerConnection, coroutineScope) }
        var listenTogetherManager by remember(playerConnection) {
            mutableStateOf(AndroidListenTogetherManager.currentManager)
        }
        LaunchedEffect(playerConnection, currentRoute) {
            if (listenTogetherManager != null || Settings.shared.listenTogetherDesktopSession.isNotBlank() ||
                currentRoute in
                setOf(
                    Screen.ListenTogether,
                    Screen.ListenTogetherSettings,
                    Screen.ListenTogetherServer,
                    Screen.ListenTogetherBlockedUsers,
                    Screen.ListenTogetherLogs,
                )
            ) {
                listenTogetherManager = AndroidListenTogetherManager.initialize(context, playerConnection)
            }
        }
        val playerPlatform =
            remember(context, playerConnection, listenTogetherManager) {
                AndroidPlayerPlatform(context.findActivity(), playerConnection, listenTogetherManager)
            }
        val playerViewModel: PlayerViewModel = koinInject()
        val downloadManager: SongDownloadManager = koinInject()
        val database: MusicDatabase = koinInject()
        val downloadedSongIds by
            remember(database) {
                database.dao().cachedAudioIdsByKind(KIND_DOWNLOAD).distinctUntilChanged().map { ids ->
                    withContext(Dispatchers.Default) { ids.toSet() }
                }
            }.collectAsStateWithLifecycle(initialValue = emptySet())
        val snackbarHostState = remember { SnackbarHostState() }
        HiddenHomeItemsSnackbar(snackbarHostState)
        YouTubeSyncSnackbar(snackbarHostState)
        LyricsTranslationSnackbar(snackbarHostState, playerViewModel.lyricsTranslationFailures)
        listenTogetherManager?.let { manager ->
            ListenTogetherGlobalEvents(manager) { snackbarHostState.showSnackbar(it) }
        }
        val addToPlaylist =
            rememberSongsPlaylistAdder(database) { completion ->
                snackbarHostState.showSnackbar(
                    getPluralString(
                        Res.plurals.added_songs_to_playlist,
                        completion.count,
                        completion.count,
                        completion.playlistName,
                    ),
                )
            }
        val downloadRequests by downloadManager.requests.collectAsState()

        val currentSong by playerConnection.currentSong.collectAsState(initial = null)
        val isPlaying by playerConnection.isPlaying.collectAsState()
        val hasMedia = currentSong != null
        LaunchedEffect(Unit) {
            PlaybackSnackbarEvents.messages.collect { message ->
                snackbarHostState.showSnackbar(message)
            }
        }

        LaunchedEffect(currentSong, isPlaying) {
            val shouldOffer =
                Settings.shared.promptContinuePlayback && Settings.shared.pendingContinuePlayback
            if (
                shouldOffer &&
                !continuePlaybackPromptHandled &&
                currentSong != null &&
                !isPlaying
            ) {
                continuePlaybackPromptHandled = true
                showContinuePlaybackPrompt = true
            }
        }

        var searchResultsVisible by rememberSaveable { mutableStateOf(false) }
        val isAndroidTv = remember(context) { context.isAndroidTv() }
        val isAutomotive = remember(context) { context.isAndroidAutomotive() }
        val adaptiveFormFactor =
            when {
                isAutomotive -> AdaptiveFormFactor.AUTOMOTIVE
                isAndroidTv -> AdaptiveFormFactor.TELEVISION
                else -> AdaptiveFormFactor.TOUCH
            }
        val hasMouseDevice = rememberHasMouseDevice()
        val isDeskMode =
            LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK ==
                Configuration.UI_MODE_TYPE_DESK
        val useDesktopLayout = hasMouseDevice || isDeskMode
        val adaptiveLayout =
            currentAdaptiveLayout(
                adaptiveFormFactor,
                Settings.shared.forceBottomNavBar,
            ).withPointerInput(useDesktopLayout)
        val useDesktopUi = adaptiveLayout.formFactor == AdaptiveFormFactor.DESKTOP
        val useDesktopMiniPlayer = shouldUseDesktopMiniPlayer(useDesktopUi, LocalConfiguration.current.smallestScreenWidthDp)
        val captionHeight = WindowInsets.captionBar.getTop(LocalDensity.current)
        val hasDesktopCaption = shouldUseDesktopCaption(Build.VERSION.SDK_INT, captionHeight, useDesktopLayout)
        val shouldShowNavigationBar =
            (useDesktopUi || currentRoute in topLevelRoutes) &&
                !(currentRoute == Screen.Search && searchResultsVisible)
        val usesDirectionalNavigation = isAndroidTv || isAutomotive
        val navigationSuiteType =
            when {
                adaptiveFormFactor == AdaptiveFormFactor.TOUCH && isLandscape &&
                    adaptiveLayout.widthBreakpoint >= AdaptiveWidthBreakpoint.MEDIUM && !Settings.shared.forceBottomNavBar -> {
                    NavigationSuiteType.WideNavigationRailCollapsed
                }

                adaptiveLayout.navigationSuiteType.isNavigationBar && !Settings.shared.slimNavBar -> {
                    NavigationSuiteType.NavigationBar
                }

                else -> {
                    adaptiveLayout.navigationSuiteType
                }
            }
        val useNavigationRail = !navigationSuiteType.isNavigationBar
        // Hiding navigation must not move the retained lookahead tree between scaffolds.
        val useFloatingNavBar =
            Settings.shared.floatingNavBar && !useDesktopUi && !usesDirectionalNavigation &&
                navigationSuiteType.isNavigationBar
        val isImeVisible = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 0.dp
        val shouldHideMiniPlayerForIme = isLandscape && isImeVisible
        val shouldShowMiniPlayer = hasMedia
        val immersiveContent = remember { mutableStateOf(false) }
        val shouldRenderMiniPlayer = shouldShowMiniPlayer && !shouldHideMiniPlayerForIme

        val topLevelScreens = remember { setOf(Screen.Home, Screen.Library, Screen.EasterEgg) }
        val shouldShowTopBar =
            navigationState.topLevelRoute in topLevelScreens && currentRoute in topLevelScreens

        val useFloatingTopBar = Settings.shared.floatingTopBar && !hasDesktopCaption
        val topAppBarScrollBehavior = appBarScrollBehavior()
        val selectedRailFocusRequester = remember { FocusRequester() }
        val contentFocusRequester = remember { FocusRequester() }

        LaunchedEffect(usesDirectionalNavigation, useNavigationRail, shouldShowNavigationBar) {
            if (usesDirectionalNavigation && useNavigationRail && shouldShowNavigationBar) {
                withFrameNanos { }
                runCatching { selectedRailFocusRequester.requestFocus() }
            }
        }

        LaunchedEffect(navigationState.topLevelRoute, currentRoute) {
            if (currentRoute in topLevelScreens || currentRoute == Screen.Search) {
                topAppBarScrollBehavior.state.resetHeightOffset()
            }
        }

        // Tab reselection bypasses nested scroll, so it must also reveal the collapsed top bar.
        LaunchedEffect(scrollToTopSequence) {
            if (scrollToTopSequence > 0L) {
                topAppBarScrollBehavior.state.contentOffset = 0f
                topAppBarScrollBehavior.state.resetHeightOffset()
            }
        }

        // Reserve navigation space while system bars return from immersive playback.
        val navigationBarBottomPadding = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
        val density = LocalDensity.current
        var navigationBarContentInset by remember(navigationSuiteType, useFloatingNavBar) { mutableStateOf(0.dp) }
        var floatingNavBarHeight by remember(navigationSuiteType, useFloatingNavBar) { mutableStateOf(0.dp) }
        val safeBottomPadding =
            maxOf(
                navigationBarBottomPadding,
                WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding(),
                WindowInsets.mandatorySystemGestures.asPaddingValues().calculateBottomPadding(),
            )
        val navigationVisibilityProgress =
            animateFloatAsState(
                targetValue = if (shouldShowNavigationBar) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 700f),
                label = "navigationVisibility",
            )
        val hasBottomNavigation = navigationSuiteType.isNavigationBar
        val isExpressiveMiniPlayer = Settings.shared.miniPlayerType == MiniPlayerType.EXPRESSIVE && !useDesktopMiniPlayer
        val isModernMiniPlayer = Settings.shared.miniPlayerType == MiniPlayerType.MODERN && !useDesktopMiniPlayer
        val miniPlayerBottomSpacing =
            when {
                isExpressiveMiniPlayer -> ModernMiniPlayerBottomSpacing
                isModernMiniPlayer -> ModernMiniPlayerBottomSpacing
                else -> 0.dp
            }
        val hiddenMiniPlayerBottomOffset = safeBottomPadding + miniPlayerBottomSpacing
        val navigationBottomInset =
            when {
                !hasBottomNavigation -> 0.dp
                useFloatingNavBar -> floatingNavBarHeight
                else -> navigationBarContentInset
            }
        val navigationConsumesSpace = hasBottomNavigation && !useFloatingNavBar
        val miniPlayerHeight =
            if (useDesktopMiniPlayer) {
                DesktopMiniPlayerHeight
            } else if (isExpressiveMiniPlayer) {
                ExpressiveMiniPlayerHeight
            } else if (isModernMiniPlayer) {
                modernMiniPlayerHeight(LocalDensity.current)
            } else {
                MiniPlayerHeight
            }
        val miniPlayerInsets =
            calculateMiniPlayerInsets(
                systemBottomInset = if (shouldRenderMiniPlayer) safeBottomPadding else navigationBarBottomPadding,
                navigationBottomInset = navigationBottomInset,
                navigationConsumesSpace = navigationConsumesSpace,
                playerHeight = if (shouldRenderMiniPlayer) miniPlayerHeight else 0.dp,
                playerBottomSpacing = if (shouldRenderMiniPlayer) miniPlayerBottomSpacing else 0.dp,
            )
        val miniPlayerCollapsedBound =
            if (!shouldRenderMiniPlayer) {
                0.dp
            } else {
                miniPlayerHeight + miniPlayerInsets.bottomOffset
            }

        val hiddenMiniPlayerContentInset =
            if (shouldRenderMiniPlayer) {
                miniPlayerHeight + hiddenMiniPlayerBottomOffset
            } else {
                navigationBarBottomPadding
            }
        val floatingContentInset = if (useFloatingNavBar && shouldShowNavigationBar) floatingNavBarHeight else 0.dp
        val visibleMiniPlayerContentInset =
            (if (shouldRenderMiniPlayer) miniPlayerHeight + miniPlayerBottomSpacing else 0.dp) + floatingContentInset
        val miniPlayerContentInset = {
            val animatedInset =
                if (hasBottomNavigation) {
                    hiddenMiniPlayerContentInset +
                        (visibleMiniPlayerContentInset - hiddenMiniPlayerContentInset) * navigationVisibilityProgress.value
                } else {
                    hiddenMiniPlayerContentInset
                }
            maxOf(animatedInset, miniPlayerInsets.contentBottomInset)
        }
        val floatingActionsMinInset =
            maxOf(navigationBottomInset, hiddenMiniPlayerBottomOffset) -
                if (navigationConsumesSpace) navigationBottomInset else 0.dp
        val miniPlayerRailPadding = {
            if (
                adaptiveFormFactor == AdaptiveFormFactor.TOUCH &&
                useNavigationRail
            ) {
                WideNavigationRailCollapsedWidth * navigationVisibilityProgress.value
            } else {
                0.dp
            }
        }
        val menuState = remember { MenuState() }
        val menuAnchor = rememberCursorContextMenuState()
        val navigationMotion = rememberNavigationMotion()
        // Stretch overscroll re-renders the whole list into an offscreen layer every frame at the edges.
        val overscrollFactory = LocalOverscrollFactory.current

        var tvAppHasFocus by remember { mutableStateOf(false) }
        NavigationContentProvider(
            miniPlayerContentInset,
            LocalTvAppHasFocus provides { tvAppHasFocus },
            LocalTvNavigationFocusFallback provides {
                shouldShowNavigationBar && useNavigationRail &&
                    runCatching { selectedRailFocusRequester.requestFocus() }.getOrDefault(false)
            },
            LocalOverscrollFactory provides overscrollFactory,
            LocalNavigator provides navigator,
            LocalNavigationContainerState provides navigator.state.containers,
            LocalPlaybackController provides playbackController,
            LocalDownloadedSongIds provides downloadedSongIds,
            LocalPlayerConnection provides playerConnection,
            LocalPlayerSession provides playerConnection,
            LocalPlayerPlatform provides playerPlatform,
            LocalListenTogetherManager provides listenTogetherManager,
            LocalIsTv provides isAndroidTv,
            LocalMenuState provides menuState,
            LocalUseDesktopUi provides useDesktopUi,
            LocalContextMenuAnchor provides menuAnchor,
            LocalMouseScrollFactors provides rememberMouseScrollFactors(),
            LocalPlaylistAdder provides addToPlaylist,
        ) {
            val appAccountButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                val accountName = authManager.accountName
                val accountThumbnail = authManager.accountThumbnail
                var showAccountMenu by remember { mutableStateOf(false) }
                val updatePending = stringResource(Res.string.update_pending)
                Box {
                    IconButton(
                        onClick = { showAccountMenu = true },
                        modifier =
                            (
                                if (hasPendingUpdate) {
                                    buttonModifier.semantics { stateDescription = updatePending }
                                } else {
                                    buttonModifier
                                }
                            ).tvFocusHighlight(
                                CircleShape,
                                scale = 1.08f,
                                bringIntoView = false,
                            ).navigationPopupSource(),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        TopBarProfileAvatar(
                            accountName = accountName,
                            accountThumbnail = accountThumbnail,
                            hasPendingUpdate = hasPendingUpdate,
                        )
                    }
                    if (showAccountMenu) {
                        AccountMenu(
                            onClose = { showAccountMenu = false },
                            onNavigateToLogin = { navigator.navigate(Screen.Login) },
                            onNavigateToTokenLogin = { navigator.navigate(Screen.TokenLogin) },
                            onNavigateToSwitchChannel = {
                                navigator.navigate(Screen.SwitchChannel)
                            },
                            onNavigateToHistory =
                                if (navigationState.topLevelRoute == Screen.Library) {
                                    { navigator.navigate(Screen.History) }
                                } else {
                                    null
                                },
                            onNavigateToStatistics = { navigator.navigate(Screen.Statistics) },
                            onNavigateToIntegrations = { navigator.navigate(Screen.Integrations) },
                            onNavigateToSettings = { navigator.navigate(Screen.Settings) },
                            onLogout = {
                                coroutineScope.launch {
                                    authManager.clearAllCredentials()
                                    context.packageManager
                                        .getLaunchIntentForPackage(context.packageName)
                                        ?.apply {
                                            addFlags(
                                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                                    Intent.FLAG_ACTIVITY_CLEAR_TASK,
                                            )
                                        }?.let(context::startActivitySafely)
                                }
                            },
                        )
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (isAndroidTv) Modifier.onFocusChanged { tvAppHasFocus = it.hasFocus }.focusGroup() else Modifier)
                    .contextMenuAnchor(menuAnchor)
                    .drawBehind {
                        val left = horizontalSystemBars.getLeft(this, layoutDirection).toFloat()
                        val right = horizontalSystemBars.getRight(this, layoutDirection).toFloat()
                        if (left > 0f) drawRect(appBackground, size = Size(left, size.height))
                        if (right > 0f) drawRect(appBackground, topLeft = Offset(size.width - right, 0f), size = Size(right, size.height))
                    },
            ) {
                DesktopCaptionBar(
                    enabled = hasDesktopCaption,
                    darkTheme = darkTheme,
                    color = appBackground,
                ) {
                    val captionSize = with(LocalDensity.current) { captionHeight.toDp() }
                    appAccountButton(Modifier.size(captionSize).systemGestureExclusion())
                    BoxWithConstraints(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (currentRoute != Screen.Search) {
                            if (maxWidth < 240.dp) {
                                IconButton(
                                    onClick = { navigator.navigateToRoot(Screen.Search) },
                                    modifier = Modifier.size(captionSize).systemGestureExclusion(),
                                    shapes = IconButtonDefaults.shapes(),
                                ) {
                                    Icon(MetrolistIcons.Outlined.Search, contentDescription = stringResource(Res.string.search))
                                }
                            } else {
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth(0.6f)
                                            .height(captionSize)
                                            .systemGestureExclusion()
                                            .clickable { navigator.navigateToRoot(Screen.Search) }
                                            .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(MetrolistIcons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(stringResource(Res.string.search), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                ListenTogetherPlaybackRequestHandler(playerConnection)
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(
                            if (hasDesktopCaption) {
                                Modifier.consumeWindowInsets(WindowInsets.captionBar)
                            } else {
                                Modifier.windowInsetsPadding(WindowInsets.captionBar)
                            },
                        ),
                ) {
                    val maxHeight = this.maxHeight

                    val playerBottomSheetState =
                        rememberBottomSheetState(
                            dismissedBound = 0.dp,
                            collapsedBound = miniPlayerCollapsedBound,
                            expandedBound = maxHeight,
                            initialAnchor = DISMISSED_ANCHOR,
                        )
                    val calculateFloatingActionsBottomInset by rememberUpdatedState {
                        if (shouldShowMiniPlayer) {
                            (miniPlayerContentInset() - (miniPlayerCollapsedBound - playerBottomSheetState.value).coerceAtLeast(0.dp))
                                .coerceAtLeast(floatingActionsMinInset)
                        } else {
                            miniPlayerContentInset()
                        }
                    }
                    // Nav entries retain this State; keep its identity stable while updating the inputs.
                    // Read the sheet offset lazily so animation frames do not recompose the app root.
                    val floatingActionsBottomInset = remember { derivedStateOf { calculateFloatingActionsBottomInset() } }
                    var focusPlayPauseOnPlayerExpand by remember { mutableStateOf(false) }
                    var pendingOpenPlayer by rememberSaveable { mutableStateOf(false) }
                    val miniPlayerFocusRequester = remember { FocusRequester() }
                    val youtubeMusicLabel = stringResource(Res.string.youtube_music)
                    val focusManager = LocalFocusManager.current
                    val keyboardController = LocalSoftwareKeyboardController.current
                    val hapticManager = rememberMetrolistHaptics()

                    val isPlayerExpandedOrExpanding by remember(playerBottomSheetState) {
                        derivedStateOf {
                            playerBottomSheetState.isExpanded || playerBottomSheetState.progress > 0f
                        }
                    }

                    val isInPipMode = LocalIsInPipMode.current
                    val contentObscured by remember(playerBottomSheetState) {
                        derivedStateOf { playerBackgroundOpaque && playerBottomSheetState.isSettledExpanded }
                    }
                    val libraryNeeded =
                        currentRoute == Screen.Library || (currentRoute is Screen.Playlist && isHistoryPlaylistId(currentRoute.id))
                    val libraryActive = libraryNeeded && !contentObscured && !isInPipMode
                    LifecycleStartEffect(libraryViewModel, libraryActive) {
                        libraryViewModel.setActive(libraryActive)
                        onStopOrDispose { libraryViewModel.setActive(false) }
                    }
                    val listenTogetherVisible = currentRoute == Screen.ListenTogether && !isPlayerExpandedOrExpanding && !isInPipMode
                    LifecycleResumeEffect(listenTogetherVisible) {
                        AndroidListenTogetherManager.setScreenVisible(listenTogetherVisible)
                        onPauseOrDispose { AndroidListenTogetherManager.setScreenVisible(false) }
                    }

                    val isPlayerExpanded = playerBottomSheetState.isExpanded
                    var lyricsVisible by remember { mutableStateOf(false) }
                    val activeImport by PlaylistImportStatusManager.activeImport.collectAsState()
                    val hostView = LocalView.current
                    val keepScreenOn =
                        (isPlayerExpanded && Settings.shared.keepScreenOnMode.keepsAwake(isPlaying, lyricsVisible)) ||
                            (currentRoute in setOf(Screen.PlaylistImport, Screen.BackupRestore) && activeImport?.isRunning == true)
                    DisposableEffect(hostView, keepScreenOn) {
                        hostView.keepScreenOn = keepScreenOn
                        onDispose { hostView.keepScreenOn = false }
                    }
                    LaunchedEffect(isPlayerExpanded, isLandscape) {
                        if (isPlayerExpanded) {
                            playerConnection.ensureConnected()
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                        }
                    }

                    DisposableEffect(lifecycleOwner, isPlayerExpanded) {
                        val observer =
                            LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME && isPlayerExpanded) {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                }
                            }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    LaunchedEffect(Unit) {
                        playerBottomSheetState.snapTo(0.dp)
                    }

                    LaunchedEffect(incomingIntent) {
                        val intent = incomingIntent ?: return@LaunchedEffect
                        if (intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
                            playerConnection.playFromSearch(
                                voiceSearchRequest(intent.getStringExtra(SearchManager.QUERY).orEmpty(), intent.extras),
                            )
                            onIncomingIntentConsumed(intent)
                            return@LaunchedEffect
                        }
                        val destination = intent.toIncomingDestination()
                        if (!Settings.shared.listenTogetherEnabled &&
                            (destination is IncomingDestination.ListenTogether || destination == IncomingDestination.ListenTogetherRoom)
                        ) {
                            onIncomingIntentConsumed(intent)
                            return@LaunchedEffect
                        }
                        if (destination != null && destination !is IncomingDestination.Search) {
                            pendingSearchQuery = null
                        }
                        if (destination != null && destination !is IncomingDestination.Player &&
                            destination !is IncomingDestination.PlayVideo && destination !is IncomingDestination.PlaySearch
                        ) {
                            pendingOpenPlayer = false
                            if (hasMedia) playerBottomSheetState.collapseSoft()
                        }
                        when (destination) {
                            null -> {}

                            IncomingDestination.Home -> {
                                navigator.navigateToRoot(Screen.Home)
                            }

                            IncomingDestination.Library -> {
                                navigator.navigateToRoot(Screen.Library)
                            }

                            IncomingDestination.Downloads -> {
                                navigator.navigate(Screen.Downloads)
                            }

                            IncomingDestination.ListenTogetherRoom -> {
                                pendingRoomCode = null
                                navigator.navigate(Screen.ListenTogether)
                            }

                            IncomingDestination.Updates -> {
                                navigator.navigate(Screen.UpdateDetails)
                            }

                            IncomingDestination.Settings -> {
                                navigator.navigate(Screen.Settings)
                            }

                            IncomingDestination.Login -> {
                                navigator.navigate(Screen.Login)
                            }

                            IncomingDestination.BackupRestore -> {
                                navigator.navigate(Screen.BackupRestore)
                            }

                            IncomingDestination.Recognition -> {
                                navigator.navigate(Screen.Recognition)
                            }

                            IncomingDestination.Player -> {
                                pendingOpenPlayer = true
                            }

                            is IncomingDestination.Search -> {
                                pendingSearchQuery = destination.query
                                navigator.navigateToRoot(Screen.Search)
                            }

                            is IncomingDestination.PlaySearch -> {
                                val songs =
                                    runCatching {
                                        GlobalYouTubeRepository.instance
                                            .searchFiltered(destination.query, SearchFilter.SONG)
                                            .items
                                            .filterIsInstance<SongItem>()
                                    }.getOrDefault(emptyList())
                                if (songs.isNotEmpty()) {
                                    playerConnection.playQueue(songs, finite = true)
                                } else {
                                    pendingSearchQuery = destination.query
                                    pendingOpenPlayer = false
                                    if (hasMedia) playerBottomSheetState.collapseSoft()
                                    navigator.navigateToRoot(Screen.Search)
                                }
                            }

                            is IncomingDestination.Album -> {
                                navigator.navigate(Screen.Album(destination.id))
                            }

                            is IncomingDestination.Artist -> {
                                navigator.navigate(Screen.Artist(destination.id))
                            }

                            is IncomingDestination.Podcast -> {
                                navigator.navigate(Screen.Podcast(destination.id))
                            }

                            is IncomingDestination.Playlist -> {
                                navigator.navigate(Screen.Playlist(destination.id))
                            }

                            is IncomingDestination.AutoPlaylist -> {
                                navigator.navigate(Screen.AutoPlaylist(destination.type))
                            }

                            is IncomingDestination.Browse -> {
                                navigator.navigate(Screen.Browse(destination.id, destination.params, destination.title))
                            }

                            is IncomingDestination.ListenTogether -> {
                                pendingRoomCode = destination.roomCode
                                pendingOpenPlayer = false
                                if (hasMedia) playerBottomSheetState.collapseSoft()
                                navigator.navigate(Screen.ListenTogether)
                            }

                            is IncomingDestination.PlaylistImport -> {
                                navigator.navigate(Screen.PlaylistImport)
                                destination.resultMessage?.let { message ->
                                    coroutineScope.launch { snackbarHostState.showSnackbar(message) }
                                }
                            }

                            is IncomingDestination.PlayVideo -> {
                                val song =
                                    incomingPlaybackSong?.await() ?: resolveIncomingPlaybackSong(
                                        videoId = destination.videoId,
                                        fallbackArtist = youtubeMusicLabel,
                                        loadMetadata = GlobalYouTubeRepository.instance::getSongMetadata,
                                        loadPlayerInformation = GlobalYouTubeRepository.instance::getSongPlayerInformation,
                                    )
                                playerConnection.playSong(song, "", startPositionMs = destination.startPositionMs)
                            }
                        }
                        onIncomingIntentConsumed(intent)
                    }

                    LaunchedEffect(pendingOpenPlayer, hasMedia) {
                        if (pendingOpenPlayer && hasMedia) {
                            playerBottomSheetState.expandSoft()
                            pendingOpenPlayer = false
                        }
                    }

                    LaunchedEffect(miniPlayerCollapsedBound) {
                        if (shouldShowMiniPlayer && !playerBottomSheetState.isExpanded &&
                            playerBottomSheetState.targetAnchor != EXPANDED_ANCHOR
                        ) {
                            playerBottomSheetState.collapse(
                                spring(dampingRatio = 0.9f, stiffness = 700f),
                            )
                        }
                    }

                    LaunchedEffect(currentSong?.id) {
                        when {
                            !hasMedia -> {
                                playerBottomSheetState.dismiss()
                            }

                            else -> {
                                if (hasMedia && !playerBottomSheetState.isExpanded &&
                                    playerBottomSheetState.targetAnchor != EXPANDED_ANCHOR
                                ) {
                                    playerBottomSheetState.collapseSoft()
                                }
                            }
                        }
                    }

                    val isPlayerConnected by playerConnection.isConnected.collectAsState()
                    val videoPlayer = if (isPlayerConnected) playerConnection.videoPlayer else null
                    val playbackResource by playerConnection.currentPlaybackResource.collectAsState()
                    val isVideoModeActive by playerConnection.isVideoModeActive.collectAsState()
                    val isVideoViewerVisible by playerConnection.isVideoViewerVisible.collectAsState()
                    val activity = remember(context) { context.findActivity() }
                    val videoSize = videoPlayer?.videoSize
                    val hasVideo =
                        shouldShowMusicVideoSurface(
                            isVideoModeActive = isVideoModeActive,
                            isVideoViewerVisible = isVideoViewerVisible,
                            hasVideo = playbackResource?.hasVideo == true,
                        )

                    LaunchedEffect(isInPipMode, isPlaying, videoSize, hasVideo) {
                        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            PictureInPictureHelper.updatePictureInPictureParams(
                                activity = activity,
                                isPlaying = isPlaying,
                                videoSize = videoSize,
                                hasVideo = hasVideo,
                            )
                        }
                    }

                    Box(Modifier.fillMaxSize()) {
                        val playerHandlesBack by remember(playerBottomSheetState) {
                            derivedStateOf { playerBottomSheetState.progress > 0.2f && !playerBottomSheetState.isDismissed }
                        }
                        val routeBackDispatcher = rememberNavigationEventDispatcherOwner(enabled = !playerHandlesBack)
                        val hostDefaults = LocalHostDefaultProvider.current
                        val routeHostDefaults =
                            remember(hostDefaults, routeBackDispatcher) {
                                object : HostDefaultProvider {
                                    override fun <T> getHostDefault(key: HostDefaultKey<T>): T {
                                        if (key == NavigationEventDispatcherOwnerHostDefaultKey) {
                                            @Suppress("UNCHECKED_CAST")
                                            return routeBackDispatcher as T
                                        }
                                        return hostDefaults.getHostDefault(key)
                                    }
                                }
                            }
                        CompositionLocalProvider(
                            LocalContentObscured provides (isInPipMode || contentObscured),
                            LocalFloatingNavBar provides useFloatingNavBar,
                            LocalPlayerExpanded provides isPlayerExpanded,
                            LocalPlayerExpandedOrExpanding provides isPlayerExpandedOrExpanding,
                            LocalImmersiveContent provides immersiveContent,
                        ) {
                            MetrolistNavigationScaffold(
                                navigator = navigator,
                                navigationState = navigationState,
                                adaptiveLayout = adaptiveLayout,
                                navigationSuiteType = navigationSuiteType,
                                navigationVisible = shouldShowNavigationBar,
                                containerColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surface,
                                onSearchLongClick = { navigator.navigate(Screen.Recognition) },
                                floatingNavigation = useFloatingNavBar,
                                onFloatingNavigationOccupiedHeightChanged = {
                                    floatingNavBarHeight = it
                                },
                                selectedItemFocusRequester = selectedRailFocusRequester,
                                bottomExitFocusRequester =
                                    if (shouldRenderMiniPlayer) {
                                        miniPlayerFocusRequester
                                    } else {
                                        FocusRequester.Cancel
                                    },
                                contentFocusRequester = contentFocusRequester,
                                onScrollToTop = ::requestScrollToTop,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .windowInsetsPadding(horizontalSystemBars.only(WindowInsetsSides.Horizontal))
                                        .focusProperties {
                                            onEnter = { if (isPlayerExpandedOrExpanding) cancelFocusChange() }
                                        }.focusGroup()
                                        .graphicsLayer {
                                            // Cache the static content as a texture during sheet motion to avoid redrawing it each frame.
                                            compositingStrategy =
                                                if (playerBottomSheetState.progress > 0f && !playerBottomSheetState.isSettledExpanded) {
                                                    CompositingStrategy.Offscreen
                                                } else {
                                                    CompositingStrategy.Auto
                                                }
                                            alpha =
                                                if (isInPipMode || contentObscured) {
                                                    0f
                                                } else {
                                                    1f
                                                }
                                        }.then(
                                            if (isPlayerExpandedOrExpanding) {
                                                Modifier.clearAndSetSemantics { }
                                            } else {
                                                Modifier
                                            },
                                        ),
                            ) {
                                Column(Modifier.fillMaxSize()) {
                                    PlaylistImportProgressCard()
                                    Scaffold(
                                        containerColor = Color.Transparent,
                                        contentWindowInsets = WindowInsets(0),
                                        topBar = {
                                            if (shouldShowTopBar) {
                                                val topBarTitle: @Composable () -> Unit = {
                                                    AppTitleWithUpdateWarning(
                                                        title =
                                                            when (navigationState.topLevelRoute) {
                                                                Screen.Home -> {
                                                                    stringResource(Res.string.home)
                                                                }

                                                                Screen.Library -> {
                                                                    stringResource(Res.string.filter_library)
                                                                }

                                                                Screen.EasterEgg -> {
                                                                    stringResource(Res.string.mysterious_fourth_tab)
                                                                }

                                                                else -> {
                                                                    ""
                                                                }
                                                            },
                                                        showWarning = hasPendingUpdate && navigationState.topLevelRoute == Screen.Home,
                                                    )
                                                }
                                                val topBarSubtitle: @Composable () -> Unit = {
                                                    if (navigationState.topLevelRoute == Screen.Home &&
                                                        !playbackNetworkState.connected
                                                    ) {
                                                        Text(stringResource(Res.string.offline_mode))
                                                    }
                                                }
                                                val topBarActions: @Composable RowScope.() -> Unit = {
                                                    if (navigationState.topLevelRoute == Screen.Home) {
                                                        TopBarIconButton(
                                                            floating = useFloatingTopBar,
                                                            onClick = { navigator.navigate(Screen.History) },
                                                            modifier =
                                                                Modifier.tvFocusHighlight(
                                                                    CircleShape,
                                                                    scale = 1.08f,
                                                                    bringIntoView = false,
                                                                ),
                                                        ) {
                                                            Icon(
                                                                MetrolistIcons.Outlined.MusicHistory,
                                                                contentDescription = stringResource(Res.string.menu_history),
                                                                modifier = Modifier.size(24.dp),
                                                            )
                                                        }
                                                    }
                                                    if (navigationState.topLevelRoute == Screen.Library &&
                                                        downloadRequests.any { it.state.isRunnable }
                                                    ) {
                                                        TopBarIconButton(
                                                            floating = useFloatingTopBar,
                                                            onClick = { navigator.navigate(Screen.Downloads) },
                                                        ) {
                                                            Icon(
                                                                MetrolistIcons.Outlined.Download,
                                                                contentDescription = stringResource(Res.string.downloads),
                                                            )
                                                        }
                                                    }
                                                    if (Settings.shared.devtoolsUnlocked) {
                                                        TopBarIconButton(
                                                            floating = useFloatingTopBar,
                                                            onClick = { navigator.navigate(Screen.Devtools) },
                                                            modifier =
                                                                Modifier.tvFocusHighlight(
                                                                    CircleShape,
                                                                    scale = 1.08f,
                                                                    bringIntoView = false,
                                                                ),
                                                        ) {
                                                            Icon(
                                                                MetrolistIcons.Outlined.Settings,
                                                                contentDescription = stringResource(Res.string.devtools_title),
                                                                modifier = Modifier.size(24.dp),
                                                            )
                                                        }
                                                    }
                                                    if (Settings.shared.listenTogetherEnabled) {
                                                        TopBarIconButton(
                                                            floating = useFloatingTopBar,
                                                            onClick = { navigator.navigate(Screen.ListenTogether) },
                                                            modifier =
                                                                Modifier.tvFocusHighlight(
                                                                    CircleShape,
                                                                    scale = 1.08f,
                                                                    bringIntoView = false,
                                                                ),
                                                        ) {
                                                            Icon(
                                                                MetrolistIcons.Outlined.Group,
                                                                contentDescription = stringResource(Res.string.listen_together),
                                                                modifier = Modifier.size(24.dp),
                                                            )
                                                        }
                                                    }
                                                    if (!hasDesktopCaption) {
                                                        appAccountButton(if (useFloatingTopBar) Modifier.floatingBarCircle() else Modifier)
                                                    }
                                                }
                                                if (useFloatingTopBar) {
                                                    FloatingTopAppBar(
                                                        title = topBarTitle,
                                                        subtitle = topBarSubtitle,
                                                        actions = topBarActions,
                                                    )
                                                } else {
                                                    TopAppBar(
                                                        expandedHeight = TabTopBarHeight,
                                                        navigationIcon = {
                                                        },
                                                        title = topBarTitle,
                                                        subtitle = topBarSubtitle,
                                                        actions = topBarActions,
                                                        scrollBehavior = topAppBarScrollBehavior,
                                                        colors =
                                                            TopAppBarDefaults.topAppBarColors(
                                                                containerColor =
                                                                    if (pureBlack) {
                                                                        Color.Black
                                                                    } else {
                                                                        MaterialTheme.colorScheme.surfaceContainer
                                                                    },
                                                                scrolledContainerColor =
                                                                    if (pureBlack) {
                                                                        Color.Black
                                                                    } else {
                                                                        MaterialTheme.colorScheme.surfaceContainer
                                                                    },
                                                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                                                                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            ),
                                                    )
                                                }
                                            }
                                        },
                                        modifier =
                                            Modifier
                                                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                                                .onSizeChanged { size ->
                                                    if (hasBottomNavigation && !useFloatingNavBar) {
                                                        navigationBarContentInset =
                                                            (maxHeight - with(density) { size.height.toDp() }).coerceAtLeast(0.dp)
                                                    }
                                                },
                                    ) { scaffoldPadding ->
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .consumeWindowInsets(scaffoldPadding)
                                                .consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
                                        ) {
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .fillMaxSize(),
                                            ) {
                                                Box(
                                                    modifier =
                                                        Modifier
                                                            .fillMaxSize()
                                                            .then(
                                                                if (!isAndroidTv) {
                                                                    Modifier
                                                                        .focusProperties { canFocus = !isPlayerExpanded }
                                                                        .focusGroup()
                                                                } else {
                                                                    Modifier
                                                                },
                                                            ).focusRequester(contentFocusRequester)
                                                            .focusRestorer()
                                                            .then(
                                                                if (useNavigationRail && shouldShowNavigationBar) {
                                                                    Modifier.focusProperties {
                                                                        onExit = {
                                                                            if (requestedFocusDirection == directionToNavigationRail) {
                                                                                selectedRailFocusRequester.requestFocus()
                                                                            }
                                                                        }
                                                                    }
                                                                } else {
                                                                    Modifier
                                                                },
                                                            ).focusGroup(),
                                                ) {
                                                    val navEntryProvider =
                                                        remember(
                                                            navigator,
                                                            authManager,
                                                            context,
                                                            coroutineScope,
                                                            pendingSearchQuery,
                                                            pendingRoomCode,
                                                            floatingActionsBottomInset,
                                                            homeScrollToTopEvents,
                                                            libraryScrollToTopEvents,
                                                            libraryViewModel,
                                                        ) {
                                                            getEntryProvider(
                                                                navigator,
                                                                authManager,
                                                                context,
                                                                coroutineScope,
                                                                pendingSearchQuery,
                                                                pendingRoomCode,
                                                                floatingActionsBottomInset,
                                                                homeScrollToTopEvents,
                                                                libraryScrollToTopEvents,
                                                                libraryViewModel,
                                                                onSearchResultsVisible = { searchResultsVisible = it },
                                                            )
                                                        }
                                                    CompositionLocalProvider(LocalHostDefaultProvider provides routeHostDefaults) {
                                                        RootExitBackHandler(
                                                            enabled = !navigator.canGoBack && !playerHandlesBack,
                                                            onExit = onExitApp,
                                                        )
                                                        NavSharedTransitionLayout(Modifier.fillMaxSize(), Alignment.TopCenter) {
                                                            val onBack: () -> Unit = {
                                                                if (navigator.goBack() &&
                                                                    !navigationState.topLevelRoute.isVisibleNavigationScreen()
                                                                ) {
                                                                    val target = resolveAndPersistStartupScreen()
                                                                    navigationState.startRoute = target
                                                                    navigator.navigate(target)
                                                                }
                                                            }
                                                            val sceneState =
                                                                rememberSceneState(
                                                                    entries =
                                                                        navigationState.toEntries(navEntryProvider) {
                                                                            listOf(rememberDetailViewModelNavEntryDecorator())
                                                                        },
                                                                    sceneStrategies = remember { listOf(DialogSceneStrategy()) },
                                                                    onBack = onBack,
                                                                )
                                                            val backState = rememberNavigationEventState(sceneState)
                                                            val input = navigationState.containers.input
                                                            val backInProgress =
                                                                backState.transitionState is NavigationEventTransitionState.InProgress
                                                            SideEffect {
                                                                if (backInProgress) {
                                                                    input.beginBack()
                                                                } else {
                                                                    input.reset()
                                                                }
                                                            }
                                                            NavigationBackHandler(
                                                                sceneState = sceneState,
                                                                state = backState,
                                                                onBackCancelled = { input.cancelBack() },
                                                                onBackCompleted = onBack,
                                                            )
                                                            NavDisplay(
                                                                sceneState = sceneState,
                                                                navigationEventState = backState,
                                                                modifier =
                                                                    if (useDesktopUi) {
                                                                        Modifier
                                                                            .widthIn(
                                                                                max = DesktopContentMaxWidth,
                                                                            ).fillMaxSize()
                                                                    } else {
                                                                        Modifier
                                                                    },
                                                                transitionSpec = {
                                                                    val source =
                                                                        initialState.entries.lastOrNull()?.metadata?.get(
                                                                            NAVIGATION_ROUTE_METADATA_KEY,
                                                                        ) as? Screen
                                                                    val destination =
                                                                        targetState.entries.lastOrNull()?.metadata?.get(
                                                                            NAVIGATION_ROUTE_METADATA_KEY,
                                                                        ) as? Screen
                                                                    navigationMotion.transition(
                                                                        navigationState.containers.pagePattern(source, destination),
                                                                        origin = navigationState.containers.pageOrigin(destination),
                                                                    )
                                                                },
                                                                popTransitionSpec = {
                                                                    val source =
                                                                        targetState.entries.lastOrNull()?.metadata?.get(
                                                                            NAVIGATION_ROUTE_METADATA_KEY,
                                                                        ) as? Screen
                                                                    val destination =
                                                                        initialState.entries.lastOrNull()?.metadata?.get(
                                                                            NAVIGATION_ROUTE_METADATA_KEY,
                                                                        ) as? Screen
                                                                    navigationMotion.transition(
                                                                        navigationState.containers.pagePattern(source, destination),
                                                                        isPop = true,
                                                                        origin = navigationState.containers.pageOrigin(destination),
                                                                    )
                                                                },
                                                                predictivePopTransitionSpec = {
                                                                    val source =
                                                                        targetState.entries.lastOrNull()?.metadata?.get(
                                                                            NAVIGATION_ROUTE_METADATA_KEY,
                                                                        ) as? Screen
                                                                    val destination =
                                                                        initialState.entries.lastOrNull()?.metadata?.get(
                                                                            NAVIGATION_ROUTE_METADATA_KEY,
                                                                        ) as? Screen
                                                                    navigationMotion.predictiveBackTransition(
                                                                        navigationState.containers.pagePattern(source, destination),
                                                                        navigationState.containers.pageOrigin(destination),
                                                                    )
                                                                },
                                                            )
                                                        }
                                                    }
                                                }

                                                installedReleaseNotes?.let { release ->
                                                    ReleaseNotesSheet(
                                                        release = release,
                                                        onDismiss = {
                                                            installedReleaseNotes = null
                                                            AppUpdateManager.clearPendingRelease()
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        BottomSheet(
                            state = playerBottomSheetState,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .drawWithContent {
                                        val bottomInset =
                                            if (playerBottomSheetState.progress <= 0f) {
                                                navigationBottomInset.toPx()
                                            } else {
                                                0f
                                            }
                                        clipRect(bottom = (size.height - bottomInset).coerceAtLeast(0f)) {
                                            this@drawWithContent.drawContent()
                                        }
                                    }.then(
                                        if (!isAndroidTv ||
                                            (isLandscape && (Settings.shared.immersivePlayer || playerBottomSheetState.progress > 0f))
                                        ) {
                                            Modifier
                                        } else {
                                            Modifier.windowInsetsPadding(horizontalSystemBars.only(WindowInsetsSides.Horizontal))
                                        },
                                    ).zIndex(
                                        if (playerBottomSheetState.isDismissed || !shouldShowMiniPlayer || immersiveContent.value) {
                                            -1f
                                        } else {
                                            1f
                                        },
                                    ),
                            collapsedHeaderHeight = miniPlayerHeight,
                            background = {
                                if (hasMedia && !playerBottomSheetState.isCollapsed) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxSize()
                                                .drawBehind {
                                                    if (!playerBackgroundOpaque || !playerBottomSheetState.isSettledExpanded) {
                                                        drawRect(Color.Black.copy(alpha = 0.5f))
                                                    }
                                                },
                                    )
                                }
                            },
                            prepareDismissal = {
                                val stopDismissedRequest = playerConnection.prepareDismissal()
                                val onComplete: () -> Unit = {
                                    hapticManager.perform(HapticEvent.CONFIRM)
                                    stopDismissedRequest()
                                }
                                onComplete
                            },
                            isCollapsedContentClickable = false,
                            isExpandable = true,
                            isDraggableWhenCollapsed = true,
                            preserveContentWhenCollapsed = true,
                            morphCollapsedContainer = true,
                            collapsedContent = {
                                if (shouldRenderMiniPlayer) {
                                    MiniPlayer(
                                        useDesktopLayout = useDesktopMiniPlayer,
                                        onExpand = {
                                            focusManager.clearFocus(force = true)
                                            keyboardController?.hide()
                                            focusPlayPauseOnPlayerExpand = false
                                            playerBottomSheetState.expandSoft()
                                        },
                                        onDpadExpand = {
                                            focusManager.clearFocus(force = true)
                                            keyboardController?.hide()
                                            focusPlayPauseOnPlayerExpand = true
                                            playerBottomSheetState.expandSoft()
                                        },
                                        bottomSheetState = playerBottomSheetState,
                                        cardFocusRequester = miniPlayerFocusRequester,
                                        modifier =
                                            Modifier
                                                .windowInsetsPadding(horizontalSystemBars.only(WindowInsetsSides.Horizontal))
                                                .semantics {
                                                    customActions =
                                                        listOf(
                                                            CustomAccessibilityAction(stopPlaybackLabel) {
                                                                playerBottomSheetState.dismiss(playerConnection.prepareDismissal())
                                                                true
                                                            },
                                                        )
                                                }.onPreviewKeyEvent { event ->
                                                    if (isAndroidTv && event.key == Key.DirectionUp) {
                                                        event.type == KeyEventType.KeyUp ||
                                                            contentFocusRequester.requestFocus(FocusDirection.Up)
                                                    } else {
                                                        false
                                                    }
                                                }.layout { measurable, constraints ->
                                                    val start = miniPlayerRailPadding().roundToPx()
                                                    val child = measurable.measure(constraints.offset(horizontal = -start))
                                                    layout(child.width + start, child.height) { child.placeRelative(start, 0) }
                                                },
                                    )
                                }
                            },
                        ) {
                            val playerLifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
                            PlayerScreen(
                                isScreenVisible = playerLifecycleState.isAtLeast(Lifecycle.State.STARTED),
                                viewModel = playerViewModel,
                                bottomSheetState = playerBottomSheetState,
                                requestPlayPauseFocus = focusPlayPauseOnPlayerExpand,
                                onPlayPauseFocusRequested = { focusPlayPauseOnPlayerExpand = false },
                                onLyricsVisibleChange = { lyricsVisible = it },
                                onClose = {
                                    playerBottomSheetState.collapseSoft()
                                    if (usesDirectionalNavigation) {
                                        coroutineScope.launch {
                                            delay(250.milliseconds)
                                            runCatching { miniPlayerFocusRequester.requestFocus() }
                                        }
                                    }
                                },
                                onNavigateToAlbum = { albumId ->
                                    playerBottomSheetState.collapseSoft()
                                    navigator.navigate(Screen.Album(albumId))
                                },
                                onNavigateToArtist = { artistId ->
                                    playerBottomSheetState.collapseSoft()
                                    navigator.navigate(Screen.Artist(artistId))
                                },
                                onNavigateToListenTogether = {
                                    playerBottomSheetState.collapseSoft()
                                    navigator.navigate(Screen.ListenTogether)
                                },
                                onNavigateToIntegrations = {
                                    playerBottomSheetState.collapseSoft()
                                    navigator.navigate(Screen.Integrations)
                                },
                            )
                        }
                    }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier =
                            Modifier
                                .align(
                                    Alignment.BottomCenter,
                                ).windowInsetsPadding(horizontalSystemBars.only(WindowInsetsSides.Horizontal))
                                .padding(bottom = miniPlayerCollapsedBound + 16.dp)
                                .zIndex(100f),
                    )

                    PopupVisibility(visible = showContinuePlaybackPrompt) {
                        AlertDialog(
                            onDismissRequest = {
                                Settings.shared.pendingContinuePlayback = false
                                showContinuePlaybackPrompt = false
                            },
                            title = { Text(stringResource(Res.string.continue_playback_title)) },
                            text = {
                                Text(
                                    stringResource(
                                        Res.string.continue_playback_message,
                                        currentSong?.title.orEmpty(),
                                    ),
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        Settings.shared.pendingContinuePlayback = false
                                        showContinuePlaybackPrompt = false
                                        if (!playerConnection.isPlaying.value) {
                                            playerConnection.togglePlayPause()
                                        }
                                    },
                                    shapes = ButtonDefaults.shapes(),
                                ) {
                                    Text(stringResource(Res.string.play))
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        Settings.shared.pendingContinuePlayback = false
                                        showContinuePlaybackPrompt = false
                                    },
                                    shapes = ButtonDefaults.shapes(),
                                ) {
                                    Text(stringResource(Res.string.action_not_now))
                                }
                            },
                        )
                    }

                    BottomSheetMenu(state = menuState)

                    if (isInPipMode) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .zIndex(999f)
                                    .windowInsetsPadding(horizontalSystemBars.only(WindowInsetsSides.Horizontal))
                                    .background(Color.Black),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (hasVideo && videoPlayer != null) {
                                MusicVideoSurface(
                                    player = videoPlayer,
                                    artworkUrl = currentSong?.thumbnail,
                                    modifier = Modifier.fillMaxSize(),
                                    fillContainer = true,
                                    useSurfaceView = true,
                                    showControls = false,
                                    isPip = true,
                                    cornerRadius = 0.dp,
                                )
                            } else {
                                AsyncImage(
                                    url = currentSong?.thumbnail,
                                    contentDescription = currentSong?.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
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
private fun ListenTogetherPlaybackRequestHandler(playerConnection: PlayerConnection) {
    var request by remember { mutableStateOf<ListenTogetherPlaybackRequest?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(playerConnection) {
        playerConnection.listenTogetherPlaybackRequests.collect { request = it }
    }

    PopupContent(request) { pending ->
        AlertDialog(
            onDismissRequest = { request = null },
            icon = {
                Icon(
                    imageVector = MetrolistIcons.Filled.Groups,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text(stringResource(Res.string.listen_together_guest_choice_title)) },
            text = { Text(stringResource(Res.string.listen_together_guest_choice_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        AndroidListenTogetherManager.suggestTrack(pending.song)
                        request = null
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.listen_together_guest_queue))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { request = null }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(Res.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            request = null
                            scope.launch {
                                if (AndroidListenTogetherManager.leaveRoomAndWait()) {
                                    if (pending.queue.isNotEmpty()) {
                                        playerConnection.playQueue(
                                            pending.queue,
                                            pending.startIndex,
                                            pending.queueTitle,
                                            shuffleModeEnabled = pending.shuffleModeEnabled,
                                        )
                                    } else {
                                        playerConnection.playSong(pending.song, "")
                                    }
                                }
                            }
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(Res.string.listen_together_guest_solo))
                    }
                }
            },
        )
    }
}

@Composable
private fun TopBarProfileAvatar(
    accountName: String,
    accountThumbnail: String,
    hasPendingUpdate: Boolean,
) {
    Box(modifier = Modifier.size(30.dp)) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (accountName.isNotEmpty() && accountThumbnail.isNotEmpty()) {
                val context = LocalContext.current
                val startupPresentation = LocalStartupPresentation.current
                var avatarReady by remember(accountThumbnail) { mutableStateOf(false) }
                var avatarLoaded by remember(accountThumbnail) { mutableStateOf(false) }
                SideEffect { startupPresentation?.avatarReady = avatarReady }
                val request =
                    remember(context, accountThumbnail) {
                        ImageRequest
                            .Builder(context)
                            .data(accountThumbnail)
                            .crossfade(false)
                            .build()
                    }
                if (!avatarLoaded) {
                    Icon(
                        MetrolistIcons.Outlined.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
                CoilAsyncImage(
                    model = request,
                    contentDescription = stringResource(Res.string.profile),
                    onSuccess = {
                        avatarLoaded = true
                        avatarReady = true
                    },
                    onError = { avatarReady = true },
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                Icon(
                    MetrolistIcons.Outlined.AccountCircle,
                    contentDescription = stringResource(Res.string.profile),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        if (hasPendingUpdate) {
            Box(
                modifier =
                    Modifier
                        .size(14.dp)
                        .align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "!",
                    color = MaterialTheme.colorScheme.onError,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun AppTitleWithUpdateWarning(
    title: String,
    showWarning: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLargeEmphasized,
        )
        if (showWarning) {
            Text(
                text = stringResource(Res.string.updates_pending_parenthetical),
                modifier = Modifier.padding(start = 8.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun GitHubRelease.changelogSnippet(): String {
    val lines =
        body
            .orEmpty()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
    val versionHeading =
        lines.indexOfFirst { line ->
            line.trimStart('#', ' ').startsWith("Version $version", ignoreCase = true)
        }
    val firstChangesHeading =
        lines.indexOfFirst { line ->
            line.startsWith('#') && !line.contains("maintenance mode", ignoreCase = true)
        }
    val startIndex =
        when {
            versionHeading >= 0 -> versionHeading + 1
            firstChangesHeading >= 0 -> firstChangesHeading
            else -> 0
        }
    return lines
        .subList(startIndex, lines.size)
        .take(15)
        .joinToString("\n")
}

val navTabMetadata: Map<String, Any> = mapOf("nav_tab" to true)

sealed class IncomingDestination {
    data class Podcast(
        val id: String,
    ) : IncomingDestination()

    object Home : IncomingDestination()

    object Library : IncomingDestination()

    object Downloads : IncomingDestination()

    data class PlaylistImport(
        val resultMessage: String?,
    ) : IncomingDestination()

    object ListenTogetherRoom : IncomingDestination()

    object Updates : IncomingDestination()

    object Settings : IncomingDestination()

    object Login : IncomingDestination()

    object BackupRestore : IncomingDestination()

    object Recognition : IncomingDestination()

    object Player : IncomingDestination()

    data class Search(
        val query: String?,
    ) : IncomingDestination()

    data class PlaySearch(
        val query: String,
    ) : IncomingDestination()

    data class Album(
        val id: String,
    ) : IncomingDestination()

    data class Artist(
        val id: String,
    ) : IncomingDestination()

    data class Playlist(
        val id: String,
    ) : IncomingDestination()

    data class AutoPlaylist(
        val type: String,
    ) : IncomingDestination()

    data class Browse(
        val id: String,
        val params: String?,
        val title: String?,
    ) : IncomingDestination()

    data class ListenTogether(
        val roomCode: String,
    ) : IncomingDestination()

    data class PlayVideo(
        val videoId: String,
        val playlistId: String?,
        val startPositionMs: Long = 0L,
    ) : IncomingDestination()
}

fun Intent.toIncomingDestination(): IncomingDestination? {
    if (action == MainActivity.ACTION_OPEN_WIDGET_TARGET) {
        return widgetTargetDestination()
    }

    val urls =
        when (action) {
            Intent.ACTION_VIEW, NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                listOfNotNull(dataString)
            }

            Intent.ACTION_SEND -> {
                if (type == "text/plain") {
                    getCharSequenceExtra(Intent.EXTRA_TEXT)
                        ?.toString()
                        ?.trim()
                        ?.split(Regex("\\s+"))
                        .orEmpty()
                } else {
                    emptyList()
                }
            }

            else -> {
                emptyList()
            }
        }
    urls.firstNotNullOfOrNull(::incomingUrlDestination)?.let { return it }

    return when (action) {
        MainActivity.ACTION_SEARCH -> IncomingDestination.Search(null)
        MainActivity.ACTION_LIBRARY -> IncomingDestination.Library
        MainActivity.ACTION_PLAYLIST_IMPORT -> IncomingDestination.PlaylistImport(getStringExtra(MainActivity.EXTRA_IMPORT_RESULT_MESSAGE))
        MainActivity.ACTION_DOWNLOADS -> IncomingDestination.Downloads
        MainActivity.ACTION_LISTEN_TOGETHER -> IncomingDestination.ListenTogetherRoom
        MainActivity.ACTION_UPDATES -> IncomingDestination.Updates
        MainActivity.ACTION_LOGIN -> IncomingDestination.Login
        Intent.ACTION_APPLICATION_PREFERENCES -> IncomingDestination.Settings
        MainActivity.ACTION_BACKUP_RESTORE -> IncomingDestination.BackupRestore
        MainActivity.ACTION_RECOGNITION -> IncomingDestination.Recognition
        else -> null
    }
}

private fun incomingUrlDestination(rawUri: String): IncomingDestination? {
    parseYouTubePodcastId(rawUri)?.let { return IncomingDestination.Podcast(it) }
    parseListenTogetherInvitation(rawUri)?.let { return IncomingDestination.ListenTogether(it) }
    extractListenTogetherRoomCode(rawUri)?.let { return IncomingDestination.ListenTogether(it) }
    when (val parsed = parseYouTubeUrl(rawUri)) {
        is YouTubeUrlParser.ParsedUrl.Video -> {
            return IncomingDestination.PlayVideo(parsed.id, null, parseYouTubeStartPositionMs(rawUri))
        }

        is YouTubeUrlParser.ParsedUrl.Playlist -> {
            return IncomingDestination.Playlist(parsed.id)
        }

        is YouTubeUrlParser.ParsedUrl.Album -> {
            return IncomingDestination.Album(parsed.id)
        }

        is YouTubeUrlParser.ParsedUrl.Artist -> {
            return IncomingDestination.Artist(parsed.id)
        }

        null -> {}
    }
    val uri = rawUri.toUri()
    val host = uri.host?.lowercase()
    if ((uri.scheme == "https" || uri.scheme == "http") &&
        (host == "youtube.com" || host?.endsWith(".youtube.com") == true) &&
        uri.path == "/search"
    ) {
        return uri
            .getQueryParameter("q")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(IncomingDestination::Search)
    }
    return null
}

fun Intent.widgetTargetDestination(): IncomingDestination? {
    val id = getStringExtra(MainActivity.EXTRA_WIDGET_TARGET_ID) ?: return null
    return when (getStringExtra(MainActivity.EXTRA_WIDGET_TARGET_TYPE)) {
        "ALBUM" -> IncomingDestination.Album(id)

        "ARTIST" -> IncomingDestination.Artist(id)

        "PLAYLIST" -> IncomingDestination.Playlist(id)

        "AUTO_PLAYLIST" -> IncomingDestination.AutoPlaylist(id)

        PlaylistWidgetReceiver.TARGET_TYPE_LOCAL,
        PlaylistWidgetReceiver.TARGET_TYPE_ONLINE,
        PlaylistWidgetReceiver.TARGET_TYPE_COLLECTION,
        -> IncomingDestination.Playlist(id)

        PlaylistWidgetReceiver.TARGET_TYPE_LIKED -> IncomingDestination.AutoPlaylist("liked")

        PlaylistWidgetReceiver.TARGET_TYPE_DOWNLOADED -> IncomingDestination.AutoPlaylist("downloaded")

        PlaylistWidgetReceiver.TARGET_TYPE_TOP -> IncomingDestination.AutoPlaylist("top100")

        "SONG" -> IncomingDestination.PlayVideo(id, null)

        else -> null
    }
}

fun String.sanitizeRoomCode(): String = filter { it.isLetterOrDigit() }.uppercase().take(8)

fun resolveAndPersistStartupScreen(): Screen {
    val default =
        when {
            Settings.shared.defaultOpenTab == "MYSTERIOUS" -> {
                Screen.EasterEgg
            }

            else -> {
                when (defaultOpenTabRoot(Settings.shared.defaultOpenTab)) {
                    "SEARCH" -> Screen.Search
                    "LIBRARY" -> Screen.Library
                    else -> Screen.Home
                }
            }
        }
    Settings.shared.isExpanded = false
    return default
}

fun NavKey.isVisibleNavigationScreen(): Boolean =
    when (this) {
        Screen.Home, Screen.Search, Screen.Library, Screen.EasterEgg -> true
        else -> false
    }

fun getEntryProvider(
    navigator: Navigator,
    authManager: AuthManager,
    context: Context,
    scope: CoroutineScope,
    pendingSearchQuery: String?,
    pendingRoomCode: String?,
    floatingActionsBottomInset: State<Dp>,
    homeScrollToTopEvents: SharedFlow<Long>,
    libraryScrollToTopEvents: SharedFlow<Long>,
    libraryViewModel: LibraryViewModel,
    onSearchResultsVisible: (Boolean) -> Unit = {},
): (NavKey) -> NavEntry<NavKey> =
    entryProvider<NavKey> {
        entry<Screen.Home>(metadata = navTabMetadata) {
            HomeScreen(
                floatingActionsBottomInset = floatingActionsBottomInset,
                onNavigateToAlbum = { albumId -> navigator.navigate(Screen.Album(albumId)) },
                onNavigateToArtist = { artistId -> navigator.navigate(Screen.Artist(artistId)) },
                onNavigateToPlaylist = { id, thumbnail -> navigator.navigate(Screen.Playlist(id, thumbnailHint = thumbnail)) },
                onNavigateToPodcast = { podcastId -> navigator.navigate(Screen.Podcast(podcastId)) },
                onNavigateToRecognition = { navigator.navigate(Screen.Recognition) },
                onNavigateToLibrary = { filter ->
                    filter?.let { Settings.shared.libraryFilter = it }
                    navigator.navigate(Screen.Library)
                },
                onNavigateToBrowse = { browseId, params, title ->
                    navigator.navigate(Screen.Browse(browseId, params, title))
                },
                onNavigateToVideoBrowse = { browseId, params, title ->
                    navigator.navigate(Screen.Browse(browseId, params, title))
                },
                onNavigateToHomeSection = { shelf ->
                    navigator.navigate(Screen.HomeSection(shelf.pageKey, shelf.title, shelf.toHomeSection()))
                },
                scrollToTopEvents = homeScrollToTopEvents,
            )
        }

        entry<Screen.EasterEgg>(metadata = navTabMetadata) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.mysterious_fourth_tab_description))
            }
        }

        entry<Screen.HomeSection>(metadata = navTabMetadata + detailViewModelMetadata) { section ->
            HomeSectionScreen(
                sectionKey = section.key,
                section = section.section,
                title = section.title,
                onBackClick = { navigator.goBack() },
                onNavigateToAlbum = { albumId -> navigator.navigate(Screen.Album(albumId)) },
                onNavigateToArtist = { artistId -> navigator.navigate(Screen.Artist(artistId)) },
                onNavigateToPlaylist = { id, thumbnail -> navigator.navigate(Screen.Playlist(id, thumbnailHint = thumbnail)) },
                onNavigateToPodcast = { podcastId -> navigator.navigate(Screen.Podcast(podcastId)) },
                onNavigateToBrowse = { browseId, params, title ->
                    navigator.navigate(Screen.Browse(browseId, params, title))
                },
            )
        }

        entry<Screen.Browse>(metadata = navTabMetadata + detailViewModelMetadata) { browse ->
            BrowseScreen(
                browseId = browse.browseId,
                params = browse.params,
                title = browse.title,
                startVideo = browse.startVideo,
                onBackClick = { navigator.goBack() },
                onNavigateToAlbum = { albumId -> navigator.navigate(Screen.Album(albumId)) },
                onNavigateToArtist = { artistId -> navigator.navigate(Screen.Artist(artistId)) },
                onNavigateToPlaylist = { id, thumbnail -> navigator.navigate(Screen.Playlist(id, thumbnailHint = thumbnail)) },
                onNavigateToPodcast = { podcastId -> navigator.navigate(Screen.Podcast(podcastId)) },
                onNavigateToBrowse = { browseId, params, title ->
                    navigator.navigate(Screen.Browse(browseId, params, title))
                },
            )
        }

        entry<Screen.Library>(metadata = navTabMetadata) {
            LibraryScreen(
                viewModel = libraryViewModel,
                floatingActionsBottomInset = floatingActionsBottomInset,
                scrollToTopEvents = libraryScrollToTopEvents,
                onNavigateToAlbum = { albumId -> navigator.navigate(Screen.Album(albumId)) },
                onNavigateToArtist = { artistId -> navigator.navigate(Screen.Artist(artistId)) },
                onNavigateToPlaylist = { id, thumbnail -> navigator.navigate(Screen.Playlist(id, thumbnailHint = thumbnail)) },
                onNavigateToPodcast = { podcastId -> navigator.navigate(Screen.Podcast(podcastId)) },
                onNavigateToPlaylistImport = { navigator.navigate(Screen.PlaylistImport) },
                onNavigateToAutoPlaylist = { type ->
                    // Keep podcast auto playlists on the Library stack so Back returns to the podcast library.
                    navigator.navigate(Screen.Library)
                    navigator.navigate(Screen.AutoPlaylist(type))
                },
            )
        }

        entry<Screen.Search>(metadata = navTabMetadata) {
            SearchScreen(
                navigator = navigator,
                floatingActionsBottomInset = floatingActionsBottomInset,
                initialQuery = pendingSearchQuery,
                onSearchResultsVisible = onSearchResultsVisible,
            )
        }

        entry<Screen.Recognition>(metadata = navTabMetadata) {
            RecognitionScreen(
                onNavigateBack = { navigator.goBack() },
                onNavigateToHistory = { navigator.navigate(Screen.RecognitionHistory) },
            )
        }
        entry<Screen.RecognitionHistory>(metadata = navTabMetadata) {
            RecognitionHistoryScreen(onNavigateBack = { navigator.goBack() })
        }
        entry<Screen.History>(metadata = navTabMetadata) {
            HistoryScreen(onNavigateBack = { navigator.goBack() })
        }

        entry<Screen.Login>(metadata = navTabMetadata) {
            LoginScreen(
                onNavigateBack = { navigator.goBack() },
                onNavigateToTokenLogin = { navigator.navigate(Screen.TokenLogin) },
            )
        }

        entry<Screen.SwitchChannel>(metadata = navTabMetadata) {
            LoginScreen(
                onNavigateBack = { navigator.goBack() },
                onNavigateToTokenLogin = { navigator.navigate(Screen.TokenLogin) },
                isSwitchingChannel = true,
            )
        }

        entry<Screen.TokenLogin>(metadata = navTabMetadata) {
            val miniPlayerInset = LocalMiniPlayerBottomInset.current
            TokenLoginScreen(
                onNavigateBack = { navigator.goBack() },
                bottomContentPadding = miniPlayerInset,
            )
        }

        entry<Screen.Album>(metadata = navTabMetadata + detailViewModelMetadata) { album ->
            CompositionLocalProvider(
                LocalNavVisibilityScope provides LocalNavAnimatedContentScope.current,
                LocalAlbumArtworkSource provides album.artworkSource,
            ) {
                AlbumScreen(
                    albumId = album.id,
                    onBackClick = { navigator.goBack() },
                    onArtistClick = { artistId -> navigator.navigate(Screen.Artist(artistId)) },
                    onAlbumClick = { newAlbumId -> navigator.navigate(Screen.Album(newAlbumId)) },
                    onAlbumArtworkClick = { id, token -> navigator.navigate(Screen.Album(id, artworkSource = token)) },
                )
            }
        }

        entry<Screen.Artist>(metadata = navTabMetadata + detailViewModelMetadata) { artist ->
            CompositionLocalProvider(LocalNavVisibilityScope provides LocalNavAnimatedContentScope.current) {
                ArtistScreenHost(
                    artistId = artist.id,
                    onBackClick = { navigator.goBack() },
                    onNavigateToAlbum = { albumId, albums, token ->
                        navigator.navigateFrom(
                            artist,
                            Screen.Album(
                                id = albumId,
                                gallery = albums.map { AlbumSeed(it.id, it.title, it.thumbnail) },
                                artworkSource = token,
                            ),
                        )
                    },
                    onNavigateToPlaylist = { id, thumbnail ->
                        navigator.navigateFrom(artist, Screen.Playlist(id, thumbnailHint = thumbnail))
                    },
                    onNavigateToArtist = { artistId -> navigator.navigateFrom(artist, Screen.Artist(artistId)) },
                    onNavigateToPodcast = { podcastId -> navigator.navigateFrom(artist, Screen.Podcast(podcastId)) },
                    onNavigateToBrowse = { browseId, params, title ->
                        navigator.navigateFrom(artist, Screen.Browse(browseId, params, title))
                    },
                    onNavigateToAboutArtist = { item ->
                        navigator.navigateFrom(artist, Screen.AboutArtist(item.id, item.title, item))
                    },
                )
            }
        }

        entry<Screen.AboutArtist>(metadata = navTabMetadata + detailViewModelMetadata) { aboutArtist ->
            CompositionLocalProvider(LocalNavVisibilityScope provides LocalNavAnimatedContentScope.current) {
                AboutArtistScreen(
                    artistId = aboutArtist.id,
                    artistName = aboutArtist.name,
                    initialInfo = aboutArtist.artist,
                    onBackClick = { navigator.goBack() },
                )
            }
        }

        entry<Screen.Integrations>(metadata = navTabMetadata) {
            IntegrationsScreen(
                onNavigateBack = { navigator.goBack() },
                onNavigateToListenTogether = { navigator.navigate(Screen.ListenTogetherSettings) },
            )
        }

        entry<Screen.Discord>(metadata = navTabMetadata) {
            SettingsIntegrationsScreen(
                onNavigateBack = { navigator.goBack() },
                onNavigateToListenTogether = { navigator.navigate(Screen.ListenTogetherSettings) },
                initialPage = SettingsPageId.DISCORD,
            )
        }

        entry<Screen.Playlist>(metadata = navTabMetadata + detailViewModelMetadata) { playlist ->
            val builtin = BuiltinPlaylist.fromId(playlist.id)
            val collectionType = builtin?.collectionType
            when {
                builtin == BuiltinPlaylist.HISTORY -> {
                    HistoryScreen(onNavigateBack = { navigator.goBack() })
                }

                collectionType != null -> {
                    AutoPlaylistScreen(
                        type = collectionType,
                        onBackClick = { navigator.goBack() },
                        onArtistClick = { navigator.navigate(Screen.Artist(it)) },
                        onOpenDownloadQueue = { navigator.navigate(Screen.Downloads) },
                    )
                }

                else -> {
                    PlaylistScreen(
                        playlistId = playlist.id,
                        thumbnailHint = playlist.thumbnailHint,
                        onBackClick = { navigator.goBack() },
                        onArtistClick = { artistId -> navigator.navigate(Screen.Artist(artistId)) },
                        onAlbumClick = { albumId -> navigator.navigate(Screen.Album(albumId)) },
                    )
                }
            }
        }

        entry<Screen.Podcast>(metadata = navTabMetadata) { podcast ->
            PodcastScreen(
                podcastId = podcast.id,
                onNavigateBack = { navigator.goBack() },
                onChannelClick = { navigator.navigate(Screen.Artist(it)) },
            )
        }

        entry<Screen.AutoPlaylist>(metadata = navTabMetadata) { autoPlaylist ->
            AutoPlaylistScreen(
                type = autoPlaylist.type,
                onBackClick = { navigator.goBack() },
                onArtistClick = { artistId -> navigator.navigate(Screen.Artist(artistId)) },
                onOpenDownloadQueue = { navigator.navigate(Screen.Downloads) },
            )
        }

        entry<Screen.About>(metadata = navTabMetadata) {
            AndroidAboutScreen(
                onNavigateBack = { navigator.goBack() },
                onDevtoolsUnlocked = { navigator.navigate(Screen.Devtools) },
            )
        }

        entry<Screen.Updates>(metadata = navTabMetadata) {
            UpdatesScreen(
                onNavigateBack = { navigator.goBack() },
                onOpenDetails = { navigator.navigate(Screen.UpdateDetails) },
            )
        }

        entry<Screen.UpdateDetails>(metadata = navTabMetadata) {
            UpdateDetailsScreen(onNavigateBack = { navigator.goBack() })
        }

        entry<Screen.LastFm>(metadata = navTabMetadata) {
            SettingsIntegrationsScreen(
                onNavigateBack = { navigator.goBack() },
                onNavigateToListenTogether = { navigator.navigate(Screen.ListenTogetherSettings) },
                initialPage = SettingsPageId.LAST_FM,
            )
        }

        entry<Screen.ListenBrainz>(metadata = navTabMetadata) {
            SettingsIntegrationsScreen(
                onNavigateBack = { navigator.goBack() },
                onNavigateToListenTogether = { navigator.navigate(Screen.ListenTogetherSettings) },
                initialPage = SettingsPageId.LISTEN_BRAINZ,
            )
        }

        entry<Screen.ListenTogether>(metadata = navTabMetadata) {
            ListenTogetherScreen(
                initialRoomCode = pendingRoomCode,
                onNavigateBack = { navigator.goBack() },
                onNavigateToSettings = { navigator.navigate(Screen.ListenTogetherSettings) },
            )
        }

        entry<Screen.ListenTogetherSettings>(metadata = navTabMetadata) {
            ListenTogetherSettingsScreen(
                onNavigateBack = { navigator.goBack() },
                onNavigateToServer = { navigator.navigate(Screen.ListenTogetherServer) },
                onNavigateToBlockedUsers = { navigator.navigate(Screen.ListenTogetherBlockedUsers) },
                onNavigateToLogs = { navigator.navigate(Screen.ListenTogetherLogs) },
            )
        }

        entry<Screen.ListenTogetherServer>(metadata = navTabMetadata) {
            ListenTogetherServerScreen(onNavigateBack = { navigator.goBack() })
        }

        entry<Screen.ListenTogetherBlockedUsers>(metadata = navTabMetadata) {
            ListenTogetherBlockedUsersScreen(onNavigateBack = { navigator.goBack() })
        }

        entry<Screen.ListenTogetherLogs>(metadata = navTabMetadata) {
            ListenTogetherLogsScreen(onNavigateBack = { navigator.goBack() })
        }

        entry<Screen.BackupRestore>(metadata = navTabMetadata) {
            BackupRestoreScreen(onNavigateBack = { navigator.goBack() })
        }

        entry<Screen.PlaylistImport>(metadata = navTabMetadata) {
            PlaylistImportScreen(onNavigateBack = { navigator.goBack() })
        }

        entry<Screen.AndroidAuto>(metadata = navTabMetadata) {
            AndroidAutoScreen(onNavigateBack = { navigator.goBack() })
        }

        entry<Screen.Settings>(metadata = navTabMetadata) {
            SettingsScreen(
                onNavigateBack = {
                    val target = resolveAndPersistStartupScreen()
                    navigator.state.startRoute = target
                    navigator.goBack()
                    if (!navigator.state.topLevelRoute.isVisibleNavigationScreen()) navigator.navigate(target)
                },
                onNavigateToDownloads = { navigator.navigate(Screen.AutoPlaylist("downloaded")) },
                onNavigateToArtist = { artistId -> navigator.navigate(Screen.Artist(artistId)) },
            )
        }

        entry<Screen.Statistics>(metadata = navTabMetadata) {
            StatsScreen(
                onNavigateBack = { navigator.goBack() },
                onOpenDetail = {
                    detail,
                    period,
                    dateRange,
                    mode,
                    ->
                    navigator.navigate(Screen.StatisticsDetail(detail, period, dateRange, mode))
                },
            )
        }

        entry<Screen.StatisticsDetail>(metadata = navTabMetadata) { detail ->
            StatsDetailScreen(
                detail = detail.detail,
                period = detail.period,
                dateRange = detail.dateRange,
                mode = detail.mode,
                onNavigateBack = { navigator.goBack() },
            )
        }

        entry<Screen.Devtools>(metadata = navTabMetadata) {
            DevtoolsSettingsScreen(
                onNavigateBack = { navigator.goBack() },
                onNavigateToPlaybackBenchmark = { navigator.navigate(Screen.PlaybackBenchmark) },
            )
        }
        entry<Screen.PlaybackBenchmark>(metadata = navTabMetadata) {
            PlaybackBenchmarkSettingsScreen(onNavigateBack = { navigator.goBack() })
        }

        entry<Screen.Downloads>(metadata = navTabMetadata) {
            DownloadsScreen(onNavigateBack = { navigator.goBack() })
        }

        entry<Screen.Storage>(metadata = navTabMetadata) {
            StorageSettingsScreen(
                onNavigateBack = { navigator.goBack() },
                onNavigateToDownloads = { navigator.navigate(Screen.AutoPlaylist("downloaded")) },
            )
        }
    }.let { provider ->
        { route ->
            val entry = provider(route)
            NavEntry(
                key = route,
                contentKey = entry.contentKey,
                metadata = entry.metadata + (NAVIGATION_ROUTE_METADATA_KEY to route),
            ) {
                TvNavigationFocus(
                    requestInitialFocus = !route.isVisibleNavigationScreen(),
                    hasFocusableContent = route != Screen.EasterEgg,
                ) {
                    NavigationDestination(route as Screen, LocalNavAnimatedContentScope.current) { entry.Content() }
                }
            }
        }
    }

@Composable
private fun NavigationContentProvider(
    miniPlayerInset: () -> Dp,
    vararg values: ProvidedValue<*>,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalMiniPlayerBottomInset provides miniPlayerInset(),
        *values,
    ) { NavSharedTransitionLayout(content = content) }
}

val LocalImmersiveContent = staticCompositionLocalOf<MutableState<Boolean>> { mutableStateOf(false) }
