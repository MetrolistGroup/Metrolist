package com.metrolist.shared.utils

fun redactDiagnosticText(text: String): String =
    text
        .replace(COOKIE_HEADER_PATTERN, "$1=<redacted>")
        .replace(URL_PATTERN) { redactDiagnosticUrl(it.value) }
        .replace(AUTH_SCHEME_PATTERN, "$1 <redacted>")
        .replace(SECRET_PATTERN, "$1=<redacted>")
        .replace(OPAQUE_TOKEN_PATTERN) { if (it.value.startsWith(R8_MAP_ID_PREFIX)) it.value else "<redacted>" }
        .replace(IPV4_PATTERN, "<redacted-ip>")

/** Signed media URLs can hide signatures in both queries and long path segments. */
fun redactDiagnosticUrl(url: String): String {
    val match = URL_PARTS_PATTERN.matchEntire(url) ?: return "<redacted-url>"
    val (scheme, host, path, query) = match.destructured
    val safePath = path.split('/').joinToString("/") { segment -> if (OPAQUE_SEGMENT_PATTERN.matches(segment)) "<redacted>" else segment }
    val safeHost = host.replace(IPV4_PATTERN, "<redacted-ip>")
    return "$scheme://$safeHost$safePath${if (query.isNotEmpty()) "?<redacted>" else ""}"
}

private val URL_PATTERN = Regex("(?i)\\b(?:https?|wss?)://[^\\s\"'<>]+")
private val URL_PARTS_PATTERN = Regex("(?i)(https?|wss?)://(?:[^@/?#]*@)?([^/?#]+)([^?#]*)(\\?[^#]*)?(?:#.*)?")
private val AUTH_SCHEME_PATTERN = Regex("(?i)\\b(Bearer|Basic|SAPISIDHASH|SAPISID1PHASH|SAPISID3PHASH)\\s+[^\\s,;\"']+")
private val SECRET_PATTERN =
    Regex(
        "(?i)\\b(access[_-]?token|refresh[_-]?token|id[_-]?token|po[_-]?token|token|cookie|set-cookie|authorization|password|secret|" +
            "api[_-]?key|key|session|sapisid|sid|signature|sig)\\b[\"']?\\s*[:=]\\s*[\"']?[^\\s,;\"']+",
    )

private const val R8_MAP_ID_PREFIX = "r8-map-id-"
private val OPAQUE_TOKEN_PATTERN = Regex("[A-Za-z0-9_\\-+/=%]{40,}")
private val OPAQUE_SEGMENT_PATTERN = Regex("[A-Za-z0-9_\\-+=%.]{24,}")
private val IPV4_PATTERN = Regex("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b")

private val COOKIE_HEADER_PATTERN =
    Regex("""(?i)\b(cookie|set-cookie)\b["']?\s*[:=]\s*(?:"(?:\\.|[^"\\\r\n])*"|'(?:\\.|[^'\\\r\n])*'|[^\r\n]+)""")
