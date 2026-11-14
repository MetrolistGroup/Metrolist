package com.metrolist.android.ui.menu

import android.widget.Toast
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.metrolist.android.LocalPlaylistAdder
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.home_load_item_failed
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.menu.MediaDownloadState
import com.metrolist.shared.ui.menu.MediaMenuSheetContent
import com.metrolist.shared.ui.menu.albumMediaMenuModel
import com.metrolist.shared.ui.menu.loadMediaItemSongs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumMenu(
    album: AlbumItem,
    onDismiss: () -> Unit,
    isFavorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onGoToArtist: ((String) -> Unit)? = null,
    onHideFromLibrary: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    loadSongs: suspend () -> List<SongItem> = { loadMediaItemSongs(album) },
) {
    var loadingPlaylistSongs by remember(album.id) { mutableStateOf(false) }
    val addToPlaylist = LocalPlaylistAdder.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val download = rememberMediaDownloadAction(album, onDismiss, onDownload, loadSongs)
    val pin = rememberSpeedDialToggle(album)
    val model =
        albumMediaMenuModel(
            album = album,
            isFavorite = isFavorite,
            onFavorite = onFavorite,
            onDismiss = { if (!loadingPlaylistSongs && !download.isConfirming()) onDismiss() },
            isPinned = pin.isPinned,
            downloadState = if (download.isDownloaded) MediaDownloadState.DOWNLOADED else MediaDownloadState.NOT_DOWNLOADED,
            onPlay = onPlay,
            onShuffle = onShuffle,
            onShare = onShare,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onAddToPlaylist =
                onAddToPlaylist ?: {
                    loadingPlaylistSongs = true
                    scope.launch {
                        try {
                            val songs =
                                try {
                                    loadSongs()
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            if (songs.isEmpty()) {
                                Toast
                                    .makeText(
                                        context,
                                        getString(Res.string.home_load_item_failed, album.title),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            } else {
                                addToPlaylist(songs)
                                onDismiss()
                            }
                        } finally {
                            loadingPlaylistSongs = false
                        }
                    }
                    Unit
                },
            onTogglePin = pin.toggle,
            onDownload = download.invoke,
            onHideFromLibrary = onHideFromLibrary,
            onExport = onExport,
            onGoToArtist = onGoToArtist,
        )
    MediaMenuBottomSheet(model = model, onDismissRequest = onDismiss, canDismiss = { !loadingPlaylistSongs && !download.isConfirming() }) {
        MediaMenuSheetContent(model)
    }
    download.confirmationDialog(onDismiss)
}
