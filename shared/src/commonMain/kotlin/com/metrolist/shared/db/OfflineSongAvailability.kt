package com.metrolist.shared.db

fun interface OfflineSongAvailability {
    suspend fun songIds(): Set<String>
}
