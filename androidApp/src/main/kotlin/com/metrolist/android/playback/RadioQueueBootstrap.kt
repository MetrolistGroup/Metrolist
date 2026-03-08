package com.metrolist.android.playback

import com.metrolist.shared.model.RadioQueuePage

internal suspend fun bootstrapRadioQueue(
    loadPage: suspend (String?) -> RadioQueuePage,
    isCurrent: () -> Boolean,
    applyPage: suspend (RadioQueuePage, initial: Boolean) -> Boolean,
    nextContinuation: (RadioQueuePage) -> String? = { it.continuation },
) {
    var continuation: String? = null
    val seenContinuations = mutableSetOf<String>()
    var initial = true
    repeat(10) {
        if (!isCurrent()) return
        val page = loadPage(continuation)
        if (!isCurrent()) return
        val appended = applyPage(page, initial)
        if (!isCurrent()) return
        if (appended) {
            if (!initial) return
            initial = false
        }
        continuation = nextContinuation(page)?.takeIf(seenContinuations::add) ?: return
    }
}
