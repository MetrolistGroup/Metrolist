@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.isHistoryPlaylistId
import com.metrolist.shared.db.refreshHistoryPlaylist
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.dismiss
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.listentogether.ListenTogetherManager
import com.metrolist.shared.listentogether.ListenTogetherRoomRole
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.playback.PlayerSession
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.HiddenLibraryItems
import com.metrolist.shared.state.AlbumLikeState
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.storage.trackedDownloadPlaylistId
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.ModalBottomSheet
import com.metrolist.shared.ui.components.localizedBuiltinPlaylist
import com.metrolist.shared.ui.menu.BuiltinPlaylistMenu
import com.metrolist.shared.ui.menu.MediaDownloadState
import com.metrolist.shared.ui.menu.MediaMenuPlaybackActions
import com.metrolist.shared.ui.menu.MediaMenuSheetContent
import com.metrolist.shared.ui.menu.albumMediaMenuModel
import com.metrolist.shared.ui.menu.artistMediaMenuModel
import com.metrolist.shared.ui.menu.loadMediaItemSongs
import com.metrolist.shared.ui.menu.playlistMediaMenuModel
import com.metrolist.shared.ui.menu.podcastMediaMenuModel
import com.metrolist.shared.ui.menu.rememberDeleteUploadAction
import com.metrolist.shared.ui.menu.rememberSongWithAlbum
import com.metrolist.shared.ui.menu.rememberSpeedDialToggle
import com.metrolist.shared.ui.menu.songMediaMenuModel
import com.metrolist.shared.ui.menu.withMediaMenuSongs
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.theme.ComponentShapes
import com.metrolist.shared.utils.ShareUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import com.metrolist.shared.db.entities.toSongItem as storedSongToSongItem
import com.metrolist.shared.ui.components.rememberModalSheetState as rememberBottomSheetState

