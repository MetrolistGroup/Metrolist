package com.metrolist.shared.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.metrolist.shared.api.PlaylistSongAlreadyPresentException
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlist.PlaylistAddBatchResult
import com.metrolist.shared.playlist.addSongToStoredPlaylist
import com.metrolist.shared.playlist.addSongsToLocalPlaylist
import com.metrolist.shared.playlist.createStoredLocalPlaylist
import com.metrolist.shared.playlist.createStoredPlaylist
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

data class PlaylistAddCompletion(
    val playlistName: String,
    val count: Int,
)

internal class MobilePlaylistWorkflow(
    private val scope: CoroutineScope,
    private val create: suspend (String, Boolean) -> Result<PlaylistEntity>,
    private val canCreateRemote: () -> Boolean = { false },
    private val addLocalBatch: (suspend (PlaylistEntity, List<SongItem>, Boolean) -> PlaylistAddBatchResult)? = null,
    private val add: suspend (PlaylistEntity, SongItem, Boolean) -> Result<Unit>,
) {
    private var batch by mutableStateOf<List<SongItem>>(emptyList())
    private var nextIndex by mutableIntStateOf(0)
    val songs: List<SongItem> get() = batch.subList(nextIndex, batch.size)
    val song: SongItem? get() = batch.getOrNull(nextIndex)
    var creating by mutableStateOf(false)
        private set
    var name by mutableStateOf("")
    var createLocally by mutableStateOf(true)
    val remoteCreationAvailable: Boolean get() = canCreateRemote()
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var duplicate by mutableStateOf<PlaylistEntity?>(null)
        private set
    private var completions by mutableStateOf<List<PlaylistAddCompletion>>(emptyList())
    val completion: PlaylistAddCompletion? get() = completions.firstOrNull()
    var selectedPlaylists by mutableStateOf<List<PlaylistEntity>>(emptyList())
        private set
    var completedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var failedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    private val progress = mutableMapOf<String, Int>()
    private val addedCounts = mutableMapOf<String, Int>()

    var completionIndex by mutableIntStateOf(0)
        private set

    fun consumeCompletion() {
        completionIndex++
        completions = completions.drop(1)
    }

    fun select(song: SongItem) = select(listOf(song))

    fun select(songs: List<SongItem>) {
        if (busy) return
        batch = songs.toList()
        nextIndex = 0
        selectedPlaylists = emptyList()
        completedIds = emptySet()
        failedIds = emptySet()
        progress.clear()
        addedCounts.clear()
        failed = false
        duplicate = null
    }

    fun dismissSong() {
        if (!busy) {
            batch = emptyList()
            nextIndex = 0
            duplicate = null
            failed = false
        }
    }

    fun showCreate() {
        if (busy) return
        creating = true
        createLocally = !canCreateRemote()
        failed = false
    }

    fun dismissCreate() {
        if (busy) return
        creating = false
        name = ""
    }

    fun togglePlaylist(playlist: PlaylistEntity) {
        if (busy || duplicate != null || playlist.id in completedIds) return
        selectedPlaylists =
            if (selectedPlaylists.any { it.id == playlist.id }) {
                selectedPlaylists.filterNot { it.id == playlist.id }
            } else {
                selectedPlaylists + playlist
            }
        failedIds = failedIds - playlist.id
        failed = failedIds.isNotEmpty()
    }

    fun skipDuplicate() {
        val playlist = duplicate ?: return
        if (busy) return
        progress[playlist.id] = nextIndex + 1
        launchAdd()
    }

    fun confirmDuplicate() {
        val playlist = duplicate ?: return
        launchAdd(allowDuplicateIn = playlist.id)
    }

    fun addSelected() = launchAdd(retryFailed = true)

    private fun launchAdd(
        allowDuplicateIn: String? = null,
        retryFailed: Boolean = false,
    ) {
        if (busy || batch.isEmpty() || selectedPlaylists.none { it.id !in completedIds }) return
        busy = true
        if (retryFailed) failedIds = emptySet()
        failed = failedIds.isNotEmpty()
        duplicate = null
        scope.launch {
            try {
                finishSelected(allowDuplicateIn)
            } finally {
                busy = false
            }
        }
    }

    fun createPlaylist() {
        if (busy || name.isBlank()) return
        val requestedName = name.trim()
        val hasSongs = songs.isNotEmpty()
        val local = createLocally
        busy = true
        failed = false
        scope.launch {
            try {
                val playlist = create(requestedName, local).getOrNull()
                if (playlist == null) {
                    failed = true
                    return@launch
                }
                creating = false
                name = ""
                if (hasSongs) {
                    failedIds = emptySet()
                    selectedPlaylists = selectedPlaylists + playlist
                    finishSelected()
                }
            } finally {
                busy = false
            }
        }
    }

    private suspend fun finishSelected(allowDuplicateIn: String? = null) {
        for (playlist in selectedPlaylists) {
            if (playlist.id in completedIds || playlist.id in failedIds) continue
            nextIndex = progress[playlist.id] ?: 0
            finishAdd(playlist, allowDuplicate = playlist.id == allowDuplicateIn)
            if (duplicate != null) return
        }
        if (selectedPlaylists.all { it.id in completedIds }) {
            batch = emptyList()
            nextIndex = 0
        } else {
            nextIndex = progress[selectedPlaylists.first { it.id !in completedIds }.id] ?: 0
        }
    }

    private suspend fun finishAdd(
        playlist: PlaylistEntity,
        allowDuplicate: Boolean,
    ) {
        var allowCurrentDuplicate = allowDuplicate
        while (songs.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val result =
                if ((playlist.isLocal || playlist.browseId == null) && addLocalBatch != null) {
                    addLocalBatch(playlist, songs, allowCurrentDuplicate)
                } else {
                    val single = add(playlist, songs.first(), allowCurrentDuplicate)
                    PlaylistAddBatchResult(if (single.isSuccess) 1 else 0, single.exceptionOrNull())
                }
            nextIndex += result.addedCount
            progress[playlist.id] = nextIndex
            addedCounts[playlist.id] = (addedCounts[playlist.id] ?: 0) + result.addedCount
            if (result.addedCount > 0) allowCurrentDuplicate = false
            when (result.failure) {
                null -> {}

                is PlaylistSongAlreadyPresentException -> {
                    duplicate = playlist
                    return
                }

                else -> {
                    failedIds = failedIds + playlist.id
                    failed = true
                    return
                }
            }
        }
        completedIds = completedIds + playlist.id
        val added = addedCounts[playlist.id] ?: 0
        if (added > 0) completions = completions + PlaylistAddCompletion(playlist.name, added)
    }
}

