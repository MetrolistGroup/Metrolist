package com.metrolist.shared.deviceconnect

/** Discovery candidates are untrusted until the connection authenticates. Access under stateMutex. */
internal class DeviceConnectEndpoints {
    private val candidates = mutableMapOf<String, MutableList<DiscoveredDevice>>()
    private val authenticatedIds = mutableSetOf<String>()
    private val boundDeviceIdByBluetoothHost = mutableMapOf<String, String>()

    fun resolveDeviceId(
        deviceId: String,
        host: String,
    ): String =
        if (isUnknownBluetoothDeviceId(deviceId, host) || (deviceId.isBlank() && isDeviceConnectBluetoothHost(host))) {
            boundDeviceIdByBluetoothHost[host]
                ?: unknownBluetoothDeviceId(host.removePrefix(DEVICE_CONNECT_BT_PREFIX))
        } else {
            deviceId
        }

    fun resolveDevice(device: DiscoveredDevice): DiscoveredDevice {
        val resolvedId = resolveDeviceId(device.deviceId, device.host)
        return if (resolvedId == device.deviceId) device else device.copy(deviceId = resolvedId)
    }

    fun discover(device: DiscoveredDevice): DiscoveredDevice {
        val resolved = resolveDevice(device)
        val endpoints = candidates.getOrPut(resolved.deviceId) { mutableListOf() }
        val index = endpoints.indexOfFirst { it.host == resolved.host && it.port == resolved.port }
        if (index >= 0) {
            endpoints[index] = resolved
        } else if (endpoints.size < 8) {
            // Prefer direct P2P/Hotspot/Tether IPv4 -> standard LAN IPv4 -> IPv6 -> Bluetooth RFCOMM fallback.
            // An authenticated endpoint keeps the head; failed() still rotates candidates afterwards.
            val rank = endpointRank(resolved.host)
            val minIndex = if (resolved.deviceId in authenticatedIds) 1 else 0
            val insertBefore =
                (minIndex until endpoints.size).firstOrNull { idx ->
                    endpointRank(endpoints[idx].host) > rank
                }
            if (insertBefore != null) {
                endpoints.add(insertBefore, resolved)
            } else {
                endpoints.add(resolved)
            }
        }
        return resolved
    }

    private fun endpointRank(host: String): Int =
        when {
            isDeviceConnectBluetoothHost(host) -> 3
            ':' in host -> 2
            isDirectLocalSubnetworkIpv4(host) -> 0
            else -> 1
        }

    fun bindBluetoothHost(
        host: String,
        deviceId: String,
    ): List<DiscoveredDevice> {
        if (!isDeviceConnectBluetoothHost(host) || !isValidDeviceId(deviceId)) return emptyList()
        boundDeviceIdByBluetoothHost[host] = deviceId
        val unknownId = unknownBluetoothDeviceId(host.removePrefix(DEVICE_CONNECT_BT_PREFIX))
        val moved = candidates.remove(unknownId).orEmpty()
        return moved.map { candidate ->
            discover(candidate.copy(deviceId = deviceId))
        }
    }

    fun unbindDevice(deviceId: String) {
        boundDeviceIdByBluetoothHost.entries.removeAll { it.value == deviceId }
    }

    fun authenticated(peer: DeviceConnectManager.PeerDevice) {
        if (isDeviceConnectBluetoothHost(peer.host) && isValidDeviceId(peer.id)) {
            bindBluetoothHost(peer.host, peer.id)
        }
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

    fun lost(device: DiscoveredDevice): DiscoveredDevice {
        val resolved = resolveDevice(device)
        candidates[resolved.deviceId]?.removeAll {
            it.host == resolved.host && it.port == resolved.port && it.serviceName == resolved.serviceName
        }
        if (device.deviceId != resolved.deviceId) {
            candidates[device.deviceId]?.removeAll {
                it.host == device.host && it.port == device.port && it.serviceName == device.serviceName
            }
        }
        return resolved
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

    fun reset() {
        clear()
        boundDeviceIdByBluetoothHost.clear()
    }
}
