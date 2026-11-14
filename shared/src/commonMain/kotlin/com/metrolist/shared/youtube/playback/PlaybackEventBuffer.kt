package com.metrolist.shared.youtube.playback

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class PlaybackEventBuffer internal constructor(
    private val capacity: Int,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    init {
        require(capacity > 0)
    }

    private val lock = SynchronizedObject()
    private val entries = ArrayDeque<Lazy<PlaybackEvent>>(capacity)
    private val revision = MutableStateFlow(0L)
    private var nextId = 0L

    val value: List<PlaybackEvent>
        get() = synchronized(lock) { entries.toList() }.map { it.value }

    val updates: Flow<List<PlaybackEvent>> =
        flow {
            revision.collect {
                emit(value)
                delay(250)
            }
        }.flowOn(dispatcher)

    internal fun append(event: (Long) -> PlaybackEvent) {
        synchronized(lock) {
            val id = ++nextId
            if (entries.size == capacity) entries.removeFirst()
            entries.addLast(lazy { event(id) })
            revision.value++
        }
    }

    internal fun clear() {
        synchronized(lock) {
            entries.clear()
            revision.value++
        }
    }
}
