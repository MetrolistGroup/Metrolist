/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.HomeState
import com.metrolist.desktop.state.content.LibraryState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.desktop.state.content.PodcastState
import com.metrolist.desktop.state.playback.ListenTogetherState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.storage.DownloadManager
import com.metrolist.desktop.storage.DownloadState
import com.metrolist.desktop.storage.SongCacheManager
import com.metrolist.desktop.storage.playlistDownloadProgress
import com.metrolist.desktop.storage.playlistDownloadState
import com.metrolist.desktop.utils.exportCollection
import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.loadAndCachePlaylist
import com.metrolist.shared.db.refreshHistoryPlaylist
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_download
import com.metrolist.shared.generated.resources.action_like
import com.metrolist.shared.generated.resources.add_to_playlist
import com.metrolist.shared.generated.resources.add_to_queue
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.delete_downloaded_audio
import com.metrolist.shared.generated.resources.delete_playlist
import com.metrolist.shared.generated.resources.dislike_song
import com.metrolist.shared.generated.resources.downloading_percent
import com.metrolist.shared.generated.resources.error_unknown
import com.metrolist.shared.generated.resources.export_collection
import com.metrolist.shared.generated.resources.export_format_csv
import com.metrolist.shared.generated.resources.export_format_csv_description
import com.metrolist.shared.generated.resources.export_format_m3u
import com.metrolist.shared.generated.resources.export_format_m3u_description
import com.metrolist.shared.generated.resources.hide_from_library
import com.metrolist.shared.generated.resources.make_available_offline
import com.metrolist.shared.generated.resources.move_down
import com.metrolist.shared.generated.resources.move_up
import com.metrolist.shared.generated.resources.percent_symbol
import com.metrolist.shared.generated.resources.pin_to_speed_dial
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.play_next
import com.metrolist.shared.generated.resources.remove
import com.metrolist.shared.generated.resources.remove_dislike
import com.metrolist.shared.generated.resources.remove_download
import com.metrolist.shared.generated.resources.remove_from_playlist
import com.metrolist.shared.generated.resources.remove_from_saved_episodes
import com.metrolist.shared.generated.resources.remove_from_saved_podcasts
import com.metrolist.shared.generated.resources.rename
import com.metrolist.shared.generated.resources.save
import com.metrolist.shared.generated.resources.save_episode
import com.metrolist.shared.generated.resources.save_podcast
import com.metrolist.shared.generated.resources.select_songs
import com.metrolist.shared.generated.resources.share
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.generated.resources.start_radio
import com.metrolist.shared.generated.resources.stored_remove_selected_message
import com.metrolist.shared.generated.resources.stored_remove_selected_title
import com.metrolist.shared.generated.resources.subscribe
import com.metrolist.shared.generated.resources.suggest_to_host
import com.metrolist.shared.generated.resources.unlike
import com.metrolist.shared.generated.resources.unpin_from_speed_dial
import com.metrolist.shared.generated.resources.unsubscribe
import com.metrolist.shared.generated.resources.view_album
import com.metrolist.shared.generated.resources.view_artist
import com.metrolist.shared.generated.resources.view_podcast
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.model.speedDialItemId
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.Material3MenuGroup
import com.metrolist.shared.ui.components.Material3MenuItemData
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.localizedBuiltinPlaylist
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.CursorContextMenuState
import com.metrolist.shared.ui.menu.CursorDropdownMenu
import com.metrolist.shared.ui.menu.ExpressiveMenuActionGroup
import com.metrolist.shared.ui.menu.MediaContextMenu
import com.metrolist.shared.ui.menu.MediaDownloadState
import com.metrolist.shared.ui.menu.MediaMenuModel
import com.metrolist.shared.ui.menu.MediaMenuPlaybackActions
import com.metrolist.shared.ui.menu.albumMediaMenuModel
import com.metrolist.shared.ui.menu.artistMediaMenuModel
import com.metrolist.shared.ui.menu.cursorContextMenu
import com.metrolist.shared.ui.menu.loadMediaItemSongs
import com.metrolist.shared.ui.menu.playlistMediaMenuModel
import com.metrolist.shared.ui.menu.podcastMediaMenuModel
import com.metrolist.shared.ui.menu.rememberCursorContextMenuState
import com.metrolist.shared.ui.menu.rememberDeleteUploadAction
import com.metrolist.shared.ui.menu.songMediaMenuModel
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.theme.ComponentShapes
import com.metrolist.shared.utils.CollectionExportFormat
import com.metrolist.shared.utils.ShareUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.GlobalContext
import com.metrolist.shared.db.entities.toSongItem as localSongItem

