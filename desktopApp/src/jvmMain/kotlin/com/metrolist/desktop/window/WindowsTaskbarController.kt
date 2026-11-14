package com.metrolist.desktop.window

import com.metrolist.desktop.utils.AppLogger
import com.metrolist.desktop.window.jna.User32Extend
import com.metrolist.desktop.window.jna.structure.WinUserConst.PM_REMOVE
import com.metrolist.desktop.window.jna.structure.WinUserConst.QS_ALLINPUT
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinUser
import dev.nucleusframework.launcher.windows.ThumbnailToolbarButton
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

// Nucleus retains one COM object. Keep every call in the apartment that created it.
// The thread is an STA before Nucleus joins it to the MTA: TaskbarList is apartment-threaded, so creating it from the
// MTA hands Nucleus a proxy. Its static destructor releases that proxy at process exit, the RPC runtime raises
// RPC_E_SYS_CALL_FAILED, and Windows ARM64 HotSpot reports that first-chance exception as a fatal error.
internal val windowsTaskbarExecutor: Executor by lazy {
    val kernel32 = Kernel32.INSTANCE
    val user32 = checkNotNull(User32Extend.instance)
    val workAvailable = checkNotNull(kernel32.CreateEvent(null, false, false, null))
    val executor = WindowsTaskbarExecutor { check(kernel32.SetEvent(workAvailable)) }
    Thread({
        try {
            val result = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED)
            check(!COMUtils.FAILED(result)) { "CoInitializeEx failed: 0x%08x".format(result.toInt()) }
            val message = WinUser.MSG()
            // COM uses hidden windows in the STA, including while no toolbar updates are queued.
            while (true) {
                val status = user32.MsgWaitForMultipleObjects(1, arrayOf(workAvailable), false, WinBase.INFINITE, QS_ALLINPUT)
                check(status == WinBase.WAIT_OBJECT_0 || status == WinBase.WAIT_OBJECT_0 + 1) {
                    "Taskbar message wait failed: ${kernel32.GetLastError()}"
                }
                executor.runPendingTasks()
                while (user32.PeekMessage(message, null, 0, 0, PM_REMOVE)) {
                    user32.TranslateMessage(message)
                    user32.DispatchMessage(message)
                }
            }
        } catch (error: Exception) {
            AppLogger.error("WindowsTaskbar", "Taskbar message loop stopped", error)
        } finally {
            kernel32.CloseHandle(workAvailable)
        }
    }, "WindowsTaskbar").apply { isDaemon = true }.start()
    executor
}

internal class WindowsTaskbarExecutor(
    private val wakeUp: () -> Unit,
) : Executor {
    private val tasks = ConcurrentLinkedQueue<Runnable>()

    override fun execute(command: Runnable) {
        tasks.add(command)
        wakeUp()
    }

    fun runPendingTasks() {
        while (true) {
            val task = tasks.poll() ?: return
            try {
                task.run()
            } catch (error: Exception) {
                AppLogger.warning("WindowsTaskbar", "Taskbar update failed", error)
            }
        }
    }
}

internal class WindowsTaskbarController(
    private val executor: Executor,
    private val isWindow: () -> Boolean,
    private val register: (List<ThumbnailToolbarButton>) -> Boolean,
    private val update: (List<ThumbnailToolbarButton>) -> Boolean,
    private val unregister: () -> Unit,
) {
    @Volatile
    private var closed = false
    private var ready = false
    private var registered = false
    private var buttons = emptyList<ThumbnailToolbarButton>()

    fun taskbarCreated() =
        execute {
            ready = true
            render()
        }

    fun setButtons(buttons: List<ThumbnailToolbarButton>) =
        execute {
            this.buttons = buttons
            render()
        }

    private fun render() {
        if (!ready || buttons.isEmpty() || closed || !isWindow()) return
        if (registered) {
            update(buttons)
        } else {
            registered = register(buttons)
        }
    }

    fun close() {
        closed = true
        executor.execute {
            if (registered && isWindow()) unregister()
            registered = false
        }
    }

    private fun execute(action: () -> Unit) {
        executor.execute { if (!closed) action() }
    }
}
