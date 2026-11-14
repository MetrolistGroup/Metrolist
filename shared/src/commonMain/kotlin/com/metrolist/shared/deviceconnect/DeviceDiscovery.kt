/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * LAN service discovery (mDNS/NSD). The platform actual uses Android NsdManager /
 * desktop JmDNS. The [context] is the platform application context (Android: Context).
 * [onError] is invoked for asynchronous registration/discovery failures so the caller
 * (the manager) can surface them in the in-app debug log.
 */

package com.metrolist.shared.deviceconnect

internal const val DEVICE_CONNECT_DEVICE_ID_TXT_KEY = "deviceId"
internal const val DEVICE_CONNECT_DEVICE_NAME_TXT_KEY = "deviceName"
internal const val DEVICE_CONNECT_DEVICE_TYPE_TXT_KEY = "deviceType"
private const val DEVICE_CONNECT_DISCOVERY_NAME_MAX_CHARS = 48

internal fun deviceConnectDiscoveryTxtRecords(
    deviceId: String,
    deviceName: String,
    deviceType: DeviceConnectDeviceType,
): Map<String, String> =
    mapOf(
        DEVICE_CONNECT_DEVICE_ID_TXT_KEY to deviceId,
        DEVICE_CONNECT_DEVICE_NAME_TXT_KEY to
            sanitizeDeviceName(deviceName)
                .take(DEVICE_CONNECT_DISCOVERY_NAME_MAX_CHARS)
                .dropLastWhile(Char::isHighSurrogate),
        DEVICE_CONNECT_DEVICE_TYPE_TXT_KEY to deviceType.wireName,
    )

internal fun deviceConnectNameFromTxtRecord(valueFor: (String) -> String?): String =
    sanitizeDeviceName(valueFor(DEVICE_CONNECT_DEVICE_NAME_TXT_KEY).orEmpty())

internal fun deviceConnectTypeFromTxtRecord(valueFor: (String) -> String?): DeviceConnectDeviceType =
    DeviceConnectDeviceType.fromWireName(valueFor(DEVICE_CONNECT_DEVICE_TYPE_TXT_KEY))

expect class DeviceDiscoveryController(
    context: Any,
    onError: (message: String, throwable: Throwable?) -> Unit,
    onClosed: () -> Unit = {},
    onFailure: () -> Unit = {},
) {
    fun registerService(
        deviceId: String,
        deviceName: String,
        port: Int,
        deviceType: DeviceConnectDeviceType = DeviceConnectDeviceType.UNKNOWN,
    )

    suspend fun unregisterService()

    fun startDiscovery(
        onFound: (DiscoveredDevice) -> Unit,
        onLost: (DiscoveredDevice) -> Unit,
    )

    suspend fun stopDiscovery()

    suspend fun close()
}

data class DiscoveredDevice(
    val serviceName: String,
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    val deviceType: DeviceConnectDeviceType = DeviceConnectDeviceType.UNKNOWN,
)
