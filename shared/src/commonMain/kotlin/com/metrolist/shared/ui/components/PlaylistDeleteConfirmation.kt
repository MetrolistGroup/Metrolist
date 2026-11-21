package com.metrolist.shared.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.shared.model.PlaylistItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaylistDeleteConfirmation(
    private val scope: CoroutineScope,
) {
    class Request internal constructor(
        val playlist: PlaylistItem,
    ) {
        internal val key = if (playlist.isLocal) playlist.id else playlist.id.removePrefix("VL")
    }

    var request by mutableStateOf<Request?>(null)
        private set
    var failed by mutableStateOf(false)
        private set
    private var submissions by mutableStateOf<Map<String, Request>>(emptyMap())

    fun open(playlist: PlaylistItem) {
        val newRequest = Request(playlist)
        if (isSubmitting(newRequest)) return
        request = newRequest
        failed = false
    }

    fun dismiss(expected: Request) {
        if (request !== expected) return
        request = null
        failed = false
    }

    fun isSubmitting(expected: Request): Boolean = expected.key in submissions

    fun confirm(
        expected: Request,
        submit: (PlaylistItem, (Result<Unit>) -> Unit) -> Job,
        onFailure: () -> Unit = {},
    ) {
        if (!scope.isActive || request !== expected || isSubmitting(expected)) return
        submissions += expected.key to expected
        failed = false
        val job =
            submit(expected.playlist) { result ->
                if (clearSubmission(expected) && scope.isActive && request === expected) {
                    result.onSuccess { dismiss(expected) }
                    result.onFailure {
                        failed = true
                        onFailure()
                    }
                }
            }
        // A canceled mutation can finish without delivering a result.
        scope.launch {
            job.join()
            clearSubmission(expected)
        }
    }

    private fun clearSubmission(expected: Request): Boolean {
        if (submissions[expected.key] !== expected) return false
        submissions -= expected.key
        return true
    }
}
