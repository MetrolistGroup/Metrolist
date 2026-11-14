@file:Suppress("FunctionName")

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import com.metrolist.innertubex.utils.YouTubeUrlParser
import com.metrolist.shared.api.artwork.AnimatedArtworkService
import com.metrolist.shared.auth.YouTubeSessionState
import com.metrolist.shared.constants.MiniPlayerHeight
import com.metrolist.shared.constants.ModernMiniPlayerBottomSpacing
import com.metrolist.shared.constants.modernMiniPlayerHeight
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.isHistoryPlaylistId
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.account
import com.metrolist.shared.generated.resources.action_login
import com.metrolist.shared.generated.resources.action_not_now
import com.metrolist.shared.generated.resources.added_songs_to_playlist
import com.metrolist.shared.generated.resources.app_font_import_custom
import com.metrolist.shared.generated.resources.app_font_picker_desc
import com.metrolist.shared.generated.resources.app_font_reset
import com.metrolist.shared.generated.resources.app_font_reset_description
import com.metrolist.shared.generated.resources.app_language_system
import com.metrolist.shared.generated.resources.app_name
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.calculating
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.clear
import com.metrolist.shared.generated.resources.continue_playback_message
import com.metrolist.shared.generated.resources.continue_playback_title
import com.metrolist.shared.generated.resources.device_language
import com.metrolist.shared.generated.resources.devtools_title
import com.metrolist.shared.generated.resources.downloaded
import com.metrolist.shared.generated.resources.error
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.generated.resources.error_unknown
import com.metrolist.shared.generated.resources.export_logs
import com.metrolist.shared.generated.resources.filter_library
import com.metrolist.shared.generated.resources.home
import com.metrolist.shared.generated.resources.library
import com.metrolist.shared.generated.resources.liked_songs
import com.metrolist.shared.generated.resources.listen_together
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.logo
import com.metrolist.shared.generated.resources.mysterious_fourth_tab
import com.metrolist.shared.generated.resources.mysterious_fourth_tab_description
import com.metrolist.shared.generated.resources.offline_mode
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.playback_error_no_playable_stream
import com.metrolist.shared.generated.resources.playback_error_unable_to_play_link
import com.metrolist.shared.generated.resources.queue
import com.metrolist.shared.generated.resources.queued_downloads
import com.metrolist.shared.generated.resources.removed_downloads
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.generated.resources.search_history_removal_failed
import com.metrolist.shared.generated.resources.settings_diagnostic_logging_description
import com.metrolist.shared.generated.resources.settings_storage_summary
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.generated.resources.subscribed_to_artist
import com.metrolist.shared.generated.resources.youtube_session_expired_message
import com.metrolist.shared.generated.resources.youtube_session_expired_title
import com.metrolist.shared.listentogether.ListenTogetherManager
import com.metrolist.shared.listentogether.ListenTogetherRoomRole
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.isAvailableOffline
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.playback.LocalPlayerSession
import com.metrolist.shared.playlist.PlaylistPartiallyUpdatedException
import com.metrolist.shared.playlist.deleteStoredPlaylist
import com.metrolist.shared.playlist.renameStoredPlaylist
import com.metrolist.shared.playlist.updateStoredPlaylist
import com.metrolist.shared.playlistimport.PlaylistImportProgressCard
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.settings.MiniPlayerType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.defaultOpenTabRoot
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.state.UploadNotificationState
import com.metrolist.shared.state.rememberArtistSubscription
import com.metrolist.shared.state.toggleArtistSubscription
import com.metrolist.shared.storage.isAvailableCachedAudio
import com.metrolist.shared.storage.isAvailableDownload
import com.metrolist.shared.ui.TokenLoginScreen
import com.metrolist.shared.ui.YouTubeSyncSnackbar
import com.metrolist.shared.ui.components.AccountPopupDialog
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.BottomSheet
import com.metrolist.shared.ui.components.COLLAPSED_ANCHOR
import com.metrolist.shared.ui.components.ChipShelf
import com.metrolist.shared.ui.components.EXPANDED_ANCHOR
import com.metrolist.shared.ui.components.HomeLoadingPlaceholder
import com.metrolist.shared.ui.components.MiniPlayer
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.RefreshIndicator
import com.metrolist.shared.ui.components.SongSwipeActions
import com.metrolist.shared.ui.components.SwipeBackLayout
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.lyrics.LyricsTranslationSnackbar
import com.metrolist.shared.ui.components.rememberBottomSheetState
import com.metrolist.shared.ui.design.expressive.components.ExpressiveMiniPlayerHeight
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.listentogether.ListenTogetherGlobalEvents
import com.metrolist.shared.ui.menu.HiddenHomeItemsSnackbar
import com.metrolist.shared.ui.navigation.AdaptiveFormFactor
import com.metrolist.shared.ui.navigation.AdaptiveNavigationItem
import com.metrolist.shared.ui.navigation.AdaptiveNavigationScaffold
import com.metrolist.shared.ui.navigation.LocalContentObscured
import com.metrolist.shared.ui.navigation.LocalNavigationContainerState
import com.metrolist.shared.ui.navigation.NavSharedTransitionLayout
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.navigation.NavigationContainerState
import com.metrolist.shared.ui.navigation.NavigationContentProvider
import com.metrolist.shared.ui.navigation.NavigationDestination
import com.metrolist.shared.ui.navigation.currentAdaptiveLayout
import com.metrolist.shared.ui.navigation.isNavigationBar
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.navigation.rememberNavigationMotion
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.playback.PlaybackControllerPlayerSession
import com.metrolist.shared.ui.playback.PlaybackUiState
import com.metrolist.shared.ui.player.LocalPlayerPlatform
import com.metrolist.shared.ui.player.PlayerScreen
import com.metrolist.shared.ui.player.collectWhilePlayerActive
import com.metrolist.shared.ui.screens.AboutScreen
import com.metrolist.shared.ui.screens.HomeContent
import com.metrolist.shared.ui.screens.SearchScreen
import com.metrolist.shared.ui.screens.homeContentText
import com.metrolist.shared.ui.screens.pageKey
import com.metrolist.shared.ui.screens.searchScreenText
import com.metrolist.shared.ui.screens.toHomeSection
import com.metrolist.shared.ui.settings.DevtoolsEnabledSwitch
import com.metrolist.shared.ui.settings.IntegrationPlaybackState
import com.metrolist.shared.ui.settings.IosPlayerVisibilityEffect
import com.metrolist.shared.ui.settings.LocalSettingsPageBottomPadding
import com.metrolist.shared.ui.settings.PlaybackEventLogSection
import com.metrolist.shared.ui.settings.PlayerLyricsSettingsPreview
import com.metrolist.shared.ui.settings.SettingsChangeEffect
import com.metrolist.shared.ui.settings.SettingsChoice
import com.metrolist.shared.ui.settings.SettingsHostChoice
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.settings.SettingsPlatform
import com.metrolist.shared.ui.settings.SharedIntegrationsHost
import com.metrolist.shared.ui.settings.SharedIntegrationsScreen
import com.metrolist.shared.ui.settings.SharedSettingsHost
import com.metrolist.shared.ui.settings.SharedSettingsScreen
import com.metrolist.shared.ui.settings.applyIosAppLanguage
import com.metrolist.shared.ui.settings.rememberIosAppIconActions
import com.metrolist.shared.ui.settings.rememberIosFontActions
import com.metrolist.shared.ui.settings.rememberIosLogExportAction
import com.metrolist.shared.ui.theme.MdSpacing
import com.metrolist.shared.ui.theme.MetrolistTheme
import com.metrolist.shared.ui.theme.rememberArtworkSeedColor
import com.metrolist.shared.ui.theme.resolveThemeSeedColor
import com.metrolist.shared.ui.utils.rememberFileSizeFormatter
import com.metrolist.shared.ui.utils.setText
import com.metrolist.shared.utils.BlockScreenshots
import com.metrolist.shared.utils.openUrl
import com.metrolist.shared.utils.parseYouTubeUrl
import com.metrolist.shared.utils.tryOpenUrl
import com.metrolist.shared.viewmodels.HomeViewModel
import com.metrolist.shared.viewmodels.LibraryViewModel
import com.metrolist.shared.viewmodels.PlayerPlatformServices
import com.metrolist.shared.viewmodels.PlayerViewModel
import com.metrolist.shared.viewmodels.SearchViewModel
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalComposeUiApi::class)
@Composable
fun MobileApp(
    database: MusicDatabase,
    playbackController: PlaybackController,
    listenTogetherManager: ListenTogetherManager,
    playerServices: PlayerPlatformServices,
    lyricsShareAction: @Composable (PlayerViewModel) -> ((String) -> Unit)?,
    onExportStatistics: suspend (ImageBitmap) -> Boolean,
    onPickPlaylistCover: suspend () -> ByteArray?,
    onSavePlaylistCover: suspend (PlaylistItem, ByteArray?) -> Result<Unit>,
    touchExplorationEnabled: Boolean,
    resolveAndPlayVideo: suspend (String) -> Boolean,
    systemCountry: String,
    systemLanguage: String,
    contentLanguageChoices: List<SettingsChoice>,
    contentCountryChoices: List<SettingsChoice>,
    appLanguageChoices: List<SettingsChoice>,
    storageTotalBytes: Long?,
    storageFreeBytes: Long?,
    onRemoveDownloads: suspend (List<String>) -> Int,
    onDownloadSongs: suspend (List<SongItem>) -> Int,
    onDownloadPlaylist: suspend (String) -> Int,
    onRemovePlaylistDownloads: suspend (String, List<String>) -> Int,
    iosSettingsPageContent: @Composable (SettingsPageId, onDownloads: () -> Unit) -> Unit,
    formatHistoryMonth: (Long) -> String,
    oauthCallbackUrls: Flow<String> = emptyFlow(),
    initialSettingsPage: SettingsPageId? = null,
    loginContent: @Composable (
        isSwitchingChannel: Boolean,
        onBack: () -> Unit,
        onComplete: () -> Unit,
        onTokenLogin: () -> Unit,
    ) -> Unit,
) {
    var detailsSong by remember { mutableStateOf<SongItem?>(null) }
    val formatFileSize = rememberFileSizeFormatter()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // The expanded player sheet covers the scaffold, so its own host takes over snackbars while it is open.
    var playerSheetCoversSnackbars by remember { mutableStateOf(false) }
    HiddenHomeItemsSnackbar(snackbarHostState)
    ListenTogetherGlobalEvents(listenTogetherManager) { snackbarHostState.showSnackbar(it) }
    val onSwipeFeedback: (String) -> Unit = { message ->
        snackbarHostState.currentSnackbarData?.dismiss()
        scope.launch { snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short) }
    }
    val reportDownloads: (PluralStringResource, suspend () -> Int) -> Unit = { message, action ->
        scope.launch {
            val count = action()
            onSwipeFeedback(getPluralString(message, count, count))
        }
    }
    val downloadSongs: (List<SongItem>) -> Unit = { songs -> reportDownloads(Res.plurals.queued_downloads) { onDownloadSongs(songs) } }
    val removeDownloads: (List<String>) -> Unit = { ids -> reportDownloads(Res.plurals.removed_downloads) { onRemoveDownloads(ids) } }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboard.current
    val likedSongsName = stringResource(Res.string.liked_songs)
    val account = remember { MobileAccountWorkflow() }
    val authManager = account.authManager
    remember(systemCountry, systemLanguage) {
        GlobalYouTube.applyContentRegion(
            contentCountry = Settings.shared.contentCountry,
            contentLanguage = Settings.shared.contentLanguage,
            systemCountry = systemCountry,
            systemLanguage = systemLanguage,
        )
    }
    val homeViewModel = remember(database, scope) { HomeViewModel(database, scope, likedSongsName = likedSongsName) }
    val searchViewModel = remember(scope) { SearchViewModel(scope) }
    val libraryViewModel = remember(database, scope) { LibraryViewModel(scope, database) }
    val lyricsRepository = remember { KoinPlatform.getKoin().get<LyricsRepository>() }
    val platformContext = LocalPlatformContext.current
    val playerSession = remember(playbackController, scope) { PlaybackControllerPlayerSession(playbackController, scope) }
    val playerViewModel =
        remember(playerSession, lyricsRepository, scope) {
            PlayerViewModel(
                playerConnection = playerSession,
                imageLoader = SingletonImageLoader.get(platformContext),
                platformContext = platformContext,
                platform = playerServices,
                lyricsRepository = lyricsRepository,
                animatedArtworkService = AnimatedArtworkService(GlobalYouTube.client),
                musicVideoResolver = KoinPlatform.getKoin().get(),
                viewModelScope = scope,
            )
        }
    val homeState by homeViewModel.uiState.collectAsState()
    LyricsTranslationSnackbar(snackbarHostState, playerViewModel.lyricsTranslationFailures)
    val searchState by searchViewModel.uiState.collectAsState()
    val historyRemovalFailed = stringResource(Res.string.search_history_removal_failed)
    LaunchedEffect(searchState.historyMutationFailures) {
        if (searchState.historyMutationFailures > 0) snackbarHostState.showSnackbar(historyRemovalFailed)
    }
    val playerState by remember(playbackController) {
        playbackController.state.map { it.copy(positionMs = 0L, bufferedPositionMs = 0L) }.distinctUntilChanged()
    }.collectAsState(initial = playbackController.state.value.copy(positionMs = 0L, bufferedPositionMs = 0L))
    val listenTogetherRole by listenTogetherManager.role.collectAsState()
    val inListenTogetherRoom by listenTogetherManager.isInRoom.collectAsState()
    val isListenTogetherGuest = inListenTogetherRoom && listenTogetherRole == ListenTogetherRoomRole.GUEST
    val homeListState = rememberLazyListState()
    val visibleTabRoutes =
        buildList {
            if (Settings.shared.showBottomNavHome) add(MobileRoute.Home)
            if (Settings.shared.showBottomNavSearch) add(MobileRoute.Search)
            if (Settings.shared.showBottomNavLibrary) add(MobileRoute.Library)
            if (isEmpty()) add(MobileRoute.EasterEgg)
        }
    val defaultTab =
        when (defaultOpenTabRoot(Settings.shared.defaultOpenTab)) {
            "SEARCH" -> MobileRoute.Search
            "LIBRARY" -> MobileRoute.Library
            else -> MobileRoute.Home
        }.takeIf { it in visibleTabRoutes } ?: visibleTabRoutes.first()
    var selectedTab by remember { mutableStateOf(defaultTab) }
    val routeStack =
        remember {
            mutableStateListOf<MobileRouteEntry>().apply {
                initialSettingsPage?.let { add(MobileRouteEntry(MobileRoute.Settings(initialPage = it))) }
            }
        }
    val containers = remember { NavigationContainerState() }
    val currentRoute = routeStack.lastOrNull()?.route ?: selectedTab
    val detailSession by GlobalYouTube.innerTube.sessionFlow.collectAsState()
    val detailStates = remember(scope, detailSession) { MobileDetailStateStore(scope) }
    DisposableEffect(detailStates) { onDispose { detailStates.close() } }
    LaunchedEffect(detailStates, routeStack.toList()) { detailStates.retain(routeStack.toList()) }
    var showPlayer by remember { mutableStateOf(false) }
    val libraryNeeded =
        currentRoute == MobileRoute.Library ||
            (currentRoute is MobileRoute.SongCollection && currentRoute.localPlaylistId?.let(::isHistoryPlaylistId) == true)
    val libraryActive = libraryNeeded && !(showPlayer && playerState.currentSong != null)
    LifecycleStartEffect(libraryViewModel, libraryActive) {
        libraryViewModel.setActive(libraryActive)
        onStopOrDispose { libraryViewModel.setActive(false) }
    }
    val latestVideoResolver = rememberUpdatedState(resolveAndPlayVideo)
    val videoLookup = remember(scope) { MobileVideoLookup(scope, { latestVideoResolver.value(it) }) { showPlayer = true } }

    fun cancelVideoLookup() = videoLookup.cancel()

    fun setPlayerVisible(visible: Boolean) {
        cancelVideoLookup()
        showPlayer = visible
    }

    fun playVideo(videoId: String) = videoLookup.play(videoId)

    fun playAlbum(album: AlbumItem) {
        scope.launch {
            val songs = runCatching { GlobalYouTubeRepository.instance.getAlbum(album.id)?.songs }.getOrNull().orEmpty()
            if (songs.isNotEmpty()) playbackController.play(songs.first(), songs)
        }
    }
    var showAccountMenu by remember { mutableStateOf(false) }

    var menuItem by remember { mutableStateOf<Pair<YTItem, (() -> Unit)?>?>(null) }
    var menuRemoveLabel by remember { mutableStateOf<String?>(null) }
    val playlistWorkflow = rememberMobilePlaylistWorkflow(database, scope)
    val pickPlaylistCover = rememberUpdatedState(onPickPlaylistCover)
    val savePlaylistCover = rememberUpdatedState(onSavePlaylistCover)
    val playlistEditor =
        remember(database, scope) {
            MobilePlaylistEditor(
                scope = scope,
                canEditRemote = { GlobalYouTubeRepository.instance.canManagePlaylists() },
                pickCover = { pickPlaylistCover.value() },
                saveCover = { playlist, bytes ->
                    savePlaylistCover.value(playlist, bytes).onSuccess { libraryViewModel.refresh(force = true) }
                },
                update = { playlist, name, description ->
                    val id = playlist.id
                    val result =
                        if (playlist.isLocal) {
                            renameStoredPlaylist(database, GlobalYouTubeRepository.instance, id, name)
                        } else {
                            updateStoredPlaylist(
                                database,
                                GlobalYouTubeRepository.instance,
                                id,
                                playlist.title,
                                playlist.description,
                                name,
                                description,
                            )
                        }
                    result.also {
                        val updatedName =
                            if (result.isSuccess) name else (result.exceptionOrNull() as? PlaylistPartiallyUpdatedException)?.updatedName
                        if (updatedName != null) {
                            routeStack.indices.forEach { index ->
                                val route = routeStack[index].route
                                if (route is MobileRoute.SongCollection && route.localPlaylistId == id) {
                                    routeStack[index].route = route.copy(title = updatedName)
                                } else if (route is MobileRoute.Playlist && route.id.removePrefix("VL") == id.removePrefix("VL")) {
                                    routeStack[index].route = route.copy(title = updatedName)
                                }
                            }
                            libraryViewModel.refresh(force = true)
                        }
                    }
                },
                delete = { id ->
                    deleteStoredPlaylist(database, GlobalYouTubeRepository.instance, id).onSuccess {
                        routeStack.removeAll { entry ->
                            val it = entry.route
                            (it is MobileRoute.SongCollection && it.localPlaylistId == id) ||
                                (it is MobileRoute.Playlist && it.id.removePrefix("VL") == id.removePrefix("VL"))
                        }
                        libraryViewModel.refresh(force = true)
                    }
                },
            )
        }
    val playlistCompletion = playlistWorkflow.completion
    LaunchedEffect(playlistCompletion, playlistWorkflow.completionIndex) {
        playlistCompletion?.let { completion ->
            onSwipeFeedback(
                getPluralString(
                    Res.plurals.added_songs_to_playlist,
                    completion.count,
                    completion.count,
                    completion.playlistName,
                ),
            )
            playlistWorkflow.consumeCompletion()
        }
    }
    var appRevision by remember { mutableIntStateOf(0) }
    val systemDark = isSystemInDarkTheme()

    val playbackNetworkState by PlaybackNetworkStateStore.state.collectAsState()
    val cachedAudio by remember(database) { database.dao().cachedAudio() }.collectAsState(initial = emptyList())
    val cachedSongIds = remember(cachedAudio) { cachedAudio.filter(::isAvailableCachedAudio).mapTo(mutableSetOf()) { it.songId } }
    val downloadedSongIds = remember(cachedAudio) { cachedAudio.filter(::isAvailableDownload).mapTo(mutableSetOf()) { it.songId } }
    val isOffline = !playbackNetworkState.connected
    val isHomeItemEnabled: (YTItem) -> Boolean = { item ->
        (!isOffline || item.isAvailableOffline(cachedSongIds)) &&
            (item is SongItem || item is EpisodeItem || routeForMobileItem(item) != null)
    }

    val shouldOfferContinuePlayback =
        Settings.shared.promptContinuePlayback && Settings.shared.pendingContinuePlayback
    var continuePlaybackPromptHandled by rememberSaveable { mutableStateOf(false) }
    var showContinuePlaybackPrompt by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(playerState.currentSong, playerState.isPlaying, shouldOfferContinuePlayback) {
        if (
            shouldOfferContinuePlayback &&
            !continuePlaybackPromptHandled &&
            playerState.currentSong != null &&
            !playerState.isPlaying
        ) {
            continuePlaybackPromptHandled = true
            showContinuePlaybackPrompt = true
        }
    }

    if (Settings.shared.blockScreenshots) {
        BlockScreenshots()
    }

    fun open(route: MobileRoute) {
        if (route is MobileRoute.ListenTogether && !Settings.shared.listenTogetherEnabled) return
        setPlayerVisible(false)
        cancelVideoLookup()
        containers.pendingPopup = null
        if (routeStack.lastOrNull()?.route != route) routeStack += MobileRouteEntry(route)
    }

    fun dismissKeyboard() {
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    fun back() {
        cancelVideoLookup()
        dismissKeyboard()
        containers.pendingPopup = null
        if (routeStack.isNotEmpty()) routeStack.removeAt(routeStack.lastIndex)
    }

    fun selectTab(route: MobileRoute) {
        setPlayerVisible(false)
        cancelVideoLookup()
        dismissKeyboard()
        if (selectedTab == route && routeStack.isEmpty()) {
            when (route) {
                MobileRoute.Home -> {
                    scope.launch { homeListState.animateScrollToItem(0) }
                }

                MobileRoute.Search -> {
                    searchViewModel.resetSearch()
                }

                MobileRoute.Library -> {
                    libraryViewModel.refresh()
                }

                else -> {}
            }
        } else {
            containers.pendingPopup = null
            routeStack.clear()
            selectedTab = route
        }
    }

    LaunchedEffect(oauthCallbackUrls) {
        oauthCallbackUrls.collect { callback ->
            val link = resolveMobileDeepLink(callback)
            if (account.handleDiscordCallback(callback)) return@collect
            if (link != null) {
                when (link) {
                    is MobileDeepLink.Tab -> {
                        selectTab(link.route)
                        link.query?.let { query ->
                            searchViewModel.onQueryChange(query)
                            searchViewModel.searchQuery(query)
                        }
                    }

                    is MobileDeepLink.Destination -> {
                        open(link.route)
                    }

                    is MobileDeepLink.Video -> {
                        playVideo(link.id)
                    }

                    MobileDeepLink.Player -> {
                        setPlayerVisible(true)
                    }
                }
            } else {
                when (val parsed = parseYouTubeUrl(callback)) {
                    is YouTubeUrlParser.ParsedUrl.Video -> {
                        playVideo(parsed.id)
                    }

                    is YouTubeUrlParser.ParsedUrl.Playlist -> {
                        if (parsed.id.startsWith("MPSP")) {
                            open(MobileRoute.Podcast(id = parsed.id, title = ""))
                        } else {
                            open(MobileRoute.Playlist(id = parsed.id, title = ""))
                        }
                    }

                    is YouTubeUrlParser.ParsedUrl.Album -> {
                        open(MobileRoute.Album(id = parsed.id, title = ""))
                    }

                    is YouTubeUrlParser.ParsedUrl.Artist -> {
                        open(MobileRoute.Artist(id = parsed.id, name = ""))
                    }

                    null -> {
                        if (callback.contains("music.youtube.com/browse/MPSP") || callback.contains("/podcast/")) {
                            val podcastId = callback.substringAfter("browse/").substringBefore('?').substringBefore('&')
                            if (podcastId.startsWith("MPSP")) {
                                open(MobileRoute.Podcast(id = podcastId, title = ""))
                            }
                        }
                    }
                }
            }
        }
    }

    val discordSession = account.pendingSession
    LaunchedEffect(discordSession) {
        if (discordSession != null) {
            delay(120_000L.milliseconds)
            account.expire(discordSession)
        }
    }

    @Suppress("UNUSED_EXPRESSION")
    appRevision
    Settings.shared.systemInDarkTheme = systemDark
    val darkTheme = Settings.shared.getCurrentThemeIsDark()
    val pureBlack = Settings.shared.isPureBlackTheme()

    fun openItem(item: YTItem) {
        when (item) {
            is SongItem -> playbackController.play(item)
            is EpisodeItem -> playbackController.play(item.toSongItem())
            else -> routeForMobileItem(item)?.let(::open)
        }
    }

    LaunchedEffect(Unit) {
        account.restoreSession()
        GlobalYouTube.applyContentRegion(
            contentCountry = Settings.shared.contentCountry,
            contentLanguage = Settings.shared.contentLanguage,
            systemCountry = systemCountry,
            systemLanguage = systemLanguage,
        )
        playbackController.setPlaybackSpeed(Settings.shared.playbackSpeed)
        playbackController.setPlaybackPitch(Settings.shared.playbackPitch)
        GlobalYouTubeRepository.streamResolver?.prewarm()
        runCatching { UploadNotificationState.refreshOnAppOpen(GlobalYouTubeRepository.instance) }
    }

    LaunchedEffect(homeState.isLoading, homeState.sections.isEmpty()) {
        if (!homeState.isLoading && homeState.sections.isNotEmpty()) {
            libraryViewModel.refreshAfterHomeLoaded()
        }
    }

    LaunchedEffect(visibleTabRoutes) {
        if (selectedTab !in visibleTabRoutes) {
            cancelVideoLookup()
            selectedTab = visibleTabRoutes.first()
        }
    }

    val adaptiveLayout =
        currentAdaptiveLayout(AdaptiveFormFactor.TOUCH, forceBottomNavBar = Settings.shared.forceBottomNavBar).let { layout ->
            if (!layout.navigationSuiteType.isNavigationBar) return@let layout
            val navType =
                if (Settings.shared.slimNavBar) {
                    NavigationSuiteType.ShortNavigationBarCompact
                } else {
                    NavigationSuiteType.NavigationBar
                }
            layout.copy(navigationSuiteType = navType)
        }
    val navigationItems: List<AdaptiveNavigationItem<MobileRoute>> =
        listOf(
            AdaptiveNavigationItem<MobileRoute>(
                MobileRoute.Home,
                stringResource(Res.string.home),
                MetrolistIcons.Outlined.Home,
                MetrolistIcons.Filled.Home,
            ),
            AdaptiveNavigationItem<MobileRoute>(
                MobileRoute.Search,
                stringResource(Res.string.search),
                MetrolistIcons.Outlined.Search,
                MetrolistIcons.Filled.Search,
            ),
            AdaptiveNavigationItem<MobileRoute>(
                MobileRoute.Library,
                stringResource(Res.string.library),
                MetrolistIcons.Outlined.LibraryMusic,
                MetrolistIcons.Filled.LibraryMusic,
            ),
            AdaptiveNavigationItem<MobileRoute>(
                MobileRoute.EasterEgg,
                stringResource(Res.string.mysterious_fourth_tab),
                MetrolistIcons.Filled.QuestionMark,
                MetrolistIcons.Filled.QuestionMark,
            ),
        ).filter { it.key in visibleTabRoutes }

    var miniPlayerBounds by remember { mutableStateOf<Rect?>(null) }
    var floatingNavBarHeight by remember { mutableStateOf(0.dp) }
    val useFloatingNavBar = Settings.shared.floatingNavBar && adaptiveLayout.navigationSuiteType.isNavigationBar
    val floatingContentInset = if (useFloatingNavBar && routeStack.isEmpty()) floatingNavBarHeight else 0.dp
    val miniPlayerHeight =
        when (Settings.shared.miniPlayerType) {
            MiniPlayerType.MODERN -> modernMiniPlayerHeight(LocalDensity.current)
            MiniPlayerType.EXPRESSIVE -> ExpressiveMiniPlayerHeight
            else -> MiniPlayerHeight
        }
    var playerSheetSettled by remember { mutableStateOf(false) }
    val playbackState = playerState
    val artworkSeedColor =
        rememberArtworkSeedColor(
            playbackState.currentSong
                ?.thumbnail
                ?.takeIf { Settings.shared.materialYouEnabled && Settings.shared.dynamicTheme },
        )
    MetrolistTheme(
        seedColor =
            resolveThemeSeedColor(
                materialYouEnabled = Settings.shared.materialYouEnabled,
                songArtworkColorsEnabled = Settings.shared.dynamicTheme,
                selectedThemeColor = Settings.shared.selectedThemeColor,
                artworkSeedColor = artworkSeedColor,
            ),
        darkTheme = darkTheme,
        pureBlack = pureBlack,
    ) {
        NavigationContentProvider(
            LocalNavigationContainerState provides containers,
            LocalPlaybackController provides playbackController,
            LocalContentObscured provides
                (playerState.currentSong != null && playerSheetSettled),
        ) {
            val isTopLevelRoute =
                routeStack.isEmpty() &&
                    (
                        selectedTab == MobileRoute.Home ||
                            selectedTab == MobileRoute.Library ||
                            selectedTab == MobileRoute.EasterEgg
                    )
            AdaptiveNavigationScaffold(
                layout = adaptiveLayout,
                items = navigationItems,
                selectedKey = selectedTab,
                onSelect = ::selectTab,
                navigationVisible = routeStack.isEmpty(),
                floatingNavigation = useFloatingNavBar,
                floatingSeparateKey = MobileRoute.Search.takeIf { it in visibleTabRoutes },
                onFloatingNavigationHeightChanged = { floatingNavBarHeight = it },
                navigationSuiteType = adaptiveLayout.navigationSuiteType,
                navigationItemVerticalArrangement = Arrangement.Center,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Scaffold(
                    snackbarHost = {
                        Column(Modifier.fillMaxWidth()) {
                            PlaylistImportProgressCard()
                            if (!playerSheetCoversSnackbars) SnackbarHost(snackbarHostState)
                        }
                    },
                    // The floating bar already reserves the bottom system inset.
                    modifier = Modifier.consumeWindowInsets(PaddingValues(bottom = floatingContentInset)),
                    containerColor = MaterialTheme.colorScheme.surface,
                    topBar = {
                        if (isTopLevelRoute) {
                            TopAppBar(
                                title = {
                                    Text(
                                        text =
                                            when (selectedTab) {
                                                MobileRoute.Home -> {
                                                    stringResource(Res.string.home)
                                                }

                                                MobileRoute.Library -> {
                                                    stringResource(Res.string.filter_library)
                                                }

                                                MobileRoute.EasterEgg -> {
                                                    stringResource(Res.string.mysterious_fourth_tab)
                                                }

                                                else -> {
                                                    ""
                                                }
                                            },
                                        style = MaterialTheme.typography.titleLargeEmphasized,
                                    )
                                },
                                subtitle = {
                                    if (selectedTab == MobileRoute.Home && !playbackNetworkState.connected) {
                                        Text(stringResource(Res.string.offline_mode))
                                    }
                                },
                                actions = {
                                    if (Settings.shared.devtoolsUnlocked) {
                                        IconButton(
                                            onClick = { open(MobileRoute.Settings(initialPage = SettingsPageId.DEVTOOLS)) },
                                            shapes = IconButtonDefaults.shapes(),
                                        ) {
                                            Icon(
                                                MetrolistIcons.Outlined.Settings,
                                                contentDescription = stringResource(Res.string.devtools_title),
                                                modifier = Modifier.size(24.dp),
                                            )
                                        }
                                    }
                                    if (Settings.shared.listenTogetherEnabled) {
                                        IconButton(onClick = { open(MobileRoute.ListenTogether()) }, shapes = IconButtonDefaults.shapes()) {
                                            Icon(
                                                MetrolistIcons.Outlined.Group,
                                                contentDescription = stringResource(Res.string.listen_together),
                                                modifier = Modifier.size(24.dp),
                                            )
                                        }
                                    }
                                    Box {
                                        IconButton(
                                            modifier = Modifier.navigationPopupSource(),
                                            onClick = { showAccountMenu = true },
                                            shapes = IconButtonDefaults.shapes(),
                                        ) {
                                            if (authManager.isLoggedIn && authManager.accountThumbnail.isNotBlank()) {
                                                AsyncImage(
                                                    url = authManager.accountThumbnail,
                                                    modifier = Modifier.size(32.dp).clip(CircleShape),
                                                    contentDescription = stringResource(Res.string.account),
                                                )
                                            } else {
                                                Icon(
                                                    MetrolistIcons.Outlined.AccountCircle,
                                                    contentDescription = stringResource(Res.string.account),
                                                    modifier = Modifier.size(24.dp),
                                                )
                                            }
                                        }
                                        if (showAccountMenu) {
                                            AccountPopupDialog(
                                                isSignedIn = authManager.isLoggedIn,
                                                accountName = authManager.accountName,
                                                accountSubtitle =
                                                    authManager.accountChannelHandle.ifBlank { authManager.accountEmail },
                                                accountImageUrl = authManager.accountThumbnail,
                                                onDismiss = { showAccountMenu = false },
                                                onSignIn = { open(MobileRoute.Login(isSwitchingChannel = false)) },
                                                onTokenLogin = { open(MobileRoute.TokenLogin) },
                                                onSwitchChannel = { open(MobileRoute.Login(isSwitchingChannel = true)) },
                                                onNavigateToHistory = { open(MobileRoute.History) },
                                                onNavigateToStatistics = { open(MobileRoute.Statistics) },
                                                onNavigateToIntegrations = { open(MobileRoute.Integrations) },
                                                onNavigateToSettings = { open(MobileRoute.Settings()) },
                                                onLogout = { complete ->
                                                    scope.launch {
                                                        account.logout()
                                                        appRevision++
                                                        homeViewModel.refresh {}
                                                        libraryViewModel.refresh()
                                                        complete()
                                                    }
                                                },
                                                showStatistics = true,
                                                showIntegrations = true,
                                            )
                                        }
                                    }
                                },
                                colors =
                                    TopAppBarDefaults.topAppBarColors(
                                        containerColor =
                                            if (pureBlack) {
                                                Color.Black
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainer
                                            },
                                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                            )
                        }
                    },
                    bottomBar = {
                        // The floating bar's height sits under the mini player slot; lists scroll behind both.
                        if (playbackState.currentSong == null) {
                            Spacer(Modifier.fillMaxWidth().height(floatingContentInset))
                        } else {
                            val isModernMiniPlayer = Settings.shared.miniPlayerType == MiniPlayerType.MODERN
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isModernMiniPlayer && routeStack.isNotEmpty()) {
                                            Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                                        } else {
                                            Modifier
                                        },
                                    ).padding(bottom = floatingContentInset)
                                    .padding(bottom = if (isModernMiniPlayer) ModernMiniPlayerBottomSpacing else 0.dp)
                                    .onGloballyPositioned { miniPlayerBounds = it.boundsInRoot() }
                                    .height(miniPlayerHeight),
                            )
                        }
                    },
                ) { padding ->
                    val playerContentPadding =
                        if (Settings.shared.miniPlayerType == MiniPlayerType.EXPRESSIVE && playbackState.currentSong != null) {
                            minOf(miniPlayerHeight + floatingContentInset, padding.calculateBottomPadding())
                        } else {
                            minOf(floatingContentInset, padding.calculateBottomPadding())
                        }
                    val layoutDirection = LocalLayoutDirection.current
                    val routePadding =
                        PaddingValues(
                            start = padding.calculateStartPadding(layoutDirection),
                            top = padding.calculateTopPadding(),
                            end = padding.calculateEndPadding(layoutDirection),
                            bottom = padding.calculateBottomPadding() - playerContentPadding,
                        )
                    val previousRoute =
                        when {
                            routeStack.size > 1 -> routeStack[routeStack.size - 2].route
                            routeStack.size == 1 -> selectedTab
                            else -> null
                        }

                    NavigationBackHandler(enabled = showPlayer) {
                        setPlayerVisible(false)
                    }

                    NavigationBackHandler(enabled = !showPlayer && routeStack.isNotEmpty()) {
                        back()
                    }

                    @Composable
                    fun RenderRoute(
                        route: MobileRoute,
                        entry: MobileRouteEntry?,
                    ) {
                        DisposableEffect(detailStates, entry) {
                            entry?.let(detailStates::attach)
                            onDispose { entry?.let(detailStates::detach) }
                        }
                        when (route) {
                            MobileRoute.Home -> {
                                val text = homeContentText()
                                var isRefreshing by remember { mutableStateOf(false) }
                                var showFloatingActions by remember { mutableStateOf(true) }
                                val fabNestedScrollConnection =
                                    remember {
                                        object : NestedScrollConnection {
                                            override fun onPreScroll(
                                                available: Offset,
                                                source: NestedScrollSource,
                                            ): Offset {
                                                if (source == NestedScrollSource.UserInput) {
                                                    when {
                                                        available.y < -1f -> showFloatingActions = false
                                                        available.y > 1f -> showFloatingActions = true
                                                    }
                                                }
                                                return Offset.Zero
                                            }
                                        }
                                    }

                                if (homeState.showLoadingPlaceholder) {
                                    HomeLoadingPlaceholder(Modifier.fillMaxSize().padding(routePadding))
                                } else {
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxSize()
                                                .padding(routePadding)
                                                .nestedScroll(fabNestedScrollConnection),
                                    ) {
                                        val pullToRefreshState = rememberPullToRefreshState()
                                        PullToRefreshBox(
                                            isRefreshing = isRefreshing,
                                            onRefresh = {
                                                isRefreshing = true
                                                homeViewModel.refresh { isRefreshing = false }
                                            },
                                            state = pullToRefreshState,
                                            indicator = {
                                                RefreshIndicator(
                                                    state = pullToRefreshState,
                                                    isRefreshing = isRefreshing,
                                                    modifier = Modifier.align(Alignment.TopCenter),
                                                )
                                            },
                                            modifier = Modifier.fillMaxSize(),
                                        ) {
                                            HomeContent(
                                                uiState = homeState,
                                                text = text,
                                                onItemClick = ::openItem,
                                                onLocalShelfClick = { shelf ->
                                                    open(MobileRoute.LocalShelf(shelf.pageKey, shelf.toHomeSection()))
                                                },
                                                onItemLongClick = { menuItem = it to null },
                                                onShuffleSongs = { songs ->
                                                    songs.firstOrNull()?.let { playbackController.play(it, songs) }
                                                },
                                                onPlaySongs = { songs ->
                                                    songs.firstOrNull()?.let { playbackController.play(it, songs) }
                                                },
                                                onLibraryClick = { filter ->
                                                    filter?.let { Settings.shared.libraryFilter = it }
                                                    selectTab(MobileRoute.Library)
                                                },
                                                onArtistClick = { open(MobileRoute.Artist(it, "")) },
                                                onAlbumClick = { open(MobileRoute.Album(it, "")) },
                                                onPlayAlbum = ::playAlbum,
                                                currentSongId =
                                                    remember(
                                                        playbackController,
                                                    ) { derivedStateOf { playerState.currentSong?.id } },
                                                isPlaying = remember(playbackController) { derivedStateOf { playerState.isPlaying } },
                                                isItemEnabled = isHomeItemEnabled,
                                                contentPadding = PaddingValues(bottom = playerContentPadding),
                                                listState = homeListState,
                                                onLoadMore = homeViewModel::loadMoreSections,
                                                modifier = Modifier.fillMaxSize(),
                                                header = {
                                                    if (!homeState.error.isNullOrBlank()) {
                                                        item(key = "home_error") {
                                                            Column(
                                                                Modifier.fillMaxWidth().padding(24.dp),
                                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                                            ) {
                                                                Text(text.homeUnavailable)
                                                                Button(
                                                                    onClick = { homeViewModel.refresh {} },
                                                                    shapes = ButtonDefaults.shapes(),
                                                                ) { Text(text.tryAgain) }
                                                            }
                                                        }
                                                    }
                                                    if (homeState.chips.isNotEmpty()) {
                                                        item(key = "home_chips") {
                                                            ChipShelf(
                                                                options = homeState.chips,
                                                                selectedOption = homeState.selectedChip,
                                                                onSelectionChange = homeViewModel::toggleChip,
                                                                label = { it.title },
                                                                modifier = Modifier.padding(top = MdSpacing.xs + MdSpacing.xxs),
                                                            )
                                                        }
                                                    }
                                                },
                                            )
                                        }

                                        AnimatedVisibility(
                                            visible = showFloatingActions,
                                            enter = fadeIn() + slideInVertically { it / 2 },
                                            exit = fadeOut() + slideOutVertically { it / 2 },
                                            modifier =
                                                Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(
                                                        end = 16.dp,
                                                        bottom =
                                                            padding.calculateBottomPadding() + playerContentPadding + 16.dp,
                                                    ),
                                        ) {
                                            FloatingActionButton(
                                                onClick = {
                                                    val songs = homeState.loadedHomeSongs().filter(isHomeItemEnabled).shuffled()
                                                    if (songs.isNotEmpty()) {
                                                        playbackController.play(songs.first(), songs)
                                                    }
                                                },
                                                shape = RoundedCornerShape(16.dp),
                                            ) {
                                                Icon(
                                                    MetrolistIcons.Rounded.Shuffle,
                                                    contentDescription = stringResource(Res.string.shuffle),
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            MobileRoute.Search -> {
                                val searchFocusRequester = remember { FocusRequester() }
                                LaunchedEffect(searchViewModel) { searchViewModel.refreshSearchHistory() }
                                SearchScreen(
                                    focusRequester = searchFocusRequester,
                                    itemContainer = { item, content ->
                                        if (item is SongItem) SongSwipeActions(item, onSwipeFeedback, content = content) else content()
                                    },
                                    uiState = searchState,
                                    text = searchScreenText(),
                                    onQueryChange = { query ->
                                        cancelVideoLookup()
                                        searchViewModel.onQueryChange(query)
                                    },
                                    onSearch = { query ->
                                        cancelVideoLookup()
                                        when (val parsed = parseYouTubeUrl(query)) {
                                            is YouTubeUrlParser.ParsedUrl.Video -> {
                                                playVideo(parsed.id)
                                            }

                                            is YouTubeUrlParser.ParsedUrl.Playlist -> {
                                                if (parsed.id.startsWith("MPSP")) {
                                                    open(MobileRoute.Podcast(id = parsed.id, title = ""))
                                                } else {
                                                    open(MobileRoute.Playlist(id = parsed.id, title = ""))
                                                }
                                            }

                                            is YouTubeUrlParser.ParsedUrl.Album -> {
                                                open(MobileRoute.Album(id = parsed.id, title = ""))
                                            }

                                            is YouTubeUrlParser.ParsedUrl.Artist -> {
                                                open(MobileRoute.Artist(id = parsed.id, name = ""))
                                            }

                                            null -> {
                                                searchViewModel.searchQuery(query)
                                            }
                                        }
                                    },
                                    onBack = {
                                        cancelVideoLookup()
                                        searchViewModel.resetSearch()
                                    },
                                    onItemClick = { item ->
                                        if (item is SongItem) searchViewModel.addPlayedSearchSong(item)
                                        openItem(item)
                                    },
                                    onItemLongClick = { menuItem = it to null },
                                    onSongSave = { playlistWorkflow.select(it) },
                                    onHistoryItemClick = { query ->
                                        cancelVideoLookup()
                                        searchViewModel.searchFromHistory(query)
                                    },
                                    onHistoryItemInsert = { query ->
                                        cancelVideoLookup()
                                        searchViewModel.onQueryChange(query)
                                        searchFocusRequester.requestFocus()
                                        keyboardController?.show()
                                    },
                                    onClearHistory = searchViewModel::clearSearchHistory,
                                    onFilterSelected = { filter ->
                                        cancelVideoLookup()
                                        if (filter == null) {
                                            searchViewModel.clearFilter()
                                        } else {
                                            searchViewModel.searchFiltered(filter)
                                        }
                                    },
                                    onLoadMore = searchViewModel::loadMoreFilteredResults,
                                    onRetryLoadMore = searchViewModel::retryLoadMoreFilteredResults,
                                    onRemoveHistoryItem = searchViewModel::removeSearchHistoryItem,
                                    onRemoveRecentItem = searchViewModel::removeRecentPlayedSearchSong,
                                    onClearRecentItems = searchViewModel::clearRecentPlayedSearchSongs,
                                    currentSongId = playerState.currentSong?.id,
                                    isPlaying = playerState.isPlaying,
                                    onAlbumPlay = ::playAlbum,
                                    contentPadding = PaddingValues(bottom = playerContentPadding),
                                    modifier = Modifier.fillMaxSize().padding(routePadding),
                                )
                            }

                            MobileRoute.Library -> {
                                MobileLibrary(
                                    bottomContentPadding = playerContentPadding,
                                    database = database,
                                    viewModel = libraryViewModel,
                                    playbackController = playbackController,
                                    onItemClick = ::openItem,
                                    onItemLongClick = { menuItem = it to null },
                                    onAutoPlaylistClick = { type, title -> open(MobileRoute.SongCollection(type, title)) },
                                    onCreatePlaylist = playlistWorkflow::showCreate,
                                    onPlayAlbum = ::playAlbum,
                                    modifier = Modifier.fillMaxSize().padding(routePadding),
                                )
                            }

                            MobileRoute.EasterEgg -> {
                                Box(
                                    modifier = Modifier.fillMaxSize().padding(padding),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(Res.string.mysterious_fourth_tab_description),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(32.dp),
                                    )
                                }
                            }

                            MobileRoute.History -> {
                                MobileHistoryScreen(
                                    bottomContentPadding = playerContentPadding,
                                    database = database,
                                    playbackController = playbackController,
                                    onBack = ::back,
                                    formatMonth = formatHistoryMonth,
                                    onSongMenu = { menuItem = it to null },
                                    modifier = Modifier.fillMaxSize().padding(routePadding),
                                )
                            }

                            MobileRoute.Statistics -> {
                                MobileStatisticsScreen(
                                    onError = onSwipeFeedback,
                                    bottomContentPadding = playerContentPadding,
                                    onExport = onExportStatistics,
                                    onItemMenu = { menuItem = it to null },
                                    database = database,
                                    playbackController = playbackController,
                                    onBack = ::back,
                                    onBrowse = { open(MobileRoute.Browse(it)) },
                                    onAlbumClick = { id, title -> open(MobileRoute.Album(id, title)) },
                                    onArtistClick = { id, name -> open(MobileRoute.Artist(id, name)) },
                                    modifier = Modifier.fillMaxSize().padding(routePadding),
                                )
                            }

                            is MobileRoute.ListenTogether -> {
                                MobileListenTogetherScreen(
                                    manager = listenTogetherManager,
                                    initialRoomCode = route.initialRoomCode,
                                    onBack = ::back,
                                    onCopyText = { _, text -> scope.launch { clipboard.setText(text) } },
                                    onShareText = playerServices::shareText,
                                    onMessage = onSwipeFeedback,
                                    bottomInset = padding.calculateBottomPadding(),
                                )
                            }

                            MobileRoute.Integrations -> {
                                val integrationPlayback by playbackController.state.collectAsState()
                                SharedIntegrationsScreen(
                                    host =
                                        SharedIntegrationsHost(
                                            platform = SettingsPlatform.IOS,
                                            onClose = ::back,
                                            bottomContentPadding = padding.calculateBottomPadding(),
                                            discordLoginInProgress = account.loginInProgress,
                                            playback =
                                                integrationPlayback.currentSong?.let { song ->
                                                    IntegrationPlaybackState(
                                                        song = song,
                                                        positionMs = integrationPlayback.positionMs,
                                                        durationMs = integrationPlayback.durationMs,
                                                        isPlaying = integrationPlayback.isPlaying,
                                                    )
                                                },
                                            onMessage = onSwipeFeedback,
                                            listenTogetherManager = listenTogetherManager,
                                            onCopyText = { _, text -> scope.launch { clipboard.setText(text) } },
                                            onShareText = playerServices::shareText,
                                            onStartDiscordLogin = { session ->
                                                account.startDiscordLogin(session)
                                                tryOpenUrl(session.authorizeUrl) { opened ->
                                                    if (!opened) account.failDiscordLogin(session)
                                                }
                                            },
                                            onOpenUrl = ::openUrl,
                                        ),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            is MobileRoute.Settings -> {
                                val iconError = stringResource(Res.string.error_unknown)
                                val appIcons = rememberIosAppIconActions { onSwipeFeedback(iconError) }
                                val systemDefaultLabel = stringResource(Res.string.app_language_system)
                                val deviceLanguageLabel = stringResource(Res.string.device_language)
                                val languageChoices =
                                    remember(contentLanguageChoices, systemDefaultLabel) {
                                        listOf(SettingsChoice(GlobalYouTube.SYSTEM, systemDefaultLabel)) + contentLanguageChoices
                                    }
                                val countryChoices =
                                    remember(contentCountryChoices, systemDefaultLabel) {
                                        listOf(SettingsChoice(GlobalYouTube.SYSTEM, systemDefaultLabel)) + contentCountryChoices
                                    }
                                val lyricsLanguageChoices =
                                    remember(languageChoices, deviceLanguageLabel) {
                                        listOf(SettingsChoice("device", deviceLanguageLabel)) + languageChoices.drop(1)
                                    }
                                SharedSettingsScreen(
                                    SharedSettingsHost(
                                        platform = SettingsPlatform.IOS,
                                        lyricsPreview = { modifier -> PlayerLyricsSettingsPreview(playerViewModel, modifier) },
                                        initialPage = route.initialPage,
                                        isAudioProcessingAvailable = playerState.isAudioProcessingAvailable,
                                        onClose = ::back,
                                        onOpenArtist = { open(MobileRoute.Artist(it, "")) },
                                        onSeedColorSelected = { appRevision++ },
                                        onClearSearchHistory = searchViewModel::clearSearchHistory,
                                        availablePages =
                                            buildSet {
                                                addAll(
                                                    listOf(
                                                        SettingsPageId.APPEARANCE,
                                                        SettingsPageId.FONTS,
                                                        SettingsPageId.PLAYER,
                                                        SettingsPageId.CONTENT,
                                                        SettingsPageId.LYRICS,
                                                        SettingsPageId.PRIVACY,
                                                        SettingsPageId.PLAYER_STYLE,
                                                        SettingsPageId.PLAYER_ARTWORK,
                                                        SettingsPageId.MINI_PLAYER_STYLE,
                                                        SettingsPageId.LYRICS_SOURCES,
                                                        SettingsPageId.LYRICS_AI_TRANSLATION,
                                                        SettingsPageId.LYRICS_ROMANIZATION,
                                                        SettingsPageId.HIDDEN_HOME_ITEMS,
                                                        SettingsPageId.HIDDEN_LIBRARY_ITEMS,
                                                        SettingsPageId.BLOCKED_ARTISTS,
                                                        SettingsPageId.STORAGE,
                                                        SettingsPageId.BACKUP_RESTORE,
                                                        SettingsPageId.ABOUT,
                                                        SettingsPageId.DISCORD,
                                                        SettingsPageId.LAST_FM,
                                                        SettingsPageId.LISTEN_BRAINZ,
                                                    ),
                                                )
                                                if (Settings.shared.devtoolsUnlocked) {
                                                    add(SettingsPageId.DEVTOOLS)
                                                }
                                            },
                                        externalPages = setOf(SettingsPageId.ABOUT),
                                        storageSummary =
                                            if (storageTotalBytes != null && storageFreeBytes != null) {
                                                stringResource(
                                                    Res.string.settings_storage_summary,
                                                    formatFileSize(storageTotalBytes),
                                                    formatFileSize(storageFreeBytes),
                                                )
                                            } else {
                                                stringResource(Res.string.calculating)
                                            },
                                        fontPrimaryActions =
                                            rememberIosFontActions(
                                                chooseTitle = stringResource(Res.string.app_font_import_custom),
                                                chooseDescription = stringResource(Res.string.app_font_picker_desc),
                                                resetTitle = stringResource(Res.string.app_font_reset),
                                                resetDescription = stringResource(Res.string.app_font_reset_description),
                                                onImportResult = { appRevision++ },
                                            ),
                                        privacyDiagnosticActions =
                                            listOf(
                                                rememberIosLogExportAction(
                                                    title = stringResource(Res.string.export_logs),
                                                    description = stringResource(Res.string.settings_diagnostic_logging_description),
                                                ),
                                            ),
                                        pageContent = { page, _, _ ->
                                            when (page) {
                                                SettingsPageId.DEVTOOLS -> {
                                                    var devtoolsEnabled by remember { mutableStateOf(true) }
                                                    Column(
                                                        Modifier
                                                            .fillMaxSize()
                                                            .verticalScroll(rememberScrollState())
                                                            .padding(bottom = LocalSettingsPageBottomPadding.current),
                                                    ) {
                                                        DevtoolsEnabledSwitch(
                                                            enabled = devtoolsEnabled,
                                                            onEnabledChange = {
                                                                devtoolsEnabled = it
                                                                Settings.shared.setDevtoolsEnabled(it)
                                                            },
                                                        )
                                                        if (devtoolsEnabled) {
                                                            PlaybackEventLogSection(
                                                                formatTimestamp = { Instant.fromEpochMilliseconds(it).toString() },
                                                                onCopy = {
                                                                    scope.launch {
                                                                        clipboard.setText(
                                                                            withContext(Dispatchers.IO) { PlaybackEventLog.exportText() },
                                                                        )
                                                                    }
                                                                },
                                                                onClear = PlaybackEventLog::clear,
                                                                modifier = Modifier.padding(bottom = 24.dp),
                                                            )
                                                        }
                                                    }
                                                }

                                                SettingsPageId.STORAGE, SettingsPageId.BACKUP_RESTORE -> {
                                                    val downloadsTitle = stringResource(Res.string.downloaded)
                                                    iosSettingsPageContent(page) {
                                                        open(MobileRoute.SongCollection("downloaded", downloadsTitle))
                                                    }
                                                }

                                                else -> {
                                                }
                                            }
                                        },
                                        onOpenPage = { page -> if (page == SettingsPageId.ABOUT) open(MobileRoute.About) },
                                        hostChoices =
                                            mapOf(
                                                SettingsHostChoice.APP_ICON to appIcons.choices,
                                                SettingsHostChoice.CONTENT_LANGUAGE to languageChoices,
                                                SettingsHostChoice.CONTENT_COUNTRY to countryChoices,
                                                SettingsHostChoice.APP_LANGUAGE to
                                                    listOf(
                                                        SettingsChoice(
                                                            GlobalYouTube.SYSTEM,
                                                            stringResource(Res.string.app_language_system),
                                                        ),
                                                    ) +
                                                    appLanguageChoices,
                                                SettingsHostChoice.LYRICS_TARGET_LANGUAGE to lyricsLanguageChoices,
                                            ),
                                        onHostChoiceSelected = { choice, selected ->
                                            when (choice) {
                                                SettingsHostChoice.APP_ICON -> {
                                                    appIcons.select(selected)
                                                }

                                                SettingsHostChoice.CONTENT_LANGUAGE -> {
                                                    Settings.shared.contentLanguage = selected
                                                }

                                                SettingsHostChoice.CONTENT_COUNTRY -> {
                                                    Settings.shared.contentCountry = selected
                                                }

                                                SettingsHostChoice.LYRICS_TARGET_LANGUAGE -> {
                                                    Settings.shared.lyricsAiTargetLanguage = selected
                                                }

                                                SettingsHostChoice.APP_LANGUAGE -> {
                                                    applyIosAppLanguage(selected)
                                                    appRevision++
                                                }

                                                else -> {}
                                            }
                                            if (
                                                choice == SettingsHostChoice.CONTENT_LANGUAGE ||
                                                choice == SettingsHostChoice.CONTENT_COUNTRY
                                            ) {
                                                GlobalYouTube.applyContentRegion(
                                                    Settings.shared.contentCountry,
                                                    Settings.shared.contentLanguage,
                                                    systemCountry,
                                                    systemLanguage,
                                                )
                                                homeViewModel.refresh {}
                                                libraryViewModel.refresh()
                                            }
                                        },
                                        onSettingChanged = { effect, value ->
                                            if (effect == SettingsChangeEffect.SENTRY) Settings.shared.sentryEnabled = value ?: false
                                            appRevision++
                                            if (effect == SettingsChangeEffect.CONTENT) {
                                                GlobalYouTube.applyContentRegion(
                                                    Settings.shared.contentCountry,
                                                    Settings.shared.contentLanguage,
                                                    systemCountry,
                                                    systemLanguage,
                                                )
                                                homeViewModel.refresh {}
                                                libraryViewModel.refresh()
                                            }
                                        },
                                        onFloatSettingChanged = { effect, value ->
                                            appRevision++
                                            when (effect) {
                                                SettingsChangeEffect.PLAYBACK_SPEED -> playbackController.setPlaybackSpeed(value)
                                                SettingsChangeEffect.PLAYBACK_PITCH -> playbackController.setPlaybackPitch(value)
                                                else -> Unit
                                            }
                                        },
                                    ),
                                    Modifier
                                        .fillMaxSize()
                                        .padding(bottom = padding.calculateBottomPadding())
                                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                                )
                            }

                            is MobileRoute.Login -> {
                                loginContent(
                                    route.isSwitchingChannel,
                                    ::back,
                                    {
                                        back()
                                        appRevision++
                                        homeViewModel.refresh {}
                                        libraryViewModel.refresh()
                                    },
                                    {
                                        back()
                                        open(MobileRoute.TokenLogin)
                                    },
                                )
                            }

                            MobileRoute.TokenLogin -> {
                                TokenLoginScreen(
                                    onNavigateBack = {
                                        back()
                                        homeViewModel.refresh {}
                                        libraryViewModel.refresh()
                                    },
                                    bottomContentPadding = padding.calculateBottomPadding(),
                                )
                            }

                            MobileRoute.About -> {
                                AboutScreen(
                                    logo = painterResource(Res.drawable.logo),
                                    appName = stringResource(Res.string.app_name),
                                    onNavigateBack = ::back,
                                    bottomInset = padding.calculateBottomPadding(),
                                    onDevtoolsUnlocked = {
                                        Settings.shared.devtoolsUnlocked = true
                                        appRevision++
                                    },
                                    onPlayFavoriteSong = ::playVideo,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            is MobileRoute.Browse -> {
                                MobileBrowseScreen(
                                    bottomContentPadding = playerContentPadding,
                                    model =
                                        remember(
                                            detailStates,
                                            entry,
                                        ) { detailStates.entry(checkNotNull(entry)).browse(route.destination) },
                                    destination = route.destination,
                                    currentSongId = remember(playbackController) { derivedStateOf { playerState.currentSong?.id } },
                                    isPlaying = remember(playbackController) { derivedStateOf { playerState.isPlaying } },
                                    onBack = ::back,
                                    onItemClick = { item, queue ->
                                        when (item) {
                                            is SongItem -> playbackController.play(item, queue)
                                            is EpisodeItem -> playbackController.play(item.toSongItem(), queue)
                                            else -> openItem(item)
                                        }
                                    },
                                    onSongMenu = { menuItem = it to null },
                                    modifier = Modifier.fillMaxSize().padding(routePadding),
                                )
                            }

                            is MobileRoute.LocalShelf -> {
                                MobileLocalShelfScreen(
                                    bottomContentPadding = playerContentPadding,
                                    model = remember(detailStates, entry) { detailStates.entry(checkNotNull(entry)).shelf(route.section) },
                                    title = route.section.title,
                                    currentSongId = remember(playbackController) { derivedStateOf { playerState.currentSong?.id } },
                                    isPlaying = remember(playbackController) { derivedStateOf { playerState.isPlaying } },
                                    isItemEnabled = isHomeItemEnabled,
                                    onArtistClick = { open(MobileRoute.Artist(it, "")) },
                                    onAlbumClick = { open(MobileRoute.Album(it, "")) },
                                    onAlbumPlay = ::playAlbum,
                                    onBack = ::back,
                                    onItemClick = { item, queue ->
                                        when (item) {
                                            is SongItem -> playbackController.play(item, queue)
                                            is EpisodeItem -> playbackController.play(item.toSongItem(), queue)
                                            else -> openItem(item)
                                        }
                                    },
                                    onSongMenu = { menuItem = it to null },
                                    modifier = Modifier.fillMaxSize().padding(routePadding),
                                )
                            }

                            is MobileRoute.SongCollection -> {
                                MobileSongCollectionScreen(
                                    bottomContentPadding = playerContentPadding,
                                    onDownload = downloadSongs,
                                    onRemoveDownloads = removeDownloads,
                                    onAddToPlaylist = playlistWorkflow::select,
                                    onSwipeFeedback = onSwipeFeedback,
                                    type = route.type,
                                    title = route.title,
                                    localPlaylistId = route.localPlaylistId,
                                    database = database,
                                    playbackController = playbackController,
                                    onBack = ::back,
                                    onMoreOptions = { menuItem = it to null },
                                    onSongMenu = { song, remove, removeLabel ->
                                        menuItem = song to remove
                                        menuRemoveLabel = removeLabel
                                    },
                                    modifier = Modifier.fillMaxSize().padding(routePadding),
                                )
                            }

                            is MobileRoute.Album -> {
                                MobileAlbumScreen(
                                    bottomContentPadding = playerContentPadding,
                                    loader = remember(detailStates, entry) { detailStates.entry(checkNotNull(entry)).album(route.id) },
                                    onSwipeFeedback = onSwipeFeedback,
                                    onDownload = downloadSongs,
                                    onAddToPlaylist = playlistWorkflow::select,
                                    albumId = route.id,
                                    fallbackTitle = route.title,
                                    playbackController = playbackController,
                                    onBack = ::back,
                                    onArtistClick = { open(MobileRoute.Artist(it, "")) },
                                    onAlbumClick = { open(MobileRoute.Album(it, "")) },
                                    onMoreOptions = { menuItem = it to null },
                                    onSongMenu = { menuItem = it to null },
                                    modifier = Modifier.fillMaxSize().padding(routePadding),
                                )
                            }

                            is MobileRoute.Artist -> {
                                val detail = remember(detailStates, entry) { detailStates.entry(checkNotNull(entry)) }
                                MobileArtistScreen(
                                    loader = remember(detail) { detail.artist(route.id) },
                                    subscription = detail.subscription,
                                    onError = onSwipeFeedback,
                                    onAbout = { artist, titles -> open(MobileRoute.AboutArtist(artist, titles)) },
                                    artistId = route.id,
                                    fallbackTitle = route.name,
                                    playbackController = playbackController,
                                    onBack = ::back,
                                    onItemClick = ::openItem,
                                    onSongMenu = { menuItem = it to null },
                                    onBrowse = { open(MobileRoute.Browse(it)) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            is MobileRoute.AboutArtist -> {
                                Box(Modifier.fillMaxSize().padding(routePadding)) {
                                    MobileAboutArtistScreen(route, onBack = ::back, bottomContentPadding = playerContentPadding)
                                }
                            }

                            is MobileRoute.Playlist -> {
                                if (route.id.startsWith("MPSP")) {
                                    MobilePodcastScreen(
                                        bottomContentPadding = playerContentPadding,
                                        podcastId = route.id,
                                        fallbackTitle = route.title,
                                        database = database,
                                        playbackController = playbackController,
                                        onBack = ::back,
                                        onShare = { scope.launch { clipboard.setText(it) } },
                                        onSongMenu = { menuItem = it to null },
                                        onChannelClick = { open(MobileRoute.Artist(it, "")) },
                                        modifier = Modifier.fillMaxSize().padding(routePadding),
                                    )
                                } else if (route.id.isLikedMusicPlaylistId()) {
                                    MobileSongCollectionScreen(
                                        bottomContentPadding = playerContentPadding,
                                        onDownload = downloadSongs,
                                        onRemoveDownloads = removeDownloads,
                                        onAddToPlaylist = playlistWorkflow::select,
                                        onSwipeFeedback = onSwipeFeedback,
                                        type = "liked",
                                        title = route.title.ifBlank { likedSongsName },
                                        localPlaylistId = null,
                                        database = database,
                                        playbackController = playbackController,
                                        onBack = ::back,
                                        onMoreOptions = { menuItem = it to null },
                                        onSongMenu = { song, remove, removeLabel ->
                                            menuItem = song to remove
                                            menuRemoveLabel = removeLabel
                                        },
                                        modifier = Modifier.fillMaxSize().padding(routePadding),
                                    )
                                } else {
                                    MobilePlaylistScreen(
                                        bottomContentPadding = playerContentPadding,
                                        onDownload = downloadSongs,
                                        onAddToPlaylist = playlistWorkflow::select,
                                        database = database,
                                        onSwipeFeedback = onSwipeFeedback,
                                        playlistId = route.id,
                                        fallbackTitle = route.title,
                                        playbackController = playbackController,
                                        onBack = ::back,
                                        onArtistClick = { open(MobileRoute.Artist(it, "")) },
                                        onMoreOptions = { menuItem = it to null },
                                        onEdit = { playlistEditor.show(it, deleting = false) },
                                        onSongMenu = { menuItem = it to null },
                                        modifier = Modifier.fillMaxSize().padding(routePadding),
                                    )
                                }
                            }

                            is MobileRoute.Podcast -> {
                                MobilePodcastScreen(
                                    bottomContentPadding = playerContentPadding,
                                    podcastId = route.id,
                                    fallbackTitle = route.title,
                                    database = database,
                                    playbackController = playbackController,
                                    onBack = ::back,
                                    onShare = { scope.launch { clipboard.setText(it) } },
                                    onSongMenu = { menuItem = it to null },
                                    onChannelClick = { open(MobileRoute.Artist(it, "")) },
                                    modifier = Modifier.fillMaxSize().padding(routePadding),
                                )
                            }
                        }
                    }

                    SwipeBackLayout(
                        enabled = routeStack.isNotEmpty(),
                        onBack = ::back,
                        previousContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) },
                    ) {
                        val navigationMotion = rememberNavigationMotion()
                        NavSharedTransitionLayout {
                            AnimatedContent(
                                targetState = Triple(routeStack.size, routeStack.lastOrNull(), currentRoute),
                                transitionSpec = { navigationMotion.transition() },
                                label = "mobileNavigation",
                            ) { (_, entry, route) ->
                                NavigationDestination(route.motionScreen, this) { RenderRoute(route, entry) }
                            }
                        }
                    }
                }
            }

            PopupContent(menuItem) { (item, removeFromPlaylist) ->
                MobileItemMenu(
                    item = item,
                    onShowSongDetails = { detailsSong = it },
                    onError = onSwipeFeedback,
                    removeFromPlaylist = removeFromPlaylist,
                    removeLabel = menuRemoveLabel,
                    onRenamePlaylist = { playlistEditor.show(it, deleting = false) },
                    onDeletePlaylist = { playlistEditor.show(it, deleting = true) },
                    database = database,
                    playbackController = playbackController,
                    playerSession = playerSession,
                    listenTogetherManager = listenTogetherManager,
                    downloadedSongIds = downloadedSongIds,
                    onDownloadSong = { song -> downloadSongs(listOf(song)) },
                    onRemoveDownloads = removeDownloads,
                    onDownloadSongs = downloadSongs,
                    onDownloadPlaylist = { id -> reportDownloads(Res.plurals.queued_downloads) { onDownloadPlaylist(id) } },
                    onRemovePlaylistDownloads = { id, ids ->
                        reportDownloads(Res.plurals.removed_downloads) { onRemovePlaylistDownloads(id, ids) }
                    },
                    onDismiss = {
                        menuItem = null
                        menuRemoveLabel = null
                    },
                    onAddToPlaylist = playlistWorkflow::select,
                    onOpenItem = ::openItem,
                    onOpenArtist = { open(MobileRoute.Artist(it, "")) },
                    onOpenAlbum = { id, title -> open(MobileRoute.Album(id, title)) },
                    onCopyText = { text -> scope.launch { clipboard.setText(text) } },
                    onShareText = playerServices::shareText,
                    scope = scope,
                )
            }

            PopupContent(detailsSong) { song ->
                val state by playbackController.state.collectAsState()
                MobileSongDetailsSheet(
                    state = state,
                    song = song,
                    database = database,
                    onDismiss = { detailsSong = null },
                )
            }
            MobilePlaylistEditDialog(playlistEditor)

            PopupContent(videoLookup.failedVideoId) { videoId ->
                AlertDialog(
                    onDismissRequest = videoLookup::dismissFailure,
                    title = { Text(stringResource(Res.string.error)) },
                    text = { Text(stringResource(Res.string.playback_error_unable_to_play_link)) },
                    confirmButton = {
                        TextButton(
                            onClick = { playVideo(videoId) },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(Res.string.retry)) }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = videoLookup::dismissFailure,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(Res.string.cancel)) }
                    },
                )
            }

            if (playlistWorkflow.song != null || playlistWorkflow.creating || playlistWorkflow.duplicate != null) {
                MobilePlaylistActions(playlistWorkflow, database)
            }

            YouTubeSyncSnackbar(snackbarHostState)
            PopupVisibility(visible = YouTubeSessionState.showExpiredSessionNotice) {
                AlertDialog(
                    onDismissRequest = YouTubeSessionState::dismissExpiredSessionNotice,
                    title = { Text(stringResource(Res.string.youtube_session_expired_title)) },
                    text = { Text(stringResource(Res.string.youtube_session_expired_message)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                YouTubeSessionState.dismissExpiredSessionNotice()
                                open(MobileRoute.Login(isSwitchingChannel = false))
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
                                playerState.currentSong?.title.orEmpty(),
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                Settings.shared.pendingContinuePlayback = false
                                showContinuePlaybackPrompt = false
                                if (!playerState.isPlaying) {
                                    playbackController.togglePlayPause()
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

            if (playbackState.currentSong != null && miniPlayerBounds != null) {
                var playerRootOffset by remember { mutableStateOf(Offset.Zero) }
                BoxWithConstraints(
                    Modifier.fillMaxSize().onGloballyPositioned { playerRootOffset = it.positionInRoot() },
                ) {
                    val density = LocalDensity.current
                    val playerWidth = maxWidth
                    val collapsedBounds = miniPlayerBounds!!
                    val collapsedBound = maxHeight - with(density) { (collapsedBounds.top - playerRootOffset.y).toDp() }
                    val collapsedLeft = with(density) { (collapsedBounds.left - playerRootOffset.x).toDp() }.coerceIn(0.dp, playerWidth)
                    val collapsedRight =
                        (playerWidth - with(density) { (collapsedBounds.right - playerRootOffset.x).toDp() }).coerceIn(0.dp, playerWidth)
                    val sheetState =
                        rememberBottomSheetState(
                            dismissedBound = 0.dp,
                            collapsedBound = collapsedBound.coerceIn(0.dp, maxHeight),
                            expandedBound = maxHeight,
                            initialAnchor = COLLAPSED_ANCHOR,
                        )
                    LaunchedEffect(sheetState) {
                        snapshotFlow { sheetState.targetAnchor }.drop(1).collect { anchor ->
                            showPlayer = anchor == EXPANDED_ANCHOR
                        }
                    }
                    LaunchedEffect(showPlayer) {
                        if (showPlayer && sheetState.targetAnchor != EXPANDED_ANCHOR) {
                            sheetState.expandSoft()
                        } else if (!showPlayer && sheetState.targetAnchor != COLLAPSED_ANCHOR) {
                            sheetState.collapseSoft()
                        }
                    }
                    SideEffect { playerSheetSettled = sheetState.isSettledExpanded }
                    DisposableEffect(Unit) { onDispose { playerSheetSettled = false } }
                    val playerPlatform =
                        remember(
                            database,
                            downloadSongs,
                            removeDownloads,
                            listenTogetherManager,
                            touchExplorationEnabled,
                            lyricsShareAction,
                        ) {
                            MobilePlayerPlatform(
                                database,
                                downloadSongs,
                                removeDownloads,
                                playlistWorkflow::select,
                                listenTogetherManager,
                                touchExplorationEnabled,
                                lyricsShareAction,
                            )
                        }
                    val playerUiState by playerViewModel.uiState.collectAsState()
                    val expandedPlayerVisible by remember(sheetState) {
                        derivedStateOf { sheetState.progress > 0f && !sheetState.isCollapsed }
                    }
                    SideEffect { playerSheetCoversSnackbars = expandedPlayerVisible }
                    IosPlayerVisibilityEffect(
                        expandedPlayerVisible = expandedPlayerVisible,
                        lyricsVisible = playerUiState.isLyricsVisible,
                        lyricsFullscreenVisible = playerUiState.isLyricsVisible && playerUiState.isLyricsExpanded,
                        isPlaying = playerUiState.isPlaying,
                    )
                    CompositionLocalProvider(
                        LocalContentObscured provides false,
                        LocalPlayerSession provides playerSession,
                        LocalPlayerPlatform provides playerPlatform,
                    ) {
                        BottomSheet(
                            state = sheetState,
                            modifier =
                                Modifier.drawWithContent {
                                    val bottom =
                                        if (sheetState.progress <= 0f) {
                                            collapsedBounds.bottom - playerRootOffset.y
                                        } else {
                                            size.height
                                        }
                                    clipRect(bottom = bottom.coerceIn(0f, size.height)) {
                                        this@drawWithContent.drawContent()
                                    }
                                },
                            collapsedHeaderHeight = miniPlayerHeight,
                            isCollapsedContentClickable = false,
                            preserveContentWhenCollapsed = true,
                            morphCollapsedContainer = true,
                            collapsedContent = {
                                val miniPlayerActive = !sheetState.isExpanded
                                val positionMs = playerSession.currentPosition.collectWhilePlayerActive(miniPlayerActive)
                                val song = playbackState.currentSong
                                val isLiked = SongLikeState.isLiked(song.id)
                                val artist = song.artists.firstOrNull { !it.id.isNullOrBlank() }
                                val artistId = artist?.id
                                val isArtistSubscribed = rememberArtistSubscription(database, artistId)
                                val subscribedMessage = stringResource(Res.string.subscribed_to_artist, artist?.displayName.orEmpty())
                                val subscriptionError = stringResource(Res.string.error_occurred)
                                Box(
                                    Modifier.absolutePadding(
                                        left = collapsedLeft,
                                        right = collapsedRight,
                                    ),
                                ) {
                                    MiniPlayer(
                                        isActive = miniPlayerActive,
                                        song = song,
                                        isPlaying = playbackState.isPlaying,
                                        isLoading = playbackState.isLoading,
                                        positionMs = { positionMs.value },
                                        durationMs = playbackState.durationMs,
                                        isLiked = isLiked,
                                        onLike = { SongLikeState.toggleLike(song) },
                                        artistId = artistId,
                                        isArtistSubscribed = isArtistSubscribed,
                                        onSubscribeToggle =
                                            if (artistId != null) {
                                                {
                                                    toggleArtistSubscription(
                                                        scope,
                                                        database,
                                                        GlobalYouTubeRepository.instance,
                                                        artistId,
                                                        artist.displayName,
                                                        onSubscriptionChanged = { subscribed ->
                                                            if (subscribed) onSwipeFeedback(subscribedMessage)
                                                        },
                                                        onError = { onSwipeFeedback(subscriptionError) },
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                        onAddToPlaylist = { playlistWorkflow.select(song) },
                                        onTogglePlay = playbackController::togglePlayPause,
                                        onSkipNext = playbackController::skipToNext,
                                        onSkipPrevious = playbackController::skipToPrevious,
                                        canSkipNext = !isListenTogetherGuest && playbackController.canSkipNext,
                                        canSkipPrevious = !isListenTogetherGuest && playbackController.canSkipPrevious,
                                        playbackEnabled = !isListenTogetherGuest,
                                        error =
                                            playbackState.error?.let {
                                                stringResource(
                                                    Res.string.playback_error_no_playable_stream,
                                                )
                                            },
                                        onExpand = { setPlayerVisible(true) },
                                    )
                                }
                            },
                        ) {
                            PlayerScreen(
                                viewModel = playerViewModel,
                                bottomSheetState = sheetState,
                                onClose = { setPlayerVisible(false) },
                                onNavigateToListenTogether = {
                                    showPlayer = false
                                    open(MobileRoute.ListenTogether())
                                },
                                onNavigateToArtist = { artistId ->
                                    showPlayer = false
                                    open(
                                        MobileRoute.Artist(
                                            artistId,
                                            playerState.currentSong
                                                ?.artists
                                                ?.firstOrNull { it.id == artistId }
                                                ?.name ?: "",
                                        ),
                                    )
                                },
                                onNavigateToAlbum = { albumId ->
                                    showPlayer = false
                                    open(
                                        MobileRoute.Album(
                                            albumId,
                                            playerState.currentSong?.album?.name ?: (playerState.currentSong?.title ?: ""),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    if (expandedPlayerVisible) {
                        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
                    }
                }
            }
        }
    }
}
