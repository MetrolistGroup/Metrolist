package com.metrolist.desktop.window.jna

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.utils.AppLogger
import com.metrolist.desktop.window.findSkiaLayer
import com.metrolist.desktop.window.jna.structure.MENUITEMINFO
import com.metrolist.desktop.window.jna.structure.WinUserConst
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTBOTTOM
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTBOTTOMLEFT
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTBOTTOMRIGHT
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTCAPTION
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTLEFT
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTRIGHT
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTTOP
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTTOPLEFT
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTTOPRIGHT
import com.metrolist.desktop.window.jna.structure.WinUserConst.MFT_STRING
import com.metrolist.desktop.window.jna.structure.WinUserConst.MIIM_STATE
import com.metrolist.desktop.window.jna.structure.WinUserConst.SC_CLOSE
import com.metrolist.desktop.window.jna.structure.WinUserConst.SC_MOVE
import com.metrolist.desktop.window.jna.structure.WinUserConst.SC_RESTORE
import com.metrolist.desktop.window.jna.structure.WinUserConst.SC_SIZE
import com.metrolist.desktop.window.jna.structure.WinUserConst.SIZE_MINIMIZED
import com.metrolist.desktop.window.jna.structure.WinUserConst.SWP_FRAME_ONLY
import com.metrolist.desktop.window.jna.structure.WinUserConst.TPM_RETURNCMD
import com.metrolist.desktop.window.jna.structure.WinUserConst.WA_INACTIVE
import com.metrolist.desktop.window.jna.structure.WinUserConst.WINT_MAX
import com.metrolist.desktop.window.jna.structure.WinUserConst.WM_ACTIVATE
import com.metrolist.desktop.window.jna.structure.WinUserConst.WM_NCCALCSIZE
import com.metrolist.desktop.window.jna.structure.WinUserConst.WM_NCHITTEST
import com.metrolist.desktop.window.jna.structure.WinUserConst.WM_NCMOUSEMOVE
import com.metrolist.desktop.window.jna.structure.WinUserConst.WM_NCRBUTTONUP
import com.metrolist.desktop.window.jna.structure.WinUserConst.WM_SETTINGCHANGE
import com.metrolist.desktop.window.jna.structure.WindowMargins
import com.metrolist.desktop.window.jna.structure.isWindows11OrLater
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.BaseTSD.LONG_PTR
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinDef.HMENU
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.UINT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.platform.win32.WinUser.WM_DESTROY
import com.sun.jna.platform.win32.WinUser.WM_SIZE
import com.sun.jna.platform.win32.WinUser.WS_CAPTION
import com.sun.jna.platform.win32.WinUser.WS_SYSMENU
import com.sun.jna.ptr.IntByReference
import org.jetbrains.skiko.currentSystemTheme
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window