private suspend fun loadPlaylistMenuSongs(
    item: PlaylistItem,
    database: MusicDatabase,
): List<SongItem> {
    val songs =
        if (item.id == "auto:liked") {
            SongLikeState.awaitCompleteLikes().orEmpty()
        } else if (item.isLocal) {
            database.refreshHistoryPlaylist(item.id)
            database
                .dao()
                .playlistSongs(item.id)
                .first()
                .map { it.song.storedSongToSongItem() }
        } else {
            loadMediaItemSongs(item)
        }
    return songs
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileItemMenu(
    item: YTItem,
    removeFromPlaylist: (() -> Unit)?,
    removeLabel: String?,
    onRenamePlaylist: (PlaylistItem) -> Unit,
    onDeletePlaylist: (PlaylistItem) -> Unit,
    database: MusicDatabase,
    playbackController: PlaybackController,
    playerSession: PlayerSession,
    listenTogetherManager: ListenTogetherManager,
    downloadedSongIds: Set<String>,
    onDownloadSong: (SongItem) -> Unit,
    onRemoveDownloads: (List<String>) -> Unit,
    onDownloadSongs: (List<SongItem>) -> Unit,
    onDownloadPlaylist: (String) -> Unit,
    onRemovePlaylistDownloads: (String, List<String>) -> Unit,
    onDismiss: () -> Unit,
    onAddToPlaylist: (SongItem) -> Unit,
    onOpenItem: (YTItem) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String, String) -> Unit,
    onCopyText: (String) -> Unit,
    onError: (String) -> Unit,
    onShowSongDetails: (SongItem) -> Unit,
    scope: CoroutineScope,
    onShareText: ((String) -> Unit)? = null,
) {
    if (item is PlaylistItem && item.id.startsWith("auto:") && item.id != "auto:liked") {
        BuiltinPlaylistMenu(item, database, onDismiss)
        return
    }
    val inRoom by listenTogetherManager.isInRoom.collectAsState()
    val role by listenTogetherManager.role.collectAsState()
    val isGuest = inRoom && role == ListenTogetherRoomRole.GUEST
    when (item) {
        is SongItem, is EpisodeItem -> {
            val song = rememberSongWithAlbum(item as? SongItem ?: (item as EpisodeItem).toSongItem())
            val pin = rememberSpeedDialToggle(song, database)
            val deleteUploadAction = rememberDeleteUploadAction(song, database, onDismiss)
            val sheetState = rememberBottomSheetState(SheetValue.Hidden)
            ModalBottomSheet(
                sheetState = sheetState,
                onDismissRequest = { onDismiss() },
                shape = ComponentShapes.BottomSheetShape,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 16.dp),
                ) {
                    MediaMenuSheetContent(
                        songMediaMenuModel(
                            song = song,
                            deleteUploadAction = deleteUploadAction,
                            onDismiss = { onDismiss() },
                            isLiked = SongLikeState.isLiked(song.id),
                            isDisliked = SongLikeState.isDisliked(song.id),
                            isPinned = pin.isPinned,
                            isGuest = isGuest,
                            onShowDetails = {
                                onDismiss()
                                onShowSongDetails(song)
                            },
                            onBlockArtist =
                                song.artists.firstOrNull { it.id != null }?.let { artist ->
                                    { BlockedContent.blockArtist(artist) }
                                },
                            onSuggestToHost = { listenTogetherManager.suggestTrack(song) },
                            downloadState =
                                if (song.id in
                                    downloadedSongIds
                                ) {
                                    MediaDownloadState.DOWNLOADED
                                } else {
                                    MediaDownloadState.NOT_DOWNLOADED
                                },
                            onDownload =
                                if (song.isLocal) {
                                    null
                                } else {
                                    {
                                        if (song.id in
                                            downloadedSongIds
                                        ) {
                                            onRemoveDownloads(listOf(song.id))
                                        } else {
                                            onDownloadSong(song)
                                        }
                                    }
                                },
                            onPlay =
                                if (isGuest) {
                                    null
                                } else {
                                    { playbackController.play(song) }
                                },
                            onLike = { SongLikeState.toggleLike(song) },
                            onDislike = { playerSession.toggleDislike(song) },
                            onStartRadio = { playbackController.startRadio(song) },
                            onShare = {
                                (onShareText ?: onCopyText).invoke(ShareUrls.song(song.id))
                            },
                            onAltShare = {
                                onCopyText(ShareUrls.song(song.id))
                            },
                            onPlayNext = { playbackController.playNext(listOf(song)) },
                            onAddToQueue = { playbackController.addToQueue(listOf(song)) },
                            onTogglePin = pin.toggle,
                            onAddToPlaylist = {
                                onAddToPlaylist(song)
                                onDismiss()
                            },
                            onGoToArtist = { artistId ->
                                onDismiss()
                                onOpenArtist(artistId)
                            },
                            onGoToAlbum =
                                song.album?.id?.let { albumId ->
                                    {
                                        onDismiss()
                                        onOpenAlbum(albumId, song.album.name)
                                    }
                                },
                            onRemove =
                                removeFromPlaylist?.let { remove ->
                                    {
                                        onDismiss()
                                        remove()
                                    }
                                },
                            removeLabel = removeLabel,
                        ),
                    )
                }
            }
        }

        is PlaylistItem -> {
            val item = localizedBuiltinPlaylist(item)
            val errorText = stringResource(Res.string.error_occurred)
            val bookmark = rememberPlaylistBookmark(item, database, scope) { onError(errorText) }
            val pin = rememberSpeedDialToggle(item, database)
            var playlistSongs by remember(item, database) { mutableStateOf<List<SongItem>?>(null) }

            suspend fun resolveSongs(): List<SongItem> = playlistSongs ?: loadPlaylistMenuSongs(item, database).also { playlistSongs = it }
            val playlistIsDownloaded =
                playlistSongs?.let { songs ->
                    songs.any { !it.isUnavailable } &&
                        songs.all { it.isUnavailable || it.id in downloadedSongIds }
                }
                    ?: item.isDownloaded
            val playlistDownloadState = if (playlistIsDownloaded) MediaDownloadState.DOWNLOADED else MediaDownloadState.NOT_DOWNLOADED
            val downloadPlaylistAction: () -> Unit = {
                onDismiss()
                scope.launch {
                    if (!playlistIsDownloaded && trackedDownloadPlaylistId(item.id) != null) {
                        onDownloadPlaylist(item.id)
                    } else {
                        val songs = resolveSongs().filterNot(SongItem::isLocal)
                        if (playlistIsDownloaded) onRemovePlaylistDownloads(item.id, songs.map(SongItem::id)) else onDownloadSongs(songs)
                    }
                }
            }
            val playbackActions =
                MediaMenuPlaybackActions(
                    item,
                    playbackController,
                    withSongs = { action ->
                        onDismiss()
                        scope.launch { withMediaMenuSongs(::resolveSongs, action = action) }
                    },
                    resetArtistShuffle = true,
                )
            val sheetState = rememberBottomSheetState(SheetValue.Hidden)
            ModalBottomSheet(
                sheetState = sheetState,
                onDismissRequest = { onDismiss() },
                shape = ComponentShapes.BottomSheetShape,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 16.dp),
                ) {
                    MediaMenuSheetContent(
                        playlistMediaMenuModel(
                            playlist = item,
                            isFavorite = bookmark.isBookmarked,
                            onFavorite = bookmark.toggle.takeUnless { item.id.startsWith("auto:") },
                            onRename =
                                if (item.isLocal ||
                                    (item.isEditable && GlobalYouTubeRepository.instance.canManagePlaylists())
                                ) {
                                    ({ onRenamePlaylist(item) })
                                } else {
                                    null
                                },
                            onDelete =
                                if (item.isLocal ||
                                    (item.isEditable && GlobalYouTubeRepository.instance.canManagePlaylists())
                                ) {
                                    ({ onDeletePlaylist(item) })
                                } else {
                                    null
                                },
                            onDismiss = { onDismiss() },
                            isPinned = pin.isPinned,
                            downloadState = playlistDownloadState,
                            onDownload = downloadPlaylistAction,
                            onPlay = playbackActions::play,
                            onShuffle = playbackActions::shuffle,
                            onPlayNext = playbackActions::playNext,
                            onAddToQueue = playbackActions::addToQueue,
                            onTogglePin = pin.toggle,
                            onHideFromLibrary =
                                {
                                    HiddenLibraryItems.hide(
                                        item,
                                    )
                                }.takeUnless { item.id.startsWith("auto:") || isHistoryPlaylistId(item.id) },
                            onShare =
                                if (item.id.startsWith("auto:")) {
                                    null
                                } else {
                                    {
                                        onDismiss()
                                        onCopyText(ShareUrls.playlist(item.id))
                                    }
                                },
                        ),
                    )
                }
            }
        }

        is AlbumItem -> {
            val isFavorite by remember(item.id) { AlbumLikeState.albumLiked(item.id) }.collectAsState(false)
            val errorText = stringResource(Res.string.error_occurred)
            val pin = rememberSpeedDialToggle(item, database)
            var albumSongs by remember(item) { mutableStateOf<List<SongItem>?>(null) }

            suspend fun resolveSongs(): List<SongItem> = albumSongs ?: loadMediaItemSongs(item).also { albumSongs = it }
            val albumIsDownloaded =
                albumSongs?.let { songs -> songs.any { !it.isUnavailable } && songs.all { it.isUnavailable || it.id in downloadedSongIds } }
                    ?: item.isDownloaded
            val downloadAlbumAction: () -> Unit = {
                onDismiss()
                scope.launch {
                    val songs = resolveSongs().filterNot(SongItem::isLocal)
                    if (albumIsDownloaded) onRemoveDownloads(songs.map(SongItem::id)) else onDownloadSongs(songs)
                }
            }
            val playbackActions =
                MediaMenuPlaybackActions(
                    item,
                    playbackController,
                    withSongs = { action ->
                        onDismiss()
                        scope.launch { withMediaMenuSongs(::resolveSongs, action = action) }
                    },
                    resetArtistShuffle = true,
                )
            val sheetState = rememberBottomSheetState(SheetValue.Hidden)
            ModalBottomSheet(
                sheetState = sheetState,
                onDismissRequest = { onDismiss() },
                shape = ComponentShapes.BottomSheetShape,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 16.dp),
                ) {
                    MediaMenuSheetContent(
                        albumMediaMenuModel(
                            album = item,
                            isFavorite = isFavorite,
                            onFavorite = {
                                scope.launch {
                                    try {
                                        AlbumLikeState.toggleAlbumLike(item, resolveSongs()) { result ->
                                            if (result.isFailure) onError(errorText)
                                        }
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        onError(errorText)
                                    }
                                }
                            },
                            onDismiss = { onDismiss() },
                            isPinned = pin.isPinned,
                            downloadState =
                                if (albumIsDownloaded) MediaDownloadState.DOWNLOADED else MediaDownloadState.NOT_DOWNLOADED,
                            onDownload = downloadAlbumAction,
                            onPlay = playbackActions::play,
                            onShuffle = playbackActions::shuffle,
                            onPlayNext = playbackActions::playNext,
                            onAddToQueue = playbackActions::addToQueue,
                            onTogglePin = pin.toggle,
                            onShare = {
                                onDismiss()
                                onCopyText(ShareUrls.album(item.id))
                            },
                            onGoToArtist = { artistId ->
                                onDismiss()
                                onOpenArtist(artistId)
                            },
                        ),
                    )
                }
            }
        }

        is ArtistItem -> {
            val pin = rememberSpeedDialToggle(item, database)
            val playbackActions =
                MediaMenuPlaybackActions(
                    item,
                    playbackController,
                    withSongs = { action ->
                        onDismiss()
                        scope.launch { withMediaMenuSongs({ loadMediaItemSongs(item) }, action = action) }
                    },
                    resetArtistShuffle = true,
                )
            val sheetState = rememberBottomSheetState(SheetValue.Hidden)
            ModalBottomSheet(
                sheetState = sheetState,
                onDismissRequest = { onDismiss() },
                shape = ComponentShapes.BottomSheetShape,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 16.dp),
                ) {
                    MediaMenuSheetContent(
                        artistMediaMenuModel(
                            artist = item,
                            onDismiss = { onDismiss() },
                            isPinned = pin.isPinned,
                            onPlay = playbackActions::play,
                            onShuffle = playbackActions::shuffle,
                            onPlayNext = playbackActions::playNext,
                            onAddToQueue = playbackActions::addToQueue,
                            onTogglePin = pin.toggle,
                            onShare = {
                                onDismiss()
                                onCopyText(ShareUrls.artist(item.id))
                            },
                        ),
                    )
                }
            }
        }

        is PodcastItem -> {
            val pin = rememberSpeedDialToggle(item, database)
            val playbackActions =
                MediaMenuPlaybackActions(
                    item,
                    playbackController,
                    withSongs = { action ->
                        onDismiss()
                        scope.launch { withMediaMenuSongs({ loadMediaItemSongs(item) }, action = action) }
                    },
                    resetArtistShuffle = true,
                )
            val sheetState = rememberBottomSheetState(SheetValue.Hidden)
            ModalBottomSheet(
                sheetState = sheetState,
                onDismissRequest = { onDismiss() },
                shape = ComponentShapes.BottomSheetShape,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 16.dp),
                ) {
                    MediaMenuSheetContent(
                        podcastMediaMenuModel(
                            podcast = item,
                            onDismiss = { onDismiss() },
                            isPinned = pin.isPinned,
                            onPlay = playbackActions::play,
                            onShuffle = playbackActions::shuffle,
                            onPlayOldestFirst = playbackActions::playOldestFirst,
                            onPlayNext = playbackActions::playNext,
                            onAddToQueue = playbackActions::addToQueue,
                            onTogglePin = pin.toggle,
                            onShare = {
                                onDismiss()
                                onCopyText(ShareUrls.podcast(item.id))
                            },
                        ),
                    )
                }
            }
        }

        else -> {
            AlertDialog(
                onDismissRequest = { onDismiss() },
                title = { Text(item.title) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onOpenItem(item)
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.play)) }
                },
                dismissButton = {
                    TextButton(onClick = { onDismiss() }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.dismiss)) }
                },
            )
        }
    }
}
