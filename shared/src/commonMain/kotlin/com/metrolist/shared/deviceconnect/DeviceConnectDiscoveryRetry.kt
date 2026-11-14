package com.metrolist.shared.deviceconnect

internal class DeviceConnectDiscoveryRetry {
    private var missingPeers = emptySet<String>()
    private var failed = false
    private var intervalMs = INITIAL_INTERVAL_MS
    private var lastRestartMs = 0L

    fun reset(nowMs: Long) {
        intervalMs = INITIAL_INTERVAL_MS
        lastRestartMs = nowMs
    }

    fun shouldRestart(
        nowMs: Long,
        missing: Set<String>,
        discoveryFailed: Boolean = false,
    ): Boolean {
        if (missing != missingPeers || discoveryFailed != failed) {
            failed = discoveryFailed
            missingPeers = missing
            reset(nowMs)
        }
        if ((missing.isEmpty() && !discoveryFailed) || nowMs - lastRestartMs < intervalMs) return false
        lastRestartMs = nowMs
        intervalMs = (intervalMs * 2).coerceAtMost(MAX_INTERVAL_MS)
        return true
    }

    private companion object {
        const val INITIAL_INTERVAL_MS = 30_000L
        const val MAX_INTERVAL_MS = 600_000L
    }
}
