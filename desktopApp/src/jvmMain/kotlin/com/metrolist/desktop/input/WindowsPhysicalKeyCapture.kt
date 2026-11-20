package com.metrolist.desktop.input

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.awt.event.KeyEvent as AwtKeyEvent

internal const val WINDOWS_LOW_LEVEL_EXTENDED_KEY_FLAG = 0x01
private const val PENDING_KEY_CAPTURE_MAX_AGE_NANOS = 5_000_000_000L

internal class WindowsPhysicalKeyCaptureBuffer(
    private val timeSourceNanos: () -> Long = System::nanoTime,
) {
    private data class CapturedKey(
        val scanCode: Int,
        val flags: Int,
        val capturedAtNanos: Long,
    )

    private val capturesByVirtualKey = ConcurrentHashMap<Int, ConcurrentLinkedDeque<CapturedKey>>()

    fun record(
        virtualKeyCode: Int,
        scanCode: Int,
        flags: Int,
    ) {
        if (windowsPhysicalPositionForScanCode(scanCode, flags) == null) return
        val now = timeSourceNanos()
        capturesByVirtualKey
            .computeIfAbsent(virtualKeyCode) { ConcurrentLinkedDeque() }
            .also { captures ->
                pruneExpired(captures, now)
                captures.addLast(CapturedKey(scanCode, flags, now))
            }
    }

    fun consume(awtKeyCode: Int): PhysicalKeyPosition? {
        val virtualKeyCode = windowsVirtualKeyForAwtCode(awtKeyCode)
        val captures = capturesByVirtualKey[virtualKeyCode] ?: return null
        pruneExpired(captures, timeSourceNanos())
        val capturedKey = captures.pollFirst()
        return capturedKey?.let { windowsPhysicalPositionForScanCode(it.scanCode, it.flags) }
    }

    private fun pruneExpired(
        captures: ConcurrentLinkedDeque<CapturedKey>,
        nowNanos: Long,
    ) {
        while (true) {
            val oldest = captures.peekFirst() ?: break
            if (nowNanos - oldest.capturedAtNanos <= PENDING_KEY_CAPTURE_MAX_AGE_NANOS) break
            captures.pollFirst()
        }
    }

    fun clear() = capturesByVirtualKey.clear()
}

internal class WindowsPhysicalKeyCapture : AutoCloseable {
    private val pendingKeyCaptures = WindowsPhysicalKeyCaptureBuffer()

    @Volatile private var hook: WinUser.HHOOK? = null

    @Volatile private var hookThreadId: Int? = null

    @Volatile private var enabled = false

    @Volatile private var hookThread: Thread? = null

    val isAvailable: Boolean get() = hook != null

    private val procedure = WindowsPhysicalKeyHookProcedure(this)

    fun setEnabled(enabled: Boolean) {
        if (enabled == this.enabled) return
        if (enabled && hookThread?.isAlive == true) return
        this.enabled = enabled
        if (enabled) {
            val ready = CountDownLatch(1)
            hookThread =
                Thread(
                    {
                        User32.INSTANCE.PeekMessage(WinUser.MSG(), null, 0, 0, 0)
                        hookThreadId = Kernel32.INSTANCE.GetCurrentThreadId()
                        if (this.enabled) {
                            hook =
                                runCatching {
                                    User32.INSTANCE.SetWindowsHookEx(WinUser.WH_KEYBOARD_LL, procedure, null, 0)
                                }.getOrNull()
                        }
                        if (!this.enabled) {
                            hook?.let(User32.INSTANCE::UnhookWindowsHookEx)
                            hook = null
                        }
                        ready.countDown()
                        if (hook == null) this.enabled = false
                        val message = WinUser.MSG()
                        while (hook != null && User32.INSTANCE.GetMessage(message, null, 0, 0) > 0) {
                            User32.INSTANCE.TranslateMessage(message)
                            User32.INSTANCE.DispatchMessage(message)
                        }
                        hook?.let(User32.INSTANCE::UnhookWindowsHookEx)
                        hook = null
                        hookThreadId = null
                        pendingKeyCaptures.clear()
                        if (hookThread === Thread.currentThread()) hookThread = null
                    },
                    "Metrolist Windows shortcut key capture",
                ).apply {
                    isDaemon = true
                    start()
                }
            if (!ready.await(1, TimeUnit.SECONDS)) setEnabled(false)
        } else {
            hookThreadId?.let { threadId ->
                User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, WPARAM(0), LPARAM(0))
            }
            pendingKeyCaptures.clear()
            val thread = hookThread
            thread?.join(1_000)
            if (thread?.isAlive != true) hookThread = null
        }
    }

    internal fun onHookCallback(
        code: Int,
        message: WPARAM,
        data: LPARAM,
    ): LRESULT {
        if (code >= 0) {
            val messageId = message.toInt()
            val pointer = Pointer(data.toLong())
            val virtualKeyCode = pointer.getInt(0)
            if (messageId == WM_KEYDOWN || messageId == WM_SYSKEYDOWN) {
                if (enabled) {
                    pendingKeyCaptures.record(
                        virtualKeyCode,
                        pointer.getInt(Int.SIZE_BYTES.toLong()),
                        pointer.getInt((Int.SIZE_BYTES * 2).toLong()),
                    )
                }
            }
        }
        return User32.INSTANCE.CallNextHookEx(hook, code, message, data)
    }

    fun consume(awtKeyCode: Int): PhysicalKeyPosition? = pendingKeyCaptures.consume(awtKeyCode)

    override fun close() = setEnabled(false)

    private companion object {
        const val WM_KEYDOWN = 0x0100
        const val WM_KEYUP = 0x0101
        const val WM_SYSKEYDOWN = 0x0104
        const val WM_SYSKEYUP = 0x0105
    }
}

internal class WindowsPhysicalKeyHookProcedure(
    private val capture: WindowsPhysicalKeyCapture,
) : WindowsPhysicalKeyHookProcedureContract {
    override fun callback(
        code: Int,
        message: WPARAM,
        data: LPARAM,
    ): LRESULT = capture.onHookCallback(code, message, data)
}

internal interface WindowsPhysicalKeyHookProcedureContract : WinUser.HOOKPROC {
    fun callback(
        code: Int,
        message: WPARAM,
        data: LPARAM,
    ): LRESULT
}

internal fun windowsVirtualKeyForAwtCode(awtKeyCode: Int): Int =
    when (awtKeyCode) {
        AwtKeyEvent.VK_BACK_QUOTE, AwtKeyEvent.VK_DEAD_GRAVE -> 0xC0
        AwtKeyEvent.VK_MINUS -> 0xBD
        AwtKeyEvent.VK_EQUALS -> 0xBB
        AwtKeyEvent.VK_COMMA -> 0xBC
        AwtKeyEvent.VK_SLASH -> 0xBF
        else -> awtKeyCode
    }
