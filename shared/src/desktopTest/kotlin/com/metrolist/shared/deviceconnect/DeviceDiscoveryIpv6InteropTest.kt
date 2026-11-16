/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.deviceconnect

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceDiscoveryIpv6InteropTest {
    @Test
    fun dualStackControllerAndIpv6OnlyPeerDiscoverEachOther() {
        assumeTrue(System.getenv("METROLIST_TEST_IPV6_DISCOVERY") == "1")
        val addresses = desktopDeviceConnectDiscoveryAddresses()
        assumeTrue("Needs a dual-stack LAN", addresses.any { it is Inet4Address } && addresses.any { it is Inet6Address })
        val ipv6 = addresses.filterIsInstance<Inet6Address>().first()
        val foundPeer = CompletableFuture<DiscoveredDevice>()
        val foundController = CompletableFuture<ServiceInfo>()
        val failures = ConcurrentLinkedQueue<String>()
        val controller = DeviceDiscoveryController(Unit, onError = { message, _ -> failures += message })
        val type = "${DeviceConnectManager.SERVICE_TYPE}local."
        val peer = JmDNS.create(ipv6, "Metrolist-ipv6-peer-${UUID.randomUUID()}")
        try {
            peer.addServiceListener(
                type,
                object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) = Unit

                    override fun serviceRemoved(event: ServiceEvent) = Unit

                    override fun serviceResolved(event: ServiceEvent) {
                        if (event.info.getPropertyString(DEVICE_CONNECT_DEVICE_ID_TXT_KEY) == "dual-stack-controller" &&
                            event.info.inet6Addresses.isNotEmpty()
                        ) {
                            foundController.complete(event.info)
                        }
                    }
                },
            )
            peer.registerService(
                ServiceInfo.create(
                    type,
                    "metrolist-ipv6-only-${UUID.randomUUID()}",
                    40_001,
                    0,
                    0,
                    deviceConnectDiscoveryTxtRecords("ipv6-only-peer", "IPv6 peer", DeviceConnectDeviceType.DESKTOP),
                ),
            )
            controller.registerService("dual-stack-controller", "Dual-stack controller", 40_000, DeviceConnectDeviceType.DESKTOP)
            controller.startDiscovery({ if (it.deviceId == "ipv6-only-peer") foundPeer.complete(it) }, {})
            val discovered = foundPeer.get(20, TimeUnit.SECONDS)
            assertTrue(InetAddress.getByName(discovered.host) is Inet6Address)
            assertEquals("IPv6 peer", discovered.deviceName)
            assertEquals(40_001, discovered.port)
            val reverse = foundController.get(20, TimeUnit.SECONDS)
            assertTrue(reverse.inet4Addresses.isEmpty())
            assertTrue(reverse.inet6Addresses.isNotEmpty())
            assertEquals(40_000, reverse.port)
            assertTrue(failures.isEmpty(), failures.joinToString())
            println("mDNS IPv6 interoperability: dual-stack controller and IPv6-only peer resolved each other's TXT records")
        } finally {
            try {
                runBlocking { controller.close() }
            } finally {
                peer.close()
            }
        }
    }
}
