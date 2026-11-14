/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.menu

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.metrolist.android.ui.component.Material3CardItem
import com.metrolist.android.ui.component.Material3CardsGroup
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.rememberModalSheetState
import com.metrolist.shared.ui.menu.QueueMenuSheetContent
import com.metrolist.shared.ui.menu.queueMediaMenuModel
import com.metrolist.shared.ui.menu.rememberDeleteUploadAction
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueMenu(
    song: SongItem,
    songTitle: String,
    artistName: String,
    artists: List<ArtistTiny>,
    albumName: String?,
    duration: String?,
    thumbnailUrl: String?,
    isLiked: Boolean,
    onDismiss: () -> Unit,
    onStartRadio: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: ((String) -> Unit)? = null,
    onLike: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onAltShare: (() -> Unit)? = null,
    onRefetch: (() -> Unit)? = null,
    onShowInfo: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null,
    onNavigateToListenTogether: (() -> Unit)? = null,
    isDisliked: Boolean = false,
    onDislike: (() -> Unit)? = null,
) {
    var showDetails by remember(song.id) { mutableStateOf(false) }
    val isTv = LocalIsTv.current
    val menuSheetState = rememberModalSheetState()
    val scope = rememberCoroutineScope()
    val showSongDetails: () -> Unit = {
        if (onShowInfo != null) {
            onShowInfo()
            onDismiss()
        } else {
            scope.launch { if (isTv || menuSheetState.hide()) showDetails = true }
        }
    }
    if (showDetails) {
        SongDetailsSheet(song = song, onDismiss = onDismiss)
        return
    }
    val model =
        queueMediaMenuModel(
            songTitle = songTitle,
            deleteUploadAction = rememberDeleteUploadAction(song, koinInject<MusicDatabase>(), onDismiss),
            artistName = artistName,
            artists = artists,
            albumName = albumName,
            thumbnailUrl = thumbnailUrl,
            isDisliked = isDisliked,
            onDismiss = onDismiss,
            onStartRadio = onStartRadio,
            onAddToPlaylist = onAddToPlaylist,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onDownload = onDownload,
            onGoToAlbum = onGoToAlbum,
            onGoToArtist = onGoToArtist,
            onShare = onShare,
            onAltShare = onAltShare,
            onRefetch = onRefetch,
            onShowInfo = showSongDetails,
            onRemoveFromQueue = onRemoveFromQueue,
            onNavigateToListenTogether = onNavigateToListenTogether,
            onDislike = onDislike,
        )
    MediaMenuBottomSheet(onDismissRequest = onDismiss, scrollContent = false, sheetState = menuSheetState) {
        QueueMenuSheetContent(model, duration, isLiked, onLike) { actions ->
            Material3CardsGroup(
                items =
                    actions.map { action ->
                        Material3CardItem(
                            icon = action.icon,
                            title = { Text(action.title) },
                            description = action.description?.let { description -> { Text(description) } },
                            enabled = action.enabled,
                            onClick = action.onClick,
                        )
                    },
            )
        }
    }
}