@Composable
fun SongContextMenu(
    song: SongItem,
    expanded: Boolean,
    offset: DpOffset = DpOffset.Zero,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    removeLabel: String? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onHideFromLibrary: (() -> Unit)? = null,
    additionalActions: List<PlayerMenuAction> = emptyList(),
    onDismiss: () -> Unit,
) {
    var showDetails by remember(song.id) { mutableStateOf(false) }
    var showEditDialog by remember(song.id) { mutableStateOf(false) }
    val deleteUploadAction =
        if (expanded) rememberDeleteUploadAction(song, remember { GlobalContext.get().get<MusicDatabase>() }, onDismiss) else null
    if (showEditDialog) {
        SongMetadataEditorDialog(
            song = song,
            onSaved = {
                showEditDialog = false
                onDismiss()
            },
            onDismiss = { showEditDialog = false },
        )
    }
    PopupVisibility(visible = showDetails) {
        MediaDetailsDialog(song, onDismiss = {
            showDetails = false
            onDismiss()
        })
    }
    MediaContextMenu(expanded = expanded && !showEditDialog && !showDetails, offset = offset, onDismiss = onDismiss) {
        val playback = LocalPlaybackController.current
        val isLiked = PlayerState.isLiked(song.id)
        val isDisliked = PlayerState.isDisliked(song.id)
        val isGuest = PlayerState.isListenTogetherGuest()
        val downloadState = mediaDownloadState(song.id)
        val isPinned = song.id in HomeState.uiState.speedDialPinnedIds
        songMediaMenuModel(
            song = song,
            deleteUploadAction = deleteUploadAction,
            onDismiss = onDismiss,
            isLiked = isLiked,
            isDisliked = isDisliked,
            isGuest = isGuest,
            isPinned = isPinned,
            downloadState = downloadState,
            downloadProgress = ((LibraryState.downloadProgress[song.id] ?: 0f) * 100).toInt(),
            onPlay = { playback.play(song) },
            onLike = {
                PlayerState.toggleLike(song)
                onDismiss()
            },
            onDislike = { PlayerState.toggleDislike(song) },
            onStartRadio = { playback.startRadio(song) },
            onAddToPlaylist = {
                showAddToPlaylist(song)
                onDismiss()
            },
            onShare = { shareSong(song) },
            onSuggestToHost = { suggestToHost(song) },
            onPlayNext = { playback.playNext(listOf(song)) },
            onAddToQueue = { playback.addToQueue(listOf(song)) },
            onTogglePin = {
                if (isPinned) HomeState.unpinSpeedDialItem(song.id) else HomeState.pinSpeedDialItem(song)
            },
            onDownload = {
                if (downloadState == MediaDownloadState.DOWNLOADED) {
                    DownloadManager.deleteDownload(song.id)
                } else {
                    DownloadManager.download(song)
                }
            },
            onBlockArtist =
                song.artists.firstOrNull { it.id != null }?.let { artist ->
                    { PlayerState.blockArtist(artist) }
                },
            onHideFromLibrary = onHideFromLibrary,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onRemove = onRemoveFromPlaylist,
            removeLabel = removeLabel ?: stringResource(Res.string.remove_from_playlist),
            onShowDetails = { showDetails = true },
            onEdit = { showEditDialog = true },
            onGoToArtist = ArtistState::fetchArtistData,
            onGoToAlbum =
                song.album?.id?.let { albumId ->
                    {
                        if (song.podcastId != null) {
                            PodcastState.fetch(albumId)
                        } else {
                            PlaylistAlbumState.fetchAlbumData(albumId)
                        }
                    }
                },
            additionalActions = additionalActions,
        )
    }
}

