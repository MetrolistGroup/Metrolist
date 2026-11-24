@file:OptIn(ExperimentalForeignApi::class)

package com.metrolist.shared.cast

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.darwin.NSObject

internal class IosNetServiceCastDiscovery : MdnsCastDiscovery {
    private val lock = Any()
    private var running = false
    private var generation = 0L
    private var callbacks: Callbacks? = null
    private val browsers = mutableMapOf<CastProtocol, NSNetServiceBrowser>()
    private val browserDelegates = mutableMapOf<CastProtocol, BrowserDelegate>()
    private val services = mutableMapOf<String, NSNetService>()
    private val serviceDelegates = mutableMapOf<String, ServiceDelegate>()

    override fun start(
        onDeviceFound: (CastDevice) -> Unit,
        onDeviceLost: (String) -> Unit,
        onError: (CastError) -> Unit,
    ) {
        val startedGeneration =
            synchronized(lock) {
                if (running) return
                running = true
                ++generation
                callbacks = Callbacks(onDeviceFound, onDeviceLost, onError)
                generation
            }
        startBrowsing(CAST_TYPE, CastProtocol.FCAST, startedGeneration)
        startBrowsing(GOOGLE_CAST_TYPE, CastProtocol.GOOGLE_CAST, startedGeneration)
    }

    private fun startBrowsing(
        type: String,
        protocol: CastProtocol,
        startedGeneration: Long,
    ) {
        val browser = NSNetServiceBrowser()
        val delegate = BrowserDelegate(protocol, startedGeneration)
        synchronized(lock) {
            if (!running || generation != startedGeneration) return
            browsers[protocol] = browser
            browserDelegates[protocol] = delegate
        }
        browser.delegate = delegate
        browser.searchForServicesOfType(type, LOCAL_DOMAIN)
    }

    private fun onServiceFound(
        protocol: CastProtocol,
        eventGeneration: Long,
        service: NSNetService,
    ) {
        val id = deviceId(protocol, service.name)
        val shouldResolve =
            synchronized(lock) {
                if (!running || generation != eventGeneration || services.containsKey(id)) {
                    false
                } else {
                    val delegate = ServiceDelegate(id, protocol, eventGeneration)
                    services[id] = service
                    serviceDelegates[id] = delegate
                    service.delegate = delegate
                    true
                }
            }
        if (shouldResolve) service.resolveWithTimeout(5.0)
    }

    private fun onServiceResolved(
        id: String,
        protocol: CastProtocol,
        eventGeneration: Long,
    ) {
        val service =
            synchronized(lock) {
                if (!running || generation != eventGeneration) null else services[id]
            } ?: return
        val host = service.hostName?.takeIf(String::isNotBlank)
        val port = service.port.toInt()
        if (host == null || port <= 0) {
            forgetService(id)
            reportDiscoveryError(eventGeneration)
            return
        }
        val device =
            CastDevice(
                id = id,
                name = service.name.takeIf(String::isNotBlank) ?: id,
                protocol = protocol,
                host = host,
                port = port,
            )
        val onDeviceFound =
            synchronized(lock) {
                if (!running || generation != eventGeneration) null else callbacks?.onDeviceFound
            }
        onDeviceFound?.invoke(device)
    }

    private fun onServiceLost(
        protocol: CastProtocol,
        eventGeneration: Long,
        service: NSNetService,
    ) {
        val id = deviceId(protocol, service.name)
        val onDeviceLost =
            synchronized(lock) {
                if (!running || generation != eventGeneration) {
                    null
                } else {
                    forgetServiceLocked(id)
                    callbacks?.onDeviceLost
                }
            }
        onDeviceLost?.invoke(id)
    }

    private fun onServiceFailed(
        id: String,
        eventGeneration: Long,
    ) {
        forgetService(id)
        reportDiscoveryError(eventGeneration)
    }

    private fun reportDiscoveryError(eventGeneration: Long) {
        val onError =
            synchronized(lock) {
                if (!running || generation != eventGeneration) null else callbacks?.onError
            }
        onError?.invoke(CastError.DISCOVERY_FAILED)
    }

    private fun forgetService(id: String) {
        val service =
            synchronized(lock) {
                forgetServiceLocked(id)
            }
        service?.delegate = null
        service?.stop()
    }

    private fun forgetServiceLocked(id: String): NSNetService? {
        serviceDelegates.remove(id)
        return services.remove(id)
    }

    override fun stop() {
        val (eventGeneration, activeBrowsers, activeServices) =
            synchronized(lock) {
                if (!running) return
                running = false
                val stoppedGeneration = ++generation
                callbacks = null
                val browserList = browsers.values.toList()
                val serviceList = services.values.toList()
                browsers.clear()
                browserDelegates.clear()
                services.clear()
                serviceDelegates.clear()
                Triple(stoppedGeneration, browserList, serviceList)
            }
        activeBrowsers.forEach { browser ->
            browser.delegate = null
            browser.stop()
        }
        activeServices.forEach { service ->
            service.delegate = null
            service.stop()
        }
    }

    private fun deviceId(
        protocol: CastProtocol,
        serviceName: String,
    ): String = "${protocol.name.lowercase()}:$serviceName"

    private data class Callbacks(
        val onDeviceFound: (CastDevice) -> Unit,
        val onDeviceLost: (String) -> Unit,
        val onError: (CastError) -> Unit,
    )

    private inner class BrowserDelegate(
        private val protocol: CastProtocol,
        private val eventGeneration: Long,
    ) : NSObject(), NSNetServiceBrowserDelegateProtocol {
        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didFindService: NSNetService,
            moreComing: Boolean,
        ) = onServiceFound(protocol, eventGeneration, didFindService)

        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didRemoveService: NSNetService,
            moreComing: Boolean,
        ) = onServiceLost(protocol, eventGeneration, didRemoveService)
    }

    private inner class ServiceDelegate(
        private val id: String,
        private val protocol: CastProtocol,
        private val eventGeneration: Long,
    ) : NSObject(), NSNetServiceDelegateProtocol {
        override fun netServiceDidResolveAddress(sender: NSNetService) =
            onServiceResolved(id, protocol, eventGeneration)

        override fun netService(
            sender: NSNetService,
            didNotResolve: Map<Any?, *>,
        ) = onServiceFailed(id, eventGeneration)
    }

    private companion object {
        const val CAST_TYPE = "_fcast._tcp."
        const val GOOGLE_CAST_TYPE = "_googlecast._tcp."
        const val LOCAL_DOMAIN = "local."
    }
}
