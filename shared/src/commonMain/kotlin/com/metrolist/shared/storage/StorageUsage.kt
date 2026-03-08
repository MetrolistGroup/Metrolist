package com.metrolist.shared.storage

fun storageUsageFraction(
    usedBytes: Long,
    totalBytes: Long,
): Float = if (totalBytes <= 0) 0f else (usedBytes.coerceAtLeast(0).toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()

val SongCacheLimitsMb = listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192, 16384)

fun songCacheLimitIndex(limitMb: Int): Int =
    SongCacheLimitsMb.indexOf(limitMb).takeIf { it >= 0 } ?: if (limitMb < 0) SongCacheLimitsMb.lastIndex else 0

fun cacheAllocationBytes(vararg limitsMb: Int): Long =
    limitsMb.sumOf { (if (it < 0) SongCacheLimitsMb.last() else it).toLong() } * 1024 * 1024
