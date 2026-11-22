package com.metrolist.desktop.input

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.linux.ErrNo
import java.awt.AWTEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.awt.event.KeyEvent as AwtKeyEvent

private object X11ThreadingState {
    @Volatile var ready = false
}

private const val X11_KEY_PRESS_EVENT = 2
private const val X11_EVENT_TYPE_MASK = 0x7F
private const val X11_TIME_MASK = 0xFFFF_FFFFL

private data class NativeKeyEventIdentity(
    val eventTime: Long,
    val keyCode: Int,
)

private object AwtNativeKeyEventReader {
    private val getBData by lazy { runCatching(::createGetBData).getOrNull() }
    private val timeOffset = nativeKeyEventTimeOffset()
    private val keyCodeOffset = timeOffset + Native.LONG_SIZE + KEY_CODE_DELTA

    fun read(event: AwtKeyEvent): NativeKeyEventIdentity? {
        val accessor = getBData ?: return null
        val data = runCatching { accessor(event) }.getOrNull() ?: return null
        if (data.size < keyCodeOffset + Int.SIZE_BYTES) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder())
        if (buffer.getInt(0) and X11_EVENT_TYPE_MASK != X11_KEY_PRESS_EVENT) return null
        val rawTime =
            if (Native.LONG_SIZE == Long.SIZE_BYTES) {
                buffer.getLong(timeOffset)
            } else {
                buffer.getInt(timeOffset).toLong()
            }
        val eventTime = rawTime and X11_TIME_MASK
        val keyCode = buffer.getInt(keyCodeOffset) and 0xFF
        return NativeKeyEventIdentity(eventTime, keyCode)
    }

    private fun createGetBData(): ((AwtKeyEvent) -> ByteArray?)? {
        val awtModule = AwtKeyEvent::class.java.module
        val callerModule = LinuxPhysicalKeyCapture::class.java.module
        if (!awtModule.isOpen("sun.awt", callerModule) && !awtModule.isExported("sun.awt", callerModule)) return null
        val accessorMethod = Class.forName("sun.awt.AWTAccessor").getMethod("getAWTEventAccessor")
        if (!accessorMethod.trySetAccessible()) return null
        val accessor = runCatching { accessorMethod.invoke(null) }.getOrNull() ?: return null
        val dataMethod = Class.forName("sun.awt.AWTAccessor\$AWTEventAccessor").getMethod("getBData", AWTEvent::class.java)
        if (!dataMethod.trySetAccessible()) return null
        return { event -> runCatching { dataMethod.invoke(accessor, event) as? ByteArray }.getOrNull() }
    }

    // XKeyEvent: type, serial, send_event, display, window, root, subwindow, Time, x, y, x_root, y_root, state, keycode.
    private fun nativeKeyEventTimeOffset(): Int {
        val pointerSize = Native.LONG_SIZE
        val serialOffset = alignTo(Int.SIZE_BYTES, pointerSize)
        val sendEventOffset = serialOffset + pointerSize
        val displayOffset = alignTo(sendEventOffset + Int.SIZE_BYTES, pointerSize)
        return displayOffset + pointerSize * 4
    }

    private fun alignTo(
        value: Int,
        alignment: Int,
    ): Int = (value + alignment - 1) / alignment * alignment

    private const val KEY_CODE_DELTA = 20
}

internal fun initializeLinuxX11Threading() {
    // XRecord runs on its own thread, so libX11 must enter thread-safe mode before AWT initializes it.
    X11ThreadingState.ready = runCatching { X11.INSTANCE.XInitThreads() != 0 }.getOrDefault(false)
}

internal class LinuxPhysicalKeyCapture : PlatformPhysicalKeyCapture {
    private val lifecycleLock = ReentrantLock()
    private val pending = OrderedPhysicalKeyCaptureBuffer(timeSourceMillis = { monotonicMillis() })

    @Volatile private var suppressedModifierKeycodes: Set<Int> = emptySet()

