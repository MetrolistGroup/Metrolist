package com.metrolist.shared.playlist

import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.librarySearchKey
import com.metrolist.shared.viewmodels.currentLibraryAccountKey
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class LibraryBookmarkSnapshot(
    val accountKey: String,
    val revision: Long,
)

internal object LibraryBookmarkMutations {
    private data class Choice(
        val revision: Long,
        val bookmarked: Boolean,
    )

    private val lock = SynchronizedObject()
    private val mutationMutex = Mutex()
    private var revision = 0L
    private val choices = mutableMapOf<String, MutableMap<String, Choice>>()

    fun capture(): LibraryBookmarkSnapshot =
        synchronized(lock) {
            LibraryBookmarkSnapshot(currentLibraryAccountKey(), revision)
        }

    fun record(
        accountKey: String,
        key: String,
        bookmarked: Boolean,
    ) = synchronized(lock) {
        choices.getOrPut(accountKey) { mutableMapOf() }[key] = Choice(++revision, bookmarked)
    }

    fun newerChoices(snapshot: LibraryBookmarkSnapshot): Map<String, Boolean> =
        synchronized(lock) {
            choices[snapshot.accountKey].orEmpty().filterValues { it.revision > snapshot.revision }.mapValues { it.value.bookmarked }
        }

    fun filterRemoved(
        items: List<YTItem>,
        snapshot: LibraryBookmarkSnapshot,
    ): List<YTItem> {
        val newer = newerChoices(snapshot)
        return items.filterNot { newer[it.librarySearchKey()] == false }
    }

    suspend fun <T> withLock(block: suspend () -> T): T = mutationMutex.withLock { block() }
}
