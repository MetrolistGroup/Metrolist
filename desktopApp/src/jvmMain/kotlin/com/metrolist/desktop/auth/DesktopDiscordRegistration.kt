package com.metrolist.desktop.auth

import com.metrolist.desktop.utils.AppLogger
import com.metrolist.desktop.utils.registerWindowsUriHandler
import com.metrolist.shared.utils.OsUtil
import com.metrolist.shared.utils.XdgUtils
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.awt.Desktop
import java.io.File
import java.util.concurrent.TimeUnit

internal fun registerDesktopDiscordCallback(displayName: String): Boolean =
    runCatching {
        val launcher =
            System.getProperty("jpackage.app-path") ?: ProcessHandle
                .current()
                .info()
                .command()
                .orElse(null)
        when {
            OsUtil.isWindows -> {
                registerWindowsUriHandler(launcher?.let(::File), displayName, "metrolist-auth") { key, name, value ->
                    Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, key)
                    Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, name, value)
                }
            }

            OsUtil.isMac -> {
                launcher?.contains(".app/Contents/MacOS/") == true && Desktop.isDesktopSupported() &&
                    Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)
            }

            OsUtil.isLinux -> {
                if (!System.getenv("FLATPAK_ID").isNullOrBlank()) return@runCatching true
                val query = listOf("xdg-mime", "query", "default", DISCORD_MIME_TYPE)
                val packagedHandler = runRegistrationCommand(query)?.trim()
                if (packagedHandler in setOf("metrolist.desktop", "Metrolist.desktop", "com.metrolist.desktop.desktop")) {
                    return@runCatching true
                }
                val executable = linuxDiscordLauncher(System.getenv("APPIMAGE"), launcher) ?: return@runCatching false
                val desktopFile = File(XdgUtils.getXdgDataHome(), "applications/$DISCORD_DESKTOP_FILE")
                desktopFile.parentFile.mkdirs()
                desktopFile.writeText(linuxDiscordDesktopEntry(executable.absolutePath, displayName))
                runRegistrationCommand(linuxDiscordRegistrationCommand()) != null &&
                    runRegistrationCommand(query)?.trim() == DISCORD_DESKTOP_FILE
            }

            else -> {
                false
            }
        }
    }.onFailure { AppLogger.warning("DiscordLogin", "Unable to register Discord callback handler", it) }.getOrDefault(false)

internal fun linuxDiscordLauncher(
    appImage: String?,
    launcher: String?,
): File? {
    val executable = (appImage?.takeIf(String::isNotBlank) ?: launcher)?.let(::File) ?: return null
    if (!executable.isAbsolute || !executable.isFile || !executable.canExecute() ||
        executable.name in setOf("java", "java.exe", "javaw.exe")
    ) {
        return null
    }
    val wrapper = File(executable.parentFile, "Metrolist")
    return if (appImage.isNullOrBlank() && executable.name == "Metrolist.bin" && wrapper.canExecute()) wrapper else executable
}

internal fun linuxDiscordDesktopEntry(
    executable: String,
    displayName: String,
): String {
    require('\n' !in executable && '\r' !in executable && '\n' !in displayName && '\r' !in displayName)
    val quoted =
        buildString {
            append('"')
            // Desktop entries unescape string values before parsing quoted Exec arguments.
            executable.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\\\\\")
                    '"', '$', '`' -> append("\\\\").append(char)
                    '%' -> append("%%")
                    else -> append(char)
                }
            }
            append('"')
        }
    // env lets desktop loaders resolve the command before expanding %% in the launcher path.
    return """
        [Desktop Entry]
        Type=Application
        Name=$displayName
        Exec=env $quoted %u
        Terminal=false
        NoDisplay=true
        MimeType=$DISCORD_MIME_TYPE;
        """.trimIndent() + "\n"
}

internal fun linuxDiscordRegistrationCommand(): List<String> = listOf("xdg-mime", "default", DISCORD_DESKTOP_FILE, DISCORD_MIME_TYPE)

private fun runRegistrationCommand(command: List<String>): String? {
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    return if (process.exitValue() == 0) process.inputStream.bufferedReader().use { it.readText() } else null
}

private const val DISCORD_DESKTOP_FILE = "com.metrolist.desktop.discord-auth.desktop"
private const val DISCORD_MIME_TYPE = "x-scheme-handler/metrolist-auth"
