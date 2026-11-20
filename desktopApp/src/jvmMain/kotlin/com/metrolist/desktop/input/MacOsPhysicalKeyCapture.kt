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

internal class MacOsPhysicalKeyCapture : PlatformPhysicalKeyCapture {
    private val lifecycleLock = ReentrantLock()
    private val pending = OrderedPhysicalKeyCaptureBuffer()

    @Volatile private var requested = false

    @Volatile private var available = false

    @Volatile private var worker: Thread? = null

    @Volatile private var runLoop: Pointer? = null

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
                runLoop?.let(MacCoreFoundation.INSTANCE::CFRunLoopStop)
                pending.clear()
                worker
            }
        currentWorker?.join(1_000)
        lifecycleLock.withLock {
            if (worker === currentWorker && currentWorker?.isAlive != true) worker = null
        }
    }

    private fun startWorker(): CountDownLatch {
        val ready = CountDownLatch(1)
        worker =
            Thread(
                {
                    try {
                        runEventTap(ready)
                    } finally {
                        lifecycleLock.withLock {
                            val shouldRestart = restartOnExit && requested
                            restartOnExit = false
                            if (worker === Thread.currentThread()) worker = null
                            available = false
                            runLoop = null
                            tap = null
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

    private fun runEventTap(ready: CountDownLatch) {
        var eventTap: Pointer? = null
        var source: Pointer? = null
        try {
            val coreGraphics = MacCoreGraphics.INSTANCE
            val coreFoundation = MacCoreFoundation.INSTANCE
            coreGraphics.CGRequestListenEventAccess()
            if (!requested) return
            eventTap =
                coreGraphics.CGEventTapCreate(
                    SESSION_EVENT_TAP,
                    HEAD_INSERT_EVENT_TAP,
                    LISTEN_ONLY_EVENT_TAP,
                    KEY_DOWN_EVENT_MASK,
                    callback,
                    null,
                ) ?: return
            tap = eventTap
            val currentRunLoop = coreFoundation.CFRunLoopGetCurrent()
            source = coreFoundation.CFMachPortCreateRunLoopSource(null, eventTap, 0L) ?: return
            runLoop = currentRunLoop
            coreFoundation.CFRunLoopAddSource(currentRunLoop, source, COMMON_RUN_LOOP_MODES)
            coreGraphics.CGEventTapEnable(eventTap, true)
            available = true
            ready.countDown()
            coreFoundation.CFRunLoopRun()
        } catch (_: UnsatisfiedLinkError) {
            available = false
        } finally {
            ready.countDown()
            available = false
            runLoop = null
            tap = null
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
                if (type == EVENT_TAP_DISABLED_BY_TIMEOUT || type == EVENT_TAP_DISABLED_BY_USER_INPUT) {
                    if (activeTap != null && requested) MacCoreGraphics.INSTANCE.CGEventTapEnable(activeTap, true)
                } else if (type == KEY_DOWN_EVENT_TYPE && requested && event != null) {
                    val keyCode =
                        MacCoreGraphics.INSTANCE
                            .CGEventGetIntegerValueField(event, KEYBOARD_EVENT_KEYCODE_FIELD)
                            .toInt()
                    pending.record(macOsPhysicalPositionForKeyCode(keyCode))
                }
                return event
            }
        }

    override fun consume(awtEventWhen: Long): CapturedPhysicalKey? = pending.consume(awtEventWhen)

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
        val COMMON_RUN_LOOP_MODES: Pointer =
            NativeLibrary
                .getInstance("CoreFoundation")
                .getGlobalVariableAddress("kCFRunLoopCommonModes")
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

    fun CFRunLoopRun()

    fun CFRunLoopStop(runLoop: Pointer)

    fun CFRelease(value: Pointer)

    companion object {
        val INSTANCE: MacCoreFoundation = Native.load("CoreFoundation", MacCoreFoundation::class.java)
    }
}
