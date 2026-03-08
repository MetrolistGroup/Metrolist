package com.metrolist.desktop.nativeimage

import com.metrolist.desktop.window.jna.User32Extend
import com.metrolist.desktop.window.jna.structure.MENUITEMINFO
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.Ole32
import org.bytedeco.ffmpeg.global.avutil
import org.jetbrains.skia.FontMgr
import java.awt.GraphicsEnvironment
import javax.sound.sampled.AudioSystem

internal fun nativeBackendSmoke() {
    check(GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts.isNotEmpty())
    check(FontMgr.default.familiesCount > 0)
    AudioSystem.getMixerInfo()
    check(avutil.av_version_info().string.isNotBlank())
    if (System.getProperty("os.name").startsWith("Windows")) {
        checkNotNull(User32Extend.instance).GetSystemMetrics(0)
        check(MENUITEMINFO().size() > 0)
        val result = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED)
        COMUtils.checkRC(result)
        Ole32.INSTANCE.CoUninitialize()
    }
    println("[NativeBackendSmoke] passed")
}
