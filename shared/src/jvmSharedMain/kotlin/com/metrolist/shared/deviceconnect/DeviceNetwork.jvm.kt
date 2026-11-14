package com.metrolist.shared.deviceconnect

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

internal actual fun getLocalIpV4Addresses(): List<String> = deviceConnectLanIpv4Addresses().mapNotNull { it.hostAddress }

internal fun isDeviceConnectLanIpv4(address: Inet4Address): Boolean =
    !address.isLoopbackAddress &&
        !address.isMulticastAddress &&
        (address.isSiteLocalAddress || isDeviceConnectLanHost(address.hostAddress.orEmpty()))

internal fun deviceConnectLanIpv4Addresses(): List<Inet4Address> = deviceConnectLanAddresses().filterIsInstance<Inet4Address>()

internal fun isDeviceConnectLanAddress(address: InetAddress): Boolean =
    !address.isLoopbackAddress && !address.isMulticastAddress && isDeviceConnectLanHost(address.hostAddress.orEmpty())

internal fun deviceConnectLanAddresses(): List<InetAddress> =
    runCatching {
        NetworkInterface
            .getNetworkInterfaces()
            .toList()
            .filter {
                runCatching {
                    it.isUp &&
                        !it.isLoopback &&
                        isDeviceConnectLanInterfaceName(it.name) &&
                        ((!it.isVirtual && it.supportsMulticast()) || isDirectLocalNetworkInterface(it.name, it.displayName.orEmpty()))
                }.getOrDefault(false)
            }.flatMap { it.inetAddresses.toList() }
            .filter(::isDeviceConnectLanAddress)
            .distinct()
            .sortedWith(
                compareBy<InetAddress>(
                    { if (isDirectLocalSubnetworkIpv4(it.hostAddress.orEmpty())) 0 else 1 },
                    { it.hostAddress.orEmpty() },
                ),
            )
    }.getOrDefault(emptyList())

internal fun isDirectLocalNetworkInterface(
    name: String,
    displayName: String = "",
): Boolean {
    if (!isDeviceConnectLanInterfaceName(name)) return false
    val lowerName = name.lowercase()
    val lowerDisplay = displayName.lowercase()
    val prefixes = listOf("p2p", "ap", "swlan", "rndis", "ncm", "usb")
    val displayMarkers = listOf("wi-fi direct", "local area connection*", "microsoft wi-fi direct", "rndis", "usb ethernet")
    return prefixes.any(lowerName::startsWith) || displayMarkers.any(lowerDisplay::contains)
}

internal fun isDeviceConnectLanInterfaceName(name: String): Boolean =
    listOf("br-", "docker", "veth", "tun", "tap", "ppp", "vpn", "wg")
        .none(name.lowercase()::startsWith)

internal fun isDeviceConnectAllowedClientAddress(value: String?): Boolean {
    val host = value?.substringBefore('%') ?: return false
    if (host.isEmpty() || host.any { !it.isDigit() && it !in ".:abcdefABCDEF" }) return false
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
    val bytes = address.address
    val isUniqueLocalIpv6 = bytes.size == 16 && bytes.isNotEmpty() && bytes[0].toInt() and 0xfe == 0xfc
    return address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || isUniqueLocalIpv6 ||
        isDeviceConnectLanHost(address.hostAddress.orEmpty())
}
