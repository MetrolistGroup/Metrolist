package com.metrolist.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class StartupRecovery(
    pendingReportId: String?,
    private val save: (Throwable) -> String,
) {
    private val pending = MutableStateFlow(pendingReportId)
    val report = pending.asStateFlow()
    val isRecovering: Boolean get() = pending.value != null
    private var handedOff = false

    @Synchronized
    fun capture(error: Throwable) {
        if (!isRecovering) pending.value = save(error)
    }

    fun run(block: () -> Unit) {
        if (isRecovering) return
        try {
            block()
        } catch (error: Throwable) {
            capture(error)
        }
    }

    @Synchronized
    fun claimReport(hasWindowFocus: Boolean): String? {
        if (!hasWindowFocus || handedOff) return null
        return pending.value?.also { handedOff = true }
    }
}
