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

    @Volatile private var controlDisplay: Pointer? = null

    @Volatile private var recordContext: NativeLong? = null

    @Volatile private var closed = false

    private val wakeupPipe = IntArray(2) { -1 }

    init {
        if (Posix.INSTANCE.pipe(wakeupPipe) != 0) {
            wakeupPipe[0] = -1
            wakeupPipe[1] = -1
        }
    }

    override val isAvailable: Boolean get() = available

    override fun setEnabled(enabled: Boolean) {
        if (closed) return
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
        val activeContext = recordContext
        if (currentWorker?.isAlive == true && activeContext != null) {
            // Use a separate connection so this caller never shares Xlib state with the capture thread.
            runCatching {
                val display = X11.INSTANCE.XOpenDisplay(null) ?: return@runCatching
                try {
                    recordApi?.XRecordDisableContext(display, activeContext)
                    X11.INSTANCE.XSync(display, false)
                } finally {
                    X11.INSTANCE.XCloseDisplay(display)
                }
            }
        }
        val wakeupFd = wakeupPipe[1]
        if (currentWorker?.isAlive == true && wakeupFd >= 0) {
            runCatching { Posix.INSTANCE.write(wakeupFd, byteArrayOf(1), NativeLong(1)) }
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
            val activeContext = xtst.XRecordCreateContext(activeControlDisplay, 0, clients, 1, ranges, 1)
            if (activeContext.toLong() == 0L) return
            context = activeContext
            x11.XSync(activeControlDisplay, false)
            val enabled =
                lifecycleLock.withLock {
                    controlDisplay = activeControlDisplay
                    recordContext = activeContext
                    requested && xtst.XRecordEnableContextAsync(activeEventDisplay, activeContext, callback, null) != 0
                }
            if (!enabled) return

            available = true
            ready.countDown()
            x11.XFlush(activeEventDisplay)
            val eventFd = x11.XConnectionNumber(activeEventDisplay)
            val pollFds = arrayOf(PollFd(eventFd, POLLIN), PollFd(wakeupPipe[0], POLLIN))
            while (requested) {
                pollFds[0].revents = 0
                pollFds[1].revents = 0
                if (Posix.INSTANCE.poll(pollFds, NativeLong(pollFds.size.toLong()), -1) < 0) break
                if (pollFds[1].revents.toInt() != 0) {
                    drainWakeup()
                    continue
                }
                val events = pollFds[0].revents.toInt()
                if (events and (POLLERR or POLLHUP or POLLNVAL) != 0) break
                if (events and POLLIN != 0) xtst.XRecordProcessReplies(activeEventDisplay)
            }
        } catch (_: UnsatisfiedLinkError) {
            available = false
        } finally {
            ready.countDown()
            val activeContext = context
            val activeControlDisplay = controlDisplay
            controlDisplay = null
            recordContext = null
            if (activeContext != null && activeControlDisplay != null) {
                runCatching { recordApi?.XRecordFreeContext(activeControlDisplay, activeContext) }
            }
            range?.let { runCatching { X11.INSTANCE.XFree(it) } }
            eventDisplay?.let { runCatching { X11.INSTANCE.XCloseDisplay(it) } }
            activeControlDisplay?.let { runCatching { X11.INSTANCE.XCloseDisplay(it) } }
            recordApi = null
        }
    }

    private fun drainWakeup() {
        val wakeupFd = wakeupPipe[0]
        if (wakeupFd < 0) return
        val buffer = ByteArray(64)
        while (Posix.INSTANCE.read(wakeupFd, buffer, NativeLong(buffer.size.toLong())).toLong() > 0) {
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

    override fun close() {
        if (closed) return
        setEnabled(false)
        lifecycleLock.withLock {
            if (closed) return
            closed = true
            wakeupPipe.forEach { fd ->
                if (fd >= 0) runCatching { Posix.INSTANCE.close(fd) }
            }
        }
    }

    private companion object {
        const val X_RECORD_ALL_CLIENTS = 3L
        const val RECORD_FROM_SERVER = 0
        const val X_KEY_PRESS = 2
        const val X_EVENT_TYPE_MASK = 0x7F
        const val DEVICE_EVENTS_OFFSET = 18L
        const val X_RECORD_RANGE_SIZE = 32L
        const val POLLIN = 0x1
        const val POLLERR = 0x8
        const val POLLHUP = 0x10
        const val POLLNVAL = 0x20
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

    fun XConnectionNumber(display: Pointer): Int

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

    fun XRecordProcessReplies(display: Pointer)

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

private interface Posix : Library {
    fun pipe(fds: IntArray): Int

    fun poll(
        fds: Array<PollFd>,
        count: NativeLong,
        timeout: Int,
    ): Int

    fun read(
        fd: Int,
        buffer: ByteArray,
        count: NativeLong,
    ): NativeLong

    fun write(
        fd: Int,
        buffer: ByteArray,
        count: NativeLong,
    ): NativeLong

    fun close(fd: Int): Int

    companion object {
        val INSTANCE: Posix = Native.load("c", Posix::class.java)
    }
}

@Structure.FieldOrder("fd", "events", "revents")
private class PollFd(
    fd: Int,
    events: Int,
) : Structure() {
    @JvmField val fd: Int = fd

    @JvmField var events: Short = events.toShort()

    @JvmField var revents: Short = 0
}
