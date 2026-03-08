package com.metrolist.desktop.storage

internal data class DownloadQueueSummary(
    val completed: Int,
    val active: Int,
    val failed: Int,
    val progress: Float,
) {
    val total: Int get() = completed + active + failed
}

internal fun downloadQueueSummary(
    states: Map<String, DownloadState>,
    progresses: Map<String, Float>,
    completed: Int,
): DownloadQueueSummary {
    val active = states.count { it.value == DownloadState.QUEUED || it.value == DownloadState.DOWNLOADING }
    val failed = states.count { it.value == DownloadState.FAILED }
    val total = completed + active + failed
    val inProgress =
        states.entries.sumOf { (id, state) ->
            if (state == DownloadState.DOWNLOADING) (progresses[id] ?: 0f).coerceIn(0f, 1f).toDouble() else 0.0
        }
    return DownloadQueueSummary(
        completed = completed,
        active = active,
        failed = failed,
        progress = if (total == 0) 0f else ((completed + inProgress) / total).toFloat().coerceIn(0f, 1f),
    )
}
