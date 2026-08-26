/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.extensions

import androidx.sqlite.db.SimpleSQLiteQuery
import java.net.InetSocketAddress
import java.net.InetSocketAddress.createUnresolved
import java.text.Normalizer

private val combiningDiacriticalMarksRegex = "\\p{Mn}+".toRegex()

inline fun <reified T : Enum<T>> String?.toEnum(defaultValue: T): T =
    if (this == null) {
        defaultValue
    } else {
        try {
            enumValueOf(this)
        } catch (e: IllegalArgumentException) {
            defaultValue
        }
    }

fun String.toSQLiteQuery(): SimpleSQLiteQuery = SimpleSQLiteQuery(this)

fun String.normalizeForSearch(): String =
    Normalizer
        .normalize(this.trim(), Normalizer.Form.NFD)
        .replace(combiningDiacriticalMarksRegex, "")
        .lowercase()

fun matchesNormalizedQuery(normalizedQuery: String, vararg values: String?): Boolean {
    val q = normalizedQuery.trim()
    if (q.isBlank()) return true
    return values.any { value ->
        value?.normalizeForSearch()?.contains(q) == true
    }
}

fun String.toInetSocketAddress(): InetSocketAddress {
    val (host, port) = split(":")
    return createUnresolved(host, port.toInt())
}
