package com.metrolist.desktop.utils

import com.metrolist.shared.utils.OsUtil
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinReg
import dev.nucleusframework.core.runtime.NucleusApp
import java.io.File

internal object WindowsAppIdentity {
    internal val appId: String get() = NucleusApp.aumid

    fun setProcessIdentity() {
        if (!OsUtil.isWindows) return
        runCatching {
            COMUtils.checkRC(Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(WString(appId)))
        }.onFailure { AppLogger.warning("WindowsAppIdentity", "Unable to set Windows process identity", it) }
    }

    fun register(displayName: String) {
        if (!OsUtil.isWindows) return
        runCatching {
            val resources = System.getProperty("compose.application.resources.dir")?.let(::File) ?: return@runCatching
            val icon = resolveWindowsIdentityIcon(resources) ?: error("Packaged Windows identity icon is missing")
            val key = "Software\\Classes\\AppUserModelId\\$appId"
            Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, key)
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "DisplayName", displayName)
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "IconUri", icon.absolutePath)
        }.onFailure { AppLogger.warning("WindowsAppIdentity", "Unable to register Windows app identity", it) }
        runCatching {
            registerWindowsUriHandler(System.getProperty("jpackage.app-path")?.let(::File), displayName) { key, name, value ->
                Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, key)
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, name, value)
            }
        }.onFailure { AppLogger.warning("WindowsAppIdentity", "Unable to register Windows URI handler", it) }
    }
}

internal fun resolveWindowsIdentityIcon(resources: File): File? =
    sequenceOf(File(resources, "windows/logo.png"), File(resources, "logo.png")).firstOrNull(File::isFile)

internal fun registerWindowsUriHandler(
    launcher: File?,
    displayName: String,
    scheme: String = "metrolist",
    writeString: (key: String, name: String, value: String) -> Unit,
): Boolean {
    if (launcher == null || !launcher.isAbsolute || !launcher.isFile || !launcher.extension.equals("exe", ignoreCase = true) ||
        launcher.name.lowercase() in setOf("java.exe", "javaw.exe")
    ) {
        return false
    }
    val key = "Software\\Classes\\$scheme"
    writeString(key, "", displayName)
    writeString(key, "URL Protocol", "")
    writeString("$key\\DefaultIcon", "", "\"${launcher.absolutePath}\",0")
    writeString("$key\\shell\\open\\command", "", "\"${launcher.absolutePath}\" \"%1\"")
    return true
}
