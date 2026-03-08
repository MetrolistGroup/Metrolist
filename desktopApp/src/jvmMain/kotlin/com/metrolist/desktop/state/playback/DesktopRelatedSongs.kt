package com.metrolist.desktop.state.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class DesktopRelatedSongs(
    private val scope: CoroutineScope,
    private val currentSongId: () -> String?,
    private val load: suspend (String) -> List<SongItem>,
) {
    var songs by mutableStateOf<List<SongItem>>(emptyList())
    var loading by mutableStateOf(false)
        private set
    private var requestedId: String? = null
    private var job: Job? = null
    private var generation = 0L

    fun fetch(videoId: String) {
        if (requestedId == videoId) return
        val request = ++generation
        job?.cancel()
        requestedId = videoId
        songs = emptyList()
        loading = true
        job =
            scope.launch {
                try {
                    val result = load(videoId)
                    if (isActive && request == generation && currentSongId() == videoId) songs = result
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (request == generation) requestedId = null
                } finally {
                    if (request == generation) {
                        if (songs.isEmpty()) requestedId = null
                        loading = false
                    }
                }
            }
    }
}
