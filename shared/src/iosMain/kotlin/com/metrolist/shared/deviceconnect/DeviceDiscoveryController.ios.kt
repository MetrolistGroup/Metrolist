@file:OptIn(ExperimentalForeignApi::class)

package com.metrolist.shared.deviceconnect

import com.metrolist.shared.utils.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import platform.Network.nw_browser_state_waiting
import platform.Network.nw_browser_t
import platform.Network.nw_endpoint_get_bonjour_service_domain
import platform.Network.nw_endpoint_get_bonjour_service_name
import platform.Network.nw_endpoint_get_bonjour_service_type
import platform.Network.nw_endpoint_t
import platform.Network.nw_error_get_error_code
import platform.Network.nw_txt_record_access_key
import platform.Network.nw_txt_record_t
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.sockaddr

private const val BONJOUR_RESOLVE_TIMEOUT_SECONDS = 5.0
private const val BONJOUR_RESOLVE_MAX_ATTEMPTS = 3

// kDNSServiceErr_PolicyDenied (TN3179); Kotlin/Native does not expose it.
private const val DNS_SERVICE_ERR_POLICY_DENIED = -65570

// All state lives on the main queue, where NWBrowser and NSNetService deliver their callbacks.
actual class DeviceDiscoveryController actual constructor(
    context: Any,
    private val onError: (message: String, throwable: Throwable?) -> Unit,
    private val onClosed: () -> Unit,
    private val onFailure: () -> Unit,
) {
    private var browser: nw_browser_t = null
    private var onFound: (DiscoveredDevice) -> Unit = {}
    private var onLost: (DiscoveredDevice) -> Unit = {}
    private val devices = mutableMapOf<String, DiscoveredDevice>()
    private val resolvers = mutableMapOf<String, Resolver>()

    // Registration stays off until iOS Device Connect crypto exists; onError here would mark discovery failed.
    actual fun registerService(
        deviceId: String,
        deviceName: String,
        port: Int,
        deviceType: DeviceConnectDeviceType,
    ) {}

    actual suspend fun unregisterService() {}

    actual fun startDiscovery(
        onFound: (DiscoveredDevice) -> Unit,
        onLost: (DiscoveredDevice) -> Unit,
    ) {
        dispatch_async(dispatch_get_main_queue()) {
            if (browser != null) return@dispatch_async
            this.onFound = onFound
            this.onLost = onLost
            val descriptor = nw_browse_descriptor_create_bonjour_service(DeviceConnectManager.SERVICE_TYPE, null)
            nw_browse_descriptor_set_include_txt_record(descriptor, true)
            val created = nw_browser_create(descriptor, null)
            if (created == null) {
                fail("Failed to create iOS Bonjour browser.")
                return@dispatch_async
            }
            browser = created
            nw_browser_set_queue(created, dispatch_get_main_queue())
            nw_browser_set_state_changed_handler(created) { state, error ->
                // Other waiting errors clear on their own once a network path returns.
                val denied =
                    state == nw_browser_state_waiting && error != null && nw_error_get_error_code(error) == DNS_SERVICE_ERR_POLICY_DENIED
                if (browser === created && (state == nw_browser_state_failed || denied)) {
                    fail("iOS Bonjour discovery unavailable (error ${error?.let(::nw_error_get_error_code)}).")
                }
            }
            nw_browser_set_browse_results_changed_handler(created) { old, new, _ ->
                if (browser === created) onBrowseChange(old, new)
            }
            nw_browser_start(created)
        }
    }

    private fun fail(message: String) {
        onError(message, null)
        onFailure()
    }

    private fun onBrowseChange(
        old: nw_browse_result_t,
        new: nw_browse_result_t,
    ) {
        if (old != null && new != null && nw_browse_result_get_changes(old, new) == nw_browse_result_change_identical.toULong()) return
        serviceName(old)?.let(::forget)
        val service = bonjourService(new) ?: return
        forget(service.serviceName)
        resolvers[service.serviceName] =
            Resolver(service) { device ->
                resolvers.remove(service.serviceName)
                device?.let {
                    devices[service.serviceName] = it
                    onFound(it)
                }
            }.also { it.start() }
    }

    private fun forget(serviceName: String) {
        resolvers.remove(serviceName)?.stop()
        devices.remove(serviceName)?.let(onLost)
    }

    actual suspend fun stopDiscovery() {
        withContext(Dispatchers.Main) {
            browser?.let { nw_browser_cancel(it) }
            browser = null
            resolvers.values.forEach { it.stop() }
            resolvers.clear()
            devices.clear()
        }
    }

    actual suspend fun close() {
        stopDiscovery()
        onClosed()
    }
}

