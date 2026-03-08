package com.metrolist.android.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

internal class DownloadQueuePersistence(
    scope: CoroutineScope,
    private val write: (List<DownloadRequest>) -> Unit,
) {
    private val pending = AtomicReference<List<DownloadRequest>?>(null)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val writer = Mutex()

    init {
        scope.launch {
            for (change in changes) {
                delay(250)
                try {
                    flush()
                } catch (e: Exception) {
                    Timber.w(e, "Failed to persist download queue")
                    delay(1_000)
                    changes.trySend(Unit)
                }
            }
        }
    }

    fun submit(requests: List<DownloadRequest>) {
        pending.set(requests)
        check(changes.trySend(Unit).isSuccess)
    }

    suspend fun flush() {
        writer.withLock {
            val snapshot = pending.getAndSet(null) ?: return
            try {
                write(snapshot)
            } catch (e: Exception) {
                pending.compareAndSet(null, snapshot)
                throw e
            }
        }
    }
}
