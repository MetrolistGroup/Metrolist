package com.metrolist.shared.deviceconnect

/** Accessed under the manager's state mutex; pending sockets cannot occupy every connection slot. */
internal class DeviceConnectIncomingAdmission {
    private val pendingByHost = mutableMapOf<String, Int>()

    fun tryAcquire(host: String): Boolean {
        val count = pendingByHost[host] ?: 0
        if (count >= 2 || pendingByHost.values.sum() >= 4) return false
        pendingByHost[host] = count + 1
        return true
    }

    fun release(host: String) {
        val count = pendingByHost[host] ?: return
        if (count == 1) pendingByHost.remove(host) else pendingByHost[host] = count - 1
    }
}
