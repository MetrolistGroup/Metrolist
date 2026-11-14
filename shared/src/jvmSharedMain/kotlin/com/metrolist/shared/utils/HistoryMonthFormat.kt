package com.metrolist.shared.utils

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun formatHistoryMonth(
    playedAt: Long,
    locale: Locale = Locale.getDefault(),
): String =
    DateTimeFormatter
        .ofPattern(historyMonthPattern(locale), locale)
        .format(Instant.ofEpochMilli(playedAt).atZone(ZoneId.systemDefault()))

internal expect fun historyMonthPattern(locale: Locale): String
