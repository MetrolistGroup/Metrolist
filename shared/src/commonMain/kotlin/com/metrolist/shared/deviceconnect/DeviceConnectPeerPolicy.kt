package com.metrolist.shared.deviceconnect

internal const val DEVICE_CONNECT_BT_SCHEME = "bt"
internal const val DEVICE_CONNECT_BT_PREFIX = "bt://"
internal const val DEVICE_CONNECT_BT_PORT = 1
internal const val DEVICE_CONNECT_BT_SERVICE_UUID = "8f64b3c0-7a1d-4e92-9b5a-3c1e2d4f6a80"
internal const val DEVICE_CONNECT_UNKNOWN_BT_ID_PREFIX = "bt:"

internal fun unknownBluetoothDeviceId(mac: String): String {
    val normalized = requireNotNull(normalizeBluetoothMac(mac)) { "Invalid Bluetooth MAC: $mac" }
    return "$DEVICE_CONNECT_UNKNOWN_BT_ID_PREFIX$normalized"
}

internal fun isUnknownBluetoothDeviceId(
    deviceId: String,
    host: String? = null,
): Boolean {
    if (!deviceId.startsWith(DEVICE_CONNECT_UNKNOWN_BT_ID_PREFIX)) return false
    val rawMac = deviceId.removePrefix(DEVICE_CONNECT_UNKNOWN_BT_ID_PREFIX)
    val normalized = normalizeBluetoothMac(rawMac) ?: return false
    if (deviceId != "$DEVICE_CONNECT_UNKNOWN_BT_ID_PREFIX$normalized") return false
    return host == null || host == "$DEVICE_CONNECT_BT_PREFIX$normalized"
}

internal fun DeviceConnectManager.PeerState.isLiveConnectionState(): Boolean =
    this == DeviceConnectManager.PeerState.CONNECTING ||
        this == DeviceConnectManager.PeerState.HANDSHAKING ||
        this == DeviceConnectManager.PeerState.CONNECTED

internal fun peerAfterFailedDeviceConnectAttempt(
    current: DeviceConnectManager.PeerDevice?,
    attemptedHost: String,
    attemptedPort: Int,
    attemptedServiceName: String,
    failedEndpointState: DeviceConnectManager.PeerState? = null,
): DeviceConnectManager.PeerDevice? =
    when {
        current == null -> {
            null
        }

        current.state != DeviceConnectManager.PeerState.CONNECTING -> {
            current
        }

        current.host == attemptedHost &&
            current.port == attemptedPort &&
            (current.serviceName == attemptedServiceName || current.serviceName.isBlank()) -> {
            failedEndpointState?.let { current.copy(state = it) }
        }

        else -> {
            current.copy(state = DeviceConnectManager.PeerState.DISCOVERED)
        }
    }

internal fun peerAfterLostDeviceConnectEndpoint(
    current: DeviceConnectManager.PeerDevice,
    lost: DiscoveredDevice,
): DeviceConnectManager.PeerDevice? {
    val exactEndpoint =
        current.serviceName == lost.serviceName &&
            current.host == lost.host &&
            current.port == lost.port
    if (!exactEndpoint) return current
    return peerAfterDeviceConnectDiscoveryStopped(current)
}

internal fun peerAfterDeviceConnectDiscoveryStopped(current: DeviceConnectManager.PeerDevice): DeviceConnectManager.PeerDevice? =
    if (current.state.isLiveConnectionState()) current.copy(serviceName = "") else null

internal fun hasAvailableDeviceConnectEndpoint(peer: DeviceConnectManager.PeerDevice): Boolean =
    peer.serviceName.isNotBlank() && peer.host.isNotBlank() && peer.port in 1..65535

internal fun isValidDeviceId(value: String): Boolean =
    value.length in 1..128 &&
        value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

internal fun deviceConnectWebSocketUrl(
    host: String,
    port: Int,
): String {
    val unwrappedHost = host.removePrefix("[").removeSuffix("]")
    val authorityHost =
        if (':' in unwrappedHost) {
            "[${unwrappedHost.replace("%", "%25")}]"
        } else {
            unwrappedHost
        }
    return "ws://$authorityHost:$port/connect"
}

internal fun selectDeviceConnectHost(
    hosts: List<String>,
    localHost: String? = getLocalIpV4Address(),
): String? {
    val cleanHosts =
        hosts
            .map { it.removePrefix("[").removeSuffix("]") }
            .filter(::isDeviceConnectLanHost)
    if (cleanHosts.isEmpty()) return null
    val localPrefix = localHost?.ipv424Prefix()
    return localPrefix?.let { prefix -> cleanHosts.firstOrNull { it.ipv424Prefix() == prefix } }
        ?: cleanHosts.firstOrNull(::isDirectLocalSubnetworkIpv4)
        ?: cleanHosts.first()
}