internal class ComposeWindowProcedure(
    private val window: Window,
    private val hitTest: (x: Float, y: Float) -> Int,
    private val onWindowInsetUpdate: (WindowInsets) -> Unit,
) : WindowProcedure {
    private companion object {
        const val WM_NCDESTROY = 0x0082
        const val WM_COMMAND = 0x0111
        const val THBN_CLICKED = 0x1800L
    }

    private val windowPointer =
        (this.window as? ComposeWindow)
            ?.windowHandle
            ?.let(::Pointer)
            ?: Native.getWindowPointer(this.window)

    val windowHandle = HWND(windowPointer)

    private val taskbarButtonCreatedMessage = User32Extend.instance?.RegisterWindowMessage("TaskbarButtonCreated") ?: 0
    var taskbarGeneration by mutableIntStateOf(0)
        private set
    var onTaskbarClick: ((Int) -> Unit)? = null

    @Volatile
    var isDestroyed = false
        private set

    private var hitResult = 1

    private val margins =
        WindowMargins(
            leftBorderWidth = 0,
            topBorderHeight = 0,
            rightBorderWidth = -1,
            bottomBorderHeight = -1,
        )

    private var defaultWindowProcedure = User32Extend.instance?.setWindowLong(windowHandle, WinUser.GWL_WNDPROC, this) ?: LONG_PTR(-1)

    private var dpi = UINT(0)
    private var width = 0
    private var height = 0
    private var isMinimized = false
    private var frameX = 0
    private var frameY = 0
    private var edgeX = 0
    private var edgeY = 0
    private var padding = 0
    private var isMaximized = User32Extend.instance?.isWindowInMaximized(windowHandle) == true

    var isWindowFrameAccentColorEnabled by mutableStateOf(isAccentColorWindowFrame())

    var windowFrameColor by mutableStateOf(currentAccentColor())

    var windowTheme by mutableStateOf(currentSystemTheme)

    var isWindowActive by mutableStateOf(true)

    val skiaLayerProcedure =
        (window as? ComposeWindow)?.findSkiaLayer()?.let {
            SkiaLayerWindowProcedure(
                skiaLayer = it,
                hitTest = { x, y ->
                    updateWindowInfo()
                    val horizontalPadding = frameX
                    val verticalPadding = frameY
                    hitResult =
                        when {
                            isMaximized -> hitTest(x, y)
                            x <= horizontalPadding && y > verticalPadding && y < height - verticalPadding -> HTLEFT
                            x <= horizontalPadding && y <= verticalPadding -> HTTOPLEFT
                            x <= horizontalPadding -> HTBOTTOMLEFT
                            y <= verticalPadding && x > horizontalPadding && x < width - horizontalPadding -> HTTOP
                            y <= verticalPadding && x <= horizontalPadding -> HTTOPLEFT
                            y <= verticalPadding -> HTTOPRIGHT
                            x >= width - horizontalPadding && y > verticalPadding && y < height - verticalPadding -> HTRIGHT
                            x >= width - horizontalPadding && y <= verticalPadding -> HTTOPRIGHT
                            x >= width - horizontalPadding -> HTBOTTOMRIGHT
                            y >= height - verticalPadding && x > horizontalPadding && x < width - horizontalPadding -> HTBOTTOM
                            y >= height - verticalPadding && x <= horizontalPadding -> HTBOTTOMLEFT
                            y >= height - verticalPadding -> HTBOTTOMRIGHT
                            else -> hitTest(x, y)
                        }
                    hitResult
                },
            )
        }

    init {
        enableResizability()
        enableBorderAndShadow()
        // The window is already shown with the startup screen, so Windows keeps the native title bar until it recomputes the frame.
        User32Extend.instance?.SetWindowPos(windowHandle, null, 0, 0, 0, 0, SWP_FRAME_ONLY)
    }

    var isFullscreen: Boolean = false

    override fun callback(
        hWnd: HWND,
        uMsg: Int,
        wParam: WPARAM,
        lParam: LPARAM,
    ): LRESULT {
        if (uMsg == WM_NCDESTROY) isDestroyed = true
        if (taskbarButtonCreatedMessage != 0 && uMsg == taskbarButtonCreatedMessage) {
            EventQueue.invokeLater { if (!isDestroyed) taskbarGeneration++ }
        }
        if (uMsg == WM_COMMAND && (wParam.toLong() ushr 16 and 0xffff) == THBN_CLICKED) {
            val id = (wParam.toLong() and 0xffff).toInt()
            EventQueue.invokeLater { if (!isDestroyed) onTaskbarClick?.invoke(id) }
        }
        if (isFullscreen) {
            if (uMsg == WM_NCCALCSIZE) {
                onWindowInsetUpdate(WindowInsets(0, 0, 0, 0))
                return LRESULT(0)
            }
            return User32Extend.instance?.CallWindowProc(defaultWindowProcedure, hWnd, uMsg, wParam, lParam)
                ?: LRESULT(0)
        }
        return when (uMsg) {
            // Returning zero makes the title bar and border part of the client area.
            WM_NCCALCSIZE -> {
                if (wParam.toInt() == 0) {
                    User32Extend.instance?.CallWindowProc(defaultWindowProcedure, hWnd, uMsg, wParam, lParam) ?: LRESULT(0)
                } else {
                    val user32 = User32Extend.instance ?: return LRESULT(0)
                    dpi = user32.GetDpiForWindow(hWnd)
                    frameX = user32.GetSystemMetricsForDpi(WinUser.SM_CXFRAME, dpi)
                    frameY = user32.GetSystemMetricsForDpi(WinUser.SM_CYFRAME, dpi)
                    edgeX = user32.GetSystemMetricsForDpi(WinUser.SM_CXEDGE, dpi)
                    edgeY = user32.GetSystemMetricsForDpi(WinUser.SM_CYEDGE, dpi)
                    padding = user32.GetSystemMetricsForDpi(WinUser.SM_CXPADDEDBORDER, dpi)
                    isMaximized = user32.isWindowInMaximized(hWnd)
                    onWindowInsetUpdate(
                        WindowInsets(
                            left =
                                if (isMaximized) {
                                    frameX + padding
                                } else {
                                    edgeX
                                },
                            right =
                                if (isMaximized) {
                                    frameX + padding
                                } else {
                                    edgeX
                                },
                            top =
                                if (isMaximized) {
                                    frameY + padding
                                } else {
                                    edgeY
                                },
                            bottom =
                                if (isMaximized) {
                                    frameY + padding
                                } else {
                                    edgeY
                                },
                        ),
                    )
                    LRESULT(0)
                }
            }

            WM_NCHITTEST -> {
                LRESULT(hitResult.toLong())
            }

            WM_DESTROY -> {
                User32Extend.instance?.CallWindowProc(defaultWindowProcedure, hWnd, uMsg, wParam, lParam) ?: LRESULT(0)
            }

            WM_SIZE -> {
                width = lParam.toInt() and 0xFFFF
                height = (lParam.toInt() shr 16) and 0xFFFF
                val minimized = wParam.toInt() == SIZE_MINIMIZED
                if (minimized != isMinimized) {
                    isMinimized = minimized
                    AppLogger.info("WindowsWindow", "WM_SIZE minimized=$minimized client=${width}x$height dpi=${dpi.toInt()}")
                    if (!minimized) {
                        EventQueue.invokeLater {
                            if (!isDestroyed) {
                                val layer = (window as? ComposeWindow)?.findSkiaLayer()
                                AppLogger.info(
                                    "WindowsWindow",
                                    "Restore reached EDT: window=${window.width}x${window.height} " +
                                        "state=${(window as? Frame)?.extendedState} showing=${window.isShowing} " +
                                        "layer=${layer?.width}x${layer?.height} showing=${layer?.isShowing}",
                                )
                            }
                        }
                    }
                }
                User32Extend.instance?.CallWindowProc(defaultWindowProcedure, hWnd, uMsg, wParam, lParam) ?: LRESULT(0)
            }

            WM_NCRBUTTONUP -> {
                if (wParam.toInt() == HTCAPTION) {
                    val user32 = User32Extend.instance ?: return LRESULT(0)
                    val oldStyle = user32.GetWindowLong(hWnd, WinUser.GWL_STYLE)
                    user32.SetWindowLong(hWnd, WinUser.GWL_STYLE, oldStyle or WS_SYSMENU)
                    val menu = user32.GetSystemMenu(hWnd, false)
                    user32.SetWindowLong(hWnd, WinUser.GWL_STYLE, oldStyle)
                    isMaximized = user32.isWindowInMaximized(hWnd)
                    if (menu != null) {
                        val menuItemInfo =
                            MENUITEMINFO().apply {
                                cbSize = this.size()
                                fMask = MIIM_STATE
                                fType = MFT_STRING
                            }

                        updateMenuItemInfo(menu, menuItemInfo, SC_RESTORE, isMaximized)
                        updateMenuItemInfo(menu, menuItemInfo, SC_MOVE, !isMaximized)
                        updateMenuItemInfo(menu, menuItemInfo, SC_SIZE, !isMaximized)
                        updateMenuItemInfo(menu, menuItemInfo, WinUser.SC_MINIMIZE, true)
                        updateMenuItemInfo(menu, menuItemInfo, WinUser.SC_MAXIMIZE, !isMaximized)
                        updateMenuItemInfo(menu, menuItemInfo, SC_CLOSE, true)

                        user32.SetMenuDefaultItem(menu, WINT_MAX, false)

                        val lParamValue = lParam.toInt()
                        val x = lParamValue.lowWord
                        val y = lParamValue.highWord

                        val ret = user32.TrackPopupMenu(menu, TPM_RETURNCMD, x, y, 0, hWnd, null)
                        if (ret != 0) {
                            user32.PostMessage(
                                hWnd,
                                WinUser.WM_SYSCOMMAND,
                                WPARAM(ret.toLong()),
                                LPARAM(0),
                            )
                        }
                    }
                }
                User32Extend.instance?.CallWindowProc(defaultWindowProcedure, hWnd, uMsg, wParam, lParam) ?: LRESULT(0)
            }

            WM_SETTINGCHANGE -> {
                val changedKey = lParam.toLong().takeIf { it != 0L }?.let { Pointer(it).getWideString(0) }
                if (changedKey == "ImmersiveColorSet") {
                    windowTheme = currentSystemTheme
                    windowFrameColor = currentAccentColor()
                    isWindowFrameAccentColorEnabled = isAccentColorWindowFrame()
                }
                User32Extend.instance?.CallWindowProc(defaultWindowProcedure, hWnd, uMsg, wParam, lParam) ?: LRESULT(0)
            }

            else -> {
                if (uMsg == WM_ACTIVATE) {
                    isWindowActive = wParam.toInt() != WA_INACTIVE
                }
                if (uMsg == WM_NCMOUSEMOVE) {
                    skiaLayerProcedure?.let {
                        User32Extend.instance?.PostMessage(it.contentHandle, uMsg, wParam, lParam)
                    }
                }
                User32Extend.instance?.CallWindowProc(defaultWindowProcedure, hWnd, uMsg, wParam, lParam) ?: LRESULT(0)
            }
        }
    }

    // Refresh hit-test geometry when the window moves to another monitor.
    private fun updateWindowInfo() {
        User32Extend.instance?.apply {
            dpi = GetDpiForWindow(windowHandle)
            frameX = GetSystemMetricsForDpi(WinUser.SM_CXFRAME, dpi)
            frameY = GetSystemMetricsForDpi(WinUser.SM_CYFRAME, dpi)

            val rect = WinDef.RECT()
            if (GetWindowRect(windowHandle, rect)) {
                rect.read()
                width = rect.right - rect.left
                height = rect.bottom - rect.top
            }
            rect.clear()
        }
    }

    private fun updateMenuItemInfo(
        menu: HMENU,
        menuItemInfo: MENUITEMINFO,
        item: Int,
        enabled: Boolean,
    ) {
        menuItemInfo.fState = if (enabled) WinUserConst.MFS_ENABLED else WinUserConst.MFS_DISABLED
        User32Extend.instance?.SetMenuItemInfo(menu, item, false, menuItemInfo)
    }

    fun currentAccentColor(): Color {
        val value =
            Advapi32Util
                .registryGetIntValue(
                    WinReg.HKEY_CURRENT_USER,
                    "SOFTWARE\\Microsoft\\Windows\\DWM",
                    "AccentColor",
                ).toLong()
        val alpha = (value and 0xFF000000)
        val green = (value and 0xFF).shl(16)
        val blue = (value and 0xFF00)
        val red = (value and 0xFF0000).shr(16)
        return Color((alpha or green or blue or red).toInt())
    }

    fun isAccentColorWindowFrame(): Boolean =
        Advapi32Util.registryGetIntValue(
            WinReg.HKEY_CURRENT_USER,
            "SOFTWARE\\Microsoft\\Windows\\DWM",
            "ColorPrevalence",
        ) != 0

    /** Also requires resizable = true on the Compose Window. */
    private fun enableResizability() {
        User32Extend.instance?.updateWindowStyle(windowHandle) { oldStyle ->
            (oldStyle or WS_CAPTION) and WS_SYSMENU.inv()
        }
    }

    /** Zero margins disable the window border and shadow. */
    private fun enableBorderAndShadow() {
        val dwmApi =
            "dwmapi"
                .runCatching(NativeLibrary::getInstance)
                .onFailure { println("Could not load dwmapi library") }
                .getOrNull()
        dwmApi
            ?.runCatching { getFunction("DwmExtendFrameIntoClientArea") }
            ?.onFailure { println("Could not enable window native decorations (border/shadow/rounded corners)") }
            ?.getOrNull()
            ?.invoke(arrayOf(windowHandle, margins))

        if (isWindows11OrLater()) {
            dwmApi?.getFunction("DwmSetWindowAttribute")?.apply {
                invoke(
                    WinNT.HRESULT::class.java,
                    arrayOf(windowHandle, 35, IntByReference((0xFFFFFFFE).toInt()), 4),
                )
                invoke(
                    WinNT.HRESULT::class.java,
                    arrayOf(windowHandle, 38, IntByReference(2), 4),
                )
            }
        }
    }
}
