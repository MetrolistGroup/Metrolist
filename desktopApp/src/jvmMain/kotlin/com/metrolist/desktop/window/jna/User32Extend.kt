package com.metrolist.desktop.window.jna

import com.metrolist.desktop.window.jna.structure.MENUITEMINFO
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.BaseTSD.LONG_PTR
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HMENU
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinDef.UINT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.platform.win32.WinUser.WindowProc
import com.sun.jna.win32.W32APIOptions

@Suppress("FunctionName")
internal interface User32Extend : User32 {
    fun MsgWaitForMultipleObjects(
        nCount: Int,
        pHandles: Array<HANDLE>,
        fWaitAll: Boolean,
        dwMilliseconds: Int,
        dwWakeMask: Int,
    ): Int

    fun SetWindowLong(
        hWnd: HWND,
        nIndex: Int,
        wndProc: WindowProc,
    ): LONG_PTR

    fun SetWindowLongPtr(
        hWnd: HWND,
        nIndex: Int,
        wndProc: WindowProc,
    ): LONG_PTR

    fun CallWindowProc(
        proc: LONG_PTR,
        hWnd: HWND,
        uMsg: Int,
        uParam: WPARAM,
        lParam: LPARAM,
    ): LRESULT

    fun GetSystemMetricsForDpi(
        nIndex: Int,
        dpi: UINT,
    ): Int

    fun GetDpiForWindow(hWnd: HWND): UINT

    fun ScreenToClient(
        hWnd: HWND,
        lpPoint: POINT,
    ): Boolean

    fun GetSystemMenu(
        hWnd: HWND,
        bRevert: Boolean,
    ): HMENU?

    fun SetMenuItemInfo(
        hMenu: HMENU,
        uItem: Int,
        fByPosition: Boolean,
        lpmii: MENUITEMINFO,
    ): Boolean

    fun TrackPopupMenu(
        hMenu: HMENU,
        uFlags: Int,
        x: Int,
        y: Int,
        nReserved: Int,
        hWnd: HWND,
        prcRect: RECT?,
    ): Int

    fun SetMenuDefaultItem(
        hMenu: HMENU,
        uItem: Int,
        fByPos: Boolean,
    ): Boolean

    companion object {
        val instance by lazy {
            runCatching {
                Native.load(
                    "user32",
                    User32Extend::class.java,
                    W32APIOptions.DEFAULT_OPTIONS,
                )
            }.onFailure { println("Could not load user32 library") }
                .getOrNull()
        }
    }
}

internal fun User32Extend.setWindowLong(
    hWnd: HWND,
    nIndex: Int,
    procedure: WindowProcedure,
): LONG_PTR =
    if (Platform.is64Bit()) {
        SetWindowLongPtr(hWnd, nIndex, procedure)
    } else {
        SetWindowLong(hWnd, nIndex, procedure)
    }

internal fun User32.isWindowInMaximized(hWnd: HWND): Boolean {
    val placement = WinUser.WINDOWPLACEMENT()
    val result =
        GetWindowPlacement(hWnd, placement)
            .booleanValue() && placement.showCmd == WinUser.SW_SHOWMAXIMIZED
    placement.clear()
    return result
}

internal fun User32.updateWindowStyle(
    hWnd: HWND,
    styleBlock: (oldStyle: Int) -> Int,
) {
    val oldStyle = GetWindowLong(hWnd, WinUser.GWL_STYLE)
    SetWindowLong(hWnd, WinUser.GWL_STYLE, styleBlock(oldStyle))
}

internal val Int.lowWord: Int
    get() = (this and 0xFFFF)

internal val Int.highWord: Int
    get() = (this shr 16) and 0xFFFF

typealias WindowProcedure = WindowProc
