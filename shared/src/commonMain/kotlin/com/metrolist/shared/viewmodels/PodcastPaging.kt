package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.PodcastPage
import kotlinx.coroutines.flow.Flow

suspend fun collectPodcastPages(
    pages: Flow<PodcastPage>,
    awaitNextPage: suspend () -> Unit,
    publish: (PodcastPage) -> Unit,
) {
    pages.collect { page ->
        publish(page)
        if (page.continuation != null) awaitNextPage()
    }
}