@Composable
internal fun rememberMobilePlaylistWorkflow(
    database: MusicDatabase,
    scope: CoroutineScope,
): MobilePlaylistWorkflow =
    remember(database, scope) {
        MobilePlaylistWorkflow(
            scope = scope,
            canCreateRemote = { GlobalYouTubeRepository.instance.canManagePlaylists() },
            create = { name, local ->
                val repository = GlobalYouTubeRepository.instance
                when {
                    local -> {
                        createStoredLocalPlaylist(database, name)
                    }

                    !repository.canManagePlaylists() -> {
                        Result.failure(IllegalStateException("Playlist authentication required"))
                    }

                    else -> {
                        createStoredPlaylist(database, repository, name)
                            .mapCatching { id ->
                                checkNotNull(database.dao().findPlaylistEntity(id))
                            }.onFailure { if (it is CancellationException) throw it }
                    }
                }
            },
            addLocalBatch = { playlist, songs, allowDuplicate ->
                addSongsToLocalPlaylist(database, playlist.id, songs, allowDuplicate)
            },
            add = { playlist, song, allowDuplicate ->
                addSongToStoredPlaylist(database, GlobalYouTubeRepository.instance, playlist.id, song, allowDuplicate)
            },
        )
    }

@Composable
fun rememberSongsPlaylistAdder(
    database: MusicDatabase,
    onAdded: (suspend (PlaylistAddCompletion) -> Unit)? = null,
): (List<SongItem>) -> Unit {
    val workflow = rememberMobilePlaylistWorkflow(database, rememberCoroutineScope())
    if (workflow.song != null || workflow.creating || workflow.duplicate != null) {
        MobilePlaylistActions(workflow, database)
    }
    val currentOnAdded by rememberUpdatedState(onAdded)
    val completion = workflow.completion
    LaunchedEffect(completion, workflow.completionIndex) {
        if (completion != null) {
            currentOnAdded?.invoke(completion)
            workflow.consumeCompletion()
        }
    }
    return remember(workflow) { workflow::select }
}
