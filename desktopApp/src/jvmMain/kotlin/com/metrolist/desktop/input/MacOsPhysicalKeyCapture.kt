package com.metrolist.desktop.input

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.awt.event.KeyEvent as AwtKeyEvent

internal class MacOsPhysicalKeyCapture : PlatformPhysicalKeyCapture {
    private val lifecycleLock = ReentrantLock()
    private val pending = OrderedPhysicalKeyCaptureBuffer()

    @Volatile private var requested = false

    // A disable invalidates the current worker even if focus returns before its bounded run exits.
    @Volatile private var workerGeneration = 0L

    @Volatile private var activeGeneration = 0L

    @Volatile private var available = false

    @Volatile private var worker: Thread? = null

    @Volatile private var tap: Pointer? = null

    private var restartOnExit = false

    override val isAvailable: Boolean get() = available

    override fun setEnabled(enabled: Boolean) {
        if (enabled) {
            val ready =
                lifecycleLock.withLock {
                    val currentWorker = worker
                    if (currentWorker?.isAlive == true && !requested) restartOnExit = true
                    requested = true
                    if (currentWorker?.isAlive == true) null else startWorker()
                }
            ready?.await(1, TimeUnit.SECONDS)
            return
        }

        val currentWorker =
            lifecycleLock.withLock {
                requested = false
                restartOnExit = false
                workerGeneration++
                activeGeneration = 0L
                available = false
                tap = null
                pending.clear()
                worker
            }
        currentWorker?.join(1_000)
        lifecycleLock.withLock {
            if (worker === currentWorker && currentWorker?.isAlive != true) worker = null
        }
    }

    private fun startWorker(): CountDownLatch {
        val generation = ++workerGeneration
        val ready = CountDownLatch(1)
        worker =
            Thread(
                {
                    try {
                        runEventTap(generation, ready)
                    } finally {
                        lifecycleLock.withLock {
                            val shouldRestart = restartOnExit && requested
                            restartOnExit = false
                            if (worker === Thread.currentThread()) worker = null
                            available = false
                            pending.clear()
                            if (shouldRestart && worker == null) startWorker()
                        }
                    }
                },
                "Metrolist macOS shortcut key capture",
            ).apply {
                isDaemon = true
                start()
            }
        return ready
    }

    private fun runEventTap(
        generation: Long,
        ready: CountDownLatch,
    ) {
        var eventTap: Pointer? = null
        var source: Pointer? = null
        try {
            val coreGraphics = MacCoreGraphics.INSTANCE
            val coreFoundation = MacCoreFoundation.INSTANCE
            coreGraphics.CGRequestListenEventAccess()
            if (!requested || workerGeneration != generation) return
            val activeTap =
                coreGraphics.CGEventTapCreate(
                    SESSION_EVENT_TAP,
                    HEAD_INSERT_EVENT_TAP,
                    LISTEN_ONLY_EVENT_TAP,
                    KEY_DOWN_EVENT_MASK,
                    callback,
                    null,
                ) ?: return
            eventTap = activeTap
            val currentRunLoop = coreFoundation.CFRunLoopGetCurrent()
            val activeSource = coreFoundation.CFMachPortCreateRunLoopSource(null, activeTap, 0L) ?: return
            source = activeSource
            val activated =
                lifecycleLock.withLock {
                    if (!requested || workerGeneration != generation) {
                        false
                    } else {
                        coreFoundation.CFRunLoopAddSource(currentRunLoop, activeSource, COMMON_RUN_LOOP_MODES)
                        coreGraphics.CGEventTapEnable(activeTap, true)
                        tap = activeTap
                        activeGeneration = generation
                        available = true
                        true
                    }
                }
            ready.countDown()
            if (!activated) return
            // A bounded run keeps a deactivation that races loop startup from parking the worker forever.
            while (requested && workerGeneration == generation) {
                coreFoundation.CFRunLoopRunInMode(DEFAULT_RUN_LOOP_MODE, RUN_LOOP_POLL_SECONDS, false)
            }
        } catch (_: UnsatisfiedLinkError) {
            available = false
        } finally {
            ready.countDown()
            lifecycleLock.withLock {
                available = false
                if (tap === eventTap) {
                    tap = null
                    activeGeneration = 0L
                }
            }
            source?.let(MacCoreFoundation.INSTANCE::CFRelease)
            eventTap?.let(MacCoreFoundation.INSTANCE::CFRelease)
        }
    }

