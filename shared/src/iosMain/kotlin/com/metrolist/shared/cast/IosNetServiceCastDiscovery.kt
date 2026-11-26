@file:OptIn(ExperimentalForeignApi::class)

package com.metrolist.shared.cast

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readBytes
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private const val FCAST_TYPE = "_fcast._tcp."
private const val GOOGLE_CAST_TYPE = "_googlecast._tcp."
private const val LOCAL_DOMAIN = "local."
private const val FRIENDLY_NAME_KEY = "fn="
private const val RESOLVE_TIMEOUT_SECONDS = 5.0

// Kotlin/Native cannot mix an Objective-C superclass with a Kotlin interface, so the delegate is a separate object.
internal class IosNetServiceCastDiscovery : MdnsCastDiscovery {
    private val browser = NetServiceCastBrowser()

    override fun start(
        onDeviceFound: (CastDevice) -> Unit,
        onDeviceLost: (String) -> Unit,
        onError: (CastError) -> Unit,
    ) = browser.start(onDeviceFound, onDeviceLost, onError)

    override fun stop() = browser.stop()
}

// All state lives on the main queue, where browsers and services created there deliver their callbacks.
private class NetServiceCastBrowser :
    NSObject(),
    NSNetServiceBrowserDelegateProtocol,
    NSNetServiceDelegateProtocol {
    private var onDeviceFound: ((CastDevice) -> Unit)? = null
    private var onDeviceLost: ((String) -> Unit)? = null
    private var onError: ((CastError) -> Unit)? = null
    private val browsers = mutableMapOf<NSNetServiceBrowser, CastProtocol>()
    private val services = mutableMapOf<String, Pair<CastProtocol, NSNetService>>()

    fun start(
        onDeviceFound: (CastDevice) -> Unit,
        onDeviceLost: (String) -> Unit,
        onError: (CastError) -> Unit,
    ) {
        dispatch_async(dispatch_get_main_queue()) {
            if (this.onDeviceFound != null) return@dispatch_async
            this.onDeviceFound = onDeviceFound
            this.onDeviceLost = onDeviceLost
            this.onError = onError
            browse(FCAST_TYPE, CastProtocol.FCAST)
            browse(GOOGLE_CAST_TYPE, CastProtocol.GOOGLE_CAST)
        }
    }

    fun stop() {
        dispatch_async(dispatch_get_main_queue()) { stopNow() }
    }

    private fun browse(
        type: String,
        protocol: CastProtocol,
    ) {
        val browser = NSNetServiceBrowser()
        browsers[browser] = protocol
        browser.delegate = this
        browser.searchForServicesOfType(type, LOCAL_DOMAIN)
    }

    private fun stopNow() {
        onDeviceFound = null
        onDeviceLost = null
        onError = null
        browsers.keys.forEach {
            it.delegate = null
            it.stop()
        }
        browsers.clear()
        services.values.forEach { release(it.second) }
        services.clear()
    }

    private fun release(service: NSNetService) {
        service.delegate = null
        service.stop()
    }

    private fun forget(service: NSNetService) {
        services.entries.removeAll { it.value.second === service }
        release(service)
    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(
        browser: NSNetServiceBrowser,
        didFindService: NSNetService,
        moreComing: Boolean,
    ) {
        val protocol = browsers[browser] ?: return
        val id = deviceId(protocol, didFindService.name)
        if (id in services) return
        services[id] = protocol to didFindService
        didFindService.delegate = this
        didFindService.resolveWithTimeout(RESOLVE_TIMEOUT_SECONDS)
    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(
        browser: NSNetServiceBrowser,
        didRemoveService: NSNetService,
        moreComing: Boolean,
    ) {
        val id = deviceId(browsers[browser] ?: return, didRemoveService.name)
        services.remove(id)?.let { release(it.second) }
        onDeviceLost?.invoke(id)
    }

    override fun netServiceBrowser(
        browser: NSNetServiceBrowser,
        didNotSearch: Map<Any?, *>,
    ) {
        // Retire only the failed browser so the other protocol keeps discovering.
        val protocol = browsers.remove(browser) ?: return
        browser.delegate = null
        val lost = services.filterValues { it.first == protocol }.keys
        lost.forEach { id -> services.remove(id)?.let { release(it.second) } }
        val reportLost = onDeviceLost
        val report = onError
        if (browsers.isEmpty()) stopNow()
        lost.forEach { reportLost?.invoke(it) }
        report?.invoke(CastError.DISCOVERY_FAILED)
    }

    override fun netServiceDidResolveAddress(sender: NSNetService) {
        val (id, entry) = services.entries.firstOrNull { it.value.second === sender } ?: return
        val host = sender.hostName?.takeIf(String::isNotBlank)
        val port = sender.port.toInt()
        if (host == null || port <= 0) return forget(sender)
        // Stop after the first address so one service does not report the device repeatedly.
        sender.stop()
        onDeviceFound?.invoke(
            CastDevice(
                id = id,
                name = friendlyName(sender) ?: sender.name,
                protocol = entry.first,
                host = host,
                port = port,
            ),
        )
    }

    @ObjCSignatureOverride
    override fun netService(
        sender: NSNetService,
        didNotResolve: Map<Any?, *>,
    ) = forget(sender)

    private fun friendlyName(service: NSNetService): String? {
        val txt = service.TXTRecordData() ?: return null
        val bytes = txt.bytes?.readBytes(txt.length.toInt()) ?: return null
        var offset = 0
        while (offset < bytes.size) {
            val length = bytes[offset++].toInt() and 0xFF
            if (offset + length > bytes.size) return null
            val record = bytes.decodeToString(offset, offset + length)
            offset += length
            if (record.startsWith(FRIENDLY_NAME_KEY, ignoreCase = true)) {
                return record.substring(FRIENDLY_NAME_KEY.length).takeIf(String::isNotBlank)
            }
        }
        return null
    }

    private fun deviceId(
        protocol: CastProtocol,
        serviceName: String,
    ): String = "${protocol.name.lowercase()}:$serviceName"
}
