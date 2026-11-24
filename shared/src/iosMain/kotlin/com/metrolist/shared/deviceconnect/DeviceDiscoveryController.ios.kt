@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.metrolist.shared.deviceconnect

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import platform.Foundation.NSData
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSObject
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Network.nw_browse_descriptor_create_bonjour_service
import platform.Network.nw_browse_descriptor_set_include_txt_record
import platform.Network.nw_browse_result_copy_endpoint
import platform.Network.nw_browse_result_copy_txt_record_object
import platform.Network.nw_browse_result_t
import platform.Network.nw_browser_cancel
import platform.Network.nw_browser_create
import platform.Network.nw_browser_set_browse_results_changed_handler
import platform.Network.nw_browser_set_queue
import platform.Network.nw_browser_set_state_changed_handler
import platform.Network.nw_browser_start
import platform.Network.nw_browser_state_failed
import platform.Network.nw_browser_t
import platform.Network.nw_endpoint_get_bonjour_service_domain
import platform.Network.nw_endpoint_get_bonjour_service_name
import platform.Network.nw_endpoint_get_bonjour_service_type
import platform.Network.nw_txt_record_access_key
import platform.Network.nw_txt_record_t
import platform.darwin.dispatch_get_main_queue
import platform.posix.uint8_tVar

private const val BONJOUR_LOCAL_DOMAIN = "local."
private const val BONJOUR_RESOLVE_TIMEOUT_SECONDS = 5.0