    @Volatile private var modifierMappingReady = false

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
                suppressedModifierKeycodes = emptySet()
                modifierMappingReady = false
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
                            suppressedModifierKeycodes = emptySet()
                            modifierMappingReady = false
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
        var controlDisplayForCleanup: Pointer? = null
        var context: NativeLong? = null
        var range: Pointer? = null
        try {
            if (!X11ThreadingState.ready || !requested || workerGeneration != generation) return
            val x11 = X11.INSTANCE
            val xtst = Native.load("Xtst", XTestRecord::class.java)
            recordApi = xtst
            val activeEventDisplay = x11.XOpenDisplay(null) ?: return
            eventDisplay = activeEventDisplay
            val openedControlDisplay = x11.XOpenDisplay(null) ?: return
            controlDisplayForCleanup = openedControlDisplay
            range = xtst.XRecordAllocRange() ?: return
            range.clear(X_RECORD_RANGE_SIZE)
            // Range members are byte ranges in Xlib's 32-byte XRecordRange structure.
            range.setByte(DELIVERED_EVENTS_OFFSET, X_MAPPING_NOTIFY.toByte())
            range.setByte(DELIVERED_EVENTS_OFFSET + 1, X_MAPPING_NOTIFY.toByte())
            range.setByte(DEVICE_EVENTS_OFFSET, X_KEY_PRESS.toByte())
            range.setByte(DEVICE_EVENTS_OFFSET + 1, X_KEY_PRESS.toByte())

            val clients = Memory(Native.LONG_SIZE.toLong())
            clients.setNativeLong(0, NativeLong(X_RECORD_ALL_CLIENTS))
            val ranges = Memory(Native.POINTER_SIZE.toLong())
            ranges.setPointer(0, range)
            val activeContext =
                xtst.XRecordCreateContext(
                    openedControlDisplay,
                    0,
                    clients,
                    1,
                    ranges,
                    1,
                )
            if (activeContext.toLong() == 0L) return
            context = activeContext
            x11.XSync(openedControlDisplay, false)
            val enabled =
                lifecycleLock.withLock {
                    if (!requested || workerGeneration != generation) return@withLock false
                    this.controlDisplay = openedControlDisplay
                    recordContext = activeContext
                    xtst.XRecordEnableContextAsync(activeEventDisplay, activeContext, callback, null) != 0
                }
            if (!enabled) return

            x11.XFlush(activeEventDisplay)
            var nextModifierRefreshAt = monotonicMillis()
            if (refreshModifierKeycodes(openedControlDisplay)) {
                nextModifierRefreshAt = monotonicMillis() + MODIFIER_REFRESH_INTERVAL_MILLIS
            } else {
                nextModifierRefreshAt = monotonicMillis() + REFRESH_RETRY_MILLIS
            }
            // Enable is on the wire and the modifier map is known, so the dispatcher can rely on
            // captured positions instead of falling back for the first symbol shortcut.
            if (modifierMappingReady) available = true
            ready.countDown()
            val eventFd = x11.XConnectionNumber(activeEventDisplay)

            @Suppress("UNCHECKED_CAST")
            val pollFds = PollFd().toArray(2) as Array<PollFd>
            pollFds[0].fd = eventFd
            pollFds[0].events = POLLIN.toShort()
            pollFds[1].fd = wakeupPipe[0]
            pollFds[1].events = POLLIN.toShort()
            while (requested && workerGeneration == generation) {
                if (!pollWithRetry(pollFds, generation, MAX_POLL_WAIT_MILLIS)) break
                pollFds.forEach { it.read() }
                if (pollFds[1].revents.toInt() != 0) {
                    drainWakeup()
                    continue
                }
                val events = pollFds[0].revents.toInt()
                if (events and (POLLERR or POLLHUP or POLLNVAL) != 0) break
                if (events and POLLIN != 0) {
                    xtst.XRecordProcessReplies(activeEventDisplay)
                }
                val loopMonotonic = monotonicMillis()
                if (loopMonotonic >= nextModifierRefreshAt) {
                    nextModifierRefreshAt =
                        loopMonotonic +
                        if (refreshModifierKeycodes(openedControlDisplay)) {
                            MODIFIER_REFRESH_INTERVAL_MILLIS
                        } else {
                            REFRESH_RETRY_MILLIS
                        }
                }
                if (!available && requested && workerGeneration == generation && modifierMappingReady) {
                    available = true
                }
            }
        } catch (_: UnsatisfiedLinkError) {
            available = false
        } finally {
            ready.countDown()
            val activeContext = context
            lifecycleLock.withLock {
                if (this.controlDisplay === controlDisplayForCleanup) this.controlDisplay = null
                if (recordContext === activeContext) recordContext = null
            }
            if (activeContext != null && controlDisplayForCleanup != null) {
                runCatching { recordApi?.XRecordFreeContext(controlDisplayForCleanup, activeContext) }
            }
            range?.let { runCatching { X11.INSTANCE.XFree(it) } }
            eventDisplay?.let { runCatching { X11.INSTANCE.XCloseDisplay(it) } }
            controlDisplayForCleanup?.let { runCatching { X11.INSTANCE.XCloseDisplay(it) } }
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
        timeoutMillis: Int,
    ): Boolean {
        while (true) {
            fds.forEach { fd ->
                fd.revents = 0
                fd.write()
            }
            val result = Posix.INSTANCE.poll(fds, NativeLong(fds.size.toLong()), timeoutMillis)
            fds.forEach { it.read() }
            if (result >= 0) return true
            if (!requested || workerGeneration != generation || Native.getLastError() != ErrNo.EINTR) return false
        }
    }

