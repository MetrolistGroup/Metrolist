package com.metrolist.desktop.utils

import com.metrolist.shared.utils.XdgUtils
import java.awt.EventQueue
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class SingleInstanceLock(
    private val directory: Path,
    identifier: String,
) {
    private val lockFile = directory.resolve("$identifier.lock")
    private val requestPrefix = "$identifier.restore-"
    private var channel: FileChannel? = null
    private var fileLock: FileLock? = null
    private var watchService: WatchService? = null
    private var watcherThread: Thread? = null
    private val released = AtomicBoolean(true)

    @Synchronized
    fun acquire(
        payload: String? = null,
        onRestoreRequest: (String?) -> Unit,
    ): Boolean {
        Files.createDirectories(directory)
        val candidateChannel =
            FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
        val candidateLock =
            try {
                candidateChannel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
        if (candidateLock == null) {
            candidateChannel.close()
            signalRestoreRequest(payload)
            return false
        }

        channel = candidateChannel
        fileLock = candidateLock
        released.set(false)
        startRestoreWatcher(onRestoreRequest)
        return true
    }

    private fun signalRestoreRequest(payload: String?) {
        val content = payload?.trim()?.takeIf { it.isNotEmpty() } ?: System.nanoTime().toString()
        val temporary = Files.createTempFile(directory, requestPrefix, ".tmp")
        try {
            Files.writeString(temporary, content)
            Files.move(temporary, temporary.resolveSibling("${temporary.fileName}.ready"), ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun consumeRestoreRequests(onRestoreRequest: (String?) -> Unit) {
        Files.newDirectoryStream(directory, "$requestPrefix*.ready").use { requests ->
            for (request in requests) {
                val payload = Files.readString(request).trim()
                Files.deleteIfExists(request)
                runCatching { onRestoreRequest(payload) }
            }
        }
    }

    private fun startRestoreWatcher(onRestoreRequest: (String?) -> Unit) {
        val watcher = FileSystems.getDefault().newWatchService()
        val highSensitivity =
            runCatching {
                val clazz = Class.forName("com.sun.nio.file.SensitivityWatchEventModifier")
                val field = clazz.getField("HIGH")
                field.get(null) as? java.nio.file.WatchEvent.Modifier
            }.getOrNull()
        if (highSensitivity != null) {
            directory.register(watcher, arrayOf(ENTRY_CREATE, ENTRY_MODIFY), highSensitivity)
        } else {
            directory.register(watcher, ENTRY_CREATE, ENTRY_MODIFY)
        }
        watchService = watcher
        watcherThread =
            thread(name = "MetrolistSingleInstance", isDaemon = true) {
                try {
                    while (!released.get()) {
                        consumeRestoreRequests(onRestoreRequest)
                        val key = watcher.take()
                        key.pollEvents()
                        if (!key.reset()) break
                    }
                } catch (_: ClosedWatchServiceException) {
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
    }

    @Synchronized
    fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching { watchService?.close() }
        watcherThread?.interrupt()
        watcherThread = null
        watchService = null
        runCatching { fileLock?.release() }
        fileLock = null
        runCatching { channel?.close() }
        channel = null
    }
}

object DesktopSingleInstance {
    private val lock =
        SingleInstanceLock(
            directory = XdgUtils.getXdgStateHome().toPath().resolve("metrolist"),
            identifier = "metrolist",
        )
    private val pendingPayloads = ConcurrentLinkedQueue<String>()

    @Volatile
    private var restoreHandler: ((String?) -> Unit)? = null

    fun acquire(args: Array<String> = emptyArray()): Boolean {
        val payload = args.firstOrNull { it.isNotBlank() }
        val acquired =
            lock.acquire(payload) { receivedPayload ->
                EventQueue.invokeLater {
                    val handler = restoreHandler
                    if (handler != null) {
                        handler.invoke(receivedPayload)
                    } else {
                        pendingPayloads.add(receivedPayload.orEmpty())
                    }
                }
            }
        if (acquired) {
            Runtime.getRuntime().addShutdownHook(
                thread(start = false, name = "MetrolistInstanceRelease") { release() },
            )
        }
        return acquired
    }

    fun setRestoreHandler(handler: (String?) -> Unit) {
        restoreHandler = handler
        EventQueue.invokeLater {
            while (true) {
                val payload = pendingPayloads.poll() ?: break
                handler(payload.takeIf { it.isNotEmpty() })
            }
        }
    }

    fun setRestoreHandler(handler: () -> Unit) {
        setRestoreHandler { _ -> handler() }
    }

    fun release() {
        restoreHandler = null
        pendingPayloads.clear()
        lock.release()
    }
}
