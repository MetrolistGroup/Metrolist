package com.metrolist.desktop.state.playback

import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.playback.hasSameStructure
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal interface PlaybackStateStore {
    var persistentQueue: Boolean
    var persistentPlaybackPosition: String
    var persistentPlaybackState: String
    var rememberShuffleAndRepeat: Boolean
}

private object SettingsPlaybackStateStore : PlaybackStateStore {
    override var persistentQueue: Boolean
        get() = Settings.shared.persistentQueue
        set(value) {
            Settings.shared.persistentQueue = value
        }
    override var persistentPlaybackPosition: String
        get() = Settings.shared.persistentPlaybackPosition
        set(value) {
            Settings.shared.persistentPlaybackPosition = value
        }
    override var persistentPlaybackState: String
        get() = Settings.shared.persistentPlaybackState
        set(value) {
            Settings.shared.persistentPlaybackState = value
        }
    override var rememberShuffleAndRepeat: Boolean
        get() = Settings.shared.rememberShuffleAndRepeat
        set(value) {
            Settings.shared.rememberShuffleAndRepeat = value
        }
}

internal data class PlaybackStateWrite(
    val snapshot: MusicQueueSnapshot,
    val positionMs: Long,
    val repeatMode: RepeatMode,
    val force: Boolean = false,
)

internal class PlaybackStateWriteQueue(
    scope: CoroutineScope,
    private val persist: (PlaybackStateWrite) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val requests = Channel<PlaybackStateWrite>(Channel.CONFLATED)
    private val workerFailure = AtomicReference<Throwable?>()
    private val flushCompletion = CompletableDeferred<Unit>()
    private val stateLock = Any()
    private var flushStarted = false
    private val worker =
        scope.launch(dispatcher) {
            for (write in requests) {
                runCatching { persist(write) }.exceptionOrNull()?.let { workerFailure.compareAndSet(null, it) }
            }
        }

    fun submit(write: PlaybackStateWrite) {
        synchronized(stateLock) {
            if (!flushStarted) requests.trySend(write)
        }
    }

    suspend fun flush(write: PlaybackStateWrite) {
        val ownsFlush =
            synchronized(stateLock) {
                if (flushStarted) {
                    false
                } else {
                    flushStarted = true
                    requests.close()
                    true
                }
            }
        if (ownsFlush) {
            withContext(NonCancellable) {
                worker.join()
                val finalFailure =
                    runCatching {
                        withContext(dispatcher) { persist(write.copy(force = true)) }
                    }.exceptionOrNull()
                val failure = finalFailure ?: workerFailure.get()
                if (finalFailure != null) workerFailure.get()?.let(finalFailure::addSuppressed)
                if (failure == null) flushCompletion.complete(Unit) else flushCompletion.completeExceptionally(failure)
            }
        }
        flushCompletion.await()
    }
}

internal class DesktopPlaybackRestoration {
    private var untouched = true
    private var completed = false
    private var job: Job? = null
    val pending: Boolean get() = untouched && !completed

    fun start(
        scope: CoroutineScope,
        restore: suspend () -> Unit,
    ) {
        job =
            scope.launch {
                restore()
                completed = true
            }
    }

    fun invalidate() {
        untouched = false
        job?.cancel()
    }

    suspend fun await() = job?.join()

    suspend fun stop(): Boolean {
        val preserveStoredSession = pending
        job?.cancelAndJoin()
        return preserveStoredSession
    }
}

internal class PlaybackStatePersistence(
    private val store: PlaybackStateStore = SettingsPlaybackStateStore,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private var lastState: PersistedPlaybackState? = null
    private var lastPosition: PlaybackPositionCheckpoint? = null

    fun persist(
        snapshot: MusicQueueSnapshot,
        positionMs: Long,
        repeatMode: RepeatMode,
        force: Boolean = false,
    ) {
        if (!store.persistentQueue) {
            clear()
            return
        }
        if (snapshot.displayedItems.isEmpty() || snapshot.currentIndex !in snapshot.displayedItems.indices) {
            clear()
            return
        }
        var state = lastState
        if (state == null || !state.queue.hasSameStructure(snapshot)) {
            state = PersistedPlaybackState(snapshot, positionMs, repeatMode, UUID.randomUUID().toString())
            store.persistentPlaybackState = json.encodeToString(PersistedPlaybackState.serializer(), state)
            lastState = state
        }
        val checkpoint = PlaybackPositionCheckpoint(checkNotNull(state.revision), positionMs, snapshot.currentIndex, repeatMode)
        if (force || checkpoint != lastPosition) {
            store.persistentPlaybackPosition = json.encodeToString(PlaybackPositionCheckpoint.serializer(), checkpoint)
            lastPosition = checkpoint
        }
    }

    fun restore(): PersistedPlaybackState? {
        if (!store.persistentQueue) {
            clear()
            return null
        }
        val raw =
            store.persistentPlaybackState.takeIf(String::isNotBlank) ?: run {
                clear()
                return null
            }
        val state =
            runCatching {
                json.decodeFromString(PersistedPlaybackState.serializer(), raw)
            }.getOrElse {
                clear()
                return null
            }
        if (state.queue.displayedItems.isEmpty() || state.queue.currentIndex !in state.queue.displayedItems.indices) {
            clear()
            return null
        }
        val checkpoint =
            runCatching {
                json.decodeFromString(PlaybackPositionCheckpoint.serializer(), store.persistentPlaybackPosition)
            }.getOrNull()?.takeIf {
                it.revision == state.revision &&
                    (it.currentIndex == null || it.currentIndex in state.queue.displayedItems.indices)
            }
        return state.copy(
            positionMs = checkpoint?.positionMs ?: state.positionMs,
            queue =
                state.queue
                    .copy(
                        currentIndex = checkpoint?.currentIndex ?: state.queue.currentIndex,
                    ).forRestore(store.rememberShuffleAndRepeat),
            repeatMode = if (store.rememberShuffleAndRepeat) checkpoint?.repeatMode ?: state.repeatMode else RepeatMode.OFF,
        )
    }

    private fun clear() {
        if (store.persistentPlaybackState.isNotEmpty()) store.persistentPlaybackState = ""
        if (store.persistentPlaybackPosition.isNotEmpty()) store.persistentPlaybackPosition = ""
        lastState = null
        lastPosition = null
    }
}

@Serializable
private data class PlaybackPositionCheckpoint(
    val revision: String,
    val positionMs: Long,
    val currentIndex: Int? = null,
    val repeatMode: RepeatMode? = null,
)
