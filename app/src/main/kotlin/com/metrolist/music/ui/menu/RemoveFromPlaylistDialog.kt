package com.metrolist.music.ui.menu

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.utils.completed
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.PlaylistListItem
import com.metrolist.music.utils.isGenuineEmptyPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

@Composable
fun RemoveFromPlaylistDialog(
    isVisible: Boolean,
    songId: String,
    initialBrowseId: String? = null,
    initialLocalPlaylistId: String? = null,
    initialLocalRowId: Int? = null,
    onDismiss: () -> Unit,
) {
    if (!isVisible) return
    val database = LocalDatabase.current
    val syncUtils = LocalSyncUtils.current
    val playerConnection = LocalPlayerConnection.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlists by remember(database) { database.playlistsByNameAsc() }
        .collectAsStateWithLifecycle(initialValue = null)
    var selectedPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }
    var occurrences by remember { mutableStateOf<List<PlaylistSongMap>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var discoverPlaylists by remember { mutableStateOf(initialBrowseId == null && initialLocalPlaylistId == null) }
    var candidates by remember { mutableStateOf(PlaylistRemovalCandidates(emptyList(), false)) }
    var checkingPlaylists by remember { mutableStateOf(true) }
    var retryChecks by remember { mutableStateOf(0) }
    var autoSelectionAttempted by remember { mutableStateOf(false) }

    fun remove(playlist: PlaylistEntity, occurrence: PlaylistSongMap) {
        val browseId = playlist.browseId
        if (browseId == null) {
            database.transaction {
                val current = playlistSongMaps(songId).firstOrNull { it.id == occurrence.id }
                    ?: return@transaction
                move(playlist.id, current.position, Int.MAX_VALUE)
                delete(current.copy(position = Int.MAX_VALUE))
            }
            playerConnection?.service?.clearLocalPlaylistSource(songId, playlist.id, occurrence.id)
            Toast.makeText(context, R.string.removed_from_playlist, Toast.LENGTH_SHORT).show()
        } else {
            syncUtils.scheduleRemoveFromPlaylist(
                browseId, songId, playlist.id,
                localRowId = occurrence.id.takeIf { it != 0 },
                onSuccess = { removedNow ->
                    withContext(Dispatchers.Main) {
                        if (occurrence.setVideoId == null) {
                            playerConnection?.service?.clearLocalPlaylistSource(songId, playlist.id, occurrence.id)
                        }
                        occurrence.setVideoId?.let { playerConnection?.service?.clearPlaylistSource(songId, it, browseId) }
                        Toast.makeText(context, if (removedNow) R.string.removed_from_playlist else R.string.song_not_in_playlist, Toast.LENGTH_SHORT).show()
                    }
                },
                onFailure = {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.error_remove_from_playlist, Toast.LENGTH_SHORT).show()
                    }
                },
            ) { occurrence.setVideoId }
        }
        onDismiss()
    }

    suspend fun choose(playlist: PlaylistEntity, rowId: Int? = null) {
        autoSelectionAttempted = true
        loading = true
        try {
            val matches = withContext(Dispatchers.IO) {
                val browseId = playlist.browseId
                if (browseId == null) {
                    localPlaylistRemovalOccurrences(database.playlistSongMaps(songId), playlist.id, rowId)
                } else {
                    syncUtils.awaitPlaylistAdditions(playlist.id)
                    val page = YouTube.playlist(browseId).completed().getOrThrow()
                    check(page.playlist.isEditable) { "Playlist is not editable" }
                    check(page.songs.isNotEmpty() || isGenuineEmptyPlaylist(page)) { "Playlist response is incomplete" }
                    remotePlaylistRemovalOccurrences(
                        database.playlistSongMaps(songId), playlist.id, songId, page.songs,
                    ).filter { rowId == null || it.id == rowId }
                }
            }
            when (matches.size) {
                0 -> {
                    playlist.browseId?.let { browseId ->
                        withContext(Dispatchers.IO) { syncUtils.syncPlaylistSuspend(browseId, playlist.id) }
                    }
                    discoverPlaylists = true
                    retryChecks++
                    Toast.makeText(context, R.string.song_not_in_playlist, Toast.LENGTH_SHORT).show()
                }
                1 -> remove(playlist, matches.single())
                else -> {
                    selectedPlaylist = playlist
                    occurrences = matches.sortedBy { it.position }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            discoverPlaylists = true
            Toast.makeText(context, R.string.error_remove_from_playlist, Toast.LENGTH_SHORT).show()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(songId, playlists, discoverPlaylists, retryChecks) {
        if (!discoverPlaylists) {
            checkingPlaylists = false
            return@LaunchedEffect
        }
        checkingPlaylists = true
        val availablePlaylists = playlists ?: return@LaunchedEffect
        try {
            candidates = withContext(Dispatchers.IO) {
                availablePlaylists.filter { it.playlist.isEditable && it.playlist.browseId != null }.forEach {
                    syncUtils.awaitPlaylistAdditions(it.id)
                }
                findPlaylistRemovalCandidates(availablePlaylists, songId, database.playlistSongMaps(songId)) { browseId ->
                    YouTube.playlist(browseId).completed().getOrThrow()
                }
            }
            if (!autoSelectionAttempted && selectedPlaylist == null) {
                candidates.automaticPlaylist(initialBrowseId, initialLocalPlaylistId)?.let { playlist ->
                    autoSelectionAttempted = true
                    scope.launch { choose(playlist.playlist) }
                }
            }
        } finally {
            checkingPlaylists = false
        }
    }

    LaunchedEffect(initialBrowseId, initialLocalPlaylistId) {
        if (initialBrowseId != null) {
            val browseId = initialBrowseId
            val playlist = withContext(Dispatchers.IO) { database.playlistByBrowseId(browseId).firstOrNull() }
            choose(playlist?.playlist ?: PlaylistEntity(name = "", browseId = browseId), initialLocalRowId)
        } else if (initialLocalPlaylistId != null) {
            val playlist = withContext(Dispatchers.IO) { database.playlistBlocking(initialLocalPlaylistId) }
            val editable = playlist?.playlist?.takeIf { it.isEditable }
            if (editable != null) choose(editable, initialLocalRowId) else discoverPlaylists = true
        }
    }

    ListDialog(onDismiss = onDismiss) {
        item {
            Text(
                stringResource(if (selectedPlaylist == null) R.string.choose_playlist_to_remove else R.string.choose_playlist_occurrence),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        if (loading || checkingPlaylists) {
            item { CircularProgressIndicator(modifier = Modifier.padding(24.dp)) }
        } else if (selectedPlaylist != null) {
            items(occurrences) { occurrence ->
                ListItem(
                    title = stringResource(
                        if (selectedPlaylist?.browseId != null && occurrence.setVideoId == null) {
                            R.string.local_playlist_occurrence_position
                        } else R.string.playlist_occurrence_position,
                        occurrence.position + 1,
                    ),
                    thumbnailContent = {},
                    modifier = Modifier.clickable { remove(selectedPlaylist!!, occurrence) },
                )
            }
        } else {
            if (candidates.hasFailures) {
                item {
                    Text(
                        stringResource(R.string.error_check_removal_playlists),
                        modifier = Modifier.clickable { retryChecks++ }.padding(24.dp),
                    )
                }
            }
            if (candidates.playlists.isEmpty() && !candidates.hasFailures) {
                item { Text(stringResource(R.string.no_playlists_containing_song), modifier = Modifier.padding(24.dp)) }
            }
            items(candidates.playlists, key = { it.id }) { playlist ->
                PlaylistListItem(
                    playlist = playlist,
                    modifier = Modifier.clickable { scope.launch { choose(playlist.playlist) } },
                )
            }
        }
    }
}
