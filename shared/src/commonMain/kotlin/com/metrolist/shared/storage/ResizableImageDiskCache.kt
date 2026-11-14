package com.metrolist.shared.storage

import coil3.disk.DiskCache
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Path

class ResizableImageDiskCache(
    override val directory: Path,
    maxSizeBytes: Long,
) : DiskCache {
    private val lock = SynchronizedObject()
    private var requestedSize = maxSizeBytes.also { require(it >= 0) }
    private var activeHandles = 0
    private var closed = false
    private var delegate = createCache()

    private fun createCache(): DiskCache {
        val builder = DiskCache.Builder().directory(directory).maxSizeBytes(requestedSize.coerceAtLeast(1))
        val cache = builder.build()
        if (requestedSize == 0L) {
            cache.clear()
        } else if (cache.size > requestedSize) {
            // Coil trims a loaded journal on shutdown, not when it first reads the smaller budget.
            cache.shutdown()
            return builder.build()
        }
        return cache
    }

    override val fileSystem get() = synchronized(lock) { delegate.fileSystem }
    override val size get() = synchronized(lock) { delegate.size }
    override val maxSize get() = synchronized(lock) { requestedSize }

    fun resize(maxSizeBytes: Long) =
        synchronized(lock) {
            require(maxSizeBytes >= 0)
            check(!closed)
            requestedSize = maxSizeBytes
            applyPendingSize()
        }

    private fun applyPendingSize() {
        // Open Coil snapshots/editors own files; never reopen their directory until they finish.
        if (!closed && activeHandles == 0 && delegate.maxSize != requestedSize.coerceAtLeast(1)) {
            delegate.shutdown()
            delegate = createCache()
        }
        if (!closed && activeHandles == 0 && requestedSize == 0L) delegate.clear()
    }

    private fun canOpen() = !closed && requestedSize > 0 && delegate.maxSize == requestedSize

    override fun openSnapshot(key: String): DiskCache.Snapshot? =
        synchronized(lock) {
            if (canOpen()) delegate.openSnapshot(key)?.let(::Snapshot) else null
        }

    override fun openEditor(key: String): DiskCache.Editor? =
        synchronized(lock) {
            if (canOpen()) delegate.openEditor(key)?.let(::Editor) else null
        }

    override fun remove(key: String): Boolean = synchronized(lock) { !closed && delegate.remove(key) }

    override fun clear() =
        synchronized(lock) {
            check(!closed)
            delegate.clear()
        }

    override fun shutdown() =
        synchronized(lock) {
            if (!closed) {
                closed = true
                delegate.shutdown()
            }
        }

    private inner class Snapshot(
        private val snapshot: DiskCache.Snapshot,
    ) : DiskCache.Snapshot {
        private var finished = false

        init {
            activeHandles++
        }

        override val metadata get() = snapshot.metadata
        override val data get() = snapshot.data

        override fun close() =
            synchronized(lock) {
                if (!finished) {
                    finished = true
                    try {
                        snapshot.close()
                    } finally {
                        activeHandles--
                        applyPendingSize()
                    }
                }
            }

        override fun closeAndOpenEditor(): DiskCache.Editor? =
            synchronized(lock) {
                if (finished) return@synchronized null
                finished = true
                try {
                    snapshot.closeAndOpenEditor()?.let(::Editor)
                } finally {
                    activeHandles--
                    applyPendingSize()
                }
            }
    }

    private inner class Editor(
        private val editor: DiskCache.Editor,
    ) : DiskCache.Editor {
        private var finished = false

        init {
            activeHandles++
        }

        override val metadata get() = editor.metadata
        override val data get() = editor.data

        private fun <T> finish(block: () -> T): T =
            synchronized(lock) {
                check(!finished)
                finished = true
                try {
                    block()
                } finally {
                    activeHandles--
                    applyPendingSize()
                }
            }

        override fun commit() = finish { editor.commit() }

        override fun abort() = finish { editor.abort() }

        override fun commitAndOpenSnapshot(): DiskCache.Snapshot? = finish { editor.commitAndOpenSnapshot()?.let(::Snapshot) }
    }
}
