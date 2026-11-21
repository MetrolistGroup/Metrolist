package com.metrolist.desktop.input

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.linux.ErrNo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private object X11ThreadingState {
    @Volatile var ready = false
}

private data class ServerClockAnchor(
    val wallTimeAtAnchor: Long,
    val serverTimeAtAnchor: Long,
)

internal fun initializeLinuxX11Threading() {
    // XRecord runs on its own thread, so libX11 must enter thread-safe mode before AWT initializes it.
    X11ThreadingState.ready = runCatching { X11.INSTANCE.XInitThreads() != 0 }.getOrDefault(false)
}

internal class LinuxPhysicalKeyCapture : PlatformPhysicalKeyCapture {
    private val lifecycleLock = ReentrantLock()
    private val pending = OrderedPhysicalKeyCaptureBuffer()

    @Volatile private var clockAnchor: ServerClockAnchor? = null

    @Volatile private var replyWallStartMillis = 0L

    @Volatile private var requested = false

    // Each worker has its own generation. Disabling invalidates the current one even if focus returns
    // before its XRecord loop observes the wakeup, so a stale worker can never resume on a disabled context.
    @Volatile private var workerGeneration = 0L

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
                restartOnExit = false
                workerGeneration++
                pending.clear()
                clockAnchor = null
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
        val generation = ++workerGeneration
        val ready = CountDownLatch(1)
        worker =
            Thread(
                {
                    try {
                        captureX11Events(generation, ready)
                    } finally {
                        lifecycleLock.withLock {
                            val shouldRestart = restartOnExit && requested
                            restartOnExit = false
                            if (worker === Thread.currentThread()) worker = null
                            available = false
                            pending.clear()
                            clockAnchor = null
                            replyWallStartMillis = 0L
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

    private fun captureX11Events(
        generation: Long,
        ready: CountDownLatch,
    ) {
        var eventDisplay: Pointer? = null
        var controlDisplay: Pointer? = null
        var context: NativeLong? = null
        var range: Pointer? = null
        try {
            if (!X11ThreadingState.ready || !requested || workerGeneration != generation) return
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
            val activeContext =
                xtst.XRecordCreateContext(
                    activeControlDisplay,
                    X_RECORD_FROM_SERVER_TIME,
                    clients,
                    1,
                    ranges,
                    1,
                )
            if (activeContext.toLong() == 0L) return
            context = activeContext
            x11.XSync(activeControlDisplay, false)
            val enabled =
                lifecycleLock.withLock {
                    if (!requested || workerGeneration != generation) return@withLock false
                    controlDisplay = activeControlDisplay
                    recordContext = activeContext
                    xtst.XRecordEnableContextAsync(activeEventDisplay, activeContext, callback, null) != 0
                }
            if (!enabled) return

            available = true
            ready.countDown()
            x11.XFlush(activeEventDisplay)
            val eventFd = x11.XConnectionNumber(activeEventDisplay)

            @Suppress("UNCHECKED_CAST")
            val pollFds = PollFd().toArray(2) as Array<PollFd>
            pollFds[0].fd = eventFd
            pollFds[0].events = POLLIN.toShort()
            pollFds[1].fd = wakeupPipe[0]
            pollFds[1].events = POLLIN.toShort()
            while (requested && workerGeneration == generation) {
                if (!pollWithRetry(pollFds, generation)) break
                pollFds.forEach { it.read() }
                if (pollFds[1].revents.toInt() != 0) {
                    drainWakeup()
                    continue
                }
                val events = pollFds[0].revents.toInt()
                if (events and (POLLERR or POLLHUP or POLLNVAL) != 0) break
                if (events and POLLIN != 0) {
                    replyWallStartMillis = System.currentTimeMillis()
                    xtst.XRecordProcessReplies(activeEventDisplay)
                }
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
        val buffer = ByteArray(1)
        Posix.INSTANCE.read(wakeupFd, buffer, NativeLong(buffer.size.toLong()))
    }

    private fun pollWithRetry(
        fds: Array<PollFd>,
        generation: Long,
    ): Boolean {
        while (true) {
            fds.forEach { fd ->
                fd.revents = 0
                fd.write()
            }
            val result = Posix.INSTANCE.poll(fds, NativeLong(fds.size.toLong()), -1)
            fds.forEach { it.read() }
            if (result >= 0) return true
            if (!requested || workerGeneration != generation || Native.getLastError() != ErrNo.EINTR) return false
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
                    val serverTime = data.serverTime.toLong() and X_TIME_MASK
                    if (data.category == RECORD_START_OF_DATA) {
                        // StartOfData is the first datum and brackets server time with the local clock
                        // while ProcessReplies is active, so later events can be timestamped without
                        // depending on how long the callback was delayed.
                        val start = replyWallStartMillis
                        val wallNow = System.currentTimeMillis()
                        val wallAtAnchor = if (start > 0L) start + (wallNow - start) / 2 else wallNow
                        clockAnchor = ServerClockAnchor(wallAtAnchor, serverTime)
                        return
                    }

                    val eventData = data.data ?: return
                    if (data.category != RECORD_FROM_SERVER || data.dataLength.toLong() <= 0L) return
                    if (eventData.getByte(0).toInt() and X_EVENT_TYPE_MASK != X_KEY_PRESS) return

                    val xKeyCode = eventData.getByte(1).toInt() and 0xFF
                    if (xKeyCode !in X_MODIFIER_KEYCODES) {
                        pending.record(
                            linuxPhysicalPositionForX11KeyCode(xKeyCode),
                            wallTimeForServerTime(serverTime),
                        )
                    }
                } finally {
                    recordApi?.XRecordFreeData(dataPointer)
                }
            }
        }

    private fun wallTimeForServerTime(serverTime: Long): Long {
        val anchor = clockAnchor ?: return System.currentTimeMillis()
        // X Time is 32-bit and wraps; a signed 32-bit delta stays correct across the wrap.
        val delta = ((serverTime and X_TIME_MASK) - anchor.serverTimeAtAnchor).toInt().toLong()
        return anchor.wallTimeAtAnchor + delta
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
        const val X_RECORD_FROM_SERVER_TIME = 0x1
        const val RECORD_FROM_SERVER = 0
        const val RECORD_START_OF_DATA = 4
        const val X_TIME_MASK = 0xFFFF_FFFFL
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
    fun XInitThreads(): Int

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
internal class PollFd : Structure() {
    @JvmField var fd: Int = 0

    @JvmField var events: Short = 0

    @JvmField var revents: Short = 0
}
