package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

internal class CanvasPreloader<T>(
    private val scope: CoroutineScope,
    private val load: suspend (SongItem) -> T,
) {
    val ready = MutableStateFlow<Map<String, T>>(emptyMap())
    private var currentId: String? = null
    private var targetIds = emptyList<String>()
    private var job: Job? = null
    private var generation = 0

    fun update(
        currentId: String?,
        next: SongItem?,
        previous: SongItem? = null,
        enabled: Boolean = true,
    ) {
        this.currentId = currentId
        val targets = listOfNotNull(next, previous).distinctBy { it.id }.filterNot { it.id == currentId }
        val ids = targets.map { it.id }
        ready.value = ready.value.filterKeys { it == currentId || it in ids }
        if (!enabled) {
            pause()
            return
        }
        if (targetIds == ids) return
        job?.cancel()
        val request = ++generation
        targetIds = ids
        job =
            scope.launchCanvasFetch {
                for (song in targets) {
                    if (song.id in ready.value) continue
                    launchCanvasFetch {
                        val result = load(song)
                        if (request == generation) ready.value = ready.value + (song.id to result)
                    }
                }
            }
    }

    fun pause() {
        generation++
        job?.cancel()
        targetIds = emptyList()
    }

    fun rememberCurrent(
        id: String,
        value: T,
    ) {
        if (id == currentId) ready.value = ready.value + (id to value)
    }

    fun forget(id: String) {
        ready.value -= id
    }

    fun clear() {
        generation++
        job?.cancel()
        currentId = null
        targetIds = emptyList()
        ready.value = emptyMap()
    }
}
