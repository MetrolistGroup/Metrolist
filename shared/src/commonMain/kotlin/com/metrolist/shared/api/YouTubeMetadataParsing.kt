package com.metrolist.shared.api

internal fun parseDurationSeconds(text: String?): Long? {
    val parts = text?.trim()?.split(':') ?: return null
    if (parts.size !in 2..3) return null
    val values = parts.map { it.toLongOrNull() ?: return null }
    if (values.any { it < 0L } || values.drop(1).any { it > 59L }) return null
    return values.fold(0L) { total, value -> total * 60L + value }
}

internal fun String.playlistIdFromCanonicalUrl(): String? =
    substringAfter('?', missingDelimiterValue = "")
        .substringBefore('#')
        .split('&')
        .firstNotNullOfOrNull { parameter ->
            val name = parameter.substringBefore('=', missingDelimiterValue = "")
            val value = parameter.substringAfter('=', missingDelimiterValue = "").trim()
            value.takeIf { name == "list" && it.isNotEmpty() }
        }
