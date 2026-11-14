package com.metrolist.shared.ui.menu

import androidx.compose.runtime.Composable
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.add_to_playlist
import com.metrolist.shared.generated.resources.dislike_all
import com.metrolist.shared.generated.resources.like_all
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.remove_from_playlist
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.selection.SelectionActions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun songSelectionActions(
    songs: List<SongItem>,
    isGuest: Boolean = false,
    downloadState: MediaDownloadState = MediaDownloadState.NOT_DOWNLOADED,
    onPlay: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    removeLabel: String? = null,
    onLikeAll: (() -> Unit)? = null,
    additionalActions: List<PlayerMenuAction> = emptyList(),
): SelectionActions<PlayerMenuAction> {
    val onDismiss = {}
    val playLabel = stringResource(Res.string.play)
    val shuffleLabel = stringResource(Res.string.shuffle)
    val addToPlaylistLabel = stringResource(Res.string.add_to_playlist)
    val likeAllLabel = stringResource(Res.string.like_all)
    val dislikeAllLabel = stringResource(Res.string.dislike_all)
    val removeFromPlaylistLabel = stringResource(Res.string.remove_from_playlist)
    val allLiked = songs.isNotEmpty() && songs.all { SongLikeState.isLiked(it.id) }

    val playback =
        onPlay?.takeUnless { isGuest }?.let {
            PlayerMenuAction(MetrolistIcons.Rounded.PlayArrow, playLabel, prominent = true, onClick = it)
        }
    val queue = if (isGuest) emptyList() else queueActions(onDismiss, onPlayNext, onAddToQueue)
    return SelectionActions(
        primary =
            listOfNotNull(playback) + queue.take(1) +
                listOfNotNull(
                    onAddToPlaylist?.let {
                        PlayerMenuAction(MetrolistIcons.AutoMirrored.Outlined.PlaylistAdd, addToPlaylistLabel, onClick = it)
                    },
                    downloadAction(downloadState, 0, false, onDismiss, onDownload),
                    onRemove?.let {
                        PlayerMenuAction(MetrolistIcons.Outlined.Delete, removeLabel ?: removeFromPlaylistLabel, onClick = it)
                    },
                ),
        overflow =
            queue.drop(1) +
                listOfNotNull(
                    onShuffle?.takeUnless { isGuest }?.let {
                        PlayerMenuAction(MetrolistIcons.Rounded.Shuffle, shuffleLabel, onClick = it)
                    },
                    onLikeAll?.let {
                        PlayerMenuAction(
                            icon = if (allLiked) MetrolistIcons.Filled.Favorite else MetrolistIcons.Outlined.FavoriteBorder,
                            title = if (allLiked) dislikeAllLabel else likeAllLabel,
                            selected = allLiked,
                            onClick = it,
                        )
                    },
                ) + additionalActions,
    )
}
