package com.metrolist.android.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Keep the old file through cancellation, and retire it only after the new reference is durable. */
internal suspend fun selectCustomFont(
    prepare: () -> Result<AppFontManager.ImportedFont>,
    select: (AppFontManager.ImportedFont) -> String,
    flushSelection: () -> Unit,
    remove: (String) -> Unit,
    onCleanupFailure: (Exception) -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): Result<AppFontManager.ImportedFont> {
    var candidate: AppFontManager.ImportedFont? = null
    var selectionStarted = false
    try {
        val result = withContext(ioDispatcher) { prepare().also { candidate = it.getOrNull() } }
        result.onSuccess { prepared ->
            // If applying settings throws, either reference is possible. Preserve both files.
            selectionStarted = true
            val previous = select(prepared)
            if (previous != prepared.fileName) {
                retireCustomFont(previous, flushSelection, remove, onCleanupFailure, ioDispatcher)
            }
        }
        return result
    } finally {
        if (!selectionStarted) {
            val discarded = candidate?.fileName
            if (discarded != null) runFontCleanup(ioDispatcher, onCleanupFailure) { remove(discarded) }
        }
    }
}

internal suspend fun retireCustomFont(
    fileName: String,
    flushSelection: () -> Unit,
    remove: (String) -> Unit,
    onCleanupFailure: (Exception) -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    if (fileName.isBlank()) return
    runFontCleanup(ioDispatcher, onCleanupFailure) {
        flushSelection()
        remove(fileName)
    }
}

private suspend fun runFontCleanup(
    ioDispatcher: CoroutineDispatcher,
    onCleanupFailure: (Exception) -> Unit,
    action: () -> Unit,
) = withContext(NonCancellable + ioDispatcher) {
    try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onCleanupFailure(error)
    }
}
