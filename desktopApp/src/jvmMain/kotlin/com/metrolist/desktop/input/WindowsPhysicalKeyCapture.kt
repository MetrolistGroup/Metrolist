package com.metrolist.desktop.input

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.awt.event.KeyEvent as AwtKeyEvent

internal const val WINDOWS_LOW_LEVEL_EXTENDED_KEY_FLAG = 0x01

internal class WindowsPhysicalKeyCapture : PlatformPhysicalKeyCapture {
    private val pendingKeyCaptures = OrderedPhysicalKeyCaptureBuffer()
    private val lifecycleLock = ReentrantLock()

    @Volatile private var hook: WinUser.HHOOK? = null

    @Volatile private var hookThreadId: Int? = null

    @Volatile private var enabled = false

    @Volatile private var hookThread: Thread? = null

    override val isAvailable: Boolean get() = hook != null

    private val procedure = WindowsPhysicalKeyHookProcedure(this)

    override fun setEnabled(enabled: Boolean) {
        if (enabled) {
            val ready =
                lifecycleLock.withLock {
                    if (!this.enabled) this.enabled = true
                    if (hookThread?.isAlive == true) {
                        null
                    } else {
                        startHookThread()
                    }
                }
            if (ready != null && !ready.await(1, TimeUnit.SECONDS)) setEnabled(false)
        } else {
            val thread =
                lifecycleLock.withLock {
                    if (!this.enabled) return
                    this.enabled = false
                    hookThreadId?.let { threadId ->
                        User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, WPARAM(0), LPARAM(0))
                    }
                    pendingKeyCaptures.clear()
                    hookThread
                }
            thread?.join(1_000)
            lifecycleLock.withLock {
                if (hookThread === thread && thread?.isAlive != true) hookThread = null
            }
        }
    }

    private fun startHookThread(): CountDownLatch {
        val ready = CountDownLatch(1)
        hookThread =
            Thread(
                {
                    User32.INSTANCE.PeekMessage(WinUser.MSG(), null, 0, 0, 0)
                    hookThreadId = Kernel32.INSTANCE.GetCurrentThreadId()
                    if (enabled) {
                        hook =
                            runCatching {
                                User32.INSTANCE.SetWindowsHookEx(
                                    WinUser.WH_KEYBOARD_LL,
                                    procedure,
                                    Kernel32.INSTANCE.GetModuleHandle(null),
                                    0,
                                )
                            }.getOrNull()
                    }
                    if (!enabled) {
                        hook?.let(User32.INSTANCE::UnhookWindowsHookEx)
                        hook = null
                    }
                    ready.countDown()
                    if (hook == null) enabled = false
                    val message = WinUser.MSG()
                    while (hook != null && User32.INSTANCE.GetMessage(message, null, 0, 0) > 0) {
                        User32.INSTANCE.TranslateMessage(message)
                        User32.INSTANCE.DispatchMessage(message)
                    }
                    hook?.let(User32.INSTANCE::UnhookWindowsHookEx)
                    hook = null
                    hookThreadId = null
                    pendingKeyCaptures.clear()
                    lifecycleLock.withLock {
                        if (hookThread === Thread.currentThread()) hookThread = null
                        if (enabled && hookThread == null) startHookThread()
                    }
                },
                "Metrolist Windows shortcut key capture",
            ).apply {
                isDaemon = true
                start()
            }
        return ready
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
                if (enabled && virtualKeyCode !in WINDOWS_MODIFIER_VIRTUAL_KEYS) {
                    pendingKeyCaptures.record(
                        windowsPhysicalPositionForScanCode(
                            pointer.getInt(Int.SIZE_BYTES.toLong()),
                            pointer.getInt((Int.SIZE_BYTES * 2).toLong()),
                        ),
                    )
                }
            }
        }
        return User32.INSTANCE.CallNextHookEx(hook, code, message, data)
    }

    override fun consume(event: AwtKeyEvent): CapturedPhysicalKey? = pendingKeyCaptures.consume(event.`when`)

    override fun close() = setEnabled(false)

    private companion object {
        const val WM_KEYDOWN = 0x0100
        const val WM_KEYUP = 0x0101
        const val WM_SYSKEYDOWN = 0x0104
        const val WM_SYSKEYUP = 0x0105
        val WINDOWS_MODIFIER_VIRTUAL_KEYS =
            setOf(
                0x10,
                0x11,
                0x12,
                0x14,
                // Windows reports the Japanese Kana Lock key as VK_KANA (0x15), which AWT maps to VK_KANA_LOCK.
                0x15,
                0x5B,
                0x5C,
                0xA0,
                0xA1,
                0xA2,
                0xA3,
                0xA4,
                0xA5,
            )
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
