package com.metrolist.shared.storage

fun audioCacheBudget(
    configuredBytes: Long,
    cachedBytes: Long,
    usableBytes: Long,
    reserveBytes: Long,
    additionalTemporaryBytes: Long = 0L,
    completedTemporaryBytes: Long = 0L,
): Long =
    minOf(
        if (configuredBytes < 0L) Long.MAX_VALUE else configuredBytes,
        (cachedBytes + usableBytes + completedTemporaryBytes - reserveBytes - additionalTemporaryBytes).coerceAtLeast(0L),
    )
