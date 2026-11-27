package com.metrolist.shared.viewmodels

import com.metrolist.shared.api.PlaylistPage
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.playlist.PendingPlaylistAdds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PlaylistPageLoader(
    private val firstPage: suspend () -> PlaylistPage,
    private val nextPage: suspend (PlaylistPage) -> PlaylistPage,
) {
    constructor(repository: YouTubeRepository, playlistId: String) : this(
        { repository.getPlaylistPages(playlistId, applyContentFilters = false).first() },
        { repository.continuePlaylistPage(it, applyContentFilters = false) },
    )

    private val mutex = Mutex()
    private val _page = MutableStateFlow<PlaylistPage?>(null)
    val page = _page.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    // The queue revision from before the first page, which storing a snapshot of these pages passes along.
    var queueRevision: Long? = null
        private set

    suspend fun loadFirst() = mutex.withLock { if (_page.value == null) _page.value = fetchFirst() }

    private suspend fun fetchFirst(): PlaylistPage {
        queueRevision = PendingPlaylistAdds.queueRevision()
        return firstPage()
    }

    suspend fun loadMore() {
        val cursor = _page.value ?: return
        mutex.withLock {
            if (_page.value !== cursor || !cursor.hasMore) return
            _loading.value = true
            try {
                _page.value = nextPage(cursor)
            } finally {
                _loading.value = false
            }
        }
    }

    suspend fun complete(): PlaylistPage =
        mutex.withLock {
            _loading.value = true
            try {
                if (_page.value == null) _page.value = fetchFirst()
                while (_page.value!!.hasMore) _page.value = nextPage(_page.value!!)
                _page.value!!
            } finally {
                _loading.value = false
            }
        }
}
