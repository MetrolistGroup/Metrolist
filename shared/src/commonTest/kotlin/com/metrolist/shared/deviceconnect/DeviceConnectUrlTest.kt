package com.metrolist.shared.deviceconnect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceConnectUrlTest {
    @Test
    fun parsesManualLanAndVpnEndpoints() {
        for (host in listOf("10.1.2.3", "172.16.0.1", "192.168.1.4", "100.64.0.1", "100.127.255.254", "169.254.10.20")) {
            assertEquals(DeviceConnectEndpoint(host, 43210), parseDeviceConnectEndpoint(" $host:43210 "))
            assertTrue(isDeviceConnectLanHost(host))
        }
        for (host in listOf("fd7a:115c:a1e0::1", "fe80::1%wlan0")) {
            assertEquals(DeviceConnectEndpoint(host, 65535), parseDeviceConnectEndpoint("[$host]:65535"))
        }
    }

    @Test
    fun parsesAndNormalizesBluetoothFallbackEndpoints() {
        assertEquals(
            DeviceConnectEndpoint("bt://AA:BB:CC:DD:EE:FF", DEVICE_CONNECT_BT_PORT),
            parseDeviceConnectEndpoint(" bt://aa-bb-cc-dd-ee-ff "),
        )
        assertEquals(
            DeviceConnectEndpoint("bt://12:34:56:78:9A:BC", DEVICE_CONNECT_BT_PORT),
            parseDeviceConnectEndpoint("BT:12:34:56:78:9a:bc"),
        )
        assertTrue(isDeviceConnectPeerHost("bt://AA:BB:CC:DD:EE:FF"))
        assertFalse(isDeviceConnectLanHost("bt://AA:BB:CC:DD:EE:FF"))

        listOf(
            "bt://00:00:00:00:00:00",
            "bt://FF:FF:FF:FF:FF:FF",
            "bt://not-a-mac",
            "bt://AA:BB:CC:DD:EE",
            "bt://GG:11:22:33:44:55",
            "bt:",
        ).forEach {
            assertNull(parseDeviceConnectEndpoint(it), it)
        }
    }

    @Test
    fun rejectsMalformedOrNonlocalManualEndpoints() {
        listOf(
            "100.63.255.255:80",
            "100.128.0.1:80",
            "8.8.8.8:80",
            "127.0.0.1:80",
            "[2001:db8::1]:80",
            "[::1]:80",
            "[ff02::1]:80",
            "example.com:80",
            "100.64.0.1",
            "100.64.0.1:0",
            "100.64.0.1:65536",
            "100.64.0.1:-1",
            "100.64.0.1:+80",
            "100.64.0.1:999999999999",
            "100.64.0.1:",
            "100.64.0.1:80/connect",
            "user@100.64.0.1:80",
            "ws://100.64.0.1:80",
            "100.064.0.1:80",
            "100.64.0.256:80",
            "[100.64.0.1]:80",
            "fd00::1:80",
            "[fd00::1]80",
            "[fd00::1]:80:90",
            "[fd00::1%]@example.com]:80",
            "169.254.0.5:43210",
            "169.254.255.5:43210",
        ).forEach { assertNull(parseDeviceConnectEndpoint(it), it) }
    }

    @Test
    fun formatsIpv4Host() {
        assertEquals("ws://192.168.1.20:8765/connect", deviceConnectWebSocketUrl("192.168.1.20", 8765))
    }

    @Test
    fun bracketsIpv6Host() {
        assertEquals("ws://[2001:db8::20]:8765/connect", deviceConnectWebSocketUrl("2001:db8::20", 8765))
    }

    @Test
    fun encodesIpv6ScopeZone() {
        assertEquals("ws://[fe80::20%25wlan0]:8765/connect", deviceConnectWebSocketUrl("fe80::20%wlan0", 8765))
    }

    @Test
    fun prefersHostOnSameLanPrefix() {
        assertEquals(
            "192.168.1.44",
            selectDeviceConnectHost(listOf("10.8.0.44", "192.168.1.44"), localHost = "192.168.1.20"),
        )
    }

    @Test
    fun fallsBackWhenNoLanPrefixMatches() {
        assertEquals(
            "10.8.0.44",
            selectDeviceConnectHost(listOf("10.8.0.44", "172.16.0.44"), localHost = "192.168.1.20"),
        )
    }

    @Test
    fun prefersDirectLocalSubnetsWhenNoPrefixMatches() {
        assertEquals(
            "192.168.49.1",
            selectDeviceConnectHost(listOf("10.8.0.44", "192.168.49.1"), localHost = "192.168.1.20"),
        )
        assertEquals(
            "192.168.137.1",
            selectDeviceConnectHost(listOf("10.8.0.44", "192.168.137.1"), localHost = "192.168.1.20"),
        )
        assertEquals(
            "169.254.10.20",
            selectDeviceConnectHost(listOf("172.16.0.44", "169.254.10.20"), localHost = "192.168.1.20"),
        )
    }

    @Test
    fun rejectsDiscoveryAddressesOutsideTheLan() {
        assertNull(selectDeviceConnectHost(listOf("203.0.113.4", "127.0.0.1", "not-an-address")))
        assertTrue(isDeviceConnectLanHost("fd00::4"))
        assertTrue(isDeviceConnectLanHost("fe80::4%wlan0"))
        assertFalse(isDeviceConnectLanHost("2001:db8::4"))
        listOf(
            "fd00",
            "fd00:1",
            "fd00:::1",
            "fd00::1::2",
            "fd00:1:2:3:4:5:6:7:8",
            "fd00::12345",
            "fd00::1%]@example.com",
            "192.168.1.4%eth0",
        ).forEach {
            assertFalse(isDeviceConnectLanHost(it), it)
        }
        assertTrue(isDeviceConnectLanHost("fd00:1:2:3:4:5:6:7"))
    }
}
