package com.metrolist.shared.deviceconnect

/** Discovery candidates are untrusted until the connection authenticates. Access under stateMutex. */
internal class DeviceConnectEndpoints {
    private val candidates = mutableMapOf<String, MutableList<DiscoveredDevice>>()
    private val authenticatedIds = mutableSetOf<String>()

    fun discover(device: DiscoveredDevice) {
        val endpoints = candidates.getOrPut(device.deviceId) { mutableListOf() }
        val index = endpoints.indexOfFirst { it.host == device.host && it.port == device.port }
        if (index >= 0) {
            endpoints[index] = device
        } else if (endpoints.size < 8) {
            // Prefer direct P2P/Hotspot/Tether IPv4 -> standard LAN IPv4 -> IPv6 -> Bluetooth RFCOMM fallback.
            // An authenticated endpoint keeps the head; failed() still rotates candidates afterwards.
            val rank = endpointRank(device.host)
            val minIndex = if (device.deviceId in authenticatedIds) 1 else 0
            val insertBefore =
                (minIndex until endpoints.size).firstOrNull { idx ->
                    endpointRank(endpoints[idx].host) > rank
                }
            if (insertBefore != null) {
                endpoints.add(insertBefore, device)
            } else {
                endpoints.add(device)
            }
        }
    }

    private fun endpointRank(host: String): Int =
        when {
            isDeviceConnectBluetoothHost(host) -> 3
            ':' in host -> 2
            isDirectLocalSubnetworkIpv4(host) -> 0
            else -> 1
        }

    fun authenticated(peer: DeviceConnectManager.PeerDevice) {
        if (!hasAvailableDeviceConnectEndpoint(peer)) return
        val endpoints = candidates.getOrPut(peer.id) { mutableListOf() }
        endpoints.removeAll { it.host == peer.host && it.port == peer.port }
        endpoints.add(0, DiscoveredDevice(peer.serviceName, peer.id, peer.name, peer.host, peer.port, peer.deviceType))
        authenticatedIds += peer.id
        while (endpoints.size > 8) endpoints.removeAt(endpoints.lastIndex)
    }

    fun first(deviceId: String): DiscoveredDevice? = candidates[deviceId]?.firstOrNull()

    fun failed(
        deviceId: String,
        host: String,
        port: Int,
    ) {
        val endpoints = candidates[deviceId] ?: return
        val index = endpoints.indexOfFirst { it.host == host && it.port == port }
        if (index >= 0) endpoints.add(endpoints.removeAt(index))
    }

    fun lost(device: DiscoveredDevice) {
        candidates[device.deviceId]?.removeAll {
            it.host == device.host && it.port == device.port && it.serviceName == device.serviceName
        }
    }

    fun removeMatching(predicate: (DiscoveredDevice) -> Boolean) {
        val iterator = candidates.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.removeAll(predicate)
            if (entry.value.isEmpty()) iterator.remove()
        }
    }

    fun clear() {
        candidates.clear()
        authenticatedIds.clear()
    }
}
