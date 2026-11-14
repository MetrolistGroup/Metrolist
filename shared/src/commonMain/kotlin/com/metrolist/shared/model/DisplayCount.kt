package com.metrolist.shared.model

internal fun parseDisplayCount(text: String?): Int? {
    val value = text?.trim() ?: return null
    val start = value.indexOfFirst(Char::isDigit).takeIf { it >= 0 } ?: return null
    val numeric = value.drop(start).takeWhile { it.isDigit() || it in ",. '\u00a0\u202f\u066c\u2019" }.trim()
    val suffix = value.drop(start + numeric.length)
    if (suffix.isNotEmpty() && !suffix.first().isWhitespace()) return null
    val unit = suffix.trimStart().takeWhile { !it.isWhitespace() }
    if (unit in setOf("K", "k", "M", "m", "B", "b") || unit.endsWith('.')) return null
    val groups = numeric.split(Regex("[,.'\u00a0\u202f\u066c\u2019 ]+")).filter(String::isNotEmpty)
    if (groups.size > 1) {
        val standard = groups.first().length in 1..3 && groups.drop(1).all { it.length == 3 }
        val indian = groups.first().length in 1..2 && groups.last().length == 3 && groups.drop(1).dropLast(1).all { it.length == 2 }
        if (!standard && !indian) return null
    }
    return numeric
        .filter(Char::isDigit)
        .map { it.digitToIntOrNull() ?: return null }
        .joinToString("")
        .toIntOrNull()
}
