package com.metrolist.shared.listentogether.network

private val WEB_SOCKET_URL =
    Regex(
        pattern = "^(wss?)://(\\[[^]]+]|[^/:?#]+)(?::([0-9]{1,5}))?(?:[/?#].*)?$",
        option = RegexOption.IGNORE_CASE,
    )

fun isAllowedListenTogetherServerUrl(url: String): Boolean {
    val match = WEB_SOCKET_URL.matchEntire(url.trim()) ?: return false
    if (!match.groupValues[1].equals("wss", ignoreCase = true)) return false
    return match.groupValues[3].toIntOrNull()?.let { it in 1..65535 } != false
}
