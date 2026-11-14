package com.metrolist.shared.deviceconnect

actual class DeviceDiscoveryController actual constructor(
    context: Any,
    onError: (message: String, throwable: Throwable?) -> Unit,
    private val onClosed: () -> Unit,
    onFailure: () -> Unit,
) {
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
    ) {}

    actual suspend fun stopDiscovery() {}

    actual suspend fun close() {
        onClosed()
    }
}
