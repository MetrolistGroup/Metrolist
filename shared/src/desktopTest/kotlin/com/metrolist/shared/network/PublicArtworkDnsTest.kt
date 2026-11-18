package com.metrolist.shared.network

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PublicArtworkDnsTest {
    @Test
    fun officialCdnNamesKeepVpnDnsMappingsWithoutAllowingPrivateArbitraryHosts() {
        val addresses = listOf(InetAddress.getByName("198.18.0.1"))
        for (host in listOf("lh3.googleusercontent.com", "yt3.ggpht.com", "i.ytimg.com", "I.YTIMG.COM.")) {
            assertSame(addresses, artworkAddresses(host, addresses))
        }
        for (host in listOf("localhost", "example.com", "i.ytimg.com.example.com", "notgoogleusercontent.com")) {
            assertFailsWith<UnknownHostException> { artworkAddresses(host, addresses) }
        }
        assertFailsWith<UnknownHostException> { artworkAddresses("i.ytimg.com", emptyList()) }
    }

    @Test
    fun rejectsNonPublicAddresses() {
        listOf(
            "127.0.0.1",
            "10.0.0.1",
            "100.64.0.1",
            "169.254.169.254",
            "192.168.1.1",
            "198.18.0.1",
            "::1",
            "fd00::1",
        ).forEach { assertFalse(InetAddress.getByName(it).isPublicArtworkAddress(), it) }
    }

    @Test
    fun acceptsPublicAddresses() {
        listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111")
            .forEach { assertTrue(InetAddress.getByName(it).isPublicArtworkAddress(), it) }
    }
}
