package com.metrolist.shared.youtube.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JvmPlaybackNetworkMonitorTest {
    private lateinit var previousNetwork: PlaybackNetworkState

    @BeforeTest
    fun saveNetworkState() {
        previousNetwork = PlaybackNetworkStateStore.current
    }

    @AfterTest
    fun restoreNetworkState() {
        PlaybackNetworkStateStore.publish(previousNetwork)
    }

    @Test
    fun showingTheAppDetectsReconnectionWithoutActivePlayback(): Unit =
        runBlocking {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val connected = AtomicBoolean(false)
            val monitor =
                JvmPlaybackNetworkMonitor(scope, pollIntervalMs = 10, snapshot = {
                    PlaybackNetworkState("foreground-test", connected.get())
                })
            try {
                monitor.setPlaybackActive(false)
                monitor.start()
                withTimeout(5_000) { PlaybackNetworkStateStore.state.first { it.scope == "foreground-test" && !it.connected } }
                connected.set(true)
                monitor.setForegroundActive(true)
                withTimeout(5_000) { PlaybackNetworkStateStore.state.first { it.scope == "foreground-test" && it.connected } }
            } finally {
                monitor.stop()
                scope.coroutineContext[Job]!!.cancelAndJoin()
            }
        }

    @Test
    fun anActiveBridgeWithoutAServiceRouteIsOffline() {
        val bridge = InetAddress.getByName("192.0.2.1")
        val wifi = InetAddress.getByName("192.0.2.2")
        val service = InetAddress.getByName("198.51.100.1")
        val hosts = listOf("music.youtube.com")
        assertFalse(hasPlaybackRoute(hosts, setOf(bridge), { listOf(service) }, { throw SocketException("No route") }))
        assertFalse(hasPlaybackRoute(hosts, setOf(bridge), { listOf(service) }, { wifi }))
        assertTrue(hasPlaybackRoute(hosts, setOf(bridge, wifi), { listOf(service) }, { wifi }))
    }

    @Test
    fun routeChecksTryEachAddressAndSupportALocalProxy() {
        val wifi = InetAddress.getByName("192.0.2.2")
        val ipv4 = InetAddress.getByName("198.51.100.1")
        val ipv6 = InetAddress.getByName("2001:db8::1")
        assertTrue(
            hasPlaybackRoute(listOf("music.youtube.com"), setOf(wifi), { listOf(ipv6, ipv4) }) {
                if (it == ipv6) throw SocketException("IPv6 unavailable")
                wifi
            },
        )
        val loopback = InetAddress.getLoopbackAddress()
        assertTrue(hasPlaybackRoute(listOf("localhost"), setOf(wifi), { listOf(loopback) }, { loopback }))
        assertFalse(hasPlaybackRoute(listOf("music.youtube.com"), setOf(wifi), { error("DNS unavailable") }, { wifi }))
    }

    @Test
    fun onlyRoutedInterfacesAndSourceAddressesAffectTheFingerprint() {
        val wifi = InetAddress.getByName("192.0.2.2")
        val bridge = InetAddress.getByName("192.0.2.3")
        val vpn = InetAddress.getByName("192.0.2.4")
        val destination = InetAddress.getByName("198.51.100.1")
        val hosts = listOf("stream.example.test")

        fun snapshot(
            addresses: Map<InetAddress, String>,
            source: InetAddress,
            proxy: String = "",
        ) = playbackRouteState(hosts, addresses, proxy, { listOf(destination) }, { source })
        val original = snapshot(mapOf(wifi to "wlan0"), wifi)
        val withVirtualInterfaces = mapOf(wifi to "wlan0", bridge to "veth123", vpn to "tailscale0")
        assertEquals(original, snapshot(withVirtualInterfaces, wifi))
        assertEquals(original, snapshot(withVirtualInterfaces + (bridge to "br-new"), wifi))
        assertNotEquals(original, snapshot(withVirtualInterfaces, vpn))
        assertNotEquals(original, snapshot(mapOf(wifi to "eth0"), wifi))
        assertNotEquals(original, snapshot(withVirtualInterfaces, wifi, "localhost:8080"))
        assertFalse(snapshot(mapOf(bridge to "docker0"), wifi).connected)
    }

    @Test
    fun dnsAddressOrderDoesNotCauseNetworkChanges() {
        val local4 = InetAddress.getByName("192.0.2.2")
        val local6 = InetAddress.getByName("2001:db8::2")
        val destinations = listOf(InetAddress.getByName("198.51.100.1"), InetAddress.getByName("2001:db8::1"))
        val interfaces = mapOf(local4 to "wlan0", local6 to "wlan0")

        fun snapshot(destinations: List<InetAddress>) =
            playbackRouteState(
                listOf("stream.example.test"),
                interfaces,
                resolve = { destinations },
                routeAddress = { if (it.address.size == 4) local4 else local6 },
            )
        assertEquals(snapshot(destinations), snapshot(destinations.reversed()))
    }

    @Test
    fun retriesAfterSnapshotFailure() {
        runBlocking {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val attempts = AtomicInteger()
            val initialState = PlaybackNetworkState("network-initial")
            val recoveredState = PlaybackNetworkState("network-recovered")
            PlaybackNetworkStateStore.publish(initialState)
            val monitor =
                JvmPlaybackNetworkMonitor(scope, pollIntervalMs = 10, snapshot = {
                    if (attempts.incrementAndGet() == 1) {
                        throw SocketException("GetIfTable2 failed")
                    }
                    recoveredState
                })
            try {
                monitor.start()
                withTimeout(5_000) { PlaybackNetworkStateStore.state.first { it == recoveredState } }
            } finally {
                monitor.stop()
                scope.coroutineContext[Job]!!.cancelAndJoin()
            }
        }
    }
}
