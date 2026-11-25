@file:OptIn(ExperimentalForeignApi::class)

package com.metrolist.shared.deviceconnect

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_POINTOPOINT
import platform.posix.IFF_UP
import platform.posix.NI_MAXHOST
import platform.posix.NI_NUMERICHOST
import platform.posix.getnameinfo
import platform.posix.sockaddr

internal actual fun getLocalIpV4Addresses(): List<String> =
    memScoped {
        val head = alloc<CPointerVar<ifaddrs>>()
        if (getifaddrs(head.ptr) != 0) return@memScoped emptyList()
        try {
            generateSequence(head.value) { it.pointed.ifa_next }
                .map { it.pointed }
                .filter { (it.ifa_flags.toInt() and (IFF_UP or IFF_LOOPBACK or IFF_POINTOPOINT)) == IFF_UP }
                .mapNotNull { it.ifa_addr?.takeIf { address -> address.pointed.sa_family.toInt() == AF_INET } }
                .mapNotNull(::numericHost)
                .filter(::isDeviceConnectLanHost)
                .distinct()
                .toList()
        } finally {
            freeifaddrs(head.value)
        }
    }

internal fun numericHost(address: CPointer<sockaddr>): String? =
    memScoped {
        val host = allocArray<ByteVar>(NI_MAXHOST)
        val status = getnameinfo(address, address.pointed.sa_len.toUInt(), host, NI_MAXHOST.toUInt(), null, 0u, NI_NUMERICHOST)
        if (status == 0) host.toKString() else null
    }

internal fun isDeviceConnectAllowedClientAddress(value: String?): Boolean {
    val host =
        value?.substringBefore('%')?.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() || c in ".:abcdefABCDEF" } } ?: return false
    return isDeviceConnectLanHost(host) || host == "::1" || host.startsWith("127.") || host.startsWith("169.254.")
}