    private fun monotonicMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())

    private fun refreshModifierKeycodes(display: Pointer): Boolean {
        val mapping = X11.INSTANCE.XGetModifierMapping(display) ?: return false
        try {
            val maxKeysPerModifier = mapping.getInt(0)
            if (maxKeysPerModifier <= 0 || maxKeysPerModifier > MAX_KEYS_PER_MODIFIER) return false
            val map = mapping.getPointer(Native.POINTER_SIZE.toLong()) ?: return false
            val keycodes = HashSet<Int>()
            repeat(MODIFIER_COUNT * maxKeysPerModifier) { index ->
                val keycode = map.getByte(index.toLong()).toInt() and 0xFF
                if (keycode != 0 && isAwtModifierKeycode(display, keycode)) keycodes.add(keycode)
            }
            suppressedModifierKeycodes = keycodes
            modifierMappingReady = true
            return true
        } finally {
            X11.INSTANCE.XFreeModifiermap(mapping)
        }
    }

    private fun isAwtModifierKeycode(
        display: Pointer,
        keycode: Int,
    ): Boolean =
        (0 until KEYSYMS_PER_KEYCODE).any { index ->
            val keysym = X11.INSTANCE.XKeycodeToKeysym(display, keycode, index).toLong() and X11_TIME_MASK
            keysym in AWT_MODIFIER_KEYSYMS
        }

    private val callback =
        object : XRecordInterceptProc {
            override fun invoke(
                closure: Pointer?,
                dataPointer: Pointer?,
            ) {
                if (dataPointer == null) return
                val recorder = recordApi ?: return
                try {
                    if (!requested) return
                    val data = XRecordInterceptData(dataPointer)
                    data.read()
                    if (data.category != RECORD_FROM_SERVER || data.dataLength.toLong() < MIN_DATUM_LENGTH) return
                    val eventData = data.data ?: return

                    when (eventData.getByte(0).toInt() and X_EVENT_TYPE_MASK) {
                        X_MAPPING_NOTIFY -> {
                            controlDisplay?.let(::refreshModifierKeycodes)
                        }

                        X_KEY_PRESS -> {
                            if (!modifierMappingReady) return
                            val xKeyCode = eventData.getByte(1).toInt() and 0xFF
                            if (xKeyCode in suppressedModifierKeycodes) return
                            // XRecord reports the event's server timestamp in the wire event, not in serverTime.
                            val eventTime = eventData.getInt(WIRE_EVENT_TIME_OFFSET.toLong()).toLong() and X11_TIME_MASK
                            // AWT reports the same native timestamp and keycode in its key event.
                            // Unmapped presses stay in the buffer as null markers.
                            pending.record(linuxPhysicalPositionForX11KeyCode(xKeyCode), eventTime, xKeyCode)
                        }
                    }
                } finally {
                    recorder.XRecordFreeData(dataPointer)
                }
            }
        }

    override fun consume(event: AwtKeyEvent): CapturedPhysicalKey? {
        // XToolkit sets KeyEvent.when when it processes the event, not when the X server generated it.
        // The native event stores the server timestamp, which is the identity shared with XRecord.
        val identity = AwtNativeKeyEventReader.read(event) ?: return null
        return pending.consume(identity.eventTime, identity.keyCode)
    }

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
        const val X_KEY_PRESS = X11_KEY_PRESS_EVENT
        const val X_MAPPING_NOTIFY = 34
        const val X_EVENT_TYPE_MASK = X11_EVENT_TYPE_MASK
        const val DELIVERED_EVENTS_OFFSET = 16L
        const val DEVICE_EVENTS_OFFSET = 18L
        const val X_RECORD_RANGE_SIZE = 32L

        // XRecord reports data lengths in 4-byte units; core events occupy 32 bytes.
        const val MIN_DATUM_LENGTH = 8L
        const val WIRE_EVENT_TIME_OFFSET = 4
        const val MODIFIER_COUNT = 8
        const val MAX_KEYS_PER_MODIFIER = 255
        const val KEYSYMS_PER_KEYCODE = 4
        const val MODIFIER_REFRESH_INTERVAL_MILLIS = 5_000L
        const val REFRESH_RETRY_MILLIS = 500L
        const val MAX_POLL_WAIT_MILLIS = 1_000
        const val POLLIN = 0x1
        const val POLLERR = 0x8
        const val POLLHUP = 0x10
        const val POLLNVAL = 0x20

        val AWT_MODIFIER_KEYSYMS =
            setOf(
                0xffe1L, // Shift_L
                0xffe2L, // Shift_R
                0xffe3L, // Control_L
                0xffe4L, // Control_R
                0xffe5L, // Caps_Lock
                0xffe6L, // Shift_Lock
                0xffe7L, // Meta_L
                0xffe8L, // Meta_R
                0xffe9L, // Alt_L
                0xffeaL, // Alt_R
                0xffebL, // Super_L
                0xffecL, // Super_R
                0xffedL, // Hyper_L
                0xffeeL, // Hyper_R
                0xfe03L, // ISO_Level3_Shift / AltGraph
                0xff7eL, // Mode_switch / AltGraph
            )
    }
}

internal interface XRecordInterceptProc : Callback {
    fun invoke(
        closure: Pointer?,
        dataPointer: Pointer?,
    )
}

@Structure.FieldOrder("idBase", "serverTime", "clientSequence", "category", "clientSwapped", "data", "dataLength")
internal class XRecordInterceptData(
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
internal interface X11 : Library {
    fun XInitThreads(): Int

    fun XOpenDisplay(displayName: String?): Pointer?

    fun XConnectionNumber(display: Pointer): Int

    fun XCloseDisplay(display: Pointer): Int

    fun XFlush(display: Pointer): Int

    fun XSync(
        display: Pointer,
        discard: Boolean,
    ): Int

    fun XGetModifierMapping(display: Pointer): Pointer?

    fun XKeycodeToKeysym(
        display: Pointer,
        keyCode: Int,
        index: Int,
    ): NativeLong

    fun XFreeModifiermap(modifiermap: Pointer): Int

    fun XFree(data: Pointer): Int

    companion object {
        val INSTANCE: X11 = Native.load("X11", X11::class.java)
    }
}

@Suppress("FunctionName")
internal interface XTestRecord : Library {
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

internal interface Posix : Library {
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