internal fun isDirectLocalSubnetworkIpv4(value: String): Boolean {
    val parts = value.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.any { c -> !c.isDigit() } || (it != "0" && it.startsWith('0')) }) {
        return false
    }
    val first = parts[0].toIntOrNull()?.takeIf { it in 0..255 } ?: return false
    val second = parts[1].toIntOrNull()?.takeIf { it in 0..255 } ?: return false
    val third = parts[2].toIntOrNull()?.takeIf { it in 0..255 } ?: return false
    parts[3].toIntOrNull()?.takeIf { it in 0..255 } ?: return false
    return (first == 192 && second == 168 && (third == 49 || third == 137 || third == 42 || third == 43)) ||
        (first == 169 && second == 254 && third in 1..254)
}

internal fun normalizeBluetoothMac(value: String): String? {
    val trimmed = value.trim()
    val parts =
        when {
            ':' in trimmed && '-' !in trimmed -> trimmed.split(':')
            '-' in trimmed && ':' !in trimmed -> trimmed.split('-')
            else -> return null
        }
    if (parts.size != 6 || parts.any { it.length != 2 || it.any { ch -> ch !in "0123456789abcdefABCDEF" } }) {
        return null
    }
    val normalized = parts.joinToString(":") { it.uppercase() }
    if (normalized == "00:00:00:00:00:00" || normalized == "FF:FF:FF:FF:FF:FF") return null
    return normalized
}

internal fun isDeviceConnectBluetoothHost(value: String): Boolean {
    if (!value.startsWith(DEVICE_CONNECT_BT_PREFIX)) return false
    val mac = value.removePrefix(DEVICE_CONNECT_BT_PREFIX)
    return normalizeBluetoothMac(mac) == mac
}

internal fun isDeviceConnectPeerHost(value: String): Boolean = isDeviceConnectLanHost(value) || isDeviceConnectBluetoothHost(value)

internal fun isDeviceConnectLanHost(value: String): Boolean {
    val host = value.substringBefore('%')
    if ('%' in value) {
        val zone = value.substringAfter('%')
        if (':' !in host || zone.isEmpty() || zone.any { !it.isLetterOrDigit() && it !in "._-" }) return false
    }
    val ipv4 = host.split('.')
    if (
        ipv4.size == 4 &&
        ipv4.all { part ->
            part.isNotEmpty() &&
                part.all(Char::isDigit) &&
                (part == "0" || !part.startsWith('0')) &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    ) {
        val first = ipv4[0].toInt()
        val second = ipv4[1].toInt()
        val third = ipv4[2].toInt()
        return first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168) ||
            (first == 100 && second in 64..127) || (first == 169 && second == 254 && third in 1..254)
    }
    if (':' !in host || host.any { it !in "0123456789abcdefABCDEF:" }) return false
    val halves = host.split("::")
    if (halves.size > 2) return false
    val hextets = halves.flatMap { if (it.isEmpty()) emptyList() else it.split(':') }
    if (hextets.any { it.length !in 1..4 }) return false
    if (if (halves.size == 2) hextets.size !in 1..7 else hextets.size != 8) return false
    val firstHextet = host.substringBefore(':').toIntOrNull(16) ?: return false
    return firstHextet and 0xfe00 == 0xfc00 || firstHextet and 0xffc0 == 0xfe80
}

private fun String.ipv424Prefix(): String? {
    val parts = split('.')
    if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return null
    return parts.take(3).joinToString(".")
}

internal data class DeviceConnectEndpoint(
    val host: String,
    val port: Int,
)

internal fun parseDeviceConnectEndpoint(value: String): DeviceConnectEndpoint? {
    val input = value.trim()
    if (input.startsWith("$DEVICE_CONNECT_BT_SCHEME:", ignoreCase = true)) {
        val rawMac =
            if (input.startsWith(DEVICE_CONNECT_BT_PREFIX, ignoreCase = true)) {
                input.substring(DEVICE_CONNECT_BT_PREFIX.length)
            } else {
                input.substring(DEVICE_CONNECT_BT_SCHEME.length + 1)
            }
        val mac = normalizeBluetoothMac(rawMac) ?: return null
        return DeviceConnectEndpoint("$DEVICE_CONNECT_BT_PREFIX$mac", DEVICE_CONNECT_BT_PORT)
    }
    val host: String
    val portText: String
    if (input.startsWith("[")) {
        val end = input.indexOf("]:")
        if (end < 0) return null
        host = input.substring(1, end)
        if (':' !in host) return null
        portText = input.substring(end + 2)
    } else {
        if (input.count { it == ':' } != 1) return null
        host = input.substringBefore(':')
        portText = input.substringAfter(':')
    }
    if (!isDeviceConnectLanHost(host) || portText.isEmpty() || portText.any { it !in '0'..'9' }) return null
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return DeviceConnectEndpoint(host, port)
}
