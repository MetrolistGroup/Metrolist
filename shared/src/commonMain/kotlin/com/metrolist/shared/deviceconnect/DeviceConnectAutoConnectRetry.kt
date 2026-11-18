package com.metrolist.shared.deviceconnect

internal class DeviceConnectAutoConnectRetry {
    private data class Attempt(
        val intervalMs: Long = 0,
        val notBeforeMs: Long = 0,
        val inFlight: Boolean = false,
    )

    private val attempts = mutableMapOf<String, Attempt>()

    fun isDue(
        deviceId: String,
        nowMs: Long,
    ): Boolean = attempts[deviceId]?.let { !it.inFlight && nowMs >= it.notBeforeMs } ?: true

    fun begin(
        deviceId: String,
        nowMs: Long,
    ): Boolean {
        if (!isDue(deviceId, nowMs)) return false
        attempts[deviceId] = (attempts[deviceId] ?: Attempt()).copy(inFlight = true)
        return true
    }

    fun failed(
        deviceId: String,
        nowMs: Long,
    ): Boolean {
        val previous = attempts[deviceId] ?: Attempt()
        val interval = if (previous.intervalMs == 0L) 30_000L else (previous.intervalMs * 2).coerceAtMost(600_000L)
        attempts[deviceId] = Attempt(interval, nowMs + interval)
        return previous.intervalMs == 0L
    }

    fun cancel(deviceId: String) {
        attempts[deviceId]?.let { attempts[deviceId] = it.copy(inFlight = false) }
    }

    fun reset(deviceId: String) {
        attempts.remove(deviceId)
    }

    fun clear() {
        attempts.clear()
    }
}
