@file:OptIn(ExperimentalForeignApi::class)

package com.metrolist.shared.cast

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readBytes
import platform.Foundation.NSLock
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

internal class IosNetServiceCastDiscovery : MdnsCastDiscovery {
    private val lock = NSLock()
    private var running = false
    private var generation = 0L
    private var callbacks: Callbacks? = null
    private val browsers = mutableMapOf<CastProtocol, NSNetServiceBrowser>()
    private val browserDelegates = mutableMapOf<CastProtocol, BrowserDelegate>()
    private val services = mutableMapOf<String, NSNetService>()
    private val serviceDelegates = mutableMapOf<String, ServiceDelegate>()

    private inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private data class ActiveHandles(
        val browsers: List<NSNetServiceBrowser>,
        val services: List<NSNetService>,
    )

    override fun start(
        onDeviceFound: (CastDevice) -> Unit,
        onDeviceLost: (String) -> Unit,
        onError: (CastError) -> Unit,
    ) {
        val startedGeneration =
            withLock {
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
        dispatch_async(dispatch_get_main_queue()) {
            val browser = NSNetServiceBrowser()
            val delegate = BrowserDelegate(protocol, startedGeneration)
            withLock {
                if (!running || generation != startedGeneration) return@dispatch_async
                browsers[protocol] = browser
                browserDelegates[protocol] = delegate
                browser.delegate = delegate
                browser.searchForServicesOfType(type, LOCAL_DOMAIN)
            }
        }
    }

    private fun onServiceFound(
        protocol: CastProtocol,
        eventGeneration: Long,
        service: NSNetService,
    ) {
        val id = deviceId(protocol, service.name)
        dispatch_async(dispatch_get_main_queue()) {
            withLock {
                if (!running || generation != eventGeneration || services.containsKey(id)) return@dispatch_async
                val delegate = ServiceDelegate(id, protocol, eventGeneration)
                services[id] = service
                serviceDelegates[id] = delegate
                service.delegate = delegate
                service.resolveWithTimeout(RESOLVE_TIMEOUT_SECONDS)
            }
        }
    }

    private fun onServiceResolved(
        id: String,
        protocol: CastProtocol,
        eventGeneration: Long,
        service: NSNetService,
    ) {
        val current =
            withLock {
                if (!running || generation != eventGeneration || services[id] !== service) null else service
            } ?: return
        val host = current.hostName?.takeIf(String::isNotBlank)
        val port = current.port.toInt()
        if (host == null || port <= 0) {
            failService(service, id, eventGeneration)
            return
        }
        val device =
            CastDevice(
                id = id,
                name = friendlyName(current) ?: current.name.takeIf(String::isNotBlank) ?: id,
                protocol = protocol,
                host = host,
                port = port,
            )
        val onDeviceFound =
            withLock {
                if (!running || generation != eventGeneration) null else callbacks?.onDeviceFound
            }
        onDeviceFound?.invoke(device)
    }

    private fun friendlyName(service: NSNetService): String? {
        val txtData = service.TXTRecordData() ?: return null
        val bytes = txtData.bytes?.readBytes(txtData.length.toInt()) ?: return null
        var offset = 0
        while (offset < bytes.size) {
            val length = bytes[offset].toInt() and 0xFF
            offset++
            if (length == 0) continue
            if (offset + length > bytes.size) return null
            val record = bytes.copyOfRange(offset, offset + length).decodeToString()
            offset += length
            if (record.startsWith(FRIENDLY_NAME_KEY, ignoreCase = true)) {
                return record.substring(FRIENDLY_NAME_KEY.length).takeIf(String::isNotBlank)
            }
        }
        return null
    }

    private fun onServiceLost(
        protocol: CastProtocol,
        eventGeneration: Long,
        service: NSNetService,
    ) {
        val id = deviceId(protocol, service.name)
        dispatch_async(dispatch_get_main_queue()) {
            val (onDeviceLost, staleService) =
                withLock {
                    if (!running || generation != eventGeneration) return@dispatch_async
                    val removed = forgetServiceLocked(id)
                    callbacks?.onDeviceLost to removed
                }
            staleService?.let(::stopService)
            onDeviceLost?.invoke(id)
        }
    }

    private fun onServiceFailed(
        id: String,
        eventGeneration: Long,
        service: NSNetService,
    ) {
        failService(service, id, eventGeneration)
    }

    private fun failService(
        service: NSNetService,
        id: String,
        eventGeneration: Long,
    ) {
        val staleService =
            withLock {
                if (!running || generation != eventGeneration || services[id] !== service) return
                forgetServiceLocked(id)
            }
        staleService?.let(::stopService)
    }

    private fun stopService(service: NSNetService) {
        dispatch_async(dispatch_get_main_queue()) {
            service.delegate = null
            service.stop()
        }
    }

    private fun stopBrowser(browser: NSNetServiceBrowser) {
        dispatch_async(dispatch_get_main_queue()) {
            browser.delegate = null
            browser.stop()
        }
    }

    private fun forgetServiceLocked(id: String): NSNetService? {
        serviceDelegates.remove(id)
        return services.remove(id)
    }

    private fun clearLocked(): ActiveHandles {
        running = false
        ++generation
        callbacks = null
        val activeBrowsers = browsers.values.toList()
        val activeServices = services.values.toList()
        browsers.clear()
        browserDelegates.clear()
        services.clear()
        serviceDelegates.clear()
        return ActiveHandles(activeBrowsers, activeServices)
    }

    private fun stopHandles(handles: ActiveHandles) {
        handles.browsers.forEach(::stopBrowser)
        handles.services.forEach(::stopService)
    }

    private fun failDiscovery(eventGeneration: Long) {
        val (onError, handles) =
            withLock {
                if (!running || generation != eventGeneration) return
                val error = callbacks?.onError
                error to clearLocked()
            }
        stopHandles(handles)
        onError?.invoke(CastError.DISCOVERY_FAILED)
    }

    override fun stop() {
        val handles =
            withLock {
                if (!running) return
                clearLocked()
            }
        stopHandles(handles)
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
    ) : NSObject(),
        NSNetServiceBrowserDelegateProtocol {
        @ObjCSignatureOverride
        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didFindService: NSNetService,
            moreComing: Boolean,
        ) = onServiceFound(protocol, eventGeneration, didFindService)

        @ObjCSignatureOverride
        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didRemoveService: NSNetService,
            moreComing: Boolean,
        ) = onServiceLost(protocol, eventGeneration, didRemoveService)

        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didNotSearch: Map<Any?, *>,
        ) = failDiscovery(eventGeneration)
    }

    private inner class ServiceDelegate(
        private val id: String,
        private val protocol: CastProtocol,
        private val eventGeneration: Long,
    ) : NSObject(),
        NSNetServiceDelegateProtocol {
        override fun netServiceDidResolveAddress(sender: NSNetService) = onServiceResolved(id, protocol, eventGeneration, sender)

        override fun netService(
            sender: NSNetService,
            didNotResolve: Map<Any?, *>,
        ) = onServiceFailed(id, eventGeneration, sender)
    }

    private companion object {
        const val CAST_TYPE = "_fcast._tcp."
        const val GOOGLE_CAST_TYPE = "_googlecast._tcp."
        const val LOCAL_DOMAIN = "local."
        const val FRIENDLY_NAME_KEY = "fn="
        const val RESOLVE_TIMEOUT_SECONDS = 5.0
    }
}
