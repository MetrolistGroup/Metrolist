@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.metrolist.shared.deviceconnect

import com.metrolist.shared.utils.Logger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import platform.Foundation.NSData
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Network.nw_browse_descriptor_create_bonjour_service
import platform.Network.nw_browse_descriptor_set_include_txt_record
import platform.Network.nw_browse_result_change_identical
import platform.Network.nw_browse_result_copy_endpoint
import platform.Network.nw_browse_result_copy_txt_record_object
import platform.Network.nw_browse_result_get_changes
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
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import platform.posix.uint8_tVar

private const val BONJOUR_LOCAL_DOMAIN = "local."
private const val BONJOUR_RESOLVE_TIMEOUT_SECONDS = 5.0
private const val BONJOUR_RESOLVE_MAX_ATTEMPTS = 3

actual class DeviceDiscoveryController actual constructor(
    context: Any,
    private val onError: (message: String, throwable: Throwable?) -> Unit,
    private val onClosed: () -> Unit,
    private val onFailure: () -> Unit,
) {
    private val lock = SynchronizedObject()
    private var browser: nw_browser_t? = null
    private var discovering = false
    private var session = 0L
    private val devicesByService = mutableMapOf<String, DiscoveredDevice>()
    private val resolversByService = mutableMapOf<String, ActiveResolver>()

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
        val currentSession =
            synchronized(lock) {
                if (discovering) return
                discovering = true
                ++session
            }

        val descriptor =
            nw_browse_descriptor_create_bonjour_service(
                DeviceConnectManager.SERVICE_TYPE,
                null,
            )
        if (descriptor == null) {
            synchronized(lock) {
                if (session == currentSession) discovering = false
            }
            if (isSameSession(currentSession)) {
                onError("Failed to create iOS Bonjour browse descriptor.", null)
                onFailure()
            }
            return
        }
        nw_browse_descriptor_set_include_txt_record(descriptor, true)

        val createdBrowser = nw_browser_create(descriptor, null)
        if (createdBrowser == null) {
            synchronized(lock) {
                if (session == currentSession) discovering = false
            }
            if (isSameSession(currentSession)) {
                onError("Failed to create iOS Bonjour browser.", null)
                onFailure()
            }
            return
        }

        nw_browser_set_queue(createdBrowser, dispatch_get_main_queue())
        nw_browser_set_state_changed_handler(createdBrowser) { state, _ ->
            if (state == nw_browser_state_failed && isCurrentSession(currentSession)) {
                onError("iOS Bonjour discovery failed.", null)
                onFailure()
            }
        }
        nw_browser_set_browse_results_changed_handler(createdBrowser) { oldResult, newResult, _ ->
            handleBrowseChange(oldResult, newResult, onFound, onLost, currentSession)
        }

        synchronized(lock) {
            if (!discovering || session != currentSession) {
                nw_browser_cancel(createdBrowser)
                return
            }
            browser = createdBrowser
        }
        nw_browser_start(createdBrowser)
    }

    private fun isSameSession(currentSession: Long): Boolean = synchronized(lock) { session == currentSession }

    private fun isCurrentSession(currentSession: Long): Boolean = synchronized(lock) { discovering && session == currentSession }

    private fun handleBrowseChange(
        oldResult: nw_browse_result_t?,
        newResult: nw_browse_result_t?,
        onFound: (DiscoveredDevice) -> Unit,
        onLost: (DiscoveredDevice) -> Unit,
        currentSession: Long,
    ) {
        val removed = bonjourService(oldResult)
        val added = bonjourService(newResult)
        val removedKey = removed?.serviceName
        val addedKey = added?.serviceName
        val changes = nw_browse_result_get_changes(oldResult, newResult)
        val inPlaceUpdate =
            removedKey != null && removedKey == addedKey &&
                (changes and nw_browse_result_change_identical.toULong()) == 0uL

        if (removed != null && removedKey != null && removedKey != addedKey) {
            val lost =
                synchronized(lock) {
                    if (!discovering || session != currentSession) {
                        null
                    } else {
                        resolversByService.remove(removedKey)?.let(::stopResolver)
                        devicesByService.remove(removedKey)
                    }
                }
            lost?.let(onLost)
        }

        if (added != null && addedKey != null) {
            val (staleDevice, shouldResolve) =
                synchronized(lock) {
                    if (!discovering || session != currentSession) {
                        null to false
                    } else {
                        val cached = devicesByService[addedKey]
                        val active = resolversByService[addedKey]
                        val identityChanged = active != null && active.service != added
                        val stale = cached?.takeIf { !it.matches(added) || inPlaceUpdate }
                        if (identityChanged) {
                            resolversByService.remove(addedKey)?.let(::stopResolver)
                        }
                        if (stale != null) {
                            devicesByService.remove(addedKey)
                        }
                        stale to
                            (
                                (cached == null || stale != null || identityChanged) &&
                                    !resolversByService.containsKey(addedKey)
                            )
                    }
                }
            staleDevice?.let(onLost)
            if (shouldResolve) resolve(added, onFound, currentSession)
        }
    }

    private fun resolve(
        service: BonjourService,
        onFound: (DiscoveredDevice) -> Unit,
        currentSession: Long,
        attempt: Int = 1,
    ) {
        val netService = NSNetService(service.domain, service.type, service.serviceName)
        val delegate =
            BonjourResolverDelegate(service) { resolver, resolved ->
                finishResolution(service.serviceName, resolver, resolved, attempt, onFound, currentSession)
            }
        val accepted =
            synchronized(lock) {
                if (
                    !discovering ||
                    session != currentSession ||
                    devicesByService.containsKey(service.serviceName) ||
                    resolversByService.containsKey(service.serviceName)
                ) {
                    false
                } else {
                    resolversByService[service.serviceName] =
                        ActiveResolver(service, netService, delegate, attempt, currentSession)
                    true
                }
            }
        if (!accepted) return
        netService.delegate = delegate
        netService.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        netService.resolveWithTimeout(BONJOUR_RESOLVE_TIMEOUT_SECONDS)
    }

    private fun stopResolver(resolver: ActiveResolver) {
        resolver.netService.delegate = null
        resolver.netService.stop()
    }

    private fun finishResolution(
        serviceName: String,
        netService: NSNetService,
        device: DiscoveredDevice?,
        attempt: Int,
        onFound: (DiscoveredDevice) -> Unit,
        currentSession: Long,
    ) {
        val result =
            synchronized(lock) {
                val owner = resolversByService[serviceName]
                if (
                    owner == null ||
                    owner.netService !== netService ||
                    owner.attempt != attempt ||
                    owner.session != currentSession
                ) {
                    return@synchronized null
                }
                resolversByService.remove(serviceName)
                stopResolver(owner)
                when {
                    device != null &&
                        discovering &&
                        session == currentSession &&
                        !devicesByService.containsKey(serviceName) -> {
                        devicesByService[serviceName] = device
                        ResolutionResult.Found(device)
                    }

                    device == null &&
                        discovering &&
                        session == currentSession &&
                        !devicesByService.containsKey(serviceName) &&
                        attempt < BONJOUR_RESOLVE_MAX_ATTEMPTS -> {
                        ResolutionResult.Retry(owner.service, attempt + 1, currentSession)
                    }

                    else -> {
                        ResolutionResult.Failed
                    }
                }
            }
        when (result) {
            is ResolutionResult.Found -> {
                onFound(result.device)
            }

            is ResolutionResult.Retry -> {
                resolve(result.service, onFound, result.session, result.attempt)
            }

            ResolutionResult.Failed -> {
                Logger.w(
                    "DeviceDiscoveryController",
                    "iOS Bonjour resolution failed for $serviceName after $attempt attempt(s).",
                )
            }

            null -> {
                Unit
            }
        }
    }

    private inner class BonjourResolverDelegate(
        private val service: BonjourService,
        private val finish: (NSNetService, DiscoveredDevice?) -> Unit,
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
            finish(sender, device)
        }

        override fun netService(
            sender: NSNetService,
            didNotResolve: Map<Any?, *>,
        ) {
            finish(sender, null)
        }

        override fun netServiceDidStop(sender: NSNetService) {
            finish(sender, null)
        }
    }

    private data class ActiveResolver(
        val service: BonjourService,
        val netService: NSNetService,
        val delegate: BonjourResolverDelegate,
        val attempt: Int,
        val session: Long,
    )

    private sealed interface ResolutionResult {
        data class Found(
            val device: DiscoveredDevice,
        ) : ResolutionResult

        data class Retry(
            val service: BonjourService,
            val attempt: Int,
            val session: Long,
        ) : ResolutionResult

        data object Failed : ResolutionResult
    }

    actual suspend fun stopDiscovery() {
        val (activeBrowser, activeResolvers) =
            synchronized(lock) {
                discovering = false
                session++
                devicesByService.clear()
                val resolvers = resolversByService.values.toList()
                resolversByService.clear()
                (browser to resolvers).also { browser = null }
            }
        activeResolvers.forEach(::stopResolver)
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
    val hosts =
        service.addresses.orEmpty().mapNotNull { value ->
            val data = value as? NSData ?: return@mapNotNull null
            val bytes = data.bytes?.reinterpret<uint8_tVar>()?.readBytes(data.length.toInt()) ?: return@mapNotNull null
            if (bytes.size < 8) return@mapNotNull null
            when (byteValue(bytes, 1)) {
                2 -> {
                    listOf(4, 5, 6, 7).joinToString(".") { byteValue(bytes, it).toString() }
                }

                30 -> {
                    if (bytes.size < 24) {
                        null
                    } else {
                        val address = formatIpv6Address(bytes.copyOfRange(8, 24))
                        if (address.startsWith("fe80:", ignoreCase = true)) {
                            if (bytes.size < 28) {
                                null
                            } else {
                                val scope =
                                    (0..3).fold(0L) { value, index ->
                                        value or (byteValue(bytes, 24 + index).toLong() shl (8 * index))
                                    }
                                if (scope == 0L) null else "$address%$scope"
                            }
                        } else {
                            address
                        }
                    }
                }

                else -> {
                    null
                }
            }
        }
    return selectDeviceConnectHost(hosts)?.let { it to port }
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
