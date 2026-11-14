package com.metrolist.android.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.DeadSystemException
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import java.security.MessageDigest

class GlobalPlaybackNetworkMonitor(
    context: Context,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val state = DefaultPlaybackNetworkState<Network>()
    private var started = false
    private var httpProxy: String? = null
    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (state.network != network) httpProxy = null
                state.available(network)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                if (state.capabilities(
                        network,
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    )
                ) {
                    publish()
                }
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                if (state.network == network) {
                    httpProxy = linkProperties.httpProxy?.toString()
                    publish()
                }
            }

            override fun onBlockedStatusChanged(
                network: Network,
                blocked: Boolean,
            ) {
                if (state.blocked(network, blocked)) publish()
            }

            override fun onLost(network: Network) {
                if (state.lost(network)) {
                    httpProxy = null
                    publish()
                }
            }
        }

    // Before Android 8 there is no handler overload, so forward callbacks to the main thread ourselves.
    private val registeredCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            callback
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    mainHandler.post { callback.onAvailable(network) }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    mainHandler.post { callback.onCapabilitiesChanged(network, capabilities) }
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) {
                    mainHandler.post { callback.onLinkPropertiesChanged(network, linkProperties) }
                }

                override fun onLost(network: Network) {
                    mainHandler.post { callback.onLost(network) }
                }
            }
        }

    fun start() {
        if (started) return
        withLiveSystem {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                connectivityManager.registerDefaultNetworkCallback(callback, mainHandler)
            } else {
                connectivityManager.registerDefaultNetworkCallback(registeredCallback)
            }
            started = true
            refresh()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        mainHandler.removeCallbacksAndMessages(null)
        withLiveSystem { connectivityManager.unregisterNetworkCallback(registeredCallback) }
    }

    fun refresh() =
        withLiveSystem {
            // activeNetwork may be null while background access is blocked. Only onLost retires a known network.
            connectivityManager.activeNetwork?.let { network ->
                state.available(network)
                state.blocked(network, false)
                httpProxy = connectivityManager.getLinkProperties(network)?.httpProxy?.toString()
                connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                    state.capabilities(
                        network,
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    )
                }
            }
            publish()
        }

    private fun publish() =
        withLiveSystem {
            val network = state.network
            val scope =
                if (network == null) {
                    "offline"
                } else {
                    val fingerprint = "${network.networkHandle}|$httpProxy"
                    val digest =
                        MessageDigest
                            .getInstance("SHA-256")
                            .digest(fingerprint.encodeToByteArray())
                            .take(8)
                            .joinToString("") { byte -> "%02x".format(byte) }
                    "network-$digest"
                }
            PlaybackNetworkStateAdapter.publish(scope, state.connected, state.isCellular)
        }
}

internal class DefaultPlaybackNetworkState<T> {
    var network: T? = null
        private set
    private var internet = false
    private var blocked = false
    var isCellular = false
        private set
    val connected: Boolean get() = network != null && internet && !blocked

    fun available(value: T) {
        if (network == value) return
        network = value
        internet = false
        blocked = false
        isCellular = false
    }

    fun capabilities(
        value: T,
        hasInternet: Boolean,
        isCellular: Boolean = false,
    ): Boolean {
        if (network != value) return false
        // Android validation probes can fail even when YouTube is reachable.
        internet = hasInternet
        this.isCellular = isCellular
        return true
    }

    fun blocked(
        value: T,
        isBlocked: Boolean,
    ): Boolean {
        if (network != value) return false
        blocked = isBlocked
        return true
    }

    fun lost(value: T): Boolean {
        if (network != value) return false
        network = null
        internet = false
        blocked = false
        isCellular = false
        return true
    }
}

internal inline fun withLiveSystem(action: () -> Unit) {
    try {
        action()
    } catch (error: Exception) {
        if (!generateSequence<Throwable>(error) { it.cause }.any { it is DeadSystemException }) throw error
        Timber.w(error, "Network monitor unavailable after system server death")
    }
}
