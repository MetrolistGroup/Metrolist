package com.metrolist.shared.viewmodels

import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcastBrowseId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlaylistEntity.supportsDownloadPlaylistFilter(): Boolean {
    if (isLocal || browseId == null) return true
    val id = browseId.removePrefix("VL")
    return !id.equals("RDPN", true) && !id.equals("SE", true) && !id.startsWith("FE") && !id.isPodcastBrowseId()
}

data class DownloadPlaylistFilterState(
    val playlist: PlaylistEntity? = null,
    val exclude: Boolean = false,
    val songIds: Set<String>? = null,
    val failed: Boolean = false,
) {
    val loading: Boolean get() = playlist != null && songIds == null && !failed

    fun apply(songs: List<SongItem>): List<SongItem> {
        if (playlist == null) return songs
        val ids = songIds ?: return songs
        return songs.filter { (it.id in ids) != exclude }
    }
}

class DownloadPlaylistFilter(
    private val scope: CoroutineScope,
    private val localMembership: (String) -> Flow<Set<String>>,
    private val remoteMembership: suspend (String) -> Set<String>,
) {
    private val mutableState = MutableStateFlow(DownloadPlaylistFilterState())
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var revision = 0

    fun select(playlist: PlaylistEntity?) {
        loadJob?.cancel()
        val request = ++revision
        mutableState.value = DownloadPlaylistFilterState(playlist, exclude = state.value.exclude)
        if (playlist == null) return
        loadJob =
            scope.launch {
                try {
                    if (playlist.isLocal || playlist.browseId == null) {
                        localMembership(playlist.id).collect { ids ->
                            if (request == revision) mutableState.update { it.copy(songIds = ids) }
                        }
                    } else {
                        val ids = remoteMembership(playlist.browseId)
                        if (request == revision) mutableState.update { it.copy(songIds = ids) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (request == revision) mutableState.update { it.copy(songIds = null, failed = true) }
                }
            }
    }

    fun setExcluded(exclude: Boolean) {
        mutableState.update { it.copy(exclude = exclude) }
    }

    fun refresh() = select(state.value.playlist)
}
