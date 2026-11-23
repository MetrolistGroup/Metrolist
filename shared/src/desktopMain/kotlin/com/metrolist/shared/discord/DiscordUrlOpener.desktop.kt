package com.metrolist.shared.discord

import com.metrolist.shared.utils.OsUtil
import com.metrolist.shared.utils.openUrl
import java.awt.Desktop
import java.net.URI

actual fun openDiscordUrl(
    url: String,
    context: Any?,
) {
    val targetUrl =
        if (url.startsWith("https://discord.com/oauth2/authorize")) {
            url.replace("https://discord.com", "discord://action")
        } else {
            url
        }

    val opened =
        runCatching {
            check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
            try {
                Desktop.getDesktop().browse(URI(targetUrl))
            } catch (_: Exception) {
                if (targetUrl != url) {
                    Desktop.getDesktop().browse(URI(url))
                } else {
                    throw IllegalStateException("Failed to open Discord URL")
                }
            }
        }.isSuccess
    if (!opened && (OsUtil.isLinux || OsUtil.isMac)) {
        // xdg-open and open report an unhandled discord:// link only through their exit status.
        val opener = if (OsUtil.isLinux) "xdg-open" else "open"
        runCatching { ProcessBuilder(opener, targetUrl).start() }
            .onSuccess { process ->
                if (targetUrl != url) process.onExit().thenAccept { if (it.exitValue() != 0) runCatching { openUrl(url) } }
            }.onFailure { if (targetUrl != url) runCatching { openUrl(url) } }
    }
}
