@file:Suppress("FunctionName")

package com.metrolist.shared.ui.mobile

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.StoredHistoryRow
import com.metrolist.shared.db.deleteHistoryEntries
import com.metrolist.shared.db.historyChanges
import com.metrolist.shared.db.historyHasRemoteTokens
import com.metrolist.shared.db.historySong
import com.metrolist.shared.db.syncYouTubeHistory
import com.metrolist.shared.db.updateHistorySong
import com.metrolist.shared.db.visibleHistoryIds
import com.metrolist.shared.db.visibleHistoryPage
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.delete_failed
import com.metrolist.shared.generated.resources.error
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.model.HistoryEntryUi
import com.metrolist.shared.model.HistorySortMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.defaultDirection
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.menu.BuiltinPlaylistMenuButton
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.screens.HistoryContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MobileHistoryScreen(
    database: MusicDatabase,
    playbackController: PlaybackController,
    onBack: () -> Unit,
    formatMonth: (Long) -> String,
    onSongMenu: (SongItem) -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
) {
    val scope = rememberCoroutineScope()
    val loader =
        remember(database) {
            MobileHistoryLoader { limit, offset, query, mode, direction ->
                database.visibleHistoryPage(limit, offset, query, mode, direction, catalogArtistIds = true, preserveUnicodeOrder = true)
            }
        }
    val rows = loader.rows
    var entries by remember(database) { mutableStateOf(emptyList<HistoryEntryUi>()) }
    var displayedEntries by remember(database) { mutableStateOf(emptyList<HistoryEntryUi>()) }
    val hasMore = loader.hasMore
    var query by remember { mutableStateOf("") }
    var sortDirection by remember { mutableStateOf(SortDirection.DESCENDING) }
    var sortMode by remember { mutableStateOf(HistorySortMode.NEWEST) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var selectedHasRemoteEntries by remember(database) { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var failedDeletion by remember { mutableStateOf<Pair<Set<String>, Boolean>?>(null) }
    var failedUpdate by remember { mutableStateOf<(() -> Unit)?>(null) }
    var refreshKey by remember(database) { mutableIntStateOf(0) }
    LaunchedEffect(
        query,
        sortMode,
        sortDirection,
        database,
        refreshKey,
        Settings.shared.innerTubeCookie,
        Settings.shared.dataSyncId,
        Settings.shared.innerTubeAuthUser,
        Settings.shared.ytmSync,
        Settings.shared.pauseListenHistory,
    ) {
        database.historyChanges().collectLatest { loader.refresh(query, sortMode, sortDirection) }
    }
    LaunchedEffect(
        database,
        refreshKey,
        Settings.shared.innerTubeCookie,
        Settings.shared.dataSyncId,
        Settings.shared.innerTubeAuthUser,
        Settings.shared.ytmSync,
        Settings.shared.pauseListenHistory,
    ) {
        try {
            database.syncYouTubeHistory()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }
    LaunchedEffect(rows, query, sortMode, sortDirection) {
        val result =
            withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                val ui =
                    rows.map {
                        context.ensureActive()
                        it.toHistoryEntry()
                    }
                ui
            }
        entries = result
        displayedEntries = result
        if (selectedIds.isNotEmpty()) {
            selectedIds =
                selectedIds.intersect(
                    database
                        .visibleHistoryIds(
                            query,
                            catalogArtistIds = true,
                            preserveUnicodeOrder = true,
                        ).mapTo(mutableSetOf(), Long::toString),
                )
        }
    }

    LaunchedEffect(selectedIds, rows) {
        selectedHasRemoteEntries = false
        try {
            selectedHasRemoteEntries = database.historyHasRemoteTokens(selectedIds.mapTo(mutableSetOf(), String::toLong))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            selectedHasRemoteEntries = false
        }
    }

    fun deleteEntries(
        ids: Set<String>,
        alsoRemote: Boolean,
    ) {
        if (ids.isEmpty() || isDeleting) return
        isDeleting = true
        failedDeletion = null
        failedUpdate = null
        scope.launch {
            try {
                database.deleteHistoryEntries(
                    ids.mapTo(mutableSetOf(), String::toLong),
                    removeRemote =
                        if (alsoRemote) {
                            { tokens -> GlobalYouTubeRepository.instance.removeHistoryItems(tokens) }
                        } else {
                            null
                        },
                )
                selectedIds = emptySet()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failedDeletion = ids to alsoRemote
            } finally {
                isDeleting = false
            }
        }
    }

    fun updateEntry(
        songId: String,
        title: String,
        artists: String,
        album: String?,
    ) {
        failedUpdate = null
        failedDeletion = null
        scope.launch {
            try {
                database.updateHistorySong(songId, title, artists, album)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failedUpdate = { updateEntry(songId, title, artists, album) }
            }
        }
    }

    HistoryContent(
        headerActions = { BuiltinPlaylistMenuButton(BuiltinPlaylist.HISTORY, database) },
        entries = entries,
        displayedEntries = displayedEntries,
        query = query,
        sortMode = sortMode,
        sortDirection = sortDirection,
        onSortDirectionChange = { sortDirection = it },
        selectedIds = selectedIds,
        isLoading = loader.isLoading,
        onLoadMore = if (hasMore) ({ scope.launch { loader.loadMore() } }) else null,
        isDeleting = isDeleting,
        error =
            when {
                failedDeletion != null -> stringResource(Res.string.delete_failed)
                failedUpdate != null || loader.failed -> stringResource(Res.string.error)
                else -> null
            },
        canDeleteRemote = GlobalYouTubeRepository.instance.isHistorySyncEnabled(),
        selectedHasRemoteEntries = selectedHasRemoteEntries,
        onQueryChange = { query = it },
        onSortChange = {
            sortMode = it
            sortDirection = it.defaultDirection
        },
        onRefresh = {
            when {
                failedDeletion != null -> failedDeletion?.let { deleteEntries(it.first, it.second) }
                failedUpdate != null -> failedUpdate?.invoke()
                else -> refreshKey++
            }
        },
        onPlay = { entry -> scope.launch { database.historySong(entry.id.toLong())?.let(playbackController::play) } },
        onSongMenu = { entry -> scope.launch { database.historySong(entry.id.toLong())?.let(onSongMenu) } },
        onToggleSelection = { id -> selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id },
        onSelectAll = { ids ->
            if (ids.isEmpty() || ids.size < selectedIds.size) {
                selectedIds = emptySet()
            } else {
                val selectedQuery = query
                scope.launch {
                    val allIds =
                        database
                            .visibleHistoryIds(
                                selectedQuery,
                                catalogArtistIds = true,
                                preserveUnicodeOrder = true,
                            ).mapTo(mutableSetOf(), Long::toString)
                    if (query == selectedQuery) selectedIds = allIds
                }
            }
        },
        onUpdate = { entry, title, artists, album -> updateEntry(entry.songId, title, artists, album) },
        onDelete = { entry, alsoRemote -> deleteEntries(setOf(entry.id), alsoRemote) },
        onDeleteSelected = ::deleteEntries,
        onNavigateBack = onBack,
        formatMonth = formatMonth,
        contentPadding = PaddingValues(bottom = bottomContentPadding),
        modifier = modifier,
        canRefresh = true,
    )
}

private fun StoredHistoryRow.toHistoryEntry() =
    HistoryEntryUi(
        id.toString(),
        songId,
        title,
        artists,
        album,
        thumbnailUrl,
        playedAt,
        remoteFeedbackToken,
        playTimeMs,
        remoteDateLabel,
        remoteOrder,
        hasEstimatedDate = source == "YOUTUBE" && playTimeMs == null,
    )
