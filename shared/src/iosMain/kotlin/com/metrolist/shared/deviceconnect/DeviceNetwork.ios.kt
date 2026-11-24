@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.metrolist.shared.deviceconnect

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_UP
import platform.posix.inet_ntop
import platform.posix.sockaddr_in

internal actual fun getLocalIpV4Addresses(): List<String> = deviceConnectLanIpv4Addresses()

internal fun deviceConnectLanIpv4Addresses(): List<String> =
    memScoped {
        val head = alloc<CPointerVar<ifaddrs>>()
        if (getifaddrs(head.ptr) != 0) return@memScoped emptyList()
        try {
            val result = linkedSetOf<String>()
            var cursor = head.value
            while (cursor != null) {
                val item = cursor.pointed
                val address = item.ifa_addr
                if (
                    address != null &&
                    address.pointed.sa_family.toInt() == AF_INET &&
                    item.ifa_flags.toInt() and IFF_UP != 0 &&
                    item.ifa_flags.toInt() and IFF_LOOPBACK == 0
                ) {
                    val host =
                        ipv4HostAddress(
                            address
                                .reinterpret<sockaddr_in>()
                                .pointed.sin_addr.s_addr,
                        )
                    if (host != null && isDeviceConnectLanHost(host)) {
                        result += host
                    }
                }
                cursor = item.ifa_next
            }
            result.toList()
        } finally {
            freeifaddrs(head.value)
        }
    }

private fun ipv4HostAddress(value: UInt): String? {
    val b0 = (value and 0xffu).toInt()
    val b1 = ((value shr 8) and 0xffu).toInt()
    val b2 = ((value shr 16) and 0xffu).toInt()
    val b3 = ((value shr 24) and 0xffu).toInt()
    val host = "$b0.$b1.$b2.$b3"
    return host.takeIf(::isDeviceConnectLanHost)
}

internal fun isDeviceConnectAllowedClientAddress(value: String?): Boolean {
    val host = value?.substringBefore('%') ?: return false
    if (host.isEmpty() || (host.any { !it.isDigit() && it !in ".:abcdefABCDEF" })) return false
    if (isDeviceConnectLanHost(host)) return true
    val parts = host.split('.')
    if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
        val first = parts[0].toInt()
        val second = parts[1].toInt()
        return host == "127.0.0.1" || (first == 169 && second == 254)
    }
    if (':' in host) {
        val lower = host.lowercase()
        return (lower == "::1") || lower.startsWith("fe80:")
    }
    return false
}
