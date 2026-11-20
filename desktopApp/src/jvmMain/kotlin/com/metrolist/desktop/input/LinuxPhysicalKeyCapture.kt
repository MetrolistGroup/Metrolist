package com.metrolist.desktop.input

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
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
                        pollX11Keymap(ready)
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

    private fun pollX11Keymap(ready: CountDownLatch) {
        var display: Pointer? = null
        try {
            val x11 = X11.INSTANCE
            display = x11.XOpenDisplay(null) ?: return
            val previous = ByteArray(KEYMAP_SIZE)
            val current = ByteArray(KEYMAP_SIZE)
            x11.XQueryKeymap(display, previous)
            available = true
            ready.countDown()
            while (requested) {
                if (x11.XQueryKeymap(display, current) == 0) break
                for (keyCode in MIN_X11_KEYCODE..MAX_X11_KEYCODE) {
                    if (isPressed(current, keyCode) && !isPressed(previous, keyCode)) {
                        pending.record(linuxPhysicalPositionForX11KeyCode(keyCode))
                    }
                }
                current.copyInto(previous)
                try {
                    Thread.sleep(POLL_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } catch (_: UnsatisfiedLinkError) {
            available = false
        } finally {
            ready.countDown()
            display?.let(X11.INSTANCE::XCloseDisplay)
        }
    }

    private fun isPressed(
        keymap: ByteArray,
        keyCode: Int,
    ): Boolean {
        val byteIndex = keyCode ushr 3
        val bitIndex = keyCode and 7
        return keymap[byteIndex].toInt() and (1 shl bitIndex) != 0
    }

    override fun consume(awtKeyCode: Int): PhysicalKeyPosition? = pending.consume()

    override fun close() = setEnabled(false)

    private companion object {
        const val KEYMAP_SIZE = 32
        const val MIN_X11_KEYCODE = 8
        const val MAX_X11_KEYCODE = 255
        const val POLL_INTERVAL_MILLIS = 8L
    }
}

@Suppress("FunctionName")
private interface X11 : Library {
    fun XOpenDisplay(displayName: String?): Pointer?

    fun XCloseDisplay(display: Pointer): Int

    fun XQueryKeymap(
        display: Pointer,
        keysReturn: ByteArray,
    ): Int

    companion object {
        val INSTANCE: X11 = Native.load("X11", X11::class.java)
    }
}
