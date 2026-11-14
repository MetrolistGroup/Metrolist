package com.metrolist.shared.playlistimport

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

suspend fun <T : Any> awaitProviderCredentials(extract: suspend () -> T?): T {
    while (true) {
        currentCoroutineContext().ensureActive()
        val credentials = extract()
        if (credentials != null) return credentials
        delay(1_000)
    }
}
