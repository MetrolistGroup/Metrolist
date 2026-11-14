package com.metrolist.android.listentogether

internal class ApprovalNotificationAlerts {
    private var seen = emptySet<String>()
    private var lastAlertAt: Long? = null

    fun shouldAlert(
        pending: Set<String>,
        screenVisible: Boolean,
        now: Long,
    ): Boolean {
        val hasNew = (pending - seen).isNotEmpty()
        seen = pending.toSet()
        val alert = !screenVisible && hasNew && (lastAlertAt?.let { now - it >= 10_000L } != false)
        if (alert) lastAlertAt = now
        return alert
    }
}
