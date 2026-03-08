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
            // Prefer IPv4 on discovery: link-local IPv6 records can be unreachable without the right interface scope.
            // An authenticated endpoint keeps the head; failed() still rotates candidates afterwards.
            val firstIpv6 = endpoints.indexOfFirst { ':' in it.host }
            val minIndex = if (device.deviceId in authenticatedIds) 1 else 0
            if (':' !in device.host && firstIpv6 >= 0) {
                endpoints.add(maxOf(firstIpv6, minIndex).coerceAtMost(endpoints.size), device)
            } else {
                endpoints.add(device)
            }
        }
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

    fun clear() {
        candidates.clear()
        authenticatedIds.clear()
    }
}
