package com.metrolist.shared.deviceconnect

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

internal actual fun getLocalIpV4Addresses(): List<String> = deviceConnectLanIpv4Addresses().mapNotNull { it.hostAddress }

internal fun isDeviceConnectLanIpv4(address: Inet4Address): Boolean =
    !address.isLoopbackAddress && !address.isLinkLocalAddress && address.isSiteLocalAddress

internal fun deviceConnectLanIpv4Addresses(): List<Inet4Address> = deviceConnectLanAddresses().filterIsInstance<Inet4Address>()

internal fun isDeviceConnectLanAddress(address: InetAddress): Boolean =
    !address.isLoopbackAddress && !address.isMulticastAddress && isDeviceConnectLanHost(address.hostAddress.orEmpty())

internal fun deviceConnectLanAddresses(): List<InetAddress> =
    runCatching {
        deviceConnectLanInterfaces()
            .flatMap { it.inetAddresses.toList() }
            .filter(::isDeviceConnectLanAddress)
            .distinct()
            .sortedBy { it.hostAddress }
    }.getOrDefault(emptyList())

internal fun deviceConnectLanInterfaces(): List<NetworkInterface> =
    runCatching {
        NetworkInterface
            .getNetworkInterfaces()
            .toList()
            .filter {
                runCatching {
                    it.isUp &&
                        !it.isLoopback &&
                        !it.isVirtual &&
                        !it.isPointToPoint &&
                        it.supportsMulticast() &&
                        isDeviceConnectLanInterfaceName(it.name, it.displayName.orEmpty())
                }.getOrDefault(false)
            }
    }.getOrDefault(emptyList())

internal fun isDeviceConnectLanInterfaceName(
    name: String,
    displayName: String = name,
): Boolean {
    val normalized = name.lowercase()
    return listOf("br-", "docker", "veth", "tun", "utun", "tap", "ppp", "vpn", "wg", "awdl", "llw", "anpi", "bridge")
        .none(normalized::startsWith) &&
        !Regex("^ap\\d").containsMatchIn(normalized) &&
        listOf("virtual", "vethernet", "hyper-v", "bluetooth", "wi-fi direct", "hosted network")
            .none { it in normalized || it in displayName.lowercase() }
}

internal fun isDeviceConnectAllowedClientAddress(value: String?): Boolean {
    val host = value?.substringBefore('%') ?: return false
    if (host.isEmpty() || host.any { !it.isDigit() && it !in ".:abcdefABCDEF" }) return false
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
    val bytes = address.address
    val isUniqueLocalIpv6 = bytes.size == 16 && bytes.isNotEmpty() && bytes[0].toInt() and 0xfe == 0xfc
    return address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || isUniqueLocalIpv6 ||
        isDeviceConnectLanHost(address.hostAddress.orEmpty())
}
