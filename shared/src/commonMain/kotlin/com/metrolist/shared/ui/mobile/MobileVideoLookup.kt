package com.metrolist.shared.ui.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class MobileVideoLookup(
    private val scope: CoroutineScope,
    private val resolveAndPlay: suspend (String) -> Boolean,
    private val onPlaying: () -> Unit,
) {
    var failedVideoId by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null
    private var generation = 0L

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        failedVideoId = null
    }

    fun dismissFailure() {
        failedVideoId = null
    }

    fun play(videoId: String) {
        cancel()
        val request = generation
        job =
            scope.launch {
                val played = resolveAndPlay(videoId)
                if (request != generation) return@launch
                if (played) onPlaying() else failedVideoId = videoId
            }
    }
}
