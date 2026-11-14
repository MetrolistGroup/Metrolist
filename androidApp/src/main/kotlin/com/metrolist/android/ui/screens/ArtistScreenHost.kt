@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.metrolist.android.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.metrolist.android.LocalMiniPlayerBottomInset
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.ui.component.SwipeGestureHandler
import com.metrolist.android.ui.components.AnimatedCoverVideo
import com.metrolist.android.ui.components.handleItemClick
import com.metrolist.android.ui.menu.AlbumMenu
import com.metrolist.android.ui.menu.ArtistMenu
import com.metrolist.android.ui.menu.PlaylistMenu
import com.metrolist.android.ui.menu.SongMenu
import com.metrolist.android.ui.theme.SystemBarAppearance
import com.metrolist.android.utils.startActivitySafely
import com.metrolist.android.viewmodels.ArtistViewModel
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.share_album
import com.metrolist.shared.generated.resources.share_artist
import com.metrolist.shared.generated.resources.share_playlist
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.fullArtistShelfQueue
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.CollectionPreviews
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.screens.ArtistScreen
import com.metrolist.shared.utils.ShareUrls
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ArtistScreenHost(
    artistId: String,
    viewModel: ArtistViewModel =
        koinViewModel(
            key = artistId,
            parameters = { parametersOf(artistId) },
        ),
    onNavigateToAlbum: (String, List<AlbumItem>, String?) -> Unit = { _, _, _ -> },
    onNavigateToPlaylist: (String, String?) -> Unit = { _, _ -> },
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToPodcast: (String) -> Unit = {},
    onNavigateToBrowse: (String, String?, String) -> Unit = { _, _, _ -> },
    onNavigateToAboutArtist: (ArtistItem) -> Unit = {},
    onBackClick: () -> Unit = {},
) {
    val shareArtistText = stringResource(Res.string.share_artist)
    val sharePlaylistText = stringResource(Res.string.share_playlist)
    val shareAlbumText = stringResource(Res.string.share_album)
    val subscriptionError = stringResource(Res.string.error_occurred)

    val playerConnection = LocalPlayerConnection.current
    val currentSong = playerConnection?.currentSong?.collectAsState()?.value
    val isPlaying = playerConnection?.isPlaying?.collectAsState()?.value == true
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uiState by viewModel.shared.uiState.collectAsState()
    val playbackNetwork by PlaybackNetworkStateStore.state.collectAsState()
    val artworkAllowedOnNetwork = Settings.shared.isAnimatedArtworkAllowedOnNetwork(playbackNetwork)
    val info = uiState.artistInfo
    val sections = uiState.sections
    val sectionBrowseIds = uiState.sectionBrowseIds
    var selectedMenuSong by remember { mutableStateOf<SongItem?>(null) }
    var selectedMenuAlbum by remember { mutableStateOf<AlbumItem?>(null) }
    var selectedMenuPlaylist by remember { mutableStateOf<PlaylistItem?>(null) }
    var selectedMenuArtist by remember { mutableStateOf<ArtistItem?>(null) }
    var showArtistMenu by remember(artistId) { mutableStateOf(false) }
    val appleMusicImage = uiState.appleMusicArtworkUrl
    val showYouTubeImage =
        !Settings.shared.enableAppleMusicArtistImages ||
            (uiState.isAppleMusicImageLookupComplete && appleMusicImage == null)
    val displayArtist =
        remember(info, appleMusicImage, showYouTubeImage) {
            info?.copy(
                banner = appleMusicImage ?: info.banner.takeIf { showYouTubeImage },
                thumbnail = appleMusicImage ?: info.thumbnail.takeIf { showYouTubeImage },
            )
        }

    val initialArtist = remember(artistId) { CollectionPreviews.artist(artistId) ?: ArtistItem(artistId, "", null) }
    val menuArtist = displayArtist ?: initialArtist

    LaunchedEffect(
        info?.title,
        Settings.shared.enableAppleMusicArtistImages,
        Settings.shared.enableAppleMusicArtistVideos,
        Settings.shared.appleMusicArtistVideoResolution,
        Settings.shared.lastResolvedContentCountry,
        Settings.shared.lastResolvedContentLanguage,
    ) {
        viewModel.shared.refreshAppleMusicArtwork()
        viewModel.shared.refreshLastFmForLanguage()
    }

    val playbackErrorText = stringResource(Res.string.error_occurred)

    fun playArtistSongs(shuffle: Boolean) {
        val artist = menuArtist
        scope.launch {
            val songs =
                GlobalYouTubeRepository.instance.getArtistSongs(
                    artist.id,
                    ArtistPageResult(sections, sectionBrowseIds).takeIf {
                        info !=
                            null
                    },
                ) {
                    Toast.makeText(context, playbackErrorText, Toast.LENGTH_SHORT).show()
                }
            if (songs.isEmpty()) return@launch
            if (shuffle) {
                playerConnection?.shuffleQueue(songs, artist.title, songsOnly = true)
            } else {
                playerConnection?.playQueue(songs, 0, artist.title, shuffleModeEnabled = false, songsOnly = true)
            }
        }
    }

    fun refresh() = viewModel.shared.refresh {}

    fun withAlbumSongs(
        album: AlbumItem,
        action: (List<SongItem>) -> Unit,
    ) {
        scope.launch {
            GlobalYouTubeRepository.instance
                .getAlbum(album.id)
                ?.songs
                ?.takeIf { it.isNotEmpty() }
                ?.let(action)
        }
    }

    val listState = rememberLazyListState()
    var appBarVisible by remember { mutableStateOf(false) }
    val headerVisible = !appBarVisible
    val hasHeaderImage = !menuArtist.banner.isNullOrBlank() || !menuArtist.thumbnail.isNullOrBlank()
    val view = LocalView.current
    LifecycleResumeEffect(view, headerVisible, hasHeaderImage) {
        val claimed = headerVisible && hasHeaderImage
        if (claimed) SystemBarAppearance.imageOwners++
        onPauseOrDispose {
            if (claimed) SystemBarAppearance.imageOwners--
        }
    }

    ArtistScreen(
        listState = listState,
        onItemsVisible = viewModel.shared::loadItemMetadata,
        artist = displayArtist,
        artistId = artistId,
        currentSongId = currentSong?.id,
        isPlaying = isPlaying,
        sections = sections,
        songContainer = { song, content ->
            SwipeGestureHandler(
                onSwipeLeft = { playerConnection?.addToQueue(song) },
                onSwipeRight = { playerConnection?.addToQueue(song, playNext = true) },
                enabled = playerConnection != null && Settings.shared.swipeToSong,
                content = content,
            )
        },
        sectionBrowseIds = sectionBrowseIds,
        loadableSections = uiState.loadableSections,
        sectionContinuation = { title ->
            LaunchedEffect(title, uiState.sectionContinuations[title]) {
                if (title !in uiState.failedSections) viewModel.shared.loadSection(title)
            }
            if (title in uiState.failedSections) {
                TextButton(
                    onClick = { viewModel.shared.loadSection(title) },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.retry)) }
            } else {
                ContainedLoadingIndicator()
            }
        },
        isLoading = uiState.isLoading,
        errorMessage = uiState.errorMessage,
        onRefresh = ::refresh,
        onAlbumArtworkClick = { album, token ->
            onNavigateToAlbum(album.id, sections.values.flatten().filterIsInstance<AlbumItem>(), token)
        },
        onItemClick = { item ->
            if (item is SongItem) {
                scope.launch {
                    val tracks = fullArtistShelfQueue(item, sections, sectionBrowseIds)
                    playerConnection?.playQueue(tracks, startIndex = tracks.indexOfFirst { it.id == item.id }, finite = true)
                }
            } else {
                handleItemClick(
                    item,
                    { id -> onNavigateToAlbum(id, sections.values.flatten().filterIsInstance<AlbumItem>(), null) },
                    onNavigateToArtist,
                    onNavigateToPlaylist,
                    onNavigateToPodcast,
                    playerConnection,
                    scope,
                )
            }
        },
        onItemLongClick = { item ->
            when (item) {
                is SongItem -> selectedMenuSong = item
                is AlbumItem -> selectedMenuAlbum = item
                is PlaylistItem -> selectedMenuPlaylist = item
                is ArtistItem -> selectedMenuArtist = item
                else -> Unit
            }
        },
        onSectionClick = onNavigateToBrowse,
        onPlayItem = { item ->
            if (item is AlbumItem) {
                playerConnection?.playAlbum(item.id, item.title)
            }
        },
        isSongLiked = { songId -> SongLikeState.likedSongs.any { it.id == songId } },
        onLikeSong = { SongLikeState.toggleLike(it) },
        onBackClick = onBackClick,
        onMenuClick = { showArtistMenu = true },
        onPlayAllClick = { playArtistSongs(false) },
        onShuffleClick = { playArtistSongs(true) },
        onMixClick = {
            displayArtist?.let { artist ->
                scope.launch {
                    GlobalYouTubeRepository.instance.startArtistRadio(artist.id).takeIf { it.isNotEmpty() }?.let {
                        playerConnection?.playQueue(it, 0, artist.title)
                    }
                }
            }
        },
        onRefreshClick = if (LocalIsTv.current) ::refresh else null,
        isSubscribed = viewModel.shared.subscription.isSubscribed,
        subscribeSuccessCount = viewModel.shared.subscription.subscribeSuccessCount,
        showSubscribe = true,
        onSubscribeClick = {
            viewModel.shared.toggleSubscription {
                Toast.makeText(context, subscriptionError, Toast.LENGTH_SHORT).show()
            }
        },
        onUnblockClick = { displayArtist?.let { BlockedContent.unblockArtist(it.id) } },
        description = displayArtist?.description?.takeIf(String::isNotBlank) ?: uiState.lastFmBio,
        aboutImageUrl = uiState.lastFmPhotoUrl ?: info?.thumbnail ?: info?.banner,
        onAboutClick = { info?.let { onNavigateToAboutArtist(it) } },
        headerVideoLayer =
            uiState.appleMusicVideoUrl
                ?.takeIf { Settings.shared.enableAppleMusicArtistVideos && artworkAllowedOnNetwork }
                ?.let { url -> { modifier -> AnimatedCoverVideo(url = url, modifier = modifier) } },
        contentPadding = PaddingValues(bottom = LocalMiniPlayerBottomInset.current),
        onAppBarVisibleChange = { appBarVisible = it },
        overlay = {
            PopupContent(selectedMenuArtist) { artist ->
                ArtistMenu(
                    artist = artist,
                    onDismiss = { selectedMenuArtist = null },
                    onShuffle = {
                        scope.launch {
                            val songs = GlobalYouTubeRepository.instance.getArtistSongs(artist.id)
                            playerConnection?.shuffleQueue(songs, artist.title, songsOnly = true)
                        }
                    },
                    onShare = {
                        context.startActivitySafely(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, ShareUrls.artist(artist.id))
                                },
                                shareArtistText,
                            ),
                        )
                    },
                )
            }
            PopupContent(selectedMenuPlaylist) { playlist ->
                PlaylistMenu(
                    playlist = playlist,
                    onDismiss = { selectedMenuPlaylist = null },
                    onPlay = { playerConnection?.playPlaylist(playlist.id, playlist.title) },
                    onShuffle = { playerConnection?.playPlaylist(playlist.id, playlist.title, shuffle = true) },
                    onShare = {
                        context.startActivitySafely(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, ShareUrls.playlist(playlist.id))
                                },
                                sharePlaylistText,
                            ),
                        )
                    },
                )
            }
            PopupContent(menuArtist.takeIf { showArtistMenu }) { displayArtist ->
                ArtistMenu(
                    artist = displayArtist.copy(description = displayArtist.description?.takeIf(String::isNotBlank) ?: uiState.lastFmBio),
                    onDismiss = { showArtistMenu = false },
                    onShuffle = { playArtistSongs(true) },
                    onShare = {
                        val chooser =
                            Intent
                                .createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, ShareUrls.artist(artistId))
                                    },
                                    shareArtistText,
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivitySafely(chooser)
                    },
                )
            }
            PopupContent(selectedMenuAlbum) { album ->
                AlbumMenu(
                    album = album,
                    onDismiss = { selectedMenuAlbum = null },
                    onPlay = { playerConnection?.playAlbum(album.id, album.title) },
                    onShuffle = { playerConnection?.playAlbum(album.id, album.title, shuffle = true) },
                    onShare = {
                        val chooser =
                            Intent
                                .createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, ShareUrls.album(album.id))
                                    },
                                    shareAlbumText,
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivitySafely(chooser)
                    },
                    onPlayNext = {
                        withAlbumSongs(album) { songs ->
                            playerConnection?.addToQueue(songs, playNext = true)
                        }
                    },
                    onAddToQueue = {
                        withAlbumSongs(album) { songs -> playerConnection?.addToQueue(songs) }
                    },
                    onGoToArtist = onNavigateToArtist,
                )
            }
            PopupContent(selectedMenuSong) { song ->
                SongMenu(
                    song = song,
                    isLiked = SongLikeState.isLiked(song.id),
                    onDismiss = { selectedMenuSong = null },
                    onPlayNext = { playerConnection?.addToQueue(song, true) },
                    onAddToQueue = { playerConnection?.addToQueue(song) },
                    onLike = { SongLikeState.toggleLike(song) },
                    onGoToAlbum = { song.album?.id?.let { onNavigateToAlbum(it, emptyList(), null) } },
                    onGoToArtist = onNavigateToArtist,
                )
            }
        },
    )
}
