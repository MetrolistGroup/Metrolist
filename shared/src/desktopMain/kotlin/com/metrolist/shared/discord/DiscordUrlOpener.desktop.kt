package com.metrolist.shared.discord

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

    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        try {
            Desktop.getDesktop().browse(URI(targetUrl))
        } catch (e: Exception) {
            if (targetUrl != url) {
                Desktop.getDesktop().browse(URI(url))
            }
        }
    }
}
