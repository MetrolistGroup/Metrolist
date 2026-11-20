package com.metrolist.desktop.input

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class WindowsPhysicalKeyCapture : AutoCloseable {
    private val scanCodesByVirtualKey = ConcurrentHashMap<Int, Int>()

    @Volatile private var hook: WinUser.HHOOK? = null

    @Volatile private var hookThreadId: Int? = null

    @Volatile private var enabled = false
    private var hookThread: Thread? = null

    private val procedure =
        object : HookProcedure {
            override fun callback(
                code: Int,
                message: WPARAM,
                data: LPARAM,
            ): LRESULT {
                if (code >= 0) {
                    val messageId = message.toInt()
                    val pointer = Pointer.createConstant(data.toLong())
                    val virtualKeyCode = pointer.getInt(0)
                    when (messageId) {
                        WM_KEYDOWN, WM_SYSKEYDOWN -> {
                            if (enabled) scanCodesByVirtualKey[virtualKeyCode] = pointer.getInt(Int.SIZE_BYTES.toLong())
                        }

                        WM_KEYUP, WM_SYSKEYUP -> {
                            scanCodesByVirtualKey.remove(virtualKeyCode)
                        }
                    }
                }
                return User32.INSTANCE.CallNextHookEx(hook, code, message, data)
            }
        }

    fun setEnabled(enabled: Boolean) {
        if (enabled == this.enabled) return
        this.enabled = enabled
        if (enabled) {
            val ready = CountDownLatch(1)
            hookThread =
                Thread(
                    {
                        hookThreadId = Kernel32.INSTANCE.GetCurrentThreadId()
                        if (this.enabled) {
                            User32.INSTANCE.PeekMessage(WinUser.MSG(), null, 0, 0, 0)
                            hook =
                                runCatching {
                                    User32.INSTANCE.SetWindowsHookEx(WinUser.WH_KEYBOARD_LL, procedure, null, 0)
                                }.getOrNull()
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
                        scanCodesByVirtualKey.clear()
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
            scanCodesByVirtualKey.clear()
            hookThread?.join(1_000)
            hookThread = null
        }
    }

    fun consume(awtKeyCode: Int): PhysicalKeyPosition? = scanCodesByVirtualKey.remove(awtKeyCode)?.let(::windowsPhysicalPositionForScanCode)

    override fun close() = setEnabled(false)

    private interface HookProcedure : WinUser.HOOKPROC {
        fun callback(
            code: Int,
            message: WPARAM,
            data: LPARAM,
        ): LRESULT
    }

    private companion object {
        const val WM_KEYDOWN = 0x0100
        const val WM_KEYUP = 0x0101
        const val WM_SYSKEYDOWN = 0x0104
        const val WM_SYSKEYUP = 0x0105
    }
}
