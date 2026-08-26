/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.extensions

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

fun <T> Flow<T>.collect(
    scope: CoroutineScope,
    action: suspend (value: T) -> Unit,
) {
    scope.launch {
        collect(action)
    }
}

fun <T> Flow<T>.collectLatest(
    scope: CoroutineScope,
    action: suspend (value: T) -> Unit,
) {
    scope.launch {
        collectLatest(action)
    }
}

val SilentHandler = CoroutineExceptionHandler { _, _ -> }
