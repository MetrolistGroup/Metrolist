package com.metrolist.shared.utils

fun redactDiagnosticText(text: String): String =
    text
        .replace(COOKIE_HEADER_PATTERN, "$1=<redacted>")
        .replace(URL_PATTERN) { redactDiagnosticUrl(it.value) }
        .replace(AUTH_SCHEME_PATTERN, "$1 <redacted>")
        .replace(SECRET_PATTERN, "$1=<redacted>")
        .replace(OPAQUE_TOKEN_PATTERN) { if (it.value.startsWith(R8_MAP_ID_PREFIX)) it.value else "<redacted>" }
        .let(::redactIpv6Addresses)
        .replace(IPV4_PATTERN, "<redacted-ip>")

/** Signed media URLs can hide signatures in both queries and long path segments. */
fun redactDiagnosticUrl(url: String): String {
    val match = URL_PARTS_PATTERN.matchEntire(url) ?: return "<redacted-url>"
    val (scheme, host, path, query) = match.destructured
    val safePath = path.split('/').joinToString("/") { segment -> if (OPAQUE_SEGMENT_PATTERN.matches(segment)) "<redacted>" else segment }
    val safeHost = redactIpv6Addresses(host).replace(IPV4_PATTERN, "<redacted-ip>")
    return "$scheme://$safeHost$safePath${if (query.isNotEmpty()) "?<redacted>" else ""}"
}

private fun redactIpv6Addresses(text: String): String {
    val redacted = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        if (!isIpv6AddressStart(text, index)) {
            redacted.append(text[index++])
            continue
        }

        var addressEnd = index
        while (addressEnd < text.length && isIpv6LiteralCharacter(text[addressEnd])) addressEnd++
        if (addressEnd < text.length && text[addressEnd] == '%') {
            addressEnd++
            while (addressEnd < text.length && isIpv6ScopeCharacter(text[addressEnd])) addressEnd++
        }
        var candidateEnd = addressEnd
        while (candidateEnd > index && text[candidateEnd - 1] == '.') candidateEnd--
        if (candidateEnd == addressEnd &&
            candidateEnd > index &&
            text[candidateEnd - 1] == ':' &&
            isIpv6Address(text.substring(index, candidateEnd - 1))
        ) {
            candidateEnd--
        }
        val ipv6AddressEndsBeforeDelimiter =
            candidateEnd < text.length &&
                text[candidateEnd] == ':' &&
                isIpv6Address(text.substring(index, candidateEnd))
        if (candidateEnd == addressEnd &&
            candidateEnd < text.length &&
            isIpv6TokenCharacter(text[candidateEnd]) &&
            !ipv6AddressEndsBeforeDelimiter
        ) {
            redacted.append(text[index++])
            continue
        }

        if (!isIpv6Address(text.substring(index, candidateEnd))) {
            val portSeparator = text.lastIndexOf(':', candidateEnd - 1)
            if (portSeparator > index &&
                text.substring(portSeparator + 1, candidateEnd).all { it in '0'..'9' } &&
                isIpv6Address(text.substring(index, portSeparator))
            ) {
                candidateEnd = portSeparator
            }
        }
        val address = text.substring(index, candidateEnd)
        if (isIpv6Address(address)) {
            redacted.append("<redacted-ip>")
            index = candidateEnd
        } else {
            redacted.append(text[index++])
        }
    }
    return redacted.toString()
}

private fun isIpv6AddressStart(
    text: String,
    index: Int,
): Boolean {
    if (!isIpv6LiteralCharacter(text[index])) return false
    return index == 0 || !isIpv6TokenCharacter(text[index - 1]) ||
        (text[index - 1] == ':' && text.substring(0, index - 1).takeLastWhile(::isIpv6ScopeCharacter).any { !isHexDigit(it) })
}

private fun isIpv6Address(value: String): Boolean {
    val scopeIndex = value.indexOf('%')
    val address = if (scopeIndex < 0) value else value.substring(0, scopeIndex)
    if (scopeIndex >= 0 && (scopeIndex == value.lastIndex || value.indexOf('%', scopeIndex + 1) >= 0)) return false
    if (':' !in address) return false

    val compressionIndex = address.indexOf("::")
    val hasCompression = compressionIndex >= 0
    if (hasCompression && address.indexOf("::", compressionIndex + 2) >= 0) return false
    val left = if (hasCompression) address.substring(0, compressionIndex) else address
    val right = if (hasCompression) address.substring(compressionIndex + 2) else ""
    val leftGroups = if (left.isEmpty()) emptyList() else left.split(':')
    val rightGroups = if (right.isEmpty()) emptyList() else right.split(':')
    val groups = leftGroups + rightGroups
    if (groups.any(String::isEmpty)) return false

    var groupCount = 0
    groups.forEachIndexed { index, group ->
        if ('.' in group) {
            if (index != groups.lastIndex || !isIpv4Address(group)) return false
            groupCount += 2
        } else {
            if (group.length !in 1..4 || group.any { !isHexDigit(it) }) return false
            groupCount++
        }
    }
    return if (hasCompression) groupCount < 8 else groupCount == 8
}

private fun isIpv4Address(value: String): Boolean {
    val parts = value.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.all { it in '0'..'9' } && part.toIntOrNull()?.let { it in 0..255 } == true
    }
}

private fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char.lowercaseChar() in 'a'..'f'

private fun isIpv6LiteralCharacter(char: Char): Boolean = isHexDigit(char) || char == ':' || char == '.'

private fun isIpv6ScopeCharacter(char: Char): Boolean = char.isLetterOrDigit() || char == '_' || char == '-' || char == '.'

private fun isIpv6TokenCharacter(char: Char): Boolean = isIpv6LiteralCharacter(char) || isIpv6ScopeCharacter(char) || char == '%'

private val URL_PATTERN = Regex("(?i)\\b(?:https?|wss?)://[^\\s\"'<>]+")
private val URL_PARTS_PATTERN = Regex("(?i)(https?|wss?)://(?:[^@/?#]*@)?([^/?#]+)([^?#]*)(\\?[^#]*)?(?:#.*)?")
private val AUTH_SCHEME_PATTERN = Regex("(?i)\\b(Bearer|Basic|SAPISIDHASH|SAPISID1PHASH|SAPISID3PHASH)\\s+[^\\s,;\"']+")
private val SECRET_PATTERN =
    Regex(
        "(?i)\\b(auth[_-]?token|auth|access[_-]?token|refresh[_-]?token|id[_-]?token|po[_-]?token|token|cookie|" +
            "set-cookie|authorization|password|secret|" +
            "api[_-]?key|key|session|sapisid|sid|signature|sig)\\b[\"']?\\s*[:=]\\s*[\"']?[^\\s,;\"']+",
    )

private const val R8_MAP_ID_PREFIX = "r8-map-id-"
private val OPAQUE_TOKEN_PATTERN = Regex("[A-Za-z0-9_\\-+/=%]{40,}")
private val OPAQUE_SEGMENT_PATTERN = Regex("[A-Za-z0-9_\\-+=%.]{24,}")
private val IPV4_PATTERN = Regex("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b")

private val COOKIE_HEADER_PATTERN =
    Regex("""(?i)\b(cookie|set-cookie)\b["']?\s*[:=]\s*(?:"(?:\\.|[^"\\\r\n])*"|'(?:\\.|[^'\\\r\n])*'|[^\r\n]+)""")
