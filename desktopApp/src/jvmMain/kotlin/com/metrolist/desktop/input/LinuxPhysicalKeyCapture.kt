package com.metrolist.desktop.input

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class LinuxPhysicalKeyCapture : PlatformPhysicalKeyCapture {
    private val lifecycleLock = ReentrantLock()
    private val pending = OrderedPhysicalKeyCaptureBuffer()

    @Volatile private var requested = false

    @Volatile private var available = false

    @Volatile private var worker: Thread? = null

    private var restartOnExit = false

    @Volatile private var recordApi: XTestRecord? = null

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
                        captureX11Events(ready)
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
                "Metrolist Linux shortcut key capture",
            ).apply {
                isDaemon = true
                start()
            }
        return ready
    }

    private fun captureX11Events(ready: CountDownLatch) {
        var eventDisplay: Pointer? = null
        var controlDisplay: Pointer? = null
        var context: NativeLong? = null
        var range: Pointer? = null
        try {
            val x11 = X11.INSTANCE
            val xtst = Native.load("Xtst", XTestRecord::class.java)
            recordApi = xtst
            val activeEventDisplay = x11.XOpenDisplay(null) ?: return
            eventDisplay = activeEventDisplay
            val activeControlDisplay = x11.XOpenDisplay(null) ?: return
            controlDisplay = activeControlDisplay
            range = xtst.XRecordAllocRange() ?: return
            range.clear(X_RECORD_RANGE_SIZE)
            // XRecordRange.device_events is the two-byte range at offset 18 in Xlib's 32-byte structure.
            range.setByte(DEVICE_EVENTS_OFFSET, X_KEY_PRESS.toByte())
            range.setByte(DEVICE_EVENTS_OFFSET + 1, X_KEY_PRESS.toByte())

            val clients = Memory(Native.LONG_SIZE.toLong())
            clients.setNativeLong(0, NativeLong(X_RECORD_ALL_CLIENTS))
            val ranges = Memory(Native.POINTER_SIZE.toLong())
            ranges.setPointer(0, range)
            val recordContext = xtst.XRecordCreateContext(activeControlDisplay, 0, clients, 1, ranges, 1)
            if (recordContext.toLong() == 0L) return
            context = recordContext
            x11.XSync(activeControlDisplay, false)
            if (xtst.XRecordEnableContextAsync(activeEventDisplay, recordContext, callback, null) == 0) return

            available = true
            ready.countDown()
            x11.XFlush(activeEventDisplay)
            while (requested) {
                xtst.XRecordProcessReplies(activeEventDisplay)
                try {
                    Thread.sleep(REPLY_POLL_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } catch (_: UnsatisfiedLinkError) {
            available = false
        } finally {
            ready.countDown()
            val activeContext = context
            eventDisplay?.let { display ->
                activeContext?.let { recordContext ->
                    runCatching { recordApi?.XRecordDisableContext(display, recordContext) }
                }
            }
            controlDisplay?.let { display ->
                activeContext?.let { recordContext ->
                    runCatching { recordApi?.XRecordFreeContext(display, recordContext) }
                }
            }
            range?.let { runCatching { X11.INSTANCE.XFree(it) } }
            eventDisplay?.let { runCatching { X11.INSTANCE.XCloseDisplay(it) } }
            controlDisplay?.let { runCatching { X11.INSTANCE.XCloseDisplay(it) } }
            recordApi = null
        }
    }

    private val callback =
        object : XRecordInterceptProc {
            override fun invoke(
                closure: Pointer?,
                dataPointer: Pointer?,
            ) {
                if (dataPointer == null) return
                try {
                    val data = XRecordInterceptData(dataPointer)
                    data.read()
                    val eventData = data.data ?: return
                    if (data.category != RECORD_FROM_SERVER || data.dataLength.toLong() <= 0L) return
                    if (eventData.getByte(0).toInt() and X_EVENT_TYPE_MASK != X_KEY_PRESS) return

                    val xKeyCode = eventData.getByte(1).toInt() and 0xFF
                    if (xKeyCode !in X_MODIFIER_KEYCODES) {
                        pending.record(linuxPhysicalPositionForX11KeyCode(xKeyCode))
                    }
                } finally {
                    recordApi?.XRecordFreeData(dataPointer)
                }
            }
        }

    override fun consume(awtEventWhen: Long): CapturedPhysicalKey? = pending.consume(awtEventWhen)

    override fun close() = setEnabled(false)

    private companion object {
        const val X_RECORD_ALL_CLIENTS = 3L
        const val RECORD_FROM_SERVER = 0
        const val X_KEY_PRESS = 2
        const val X_EVENT_TYPE_MASK = 0x7F
        const val DEVICE_EVENTS_OFFSET = 18L
        const val X_RECORD_RANGE_SIZE = 32L
        const val REPLY_POLL_INTERVAL_MILLIS = 5L
        val X_MODIFIER_KEYCODES = setOf(37, 50, 62, 64, 66, 92, 105, 108, 133, 134)
    }
}

private interface XRecordInterceptProc : Callback {
    fun invoke(
        closure: Pointer?,
        dataPointer: Pointer?,
    )
}

@Structure.FieldOrder("idBase", "serverTime", "clientSequence", "category", "clientSwapped", "data", "dataLength")
private class XRecordInterceptData(
    pointer: Pointer,
) : Structure(pointer) {
    @JvmField var idBase: NativeLong = NativeLong(0)

    @JvmField var serverTime: NativeLong = NativeLong(0)

    @JvmField var clientSequence: NativeLong = NativeLong(0)

    @JvmField var category: Int = 0

    @JvmField var clientSwapped: Int = 0

    @JvmField var data: Pointer? = null

    @JvmField var dataLength: NativeLong = NativeLong(0)
}

@Suppress("FunctionName")
private interface X11 : Library {
    fun XOpenDisplay(displayName: String?): Pointer?

    fun XCloseDisplay(display: Pointer): Int

    fun XFlush(display: Pointer): Int

    fun XSync(
        display: Pointer,
        discard: Boolean,
    ): Int

    fun XFree(data: Pointer): Int

    companion object {
        val INSTANCE: X11 = Native.load("X11", X11::class.java)
    }
}

@Suppress("FunctionName")
private interface XTestRecord : Library {
    fun XRecordAllocRange(): Pointer?

    fun XRecordCreateContext(
        display: Pointer,
        datumFlags: Int,
        clients: Pointer,
        clientCount: Int,
        ranges: Pointer,
        rangeCount: Int,
    ): NativeLong

    fun XRecordEnableContextAsync(
        display: Pointer,
        context: NativeLong,
        callback: XRecordInterceptProc,
        closure: Pointer?,
    ): Int

    fun XRecordProcessReplies(display: Pointer)

    fun XRecordDisableContext(
        display: Pointer,
        context: NativeLong,
    ): Int

    fun XRecordFreeContext(
        display: Pointer,
        context: NativeLong,
    ): Int

    fun XRecordFreeData(data: Pointer)
}
