@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.playback.ListenTogetherState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.state.playback.SuggestedSongKeys
import com.metrolist.desktop.storage.DownloadManager
import com.metrolist.desktop.storage.DownloadState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.queue
import com.metrolist.shared.generated.resources.remove_from_queue
import com.metrolist.shared.generated.resources.removed_from_queue
import com.metrolist.shared.generated.resources.undo
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.previewAspectRatio
import com.metrolist.shared.playback.QueueRemoval
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.player.PlaylistInfo
import com.metrolist.shared.ui.player.QueueEvent
import com.metrolist.shared.ui.player.QueueScreen
import com.metrolist.shared.ui.player.QueueScreenState
import com.metrolist.shared.ui.player.QueueTrack
import com.metrolist.shared.utils.formatQueueDuration
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DesktopQueueContent(
    onDismiss: () -> Unit,
    colorScheme: ColorScheme = MaterialTheme.colorScheme,
    modifier: Modifier = Modifier,
) {
    val queueState by PlayerState.queue.state.collectAsState()
    val downloadStates by DownloadManager.downloadStates.collectAsState()
    val isListenTogetherGuest = ListenTogetherState.isListenTogetherGuest()
    val relatedSongs = PlayerState.relatedSongs
    val suggestionKeys = remember { SuggestedSongKeys() }
    val keyedSuggestions = remember(relatedSongs) { suggestionKeys.update(relatedSongs) }
    val isQueueLocked = Settings.shared.isQueueLocked
    var selectedEntryKeys by remember { mutableStateOf(setOf<Long>()) }
    var menuSong by remember { mutableStateOf<Pair<Long, SongItem>?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
    var pendingRemoval by remember { mutableStateOf<QueueRemoval?>(null) }
    var showSleepDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(queueState.entries, keyedSuggestions.keys) {
        val available = queueState.entries.mapTo(mutableSetOf()) { it.key } + keyedSuggestions.keys
        selectedEntryKeys = selectedEntryKeys.intersect(available)
    }

    val videoId = PlayerState.currentTrack?.id
    LaunchedEffect(videoId) {
        if (videoId != null) PlayerState.fetchRelated(videoId)
    }

    LaunchedEffect(pendingRemoval) {
        val removal = pendingRemoval ?: return@LaunchedEffect
        val count = removal.items.size
        val result =
            snackbarHostState.showSnackbar(
                message = getPluralString(Res.plurals.removed_from_queue, count, count),
                actionLabel = getString(Res.string.undo),
                duration = SnackbarDuration.Short,
            )
        if (result == SnackbarResult.ActionPerformed) PlayerState.restoreQueueItems(removal)
        pendingRemoval = null
    }

    fun SongItem.toQueueTrack(
        entryKey: Long,
        queueIndex: Int,
        isSuggested: Boolean,
    ) = QueueTrack(
        id = id,
        title = title,
        artist = artists.joinToString(", ") { it.displayName },
        thumbnailUrl = thumbnail,
        duration = duration?.times(1000L),
        isExplicit = isExplicit,
        isLiked = PlayerState.isLiked(id),
        isSaved = false,
        isDownloaded = downloadStates[id] == DownloadState.DOWNLOADED,
        isSuggested = isSuggested,
        canRemove = !isSuggested && queueIndex > queueState.currentIndex && !isQueueLocked,
        queueIndex = queueIndex,
        entryKey = entryKey,
        uniqueKey = if (isSuggested) "rec_$entryKey" else entryKey.toString(),
        thumbnailAspectRatio = previewAspectRatio,
    )

    val tracks =
        queueState.entries.mapIndexed { index, entry ->
            SongMetadataState
                .apply(
                    entry.song,
                ).toQueueTrack(entry.key, index, isSuggested = false)
        }
    val suggestedTracks =
        keyedSuggestions.map { (key, song) ->
            SongMetadataState.apply(song).toQueueTrack(key, -1, isSuggested = true)
        }
    val queueSongs = queueState.entries.map { SongMetadataState.apply(it.song) }
    val totalDuration = queueSongs.sumOf { it.duration ?: 0L }.takeIf { queueSongs.all { song -> song.duration != null } }

    fun songFor(entryKey: Long): SongItem? =
        if (entryKey < 0) {
            keyedSuggestions[entryKey]
        } else {
            queueState.entries.firstOrNull { it.key == entryKey }?.song
        }

    fun selectedSongs() =
        queueState.entries.filter { it.key in selectedEntryKeys }.map { it.song } +
            suggestedTracks.filter { it.entryKey in selectedEntryKeys }.mapNotNull { songFor(it.entryKey) }

    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(16.dp),
        color = colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            QueueScreen(
                state =
                    QueueScreenState(
                        playlist =
                            PlaylistInfo(
                                name = stringResource(Res.string.queue),
                                trackCount = queueSongs.size,
                                totalDuration = formatQueueDuration(totalDuration?.times(1000L)),
                            ),
                        tracks = tracks,
                        suggestedTracks = suggestedTracks,
                        isShuffleOn = PlayerState.shuffleEnabled,
                        repeatMode =
                            when (PlayerState.repeatMode) {
                                RepeatMode.OFF -> 0
                                RepeatMode.ONE -> 1
                                RepeatMode.ALL -> 2
                            },
                        isQueueLoading = PlayerState.isRelatedLoading && queueSongs.isEmpty(),
                        selectedEntryKeys = selectedEntryKeys,
                        currentEntryKey = queueState.entries.getOrNull(queueState.currentIndex)?.key,
                        isPlaying = PlayerState.isPlaying,
                    ),
                isListenTogetherGuest = isListenTogetherGuest,
                isDesktopPanel = true,
                onDismiss = onDismiss,
                backHandlerEnabled = false,
                reorderEnabled = !isQueueLocked,
                snackbarHostState = snackbarHostState,
                onEvent = { event ->
                    when (event) {
                        is QueueEvent.PlayTrack -> {
                            if (event.entryKey < 0) {
                                songFor(event.entryKey)?.let { PlayerState.playTrack(it, relatedSongs) }
                            } else {
                                PlayerState.playQueueOccurrence(event.entryKey)
                            }
                        }

                        is QueueEvent.OpenTrackMenu -> {
                            menuSong = songFor(event.entryKey)?.let { event.entryKey to it }
                            menuOffset = event.offset
                            menuExpanded = menuSong != null
                        }

                        is QueueEvent.AddTrackToPlaylist -> {
                            songFor(event.entryKey)?.let { PlayerState.showAddToPlaylistSongs = listOf(it) }
                        }

                        is QueueEvent.DownloadTrack -> {
                            songFor(event.entryKey)?.let(DownloadManager::download)
                        }

                        is QueueEvent.ToggleTrackLike -> {
                            songFor(event.entryKey)?.let(SongLikeState::toggleLike)
                        }

                        is QueueEvent.SelectChip -> {}

                        is QueueEvent.SelectTrack -> {
                            selectedEntryKeys = selectedEntryKeys + event.entryKey
                        }

                        is QueueEvent.DeselectTrack -> {
                            selectedEntryKeys = selectedEntryKeys - event.entryKey
                        }

                        is QueueEvent.ReorderTrack -> {
                            if (event.fromEntryKey >= 0 && event.toEntryKey >= 0) {
                                PlayerState.moveQueueItem(event.fromEntryKey, event.toEntryKey, selectedEntryKeys)
                            }
                        }

                        QueueEvent.CommitReorder, QueueEvent.CollapseToolbar -> {}

                        is QueueEvent.RemoveTrack -> {
                            PlayerState.removeQueueOccurrence(event.entryKey)?.let { pendingRemoval = it }
                        }

                        QueueEvent.SelectAll -> {
                            selectedEntryKeys = queueState.entries.mapTo(mutableSetOf()) { it.key }
                        }

                        QueueEvent.AddSelectedToPlaylist -> {
                            PlayerState.showAddToPlaylistSongs = selectedSongs()
                        }

                        QueueEvent.ClearSelection -> {
                            selectedEntryKeys = emptySet()
                        }

                        QueueEvent.DeleteSelected -> {
                            if (!isQueueLocked) {
                                val indices =
                                    queueState.entries
                                        .withIndex()
                                        .filter { (index, entry) -> entry.key in selectedEntryKeys && index > queueState.currentIndex }
                                        .mapTo(mutableSetOf()) { it.index }
                                PlayerState.removeQueueItems(indices)?.let { pendingRemoval = it }
                            }
                            selectedEntryKeys = emptySet()
                        }

                        QueueEvent.DownloadSelected -> {
                            DownloadManager.downloadAll(selectedSongs())
                            selectedEntryKeys = emptySet()
                        }

                        QueueEvent.LikeSelected -> {
                            SongLikeState.addLikes(selectedSongs())
                            selectedEntryKeys = emptySet()
                        }

                        QueueEvent.ToggleShuffle -> {
                            PlayerState.toggleShuffle()
                        }

                        QueueEvent.CycleRepeat -> {
                            PlayerState.cycleRepeatMode()
                        }

                        QueueEvent.ToggleSleepTimer -> {
                            showSleepDialog = true
                        }

                        QueueEvent.TogglePlayPause -> {
                            PlayerState.togglePlay()
                        }

                        QueueEvent.Previous -> {
                            PlayerState.skipPrevious()
                        }

                        QueueEvent.Next -> {
                            PlayerState.skipNext()
                        }

                        QueueEvent.Dismiss -> {
                            onDismiss()
                        }
                    }
                },
            )

            menuSong?.let { (entryKey, song) ->
                SongContextMenu(
                    song = song,
                    expanded = menuExpanded,
                    offset = menuOffset,
                    onRemoveFromPlaylist =
                        if (!isListenTogetherGuest && tracks.any { it.entryKey == entryKey && it.canRemove }) {
                            { PlayerState.removeQueueOccurrence(entryKey)?.let { pendingRemoval = it } }
                        } else {
                            null
                        },
                    removeLabel = stringResource(Res.string.remove_from_queue),
                    onDismiss = { menuExpanded = false },
                )
            }
        }
    }

    PopupVisibility(visible = showSleepDialog) {
        SleepTimerDialog(onDismiss = { showSleepDialog = false })
    }
}
