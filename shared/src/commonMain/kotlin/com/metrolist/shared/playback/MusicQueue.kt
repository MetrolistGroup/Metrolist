package com.metrolist.shared.playback

import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

data class MusicQueueEntry(
    val key: Long,
    val song: SongItem,
)

data class MusicQueueState(
    val entries: List<MusicQueueEntry> = emptyList(),
    val currentIndex: Int = -1,
)

class MusicQueue {
    private class Entry(
        val key: Long,
        var song: SongItem,
    ) {
        var published = MusicQueueEntry(key, song)

        fun publish(): MusicQueueEntry {
            if (published.song !== song) published = MusicQueueEntry(key, song)
            return published
        }
    }

    var recommendationsSongsOnly: Boolean = false
        private set

    private var queueIdentity = Any()
    private var nextEntryKey = 0L
    private val _items = MutableStateFlow<List<SongItem>>(emptyList())
    val items: StateFlow<List<SongItem>> = _items.asStateFlow()
    val songs: StateFlow<List<SongItem>> = items

    private val _snapshots = MutableStateFlow(MusicQueueSnapshot())
    val snapshots: StateFlow<MusicQueueSnapshot> = _snapshots.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _state = MutableStateFlow(MusicQueueState())

    val state: StateFlow<MusicQueueState> = _state.asStateFlow()

    private var displayedEntries = emptyList<Entry>()
    private var originalEntries = emptyList<Entry>()
    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()
    var isShuffled: Boolean
        get() = _shuffleEnabled.value
        private set(value) {
            _shuffleEnabled.value = value
        }

    fun snapshot(): MusicQueueSnapshot = _snapshots.value.also { it.undoIdentity = queueIdentity }

    fun restore(snapshot: MusicQueueSnapshot) {
        queueIdentity = snapshot.undoIdentity ?: Any()
        val normalized = snapshot.forRestore(true)
        recommendationsSongsOnly = normalized.recommendationsSongsOnly
        val original = normalized.originalItems.map { Entry(nextEntryKey++, it) }
        val displayed = normalized.originalIndices.map(original::get)
        originalEntries = original.filterNot { it.song.isUnavailable }
        displayedEntries = displayed.filterNot { it.song.isUnavailable }
        isShuffled = normalized.isShuffled
        val playableIndex =
            if (normalized.currentIndex <
                0
            ) {
                -1
            } else {
                displayed.take(normalized.currentIndex).count { !it.song.isUnavailable }
            }
        publishItems(playableIndex.coerceAtMost(displayedEntries.lastIndex))
    }

    fun setQueue(
        songs: List<SongItem>,
        startIndex: Int = 0,
        recommendationsSongsOnly: Boolean = false,
    ) {
        queueIdentity = Any()
        this.recommendationsSongsOnly = recommendationsSongsOnly
        val playableSongs = songs.filterNot { it.isUnavailable }
        originalEntries = playableSongs.map { Entry(nextEntryKey++, it) }
        displayedEntries = originalEntries
        isShuffled = false
        val selectedIndex = startIndex.coerceIn(-1, songs.lastIndex)
        val playableIndex = if (selectedIndex < 0) -1 else songs.take(selectedIndex).count { !it.isUnavailable }
        publishItems(playableIndex.coerceAtMost(playableSongs.lastIndex))
    }

    internal fun syncQueue(
        songs: List<SongItem>,
        index: Int,
        persistentShuffle: Boolean,
        legacyShuffle: Boolean = false,
    ) {
        val playableSongs = songs.filterNot { it.isUnavailable }
        val playableIndex = if (index < 0) -1 else songs.take(index).count { !it.isUnavailable }.coerceAtMost(playableSongs.lastIndex)
        val current = _items.value
        if (playableSongs.take(current.size) == current) {
            addToQueue(playableSongs.drop(current.size))
            skipTo(playableIndex)
        } else {
            val wasShuffled = isShuffled && persistentShuffle
            setQueue(playableSongs, playableIndex)
            if (wasShuffled && songs.isNotEmpty()) toggleShuffle(legacyShuffle = legacyShuffle)
        }
    }

