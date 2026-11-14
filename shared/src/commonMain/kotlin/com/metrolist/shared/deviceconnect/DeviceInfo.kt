/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.deviceconnect

import com.metrolist.shared.settings.Settings

expect fun getDeviceModelString(): String

internal expect fun getDefaultDeviceName(): String

enum class DeviceConnectDeviceType(
    val wireName: String,
) {
    ANDROID_PHONE("android_phone"),
    DESKTOP("desktop"),
    ANDROID_AUTO("android_auto"),
    WEAR_OS("wear_os"),
    IOS("ios"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWireName(value: String?): DeviceConnectDeviceType = entries.firstOrNull { it.wireName == value } ?: UNKNOWN
    }
}

internal fun selectDeviceConnectDeviceType(
    authenticated: DeviceConnectDeviceType = DeviceConnectDeviceType.UNKNOWN,
    stored: DeviceConnectDeviceType = DeviceConnectDeviceType.UNKNOWN,
    advertised: DeviceConnectDeviceType = DeviceConnectDeviceType.UNKNOWN,
): DeviceConnectDeviceType =
    listOf(authenticated, stored, advertised)
        .firstOrNull { it != DeviceConnectDeviceType.UNKNOWN } ?: DeviceConnectDeviceType.UNKNOWN

fun buildLocalDeviceInfo(deviceType: DeviceConnectDeviceType = DeviceConnectDeviceType.UNKNOWN): DeviceInfoPayload {
    val id =
        Settings.shared.deviceConnectDeviceId.ifBlank {
            val generated =
                DeviceCrypto
                    .secureRandomBytes(9)
                    .toBase64()
                    .replace("/", "-")
                    .replace("+", "_")
            Settings.shared.deviceConnectDeviceId = generated
            generated
        }
    val name = Settings.shared.deviceConnectDeviceName.ifBlank(::getDefaultDeviceName)
    return DeviceInfoPayload(
        deviceId = id,
        deviceName = name,
        model = getDeviceModelString(),
        deviceType = deviceType.wireName,
    )
}
