package com.metrolist.shared.network

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

object PublicArtworkDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !it.isPublicArtworkAddress() }) {
            throw UnknownHostException("Artwork host does not resolve exclusively to public addresses")
        }
        return addresses
    }
}

internal fun InetAddress.isPublicArtworkAddress(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
        return false
    }
    val bytes = address.map(Byte::toInt).map { it and 0xff }
    return when (bytes.size) {
        4 -> {
            when {
                bytes[0] == 0 || bytes[0] == 10 || bytes[0] == 127 || bytes[0] >= 224 -> false
                bytes[0] == 100 && bytes[1] in 64..127 -> false
                bytes[0] == 169 && bytes[1] == 254 -> false
                bytes[0] == 172 && bytes[1] in 16..31 -> false
                bytes[0] == 192 && bytes[1] == 168 -> false
                (bytes[0] == 192 && bytes[1] == 0 && (bytes[2] == 0 || bytes[2] == 2)) -> false
                (bytes[0] == 198 && (bytes[1] in 18..19 || (bytes[1] == 51 && bytes[2] == 100))) -> false
                bytes[0] == 203 && bytes[1] == 0 && bytes[2] == 113 -> false
                else -> true
            }
        }

        16 -> {
            !(
                (bytes[0] and 0xfe == 0xfc) ||
                    (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8)
            )
        }

        else -> {
            false
        }
    }
}