    private val callback =
        object : MacEventTapCallback {
            override fun invoke(
                proxy: Pointer?,
                type: Int,
                event: Pointer?,
                userInfo: Pointer?,
            ): Pointer? {
                val activeTap = tap
                if (activeTap == null || activeGeneration != workerGeneration) return event
                if (type == EVENT_TAP_DISABLED_BY_TIMEOUT || type == EVENT_TAP_DISABLED_BY_USER_INPUT) {
                    MacCoreGraphics.INSTANCE.CGEventTapEnable(activeTap, true)
                } else if (type == KEY_DOWN_EVENT_TYPE && event != null) {
                    val keyCode =
                        MacCoreGraphics.INSTANCE
                            .CGEventGetIntegerValueField(event, KEYBOARD_EVENT_KEYCODE_FIELD)
                            .toInt()
                    pending.record(macOsPhysicalPositionForKeyCode(keyCode))
                }
                return event
            }
        }

    override fun consume(event: AwtKeyEvent): CapturedPhysicalKey? = pending.consume(event.`when`)

    override fun close() = setEnabled(false)

    private companion object {
        const val SESSION_EVENT_TAP = 1
        const val HEAD_INSERT_EVENT_TAP = 0
        const val LISTEN_ONLY_EVENT_TAP = 1
        const val KEY_DOWN_EVENT_TYPE = 10
        const val KEYBOARD_EVENT_KEYCODE_FIELD = 9
        const val EVENT_TAP_DISABLED_BY_TIMEOUT = -2
        const val EVENT_TAP_DISABLED_BY_USER_INPUT = -1
        const val KEY_DOWN_EVENT_MASK = 1L shl KEY_DOWN_EVENT_TYPE
        const val RUN_LOOP_POLL_SECONDS = 0.1
        val COMMON_RUN_LOOP_MODES: Pointer =
            NativeLibrary
                .getInstance("CoreFoundation")
                .getGlobalVariableAddress("kCFRunLoopCommonModes")
                .getPointer(0)
        val DEFAULT_RUN_LOOP_MODE: Pointer =
            NativeLibrary
                .getInstance("CoreFoundation")
                .getGlobalVariableAddress("kCFRunLoopDefaultMode")
                .getPointer(0)
    }
}

private interface MacEventTapCallback : Callback {
    fun invoke(
        proxy: Pointer?,
        type: Int,
        event: Pointer?,
        userInfo: Pointer?,
    ): Pointer?
}

@Suppress("FunctionName")
private interface MacCoreGraphics : Library {
    fun CGRequestListenEventAccess(): Byte

    fun CGEventTapCreate(
        tap: Int,
        place: Int,
        options: Int,
        eventsOfInterest: Long,
        callback: MacEventTapCallback,
        userInfo: Pointer?,
    ): Pointer?

    fun CGEventTapEnable(
        tap: Pointer,
        enable: Boolean,
    )

    fun CGEventGetIntegerValueField(
        event: Pointer,
        field: Int,
    ): Long

    companion object {
        val INSTANCE: MacCoreGraphics = Native.load("CoreGraphics", MacCoreGraphics::class.java)
    }
}

@Suppress("FunctionName")
private interface MacCoreFoundation : Library {
    fun CFMachPortCreateRunLoopSource(
        allocator: Pointer?,
        port: Pointer,
        order: Long,
    ): Pointer?

    fun CFRunLoopGetCurrent(): Pointer

    fun CFRunLoopAddSource(
        runLoop: Pointer,
        source: Pointer,
        mode: Pointer,
    )

    fun CFRunLoopRunInMode(
        mode: Pointer,
        seconds: Double,
        returnAfterSourceHandled: Boolean,
    ): Int

    fun CFRelease(value: Pointer)

    companion object {
        val INSTANCE: MacCoreFoundation = Native.load("CoreFoundation", MacCoreFoundation::class.java)
    }
}