@Composable
fun AlbumContextMenu(
    album: AlbumItem,
    songs: List<SongItem> = emptyList(),
    expanded: Boolean,
    offset: DpOffset = DpOffset.Zero,
    onDismiss: () -> Unit,
    isFavorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    onHideFromLibrary: (() -> Unit)? = null,
    pinnedId: String? = null,
    additionalActions: List<PlayerMenuAction> = emptyList(),
) {
    MediaContextMenu(expanded = expanded, offset = offset, onDismiss = onDismiss) {
        val playback = LocalPlaybackController.current
        val isPinned = album.id in HomeState.uiState.speedDialPinnedIds
        val playbackActions =
            MediaMenuPlaybackActions(
                album,
                playback,
                withSongs = { action -> withMediaSongs(album, songs, action) },
            )
        albumMediaMenuModel(
            album = album,
            isFavorite = isFavorite,
            onFavorite = onFavorite,
            onDismiss = onDismiss,
            isPinned = isPinned,
            onPlay = playbackActions::play,
            onShuffle = playbackActions::shuffle,
            onShare = { AppState.copyToClipboard(ShareUrls.album(album.id)) },
            onPlayNext = playbackActions::playNext,
            onAddToQueue = playbackActions::addToQueue,
            onAddToPlaylist = { withMediaSongs(album, songs) { PlayerState.showAddToPlaylistSongs = it } },
            onTogglePin = {
                if (isPinned) HomeState.unpinSpeedDialItem(album.id) else HomeState.pinSpeedDialItem(album)
            },
            onDownload = { withMediaSongs(album, songs) { loaded -> loaded.forEach(DownloadManager::download) } },
            onHideFromLibrary = onHideFromLibrary,
            onGoToArtist = ArtistState::fetchArtistData,
            additionalActions = additionalActions,
        )
    }
}

@Composable
fun ArtistContextMenu(
    artist: ArtistItem,
    expanded: Boolean,
    offset: DpOffset = DpOffset.Zero,
    onDismiss: () -> Unit,
    onHideFromLibrary: (() -> Unit)? = null,
    pinnedId: String? = null,
    isSubscribed: Boolean? = null,
    onToggleSubscription: (() -> Unit)? = null,
    additionalActions: List<PlayerMenuAction> = emptyList(),
) {
    MediaContextMenu(expanded = expanded, offset = offset, onDismiss = onDismiss) {
        val playback = LocalPlaybackController.current
        val isPinned = artist.id in HomeState.uiState.speedDialPinnedIds
        val isBlocked = BlockedContent.isArtistBlocked(artist.id)
        val playbackActions =
            MediaMenuPlaybackActions(
                artist,
                playback,
                withSongs = { action -> withMediaSongs(artist, emptyList(), action) },
            )
        artistMediaMenuModel(
            artist = artist,
            onDismiss = onDismiss,
            isPinned = isPinned,
            isBlocked = isBlocked,
            isSubscribed = isSubscribed,
            onPlay = playbackActions::play,
            onShuffle = playbackActions::shuffle,
            onShare = { AppState.copyToClipboard(ShareUrls.artist(artist.id)) },
            onPlayNext = playbackActions::playNext,
            onAddToQueue = playbackActions::addToQueue,
            onTogglePin = {
                if (isPinned) HomeState.unpinSpeedDialItem(artist.id) else HomeState.pinSpeedDialItem(artist)
            },
            onBlock = {
                if (isBlocked) {
                    BlockedContent.unblockArtist(artist.id)
                } else {
                    PlayerState.blockArtist(ArtistTiny(artist.id, artist.title, artist.thumbnail))
                }
            },
            onToggleSubscription = onToggleSubscription,
            onHideFromLibrary = onHideFromLibrary,
            additionalActions = additionalActions,
        )
    }
}

