package com.metrolist.shared.listentogether

import io.ktor.http.URLProtocol
import io.ktor.http.Url

private val roomCodePattern = Regex("^[A-Za-z0-9]{8}$")

fun parseListenTogetherInvitation(value: String): String? {
    val uri = runCatching { Url(value) }.getOrNull() ?: return null
    val code =
        when {
            uri.protocol == URLProtocol.HTTPS &&
                uri.host.equals("metrolist.cc", ignoreCase = true) &&
                uri.encodedPath.trim('/') == "listen" -> uri.parameters["code"]

            uri.protocol.name == "metrolist" && uri.host == "listen-together" -> uri.encodedPath.trim('/')

            else -> null
        }
    return code?.takeIf(roomCodePattern::matches)?.uppercase()
}
