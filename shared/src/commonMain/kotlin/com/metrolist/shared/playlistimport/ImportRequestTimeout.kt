package com.metrolist.shared.playlistimport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

internal suspend fun <T> withImportRequestTimeout(block: suspend CoroutineScope.() -> T): T {
    // Keep successful nullable responses distinct from this request's deadline.
    val response =
        withTimeoutOrNull(15.seconds) { Result.success(block()) }
            ?: throw ImportException(ImportErrorKey.IncompleteSource)
    return response.getOrThrow()
}
