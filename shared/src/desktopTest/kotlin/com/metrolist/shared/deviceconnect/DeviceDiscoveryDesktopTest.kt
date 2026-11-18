/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.deviceconnect

import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceDiscoveryDesktopTest {
    @Test
    fun removedInterfaceRecordFallsBackToAnotherRecordForTheSameDevice() {
        val removed = DiscoveredDevice("service", "device", "", "192.0.2.1", 40_000)
        val replacement = removed.copy(host = "198.51.100.1")
        val unrelated = removed.copy(deviceId = "other", host = "203.0.113.1")

        assertEquals(replacement, desktopDiscoveryReplacement(removed, listOf(unrelated, replacement)))
        assertNull(desktopDiscoveryReplacement(removed, listOf(unrelated)))
    }

    @Test
    fun localAddressFilterAcceptsOnlyPrivateIpv4Ranges() {
        assertTrue(isDeviceConnectLanIpv4(ipv4("10.0.0.1")))
        assertTrue(isDeviceConnectLanIpv4(ipv4("172.16.0.1")))
        assertTrue(isDeviceConnectLanIpv4(ipv4("192.168.0.1")))
        assertFalse(isDeviceConnectLanIpv4(ipv4("172.15.0.1")))
        assertFalse(isDeviceConnectLanIpv4(ipv4("172.32.0.1")))
        assertFalse(isDeviceConnectLanIpv4(ipv4("192.0.2.1")))
    }

    @Test
    fun discoveryAcceptsLanIpv6ButRejectsPublicAndLoopbackAddresses() {
        listOf("fd00::1", "fe80::1234%1", "192.168.1.4").forEach {
            assertTrue(isDeviceConnectLanAddress(InetAddress.getByName(it)), it)
        }
        listOf("::1", "2001:db8::1", "ff02::fb", "127.0.0.1").forEach {
            assertFalse(isDeviceConnectLanAddress(InetAddress.getByName(it)), it)
        }
    }

    @Test
    fun ignoresContainerAndTunnelInterfaces() {
        listOf("docker0", "br-deadbeef", "veth0", "tun0", "wg0", "utun3", "awdl0", "llw0", "anpi1", "bridge0", "ap1").forEach {
            assertFalse(isDeviceConnectLanInterfaceName(it), it)
        }
        listOf("eth0", "enp1s0", "wlan0", "en0", "Wi-Fi", "Ethernet", "Apple Ethernet").forEach {
            assertTrue(isDeviceConnectLanInterfaceName(it), it)
        }
    }

    @Test
    fun ignoresWindowsVirtualAndPeerToPeerAdaptersByNameOrDescription() {
        listOf(
            "vEthernet (Default Switch)",
            "Hyper-V Virtual Ethernet Adapter",
            "Bluetooth Device (Personal Area Network)",
            "Microsoft Wi-Fi Direct Virtual Adapter",
            "Microsoft Hosted Network Adapter",
        ).forEach {
            assertFalse(isDeviceConnectLanInterfaceName(it), it)
            assertFalse(isDeviceConnectLanInterfaceName("eth3", it), it)
        }
        assertTrue(isDeviceConnectLanInterfaceName("eth3", "Intel(R) Wi-Fi 6 AX201"))
    }

    @Test
    fun discoveryUsesOneAddressPerFamilyOnADualStackInterface() {
        val addresses = listOf("fe80::1234%1", "fd00::1", "192.168.1.5", "192.168.1.4").map(InetAddress::getByName)
        val expected = listOf(ipv4("192.168.1.4"), InetAddress.getByName("fd00::1"))
        assertEquals(expected, selectDesktopDiscoveryAddresses(addresses))
        assertEquals(expected, selectDesktopDiscoveryAddresses(addresses.reversed()))
        assertEquals(expected, selectDesktopDiscoveryAddresses(addresses + addresses))
    }

    @Test
    fun discoveryKeepsIpv6OnlyInterfacesAndUsesLinkLocalOnlyWhenNeeded() {
        val linkLocal = InetAddress.getByName("fe80::1234%1")
        val uniqueLocal = InetAddress.getByName("fd00::1")
        val public = InetAddress.getByName("2001:db8::1")
        assertEquals(listOf(uniqueLocal), selectDesktopDiscoveryAddresses(listOf(linkLocal, public, uniqueLocal)))
        assertEquals(listOf(linkLocal), selectDesktopDiscoveryAddresses(listOf(public, linkLocal)))
        assertEquals(emptyList(), selectDesktopDiscoveryAddresses(listOf(public, InetAddress.getLoopbackAddress())))
        assertEquals(emptyList(), selectDesktopDiscoveryAddresses(emptyList()))
    }

    @Test
    fun discoveryKeepsLinkLocalIpv6AlongsideIpv4WhenThereIsNoUla() {
        val ipv4 = ipv4("192.168.1.4")
        val linkLocal = InetAddress.getByName("fe80::1234%1")
        val global = InetAddress.getByName("2001:db8::1")
        assertEquals(listOf(ipv4, linkLocal), selectDesktopDiscoveryAddresses(listOf(global, linkLocal, ipv4)))
    }

    @Test
    fun incompleteLookupCallbacksCannotStartAnotherLookupUntilTheServiceReappears() {
        val lookups = DesktopDiscoveryLookups()
        assertTrue(lookups.start("service"))
        assertFalse(lookups.start("service"))
        lookups.finish("service")
        repeat(100) { assertFalse(lookups.start("service")) }
        lookups.remove("service")
        assertTrue(lookups.start("service"))
        lookups.clear()
        assertTrue(lookups.start("service"))
    }

    @Test
    fun lookupCapacityIsReleasedWithoutRetryingCompletedIncompleteRecords() {
        val lookups = DesktopDiscoveryLookups()
        repeat(32) { assertTrue(lookups.start("service-$it")) }
        assertFalse(lookups.start("next"))
        lookups.finish("service-0")
        assertFalse(lookups.start("service-0"))
        assertTrue(lookups.start("next"))
    }

    @Test
    fun serverAllowsOnlyLoopbackLanOrVpnClients() {
        assertTrue(isDeviceConnectAllowedClientAddress("127.0.0.1"))
        assertTrue(isDeviceConnectAllowedClientAddress("192.168.1.4"))
        listOf("100.64.0.1", "100.127.255.254", "fd7a:115c:a1e0::1", "::ffff:100.64.0.1").forEach {
            assertTrue(isDeviceConnectAllowedClientAddress(it), it)
        }
        listOf("100.63.255.255", "100.128.0.1", "2001:db8::1", "ff02::1", "::ffff:203.0.113.4").forEach {
            assertFalse(isDeviceConnectAllowedClientAddress(it), it)
        }
        assertFalse(isDeviceConnectAllowedClientAddress("203.0.113.4"))
        assertFalse(isDeviceConnectAllowedClientAddress("example.com"))
    }

    @Test
    fun unregisterWorkersFinishEveryInterfaceAndPropagateFailures() {
        val ready = CountDownLatch(3)
        val attempts = AtomicInteger()
        val actions =
            listOf("first", null, "second").map { failure ->
                {
                    attempts.incrementAndGet()
                    ready.countDown()
                    check(ready.await(2, TimeUnit.SECONDS))
                    failure?.let { error(it) }
                    Unit
                }
            }

        val error = assertFails { runDesktopDiscoveryWorkers(actions) }

        assertEquals(3, attempts.get())
        assertEquals(setOf("first", "second"), (listOf(error) + error.suppressed).map { it.message }.toSet())
    }

    @Test
    fun stalledWorkerDoesNotBlockShutdownIndefinitely() {
        val release = CountDownLatch(1)
        try {
            assertFailsWith<TimeoutException> {
                runDesktopDiscoveryWorkers(
                    listOf({
                        release.await()
                    }),
                    timeoutMillis = 20,
                )
            }
        } finally {
            release.countDown()
        }
    }

    private fun ipv4(value: String): Inet4Address = InetAddress.getByName(value) as Inet4Address
}
