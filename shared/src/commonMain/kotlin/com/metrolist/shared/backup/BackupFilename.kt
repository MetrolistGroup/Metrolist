package com.metrolist.shared.backup

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

private val BACKUP_TIMESTAMP_FORMAT =
    LocalDateTime.Format {
        year()
        monthNumber()
        day()
        char('-')
        hour()
        minute()
        second()
    }

fun defaultBackupFilename(
    prefix: String,
    instant: Instant = Clock.System.now(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String = "$prefix-${BACKUP_TIMESTAMP_FORMAT.format(instant.toLocalDateTime(timeZone))}"
