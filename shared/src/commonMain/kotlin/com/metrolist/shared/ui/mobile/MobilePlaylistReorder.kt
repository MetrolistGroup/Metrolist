package com.metrolist.shared.ui.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.moveSelection
import com.metrolist.shared.model.selectionReorderMoves
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MobilePlaylistReorder(
    private val scope: CoroutineScope,
    private val persist: suspend (Int, Int?) -> Result<Unit>,
    private val reload: suspend () -> List<Pair<SongItem, PlaylistSongMap>>,
) {
    var entries by mutableStateOf<List<Pair<SongItem, PlaylistSongMap>>>(emptyList())
        private set
    private var sourceEntries = entries
    private var pendingMove: List<Pair<Int, Int?>>? = null
    private var originalEntries = entries
    private var movingIds = emptySet<Int>()
    private var saving = 0
    private val saveMutex = Mutex()

    fun update(entries: List<Pair<SongItem, PlaylistSongMap>>) {
        sourceEntries = entries
        if (pendingMove == null && saving == 0) this.entries = entries
    }

    fun move(
        fromMappingId: Int,
        toMappingId: Int,
        selectedMappingIds: Set<Int> = emptySet(),
    ) {
        val from = entries.indexOfFirst { it.second.id == fromMappingId }
        val to = entries.indexOfFirst { it.second.id == toMappingId }
        if (from < 0 || to < 0 || from == to) return
        val selected = entries.indices.filterTo(mutableSetOf()) { entries[it].second.id in selectedMappingIds }
        val reordered = entries.moveSelection(from, to, selected)
        if (reordered == entries) return
        if (pendingMove == null) originalEntries = entries
        movingIds = movingIds + if (fromMappingId in selectedMappingIds) selectedMappingIds else setOf(fromMappingId)
        val originalIds = originalEntries.map { it.second.id }
        val moves = selectionReorderMoves(originalIds, reordered.map { it.second.id }, movingIds)
        val preview = originalIds.toMutableList()
        pendingMove =
            moves.map { (source, destination) ->
                val id = preview.removeAt(source)
                preview.add(destination, id)
                id to preview.getOrNull(destination + 1)
            }
        entries = reordered
    }

    fun commit(onFailure: () -> Unit) {
        val move = pendingMove ?: return
        pendingMove = null
        movingIds = emptySet()
        saving++
        scope.launch {
            try {
                saveMutex.withLock {
                    move.forEach { (id, successor) -> persist(id, successor).getOrThrow() }
                    sourceEntries = reload()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (move.size > 1) {
                    try {
                        sourceEntries = reload()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                    }
                }
                onFailure()
            } finally {
                saving--
                if (pendingMove == null && saving == 0) entries = sourceEntries
            }
        }
    }
}
