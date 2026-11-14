package com.metrolist.desktop.window.jna.structure

import com.metrolist.shared.utils.OsUtil
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Kernel32

object WinUserConst {
    const val QS_ALLINPUT = 0x04FF
    const val PM_REMOVE = 0x0001
    const val SIZE_MINIMIZED = 1

    // SetWindowPos flags that only re-run WM_NCCALCSIZE.
    const val SWP_FRAME_ONLY = 0x0001 or 0x0002 or 0x0004 or 0x0010 or 0x0020

    val WM_NCCALCSIZE = 0x0083

    val WM_NCHITTEST = 0x0084

    val WM_MOUSEMOVE = 0x0200

    val WM_LBUTTONDOWN = 0x0201

    val WM_LBUTTONUP = 0x0202

    val WM_NCMOUSEMOVE = 0x00A0

    val WM_NCLBUTTONDOWN = 0x00A1

    val WM_NCLBUTTONUP = 0x00A2

    val WM_NCRBUTTONUP: Int = 0x00A5

    internal val HTTRANSPANRENT = -1

    internal val HTNOWHERE = 0

    internal val HTCLIENT = 1

    internal val HTCAPTION = 2

    internal val HTMINBUTTON = 8

    internal val HTMAXBUTTON = 9

    internal val HTCLOSE = 20

    internal val HTLEFT = 10
    internal val HTRIGHT = 11
    internal val HTTOP = 12
    internal val HTTOPLEFT = 13
    internal val HTTOPRIGHT = 14
    internal val HTBOTTOM = 15
    internal val HTBOTTOMLEFT = 16
    internal val HTBOTTOMRIGHT = 17

    const val WM_SETTINGCHANGE: Int = 0x001A

    const val WM_ACTIVATE: Int = 0x0006

    const val WA_INACTIVE: Int = 0x00000000

    const val SC_RESTORE: Int = 0x0000f120
    const val SC_MOVE: Int = 0xF010
    const val SC_SIZE: Int = 0xF000
    const val SC_CLOSE: Int = 0xF060

    const val WINT_MAX: Int = 0xFFFF

    const val MIIM_STATE: Int = 0x00000001

    const val MFT_STRING: Int = 0x00000000

    const val TPM_RETURNCMD: Int =
        0x0100

    const val MFS_ENABLED: Int = 0x00000000

    const val MFS_DISABLED: Int = 0x00000003
}

// See https://stackoverflow.com/q/62240901
@Structure.FieldOrder(
    "leftBorderWidth",
    "rightBorderWidth",
    "topBorderHeight",
    "bottomBorderHeight",
)
data class WindowMargins(
    @JvmField var leftBorderWidth: Int,
    @JvmField var rightBorderWidth: Int,
    @JvmField var topBorderHeight: Int,
    @JvmField var bottomBorderHeight: Int,
) : Structure(),
    Structure.ByReference

val windowsBuildNumber by lazy {
    if (!OsUtil.isWindows) {
        0
    } else {
        runCatching {
            Kernel32.INSTANCE
                .GetVersion()
                .high
                .toInt()
        }.getOrDefault(0)
    }
}

fun isWindows10OrLater() = OsUtil.isWindows && windowsBuildNumber >= 10240

fun isWindows11OrLater() = OsUtil.isWindows && windowsBuildNumber >= 22000