    fun peekNext(): SongItem? = _items.value.getOrNull(_currentIndex.value + 1)

    fun getNext(): SongItem? = skipTo(_currentIndex.value + 1)

    fun next(): SongItem? = getNext()

    fun getPrevious(): SongItem? = skipTo(_currentIndex.value - 1)

    fun previous(): SongItem? = getPrevious()

    fun peekPrevious(): SongItem? = _items.value.getOrNull(_currentIndex.value - 1)

    fun hasPrevious(): Boolean = _currentIndex.value > 0

    fun currentItem(): SongItem? = _items.value.getOrNull(_currentIndex.value)

    fun currentSong(): SongItem? = currentItem()

    fun updateCurrentDuration(
        durationSeconds: Long,
        expectedSong: SongItem? = currentItem(),
    ): SongItem? {
        if (durationSeconds <= 0L) return null
        val entry = displayedEntries.getOrNull(_currentIndex.value) ?: return null
        // StateFlow can retain an equal published instance after restoring a snapshot.
        val current = currentItem() ?: return null
        if (current !== expectedSong || (current.duration ?: 0L) > 0L) return null
        entry.song = current.copy(duration = durationSeconds)
        publishItems()
        return entry.song
    }

    fun replaceCurrent(song: SongItem): SongItem? {
        val entry = displayedEntries.getOrNull(_currentIndex.value) ?: return null
        val previous = entry.song
        entry.song = song
        publishItems()
        return previous
    }

    fun skipTo(index: Int): SongItem? {
        val song = _items.value.getOrNull(index) ?: return null
        _currentIndex.value = index
        _state.value = _state.value.copy(currentIndex = index)
        publishSnapshot()
        return song
    }

    fun appendPage(songs: List<SongItem>) {
        if (songs.isEmpty()) return
        val entries = songs.filterNot { it.isUnavailable }.map { Entry(nextEntryKey++, it) }
        originalEntries = originalEntries + entries
        displayedEntries = displayedEntries + if (isShuffled) entries.shuffled() else entries
        publishItems()
    }

    fun addToQueue(
        songs: List<SongItem>,
        preventDuplicates: Boolean = false,
        insertIndex: Int? = null,
    ) {
        val playableSongs = songs.filterNot { it.isUnavailable }
        var insertAt = (insertIndex ?: displayedEntries.size).coerceIn(0, displayedEntries.size)
        if (preventDuplicates) {
            val songIds = playableSongs.mapTo(mutableSetOf()) { it.id }
            val current = displayedEntries.getOrNull(_currentIndex.value)
            insertAt -= displayedEntries.take(insertAt).count { it !== current && it.song.id in songIds }
            removeSongs(songIds, keepCurrent = true)
        }
        val currentId = currentItem()?.id
        val newSongs = if (preventDuplicates) playableSongs.distinctBy { it.id }.filterNot { it.id == currentId } else playableSongs
        if (newSongs.isEmpty()) return
        val entries = newSongs.map { Entry(nextEntryKey++, it) }
        displayedEntries = displayedEntries.toMutableList().apply { addAll(insertAt, entries) }
        originalEntries = originalEntries.toMutableList().apply { addAll(if (isShuffled) size else insertAt, entries) }
        publishItems(_currentIndex.value + if (insertAt <= _currentIndex.value) entries.size else 0)
    }

    fun addToQueue(
        song: SongItem,
        preventDuplicates: Boolean = false,
        insertIndex: Int? = null,
    ) = addToQueue(listOf(song), preventDuplicates, insertIndex)

