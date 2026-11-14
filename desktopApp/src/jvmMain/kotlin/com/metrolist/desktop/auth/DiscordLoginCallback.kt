package com.metrolist.desktop.auth

import com.metrolist.shared.discord.DiscordDefaults
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal data class DiscordLoginCallback(
    val code: String?,
    val state: String?,
    val error: String?,
)

internal fun parseDiscordLoginCallback(
    url: String,
    redirectUri: String = DiscordDefaults.DISCORD_REDIRECT_URI,
): DiscordLoginCallback? =
    runCatching {
        val uri = URI(url)
        val redirect = URI(redirectUri)
        if (uri.scheme != redirect.scheme || uri.rawAuthority != redirect.rawAuthority || uri.rawPath != redirect.rawPath ||
            uri.rawFragment != null
        ) {
            return@runCatching null
        }
        val parameters = mutableMapOf<String, String>()
        uri.rawQuery.orEmpty().split('&').filter { '=' in it }.forEach {
            val parts = it.split('=', limit = 2)
            val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
            val value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
            require(parameters.put(key, value) == null)
        }
        DiscordLoginCallback(parameters["code"], parameters["state"], parameters["error"])
    }.getOrNull()
