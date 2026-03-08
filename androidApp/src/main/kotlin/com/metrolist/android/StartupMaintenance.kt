package com.metrolist.android

import kotlinx.coroutines.CancellationException

internal const val STORAGE_AUDIT_INTERVAL_MS = 24 * 60 * 60 * 1000L
internal const val TRACKED_PLAYLIST_SYNC_INTERVAL_MS = 60 * 60 * 1000L

internal fun startupMaintenanceDue(
    lastSuccessMs: Long,
    nowMs: Long,
    intervalMs: Long,
): Boolean = lastSuccessMs <= 0L || lastSuccessMs > nowMs || nowMs - lastSuccessMs >= intervalMs

internal suspend fun runStartupStorageAudit(
    audit: suspend () -> Unit,
    onSuccess: () -> Unit,
    onFailure: (Exception) -> Unit,
) {
    try {
        audit()
        onSuccess()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(error)
    }
}