    fun playNext(
        songs: List<SongItem>,
        preventDuplicates: Boolean = false,
    ) {
        val playableSongs = songs.filterNot { it.isUnavailable }
        if (preventDuplicates) removeSongs(playableSongs.mapTo(mutableSetOf()) { it.id }, keepCurrent = true)
        val current = displayedEntries.getOrNull(_currentIndex.value)
        val newSongs = if (preventDuplicates) playableSongs.distinctBy { it.id }.filterNot { it.id == current?.song?.id } else playableSongs
        if (newSongs.isEmpty()) return
        val entries = newSongs.map { Entry(nextEntryKey++, it) }
        val insertAt = (_currentIndex.value + 1).coerceIn(0, displayedEntries.size)
        val originalInsertAt = if (isShuffled) originalEntries.indexOf(current) + 1 else insertAt
        displayedEntries = displayedEntries.toMutableList().apply { addAll(insertAt, entries) }
        originalEntries = originalEntries.toMutableList().apply { addAll(originalInsertAt, entries) }
        publishItems()
    }

    fun playNext(
        song: SongItem,
        preventDuplicates: Boolean = false,
    ) = playNext(listOf(song), preventDuplicates)

    fun move(
        from: Int,
        to: Int,
        updatedItem: SongItem? = null,
    ) {
        if (from !in displayedEntries.indices || to !in displayedEntries.indices) return
        if (from == to && updatedItem == null) return
        val current = displayedEntries.getOrNull(_currentIndex.value)
        val moved = displayedEntries[from]
        if (updatedItem != null) moved.song = updatedItem
        displayedEntries = displayedEntries.toMutableList().apply { add(to, removeAt(from)) }
        if (!isShuffled) originalEntries = displayedEntries
        publishItems(displayedEntries.indexOf(current))
    }

    fun reorder(order: List<Int>) {
        if (order.size != displayedEntries.size || order.toSet() != displayedEntries.indices.toSet()) return
        val current = displayedEntries.getOrNull(_currentIndex.value)
        displayedEntries = order.map(displayedEntries::get)
        if (!isShuffled) originalEntries = displayedEntries
        publishItems(displayedEntries.indexOf(current))
    }

    fun updateItems(updates: Map<Int, SongItem>) {
        if (updates.isEmpty()) return
        updates.forEach { (index, song) -> displayedEntries.getOrNull(index)?.song = song }
        publishItems()
    }

    fun replaceUpcomingQueue(songs: List<SongItem>) {
        val prefix = displayedEntries.take((_currentIndex.value + 1).coerceAtLeast(0))
        val retained = prefix.toSet()
        val entries = songs.filterNot { it.isUnavailable }.map { Entry(nextEntryKey++, it) }
        displayedEntries = prefix + entries
        originalEntries = originalEntries.filter { it in retained } + entries
        publishItems()
    }

    fun removeAt(indices: Set<Int>): QueueRemoval? {
        val validIndices = indices.filter { it in displayedEntries.indices }.sorted()
        if (validIndices.isEmpty()) return null
        val previousCurrentIndex = _currentIndex.value
        val originalIndices = originalEntries.withIndex().associate { it.value to it.index }
        val removedEntries = validIndices.map(displayedEntries::get).toSet()
        val removed = validIndices.map { IndexedQueueItem(it, displayedEntries[it].song) }
        val removedOriginal =
            validIndices.map { index ->
                val entry = displayedEntries[index]
                IndexedQueueItem(originalIndices.getValue(entry), entry.song)
            }
        displayedEntries = displayedEntries.filterNot { it in removedEntries }
        originalEntries = originalEntries.filterNot { it in removedEntries }
        publishItems((previousCurrentIndex - validIndices.count { it < previousCurrentIndex }).coerceIn(-1, displayedEntries.lastIndex))
        return QueueRemoval(removed, removedOriginal, previousCurrentIndex, _currentIndex.value, isShuffled).also {
            it.undoIdentity = queueIdentity
        }
    }

    fun removeQueueItem(index: Int) {
        removeAt(setOf(index))
    }