actual class DeviceDiscoveryController actual constructor(
    context: Any,
    private val onError: (message: String, throwable: Throwable?) -> Unit,
    private val onClosed: () -> Unit,
    private val onFailure: () -> Unit,
) {
    private val lock = SynchronizedObject()
    private var browser: nw_browser_t? = null
    private var discovering = false
    private val devicesByService = mutableMapOf<String, DiscoveredDevice>()
    private val resolversByService = mutableMapOf<String, Pair<NSNetService, BonjourResolverDelegate>>()

    actual fun registerService(
        deviceId: String,
        deviceName: String,
        port: Int,
        deviceType: DeviceConnectDeviceType,
    ) {
        onError(
            "iOS Device Connect local server is not available; Bonjour registration is disabled until a local listener is implemented.",
            null,
        )
    }

    actual suspend fun unregisterService() {}

    actual fun startDiscovery(
        onFound: (DiscoveredDevice) -> Unit,
        onLost: (DiscoveredDevice) -> Unit,
    ) {
        val shouldStart =
            synchronized(lock) {
                if (discovering) return
                discovering = true
                true
            }
        if (!shouldStart) return

        val descriptor =
            nw_browse_descriptor_create_bonjour_service(
                DeviceConnectManager.SERVICE_TYPE,
                null,
            )
        if (descriptor == null) {
            synchronized(lock) { discovering = false }
            onError("Failed to create iOS Bonjour browse descriptor.", null)
            onFailure()
            return
        }
        nw_browse_descriptor_set_include_txt_record(descriptor, true)

        val createdBrowser = nw_browser_create(descriptor, null)
        if (createdBrowser == null) {
            synchronized(lock) { discovering = false }
            onError("Failed to create iOS Bonjour browser.", null)
            onFailure()
            return
        }

        nw_browser_set_queue(createdBrowser, dispatch_get_main_queue())
        nw_browser_set_state_changed_handler(createdBrowser) { state, _ ->
            if (state == nw_browser_state_failed) {
                onError("iOS Bonjour discovery failed.", null)
                onFailure()
            }
        }
        nw_browser_set_browse_results_changed_handler(createdBrowser) { oldResult, newResult, _ ->
            handleBrowseChange(oldResult, newResult, onFound, onLost)
        }

        synchronized(lock) {
            if (!discovering) {
                nw_browser_cancel(createdBrowser)
                return
            }
            browser = createdBrowser
        }
        nw_browser_start(createdBrowser)
    }

    private fun handleBrowseChange(
        oldResult: nw_browse_result_t?,
        newResult: nw_browse_result_t?,
        onFound: (DiscoveredDevice) -> Unit,
        onLost: (DiscoveredDevice) -> Unit,
    ) {
        val removed = bonjourService(oldResult)
        val added = bonjourService(newResult)
        val removedKey = removed?.serviceName
        val addedKey = added?.serviceName

        if (removed != null && removedKey != null && removedKey != addedKey) {
            val lost =
                synchronized(lock) {
                    resolversByService.remove(removedKey)?.first?.stop()
                    devicesByService.remove(removedKey)
                }
            lost?.let(onLost)
        }

        if (added != null && addedKey != null) {
            val (staleDevice, shouldResolve) =
                synchronized(lock) {
                    val cached = devicesByService[addedKey]
                    val stale = cached?.takeIf { !it.matches(added) }
                    if (stale != null) {
                        resolversByService.remove(addedKey)?.first?.stop()
                        devicesByService.remove(addedKey)
                    }
                    stale to (
                        discovering &&
                            (cached == null || stale != null) &&
                            !resolversByService.containsKey(addedKey)
                    )
                }
            staleDevice?.let(onLost)
            if (shouldResolve) resolve(added, onFound)
        }
    }

    private fun resolve(
        service: BonjourService,
        onFound: (DiscoveredDevice) -> Unit,
    ) {
        val netService = NSNetService(service.domain, service.type, service.serviceName)
        val delegate =
            BonjourResolverDelegate(service) { resolved ->
                finishResolution(service.serviceName, netService, resolved, onFound)
            }
        val accepted =
            synchronized(lock) {
                if (!discovering || devicesByService.containsKey(service.serviceName)) {
                    false
                } else {
                    resolversByService[service.serviceName] = netService to delegate
                    true
                }
            }
        if (!accepted) return
        netService.delegate = delegate
        netService.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        netService.resolveWithTimeout(BONJOUR_RESOLVE_TIMEOUT_SECONDS)
    }

    private fun finishResolution(
        serviceName: String,
        netService: NSNetService,
        device: DiscoveredDevice?,
        onFound: (DiscoveredDevice) -> Unit,
    ) {
        val shouldNotify =
            synchronized(lock) {
                resolversByService.remove(serviceName)?.first?.stop()
                if (device != null && discovering && devicesByService.putIfAbsent(serviceName, device) == null) {
                    true
                } else {
                    false
                }
            }
        if (shouldNotify && device != null) onFound(device)
    }

    private inner class BonjourResolverDelegate(
        private val service: BonjourService,
        private val finish: (DiscoveredDevice?) -> Unit,
    ) : NSObject(),
        NSNetServiceDelegateProtocol {
        override fun netServiceDidResolveAddress(sender: NSNetService) {
            val resolved = resolvedAddress(sender)
            val device =
                resolved?.let { (host, port) ->
                    DiscoveredDevice(
                        serviceName = service.serviceName,
                        deviceId = service.deviceId,
                        deviceName = service.deviceName,
                        host = host,
                        port = port,
                        deviceType = service.deviceType,
                    )
                }
            finish(device)
        }

        override fun netService(
            sender: NSNetService,
            didNotResolve: Map<Any?, *>,
        ) {
            finish(null)
        }

        override fun netServiceDidStop(sender: NSNetService) {
            finish(null)
        }
    }

    actual suspend fun stopDiscovery() {
        val (activeBrowser, activeResolvers) =
            synchronized(lock) {
                discovering = false
                devicesByService.clear()
                val resolvers = resolversByService.values.map { it.first }
                resolversByService.clear()
                (browser to resolvers).also { browser = null }
            }
        activeResolvers.forEach { it.stop() }
        activeBrowser?.let { nw_browser_cancel(it) }
    }

    actual suspend fun close() {
        stopDiscovery()
        onClosed()
    }
}

private data class BonjourService(
    val serviceName: String,
    val type: String,
    val domain: String,
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceConnectDeviceType,
)

