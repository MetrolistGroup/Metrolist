package com.metrolist.shared.youtube.playback

import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.security.MessageDigest
import kotlin.time.Duration.Companion.milliseconds

class JvmPlaybackNetworkMonitor(
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 2_000L,
    private val snapshot: (() -> PlaybackNetworkState)? = null,
    private val playbackUrl: () -> String? = { null },
    private val onNetworkChanged: () -> Unit = {},
) {
    private val playbackActive = MutableStateFlow(true)
    private val foregroundActive = MutableStateFlow(false)

    fun setForegroundActive(active: Boolean) {
        foregroundActive.value = active
    }

    fun setPlaybackActive(active: Boolean) {
        playbackActive.value = active
    }

    private var job: Job? = null
    private var lastState: PlaybackNetworkState? = null

    fun start() {
        if (job?.isActive == true) return
        job =
            scope.launch(Dispatchers.IO) {
                var failureLogged = false
                combine(
                    playbackActive,
                    foregroundActive,
                ) { playing, foreground -> playing || foreground }.distinctUntilChanged().collectLatest { active ->
                    while (isActive) {
                        val state =
                            try {
                                (snapshot?.invoke() ?: snapshotNetworkState(playbackUrl())).also { failureLogged = false }
                            } catch (error: Exception) {
                                if (!failureLogged) {
                                    PlaybackEventLog.error(
                                        PlaybackEventKind.NETWORK,
                                        "PlaybackNetworkMonitor",
                                        "Network snapshot failed; retrying",
                                        error,
                                    )
                                    Logger.w("PlaybackNetworkMonitor", "Network snapshot failed; retrying", error)
                                }
                                failureLogged = true
                                null
                            }
                        if (state != null) {
                            val changed = lastState?.let { it != state } == true
                            lastState = state
                            PlaybackNetworkStateStore.publish(state)
                            if (changed) onNetworkChanged()
                        }
                        delay((if (active) pollIntervalMs else 60_000L).milliseconds)
                    }
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

private fun snapshotNetworkState(playbackUrl: String?): PlaybackNetworkState {
    val interfaces =
        NetworkInterface
            .getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .filter { network -> runCatching { network.isUp }.getOrDefault(false) }
    val proxies =
        listOf("https", "http", "socks").mapNotNull { scheme ->
            System.getProperty("$scheme.proxyHost")?.takeIf(String::isNotBlank)?.let { host ->
                host to System.getProperty("$scheme.proxyPort").orEmpty()
            }
        }
    val hosts =
        proxies.map { it.first }.ifEmpty {
            listOf(runCatching { playbackUrl?.let(::URI)?.host }.getOrNull() ?: "music.youtube.com")
        }
    val addresses = interfaces.flatMap { network -> network.inetAddresses.toList().map { it to network.name } }.toMap()
    return playbackRouteState(hosts, addresses, proxies.joinToString())
}

internal fun playbackRouteState(
    hosts: List<String>,
    interfacesByAddress: Map<InetAddress, String>,
    proxyConfiguration: String = "",
    resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    routeAddress: (InetAddress) -> InetAddress = ::playbackRouteAddress,
): PlaybackNetworkState {
    val routes = playbackRouteAddresses(hosts, interfacesByAddress.keys, resolve, routeAddress)
    if (routes.isEmpty()) return PlaybackNetworkState(scope = "offline", connected = false)
    val fingerprint = routes.map { "${interfacesByAddress[it]}:${it.hostAddress}" }.sorted().joinToString(";") + proxyConfiguration
    val digest =
        MessageDigest
            .getInstance("SHA-256")
            .digest(fingerprint.encodeToByteArray())
            .take(8)
            .joinToString("") { byte -> "%02x".format(byte) }
    return PlaybackNetworkState(scope = "network-$digest", connected = true)
}

/** UDP connect asks the OS to select a route without sending a packet. */
private fun playbackRouteAddress(destination: InetAddress): InetAddress =
    DatagramSocket().use { socket ->
        socket.connect(InetSocketAddress(destination, 443))
        socket.localAddress
    }

internal fun hasPlaybackRoute(
    hosts: List<String>,
    activeAddresses: Set<InetAddress>,
    resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    routeAddress: (InetAddress) -> InetAddress = ::playbackRouteAddress,
): Boolean = playbackRouteAddresses(hosts, activeAddresses, resolve, routeAddress).isNotEmpty()

private fun playbackRouteAddresses(
    hosts: List<String>,
    activeAddresses: Set<InetAddress>,
    resolve: (String) -> List<InetAddress>,
    routeAddress: (InetAddress) -> InetAddress,
): Set<InetAddress> =
    hosts
        .flatMap { host ->
            runCatching { resolve(host) }.getOrDefault(emptyList()).mapNotNull { destination ->
                runCatching {
                    routeAddress(destination).takeIf { local ->
                        !local.isAnyLocalAddress && (local in activeAddresses || (destination.isLoopbackAddress && local.isLoopbackAddress))
                    }
                }.getOrNull()
            }
        }.toSet()
