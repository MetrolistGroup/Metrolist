package com.metrolist.shared.discord

import com.metrolist.shared.utils.openUrl
import com.metrolist.shared.utils.tryOpenUrl

actual fun openDiscordUrl(
    url: String,
    context: Any?,
) {
    val appUrlString =
        when {
            url.startsWith("https://discord.com/oauth2/authorize") -> {
                url.replace("https://discord.com", "discord://action")
            }

            url.startsWith("https://discord.com/") ||
                url.startsWith("https://discord.gg/") ||
                url.startsWith("https://discord.gift/") ||
                url.startsWith("https://discord.new/") -> {
                url.replace("https://", "discord://")
            }

            else -> {
                null
            }
        }

    if (appUrlString != null) {
        tryOpenUrl(appUrlString) { opened ->
            if (!opened) openUrl(url)
        }
        return
    }

    openUrl(url)
}