private class Resolver(
    private val service: BonjourService,
    private val onDone: (DiscoveredDevice?) -> Unit,
) : NSObject(),
    NSNetServiceDelegateProtocol {
    private var attempt = 0
    private var netService: NSNetService? = null

    fun start() {
        attempt++
        netService =
            NSNetService(service.domain, service.type, service.serviceName).apply {
                delegate = this@Resolver
                scheduleInRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
                resolveWithTimeout(BONJOUR_RESOLVE_TIMEOUT_SECONDS)
            }
    }

    // NSNetService.delegate is unretained, so detach before dropping the resolver.
    fun stop() {
        netService?.delegate = null
        netService?.stop()
        netService = null
    }

    override fun netServiceDidResolveAddress(sender: NSNetService) {
        if (sender !== netService) return
        val hosts = sender.addresses.orEmpty().mapNotNull { (it as? NSData)?.bytes?.reinterpret<sockaddr>()?.let(::numericHost) }
        val host = selectDeviceConnectHost(hosts)
        val port = sender.port.toInt()
        if (host == null || port !in 1..65535) return retry()
        stop()
        onDone(DiscoveredDevice(service.serviceName, service.deviceId, service.deviceName, host, port, service.deviceType))
    }

    @ObjCSignatureOverride
    override fun netService(
        sender: NSNetService,
        didNotResolve: Map<Any?, *>,
    ) {
        if (sender === netService) retry()
    }

    override fun netServiceDidStop(sender: NSNetService) {
        if (sender === netService) retry()
    }

    private fun retry() {
        stop()
        if (attempt < BONJOUR_RESOLVE_MAX_ATTEMPTS) return start()
        Logger.w("DeviceDiscoveryController", "iOS Bonjour resolution failed for ${service.serviceName} after $attempt attempts.")
        onDone(null)
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

// Added results have no old side and removed results have no new side; the native copy calls need nonnull.
private fun endpointServiceName(endpoint: nw_endpoint_t): String? =
    nw_endpoint_get_bonjour_service_name(endpoint)?.toKString()?.takeIf { it.isNotBlank() }

private fun serviceName(result: nw_browse_result_t): String? = result?.let { endpointServiceName(nw_browse_result_copy_endpoint(it)) }

private fun bonjourService(result: nw_browse_result_t): BonjourService? {
    if (result == null) return null
    val endpoint = nw_browse_result_copy_endpoint(result) ?: return null
    val serviceName = endpointServiceName(endpoint) ?: return null
    val type = nw_endpoint_get_bonjour_service_type(endpoint)?.toKString()?.takeIf { it.isNotBlank() } ?: return null
    val domain = nw_endpoint_get_bonjour_service_domain(endpoint)?.toKString()?.takeIf { it.isNotBlank() } ?: "local."
    val txt = nw_browse_result_copy_txt_record_object(result)
    val deviceId = txtRecordValue(txt, DEVICE_CONNECT_DEVICE_ID_TXT_KEY).orEmpty()
    if (!isValidDeviceId(deviceId)) return null
    return BonjourService(
        serviceName = serviceName,
        type = type,
        domain = domain,
        deviceId = deviceId,
        deviceName = deviceConnectNameFromTxtRecord { txtRecordValue(txt, it) },
        deviceType = deviceConnectTypeFromTxtRecord { txtRecordValue(txt, it) },
    )
}

private fun txtRecordValue(
    txt: nw_txt_record_t,
    key: String,
): String? {
    if (txt == null) return null
    var value: String? = null
    nw_txt_record_access_key(txt, key) { _, _, bytes, length ->
        if (bytes != null && length > 0u) value = bytes.readBytes(length.toInt()).decodeToString()
        true
    }
    return value
}
