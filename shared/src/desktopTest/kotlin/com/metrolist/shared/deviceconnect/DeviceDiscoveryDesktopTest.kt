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
        listOf("docker0", "br-deadbeef", "veth0", "tun0", "wg0").forEach {
            assertFalse(isDeviceConnectLanInterfaceName(it), it)
        }
        listOf("eth0", "enp1s0", "wlan0").forEach {
            assertTrue(isDeviceConnectLanInterfaceName(it), it)
        }
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