private fun DiscoveredDevice.matches(service: BonjourService): Boolean =
    deviceId == service.deviceId &&
        deviceName == service.deviceName &&
        deviceType == service.deviceType

private fun bonjourService(result: nw_browse_result_t?): BonjourService? {
    val browseResult = result ?: return null
    val endpoint = nw_browse_result_copy_endpoint(browseResult) ?: return null
    val serviceName = nw_endpoint_get_bonjour_service_name(endpoint)?.toKString().orEmpty()
    val type = nw_endpoint_get_bonjour_service_type(endpoint)?.toKString().orEmpty()
    val domain = nw_endpoint_get_bonjour_service_domain(endpoint)?.toKString()?.ifBlank { BONJOUR_LOCAL_DOMAIN }.orEmpty()
    if (serviceName.isBlank() || type.isBlank()) return null

    val txt = nw_browse_result_copy_txt_record_object(browseResult)
    val deviceId = txtRecordValue(txt, DEVICE_CONNECT_DEVICE_ID_TXT_KEY).orEmpty()
    if (!isValidDeviceId(deviceId)) return null
    return BonjourService(
        serviceName = serviceName,
        type = type,
        domain = domain,
        deviceId = deviceId,
        deviceName = deviceConnectNameFromTxtRecord { key -> txtRecordValue(txt, key) },
        deviceType = deviceConnectTypeFromTxtRecord { key -> txtRecordValue(txt, key) },
    )
}

private fun resolvedAddress(service: NSNetService): Pair<String, Int>? {
    val port = service.port.toInt().takeIf { it in 1..65535 } ?: return null
    for (value in service.addresses.orEmpty()) {
        val data = value as? NSData ?: continue
        val bytes = data.bytes?.reinterpret<uint8_tVar>()?.readBytes(data.length.toInt()) ?: continue
        if (bytes.size < 8) continue
        val host =
            when (byteValue(bytes, 1)) {
                2 -> {
                    if (bytes.size >= 8) {
                        listOf(4, 5, 6, 7).joinToString(".") { byteValue(bytes, it).toString() }
                    } else {
                        null
                    }
                }

                30 -> {
                    if (bytes.size >= 24) {
                        formatIpv6Address(bytes.copyOfRange(8, 24))
                    } else {
                        null
                    }
                }

                else -> {
                    null
                }
            } ?: continue
        if (isDeviceConnectLanHost(host) && !host.startsWith("fe80:", ignoreCase = true)) {
            return host to port
        }
    }
    return null
}

private fun byteValue(
    bytes: ByteArray,
    index: Int,
): Int = bytes[index].toInt() and 0xff

private fun formatIpv6Address(bytes: ByteArray): String {
    val hextets =
        (0 until 8).map { index ->
            (byteValue(bytes, index * 2) shl 8) or byteValue(bytes, index * 2 + 1)
        }
    var bestStart = -1
    var bestLength = 0
    var currentStart = -1
    var currentLength = 0
    for (index in 0..8) {
        if (index < 8 && hextets[index] == 0) {
            if (currentStart < 0) currentStart = index
            currentLength++
        } else {
            if (currentLength > bestLength) {
                bestStart = currentStart
                bestLength = currentLength
            }
            currentStart = -1
            currentLength = 0
        }
    }
    if (bestLength < 2) return hextets.joinToString(":") { it.toString(16) }
    val before = hextets.take(bestStart).joinToString(":") { it.toString(16) }
    val after = hextets.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
    return "$before::$after"
}

private fun txtRecordValue(
    txt: nw_txt_record_t?,
    key: String,
): String? {
    if (txt == null) return null
    var value: String? = null
    nw_txt_record_access_key(txt, key) { _, _, valuePtr, valueLen ->
        value =
            if (valuePtr == null || valueLen.toInt() <= 0) {
                null
            } else {
                valuePtr.readBytes(valueLen.toInt()).decodeToString()
            }
        true
    }
    return value
}
