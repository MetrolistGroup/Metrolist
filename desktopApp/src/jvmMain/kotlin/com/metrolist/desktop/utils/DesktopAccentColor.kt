package com.metrolist.desktop.utils

import androidx.compose.ui.graphics.Color
import com.metrolist.shared.utils.OsUtil
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

internal fun resolveDesktopSeedColor(
    configured: Color,
    useSystemAccent: Boolean,
    isWindows: Boolean = OsUtil.isWindows,
    readAccent: () -> Int = {
        Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, "SOFTWARE\\Microsoft\\Windows\\DWM", "AccentColor")
    },
): Color {
    if (!isWindows || !useSystemAccent) return configured
    return runCatching {
        val value = readAccent()
        Color((value and -0x1000000) or ((value and 0xff) shl 16) or (value and 0xff00) or ((value ushr 16) and 0xff))
    }.getOrDefault(configured)
}
