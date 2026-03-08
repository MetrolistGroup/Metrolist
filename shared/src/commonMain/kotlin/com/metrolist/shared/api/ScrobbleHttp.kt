package com.metrolist.shared.api

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Clock

internal fun scrobbleStatusDisposition(statusCode: Int): ScrobbleFailureDisposition =
    when {
        statusCode == 401 || statusCode == 403 -> ScrobbleFailureDisposition.AUTHENTICATION
        statusCode in setOf(408, 425, 429) || statusCode >= 500 -> ScrobbleFailureDisposition.RETRY
        else -> ScrobbleFailureDisposition.PERMANENT
    }

internal fun parseRetryAfter(value: String?): Long? {
    val text = value?.trim().orEmpty()
    text.toLongOrNull()?.let { return it.coerceAtLeast(0L) * 1000L }
    val parts = text.split(' ')
    if (parts.size != 6 || parts[5] != "GMT") return null
    val month = MONTHS.indexOf(parts[2]).takeIf { it >= 0 }?.plus(1) ?: return null
    val time = parts[4].split(':').map { it.toIntOrNull() ?: return null }
    if (time.size != 3) return null
    val target =
        runCatching {
            LocalDateTime(
                year = parts[3].toInt(),
                month = Month.entries[month - 1],
                day = parts[1].toInt(),
                hour = time[0],
                minute = time[1],
                second = time[2],
            ).toInstant(TimeZone.UTC).toEpochMilliseconds()
        }.getOrNull() ?: return null
    return (target - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0L)
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
