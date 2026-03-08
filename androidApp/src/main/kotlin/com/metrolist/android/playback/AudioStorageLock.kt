package com.metrolist.android.playback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AudioStorageLock {
    private val locks = List(32) { Mutex() }

    suspend fun <T> song(
        songId: String,
        block: suspend () -> T,
    ): T = locks[(songId.hashCode() and Int.MAX_VALUE) % locks.size].withLock { block() }

    suspend fun <T> all(block: suspend () -> T): T = lockRemaining(0, block)

    private suspend fun <T> lockRemaining(
        index: Int,
        block: suspend () -> T,
    ): T = if (index == locks.size) block() else locks[index].withLock { lockRemaining(index + 1, block) }
}
