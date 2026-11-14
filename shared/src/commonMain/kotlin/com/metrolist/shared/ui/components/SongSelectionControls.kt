package com.metrolist.shared.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.menu.MediaDownloadState
import com.metrolist.shared.ui.menu.SongSelectionMenuSheet
import com.metrolist.shared.ui.menu.songSelectionMenuModel
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.selection.SelectionTopBar

/**
 * Selection top bar shown while selecting songs: close (X), "N selected", select-all checkbox and a
 * three-dots button that opens the dedicated selection menu ([songSelectionMenuModel]).
 */
@Composable
fun SongSelectionControls(
    selectedCount: Int,
    allSelected: Boolean,
    onDone: () -> Unit,
    onSelectAll: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownload: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemove: (() -> Unit)? = null,
    selectedSongs: List<SongItem> = emptyList(),
    onPlay: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    downloadState: MediaDownloadState = MediaDownloadState.NOT_DOWNLOADED,
    isGuest: Boolean = false,
    removeLabel: String? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Back leaves selection mode first instead of leaving the screen.
    NavigationBackHandler(enabled = true, onBack = onDone)
    SelectionTopBar(
        count = selectedCount,
        allSelected = allSelected,
        onClose = onDone,
        onSelectAll = onSelectAll,
        onMore = { menuOpen = true },
    )
    if (menuOpen && selectedSongs.isNotEmpty()) {
        val dismiss = { menuOpen = false }
        SongSelectionMenuSheet(
            model =
                songSelectionMenuModel(
                    songs = selectedSongs,
                    onDismiss = dismiss,
                    isGuest = isGuest,
                    downloadState = downloadState,
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onAddToPlaylist = {
                        dismiss()
                        onAddToPlaylist()
                    },
                    onDownload = onDownload,
                    onRemove = onRemove,
                    removeLabel = removeLabel,
                    onLikeAll = {
                        val allLiked = selectedSongs.all { SongLikeState.isLiked(it.id) }
                        if (allLiked) {
                            SongLikeState.removeLikes(selectedSongs.map { it.id })
                        } else {
                            SongLikeState.addLikes(selectedSongs)
                        }
                    },
                ),
            onDismiss = dismiss,
        )
    }
}
