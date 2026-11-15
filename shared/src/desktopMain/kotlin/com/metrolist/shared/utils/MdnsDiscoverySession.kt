package com.metrolist.shared.utils

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class MdnsDiscoverySession<A, T : Closeable>(
    private val addresses: () -> List<A>,
    private val create: (A) -> T,
    private val configure: (T) -> Unit,
    private val onReset: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onClosed: () -> Unit = {},
    private val beforeClose: (T) -> Unit = {},
    private val lifetimeMillis: Long = TimeUnit.MINUTES.toMillis(10),
) : Closeable {
    private val active = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)
    private val closeLock = Any()
    private var closing: Future<*>? = null
    val isStopping: Boolean get() = stopping.get()
    private val resources = mutableListOf<T>()

    // JmDNS's cancellation tasks need its object monitor while close waits for them.
    private val resourceLocks = ConcurrentHashMap<T, Any>()
    private var renewal: ScheduledFuture<*>? = null
    private val group =
        object : ThreadGroup("Metrolist-mDNS") {
            override fun uncaughtException(
                thread: Thread,
                error: Throwable,
            ) {
                // JmDNS creates socket and timer threads. Keep their failures out of the app's fatal handler.
                if (active.getAndSet(false)) executor.execute { fail(error) }
            }
        }
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(group, task, "Metrolist-mDNS-owner").apply { isDaemon = true }
        }

    fun start(waitUntilStarted: Boolean = false) {
        val started =
            synchronized(closeLock) {
                if (stopping.get() || !active.compareAndSet(false, true)) return
                executor.submit {
                    try {
                        openResources()
                        // Bound the lifetime of JmDNS's DNS cache and persistent resolver state.
                        renewal = executor.scheduleWithFixedDelay(::renew, lifetimeMillis, lifetimeMillis, TimeUnit.MILLISECONDS)
                    } catch (error: Throwable) {
                        fail(error)
                    }
                }
            }
        if (waitUntilStarted) started.get()
    }

    private fun openResources() {
        addresses().distinct().forEach { address ->
            if (!active.get()) return
            val resource =
                try {
                    create(address)
                } catch (error: Exception) {
                    onError(error)
                    return@forEach
                }
            resources += resource
            resourceLocks[resource] = Any()
            if (!active.get()) return
            configure(resource)
        }
        check(!active.get() || resources.isNotEmpty()) { "mDNS has no active LAN interface" }
    }

    private fun renew() {
        if (!active.get()) return
        try {
            closeResources()
            onReset()
            if (active.get()) openResources()
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun fail(error: Throwable) {
        if (!failureReported.compareAndSet(false, true)) return
        stopping.set(true)
        active.set(false)
        renewal?.cancel(false)
        runCatching { closeResources() }.exceptionOrNull()?.let { if (it !== error) error.addSuppressed(it) }
        onReset()
        onError(error)
    }

    private fun closeResources() {
        val pending =
            resources.map { resource ->
                val task =
                    FutureTask {
                        synchronized(checkNotNull(resourceLocks[resource])) {
                            try {
                                beforeClose(resource)
                            } finally {
                                resource.close()
                            }
                            resourceLocks.remove(resource)
                        }
                    }
                Thread(group, task, "Metrolist-mDNS-close").apply { isDaemon = true }.start()
                resource to task
            }
        var failure: Throwable? = null
        pending.forEach { (resource, task) ->
            try {
                task.get()
                resources.remove(resource)
            } catch (error: ExecutionException) {
                val cause = error.cause ?: error
                if (failure == null) {
                    failure = cause
                } else if (failure !== cause) {
                    failure?.addSuppressed(cause)
                }
            }
        }
        failure?.let { throw it }
    }

    fun <R> withResource(
        resource: T,
        action: () -> R,
    ): R? {
        val lock = resourceLocks[resource] ?: return null
        synchronized(lock) {
            if (resourceLocks[resource] !== lock) return null
            return action()
        }
    }

    override fun close() {
        val pending =
            synchronized(closeLock) {
                stopping.set(true)
                active.set(false)
                renewal?.cancel(false)
                closing ?: executor
                    .submit {
                        closeResources()
                        onReset()
                        onClosed()
                        executor.shutdown()
                    }.also { closing = it }
            }
        try {
            pending.get(5, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            synchronized(closeLock) { if (closing === pending) closing = null }
            throw error.cause ?: error
        }
    }
}