    fun restore(removals: List<QueueRemoval>) {
        removals.asReversed().forEach(::restore)
    }

    fun restore(removal: QueueRemoval) {
        if (removal.undoIdentity !== queueIdentity || removal.items.size != removal.originalItems.size) return
        val entries = removal.items.map { Entry(nextEntryKey++, it.song) }
        val currentIndexBeforeRestore = _currentIndex.value
        val current = displayedEntries.getOrNull(currentIndexBeforeRestore)
        val restoredItems = if (isShuffled) removal.items else removal.originalItems
        val restoredCanonical = mergeRestoredEntries(originalEntries, removal.originalItems, entries)
        val restored =
            if (!isShuffled && removal.wasShuffled) {
                val history = removal.items.withIndex().filter { it.value.index <= removal.previousCurrentIndex }
                val prefix =
                    mergeRestoredEntries(
                        displayedEntries.take(currentIndexBeforeRestore + 1),
                        history.map { it.value },
                        history.map { entries[it.index] },
                    )
                val retained = prefix.toSet()
                prefix + restoredCanonical.filterNot { it in retained }
            } else {
                mergeRestoredEntries(displayedEntries, restoredItems, entries)
            }
        val shiftedCurrentIndex = restored.indexOf(current)
        displayedEntries = restored
        val currentIndex =
            if (isShuffled == removal.wasShuffled && currentIndexBeforeRestore == removal.currentIndexAfterRemoval) {
                removal.previousCurrentIndex
            } else {
                shiftedCurrentIndex
            }.coerceIn(-1, restored.lastIndex)
        originalEntries = if (isShuffled) restoredCanonical else restored
        publishItems(currentIndex)
    }

    private fun mergeRestoredEntries(
        retained: List<Entry>,
        positions: List<IndexedQueueItem>,
        entries: List<Entry>,
    ): List<Entry> =
        buildList(retained.size + entries.size) {
            var retainedIndex = 0
            positions.withIndex().sortedBy { it.value.index }.forEach { (index, item) ->
                while (size < item.index && retainedIndex < retained.size) add(retained[retainedIndex++])
                add(entries[index])
            }
            while (retainedIndex < retained.size) add(retained[retainedIndex++])
        }

    fun removeSongs(
        songIds: Set<String>,
        keepCurrent: Boolean = true,
    ) {
        if (songIds.isEmpty()) return
        val current = displayedEntries.getOrNull(_currentIndex.value)
        val removed = displayedEntries.filter { it.song.id in songIds && (!keepCurrent || it !== current) }.toSet()
        if (removed.isEmpty()) return
        val successorIndex = displayedEntries.take(_currentIndex.value.coerceAtLeast(0)).count { it !in removed }
        displayedEntries = displayedEntries.filterNot { it in removed }
        originalEntries = originalEntries.filterNot { it in removed }
        publishItems(if (current in removed) successorIndex - 1 else displayedEntries.indexOf(current))
    }

    fun restoreShuffleMode(enabled: Boolean) {
        isShuffled = enabled && displayedEntries.isNotEmpty()
        publishSnapshot()
    }

    fun toggleShuffle(
        preserveHistory: Boolean = true,
        legacyShuffle: Boolean = false,
    ) {
        if (displayedEntries.isEmpty()) return
        if (isShuffled && legacyShuffle) {
            val current = displayedEntries.getOrNull(_currentIndex.value)
            displayedEntries = originalEntries
            isShuffled = false
            publishItems(displayedEntries.indexOf(current))
            return
        }
        val prefix =
            if ((preserveHistory && !legacyShuffle) || isShuffled) {
                displayedEntries.take((_currentIndex.value + 1).coerceAtLeast(0))
            } else {
                listOfNotNull(displayedEntries.getOrNull(_currentIndex.value))
            }
        val retained = prefix.toSet()
        val upcoming = originalEntries.filterNot { it in retained }
        isShuffled = !isShuffled
        displayedEntries =
            prefix +
            if (isShuffled && legacyShuffle) {
                upcoming.shuffled()
            } else if (isShuffled) {
                val (recommendations, explicit) = upcoming.partition { it.song.queueSourceTitle.isRecommendationGeneratedQueueSource() }
                explicit.shuffled() + recommendations
            } else {
                upcoming
            }
        if (!isShuffled) originalEntries = displayedEntries
        publishItems(prefix.lastIndex)
    }

