package com.metrolist.desktop.state.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

// Lyric loads are tied to the visible song; user edits are tied to their captured video IDs.
internal class DesktopLyricsOffsetWrites(
    private val scope: CoroutineScope,
    private val persist: suspend (String, Int) -> Unit,
) {
    private data class PendingOffset(
        val value: Int,
        val job: Job,
    )

    private val pending = mutableMapOf<String, PendingOffset>()
    private val writeLock = Mutex()
    private var revision = 0L

    // Read and save run on the same UI dispatcher. A write may finish while storage is reading an older value.
    suspend fun resolve(
        videoId: String,
        read: suspend (String) -> Int,
    ): Int {
        while (true) {
            pending[videoId]?.let { return it.value }
            val readRevision = revision
            val stored = read(videoId)
            if (readRevision == revision) return stored
        }
    }

    fun save(
        videoId: String,
        offset: Int,
    ) {
        revision++
        pending[videoId]?.job?.cancel()
        val write =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    delay(150.milliseconds)
                    writeLock.withLock { persist(videoId, offset) }
                } finally {
                    if (pending[videoId]?.job === currentCoroutineContext()[Job]) pending.remove(videoId)
                }
            }
        pending[videoId] = PendingOffset(offset, write)
        write.start()
    }
}
