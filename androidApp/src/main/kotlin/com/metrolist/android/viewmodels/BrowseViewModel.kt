package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.shared.viewmodels.BrowseViewModel as SharedBrowseViewModel

class BrowseViewModel(
    browseId: String,
    params: String?,
    title: String?,
) : ViewModel() {
    private val shared = SharedBrowseViewModel(browseId, params, title, viewModelScope)
    val page get() = shared.page
    val isLoading get() = shared.isLoading
    val isLoadingMore get() = shared.isLoadingMore
    val loadFailed get() = shared.loadFailed
    val loadMoreFailed get() = shared.loadMoreFailed
    val hasMore get() = shared.hasMore

    fun load() = shared.load()

    fun loadMore() = shared.loadMore()
}