@Composable
fun PlaylistContextMenu(
    playlist: PlaylistItem,
    songs: List<SongItem> = emptyList(),
    expanded: Boolean,
    offset: DpOffset = DpOffset.Zero,
    onDismiss: () -> Unit,
    isFavorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    onSelect: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onHideFromLibrary: (() -> Unit)? = null,
    hideFromLibraryLabel: String? = null,
    pinnedId: String? = null,
    fallbackIcon: ImageVector? = null,
    additionalActions: List<PlayerMenuAction> = emptyList(),
) {
    val playlist = localizedBuiltinPlaylist(playlist)
    var showExportDialog by remember(playlist.id) { mutableStateOf(false) }
    val removal = remember(playlist.id) { DownloadRemovalState() }
    val isDownloadedCollection = playlist.id == "auto:downloaded"
    val actionSongs =
        if (isDownloadedCollection && songs.isEmpty()) LibraryState.downloadedFiles.map { it.toSongItem() } else songs
    MediaContextMenu(expanded = expanded, offset = offset, onDismiss = onDismiss) {
        val playback = LocalPlaybackController.current
        val isPinned = HomeState.uiState.speedDialPinnedIds.any { speedDialItemId(it) == speedDialItemId(playlist.id) }
        val builtin = BuiltinPlaylist.fromId(playlist.id)
        if (builtin in setOf(BuiltinPlaylist.HISTORY, BuiltinPlaylist.NEW_EPISODES, BuiltinPlaylist.SAVED_EPISODES)) {
            return@MediaContextMenu playlistMediaMenuModel(
                playlist = playlist,
                onDismiss = onDismiss,
                isPinned = isPinned,
                onTogglePin = {
                    if (isPinned) HomeState.unpinSpeedDialItem(playlist.id) else HomeState.pinSpeedDialItem(playlist)
                },
                additionalActions = additionalActions,
            )
        }
        val isAuto = playlist.id.startsWith("auto:")
        val isLocal = playlist.isLocal || isAuto
        val loadedSongs = actionSongs.takeIf { it.isNotEmpty() }
        val loadedDownloadState = loadedSongs?.let { playlistDownloadState(it, LibraryState.downloadStates) }
        val downloadKey = "playlist:${playlist.id.removePrefix("VL")}"
        val downloadedKeys =
            if (isAuto) {
                emptyList()
            } else {
                val downloadedSongIds = LibraryState.downloadStates.filterValues { it == DownloadState.DOWNLOADED }.keys
                remember(downloadedSongIds) {
                    GlobalContext
                        .get()
                        .get<MusicDatabase>()
                        .dao()
                        .downloadedCollectionKeys(downloadedSongIds.toList())
                }.collectAsState(initial = if (playlist.isDownloaded) listOf(downloadKey) else emptyList()).value
            }
        val isDownloaded =
            if (isAuto) {
                loadedDownloadState == DownloadState.DOWNLOADED || (loadedSongs == null && playlist.isDownloaded)
            } else {
                downloadKey in downloadedKeys
            }
        val downloadState =
            when {
                isDownloaded -> MediaDownloadState.DOWNLOADED
                loadedDownloadState == DownloadState.DOWNLOADING -> MediaDownloadState.DOWNLOADING
                else -> MediaDownloadState.NOT_DOWNLOADED
            }
        val downloadProgress =
            loadedSongs?.let { playlistDownloadProgress(it, LibraryState.downloadStates, LibraryState.downloadProgress) } ?: 0
        val playbackActions =
            MediaMenuPlaybackActions(
                playlist,
                playback,
                withSongs = { action -> withMediaSongs(playlist, actionSongs, action) },
            )
        playlistMediaMenuModel(
            playlist = playlist,
            isFavorite = isFavorite,
            onFavorite = onFavorite,
            onDismiss = onDismiss,
            isPinned = isPinned,
            fallbackIcon = fallbackIcon,
            downloadState = downloadState,
            downloadProgress = downloadProgress,
            onPlay = playbackActions::play,
            onShuffle = playbackActions::shuffle,
            onShare =
                if (isLocal) {
                    null
                } else {
                    { AppState.copyToClipboard(ShareUrls.playlist(playlist.id)) }
                },
            onPlayNext = playbackActions::playNext,
            onAddToQueue = playbackActions::addToQueue,
            onTogglePin = {
                if (isPinned) HomeState.unpinSpeedDialItem(playlist.id) else HomeState.pinSpeedDialItem(playlist)
            },
            onDownload = {
                withMediaSongs(playlist, actionSongs) { playlistSongs ->
                    if (downloadState == MediaDownloadState.DOWNLOADED) {
                        if (isDownloadedCollection) {
                            removal.request(playlistSongs.map(SongItem::id))
                        } else {
                            DownloadManager.removePlaylistDownloads(playlist.id, playlistSongs)
                        }
                    } else {
                        DownloadManager.downloadPlaylist(playlist.id, playlistSongs)
                    }
                }
            },
            onHideFromLibrary = onHideFromLibrary,
            hideFromLibraryLabel = hideFromLibraryLabel,
            onExport = { showExportDialog = true },
            onSelect = onSelect,
            onRename = onRename,
            onDelete = onDelete,
            additionalActions = additionalActions,
        )
    }
    PopupContent(removal.pendingIds) { ids ->
        AlertDialog(
            onDismissRequest = removal::dismiss,
            title = { Text(pluralStringResource(Res.plurals.stored_remove_selected_title, ids.size, ids.size)) },
            text = { Text(stringResource(Res.string.stored_remove_selected_message)) },
            confirmButton = {
                TextButton(
                    onClick = { removal.confirm(DownloadManager::deleteDownloads) },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = removal::dismiss, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
    if (showExportDialog) {
        var format by remember { mutableStateOf(CollectionExportFormat.CSV) }
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(Res.string.export_collection, playlist.title)) },
            text = {
                Material3MenuGroup(
                    items =
                        CollectionExportFormat.entries.map { option ->
                            Material3MenuItemData(
                                title = {
                                    Text(
                                        stringResource(
                                            if (option ==
                                                CollectionExportFormat.CSV
                                            ) {
                                                Res.string.export_format_csv
                                            } else {
                                                Res.string.export_format_m3u
                                            },
                                        ),
                                    )
                                },
                                description = {
                                    Text(
                                        stringResource(
                                            if (option == CollectionExportFormat.CSV) {
                                                Res.string.export_format_csv_description
                                            } else {
                                                Res.string.export_format_m3u_description
                                            },
                                        ),
                                    )
                                },
                                trailingContent = { RadioButton(selected = format == option, onClick = null) },
                                onClick = { format = option },
                            )
                        },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        exportCollection(playlist.title, format) { loadMediaSongs(playlist, actionSongs) }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.save)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExportDialog = false },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

@Composable
fun PodcastContextMenu(
    podcast: PodcastItem,
    songs: List<SongItem> = emptyList(),
    expanded: Boolean,
    offset: DpOffset = DpOffset.Zero,
    onDismiss: () -> Unit,
    onRemoveFromLibrary: (() -> Unit)? = null,
    pinnedId: String? = null,
    isSaved: Boolean? = null,
    onToggleSaved: (() -> Unit)? = null,
    additionalActions: List<PlayerMenuAction> = emptyList(),
) {
    MediaContextMenu(expanded = expanded, offset = offset, onDismiss = onDismiss) {
        val playback = LocalPlaybackController.current
        val isPinned = podcast.id in HomeState.uiState.speedDialPinnedIds
        val playbackActions =
            MediaMenuPlaybackActions(
                podcast,
                playback,
                withSongs = { action -> withMediaSongs(podcast, songs, action) },
            )
        podcastMediaMenuModel(
            podcast = podcast,
            onDismiss = onDismiss,
            isPinned = isPinned,
            isSaved = isSaved,
            onPlay = playbackActions::play,
            onShuffle = playbackActions::shuffle,
            onPlayOldestFirst = playbackActions::playOldestFirst,
            onShare = { AppState.copyToClipboard(ShareUrls.podcast(podcast.id)) },
            onPlayNext = playbackActions::playNext,
            onAddToQueue = playbackActions::addToQueue,
            onTogglePin = {
                if (isPinned) HomeState.unpinSpeedDialItem(podcast.id) else HomeState.pinSpeedDialItem(podcast)
            },
            onToggleSaved = onToggleSaved,
            onRemoveFromLibrary = onRemoveFromLibrary,
            onDownload = { withMediaSongs(podcast, songs) { loaded -> loaded.forEach(DownloadManager::download) } },
            additionalActions = additionalActions,
        )
    }
}

private fun mediaDownloadState(id: String): MediaDownloadState = mediaDownloadState(LibraryState.downloadStates[id])

private fun mediaDownloadState(state: DownloadState?): MediaDownloadState =
    when (state) {
        DownloadState.QUEUED,
        DownloadState.DOWNLOADING,
        -> MediaDownloadState.DOWNLOADING

        DownloadState.DOWNLOADED -> MediaDownloadState.DOWNLOADED

        else -> MediaDownloadState.NOT_DOWNLOADED
    }

@Composable
fun EpisodeContextMenu(
    episode: EpisodeItem,
    expanded: Boolean,
    offset: DpOffset = DpOffset.Zero,
    onDismiss: () -> Unit,
    onToggleSaved: (() -> Unit)? = null,
    onHideFromLibrary: (() -> Unit)? = null,
    additionalActions: List<PlayerMenuAction> = emptyList(),
) {
    MediaContextMenu(expanded = expanded, offset = offset, onDismiss = onDismiss) {
        val song = episode.toSongItem()
        val isGuest = PlayerState.isListenTogetherGuest()
        MediaMenuModel(
            title = episode.title,
            subtitle = episode.author?.name,
            thumbnail = episode.thumbnail,
            primaryActions =
                listOf(
                    PlayerMenuAction(
                        MetrolistIcons.Filled.PlayArrow,
                        stringResource(Res.string.play),
                        enabled =
                            !isGuest && !episode.isUnavailable,
                    ) {
                        PlayerState.playTrack(song)
                        onDismiss()
                    },
                    PlayerMenuAction(MetrolistIcons.AutoMirrored.Outlined.PlaylistAdd, stringResource(Res.string.add_to_playlist)) {
                        showAddToPlaylist(song)
                        onDismiss()
                    },
                    PlayerMenuAction(MetrolistIcons.Outlined.Share, stringResource(Res.string.share)) {
                        shareSong(song)
                        onDismiss()
                    },
                ),
            actionGroups =
                listOf(
                    if (isGuest) {
                        listOf(
                            PlayerMenuAction(
                                MetrolistIcons.AutoMirrored.Outlined.QueueMusic,
                                stringResource(Res.string.suggest_to_host),
                                enabled = !episode.isUnavailable,
                            ) {
                                suggestToHost(song)
                                onDismiss()
                            },
                        )
                    } else {
                        listOf(
                            PlayerMenuAction(
                                MetrolistIcons.AutoMirrored.Filled.PlaylistPlay,
                                stringResource(Res.string.play_next),
                                enabled = !episode.isUnavailable,
                            ) {
                                PlayerState.playNext(song)
                                onDismiss()
                            },
                            PlayerMenuAction(
                                MetrolistIcons.AutoMirrored.Outlined.QueueMusic,
                                stringResource(Res.string.add_to_queue),
                                enabled = !episode.isUnavailable,
                            ) {
                                PlayerState.addToQueue(song)
                                onDismiss()
                            },
                        )
                    },
                    listOfNotNull(
                        onToggleSaved?.let { action ->
                            PlayerMenuAction(
                                MetrolistIcons.Outlined.LibraryAdd,
                                if (episode.saved) {
                                    stringResource(
                                        Res.string.remove_from_saved_episodes,
                                    )
                                } else {
                                    stringResource(Res.string.save_episode)
                                },
                            ) {
                                action()
                                onDismiss()
                            }
                        },
                        speedDialAction(
                            item = episode,
                            pinLabel = stringResource(Res.string.pin_to_speed_dial),
                            unpinLabel = stringResource(Res.string.unpin_from_speed_dial),
                            onDismiss = onDismiss,
                        ),
                        onHideFromLibrary?.let { action -> hideFromLibraryAction(action, onDismiss) },
                        songDownloadAction(song, onDismiss),
                    ),
                    listOfNotNull(
                        episode.podcastId?.let { podcastId ->
                            PlayerMenuAction(MetrolistIcons.Outlined.Podcasts, stringResource(Res.string.view_podcast)) {
                                PodcastState.fetch(podcastId)
                                onDismiss()
                            }
                        },
                    ),
                    additionalActions,
                ),
        )
    }
}

@Composable
fun LibraryItemContextMenu(
    playlist: PlaylistItem?,
    expanded: Boolean,
    offset: DpOffset,
    onDismiss: () -> Unit,
    onHide: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    pinnedId: String? = null,
) {
    CursorDropdownMenu(expanded, offset, onDismiss) {
        val actions =
            listOfNotNull(
                onRename?.takeIf { playlist?.isEditable == true }?.let { action ->
                    PlayerMenuAction(MetrolistIcons.Outlined.Edit, stringResource(Res.string.rename)) {
                        action()
                        onDismiss()
                    }
                },
                onDelete?.takeIf { playlist?.isEditable == true }?.let { action ->
                    PlayerMenuAction(MetrolistIcons.Outlined.Delete, stringResource(Res.string.delete_playlist)) {
                        action()
                        onDismiss()
                    }
                },
                hideFromLibraryAction(onHide, onDismiss),
            )
        Column(Modifier.width(300.dp).padding(horizontal = 8.dp)) {
            ExpressiveMenuActionGroup(actions, 0, 1)
        }
    }
}

private fun showAddToPlaylist(song: SongItem) {
    PlayerState.showAddToPlaylistSong = song
}

private fun shareSong(song: SongItem) {
    AppState.copyToClipboard(ShareUrls.song(song.id))
}

private fun suggestToHost(song: SongItem) {
    ListenTogetherState.suggestTrack(song)
}

@Composable
private fun speedDialAction(
    item: YTItem,
    pinLabel: String,
    unpinLabel: String,
    onDismiss: () -> Unit,
): PlayerMenuAction {
    val isPinned = item.id in HomeState.uiState.speedDialPinnedIds
    return PlayerMenuAction(
        if (isPinned) MetrolistIcons.Outlined.KeepOff else MetrolistIcons.Filled.PushPin,
        if (isPinned) unpinLabel else pinLabel,
    ) {
        if (isPinned) HomeState.unpinSpeedDialItem(item.id) else HomeState.pinSpeedDialItem(item)
        onDismiss()
    }
}

@Composable
private fun hideFromLibraryAction(
    action: () -> Unit,
    onDismiss: () -> Unit,
) = PlayerMenuAction(MetrolistIcons.Filled.VisibilityOff, stringResource(Res.string.hide_from_library)) {
    action()
    onDismiss()
}

@Composable
internal fun songDownloadAction(
    song: SongItem,
    onDismiss: () -> Unit,
): PlayerMenuAction {
    val state = LibraryState.downloadStates[song.id]
    return when (state) {
        DownloadState.DOWNLOADING -> {
            PlayerMenuAction(
                MetrolistIcons.Outlined.Download,
                stringResource(
                    Res.string.downloading_percent,
                    ((LibraryState.downloadProgress[song.id] ?: 0f) * 100).toInt(),
                    stringResource(Res.string.percent_symbol),
                ),
                enabled = false,
            ) {}
        }

        DownloadState.DOWNLOADED -> {
            PlayerMenuAction(
                MetrolistIcons.Outlined.OfflinePin,
                stringResource(Res.string.remove_download),
                stringResource(Res.string.delete_downloaded_audio),
            ) {
                DownloadManager.deleteDownload(song.id)
                onDismiss()
            }
        }

        else -> {
            PlayerMenuAction(
                MetrolistIcons.Outlined.Download,
                stringResource(Res.string.action_download),
                stringResource(Res.string.make_available_offline),
                enabled = !song.isUnavailable,
            ) {
                DownloadManager.download(song)
                onDismiss()
            }
        }
    }
}

private fun withMediaSongs(
    item: YTItem,
    existingSongs: List<SongItem>,
    action: (List<SongItem>) -> Unit,
) {
    if (existingSongs.isNotEmpty()) {
        action(existingSongs)
        return
    }
    AppState.scope.launch {
        try {
            loadMediaSongs(item, existingSongs).takeIf { it.isNotEmpty() }?.let(action)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AppState.snackbarHostState.showSnackbar(getString(Res.string.error_unknown))
        }
    }
}

private suspend fun loadMediaSongs(
    item: YTItem,
    existingSongs: List<SongItem>,
): List<SongItem> {
    if (existingSongs.isNotEmpty()) return existingSongs
    return withContext(Dispatchers.IO) {
        if (item is PlaylistItem && item.id.startsWith("auto:")) {
            val dao = GlobalContext.get().get<MusicDatabase>().dao()
            when (item.id.removePrefix("auto:")) {
                "liked" -> {
                    SongLikeState.awaitCompleteLikes().orEmpty()
                }

                "downloaded" -> {
                    LibraryState.downloadedFiles.map { it.toSongItem() }
                }

                "top100" -> {
                    dao.mostPlayedSongs(0L, limit = 100).first().map { it.localSongItem() }
                }

                "uploaded" -> {
                    GlobalYouTubeRepository.instance.getLibraryUploadSongs(limit = 500)
                }

                "cached" -> {
                    SongCacheManager.getCachedSongs().map { entry ->
                        SongItem(
                            id = entry.songId,
                            title = entry.title,
                            artists = listOf(ArtistTiny(null, entry.artist)),
                            thumbnail = null,
                            album = null,
                        )
                    }
                }

                else -> {
                    emptyList()
                }
            }
        } else if (item is PlaylistItem) {
            val database = GlobalContext.get().get<MusicDatabase>()
            database.refreshHistoryPlaylist(item.id)
            val dao = database.dao()
            val storedPlaylist = dao.findPlaylistEntity(item.id)
            if (item.isLocal || storedPlaylist?.isLocal == true || (storedPlaylist != null && storedPlaylist.browseId == null)) {
                storedPlaylist
                    ?.let { playlist ->
                        dao.playlistSongs(playlist.id).first().map { entry ->
                            entry.song.localSongItem().copy(playlistSetVideoId = entry.map.setVideoId)
                        }
                    }.orEmpty()
            } else {
                BlockedContent.filterSongs(
                    GlobalContext
                        .get()
                        .get<MusicDatabase>()
                        .loadAndCachePlaylist(GlobalYouTubeRepository.instance, item.id)
                        .playlistSongs(),
                )
            }
        } else {
            loadMediaItemSongs(item)
        }
    }
}
