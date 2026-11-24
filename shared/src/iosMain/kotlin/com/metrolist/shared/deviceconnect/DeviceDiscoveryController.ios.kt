@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.metrolist.shared.deviceconnect

import kotlinx.cinterop.toKString
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
import platform.Network.nw_endpoint_get_bonjour_service_name
import platform.Network.nw_endpoint_get_hostname
import platform.Network.nw_endpoint_get_port
import platform.Network.nw_txt_record_access_key
import platform.Network.nw_txt_record_t
import platform.darwin.dispatch_get_main_queue
import platform.posix.uint8_tVar

actual class DeviceDiscoveryController actual constructor(
    context: Any,
    private val onError: (message: String, throwable: Throwable?) -> Unit,
    private val onClosed: () -> Unit,
    private val onFailure: () -> Unit,
) {
    private val lock = Lock()
    private var browser: nw_browser_t? = null
    private var discovering = false
    private val devicesByService = mutableMapOf<String, DiscoveredDevice>()

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
        val removed = discoveredDevice(oldResult)
        val added = discoveredDevice(newResult)
        val removedKey = removed?.serviceName
        val addedKey = added?.serviceName

        if (removed != null && removedKey != null && removedKey != addedKey) {
            val lost =
                synchronized(lock) {
                    if (devicesByService[removedKey] == removed) devicesByService.remove(removedKey) else null
                }
            lost?.let(onLost)
        }

        if (added != null && addedKey != null) {
            val changed =
                synchronized(lock) {
                    val previous = devicesByService.put(addedKey, added)
                    previous != added
                }
            if (changed) onFound(added)
        }
    }

    private fun discoveredDevice(result: nw_browse_result_t?): DiscoveredDevice? {
        val browseResult = result ?: return null
        val endpoint = nw_browse_result_copy_endpoint(browseResult) ?: return null
        val serviceName = nw_endpoint_get_bonjour_service_name(endpoint)?.toKString().orEmpty()
        val host = nw_endpoint_get_hostname(endpoint)?.toKString()?.substringBefore('%').orEmpty()
        val port = nw_endpoint_get_port(endpoint).toInt()
        if (serviceName.isBlank() || !isDeviceConnectLanHost(host) || port !in 1..65535) return null

        val txt = nw_browse_result_copy_txt_record_object(browseResult)
        val deviceId = txtRecordValue(txt, DEVICE_CONNECT_DEVICE_ID_TXT_KEY).orEmpty()
        if (!isValidDeviceId(deviceId)) return null
        val deviceName = deviceConnectNameFromTxtRecord { key -> txtRecordValue(txt, key) }
        val deviceType = deviceConnectTypeFromTxtRecord { key -> txtRecordValue(txt, key) }
        return DiscoveredDevice(
            serviceName = serviceName,
            deviceId = deviceId,
            deviceName = deviceName,
            host = host,
            port = port,
            deviceType = deviceType,
        )
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
                    valuePtr.reinterpret<uint8_tVar>().readBytes(valueLen.toInt()).decodeToString()
                }
            true
        }
        return value
    }

    actual suspend fun stopDiscovery() {
        val activeBrowser =
            synchronized(lock) {
                discovering = false
                devicesByService.clear()
                browser.also { browser = null }
            }
        activeBrowser?.let { nw_browser_cancel(it) }
    }

    actual suspend fun close() {
        stopDiscovery()
        onClosed()
    }
}

private fun kotlinx.cinterop.CPointer<uint8_tVar>.readBytes(length: Int): ByteArray =
    ByteArray(length) { index -> this[index].toByte() }
