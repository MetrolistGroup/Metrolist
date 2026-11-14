package com.metrolist.android.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ext.SdkExtensions
import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.CastError
import com.metrolist.shared.cast.CastProtocol
import com.metrolist.shared.cast.MdnsCastDiscovery
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import java.util.ArrayDeque

class AndroidMdnsCastDiscovery(
    context: Context,
) : MdnsCastDiscovery {
    private val nsdManager = context.getSystemService(NsdManager::class.java)

    // Foreground NSD manages multicast reception from T extension 7 onward.
    private val multicastLock =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) < 7
        ) {
            context
                .getSystemService(WifiManager::class.java)
                .createMulticastLock("metrolist-cast-discovery")
                .apply { setReferenceCounted(false) }
        } else {
            null
        }
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val retries = mutableSetOf<Runnable>()

    private data class PendingResolution(
        val service: NsdServiceInfo,
        val protocol: CastProtocol,
        val presence: Long,
    )

    private val pendingResolutions = ArrayDeque<PendingResolution>()
    private var onDeviceFound: ((CastDevice) -> Unit)? = null
    private var onDeviceLost: ((String) -> Unit)? = null
    private var onError: ((CastError) -> Unit)? = null
    private var activeResolution: NsdManager.ResolveListener? = null
    private val presentServices = mutableMapOf<String, Long>()
    private var presence = 0L
    private var running = false
    private var generation = 0L

    override fun start(
        onDeviceFound: (CastDevice) -> Unit,
        onDeviceLost: (String) -> Unit,
        onError: (CastError) -> Unit,
    ) {
        handler.post {
            if (running) return@post
            running = true
            generation++
            this.onError = onError
            this.onDeviceFound = onDeviceFound
            this.onDeviceLost = onDeviceLost
            runCatching { multicastLock?.acquire() }
            discover(FCAST_TYPE, CastProtocol.FCAST)
            discover(GOOGLE_CAST_TYPE, CastProtocol.GOOGLE_CAST)
        }
    }

    private fun discover(
        serviceType: String,
        protocol: CastProtocol,
        attempt: Int = 0,
    ) {
        val discoveryGeneration = generation
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) = Unit

                override fun onServiceFound(service: NsdServiceInfo) {
                    handler.post {
                        if (!running || generation != discoveryGeneration || this !in listeners) return@post
                        val token = presentServices.getOrPut(deviceId(protocol, service.serviceName)) { ++presence }
                        pendingResolutions += PendingResolution(service, protocol, token)
                        resolveNext()
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    handler.post {
                        if (!running || generation != discoveryGeneration || this !in listeners) return@post
                        val id = deviceId(protocol, service.serviceName)
                        presentServices.remove(id)
                        pendingResolutions.removeAll { (pending, pendingProtocol) -> deviceId(pendingProtocol, pending.serviceName) == id }
                        onDeviceLost?.invoke(id)
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    handler.post {
                        if (!running || generation != discoveryGeneration || !listeners.remove(this)) return@post
                        stopIfIdle()
                    }
                }

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    handler.post {
                        if (!running || generation != discoveryGeneration || !listeners.remove(this)) return@post
                        runCatching { nsdManager.stopServiceDiscovery(this) }
                            .onFailure { error ->
                                PlaybackEventLog.error(
                                    PlaybackEventKind.NETWORK,
                                    "AndroidCastDiscovery",
                                    "Could not stop failed mDNS discovery",
                                    error,
                                )
                            }
                        if (attempt < 2) {
                            val retry =
                                object : Runnable {
                                    override fun run() {
                                        retries.remove(this)
                                        if (running && generation == discoveryGeneration) discover(serviceType, protocol, attempt + 1)
                                    }
                                }
                            retries += retry
                            handler.postDelayed(retry, 1_000L)
                        } else {
                            onError?.invoke(CastError.DISCOVERY_FAILED)
                        }
                        stopIfIdle()
                    }
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    PlaybackEventLog.error(
                        PlaybackEventKind.NETWORK,
                        "AndroidCastDiscovery",
                        "mDNS discovery stop failed ($serviceType, code $errorCode)",
                        IllegalStateException("mDNS discovery stop failed with code $errorCode"),
                    )
                }
            }
        listeners += listener
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: RuntimeException) {
            listener.onStartDiscoveryFailed(serviceType, NsdManager.FAILURE_INTERNAL_ERROR)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (!running || activeResolution != null) return
        if (pendingResolutions.isEmpty()) return
        val (service, protocol, token) = pendingResolutions.removeFirst()
        val resolutionGeneration = generation
        val listener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    PlaybackEventLog.error(
                        PlaybackEventKind.NETWORK,
                        "AndroidCastDiscovery",
                        "mDNS service resolution failed (${serviceInfo.serviceName}, code $errorCode)",
                        IllegalStateException("mDNS service resolution failed with code $errorCode"),
                    )
                    handler.post { finishResolution(this) }
                }

                override fun onResolutionStopped(serviceInfo: NsdServiceInfo) {
                    handler.post { finishResolution(this) }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    handler.post {
                        if (activeResolution !== this) return@post
                        if (!running || generation != resolutionGeneration ||
                            presentServices[deviceId(protocol, service.serviceName)] != token
                        ) {
                            finishResolution(this)
                            return@post
                        }
                        val friendlyName =
                            serviceInfo.attributes["fn"]
                                ?.toString(Charsets.UTF_8)
                                ?.takeIf(String::isNotBlank)
                                ?: serviceInfo.serviceName
                        onDeviceFound?.invoke(
                            CastDevice(
                                id = deviceId(protocol, serviceInfo.serviceName),
                                name = friendlyName,
                                protocol = protocol,
                                host = requireNotNull(serviceInfo.host.hostAddress),
                                port = serviceInfo.port,
                            ),
                        )
                        finishResolution(this)
                    }
                }
            }
        activeResolution = listener
        try {
            nsdManager.resolveService(service, listener)
        } catch (error: RuntimeException) {
            PlaybackEventLog.error(
                PlaybackEventKind.NETWORK,
                "AndroidCastDiscovery",
                "Could not start mDNS service resolution (${service.serviceName})",
                error,
            )
            finishResolution(listener)
        }
    }

    private fun finishResolution(listener: NsdManager.ResolveListener) {
        if (activeResolution !== listener) return
        activeResolution = null
        resolveNext()
        stopIfIdle()
    }

    override fun stop() {
        handler.post { stopOnMain() }
    }

    private fun stopIfIdle() {
        if (listeners.isEmpty() && retries.isEmpty() && activeResolution == null && pendingResolutions.isEmpty()) {
            stopOnMain()
        }
    }

    private fun stopOnMain() {
        if (!running) return
        running = false
        generation++
        listeners.forEach { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
                .onFailure { error ->
                    PlaybackEventLog.error(
                        PlaybackEventKind.NETWORK,
                        "AndroidCastDiscovery",
                        "Could not stop mDNS discovery",
                        error,
                    )
                }
        }
        listeners.clear()
        retries.forEach(handler::removeCallbacks)
        retries.clear()
        pendingResolutions.clear()
        presentServices.clear()
        // Keep the slot until a terminal callback, even across discovery generations.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7
            )
        ) {
            activeResolution?.let { listener ->
                runCatching { nsdManager.stopServiceResolution(listener) }
                    .onFailure { error ->
                        PlaybackEventLog.error(
                            PlaybackEventKind.NETWORK,
                            "AndroidCastDiscovery",
                            "Could not stop mDNS service resolution",
                            error,
                        )
                    }
            }
        }
        onError = null
        onDeviceFound = null
        onDeviceLost = null
        if (multicastLock?.isHeld == true) multicastLock.release()
    }

    private fun deviceId(
        protocol: CastProtocol,
        serviceName: String,
    ): String = "${protocol.name.lowercase()}:$serviceName"

    private companion object {
        const val FCAST_TYPE = "_fcast._tcp."
        const val GOOGLE_CAST_TYPE = "_googlecast._tcp."
    }
}