    private fun publishItems(index: Int = _currentIndex.value) {
        val entries = displayedEntries.map { it.publish() }
        _items.value = entries.map { it.song }
        _currentIndex.value = index
        _state.value = MusicQueueState(entries, index)
        val originalIndices = originalEntries.withIndex().associate { it.value to it.index }
        _snapshots.value =
            MusicQueueSnapshot(
                displayedItems = _items.value,
                originalItems = originalEntries.map { it.song },
                originalIndices = displayedEntries.map(originalIndices::getValue),
                currentIndex = index,
                isShuffled = isShuffled,
                recommendationsSongsOnly = recommendationsSongsOnly,
            ).also { it.undoIdentity = queueIdentity }
    }

    private fun publishSnapshot() {
        _snapshots.value =
            _snapshots.value.copy(currentIndex = _currentIndex.value, isShuffled = isShuffled).also { it.undoIdentity = queueIdentity }
    }
}

private fun <T> List<T>.hasSameOccurrencesAs(other: List<T>): Boolean =
    size == other.size && groupingBy { it }.eachCount() == other.groupingBy { it }.eachCount()

@Serializable
data class MusicQueueSnapshot(
    val displayedItems: List<SongItem> = emptyList(),
    val originalItems: List<SongItem> = emptyList(),
    val currentIndex: Int = -1,
    val isShuffled: Boolean = false,
    val originalIndices: List<Int> = emptyList(),
    val recommendationsSongsOnly: Boolean = false,
) {
    @Transient
    internal var undoIdentity: Any? = null

    fun forRestore(rememberShuffle: Boolean): MusicQueueSnapshot {
        val canonical = originalItems.takeIf { it.hasSameOccurrencesAs(displayedItems) } ?: displayedItems
        val indices =
            originalIndices.takeIf { indices ->
                canonical === originalItems && indices.size == displayedItems.size &&
                    indices.toSet().size == canonical.size &&
                    indices.indices.all { canonical.getOrNull(indices[it]) == displayedItems[it] }
            } ?: run {
                val occurrences = canonical.withIndex().groupBy({ it.value }, { it.index }).mapValues { ArrayDeque(it.value) }
                displayedItems.map { occurrences.getValue(it).removeFirst() }
            }
        val index = currentIndex.coerceIn(-1, displayedItems.lastIndex)
        if (!rememberShuffle && isShuffled && displayedItems.isNotEmpty()) {
            val prefix = indices.take(index + 1)
            val retained = prefix.toSet()
            val ordered = (prefix + canonical.indices.filterNot { it in retained }).map(canonical::get)
            return copy(
                displayedItems = ordered,
                originalItems = ordered,
                originalIndices = canonical.indices.toList(),
                currentIndex = index,
                isShuffled = false,
            ).also { it.undoIdentity = undoIdentity }
        }
        return copy(
            originalItems = canonical,
            originalIndices = indices,
            currentIndex = index,
            isShuffled =
                isShuffled && displayedItems.isNotEmpty(),
        ).also { it.undoIdentity = undoIdentity }
    }
}

data class IndexedQueueItem(
    val index: Int,
    val song: SongItem,
)

data class QueueRemoval(
    val items: List<IndexedQueueItem>,
    val originalItems: List<IndexedQueueItem>,
    val previousCurrentIndex: Int,
    val currentIndexAfterRemoval: Int,
    val wasShuffled: Boolean,
) {
    internal var undoIdentity: Any? = null
}
