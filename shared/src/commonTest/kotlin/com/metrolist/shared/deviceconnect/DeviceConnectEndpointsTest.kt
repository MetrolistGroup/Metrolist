package com.metrolist.shared.deviceconnect

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceConnectEndpointsTest {
    @Test
    fun spoofedAdvertisementCannotReplaceAuthenticatedEndpointAndFailedCandidatesRotate() {
        val endpoints = DeviceConnectEndpoints()
        val real = DiscoveredDevice("real", "device", "Device", "192.168.1.2", 4000)
        val spoof = real.copy(serviceName = "spoof", host = "192.168.1.3")
        endpoints.discover(spoof)
        endpoints.authenticated(
            DeviceConnectManager.PeerDevice(
                real.deviceId,
                real.deviceName,
                real.host,
                real.port,
                DeviceConnectManager.PeerState.CONNECTED,
                serviceName = real.serviceName,
            ),
        )
        endpoints.discover(spoof)
        assertEquals(real, endpoints.first(real.deviceId))
        endpoints.failed(real.deviceId, real.host, real.port)
        assertEquals(spoof, endpoints.first(real.deviceId))
        endpoints.failed(spoof.deviceId, spoof.host, spoof.port)
        assertEquals(real, endpoints.first(real.deviceId))
        endpoints.lost(spoof)
        assertEquals(real, endpoints.first(real.deviceId))
    }

    @Test
    fun ipv4IsPreferredOnDiscoveryWithoutBreakingRotationOrAuthentication() {
        val linkLocal = DiscoveredDevice("svc", "phone", "Phone", "fe80::1%3", 4000)
        val ipv4 = linkLocal.copy(host = "192.168.1.5")

        val endpoints = DeviceConnectEndpoints()
        endpoints.discover(linkLocal)
        endpoints.discover(ipv4)
        assertEquals(ipv4, endpoints.first("phone"))
        endpoints.failed("phone", ipv4.host, ipv4.port)
        assertEquals(linkLocal, endpoints.first("phone"))

        val authenticated = DeviceConnectEndpoints()
        authenticated.authenticated(
            DeviceConnectManager.PeerDevice(
                "phone",
                "Phone",
                linkLocal.host,
                linkLocal.port,
                DeviceConnectManager.PeerState.CONNECTED,
                serviceName = linkLocal.serviceName,
            ),
        )
        authenticated.discover(ipv4)
        assertEquals(linkLocal.host, authenticated.first("phone")?.host)
    }
}
