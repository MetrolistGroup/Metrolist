package com.metrolist.android.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.components.localizedBuiltinPlaylist
import com.metrolist.shared.ui.menu.MediaDownloadState
import com.metrolist.shared.ui.menu.MediaMenuSheetContent
import com.metrolist.shared.ui.menu.loadMediaItemSongs
import com.metrolist.shared.ui.menu.playlistMediaMenuModel

@Composable
fun PlaylistMenu(
    playlist: PlaylistItem,
    onDismiss: () -> Unit,
    isFavorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onHideFromLibrary: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    fallbackIcon: ImageVector? = null,
    loadSongs: suspend () -> List<SongItem> = { loadMediaItemSongs(playlist) },
    showSpeedDialAction: Boolean = true,
    hideFromLibraryLabel: String? = null,
) {
    val playlist = localizedBuiltinPlaylist(playlist)
    val download = rememberMediaDownloadAction(playlist, onDismiss, onDownload, loadSongs)
    val pin = rememberSpeedDialToggle(playlist)
    val model =
        playlistMediaMenuModel(
            playlist = playlist,
            isFavorite = isFavorite,
            onFavorite = onFavorite,
            onDismiss = { if (!download.isConfirming()) onDismiss() },
            fallbackIcon = fallbackIcon,
            isPinned = pin.isPinned,
            showPinAction = showSpeedDialAction,
            downloadState = if (download.isDownloaded) MediaDownloadState.DOWNLOADED else MediaDownloadState.NOT_DOWNLOADED,
            onPlay = onPlay,
            onShuffle = onShuffle,
            onShare = onShare.takeUnless { playlist.isLocal || playlist.id.startsWith("auto:") },
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onTogglePin = pin.toggle,
            onDownload = download.invoke,
            onHideFromLibrary = onHideFromLibrary,
            hideFromLibraryLabel = hideFromLibraryLabel,
            onExport = onExport,
            onRename = onRename,
            onDelete = onDelete,
        )
    MediaMenuBottomSheet(model = model, onDismissRequest = onDismiss, canDismiss = { !download.isConfirming() }) {
        MediaMenuSheetContent(model)
    }
    download.confirmationDialog(onDismiss)
}
