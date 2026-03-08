package com.metrolist.shared.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ArtistSubscriptionState {
    var isSubscribed by mutableStateOf(false)
        private set
    var subscribeSuccessCount by mutableStateOf(0)
        private set
    var revision = 0L
        private set
    private var isUpdating = false

    fun toggle(
        scope: CoroutineScope,
        request: suspend (Boolean) -> Boolean,
        onError: suspend () -> Unit,
    ): Job? {
        val requested = beginToggle() ?: return null
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!requestToggle(requested, request)) onError()
        }
    }

    internal suspend fun requestToggle(
        requested: Boolean,
        request: suspend (Boolean) -> Boolean,
    ): Boolean {
        var succeeded = false
        try {
            succeeded = request(requested)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        } finally {
            finishToggle(requested, succeeded)
        }
        return succeeded
    }

    fun applyBrowse(
        subscribed: Boolean,
        browseRevision: Long,
    ) {
        if (!isUpdating && browseRevision == revision) isSubscribed = subscribed
    }

    fun beginToggle(): Boolean? {
        if (isUpdating) return null
        isUpdating = true
        revision++
        isSubscribed = !isSubscribed
        return isSubscribed
    }

    fun finishToggle(
        requested: Boolean,
        succeeded: Boolean,
    ) {
        isSubscribed = if (succeeded) requested else !requested
        isUpdating = false
        if (succeeded && requested) subscribeSuccessCount++
        revision++
    }
}
